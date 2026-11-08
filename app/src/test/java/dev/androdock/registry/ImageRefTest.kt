package dev.androdock.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageRefTest {
    @Test fun dockerHubDefaults() {
        val r = ImageRef.parse("mysql:8.0")
        assertEquals("registry-1.docker.io", r.apiHost)
        assertEquals("library/mysql", r.repository)
        assertEquals("8.0", r.tag)
        assertEquals("mysql:8.0", r.repoTag)
        assertEquals("latest", ImageRef.parse("nginx").tag)
        assertEquals("traefik/whoami:latest", ImageRef.parse("traefik/whoami").repoTag)
    }

    @Test fun otherRegistriesPortsAndDigests() {
        val g = ImageRef.parse("ghcr.io/luraku/pocketdock-azerothcore:worldserver")
        assertEquals("ghcr.io", g.apiHost)
        assertEquals("luraku/pocketdock-azerothcore", g.repository)
        assertEquals("ghcr.io/luraku/pocketdock-azerothcore:worldserver", g.repoTag)
        val local = ImageRef.parse("localhost:5000/app")
        assertEquals("localhost:5000", local.apiHost)
        assertEquals("latest", local.tag)
        val d = ImageRef.parse("alpine@sha256:abc")
        assertEquals("sha256:abc", d.reference)
        assertNull(d.repoTag)
        assertEquals("docker.io", ImageRef.parse("index.docker.io/library/redis:7").registry)
    }
}
