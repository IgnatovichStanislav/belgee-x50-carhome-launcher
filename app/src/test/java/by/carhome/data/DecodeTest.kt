package by.carhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun autoHoldSwapsArmedAndHolding() {
        assertEquals(0, Decode.autoHold(b(0)))
        assertEquals(2, Decode.autoHold(b(1)))
        assertEquals(1, Decode.autoHold(b(2)))
    }

    @Test fun autoHoldUnknownRawIsOff() {
        assertEquals(0, Decode.autoHold(b(3)))
        assertEquals(0, Decode.autoHold(b(0xff)))
    }

    @Test fun autoHoldNeedsExactlyOneByte() {
        assertNull(Decode.autoHold(null))
        assertNull(Decode.autoHold(b()))
        assertNull(Decode.autoHold(b(1, 0)))
    }

    @Test fun fuelPercentToLitres() {
        assertEquals(51f, Decode.fuelLiters(b(100), 51f)!!, 1e-4f)
        assertEquals(25.5f, Decode.fuelLiters(b(50), 51f)!!, 1e-4f)
        assertEquals(0f, Decode.fuelLiters(b(0), 51f)!!, 1e-4f)
    }

    @Test fun fuelInvalidIsNull() {
        assertNull(Decode.fuelLiters(b(0xff), 51f)) // -1 as a signed byte
        assertNull(Decode.fuelLiters(b(), 51f))
        assertNull(Decode.fuelLiters(null, 51f))
    }

    @Test fun tripFromOdometer() {
        assertEquals(12.4f, Decode.tripKm(25012.4f, 25000f), 0.01f)
        assertEquals(0f, Decode.tripKm(24999f, 25000f), 0f)
    }

    @Test fun bootMinuteIsStableWithinOneBoot() {
        val boot = 1_000_000L * 60_000
        val a = Decode.bootMinute(boot + 5_000, 5_000)
        val b = Decode.bootMinute(boot + 3_600_000 + 20, 3_600_000)
        assertEquals(a, b)
    }

    @Test fun sameBootAllowsOneMinuteDrift() {
        assertTrue(Decode.sameBoot(100, 100))
        assertTrue(Decode.sameBoot(99, 100))
        assertTrue(Decode.sameBoot(101, 100))
        assertFalse(Decode.sameBoot(98, 100))
        assertFalse(Decode.sameBoot(-1, 100))
    }

    @Test fun openHeadunitConnected() {
        assertEquals(true, Decode.ohuConnected("""{"connected":true,"wireless":false,"state":"g"}"""))
        assertEquals(false, Decode.ohuConnected("""{"state":"a", "connected" : false}"""))
        assertNull(Decode.ohuConnected("""{"state":"g"}"""))
        assertNull(Decode.ohuConnected(""))
    }
}
