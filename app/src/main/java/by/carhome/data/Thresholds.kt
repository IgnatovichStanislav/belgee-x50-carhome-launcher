package by.carhome.data

/** Warning thresholds: the only place to change them. */
object Thresholds {
    const val TIRE_LOW_BAR = 1.9f
    const val TIRE_HIGH_BAR = 3.2f
    const val TIRE_TEMP_HIGH_C = 90
    const val FUEL_LOW_LITERS = 6.8f // ~15% of 45 L: the cluster lamp was on at 14%
    const val RANGE_LOW_KM = 55
    const val COOLANT_HIGH_C = 105
    const val OIL_HIGH_C = 140
    const val BATTERY_LOW_V = 12.0f
    // Engine off: 11.9 V is a half-charged battery, not an alarm.
    const val BATTERY_LOW_V_ENGINE_OFF = 11.8f
    const val BATTERY_CHARGING_V = 13.2f

    /** Top of the RPM bar scale. */
    const val RPM_BAR_MAX = 6300
}
