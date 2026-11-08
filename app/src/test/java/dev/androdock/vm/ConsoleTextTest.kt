package dev.androdock.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleTextTest {
    private val e = "\u001B"

    @Test fun wgetProgressKeepsLastState() {
        val raw = "${e}7 95% #####${e}8${e}[0K\r${e}7100% ##########${e}8${e}[0K"
        assertEquals("100% ##########", ConsoleText.clean(raw))
    }

    @Test fun colorsAreRemoved() {
        assertEquals(" * Starting docker ... [ ok ]", ConsoleText.clean(" * Starting docker ... [ ${e}[32mok${e}[0m ]"))
    }

    @Test fun plainLineUnchanged() {
        assertEquals("df: /tmp/androdock.apkovl.tar.gz: can't find mount point",
            ConsoleText.clean("df: /tmp/androdock.apkovl.tar.gz: can't find mount point"))
    }

    @Test fun emptyAfterCleaningIsNull() {
        assertNull(ConsoleText.clean("${e}[0K\r"))
    }

    @Test fun progressDetection() {
        assertTrue(ConsoleText.isProgress(" 95% #####"))
        assertTrue(ConsoleText.isProgress("100% ##########"))
        assertFalse(ConsoleText.isProgress("df: /tmp/x: can't find mount point"))
        assertFalse(ConsoleText.isProgress("Docker 28.3.3 : 100%ok"))
    }
}
