package dev.androdock.docker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class DockerException(val code: Int, message: String) : IOException(message)

/**
 * Client minimal de l'API Docker Engine (v1.41+), exposée par la VM sur
 * 127.0.0.1:[port] grâce à la redirection de port de QEMU.
 */
class DockerClient(port: Int) {
    private val base: HttpUrl = "http://127.0.0.1:$port".toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        // Le démon d'une VM émulée et chargée (ex. initialisation de MySQL) peut mettre plus d'une minute à répondre.
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val streaming = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String, vararg query: Pair<String, String?>): HttpUrl {
        val b = base.newBuilder().addPathSegments(path.trimStart('/'))
        for ((k, v) in query) if (v != null) b.addQueryParameter(k, v)
        return b.build()
    }

    private suspend fun call(request: Request, client: OkHttpClient = http): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp -> resp.checked().body?.string().orEmpty() }
    }

    private fun Response.checked(): Response {
        if (!isSuccessful) {
            val raw = body?.string().orEmpty()
            val msg = runCatching {
                (json.parseToJsonElement(raw) as JsonObject)["message"]?.let { (it as JsonPrimitive).content }
            }.getOrNull() ?: raw.ifBlank { "HTTP $code" }
            throw DockerException(code, msg)
        }
        return this
    }

    private fun get(u: HttpUrl) = Request.Builder().url(u).get().build()
    private fun post(u: HttpUrl, body: String = "") = Request.Builder().url(u).post(body.toRequestBody(jsonType)).build()
    private fun delete(u: HttpUrl) = Request.Builder().url(u).delete().build()

    suspend fun ping(): Boolean = runCatching { call(get(url("_ping"))) == "OK" }.getOrDefault(false)

    suspend fun version(): DockerVersion = json.decodeFromString(call(get(url("version"))))

    suspend fun containers(): List<ContainerSummary> =
        json.decodeFromString(call(get(url("containers/json", "all" to "1"))))

    suspend fun images(): List<ImageSummary> = json.decodeFromString(call(get(url("images/json"))))

    suspend fun volumes(): List<VolumeSummary> =
        json.decodeFromString<VolumeList>(call(get(url("volumes")))).volumes.orEmpty()

    suspend fun start(id: String) { call(post(url("containers/$id/start"))) }
    suspend fun stop(id: String) { call(post(url("containers/$id/stop", "t" to "10"))) }
    suspend fun restart(id: String) { call(post(url("containers/$id/restart", "t" to "10"))) }
    suspend fun removeContainer(id: String) { call(delete(url("containers/$id", "force" to "1", "v" to "1"))) }
    suspend fun removeImage(id: String) { call(delete(url("images/$id", "force" to "1"))) }
    suspend fun removeVolume(name: String) { call(delete(url("volumes/$name", "force" to "1"))) }
    suspend fun prune() {
        call(post(url("containers/prune")))
        call(post(url("images/prune", "filters" to """{"dangling":["true"]}""")))
    }

    // ---- primitives bas niveau (utilisées par ComposeManager)

    suspend fun createContainer(body: JsonObject, name: String? = null): String {
        val created = call(post(url("containers/create", "name" to name), body.toString()))
        return (json.parseToJsonElement(created) as JsonObject)["Id"].let { (it as JsonPrimitive).content }
    }

    /** Dépose une archive tar (éventuellement gzip) dans le conteneur, au chemin donné (qui doit exister). */
    suspend fun putArchive(id: String, path: String, archive: ByteArray) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("containers/$id/archive", "path" to path))
            .put(archive.toRequestBody("application/x-tar".toMediaType())).build()
        streaming.newCall(req).execute().use { it.checked() }
    }

    /** Attend la fin du conteneur et renvoie son code de sortie. */
    suspend fun waitContainer(id: String): Int = withContext(Dispatchers.IO) {
        streaming.newCall(post(url("containers/$id/wait"))).execute().use { resp ->
            val body = resp.checked().body?.string().orEmpty()
            (json.parseToJsonElement(body) as JsonObject)["StatusCode"]?.let { (it as JsonPrimitive).content.toInt() } ?: -1
        }
    }

    suspend fun imageExists(ref: String): Boolean = try {
        call(get(url("images/$ref/json"))); true
    } catch (e: DockerException) {
        if (e.code == 404) false else throw e
    }

    suspend fun ensureImage(ref: String) { if (!imageExists(ref)) pull(ref) }

    /** Journaux bruts (démultiplexés) depuis un instant donné (secondes epoch, 0 = tout). */
    suspend fun logsSince(id: String, since: Long, stderr: Boolean = true): String = withContext(Dispatchers.IO) {
        val req = get(url("containers/$id/logs", "stdout" to "1", "stderr" to if (stderr) "1" else "0", "since" to since.toString()))
        http.newCall(req).execute().use { resp -> demux(resp.checked().body?.bytes() ?: ByteArray(0)) }
    }

    /** Derniers journaux d'un conteneur (stdout + stderr). */
    suspend fun logs(id: String, tail: Int = 500): String = withContext(Dispatchers.IO) {
        val req = get(url("containers/$id/logs", "stdout" to "1", "stderr" to "1", "tail" to tail.toString(), "timestamps" to "0"))
        http.newCall(req).execute().use { resp -> demux(resp.checked().body?.bytes() ?: ByteArray(0)) }
    }

    /** Télécharge une image ; [onProgress] reçoit chaque ligne d'état. */
    suspend fun pull(reference: String, onProgress: (PullProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        val (image, tag) = splitReference(reference)
        val req = post(url("images/create", "fromImage" to image, "tag" to tag))
        streaming.newCall(req).execute().use { resp ->
            val source = resp.checked().body?.source() ?: return@use
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val p = runCatching { json.decodeFromString<PullProgress>(line) }.getOrNull() ?: continue
                if (p.error != null) throw DockerException(500, p.error)
                onProgress(p)
            }
        }
    }

    /** Crée et démarre un conteneur ; télécharge l'image si nécessaire. Renvoie l'identifiant. */
    suspend fun run(spec: RunSpec, onProgress: (PullProgress) -> Unit = {}): String {
        val body = createBody(spec).toString()
        val createUrl = url("containers/create", "name" to spec.name?.takeIf { it.isNotBlank() })
        val created = try {
            call(post(createUrl, body))
        } catch (e: DockerException) {
            if (e.code != 404) throw e
            pull(spec.image, onProgress)
            call(post(createUrl, body))
        }
        val id = (json.parseToJsonElement(created) as JsonObject)["Id"].let { (it as JsonPrimitive).content }
        start(id)
        return id
    }

    private fun createBody(spec: RunSpec): JsonObject = buildJsonObject {
        put("Image", spec.image)
        if (spec.env.isNotEmpty()) putJsonArray("Env") { spec.env.forEach { add(JsonPrimitive(it)) } }
        spec.command?.takeIf { it.isNotBlank() }?.let { cmd ->
            putJsonArray("Cmd") { add(JsonPrimitive("sh")); add(JsonPrimitive("-c")); add(JsonPrimitive(cmd)) }
        }
        val ports = spec.ports.mapNotNull { parsePort(it) }
        if (ports.isNotEmpty()) putJsonObject("ExposedPorts") { ports.forEach { putJsonObject(it.second) {} } }
        putJsonObject("HostConfig") {
            if (ports.isNotEmpty()) putJsonObject("PortBindings") {
                ports.forEach { (host, key) ->
                    put(key, JsonArray(listOf(buildJsonObject { put("HostPort", host) })))
                }
            }
            if (spec.volumes.isNotEmpty()) putJsonArray("Binds") { spec.volumes.forEach { add(JsonPrimitive(it)) } }
            if (spec.restartAlways) putJsonObject("RestartPolicy") { put("Name", "unless-stopped") }
        }
    }

    /** "8080:80/tcp" -> ("8080", "80/tcp") */
    internal fun parsePort(s: String): Pair<String, String>? {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return null
        val proto = trimmed.substringAfter('/', "tcp")
        val mapping = trimmed.substringBefore('/')
        val host = mapping.substringBefore(':')
        val container = mapping.substringAfter(':', host)
        return host to "$container/$proto"
    }

    internal fun splitReference(ref: String): Pair<String, String> {
        val r = ref.trim()
        val lastSlash = r.lastIndexOf('/')
        val colon = r.lastIndexOf(':')
        return if (colon > lastSlash && !r.contains('@')) r.substring(0, colon) to r.substring(colon + 1) else r to "latest"
    }

    companion object {
        /**
         * Les journaux d'un conteneur sans TTY sont multiplexés : trames de
         * 8 octets d'en-tête [flux, 0, 0, 0, taille sur 4 octets big-endian].
         */
        fun demux(data: ByteArray): String {
            if (data.size < 8 || data[0] !in 0..2 || data[1] != 0.toByte() || data[2] != 0.toByte() || data[3] != 0.toByte()) {
                return data.decodeToString()
            }
            val sb = StringBuilder()
            var i = 0
            while (i + 8 <= data.size) {
                val len = ((data[i + 4].toInt() and 0xff) shl 24) or ((data[i + 5].toInt() and 0xff) shl 16) or
                    ((data[i + 6].toInt() and 0xff) shl 8) or (data[i + 7].toInt() and 0xff)
                val end = minOf(data.size, i + 8 + len)
                sb.append(data.decodeToString(i + 8, end))
                i = end
            }
            return sb.toString()
        }
    }
}
