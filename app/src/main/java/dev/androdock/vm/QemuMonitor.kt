package dev.androdock.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Accès au moniteur texte (HMP) de QEMU, exposé sur 127.0.0.1:[port]. */
class QemuMonitor(private val port: Int) {

    suspend fun command(cmd: String): String = withContext(Dispatchers.IO) {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
            s.soTimeout = 3000
            val input = s.getInputStream()
            readUntilPrompt(input) // bannière
            s.getOutputStream().apply { write("$cmd\n".toByteArray()); flush() }
            readUntilPrompt(input)
                .replace(Regex("\u001b\\[[0-9;]*[A-Za-z]"), "")
                .lines().drop(1).joinToString("\n").removeSuffix("(qemu) ").trim()
        }
    }

    private fun readUntilPrompt(input: java.io.InputStream): String {
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        while (!sb.endsWith("(qemu) ")) {
            val n = try { input.read(buf) } catch (_: java.net.SocketTimeoutException) { break }
            if (n < 0) break
            sb.append(String(buf, 0, n))
        }
        return sb.toString()
    }

    suspend fun addForward(proto: String, hostAddr: String, hostPort: Int, guestPort: Int) =
        command("hostfwd_add n0 $proto:$hostAddr:$hostPort-:$guestPort")

    suspend fun removeForward(proto: String, hostAddr: String, hostPort: Int) =
        command("hostfwd_remove n0 $proto:$hostAddr:$hostPort")

    suspend fun powerdown() = command("system_powerdown")
    suspend fun quit() = runCatching { command("quit") }
}
