package dev.androdock

import dev.androdock.compose.ComposeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeManagerTest {
    @Test fun projectNames() {
        assertTrue(ComposeManager.validName("forever"))
        assertTrue(ComposeManager.validName("my_stack-2"))
        assertFalse(ComposeManager.validName("Forever"))
        assertFalse(ComposeManager.validName("../etc"))
        assertFalse(ComposeManager.validName("a b"))
        assertFalse(ComposeManager.validName(""))
        assertFalse(ComposeManager.validName("x; rm -rf /"))
    }

    @Test fun shellQuoting() {
        assertEquals("'up'", ComposeManager.shq("up"))
        assertEquals("'it'\\''s'", ComposeManager.shq("it's"))
        assertEquals("'\$(reboot)'", ComposeManager.shq("\$(reboot)"))
    }
}
