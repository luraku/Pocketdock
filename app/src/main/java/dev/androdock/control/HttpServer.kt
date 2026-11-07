package dev.androdock.control

import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val bodyText: String get() = body.decodeToString()
    fun header(name: String) = headers[name.lowercase()]
}

class HttpResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String = "application/json; charset=utf-8",
) {
    companion object {
        fun json(status: Int, text: String) = HttpResponse(status, text.toByteArray())
        fun raw(status: Int, body: ByteArray, contentType: String) = HttpResponse(status, body, contentType)
    }
}

/**
 * Petit serveur HTTP/1.1 (une requête par connexion) écoutant sur la boucle
 * locale. Les gestionnaires sont des fonctions suspendues exécutées sur un pool.
 */
class HttpServer(
    port: Int,
    private val handler: suspend (HttpRequest) -> HttpResponse,
) : AutoCloseable {
    private val server = ServerSocket(port, 32, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "androdock-control").apply { isDaemon = true } }
    val port: Int get() = server.localPort

    init {
        pool.execute {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { serve(s) }
            }
        }
    }

    private fun serve(socket: Socket) = socket.use { s ->
        val response = try {
            val req = parse(BufferedInputStream(s.getInputStream())) ?: return@use
            runBlocking { handler(req) }
        } catch (e: Exception) {
            Log.w("ControlApi", "requête en erreur", e)
            HttpResponse.json(500, """{"error":${jsonString(e.message ?: e.javaClass.simpleName)}}""")
        }
        val out = s.getOutputStream()
        out.write(
            ("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
                "Content-Type: ${response.contentType}\r\n" +
                "Content-Length: ${response.body.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(response.body)
        out.flush()
    }

    private fun parse(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length <= 16 shl 20) { "corps trop volumineux" }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
        return HttpRequest(parts[0].uppercase(), URLDecoder.decode(path, "UTF-8"), query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16 * 1024) error("ligne trop longue")
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"
        503 -> "Service Unavailable"
        else -> if (code >= 500) "Error" else "OK"
    }

    override fun close() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    companion object {
        fun jsonString(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
    }
}
