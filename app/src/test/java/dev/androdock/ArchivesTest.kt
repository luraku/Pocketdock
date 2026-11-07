package dev.androdock

import dev.androdock.runtime.Archives
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArchivesTest {
    private fun tar(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(file.outputStream()).use { t ->
            for ((name, content) in entries) {
                val b = content.toByteArray()
                t.putArchiveEntry(TarArchiveEntry(name).apply { size = b.size.toLong() })
                t.write(b); t.closeArchiveEntry()
            }
        }
    }

    /** Régression : sur Android /data/data/<pkg> est un lien symbolique vers /data/user/0/<pkg>. */
    @Test fun extractsThroughSymlinkedDestination() {
        val real = Files.createTempDirectory("real").toFile()
        val alias = File(real.parentFile, "alias-" + real.name)
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        try {
            val archive = File.createTempFile("rootfs", ".tar")
            tar(archive, mapOf("./etc/os-release" to "ID=alpine\n", "./bin/busybox" to "elf", "../evil" to "x"))
            val dest = File(alias, "machines/m1/rootfs")
            val n = Archives.extractTar(archive, dest)
            assertEquals(2, n)
            assertEquals("ID=alpine\n", File(real, "machines/m1/rootfs/etc/os-release").readText())
            assertFalse("le chemin ../ ne doit pas sortir de la destination", File(alias, "machines/m1/evil").exists())
        } finally {
            alias.delete(); real.deleteRecursively()
        }
    }

    @Test fun stripComponents() {
        val archive = File.createTempFile("proot-distro", ".tar")
        tar(archive, mapOf("debian-arm64/etc/hostname" to "deb\n", "debian-arm64/bin/sh" to "elf"))
        val dest = Files.createTempDirectory("strip").toFile()
        Archives.extractTar(archive, dest, stripComponents = 1)
        assertTrue(File(dest, "etc/hostname").isFile)
    }
}
