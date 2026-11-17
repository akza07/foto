package fool.akza.foto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the defaults and bounds of the viewer action-wheel hold duration. */
class WheelDelaySettingsTest {

    @Test
    fun `default matches the platform long-press duration so existing behavior is unchanged`() {
        assertEquals(400, AppSettings.DEFAULT_WHEEL_DELAY_MS)
        assertEquals(AppSettings.DEFAULT_WHEEL_DELAY_MS, AppSettings().wheelActivationDelayMs)
    }

    @Test
    fun `range and step allow usable short and long holds`() {
        assertTrue(AppSettings.WHEEL_DELAY_MIN_MS < AppSettings.DEFAULT_WHEEL_DELAY_MS)
        assertTrue(AppSettings.DEFAULT_WHEEL_DELAY_MS < AppSettings.WHEEL_DELAY_MAX_MS)
        assertTrue(
            (AppSettings.WHEEL_DELAY_MAX_MS - AppSettings.WHEEL_DELAY_MIN_MS) %
                AppSettings.WHEEL_DELAY_STEP_MS == 0,
        )
    }

    @Test
    fun `snapping rounds to the nearest step and clamps to the range`() {
        assertEquals(400, AppSettings.snapWheelDelay(412))
        assertEquals(450, AppSettings.snapWheelDelay(426))
        assertEquals(AppSettings.WHEEL_DELAY_MIN_MS, AppSettings.snapWheelDelay(10))
        assertEquals(AppSettings.WHEEL_DELAY_MAX_MS, AppSettings.snapWheelDelay(5000))
    }
}
