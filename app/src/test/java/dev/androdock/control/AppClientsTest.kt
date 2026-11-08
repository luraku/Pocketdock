package dev.androdock.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppClientsTest {
    @get:Rule val tmp = TemporaryFolder()
    private var clock = 1_000_000L
    private fun store(f: File = File(tmp.root, "clients.json")) = AppClients(f) { clock }

    @Test fun pairingApproveCollectOnce() {
        val c = store()
        var notified = 0
        c.onRequest = { notified++ }
        val r = c.request("Mon appli", setOf(Scope.READ, Scope.RUN))
        assertEquals(1, notified)
        assertEquals(PairRequest.State.PENDING, c.collect(r.id)!!.state)
        c.approve(r.id, setOf(Scope.RUN)) // l'utilisateur retire « read »
        val got = c.collect(r.id)!!
        assertEquals(PairRequest.State.APPROVED, got.state)
        val token = got.token!!
        assertTrue(token.startsWith(AppClients.TOKEN_PREFIX))
        assertEquals(setOf(Scope.RUN), got.grantedScopes)
        assertNull("jeton remis une seule fois", c.collect(r.id))
        val client = c.authenticate(token)!!
        assertTrue(client.has(Scope.RUN))
        assertFalse(client.has(Scope.READ))
        assertNull(c.authenticate(token + "x"))
    }

    @Test fun tokenNotStoredInClear() {
        val f = File(tmp.root, "clients.json")
        val c = store(f)
        val r = c.request("A", setOf(Scope.READ))
        c.approve(r.id, setOf(Scope.READ))
        val token = c.collect(r.id)!!.token!!
        assertFalse(f.readText().contains(token))
        assertNotNull("rechargé depuis le disque", store(f).authenticate(token))
    }

    @Test fun denyRevokeAndExpire() {
        val c = store()
        val r = c.request("A", setOf(Scope.ADMIN))
        c.deny(r.id)
        assertEquals(PairRequest.State.DENIED, c.collect(r.id)!!.state)

        val r2 = c.request("B", setOf(Scope.ADMIN))
        clock += AppClients.PENDING_TTL_MS + 1
        assertEquals(PairRequest.State.EXPIRED, c.pairing(r2.id)!!.state)
        try { c.approve(r2.id, setOf(Scope.ADMIN)); fail() } catch (_: NoSuchElementException) {}

        val r3 = c.request("C", setOf(Scope.ADMIN))
        val client = c.approve(r3.id, setOf(Scope.ADMIN))
        val token = c.collect(r3.id)!!.token!!
        assertTrue("admin vaut tous les droits", c.authenticate(token)!!.has(Scope.MANAGE))
        assertTrue(c.revoke(client.id))
        assertNull(c.authenticate(token))
    }

    @Test fun pendingLimitAndValidation() {
        val c = store()
        repeat(AppClients.MAX_PENDING) { c.request("A$it", setOf(Scope.READ)) }
        try { c.request("trop", setOf(Scope.READ)); fail() } catch (_: IllegalStateException) {}
        try { store().request("  ", setOf(Scope.READ)); fail() } catch (_: IllegalArgumentException) {}
        try { AppClients.parseScopes(listOf("root")); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(setOf(Scope.READ, Scope.ADMIN), AppClients.parseScopes(listOf("read", "ADMIN")))
    }

    @Test fun requiredScopes() {
        fun req(m: String, p: String) = AppClients.requiredScope(m, p.trim('/').split('/'))
        assertEquals(Scope.READ, req("GET", "status"))
        assertEquals(Scope.READ, req("GET", "docker/containers/json"))
        assertEquals(Scope.RUN, req("POST", "containers/run"))
        assertEquals(Scope.RUN, req("POST", "images/pull"))
        assertEquals(Scope.RUN, req("POST", "engine/start"))
        assertEquals(Scope.RUN, req("PUT", "compose/demo"))
        assertEquals(Scope.RUN, req("POST", "compose/demo/up"))
        assertEquals(Scope.MANAGE, req("POST", "compose/demo/down"))
        assertEquals(Scope.MANAGE, req("DELETE", "compose/demo"))
        assertEquals(Scope.MANAGE, req("POST", "docker/containers/abc/stop"))
        assertEquals(Scope.MANAGE, req("DELETE", "docker/containers/abc"))
        assertEquals(Scope.ADMIN, req("POST", "docker/containers/create"))
        assertEquals(Scope.ADMIN, req("POST", "exec"))
        assertEquals(Scope.ADMIN, req("POST", "compose/demo/cmd"))
        assertEquals(Scope.ADMIN, req("POST", "engine/stop"))
        assertEquals(Scope.ADMIN, req("POST", "engine/reset"))
        assertEquals(Scope.ADMIN, req("PATCH", "settings"))
        assertEquals(Scope.ADMIN, req("POST", "machines/dev/exec"))
        assertNull("gestion des applications : jeton principal seulement", req("GET", "clients"))
        assertNull(req("POST", "clients/pending/x/approve"))
    }
}
