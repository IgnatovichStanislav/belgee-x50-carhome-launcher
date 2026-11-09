package by.carhome.rec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drive recorder: logs every change of every car signal the head unit receives, so decoding can be
 * calibrated from real driving data instead of guesses.
 *
 * Sources: all VHAL properties (change listener + full snapshot at start), all ECARX adapt-API
 * sensors (SENSOR_TYPE_* / TIRE_*) and HVAC functions (polled). Only changes are written.
 * Output: /sdcard/Android/data/by.carhome/files/drive-YYYYMMDD-HHMMSS.csv — `ms,src,key,value`
 * (src: V = VHAL `0xID@area`, S = sensor, H = HVAC function).
 *
 * Toggle: long-press the clock on the home screen. Off by default; the choice survives a reboot
 * ([BootReceiver]). The service is not exported, so other apps cannot start it.
 */
class RecorderService : Service() {
    companion object {
        private const val TAG = "CarHomeRec"
        private const val CHANNEL = "rec"
        private const val POLL_MS = 500L
        private const val VHAL_POLL_MS = 1000L

        /**
         * Privacy: properties that identify the car, SIM, modem or owner, or give a location, are never
         * recorded: VIN, T-Box ICCID/IMEI, BT MAC, GPS. Logs are meant to be shareable for calibration.
         */
        private val PRIVATE_PROPS = setOf(
            0x2870008e, // INFO_ID_VIN_INFO
            0x28700133, // INFO_ID_TBOXCFG_ICCID_VALUE
            0x28700135, // INFO_ID_TBOXCFG_IMEI_NUM
            0x28700152, 0x28700153, // INFO_ID_ATE_BT_GET/SETMACADDR
            0x28700159, 0x28700169, // INFO_ID_ATE_GPS_INFO / SYSCHECK
            0x28700189, 0x2870018a, 0x2870018b, 0x2870018c, 0x2870018d, 0x2870018e, // GPS lon/lat
            0x2870018f, 0x28700190, 0x28700191, 0x28700192, // GPS GSA/GGA/elevation/info
        )
        private const val HVAC_EVERY = 2 // HVAC polled every 2nd sensor poll (1 s)
        private const val MAX_LOG_BYTES = 200L * 1024 * 1024

        @Volatile var running = false
            private set

        /** Starts or stops recording; returns the new state. */
        fun toggle(ctx: Context): Boolean {
            val i = Intent(ctx, RecorderService::class.java)
            val on = !running
            if (on) ctx.startForegroundService(i) else ctx.stopService(i)
            BootReceiver.setAutostart(ctx, on)
            return on
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private var out: BufferedWriter? = null
    private var file: File? = null
    private var t0 = 0L
    private var lines = 0L
    private val last = HashMap<String, String>(4096)

    private var car: Any? = null
    private var sensors: Any? = null
    private var carFunction: Any? = null
    private var propMgr: Any? = null
    private var mSensor: Method? = null
    private var mFn: Method? = null
    private var mCfn: Method? = null
    private var vhalListener: Any? = null
    private val sensorIds = ArrayList<Pair<String, Int>>()
    private val hvacIds = ArrayList<Pair<String, Int>>()
    private var pollCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(1, notification())
        thread = HandlerThread("car-rec").apply { start() }
        h = Handler(thread.looper)
        h.post { openFile(); connect() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        h.post {
            unregisterVhal()
            write("I", "stop", "lines=$lines")
            runCatching { out?.flush(); out?.close() }
            Log.i(TAG, "stopped: ${file?.absolutePath} ($lines lines)")
            thread.quitSafely()
        }
        super.onDestroy()
    }

    // ---------- output ----------

    private fun openFile() {
        val dir = getExternalFilesDir(null) ?: filesDir
        pruneOldLogs(dir)
        val name = "drive-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
        try {
            val f = File(dir, name)
            file = f
            out = f.bufferedWriter()
            t0 = SystemClock.elapsedRealtime()
            out!!.write("ms,src,key,value\n")
        } catch (e: IOException) {
            fail(e)
            return
        }
        write("I", "start", Date().toString())
        Log.i(TAG, "recording to ${file!!.absolutePath}")
        h.postDelayed(flusher, 3000)
    }

    /** Deletes the oldest drive logs while the folder holds more than [MAX_LOG_BYTES]. */
    private fun pruneOldLogs(dir: File) {
        val logs = dir.listFiles { f -> f.isFile && f.name.startsWith("drive-") && f.name.endsWith(".csv") }
            ?.sortedBy { it.name } ?: return // names carry the start time, so name order is age order
        var total = logs.sumOf { it.length() }
        for (f in logs) {
            if (total <= MAX_LOG_BYTES) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    private val flusher = object : Runnable {
        override fun run() {
            if (out == null) return
            try { out?.flush() } catch (e: IOException) { fail(e); return }
            h.postDelayed(this, 3000)
        }
    }

    /**
     * Storage full or the file went away: an uncaught IOException here would kill the whole launcher
     * process, so drop the file and stop the service instead.
     */
    private fun fail(e: IOException) {
        Log.e(TAG, "write failed, recorder stopped: ${file?.absolutePath}", e)
        runCatching { out?.close() }
        out = null
        stopSelf()
    }

    /** Must run on the recorder thread. Writes only when the value changed. */
    private fun write(src: String, key: String, value: String) {
        val k = "$src|$key"
        if (last[k] == value) return
        last[k] = value
        val w = out ?: return
        try {
            w.write((SystemClock.elapsedRealtime() - t0).toString())
            w.write(","); w.write(src); w.write(","); w.write(key); w.write(","); w.write(value.replace(',', ';')); w.write("\n")
        } catch (e: IOException) {
            fail(e)
            return
        }
        lines++
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> "null"
        is ByteArray -> v.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
        is IntArray -> v.joinToString(" ")
        is FloatArray -> v.joinToString(" ")
        is LongArray -> v.joinToString(" ")
        is Array<*> -> v.joinToString(" ") { it.toString() }
        else -> v.toString()
    }

    // ---------- ECARX adapt API connection ----------

    private fun connect() {
        try {
            val carCls = Class.forName("com.ecarx.xui.adaptapi.car.Car")
            val c = carCls.getMethod("create", Context::class.java).invoke(null, applicationContext) ?: return
            car = c
            val watcherCls = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            val watcher = Proxy.newProxyInstance(watcherCls.classLoader, arrayOf(watcherCls)) { _, m, _ ->
                if (m.name == "onConnected") h.post { onConnected() }
                null
            }
            c.javaClass.getMethod("registerConnectWatcher", watcherCls).invoke(c, watcher)
            c.javaClass.getMethod("connect").invoke(c)
        } catch (t: Throwable) {
            write("I", "error", "adapt API: $t")
        }
    }

    private fun onConnected() {
        if (!running) return
        val c = car ?: return
        val i = Int::class.javaPrimitiveType
        sensors = runCatching { c.javaClass.getMethod("getSensorManager").invoke(c) }.getOrNull()
        carFunction = runCatching { c.javaClass.getMethod("getICarFunction").invoke(c) }.getOrNull()
        propMgr = runCatching { c.javaClass.getMethod("getCarPropertyManager").invoke(c) }.getOrNull()
        mSensor = runCatching { sensors?.javaClass?.getMethod("getSensorLatestValue", i) }.getOrNull()
        mFn = runCatching { carFunction?.javaClass?.getMethod("getFunctionValue", i) }.getOrNull()
        mCfn = runCatching { carFunction?.javaClass?.getMethod("getCustomizeFunctionValue", i, i) }.getOrNull()
        collectIds("com.ecarx.xui.adaptapi.car.sensor.ISensor", sensorIds) { it.startsWith("SENSOR_TYPE_") }
        collectIds("com.ecarx.xui.adaptapi.car.sensor.ITireSensor", sensorIds) { it.startsWith("TIRE_") && !it.contains("STATE_") }
        collectIds("com.ecarx.xui.adaptapi.car.hvac.IHvac", hvacIds) { it.startsWith("HVAC_FUNC_") }
        write("I", "connected", "sensors=${sensorIds.size} hvac=${hvacIds.size} vhal=${propMgr != null}")
        startVhal()
        h.post(poll)
    }

    private fun collectIds(cn: String, into: MutableList<Pair<String, Int>>, keep: (String) -> Boolean) {
        val cls = runCatching { Class.forName(cn) }.getOrNull() ?: return
        for (f in cls.fields) {
            if (f.type != Int::class.javaPrimitiveType || !keep(f.name)) continue
            runCatching { into += f.name to f.getInt(null) }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            for ((name, id) in sensorIds) {
                write("S", name, fmt(runCatching { mSensor?.invoke(sensors, id) }.getOrElse { "ERR" }))
            }
            if (pollCount++ % HVAC_EVERY == 0) {
                for ((name, id) in hvacIds) {
                    write("H", name, fmt(runCatching { mFn?.invoke(carFunction, id) }.getOrElse { "ERR" }))
                }
                write("H", "TEMP@1", fmt(runCatching { mCfn?.invoke(carFunction, 268828928, 1) }.getOrNull()))
                write("H", "TEMP@4", fmt(runCatching { mCfn?.invoke(carFunction, 268828928, 4) }.getOrNull()))
            }
            h.postDelayed(this, POLL_MS)
        }
    }

    // ---------- VHAL: snapshot + change listener for every property ----------

    private fun startVhal() {
        val pm = propMgr ?: return
        try {
            val configs = pm.javaClass.getMethod("getPropertyList").invoke(pm) as? List<*> ?: return
            val getProp = pm.javaClass.methods.firstOrNull {
                it.name == "getProperty" && it.parameterTypes.size == 3 && it.parameterTypes[0] == Class::class.java
            }
            val listenerCls = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener")
            // CarPropertyManager keeps listeners in a map, so the proxy must answer hashCode/equals:
            // returning null there threw "Expected to unbox a 'int'" and left us with 0 listeners.
            val listener = Proxy.newProxyInstance(listenerCls.classLoader, arrayOf(listenerCls)) { self, m, args ->
                when (m.name) {
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.getOrNull(0)
                    "toString" -> "CarHomeRecListener"
                    "onChangeEvent" -> {
                        val v = args?.getOrNull(0)
                        if (v != null) h.post { recordValue(v) }
                        null
                    }
                    else -> null
                }
            }
            vhalListener = listener
            val reg = pm.javaClass.getMethod("registerListener", listenerCls, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            var registered = 0
            var regErr: Throwable? = null
            for (cfg in configs) {
                cfg ?: continue
                val id = runCatching { cfg.javaClass.getMethod("getPropertyId").invoke(cfg) as Int }.getOrNull() ?: continue
                val areas = runCatching { cfg.javaClass.getMethod("getAreaIds").invoke(cfg) as IntArray }.getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: intArrayOf(0)
                // snapshot
                for (a in areas) {
                    val v = runCatching { getProp?.invoke(pm, classFor(id), id, a) }.getOrNull()
                    if (v != null) recordValue(v)
                }
                val modeP = runCatching { cfg.javaClass.getMethod("getChangeMode").invoke(cfg) as Int }.getOrDefault(1)
                if (modeP != 0) for (a in areas) vhalPoll += Triple(id, a, classFor(id))
                // listener (continuous props at ≤2 Hz)
                val mode = runCatching { cfg.javaClass.getMethod("getChangeMode").invoke(cfg) as Int }.getOrDefault(1)
                if (mode == 0) continue // static
                val rate = if (mode == 2) 2f else 0f
                val r = runCatching { reg.invoke(pm, listener, id, rate) as Boolean }
                r.exceptionOrNull()?.let { regErr = it.cause ?: it }
                if (r.getOrDefault(false)) registered++
            }
            vhalGet = getProp
            write("I", "vhal", "props=${configs.size} listeners=$registered polled=${vhalPoll.size} regErr=$regErr")
            // Polling is only a fallback: every VHAL get logs 3 error lines in the HAL/CarService.
            if (registered == 0) h.postDelayed(vhalPoller, VHAL_POLL_MS) else vhalPoll.clear()
        } catch (t: Throwable) {
            write("I", "error", "vhal: $t")
        }
    }

    private val vhalPoll = ArrayList<Triple<Int, Int, Class<*>>>()
    private var vhalGet: Method? = null

    /** Listener registration fails on this HU, so poll every non-static property instead. */
    private val vhalPoller = object : Runnable {
        override fun run() {
            if (!running) return
            val pm = propMgr
            val g = vhalGet
            if (pm != null && g != null) {
                val it = vhalPoll.iterator()
                while (it.hasNext()) {
                    val (id, a, cls) = it.next()
                    val r = runCatching { g.invoke(pm, cls, id, a) }
                    if (r.isFailure) { it.remove(); continue } // unsupported on this car: stop asking
                    r.getOrNull()?.let { recordValue(it) }
                }
            }
            h.postDelayed(this, VHAL_POLL_MS)
        }
    }

    private fun recordValue(v: Any) {
        if (!running) return
        val id = runCatching { v.javaClass.getMethod("getPropertyId").invoke(v) as Int }.getOrNull() ?: return
        if (id in PRIVATE_PROPS) return // never write identifiers or location to the log
        val area = runCatching { v.javaClass.getMethod("getAreaId").invoke(v) as Int }.getOrDefault(0)
        val value = runCatching { v.javaClass.getMethod("getValue").invoke(v) }.getOrNull()
        write("V", "0x%08x@%d".format(id, area), fmt(value))
    }

    private fun unregisterVhal() {
        val pm = propMgr ?: return
        val l = vhalListener ?: return
        runCatching {
            val cls = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener")
            pm.javaClass.getMethod("unregisterListener", cls).invoke(pm, l)
        }
    }

    /** VHAL value class by the property's type bits. */
    private fun classFor(id: Int): Class<*> = when (id and 0x00ff0000) {
        0x00100000 -> String::class.java
        0x00200000 -> java.lang.Boolean::class.java
        0x00400000 -> java.lang.Integer::class.java
        0x00410000 -> Array<Int>::class.java
        0x00500000 -> java.lang.Long::class.java
        0x00510000 -> Array<Long>::class.java
        0x00600000 -> java.lang.Float::class.java
        0x00610000 -> Array<Float>::class.java
        0x00700000 -> ByteArray::class.java
        else -> Any::class.java
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Запись данных", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("CarHome: запись данных автомобиля")
            .setContentText("Долгое нажатие на часы — остановить")
            .setOngoing(true)
            .build()
    }
}
