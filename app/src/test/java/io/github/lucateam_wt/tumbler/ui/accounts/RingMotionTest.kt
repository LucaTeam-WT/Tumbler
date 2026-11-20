package io.github.lucateam_wt.tumbler.ui.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingMotionTest {

    @Test
    fun `start and end progress are one second apart`() {
        assertEquals(0.4f, ringProgressAtStart(12, 30), 1e-6f)
        assertEquals(11f / 30f, ringProgressAtEnd(12, 30), 1e-6f)
    }

    @Test
    fun `a full period starts at one`() {
        assertEquals(1f, ringProgressAtStart(30, 30), 1e-6f)
    }

    @Test
    fun `the last second sweeps down to empty and never below`() {
        assertEquals(0f, ringProgressAtEnd(1, 30), 1e-6f)
        assertEquals(0f, ringProgressAtEnd(0, 30), 1e-6f)
    }

    @Test
    fun `progress stays within zero and one`() {
        assertEquals(1f, ringProgressAtStart(45, 30), 0f)
        assertEquals(0f, ringProgressAtStart(-3, 30), 0f)
    }

    @Test
    fun `the first tick snaps`() {
        assertTrue(ringShouldSnap(null, 12))
    }

    @Test
    fun `a normal one second tick keeps animating`() {
        assertFalse(ringShouldSnap(12, 11))
        assertFalse(ringShouldSnap(2, 1))
    }

    @Test
    fun `a period restart snaps instead of sweeping backwards`() {
        assertTrue(ringShouldSnap(1, 30))
        assertTrue(ringShouldSnap(1, 15))
    }

    @Test
    fun `a gap of several seconds snaps`() {
        assertTrue(ringShouldSnap(20, 15))
    }

    @Test
    fun `an unchanged value snaps`() {
        assertTrue(ringShouldSnap(12, 12))
    }
}
