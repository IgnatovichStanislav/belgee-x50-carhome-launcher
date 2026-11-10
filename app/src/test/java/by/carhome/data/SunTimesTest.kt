package by.carhome.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class SunTimesTest {
    private fun minsk(h: Int, m: Int) = ZonedDateTime.parse("2026-11-10T%02d:%02d:00+03:00[Europe/Minsk]".format(h, m))
        .toInstant().toEpochMilli()

    @Test fun noFixIsNotUsed() {
        assertFalse(SunTimes.usableFix(0.0, 0.0))
        assertFalse(SunTimes.usableFix(0.1, -0.2))
        assertFalse(SunTimes.usableFix(91.0, 27.0))
        assertTrue(SunTimes.usableFix(53.9, 27.56))
        assertTrue(SunTimes.usableFix(-33.9, 18.4))
    }

    @Test fun minskInNovember() {
        val lat = SunTimes.DEFAULT_LAT
        val lon = SunTimes.DEFAULT_LON
        assertTrue(SunTimes.isNight(minsk(7, 30), lat, lon))  // sunrise ~8:25
        assertFalse(SunTimes.isNight(minsk(12, 0), lat, lon))
        assertTrue(SunTimes.isNight(minsk(19, 37), lat, lon)) // sunset ~17:20
    }

    @Test fun zeroZeroWouldStillBeDayAtThatTime() {
        // The bug: with the 0,0 "fix" the same evening looked like day.
        assertFalse(SunTimes.isNight(minsk(19, 37), 0.0, 0.0))
    }
}
