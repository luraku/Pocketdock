package dev.androdock.registry

import android.util.Log
import dev.androdock.docker.DockerClient
import java.io.File

/**
 * Point d'entrée unique pour télécharger une image : téléchargement natif (Android) si possible,
 * sinon `docker pull` dans la VM (couches zstd, registre privé, erreur réseau…).
 */
class ImagePuller(private val docker: DockerClient, cacheDir: File) {
    private val native = NativePuller(docker, File(cacheDir, "pull"))

    data class Outcome(val method: String, val ms: Long, val detail: String)

    suspend fun pull(ref: String, preferNative: Boolean = true, onProgress: (String) -> Unit = {}): Outcome {
        val t0 = System.currentTimeMillis()
        if (preferNative) {
            try {
                val r = native.pull(ref, onProgress)
                val detail = if (r.alreadyPresent) "déjà présente, étiquetée ${r.repoTag}" else "${r.bytes / (1 shl 20)} Mo décompressés sur le téléphone"
                return Outcome("native", System.currentTimeMillis() - t0, detail)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Téléchargement natif impossible pour $ref, repli sur docker pull : ${e.message}")
                onProgress("$ref : docker pull (${e.message})")
            }
        }
        docker.pull(ref) { p -> onProgress("$ref : " + listOfNotNull(p.status, p.progress).joinToString(" ")) }
        return Outcome("docker", System.currentTimeMillis() - t0, "docker pull dans la VM")
    }

    /** Télécharge l'image si elle est absente ; sans effet si elle existe déjà. */
    suspend fun ensure(ref: String, onProgress: (String) -> Unit = {}) {
        if (!docker.imageExists(ref)) pull(ref, onProgress = onProgress)
    }

    companion object { const val TAG = "ImagePuller" }
}
