package dev.androdock.runtime

import android.content.Context
import android.system.Os
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Accès aux binaires natifs embarqués (proot, qemu, docker).
 *
 * Ils sont livrés dans nativeLibraryDir sous des noms lib*.so (seul endroit
 * exécutable pour une application ciblant Android 10+). Au démarrage, on crée
 * dans filesDir/runtime des liens symboliques portant les noms attendus :
 *  - runtime/lib/<soname>  -> bibliothèques partagées (LD_LIBRARY_PATH)
 *  - runtime/bin/<outil>   -> exécutables (PATH du shell Docker)
 */
class NativeRuntime(private val context: Context) {

    @Serializable
    private data class Manifest(
        val bins: Map<String, String> = emptyMap(),
        val libs: Map<String, String> = emptyMap(),
    )

    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val runtimeDir = File(context.filesDir, "runtime")
    val libDir = File(runtimeDir, "lib")
    val binDir = File(runtimeDir, "bin")
    val tmpDir = File(context.cacheDir, "tmp")
    val homeDir = File(context.filesDir, "home")

    /** ABI dont les binaires sont embarqués et utilisables sur cet appareil. */
    val abi: String? by lazy {
        val bundled = runCatching { context.assets.list("")?.toList() }.getOrNull().orEmpty()
        android.os.Build.SUPPORTED_ABIS.firstOrNull { "runtime-manifest-$it.json" in bundled }
    }

    /** Architecture Linux correspondante (aarch64, x86_64). */
    val linuxArch: String get() = when (abi ?: android.os.Build.SUPPORTED_ABIS.first()) {
        "x86_64" -> "x86_64"
        else -> "aarch64"
    }

    private val manifestJson = Json { ignoreUnknownKeys = true }

    private val manifest: Manifest? by lazy {
        val a = abi ?: return@lazy null
        runCatching {
            context.assets.open("runtime-manifest-$a.json").use {
                manifestJson.decodeFromString<Manifest>(it.readBytes().decodeToString())
            }
        }.getOrNull()
    }

    @Volatile private var prepared = false

    /** Vrai si l'APK contient les binaires natifs (build fait avec scripts/fetch-native.py). */
    val isBundled: Boolean get() = manifest != null

    fun has(tool: String): Boolean = manifest?.bins?.get(tool)?.let { File(nativeDir, it).exists() } == true

    fun bin(tool: String): File {
        val m = manifest ?: error("Binaires natifs absents de cet APK (lancer scripts/fetch-native.py avant le build)")
        val name = m.bins[tool] ?: error("Outil inconnu : $tool")
        return File(nativeDir, name)
    }

    @Synchronized
    fun prepare() {
        if (prepared) return
        val m = manifest ?: error("Binaires natifs absents de cet APK (lancer scripts/fetch-native.py avant le build)")
        listOf(libDir, binDir, tmpDir, homeDir).forEach { it.mkdirs() }
        for ((soname, stored) in m.libs) link(File(nativeDir, stored), File(libDir, soname))
        for ((tool, stored) in m.bins) link(File(nativeDir, stored), File(binDir, tool))
        prepared = true
    }

    private fun link(target: File, linkFile: File) {
        try {
            if (Os.readlink(linkFile.absolutePath) == target.absolutePath) return
        } catch (_: Exception) { /* lien absent */ }
        linkFile.delete()
        Os.symlink(target.absolutePath, linkFile.absolutePath)
    }

    /** Variables d'environnement communes à tous les processus natifs. */
    fun baseEnv(): MutableMap<String, String> = linkedMapOf(
        "LD_LIBRARY_PATH" to libDir.absolutePath,
        "TMPDIR" to tmpDir.absolutePath,
        "HOME" to homeDir.absolutePath,
        "PATH" to "${binDir.absolutePath}:/system/bin:/system/xbin",
        "LANG" to "C.UTF-8",
    )

    fun prootEnv(): MutableMap<String, String> = baseEnv().apply {
        put("PROOT_LOADER", bin("proot-loader").absolutePath)
        put("PROOT_TMP_DIR", tmpDir.absolutePath)
    }
}
