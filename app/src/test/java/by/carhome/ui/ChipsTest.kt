package by.carhome.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chips under the car appear only when they carry information. */
class ChipsTest {
    @Test fun autoHoldShownOnlyWhileOn() {
        assertTrue(autoHoldChipShown(1)) // armed
        assertTrue(autoHoldChipShown(2)) // holding
        assertFalse(autoHoldChipShown(0))
        assertFalse(autoHoldChipShown(null))
        assertFalse(autoHoldChipShown(7)) // unknown raw value
    }

    @Test fun beltsShownOnlyForUnbuckledDriver() {
        assertTrue(beltsChipShown(false))
        assertFalse(beltsChipShown(true))
        assertFalse(beltsChipShown(null)) // no data is not a warning
    }
}
