package by.carhome.data

/** Pure decoders for raw car and Open Headunit values; covered by DecodeTest. */
object Decode {
    /** Raw INFO_ID_AUTO_HOLD_SWITCH (0 off, 2 armed, 1 holding) → [CarState.autoHold] (0 off, 1 armed, 2 holding). */
    fun autoHold(raw: ByteArray?): Int? = raw?.takeIf { it.size == 1 }?.let {
        when (it[0].toInt() and 0xff) { 1 -> 2; 2 -> 1; else -> 0 }
    }

    /** Fuel level byte in percent → litres; a negative byte means no data. */
    fun fuelLiters(raw: ByteArray?, tankL: Float): Float? =
        raw?.takeIf { it.size == 1 && it[0] >= 0 }?.let { it[0] / 100f * tankL }

    /** Boot time in whole minutes since the epoch: identifies one ignition cycle. */
    fun bootMinute(nowMs: Long, elapsedMs: Long): Long = (nowMs - elapsedMs) / 60_000

    /** The saved boot is this boot; wall clock and uptime drift gives ±1 min. */
    fun sameBoot(saved: Long, boot: Long): Boolean = saved in boot - 1..boot + 1

    /** Km since the trip start; never negative (odometer glitches at ignition). */
    fun tripKm(odo: Float, startOdo: Float): Float = (odo - startOdo).coerceAtLeast(0f)

    private val CONNECTED = Regex("\"connected\"\\s*:\\s*(true|false)")

    /** `connected` from the ACTION_QUERY_STATE JSON; null when the field is missing. */
    fun ohuConnected(json: String): Boolean? = CONNECTED.find(json)?.groupValues?.get(1)?.toBoolean()
}
