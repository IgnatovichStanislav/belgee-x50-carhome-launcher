package by.carhome.data

enum class Gear { P, R, N, D }

/** Where the air goes; [window] = windshield. */
enum class Airflow(val face: Boolean, val feet: Boolean, val window: Boolean) {
    FACE(true, false, false), FEET(false, true, false), FACE_FEET(true, true, false),
    WINDOW(false, false, true), FACE_WINDOW(true, false, true), FEET_WINDOW(false, true, true),
    ALL(true, true, true),
}

/** Pressure / temperature of one tyre. null = no data (shown as «—»). */
data class Tire(val pressureBar: Float?, val tempC: Int?) {
    val warning: Boolean
        get() = pressureBar != null &&
            (pressureBar < Thresholds.TIRE_LOW_BAR || pressureBar > Thresholds.TIRE_HIGH_BAR) ||
            tempC != null && tempC > Thresholds.TIRE_TEMP_HIGH_C
}

/**
 * Climate. One zone if [tempRight] is null or equals [tempLeft], otherwise two (driver «В» / passenger «П»).
 * [fanLevel] 0..7.
 */
data class ClimateState(
    val tempLeft: Float? = null,
    val tempRight: Float? = null,
    val airflow: Airflow = Airflow.FACE_FEET,
    val fanLevel: Int = 0,
    val ac: Boolean = false,
    val auto: Boolean = false,
    val recirc: Boolean = false,
    // HVAC_FUNC_POWER: the driver can switch the whole climate system off.
    val power: Boolean = true,
) {
    val dualZone: Boolean get() = tempRight != null && tempRight != tempLeft
}

enum class BatteryLevel { LOW, NORMAL, CHARGING }

/** Auto start-stop: OFF = system reset/disabled, READY = armed, STOPPED = engine auto-stopped right now. */
enum class StartStop { OFF, READY, STOPPED }

/**
 * Everything the home screen shows. The source provides raw values only; warnings are derived here
 * from [Thresholds]. null = no value → «—».
 */
data class CarState(
    // top left group
    val outsideTempC: Int? = null,
    val wifiSsid: String? = null,          // null = not connected
    val btDevice: String? = null,          // null = no device
    // top right group
    val rangeKm: Int? = null,
    val fuelLiters: Float? = null,
    val consumptionL100: Float? = null,
    val odometerKm: Int? = null,
    // left tiles
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    // centre
    val tireFL: Tire = Tire(null, null),
    val tireFR: Tire = Tire(null, null),
    val tireRL: Tire = Tire(null, null),
    val tireRR: Tire = Tire(null, null),
    val gear: Gear? = null,
    val pedalPercent: Int? = null,
    // right tiles
    val coolantC: Int? = null,
    val oilC: Int? = null,
    val batteryV: Float? = null,
    // bottom row
    val climate: ClimateState = ClimateState(),
    val carPlayConnected: Boolean = false,
    val androidAuto: LinkStatus = LinkStatus.DISCONNECTED,
    // Car status: warning flags only (the HU gets no coolant/oil/tire numbers). null = unknown.
    val coolantOverheat: Boolean? = null,
    val oilPressureLow: Boolean? = null,
    val gearboxOverheat: Boolean? = null,
    val tireWarning: Boolean? = null,
    // Seat belts: true = buckled, null = unknown.
    val beltDriver: Boolean? = null,
    val beltPassenger: Boolean? = null,
    val startStop: StartStop? = null,
    // Heaters toggled from the home screen (stock shade buttons). null = unknown.
    val steeringHeat: Boolean? = null,
    val nozzleHeat: Boolean? = null,
    val windshieldHeat: Boolean? = null,
    // AutoHold: 0 = off, 1 = armed, 2 = holding. Trip: km since this ignition (head unit boot).
    val autoHold: Int? = null,
    val tripKm: Float? = null,
    // Body: what is open right now (empty = all closed or unknown). Service: km left to maintenance.
    val openings: Set<Opening> = emptySet(),
    val serviceKm: Int? = null,
    // Car day/night signal (follows the cluster backlight / light sensor). null = unknown → keep current theme.
    val night: Boolean? = null,
    // Engine running (SENSOR_TYPE_ENGINE_STATE). Battery threshold depends on it.
    val engineRunning: Boolean? = null,
) {
    val anyCarWarning: Boolean
        get() = coolantOverheat == true || oilPressureLow == true || gearboxOverheat == true || tireWarning == true
    val beltWarning: Boolean get() = beltDriver == false || beltPassenger == false
    val lowFuel: Boolean
        get() = fuelLiters != null && fuelLiters < Thresholds.FUEL_LOW_LITERS ||
            rangeKm != null && rangeKm < Thresholds.RANGE_LOW_KM
    val coolantWarning: Boolean get() = coolantC != null && coolantC >= Thresholds.COOLANT_HIGH_C
    val oilWarning: Boolean get() = oilC != null && oilC >= Thresholds.OIL_HIGH_C
    val batteryLevel: BatteryLevel
        get() = when {
            batteryV == null -> BatteryLevel.NORMAL
            batteryV < (if (engineRunning == false) Thresholds.BATTERY_LOW_V_ENGINE_OFF else Thresholds.BATTERY_LOW_V) -> BatteryLevel.LOW
            batteryV >= Thresholds.BATTERY_CHARGING_V -> BatteryLevel.CHARGING
            else -> BatteryLevel.NORMAL
        }
}

enum class Heat { STEERING, NOZZLE, WINDSHIELD }

enum class Opening(val label: String) {
    DRIVER("дверь водителя"), PASSENGER("дверь пассажира"), REAR_LEFT("задняя левая дверь"),
    REAR_RIGHT("задняя правая дверь"), TRUNK("багажник"), HOOD("капот"),
}

/** Phone projection status shown on the CarPlay / Android Auto cards. */
enum class LinkStatus(val label: String) {
    NOT_INSTALLED("Не установлен"),
    DISCONNECTED("Не подключён"),
    CONNECTING("Подключение…"),
    CONNECTED("Подключён"),
    /** The session dropped or the handshake failed; the receiver app retries by itself. */
    LOST("Нет связи"),
}
