package dev.androdock.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object Downloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Télécharge [url] vers [dest] (via un fichier .part puis renommage).
     * [onProgress] reçoit une valeur entre 0 et 1, ou -1 si la taille est inconnue.
     */
    suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (dest.exists() && dest.length() > 0) return@withContext
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val request = Request.Builder().url(url).header("User-Agent", "Pocketdock/0.1").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} pour $url")
            val body = resp.body ?: throw IOException("Réponse vide pour $url")
            val total = body.contentLength()
            var done = 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            onProgress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
        }
        if (!part.renameTo(dest)) throw IOException("Impossible de renommer ${part.name}")
        onProgress(1f)
    }
}
