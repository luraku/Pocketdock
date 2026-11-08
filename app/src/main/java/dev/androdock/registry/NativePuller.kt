package dev.androdock.registry

import dev.androdock.docker.DockerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/**
 * Téléchargement « natif » d'une image : le registre est interrogé depuis Android, chaque couche est
 * décompressée par le vrai processeur du téléphone pendant le téléchargement, puis l'image est
 * remise au démon Docker de la VM sous forme d'archive `docker save` non compressée
 * (`POST /images/load`). La VM, émulée, n'a plus de gzip à décompresser : c'était l'essentiel du
 * temps d'un `docker pull` (TSK-030).
 *
 * L'identifiant de l'image est celui d'un `docker pull` (empreinte de la configuration), et chaque
 * couche est vérifiée deux fois : empreinte compressée (digest du manifeste) et non compressée
 * (diff_id de la configuration).
 */
class NativePuller(
    private val docker: DockerClient,
    private val workDir: File,
    private val registry: RegistryClient = RegistryClient(),
    private val parallel: Int = 3,
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Result(val imageId: String, val repoTag: String?, val alreadyPresent: Boolean, val bytes: Long)

    suspend fun pull(input: String, onProgress: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val ref = ImageRef.parse(input)
        val repoTag = ref.repoTag ?: throw UnsupportedImageException("référence par condensé seul : laissée à docker pull")
        onProgress("$ref : manifeste")
        val manifest = registry.manifest(ref)
        for (l in manifest.layers) {
            if (codecOf(l.mediaType) == null) throw UnsupportedImageException("couche ${l.mediaType} non prise en charge")
        }
        val configBytes = registry.blobBytes(ref, manifest.configDigest)
        val imageId = "sha256:" + sha256Hex(configBytes)
        require(imageId == manifest.configDigest) { "empreinte de la configuration incorrecte" }
        if (docker.imageExists(imageId)) {
            docker.tagImage(imageId, repoTag)
            return@withContext Result(imageId, repoTag, alreadyPresent = true, bytes = 0)
        }
        val config = json.parseToJsonElement(String(configBytes)).jsonObject
        val diffIds = config["rootfs"]?.jsonObject?.get("diff_ids")?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw RegistryException("configuration sans rootfs.diff_ids")
        require(diffIds.size == manifest.layers.size) { "nombre de couches incohérent" }

        val dir = File(workDir, imageId.removePrefix("sha256:").take(16)).apply { deleteRecursively(); mkdirs() }
        try {
            val total = manifest.layers.sumOf { maxOf(it.size, 0) }
            val done = AtomicLong()
            val sem = Semaphore(parallel)
            val files = coroutineScope {
                manifest.layers.mapIndexed { i, layer ->
                    async {
                        sem.withPermit {
                            val out = File(dir, "layer-$i.tar")
                            fetchLayer(ref, layer, diffIds[i], out) { n ->
                                val d = done.addAndGet(n)
                                onProgress("$ref : téléchargement et décompression ${mb(d)} / ${mb(total)} Mo")
                            }
                            out
                        }
                    }
                }.awaitAll()
            }
            onProgress("$ref : chargement dans Docker")
            val body = saveArchiveBody(configBytes, imageId, diffIds, files, repoTag)
            docker.loadImage(body)
            Result(imageId, repoTag, alreadyPresent = false, bytes = files.sumOf { it.length() })
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Télécharge une couche en la décompressant, et vérifie ses deux empreintes. */
    private fun fetchLayer(ref: ImageRef, layer: Layer, diffId: String, out: File, progress: (Long) -> Unit) {
        val codec = codecOf(layer.mediaType)!!
        registry.blob(ref, layer.digest).use { resp ->
            val compressedDigest = MessageDigest.getInstance("SHA-256")
            val raw = DigestInputStream(CountingInputStream(resp.body!!.byteStream(), progress), compressedDigest)
            val plain: InputStream = if (codec == Codec.GZIP) GZIPInputStream(raw, 1 shl 16) else raw
            val diffDigest = MessageDigest.getInstance("SHA-256")
            out.outputStream().buffered(1 shl 16).use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = plain.read(buf); if (n < 0) break
                    diffDigest.update(buf, 0, n); o.write(buf, 0, n)
                }
            }
            raw.readBytes() // fin éventuelle du flux compressé (remplissage gzip)
            val got = "sha256:" + compressedDigest.digest().toHex()
            if (got != layer.digest) throw RegistryException("couche corrompue : $got au lieu de ${layer.digest}")
            val gotDiff = "sha256:" + diffDigest.digest().toHex()
            if (gotDiff != diffId) throw RegistryException("couche décompressée incorrecte : $gotDiff au lieu de $diffId")
        }
    }

    private class CountingInputStream(private val inner: InputStream, private val progress: (Long) -> Unit) : InputStream() {
        private var pending = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it.toLong()) }
        private fun count(n: Long) { pending += n; if (pending >= 4 shl 20) { progress(pending); pending = 0 } }
        override fun close() { if (pending > 0) progress(pending); pending = 0; inner.close() }
    }

    private enum class Codec { GZIP, NONE }

    companion object {
        private fun codecOf(mediaType: String): Codec? = when {
            mediaType.endsWith("gzip") -> Codec.GZIP // ...rootfs.diff.tar.gzip, ...layer.v1.tar+gzip
            mediaType.endsWith(".tar") || mediaType.endsWith("layer.v1.tar") -> Codec.NONE
            else -> null // zstd, couches étrangères (Windows) : docker pull
        }

        fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun mb(n: Long) = n / (1 shl 20)

        /** Corps HTTP produisant l'archive `docker save` au fil de l'eau (sans copie intermédiaire). */
        fun saveArchiveBody(config: ByteArray, imageId: String, diffIds: List<String>, layers: List<File>, repoTag: String): RequestBody =
            object : RequestBody() {
                override fun contentType() = "application/x-tar".toMediaType()
                override fun writeTo(sink: BufferedSink) = writeSaveArchive(sink.outputStream(), config, imageId, diffIds, layers, repoTag)
            }

        /**
         * Archive au format `docker save` : manifest.json, configuration et une couche tar non
         * compressée par répertoire nommé d'après son diff_id.
         */
        fun writeSaveArchive(out: OutputStream, config: ByteArray, imageId: String, diffIds: List<String>, layers: List<File>, repoTag: String) {
            val configName = imageId.removePrefix("sha256:") + ".json"
            val layerNames = diffIds.map { it.removePrefix("sha256:") + "/layer.tar" }
            val manifest = buildJsonArray {
                add(buildJsonObject {
                    put("Config", configName)
                    putJsonArray("RepoTags") { add(JsonPrimitive(repoTag)) }
                    putJsonArray("Layers") { layerNames.forEach { add(JsonPrimitive(it)) } }
                })
            }.toString().toByteArray()
            val tar = TarArchiveOutputStream(out)
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            fun bytes(name: String, data: ByteArray) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data); tar.closeArchiveEntry()
            }
            bytes(configName, config)
            val written = HashSet<String>()
            for ((i, f) in layers.withIndex()) {
                if (!written.add(layerNames[i])) continue // couche identique répétée (couche vide)
                tar.putArchiveEntry(TarArchiveEntry(layerNames[i]).apply { size = f.length() })
                f.inputStream().use { it.copyTo(tar, 1 shl 16) }
                tar.closeArchiveEntry()
            }
            bytes("manifest.json", manifest)
            tar.finish()
            tar.flush()
        }
    }
}
