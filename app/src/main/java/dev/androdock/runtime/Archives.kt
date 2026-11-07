package dev.androdock.runtime

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

object Archives {

    /**
     * Extrait une archive .tar(.gz|.xz) dans [dest] en préservant liens
     * symboliques et permissions. Les liens physiques sont recopiés (Android
     * interdit link() dans les données d'application).
     */
    /** Renvoie le nombre d'entrées extraites. */
    fun extractTar(archive: File, dest: File, stripComponents: Int = 0, onEntry: (String) -> Unit = {}): Int {
        var extracted = 0
        dest.mkdirs()
        // Pas de canonicalPath : sur Android /data/data/<pkg> est un lien vers /data/user/0/<pkg>,
        // ce qui ferait rejeter toutes les entrées par la garde ci-dessous.
        val destRoot = dest.absoluteFile.normalize().path.trimEnd('/') + "/"
        val raw: InputStream = BufferedInputStream(archive.inputStream(), 1 shl 16)
        val decompressed = when {
            archive.name.endsWith(".xz") -> XZCompressorInputStream(raw)
            archive.name.endsWith(".gz") || archive.name.endsWith(".tgz") -> GzipCompressorInputStream(raw)
            else -> raw
        }
        val hardLinks = mutableListOf<Pair<File, String>>()
        val dirModes = mutableListOf<Pair<File, Int>>()
        TarArchiveInputStream(decompressed).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val rel = strip(entry.name, stripComponents) ?: continue
                val out = File(dest, rel)
                // Protection contre les chemins du type ../../
                if (!(out.absoluteFile.normalize().path + "/").startsWith(destRoot)) continue
                onEntry(rel)
                extracted++
                when {
                    entry.isDirectory -> {
                        out.mkdirs()
                        dirModes += out to entry.mode
                    }
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        Os.symlink(entry.linkName, out.path)
                    }
                    entry.isLink -> {
                        val target = strip(entry.linkName, stripComponents) ?: continue
                        hardLinks += out to target
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        if (out.exists()) out.delete()
                        out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                        // On garde toujours le droit d'écriture pour le propriétaire.
                        runCatching { Os.chmod(out.path, (entry.mode and "7777".toInt(8)) or "600".toInt(8)) }
                    }
                    else -> { /* périphériques, FIFO : ignorés (impossible sans root) */ }
                }
            }
        }
        for ((link, target) in hardLinks) {
            val src = File(dest, target)
            link.parentFile?.mkdirs()
            if (src.isFile) {
                link.delete()
                src.copyTo(link, overwrite = true)
                runCatching { Os.chmod(link.path, Os.stat(src.path).st_mode and "7777".toInt(8)) }
            }
        }
        for ((dir, mode) in dirModes.asReversed()) {
            runCatching { Os.chmod(dir.path, (mode and "7777".toInt(8)) or "700".toInt(8)) }
        }
        return extracted
    }

    private fun strip(name: String, n: Int): String? {
        val parts = name.trimStart('/').removePrefix("./").split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.size <= n) return null
        return parts.drop(n).joinToString("/")
    }

    /** Écrit une archive .tar.gz simple (utilisée pour l'overlay Alpine de la VM). */
    class TarGzWriter(output: OutputStream) : AutoCloseable {
        private val tar = TarArchiveOutputStream(GZIPOutputStream(output)).apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        }

        fun dir(path: String, mode: Int = "755".toInt(8)) {
            val e = TarArchiveEntry("$path/").apply { this.mode = mode or "40000".toInt(8) }
            tar.putArchiveEntry(e); tar.closeArchiveEntry()
        }

        fun file(path: String, content: String, mode: Int = "644".toInt(8)) {
            val bytes = content.toByteArray()
            val e = TarArchiveEntry(path).apply { size = bytes.size.toLong(); this.mode = mode or "100000".toInt(8) }
            tar.putArchiveEntry(e); tar.write(bytes); tar.closeArchiveEntry()
        }

        fun symlink(path: String, target: String) {
            val e = TarArchiveEntry(path, TarArchiveEntry.LF_SYMLINK).apply { linkName = target }
            tar.putArchiveEntry(e); tar.closeArchiveEntry()
        }

        override fun close() {
            try { tar.finish() } finally { tar.close() }
        }
    }

    @Suppress("unused")
    private fun check(cond: Boolean, msg: String) { if (!cond) throw IOException(msg) }
}
