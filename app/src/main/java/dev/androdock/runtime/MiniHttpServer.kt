package dev.androdock.runtime

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Serveur HTTP minimal (GET uniquement) sur 127.0.0.1.
 * La VM y accède via la passerelle slirp 10.0.2.2 pour récupérer l'overlay
 * Alpine (apkovl) et le modloop au démarrage, sans retélécharger depuis Internet.
 */
class MiniHttpServer(private val files: Map<String, File>) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        thread(name = "androdock-http", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { handle(socket) }
            }
        }
    }

    private fun handle(socket: Socket) = socket.use { s ->
        runCatching {
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return@use
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
            val parts = requestLine.split(' ')
            val path = parts.getOrNull(1)?.substringBefore('?')?.trimStart('/') ?: ""
            val out = s.getOutputStream()
            val file = files[path]
            if (parts[0] != "GET" && parts[0] != "HEAD" || file == null || !file.isFile) {
                out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return@use
            }
            out.write(
                ("HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: ${file.length()}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            if (parts[0] == "GET") file.inputStream().use { it.copyTo(out, 1 shl 16) }
            out.flush()
        }
    }

    override fun close() { runCatching { server.close() } }
}
