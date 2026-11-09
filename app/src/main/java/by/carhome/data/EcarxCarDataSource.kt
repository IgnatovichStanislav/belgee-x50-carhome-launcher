package by.carhome.data

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.roundToInt

/**
 * Real car data from the ECARX adapt API (`com.ecarx.xui.adaptapi`, part of the firmware's
 * boot classpath) plus a few raw VHAL vendor properties. Everything is called via reflection,
 * so the app builds without the vendor SDK and degrades to «—» on other devices.
 * Same API and decoding as LunarisApp uses on this platform.
 */
class EcarxCarDataSource(context: Context) : CarDataSource {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("car-data").apply { start() }
    private val worker = Handler(thread.looper)

    private var listener: ((CarState) -> Unit)? = null
    private var last: CarState? = null
    @Volatile private var running = false

    // adapt API objects (resolved after onConnected)
    private var car: Any? = null
    private var carFunction: Any? = null
    private var sensors: Any? = null
    private var propManager: Any? = null
    private var mSensor: Method? = null
    private var mFn1: Method? = null
    private var mFn2: Method? = null
    private var mCfn2: Method? = null
    private var mProp: Method? = null
    private var mAvgFuel: Method? = null
    private var mSetFn: Method? = null
    private val prefs = ctx.getSharedPreferences("trip", Context.MODE_PRIVATE)
    private var tripStart: Float? = null
    private var lastLog = 0L

    override fun observe(listener: (CarState) -> Unit) {
        this.listener = listener
        running = true
        OpenHeadunit.start(ctx)
        worker.post { if (car == null) connect() }
        worker.post(poll)
    }

    override fun stop() {
        running = false
        worker.removeCallbacks(poll)
        listener = null
    }

    override fun release() {
        stop()
        thread.quitSafely()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            val s = try { read() } catch (t: Throwable) { Log.w(TAG, "read failed", t); CarState() }
            if (s != last) {
                last = s
                main.post { listener?.invoke(s) }
            }
            worker.postDelayed(this, POLL_MS)
        }
    }

    // ---------- connection ----------

    private fun connect() {
        try {
            val carCls = Class.forName("com.ecarx.xui.adaptapi.car.Car")
            val c = carCls.getMethod("create", Context::class.java).invoke(null, ctx) ?: return
            car = c
            val watcherCls = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            val watcher = Proxy.newProxyInstance(watcherCls.classLoader, arrayOf(watcherCls)) { _, m, _ ->
                when (m.name) {
                    "onConnected" -> worker.post { onConnected() }
                    "onDisConnected" -> worker.post { carFunction = null; sensors = null; propManager = null }
                }
                null
            }
            c.javaClass.getMethod("registerConnectWatcher", watcherCls).invoke(c, watcher)
            c.javaClass.getMethod("connect").invoke(c)
            Log.i(TAG, "adapt API connect requested")
        } catch (t: Throwable) {
            Log.w(TAG, "ECARX adapt API not available: $t")
        }
    }

    private fun onConnected() {
        val c = car ?: return
        try {
            carFunction = c.javaClass.getMethod("getICarFunction").invoke(c)
            sensors = c.javaClass.getMethod("getSensorManager").invoke(c)
            propManager = runCatching { c.javaClass.getMethod("getCarPropertyManager").invoke(c) }.getOrNull()
            mSensor = sensors?.javaClass?.getMethod("getSensorLatestValue", Int::class.javaPrimitiveType)
            mFn1 = carFunction?.javaClass?.getMethod("getFunctionValue", Int::class.javaPrimitiveType)
            mFn2 = carFunction?.javaClass?.getMethod("getFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            mCfn2 = carFunction?.javaClass?.getMethod("getCustomizeFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            mProp = propManager?.javaClass?.methods?.firstOrNull {
                it.name == "getProperty" && it.parameterTypes.size == 3 && it.parameterTypes[0] == Class::class.java
            }
            mSetFn = carFunction?.javaClass?.getMethod("setFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            mAvgFuel = runCatching { c.javaClass.getMethod("getVehicleAverageFuel") }.getOrNull()
            Log.i(TAG, "adapt API connected (prop manager: ${propManager != null})")
            // probeAllSensors()  // one-shot discovery, results in ihu-info/11-ecarx-sensors-probe.txt
        } catch (t: Throwable) {
            Log.w(TAG, "onConnected failed", t)
        }
    }

    // ---------- low-level readers (null = no data) ----------

    private fun sensor(id: Int): Float? = runCatching {
        (mSensor?.invoke(sensors, id) as? Float)?.takeIf { !it.isNaN() && !it.isInfinite() && it > INVALID_BELOW }
    }.getOrNull()

    private fun fn(id: Int): Int? = runCatching { mFn1?.invoke(carFunction, id) as? Int }.getOrNull()
    private fun fn(id: Int, area: Int): Int? = runCatching { mFn2?.invoke(carFunction, id, area) as? Int }.getOrNull()
    private fun cfn(id: Int, area: Int): Float? = runCatching {
        (mCfn2?.invoke(carFunction, id, area) as? Float)?.takeIf { !it.isNaN() && it > INVALID_BELOW }
    }.getOrNull()

    private fun <T> vhal(cls: Class<T>, id: Int): T? = runCatching {
        val v = mProp?.invoke(propManager, cls, id, 0) ?: return@runCatching null
        @Suppress("UNCHECKED_CAST")
        v.javaClass.getMethod("getValue").invoke(v) as? T
    }.getOrNull()

    private fun vhalBytes(id: Int): ByteArray? = vhal(ByteArray::class.java, id)
    private fun vhalFloat(id: Int): Float? = vhal(java.lang.Float::class.java, id)?.toFloat()

    /** Little-endian unsigned. */
    private fun ByteArray.u(): Int {
        var r = 0
        for (i in indices.reversed()) r = (r shl 8) or (this[i].toInt() and 0xff)
        return r
    }

    // ---------- heaters ----------

    private fun hvacConst(name: String): Int? = runCatching {
        Class.forName("com.ecarx.xui.adaptapi.car.hvac.IHvac").getField(name).getInt(null)
    }.getOrNull()

    private val fnSteering by lazy { hvacConst("HVAC_FUNC_STEERING_WHEEL_HEAT") }
    private val fnNozzle by lazy { hvacConst("HVAC_FUNC_FRONT_NOZZLE_HEATING") }
    private val fnWindshield by lazy { hvacConst("HVAC_FUNC_FRONT_WINDSHIELD_HEATING") }
    private val steeringLow by lazy { hvacConst("STEERING_WHEEL_HEAT_LOW") ?: 1 }

    override fun setHeat(heat: Heat, on: Boolean) {
        worker.post {
            val (id, value) = when (heat) {
                Heat.STEERING -> fnSteering to (if (on) steeringLow else 0)
                Heat.NOZZLE -> fnNozzle to (if (on) 1 else 0)
                Heat.WINDSHIELD -> fnWindshield to (if (on) 1 else 0)
            }
            if (id == null) return@post
            val ok = runCatching { mSetFn?.invoke(carFunction, id, value) }
            Log.i(TAG, "setHeat $heat=$on -> ${ok.getOrNull()} ${ok.exceptionOrNull() ?: ""}")
        }
    }

    /** VHAL door bytes: 1 = closed, 0 = open (checked on the driver and passenger doors). */
    private fun openings(): Set<Opening> {
        var set: MutableSet<Opening>? = null
        for ((id, o) in DOORS) {
            val b = vhalBytes(id)?.takeIf { it.size == 1 } ?: continue
            if (b[0].toInt() == 0) (set ?: mutableSetOf<Opening>().also { set = it }).add(o)
        }
        return set ?: emptySet()
    }

    private fun heatOn(id: Int?): Boolean? = id?.let { fn(it) }?.let { it != 0 }

    /** km since this head-unit boot (= this ignition); start odometer kept in prefs across app restarts. */
    private fun trip(odo: Float?): Float? {
        odo ?: return null
        if (tripStart == null) {
            val boot = Decode.bootMinute(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
            if (!Decode.sameBoot(prefs.getLong("boot", -1), boot)) {
                prefs.edit().putLong("boot", boot).putFloat("odo", odo).apply()
            }
            tripStart = prefs.getFloat("odo", odo)
        }
        return Decode.tripKm(odo, tripStart!!)
    }

    // ---------- state assembly ----------

    private var tick = 0
    private var slow: CarState? = null
    private var mid: CarState? = null
    private var idle: CarState? = null
    private var idleAt = 0L

    /**
     * Tiered polling: every VHAL/adapt-API read costs a binder call plus 3 error log lines in the HAL,
     * so only what moves fast is read at 5 Hz.
     *  - 5 Hz: speed, gear;
     *  - 2.5 Hz: AutoHold, doors, belts, climate, heaters;
     *  - 0.5 Hz: fuel, range, odometer/trip, battery, warnings, service, engine, day/night, Wi-Fi/BT, CarPlay.
     * Signals the car never sends to the HU (RPM, outside air, coolant/oil °C, tyre numbers, throttle,
     * consumption, start-stop) are no longer read at all.
     */
    private fun read(): CarState {
        if (carFunction == null && sensors == null) {
            // Not connected yet: Wi-Fi/BT are binder calls too, refresh them at 0.5 Hz like the slow tier.
            val now = android.os.SystemClock.elapsedRealtime()
            val cached = idle
            if (cached != null && now - idleAt < IDLE_REFRESH_MS) return cached
            idleAt = now
            return CarState(wifiSsid = wifiSsid(), btDevice = btDevice()).also { idle = it }
        }
        val n = tick++

        val s = if (slow == null || n % 10 == 0) readSlow().also { slow = it } else slow!!
        val m = if (mid == null || n % 2 == 0) readMid(s).also { mid = it } else mid!!

        val speed = sensor(S_CAR_SPEED)?.let { (maxOf(it, 0f) * 3.6f).roundToInt() }
        val gear = sensor(S_GEAR)?.toInt()?.let { g ->
            when (g) {
                GEAR_PARK -> Gear.P
                GEAR_REVERSE -> Gear.R
                GEAR_NEUTRAL -> Gear.N
                GEAR_DRIVE, in GEAR_FIRST..GEAR_TENTH -> Gear.D
                else -> null
            }
        }
        return m.copy(speedKmh = speed, gear = gear)
    }

    /** 2.5 Hz tier on top of the latest slow snapshot. */
    private fun readMid(s: CarState): CarState = s.copy(
        climate = climate(),
        beltDriver = belt(S_BELT_DRIVER),
        // No seat-occupancy signal: an empty seat reads UNBUCKLED too, so the UI never shows it as an alarm.
        beltPassenger = belt(S_BELT_PASSENGER),
        steeringHeat = heatOn(fnSteering),
        nozzleHeat = heatOn(fnNozzle),
        windshieldHeat = heatOn(fnWindshield),
        autoHold = Decode.autoHold(vhalBytes(P_AUTO_HOLD)),
        openings = openings(),
    )

    /** 0.5 Hz tier. */
    private fun readSlow(): CarState {
        val fuelL = Decode.fuelLiters(vhalBytes(P_REMAINING_OIL), FUEL_TANK_L)
        val battery = vhalBytes(P_BATTERY_VOL)?.takeIf { it.isNotEmpty() }?.let { it.u() / 10f }
        val odo = sensor(S_ODOMETER)
        val state = CarState(
            wifiSsid = wifiSsid(),
            btDevice = btDevice(),
            rangeKm = sensor(S_RANGE)?.roundToInt(),
            fuelLiters = fuelL,
            odometerKm = odo?.roundToInt(),
            tripKm = trip(odo),
            batteryV = battery,
            carPlayConnected = carPlayConnected(),
            coolantOverheat = warn(S_WARN_COOLANT_HIGH),
            oilPressureLow = warn(S_WARN_OIL_PRESSURE),
            gearboxOverheat = warn(S_WARN_TRANSMISSION_HIGH),
            tireWarning = sensor(T_TPMS_SYS_STATES)?.toInt()?.let { it in TPMS_CMN_WARN..TPMS_SYS_FAILURE && it != TPMS_SYS_NOT_AVAILABLE },
            androidAuto = OpenHeadunit.status(ctx),
            serviceKm = vhalBytes(P_MAINTAIN_ODO)?.takeIf { it.size == 2 }?.u(),
            engineRunning = when (sensor(S_ENGINE_STATE)?.toInt()) {
                ENGINE_RUNNING -> true
                ENGINE_OFF -> false
                else -> null
            },
            night = when (sensor(S_DAY_NIGHT)?.toInt()) {
                DAY_NIGHT_DAY -> false
                DAY_NIGHT_NIGHT -> true
                else -> null
            },
        )
        logRaw(state)
        return state
    }

    private fun warn(id: Int): Boolean? = when (sensor(id)?.toInt()) {
        WARNING_ON -> true
        WARNING_OFF -> false
        else -> null
    }

    private fun belt(id: Int): Boolean? = when (sensor(id)?.toInt()) {
        BELT_BUCKLED -> true
        BELT_UNBUCKLED -> false
        else -> null
    }

    private fun tire(pId: Int, tId: Int, rawP: Int, rawT: Int): Tire {
        // Units unverified: kPa expected → bar; values ≤ 0 mean "no data".
        val p = sensor(pId)?.takeIf { it > 0f }?.let { if (it > 20f) it / 100f else it }
            ?: vhalBytes(rawP)?.takeIf { it.isNotEmpty() && it.u() > 0 }?.let { it.u() * TPMS_BAR_PER_UNIT }
        val t = sensor(tId)?.roundToInt()?.takeIf { it > -40 }
            ?: vhalBytes(rawT)?.takeIf { it.isNotEmpty() && it.u() > 0 }?.let { it.u() - 40 }
        return Tire(p, t)
    }

    private fun climate(): ClimateState {
        val dual = vhalBytes(P_DUAL)?.firstOrNull()?.toInt() == 1
        val left = cfn(H_TEMP, ZONE_LEFT)
        val right = if (dual) cfn(H_TEMP, ZONE_RIGHT) else null
        val airflow = when (fn(H_BLOWING_MODE, ZONE_LEFT)) {
            BLOW_FACE -> Airflow.FACE
            BLOW_LEG -> Airflow.FEET
            BLOW_FACE_LEG -> Airflow.FACE_FEET
            BLOW_WINDOW -> Airflow.WINDOW
            BLOW_FACE_WINDOW -> Airflow.FACE_WINDOW
            BLOW_LEG_WINDOW -> Airflow.FEET_WINDOW
            else -> Airflow.FACE_FEET
        }
        val fan = fn(H_FAN_SPEED, ZONE_LEFT)?.let { v ->
            if (v in FAN_LEVEL_1..FAN_LEVEL_9) (v - FAN_LEVEL_1 + 1).coerceAtMost(7) else 0
        } ?: 0
        return ClimateState(
            tempLeft = left,
            tempRight = right,
            airflow = airflow,
            fanLevel = fan,
            ac = fn(H_AC) == 1,
            auto = fn(H_AUTO) == 1,
            recirc = fn(H_CIRCULATION) == CIRC_INNER,
            power = fn(H_POWER) != 0,
        )
    }

    // ---------- system status ----------

    /**
     * Wired CarPlay. The internal USB4715 hub role-swaps the HU port to device mode while an iPhone
     * is attached: /sys/class/switch/otg_state reads 0 (connected) / 1 (no iPhone), and the HU's own
     * USB host bus disappears. (sys.usb.state stays "iAP2,ncm" permanently, so it is useless.)
     */
    private fun carPlayConnected(): Boolean {
        val otg = runCatching { java.io.File("/sys/class/switch/otg_state/state").readText().trim() }.getOrNull()
        if (otg != null && otg.isNotEmpty()) return otg == "0"
        return runCatching { java.io.File("/sys/bus/usb/devices").list()?.isEmpty() }.getOrNull() == true
    }

    private val mSysPropGet: Method? by lazy {
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
    }

    private fun sysProp(key: String): String = runCatching { mSysPropGet?.invoke(null, key) as? String }.getOrNull() ?: ""

    private fun wifiSsid(): String? = runCatching {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo ?: return@runCatching null
        if (info.networkId == -1) return@runCatching null
        info.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }.getOrNull()

    private fun btDevice(): String? = runCatching {
        val bonded = BluetoothAdapter.getDefaultAdapter()?.bondedDevices ?: return@runCatching null
        bonded.firstOrNull { d -> d.javaClass.getMethod("isConnected").invoke(d) == true }?.name
    }.getOrNull()

    /**
     * One-shot discovery: read every SENSOR_TYPE_* / TIRE_* constant the firmware's adapt API declares
     * and log which ones return real data (`adb logcat -s CarHomeProbe`).
     */
    private fun probeAllSensors() {
        for (cn in listOf("com.ecarx.xui.adaptapi.car.sensor.ISensor", "com.ecarx.xui.adaptapi.car.sensor.ITireSensor")) {
            val cls = runCatching { Class.forName(cn) }.getOrNull() ?: continue
            val sb = StringBuilder()
            for (f in cls.fields) {
                if (f.type != Int::class.javaPrimitiveType) continue
                if (!f.name.startsWith("SENSOR_TYPE_") && !(f.name.startsWith("TIRE_") && !f.name.contains("STATE_"))) continue
                val id = runCatching { f.getInt(null) }.getOrNull() ?: continue
                val raw = runCatching { mSensor?.invoke(sensors, id) }.getOrElse { "ERR" }
                val valid = (raw as? Float)?.let { it > INVALID_BELOW && !it.isNaN() } ?: false
                sb.append(if (valid) "OK " else "-- ").append(f.name).append('=').append(raw).append('\n')
            }
            sb.lines().chunked(20).forEach { Log.i("CarHomeProbe", it.joinToString(" | ")) }
        }
    }

    /** Raw values to logcat every 5 s for calibration: `adb logcat -s CarHomeData`. */
    /** Debug trace, no extra car reads: `adb logcat -s CarHomeData`. */
    private fun logRaw(s: CarState) {
        val now = System.currentTimeMillis()
        if (now - lastLog < 30_000) return
        lastLog = now
        Log.d(TAG, "state=$s")
    }

    companion object {
        private const val TAG = "CarHomeData"
        private const val POLL_MS = 200L
        private const val IDLE_REFRESH_MS = 2000L

        /** SX11 fuel tank, litres (owner checked: 46.5 L fit into an empty tank). */
        const val FUEL_TANK_L = 45f

        // ISensor (com.ecarx.xui.adaptapi.car.sensor.ISensor)
        const val S_CAR_SPEED = 1048832          // m/s
        const val S_GEAR = 2097664
        const val S_ODOMETER = 1050368           // km
        const val S_RANGE = 1050624              // ENDURANCE_MILEAGE, km
        const val S_FUEL_LEVEL = 1050112
        const val S_RPM = 1050880
        const val S_COOLANT_TEMP = 1052416
        const val S_TEMP_AMBIENT = 1051392
        const val S_ACCEL_DEPTH = 1053696

        // ISensorEvent gear values
        const val GEAR_FIRST = 2097665
        const val GEAR_TENTH = 2097674
        const val GEAR_NEUTRAL = 2097680
        const val GEAR_DRIVE = 2097696
        const val GEAR_PARK = 2097712
        const val GEAR_REVERSE = 2097728

        // Warnings / belts / start-stop (ISensor + ISensorEvent values)
        const val S_WARN_COOLANT_HIGH = 3146752
        const val S_WARN_OIL_PRESSURE = 3146496
        const val S_WARN_TRANSMISSION_HIGH = 3147008
        const val WARNING_OFF = 3145728
        const val WARNING_ON = 3145729
        const val S_BELT_DRIVER = 2101760
        const val S_BELT_PASSENGER = 2102016
        const val BELT_UNBUCKLED = 2101761
        const val BELT_BUCKLED = 2101762
        const val S_START_STOP = 2103040
        const val SS_STANDBY = 2103042
        const val SS_STOPPED = 2103043
        const val SS_OPERATION = 2103046
        const val SS_AUTO_STOPPING = 2103047
        const val T_TPMS_SYS_STATES = 5259264
        const val TPMS_CMN_WARN = 5259266
        const val TPMS_SYS_NOT_AVAILABLE = 5259271
        const val TPMS_SYS_FAILURE = 5259272

        // ITireSensor
        const val T_PRESSURE_FL = 5243136
        const val T_PRESSURE_FR = 5243392
        const val T_PRESSURE_RL = 5243648
        const val T_PRESSURE_RR = 5243904
        const val T_TEMP_FL = 5244160
        const val T_TEMP_FR = 5244416
        const val T_TEMP_RL = 5244672
        const val T_TEMP_RR = 5244928

        // IHvac
        const val H_POWER = 268501248
        const val H_AUTO = 268501504
        const val H_AC = 268501760
        const val H_FAN_SPEED = 268566784
        const val H_CIRCULATION = 268632320
        const val H_TEMP = 268828928
        const val H_TEMP_DUAL = 268829952
        const val H_BLOWING_MODE = 268894464
        const val FAN_LEVEL_1 = 268566785
        const val FAN_LEVEL_9 = 268566793
        const val CIRC_INNER = 268632321
        const val BLOW_FACE = 268894465
        const val BLOW_LEG = 268894466
        const val BLOW_FACE_LEG = 268894467
        const val BLOW_WINDOW = 268894468
        const val BLOW_FACE_WINDOW = 268894469
        const val BLOW_LEG_WINDOW = 268894470
        const val ZONE_LEFT = 1
        const val ZONE_RIGHT = 4

        // Raw VHAL vendor properties (byte arrays) and AOSP props
        const val P_REMAINING_OIL = 0x28700207       // fuel %, 1 byte
        const val P_AUTO_HOLD = 0x2870019d // INFO_ID_AUTO_HOLD_SWITCH: raw 0 off, 2 armed, 1 holding
        const val S_DAY_NIGHT = 2101248     // SENSOR_TYPE_DAY_NIGHT
        const val S_ENGINE_STATE = 2102784 // SENSOR_TYPE_ENGINE_STATE
        const val ENGINE_OFF = 2102785     // seen with ignition on, engine stopped
        const val ENGINE_RUNNING = 2102788 // seen while idling and driving
        const val DAY_NIGHT_DAY = 2101249   // ISensorEvent.DAY_NIGHT_MODE_DAY
        const val DAY_NIGHT_NIGHT = 2101250 // ISensorEvent.DAY_NIGHT_MODE_NIGHT
        const val P_MAINTAIN_ODO = 0x287000a5 // INFO_ID_IPKINFO_MAINTAIN_ODO: km to service, 2 bytes LE
        private val DOORS = listOf(
            0x2870008f to Opening.DRIVER, 0x28700090 to Opening.PASSENGER, 0x28700091 to Opening.REAR_LEFT,
            0x28700092 to Opening.REAR_RIGHT, 0x28700093 to Opening.TRUNK, 0x28700094 to Opening.HOOD,
        )
        const val P_BATTERY_VOL = 0x287000e4         // volts ×10
        const val P_ENGINE_OIL_TEMP = 0x11600304     // °C, float — stub on this firmware, unused
        const val P_INSTANT_FUEL = 0x287000a9        // l/100 ×20 (stock status bar value)
        const val P_AD_TEMP = 0x28700260             // outside air, 2 bytes LE, raw
        const val P_COOLANT = 0x287000ad             // °C + 40, 0 = no data
        const val P_DUAL = 0x2870000a                // climate dual-zone flag
        const val P_TPMS_P_FL = 0x287000b5
        const val P_TPMS_P_FR = 0x287000b6
        const val P_TPMS_P_RL = 0x287000b7
        const val P_TPMS_P_RR = 0x287000b8
        const val P_TPMS_T_FL = 0x287000b9
        const val P_TPMS_T_FR = 0x287000ba
        const val P_TPMS_T_RL = 0x287000bb
        const val P_TPMS_T_RR = 0x287000bc

        /** Adapt API marks missing data with Int.MIN_VALUE (as float). */
        private const val INVALID_BELOW = -1.0e6f
        // Calibrated 2026-11-07: raw 469 = +3 °C on the cluster (one point; check the slope on a warmer/colder day).
        const val OUTSIDE_SCALE = 0.1f
        const val OUTSIDE_OFFSET = -44f
        /** TPMS raw unit → bar; unverified (common Geely encoding 1.373 kPa/bit). */
        const val TPMS_BAR_PER_UNIT = 0.01373f
    }
}
