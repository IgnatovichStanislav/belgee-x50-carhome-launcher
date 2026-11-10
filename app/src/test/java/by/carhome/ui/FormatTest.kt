package by.carhome.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun smallNumbersHaveNoSeparator() {
        assertEquals("0", groupThousands(0))
        assertEquals("999", groupThousands(999))
    }

    @Test fun thousandsAreSplitBySpaces() {
        assertEquals("1 000", groupThousands(1000))
        assertEquals("25 012", groupThousands(25012))
        assertEquals("999 999", groupThousands(999_999))
        assertEquals("1 234 567", groupThousands(1_234_567))
    }

    @Test fun overdueServiceKeepsTheMinus() {
        assertEquals("−1 200", groupThousands(-1200))
        assertEquals("−5", groupThousands(-5))
    }

    @Test fun extremesDoNotOverflow() {
        assertEquals("2 147 483 647", groupThousands(Int.MAX_VALUE))
        assertEquals("−2 147 483 648", groupThousands(Int.MIN_VALUE))
    }

    @Test fun oneDecimalTrip() {
        assertEquals("—", oneDecimal(null))
        assertEquals("0.0", oneDecimal(0f))
        assertEquals("1234.5", oneDecimal(1234.5f))
    }

    @Test fun distanceWithoutDataIsADash() {
        assertEquals("—", distanceText(null))
        assertEquals("25 012", distanceText(25012))
        assertEquals("−1 200", distanceText(-1200))
    }
}
