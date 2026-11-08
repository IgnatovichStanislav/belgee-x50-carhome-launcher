package by.carhome.data

import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt
import kotlin.math.sin

/** Values from the home-review5 (normal) and home-review5-active (warnings) mockups. */
object FakeStates {
    fun normal() = CarState(
        wifiSsid = "Home-WiFi", btDevice = "iPhone",
        rangeKm = 450, fuelLiters = 45.4f, tripKm = 3.8f, odometerKm = 25012, serviceKm = 5285,
        speedKmh = 42,
        gear = Gear.D,
        batteryV = 12.4f,
        climate = ClimateState(
            tempLeft = 24.5f, tempRight = null, airflow = Airflow.FACE,
            fanLevel = 3, ac = true, auto = false, recirc = false,
        ),
        carPlayConnected = true, androidAuto = LinkStatus.DISCONNECTED,
        coolantOverheat = false, oilPressureLow = false, gearboxOverheat = false, tireWarning = false,
        beltDriver = true, beltPassenger = false,
        startStop = StartStop.READY,
        autoHold = 1,
        steeringHeat = false, nozzleHeat = false, windshieldHeat = false,
    )

    /** Driver door open, AutoHold holding, steering and windshield heat on, low fuel, coolant warning. */
    fun warning() = normal().copy(
        speedKmh = 0,
        rangeKm = 52, fuelLiters = 7.4f, tripKm = 12.4f,
        coolantOverheat = true,
        beltDriver = false, beltPassenger = true,
        btDevice = null,
        autoHold = 2,
        steeringHeat = true, windshieldHeat = true,
        openings = setOf(Opening.DRIVER),
        climate = normal().climate.copy(airflow = Airflow.WINDOW),
    )

    /** Warning fixture with the climate switched off. */
    fun warningClimateOff() = warning().copy(climate = warning().climate.copy(power = false))
}

/** Fake source: mockup values plus a slow speed animation. */
class FakeCarDataSource : CarDataSource {
    private val handler = Handler(Looper.getMainLooper())
    private var listener: ((CarState) -> Unit)? = null
    private var t = 0.0

    /** Debug: true → the state with warnings. */
    @Volatile
    var warningMode = false

    private val tick = object : Runnable {
        override fun run() {
            t += 0.2
            emit()
            handler.postDelayed(this, 200)
        }
    }

    override fun observe(listener: (CarState) -> Unit) {
        this.listener = listener
        handler.removeCallbacks(tick)
        emit()
        handler.postDelayed(tick, 200)
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        listener = null
    }

    fun toggleWarningMode(): Boolean {
        warningMode = !warningMode
        emit()
        return warningMode
    }

    private fun emit() {
        val base = if (warningMode) FakeStates.warning() else FakeStates.normal()
        val speed = if (warningMode) 0 else (42 + 8 * sin(t * 0.35)).roundToInt()
        listener?.invoke(base.copy(speedKmh = speed))
    }
}
