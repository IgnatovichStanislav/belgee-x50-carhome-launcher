package by.carhome.data

/**
 * Car data source. The real implementation (ECARX API / VHAL) replaces the test fake (src/test).
 * The listener is called on the main thread; ~5 Hz is enough.
 */
interface CarDataSource {
    fun observe(listener: (CarState) -> Unit)
    fun stop()
    /** Stops for good and frees the worker thread; call from onDestroy. */
    fun release() = stop()
    /** Switches a heater on/off; no-op where the car is not reachable. */
    fun setHeat(heat: Heat, on: Boolean) {}
}
