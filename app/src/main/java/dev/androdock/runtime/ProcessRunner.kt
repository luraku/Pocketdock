package dev.androdock.runtime

import dev.androdock.machines.ShellCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean, val durationMs: Long)

/** Exécution non interactive (sans PTY) d'une commande, sortie capturée. Utilisé par l'API de contrôle. */
object ProcessRunner {
    private const val MAX_OUTPUT = 1 shl 20 // 1 Mo par flux

    suspend fun run(cmd: ShellCommand, timeoutSec: Long = 300, stdin: String? = null): ExecResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val pb = ProcessBuilder(listOf(cmd.executable) + cmd.args.drop(1)).directory(File(cmd.cwd))
        pb.environment().apply { clear(); putAll(cmd.env) }
        val proc = pb.start()
        coroutineScope {
            val out = async(Dispatchers.IO) { proc.inputStream.readCapped() }
            val err = async(Dispatchers.IO) { proc.errorStream.readCapped() }
            proc.outputStream.use { os -> stdin?.let { os.write(it.toByteArray()) } }
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) proc.destroyForcibly()
            ExecResult(
                exitCode = if (finished) proc.exitValue() else -1,
                stdout = out.await(),
                stderr = err.await(),
                timedOut = !finished,
                durationMs = System.currentTimeMillis() - started,
            )
        }
    }

    private fun java.io.InputStream.readCapped(): String {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            if (buf.size() < MAX_OUTPUT) buf.write(chunk, 0, minOf(n, MAX_OUTPUT - buf.size()))
        }
        return buf.toString(Charsets.UTF_8.name())
    }
}
