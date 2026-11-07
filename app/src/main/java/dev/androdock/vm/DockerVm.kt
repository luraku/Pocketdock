package dev.androdock.vm

import android.content.Context
import android.util.Log
import dev.androdock.docker.DockerClient
import dev.androdock.runtime.Downloader
import dev.androdock.runtime.MiniHttpServer
import dev.androdock.runtime.NativeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

enum class EngineState { STOPPED, PREPARING, BOOTING, RUNNING, STOPPING, ERROR }

data class EngineStatus(
    val state: EngineState = EngineState.STOPPED,
    val message: String = "Arrêté",
    /** Progression 0..1 pendant une étape longue, -1 si indéterminée, null sinon. */
    val progress: Float? = null,
    val dockerVersion: String? = null,
    val forwardedPorts: List<String> = emptyList(),
)

/**
 * Machine virtuelle Linux légère (Alpine + dockerd) exécutée par QEMU.
 *
 * Sur un téléphone non rooté, l'application n'a pas accès à KVM : QEMU
 * émule donc le processeur (TCG). C'est plus lent qu'OrbStack sur un Mac, mais
 * c'est la seule façon d'obtenir un vrai noyau Linux (namespaces, cgroups,
 * overlayfs) indispensable à Docker.
 */
class DockerVm(
    private val context: Context,
    private val runtime: NativeRuntime,
    private val settings: EngineSettings,
) {
    companion object {
        const val TAG = "DockerVm"
        const val DOCKER_PORT = 2375
        const val MONITOR_PORT = 47011
        const val ALPINE_BRANCH = "v3.22"
        private const val NETBOOT =
            "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/releases/aarch64/netboot"
        private const val BOOT_TIMEOUT_MS = 20 * 60 * 1000L
        private const val MAX_LOG_LINES = 2000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _console = MutableStateFlow<List<String>>(emptyList())
    /** Console série de la VM (journal de démarrage du noyau et d'OpenRC). */
    val console: StateFlow<List<String>> = _console.asStateFlow()

    val docker = DockerClient(DOCKER_PORT)
    private val monitor = QemuMonitor(MONITOR_PORT)

    private var process: Process? = null
    private var http: MiniHttpServer? = null
    private var watchJob: Job? = null
    private var forwarder: PortForwarder? = null

    val vmDir = File(context.filesDir, "vm")
    private val bootDir = File(vmDir, "boot-$ALPINE_BRANCH")
    val dataDisk = File(vmDir, "data.img")

    val isRunning: Boolean get() = _status.value.state == EngineState.RUNNING

    fun start() {
        val st = _status.value.state
        if (st == EngineState.STOPPED || st == EngineState.ERROR) setStatus(EngineState.PREPARING, "Préparation…")
        scope.launch { mutex.withLock { doStart() } }
    }

    fun stop() {
        scope.launch { mutex.withLock { doStop() } }
    }

    private fun setStatus(state: EngineState, message: String, progress: Float? = null) {
        _status.update { it.copy(state = state, message = message, progress = progress) }
    }

    private fun appendConsole(line: String) {
        _console.update { old ->
            val next = if (old.size >= MAX_LOG_LINES) old.drop(old.size - MAX_LOG_LINES + 1) else old
            next + line
        }
    }

    private suspend fun doStart() {
        val current = _status.value.state
        if (current == EngineState.RUNNING || current == EngineState.BOOTING) return
        try {
            // Un dockerd est peut-être déjà joignable (VM restée vivante après un redémarrage de l'UI)
            if (process?.isAlive == true && docker.ping()) {
                onReady(); return
            }
            setStatus(EngineState.PREPARING, "Préparation de l'environnement…")
            _console.value = emptyList()
            runtime.prepare()
            if (!runtime.has("qemu-system-aarch64")) error("QEMU n'est pas inclus dans cet APK")

            val kernel = File(bootDir, "vmlinuz-virt")
            val initrd = File(bootDir, "initramfs-virt")
            val modloop = File(bootDir, "modloop-virt")
            for ((i, f) in listOf(kernel, initrd, modloop).withIndex()) {
                Downloader.download("$NETBOOT/${f.name}", f) { p ->
                    setStatus(EngineState.PREPARING, "Téléchargement de ${f.name} (${i + 1}/3)…", p)
                }
            }

            val overlay = File(vmDir, "androdock.apkovl.tar.gz")
            ApkOverlay.write(overlay, ALPINE_BRANCH, DOCKER_PORT)

            val cfg = settings.config.value
            ensureDisk(cfg.diskGb)

            http?.close()
            val server = MiniHttpServer(mapOf("androdock.apkovl.tar.gz" to overlay, "modloop-virt" to modloop))
            http = server
            val hostUrl = "http://10.0.2.2:${server.port}"

            val cmdline = listOf(
                "console=ttyAMA0",
                // IP statique + DNS publics : le DNS virtuel de slirp (10.0.2.3) relaie vers le
                // resolv.conf du préfixe Termux, absent dans l'app. Format : client::passerelle:masque::if:none:dns0:dns1
                "ip=${ApkOverlay.GUEST_IP}::${ApkOverlay.GATEWAY}:255.255.255.0::eth0:none:${ApkOverlay.DNS.joinToString(":")}",
                "modules=loop,squashfs,virtio_net,virtio_blk,ext4",
                "alpine_repo=http://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/main",
                "modloop=$hostUrl/modloop-virt",
                "apkovl=$hostUrl/androdock.apkovl.tar.gz",
                "quiet",
            ).joinToString(" ")

            val cmd = listOf(
                runtime.bin("qemu-system-aarch64").absolutePath,
                "-M", "virt",
                "-cpu", "max,pauth-impdef=on",
                "-accel", "tcg,thread=multi,tb-size=256",
                "-smp", cfg.cpus.toString(),
                "-m", "${cfg.memoryMb}M",
                "-kernel", kernel.absolutePath,
                "-initrd", initrd.absolutePath,
                "-append", cmdline,
                "-drive", "file=${dataDisk.absolutePath},format=raw,if=virtio,cache=writeback,discard=unmap",
                "-netdev", "user,id=n0,hostfwd=tcp:127.0.0.1:$DOCKER_PORT-:$DOCKER_PORT",
                "-device", "virtio-net-pci,netdev=n0,romfile=",
                "-device", "virtio-rng-pci",
                "-display", "none",
                "-serial", "stdio",
                "-monitor", "tcp:127.0.0.1:$MONITOR_PORT,server=on,wait=off",
            )
            Log.i(TAG, "Démarrage : ${cmd.joinToString(" ")}")

            setStatus(EngineState.BOOTING, "Démarrage de la VM…", -1f)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(vmDir)
            pb.environment().putAll(runtime.baseEnv())
            val proc = pb.start()
            process = proc
            startedAt = System.currentTimeMillis()
            lastCpuSample = 0L
            scope.launch {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        appendConsole(line)
                        bootHint(line)?.let { hint ->
                            if (_status.value.state == EngineState.BOOTING) setStatus(EngineState.BOOTING, hint, -1f)
                        }
                    }
                }
            }
            watchJob = scope.launch { watchProcess(proc) }

            // Attente de l'API Docker (le premier démarrage installe les paquets : plusieurs minutes)
            val ok = withTimeoutOrNull(BOOT_TIMEOUT_MS) {
                while (isActive && proc.isAlive) {
                    if (docker.ping()) return@withTimeoutOrNull true
                    delay(2000)
                }
                false
            } ?: false
            if (!ok) {
                val alive = proc.isAlive
                if (alive) proc.destroy()
                error(if (alive) "Délai dépassé en attendant Docker" else "QEMU s'est arrêté (voir la console)")
            }
            onReady()
        } catch (e: Exception) {
            Log.e(TAG, "Échec du démarrage", e)
            cleanup()
            setStatus(EngineState.ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun bootHint(line: String): String? = when {
        "Installing packages" in line || "Fetching" in line -> "Installation des paquets Alpine…"
        "Loading boot drivers" in line -> "Chargement des pilotes…"
        "OpenRC" in line -> "Démarrage des services…"
        "Starting Docker" in line || "docker" in line && "Starting" in line -> "Démarrage de dockerd…"
        else -> null
    }

    private suspend fun onReady() {
        val v = runCatching { docker.version() }.getOrNull()
        _status.update {
            it.copy(
                state = EngineState.RUNNING,
                message = "Docker ${v?.version ?: ""} prêt".replace("  ", " "),
                progress = null,
                dockerVersion = v?.version,
            )
        }
        forwarder?.stop()
        forwarder = PortForwarder(docker, monitor, settings) { ports ->
            _status.update { it.copy(forwardedPorts = ports) }
        }.also { it.start(scope) }
    }

    private suspend fun watchProcess(proc: Process) {
        val code = withContext(Dispatchers.IO) { proc.waitFor() }
        Log.i(TAG, "QEMU terminé (code $code)")
        if (process === proc) {
            cleanup()
            val st = _status.value.state
            if (st == EngineState.RUNNING || st == EngineState.BOOTING) {
                setStatus(EngineState.ERROR, "La VM s'est arrêtée de façon inattendue (code $code)")
            } else if (st != EngineState.ERROR) {
                setStatus(EngineState.STOPPED, "Arrêté")
            }
        }
    }

    private suspend fun doStop() {
        val proc = process ?: run { setStatus(EngineState.STOPPED, "Arrêté"); return }
        setStatus(EngineState.STOPPING, "Arrêt de la VM…", -1f)
        forwarder?.stop()
        // Extinction propre (acpid -> poweroff), puis arrêt forcé si nécessaire.
        runCatching { monitor.powerdown() }
        val exited = withContext(Dispatchers.IO) { proc.waitFor(25, TimeUnit.SECONDS) }
        if (!exited) {
            monitor.quit()
            if (!withContext(Dispatchers.IO) { proc.waitFor(5, TimeUnit.SECONDS) }) proc.destroyForcibly()
        }
        watchJob?.join()
        cleanup()
        setStatus(EngineState.STOPPED, "Arrêté")
    }

    private fun cleanup() {
        forwarder?.stop(); forwarder = null
        http?.close(); http = null
        process?.takeIf { it.isAlive }?.destroy()
        process = null
        _status.update { it.copy(forwardedPorts = emptyList()) }
    }

    private fun ensureDisk(sizeGb: Int) {
        vmDir.mkdirs()
        val wanted = sizeGb.toLong() * 1024 * 1024 * 1024
        // Fichier creux : n'occupe que l'espace réellement écrit par la VM.
        RandomAccessFile(dataDisk, "rw").use { if (it.length() < wanted) it.setLength(wanted) }
    }

    /** Supprime le disque de données (tous les conteneurs, images et volumes). */
    suspend fun resetData() = mutex.withLock {
        if (process != null) doStop()
        dataDisk.delete()
    }

    // ---- métriques du processus QEMU (lues dans /proc, sans dépendance)
    private var lastCpuTicks = 0L
    private var lastCpuSample = 0L
    @Volatile private var lastCpuPercent = 0.0
    private var startedAt = 0L

    /** Instant de démarrage de la VM (ms epoch), 0 si arrêtée. */
    val startedAtMs: Long get() = if (process?.isAlive == true) startedAt else 0L

    private fun qemuPid(): Int? = process?.takeIf { it.isAlive }?.let { p ->
        runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) }.getOrNull()
    }

    /** CPU (% d'un cœur, peut dépasser 100) et mémoire résidente (octets) de QEMU. */
    @Synchronized
    fun qemuMetrics(): Pair<Double, Long>? {
        val pid = qemuPid() ?: return null
        return runCatching {
            val stat = File("/proc/$pid/stat").readText()
            val fields = stat.substringAfterLast(')').trim().split(' ')
            val ticks = fields[11].toLong() + fields[12].toLong() // utime + stime
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastCpuSample != 0L && now > lastCpuSample) {
                val hz = 100.0
                lastCpuPercent = (ticks - lastCpuTicks) / hz / ((now - lastCpuSample) / 1000.0) * 100
            }
            lastCpuTicks = ticks; lastCpuSample = now
            val rssKb = File("/proc/$pid/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
                ?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
            lastCpuPercent to rssKb * 1024
        }.getOrNull()
    }

    fun diskUsageBytes(): Long = runCatching {
        android.system.Os.stat(dataDisk.absolutePath).st_blocks * 512
    }.getOrDefault(0L)
}
