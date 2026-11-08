package dev.androdock.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AccelerationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun absentDeviceMeansSoftware() {
        val p = Acceleration.probe(File(tmp.root, "kvm"))
        assertFalse(p.kvmUsable)
        assertTrue(p.reason.contains("absent"))
        assertEquals(listOf(Accel.TCG), Acceleration.candidates(true, p))
    }

    @Test fun usableDeviceTriesKvmThenFallsBack() {
        val dev = tmp.newFile("kvm")
        val p = Acceleration.probe(dev)
        assertTrue(p.kvmUsable)
        assertEquals(listOf(Accel.KVM, Accel.TCG), Acceleration.candidates(true, p))
        assertEquals("désactivée dans les réglages", listOf(Accel.TCG), Acceleration.candidates(false, p))
    }

    @Test fun qemuArguments() {
        val kvm = Acceleration.qemuArgs(Accel.KVM)
        assertEquals("kvm", kvm[kvm.indexOf("-accel") + 1])
        assertEquals("host", kvm[kvm.indexOf("-cpu") + 1])
        val tcg = Acceleration.qemuArgs(Accel.TCG)
        assertTrue(tcg[tcg.indexOf("-accel") + 1].startsWith("tcg,thread=multi"))
        assertEquals("max,pauth-impdef=on", tcg[tcg.indexOf("-cpu") + 1])
    }
}
