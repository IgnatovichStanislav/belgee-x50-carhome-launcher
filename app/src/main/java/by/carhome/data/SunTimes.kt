package by.carhome.data

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Sunrise/sunset for the auto day/night theme. The car's SENSOR_TYPE_DAY_NIGHT reads NIGHT all day
 * on this X50 (checked on 2026-11-08 from 11:00 to 16:00), so the theme follows the sun instead.
 * NOAA "sunrise equation" (accurate to a couple of minutes, plenty for a theme switch).
 */
object SunTimes {
    /** Minsk, used until the head unit has a location fix. */
    const val DEFAULT_LAT = 53.90
    const val DEFAULT_LON = 27.56

    /** True if [utcMillis] is before sunrise or after sunset at (lat, lon). Polar day/night handled. */
    fun isNight(utcMillis: Long, lat: Double, lon: Double): Boolean {
        val dayStart = floor(utcMillis / 86_400_000.0) * 86_400_000.0
        val (rise, set) = riseSetUtc(dayStart, lat, lon) ?: return polarNight(utcMillis, lat)
        return utcMillis < rise || utcMillis > set
    }

    /** Sunrise and sunset (UTC millis) for the UTC day starting at [dayStartUtc]; null in polar day/night. */
    fun riseSetUtc(dayStartUtc: Double, lat: Double, lon: Double): Pair<Double, Double>? {
        val jd = dayStartUtc / 86_400_000.0 + 2440587.5 + 0.5 // Julian day at local noon-ish
        val n = jd - 2451545.0 + 0.0008
        val jStar = n - lon / 360.0
        val m = rad((357.5291 + 0.98560028 * jStar) % 360.0)
        val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = rad((deg(m) + c + 180.0 + 102.9372) % 360.0)
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(rad(23.4397)))
        val cosH = (sin(rad(-0.833)) - sin(rad(lat)) * sin(decl)) / (cos(rad(lat)) * cos(decl))
        if (cosH < -1 || cosH > 1) return null
        val h = deg(acos(cosH)) / 360.0
        fun toMillis(j: Double) = (j - 2440587.5) * 86_400_000.0
        return toMillis(jTransit - h) to toMillis(jTransit + h)
    }

    private fun polarNight(utcMillis: Long, lat: Double): Boolean {
        // Rough: northern winter / southern summer is dark. Irrelevant for Belarus, kept for safety.
        val month = ((utcMillis / 86_400_000L / 30L) % 12L).toInt()
        val winterNorth = month in 9..11 || month in 0..2
        return if (lat >= 0) winterNorth else !winterNorth
    }

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI
}
