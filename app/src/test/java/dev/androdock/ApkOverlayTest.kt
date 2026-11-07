package dev.androdock

import dev.androdock.vm.ApkOverlay
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class ApkOverlayTest {
    /** Les scripts OpenRC doivent être du sh valide : un `depend {` sans parenthèses empêchait le montage du disque. */
    @Test fun initScriptsAreValidShell() {
        val f = File.createTempFile("ovl", ".tar.gz").apply { deleteOnExit() }
        ApkOverlay.write(f, "v3.22", 2375)
        val e = read(f)
        for (name in listOf("etc/init.d/androdock-data", "etc/init.d/androdock-net")) {
            val script = e.getValue(name).second
            assertTrue("$name : depend() attendu", script.contains("\ndepend() {\n"))
            val tmp = File.createTempFile("openrc", ".sh").apply { writeText(script); deleteOnExit() }
            val p = ProcessBuilder("sh", "-n", tmp.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("$name n'est pas du sh valide : $out", 0, p.waitFor())
        }
    }

    private fun read(f: File): Map<String, Pair<String?, String>> {
        val out = mutableMapOf<String, Pair<String?, String>>()
        TarArchiveInputStream(GZIPInputStream(f.inputStream())).use { t ->
            while (true) {
                val e = t.nextEntry ?: break
                out[e.name] = (if (e.isSymbolicLink) e.linkName else null) to (if (e.isFile) t.readBytes().decodeToString() else "")
            }
        }
        return out
    }

    @Test fun overlayContainsDockerSetup() {
        val f = File.createTempFile("ovl", ".tar.gz").apply { deleteOnExit() }
        ApkOverlay.write(f, "v3.22", 2375)
        val e = read(f)
        assertTrue(e.getValue("etc/apk/world").second.lines().contains("docker"))
        assertTrue(e.getValue("etc/apk/repositories").second.contains("/v3.22/community"))
        assertTrue(e.getValue("etc/conf.d/docker").second.contains("tcp://0.0.0.0:2375"))
        assertEquals("/etc/init.d/docker", e.getValue("etc/runlevels/default/docker").first)
        assertEquals("/etc/init.d/androdock-data", e.getValue("etc/runlevels/default/androdock-data").first)
        assertEquals("/etc/init.d/cgroups", e.getValue("etc/runlevels/boot/cgroups").first)
        assertTrue(e.getValue("etc/network/interfaces").second.contains("address 10.0.2.15"))
        assertTrue(e.getValue("etc/resolv.conf").second.contains("nameserver 1.1.1.1"))
        val net = e.getValue("etc/init.d/androdock-net").second
        assertTrue(net.contains("! --dport 2375 -j DNAT --to-destination 127.0.0.1"))
        assertTrue(!net.contains("@DOCKER_PORT@"))
        assertEquals("/etc/init.d/androdock-net", e.getValue("etc/runlevels/default/androdock-net").first)
        assertTrue(e.getValue("etc/apk/world").second.lines().contains("docker-cli-buildx"))
        val svc = e.getValue("etc/init.d/androdock-data").second
        assertTrue(svc.startsWith("#!/sbin/openrc-run"))
        assertTrue(svc.contains("before docker"))
        assertTrue(svc.contains("mkfs.ext4"))
        assertTrue("détection du système de fichiers par e2fsprogs", svc.contains("dumpe2fs -h"))
        assertTrue("échappement Kotlin resté dans le script", !svc.contains("\${'\$'}"))
    }
}
