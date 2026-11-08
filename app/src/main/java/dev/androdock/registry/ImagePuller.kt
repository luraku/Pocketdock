package dev.androdock.registry

import android.util.Log
import dev.androdock.docker.DockerClient
import java.io.File

/**
 * Point d'entrée unique pour télécharger une image : `docker pull` dans la VM par défaut ;
 * téléchargement natif (Android) sur demande, avec repli sur `docker pull`.
 *
 * Mesure du scénario forever (TSK-030, run 58) : mysql:8.0 en natif 653 s pour 775 Mo
 * décompressés, contre ~840 s pour tout le `up` (téléchargement compris) avec docker pull au
 * run 35. L'archive non compressée (×3) transite par le réseau slirp de QEMU, lui aussi émulé :
 * le natif est plus lent sur les grosses images. Désactivé par défaut tant que le transfert ne
 * passe pas par le disque de la VM.
 */
class ImagePuller(private val docker: DockerClient, cacheDir: File) {
    private val native = NativePuller(docker, File(cacheDir, "pull"))

    data class Outcome(val method: String, val ms: Long, val detail: String)

    suspend fun pull(ref: String, preferNative: Boolean = false, onProgress: (String) -> Unit = {}): Outcome {
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
