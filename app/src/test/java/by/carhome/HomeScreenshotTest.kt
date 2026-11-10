package by.carhome

import android.graphics.Bitmap
import android.graphics.Canvas
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import by.carhome.data.CarState
import by.carhome.data.FakeStates
import by.carhome.ui.HomeView
import com.android.resources.Density
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Renders 1760x720 @160dpi through layoutlib (Paparazzi). Full-size PNGs go to /screenshots
 * (Paparazzi downsizes its own snapshots, so the view is drawn into a Bitmap by hand).
 * Night/day = the device night qualifier, i.e. values/ + drawable-nodpi vs values-notnight/ + drawable-notnight-nodpi.
 */
internal fun shoot(p: Paparazzi, name: String, state: CarState) {
    val v = HomeView(p.context)
    v.motionEnabled = false
    v.setClock("12:10", "воскресенье, 8 ноября")
    v.render(state)
    p.snapshot(v, name = name)
    val bmp = Bitmap.createBitmap(1760, 720, Bitmap.Config.ARGB_8888)
    v.draw(Canvas(bmp))
    val out = File("../screenshots").apply { mkdirs() }
    File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private fun config(night: NightMode) = DeviceConfig.NEXUS_5.copy(
    screenWidth = 1760, screenHeight = 720,
    density = Density.MEDIUM, xdpi = 160, ydpi = 160,
    orientation = ScreenOrientation.LANDSCAPE,
    nightMode = night,
)

class HomeNightTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = config(NightMode.NIGHT), theme = "android:Theme.Material.NoActionBar")

    @Test fun normal() = shoot(paparazzi, "home-night", FakeStates.normal())
    @Test fun warning() = shoot(paparazzi, "home-night-warn", FakeStates.warning())
    @Test fun bigNumbers() = shoot(paparazzi, "home-night-big", FakeStates.bigNumbers())
    @Test fun serviceOverdue() = shoot(paparazzi, "home-night-overdue", FakeStates.serviceOverdue())
}

class HomeDayTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = config(NightMode.NOTNIGHT), theme = "android:Theme.Material.Light.NoActionBar")

    @Test fun normal() = shoot(paparazzi, "home-day", FakeStates.normal())
    @Test fun warning() = shoot(paparazzi, "home-day-warn", FakeStates.warningClimateOff())
}
