package dev.androdock.machines

import android.content.Context
import dev.androdock.runtime.Archives
import dev.androdock.runtime.Downloader
import dev.androdock.runtime.FileUtils
import dev.androdock.runtime.NativeRuntime
import dev.androdock.vm.DockerVm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Machine(
    val name: String,
    val distroId: String,
    val distroLabel: String,
    val createdAt: Long,
)

data class MachineInstall(val name: String, val message: String, val progress: Float?)

/** Commande à lancer dans un terminal. args[0] est argv[0]. */
data class ShellCommand(val executable: String, val args: List<String>, val env: Map<String, String>, val cwd: String)

/**
 * Gestion des « machines » Linux : chaque machine est un rootfs complet,
 * exécuté sans privilèges grâce à proot (traduction des appels système via
 * ptrace). Elles partagent le noyau Android, démarrent instantanément et ont
 * accès au moteur Docker de la VM (client docker et DOCKER_HOST préconfigurés).
 */
class MachineManager(private val context: Context, private val runtime: NativeRuntime) {
    private val root = File(context.filesDir, "machines")
    private val cache = File(context.cacheDir, "rootfs")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _machines = MutableStateFlow(load())
    val machines: StateFlow<List<Machine>> = _machines.asStateFlow()

    private val _installing = MutableStateFlow<MachineInstall?>(null)
    val installing: StateFlow<MachineInstall?> = _installing.asStateFlow()

    private fun dir(name: String) = File(root, name)
    fun rootfs(name: String) = File(dir(name), "rootfs")

    private fun load(): List<Machine> = (root.listFiles() ?: emptyArray())
        .mapNotNull { d -> runCatching { json.decodeFromString<Machine>(File(d, "machine.json").readText()) }.getOrNull() }
        .sortedBy { it.createdAt }

    fun isValidName(name: String) = Regex("^[a-z0-9][a-z0-9-]{0,30}$").matches(name) && !dir(name).exists()

    suspend fun create(name: String, distro: Distro, customUrl: String? = null) = withContext(Dispatchers.IO) {
        require(isValidName(name)) { "Nom invalide ou déjà utilisé" }
        val url = customUrl?.takeIf { it.isNotBlank() } ?: distro.urlFor(runtime.linuxArch)
        require(url.startsWith("https://")) { "Une URL https:// est requise" }
        try {
            _installing.value = MachineInstall(name, "Téléchargement de ${distro.label}…", 0f)
            val archive = File(cache, url.substringAfterLast('/').ifBlank { "${distro.id}.tar.gz" })
            Downloader.download(url, archive) { p -> _installing.value = MachineInstall(name, "Téléchargement de ${distro.label}…", p) }

            val target = rootfs(name)
            var count = 0
            val extracted = Archives.extractTar(archive, target, distro.stripComponents) {
                if (++count % 200 == 0) _installing.value = MachineInstall(name, "Extraction ($count fichiers)…", -1f)
            }
            val sh = File(target, "bin/sh")
            check(extracted > 50 && (sh.exists() || java.nio.file.Files.isSymbolicLink(sh.toPath()))) {
                "rootfs invalide : $extracted entrées extraites, /bin/sh absent"
            }
            _installing.value = MachineInstall(name, "Configuration…", -1f)
            configure(target, name)

            val m = Machine(name, distro.id, distro.label, System.currentTimeMillis())
            File(dir(name), "machine.json").writeText(json.encodeToString(m))
            _machines.update { it + m }
        } catch (e: Exception) {
            FileUtils.deleteTree(dir(name).toPath())
            throw e
        } finally {
            _installing.value = null
        }
    }

    /** Réglages rendant la distribution utilisable sous proot. */
    private fun configure(rootfs: File, hostname: String) {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "tmp").apply { mkdirs(); setWritable(true, false) }
        File(rootfs, "root").mkdirs()
        File(rootfs, "usr/local/bin").mkdirs()
        // resolv.conf est souvent un lien vers /run/systemd/... absent sous proot
        File(rootfs, "etc/resolv.conf").apply { delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(rootfs, "etc/hostname").writeText("$hostname\n")
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost $hostname\n::1 localhost ip6-localhost\n")
        // apt essaie de changer d'utilisateur (_apt), ce qui échoue sous proot
        File(rootfs, "etc/apt/apt.conf.d").takeIf { File(rootfs, "etc/apt").isDirectory }?.let {
            it.mkdirs()
            File(it, "01-androdock").writeText("APT::Sandbox::User \"root\";\n")
        }
        // pacman 7 utilise un bac à sable (Landlock) incompatible avec proot
        File(rootfs, "etc/pacman.conf").takeIf { it.isFile }?.let { conf ->
            val text = conf.readText()
            if (!text.contains("DisableSandbox")) conf.writeText(text.replace("[options]", "[options]\nDisableSandbox"))
        }
        File(rootfs, "etc/profile.d").takeIf { it.isDirectory || it.mkdirs() }?.let {
            File(it, "androdock.sh").writeText(
                "export DOCKER_HOST=tcp://127.0.0.1:${DockerVm.DOCKER_PORT}\n" +
                    "export PS1='\\u@\\h:\\w\\$ '\n"
            )
        }
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        FileUtils.deleteTree(dir(name).toPath())
        _machines.update { list -> list.filterNot { it.name == name } }
    }

    fun sizeBytes(name: String): Long = rootfs(name).walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        .filter { it.isFile }.sumOf { it.length() }

    /** Shell interactif dans la machine. */
    fun shell(name: String, command: String? = null): ShellCommand {
        runtime.prepare()
        val rootfs = rootfs(name).absolutePath
        val proot = runtime.bin("proot").absolutePath
        val args = mutableListOf(
            "proot",
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$rootfs/tmp:/dev/shm",
            "-b", "${runtime.bin("docker").absolutePath}:/usr/local/bin/docker",
            "-w", "/root",
        )
        val sdcard = File("/storage/emulated/0")
        if (sdcard.canRead()) args += listOf("-b", "${sdcard.absolutePath}:/mnt/sdcard")
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "DOCKER_HOST=tcp://127.0.0.1:${DockerVm.DOCKER_PORT}",
            "/bin/sh", "-c",
            command ?: "if [ -x /bin/bash ]; then exec /bin/bash -l; else exec /bin/sh -l; fi",
        )
        return ShellCommand(proot, args, runtime.prootEnv() + ("TERM" to "xterm-256color"), runtime.homeDir.absolutePath)
    }

    /** Shell Android avec le client docker dans le PATH. */
    fun dockerShell(): ShellCommand {
        runtime.prepare()
        val env = runtime.baseEnv().apply {
            put("TERM", "xterm-256color")
            put("DOCKER_HOST", "tcp://127.0.0.1:${DockerVm.DOCKER_PORT}")
            put("PS1", "androdock$ ")
        }
        return ShellCommand("/system/bin/sh", listOf("sh"), env, runtime.homeDir.absolutePath)
    }

    /** `docker exec -it` dans un conteneur. */
    fun containerShell(containerId: String): ShellCommand {
        runtime.prepare()
        val env = runtime.baseEnv().apply {
            put("TERM", "xterm-256color")
            put("DOCKER_HOST", "tcp://127.0.0.1:${DockerVm.DOCKER_PORT}")
        }
        return ShellCommand(
            runtime.bin("docker").absolutePath,
            listOf("docker", "exec", "-it", containerId, "sh", "-c", "command -v bash >/dev/null && exec bash || exec sh"),
            env,
            runtime.homeDir.absolutePath,
        )
    }
}
