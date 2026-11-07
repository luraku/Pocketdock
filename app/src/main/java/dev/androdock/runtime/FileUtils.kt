package dev.androdock.runtime

import android.system.Os
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object FileUtils {
    /**
     * Suppression récursive qui ne suit JAMAIS les liens symboliques.
     * Indispensable pour un rootfs Linux : /bin -> /usr/bin y est absolu et
     * pointerait vers le système Android si on le suivait.
     */
    fun deleteTree(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Os.chmod(dir.toString(), "755".toInt(8)) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                runCatching { Files.deleteIfExists(file) }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val units = listOf("Ko", "Mo", "Go", "To")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(java.util.Locale.FRANCE, "%.1f %s", v, units[i])
    }
}
