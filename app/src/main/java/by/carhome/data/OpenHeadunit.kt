package by.carhome.data

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

/**
 * Android Auto status from Open Headunit (com.andrerinas.headunitrevived, AGPL, installed separately).
 * Its automation contract (github.com/andreknieriem/open-headunit, contract/README.md):
 *  - broadcasts `com.andrerinas.headunitrevived.SESSION_STATE` with extra `state` =
 *    connecting / connected / projecting / disconnected / failed on every session change;
 *  - answers `ACTION_QUERY_STATE` (ordered broadcast) with JSON in the result data, e.g.
 *    {"connected":true,"wireless":false,"state":"g",…}; `state` there is an obfuscated enum name,
 *    so only `connected` is used;
 *  - `ACTION_RAISE_PROJECTION` brings the running session to the front.
 * Note: the package and the action prefix differ on purpose (`headunitrevived` vs `openheadunit`).
 */
object OpenHeadunit {
    const val PKG = "com.andrerinas.headunitrevived"
    private const val RECEIVER = "com.andrerinas.openheadunit.automation.AutomationReceiver"
    private const val SESSION_STATE = "$PKG.SESSION_STATE"
    private const val ACT = "com.andrerinas.openheadunit."
    private const val TAG = "CarHome/OHU"
    /** Re-ask the app this often: a missed broadcast (e.g. the app was killed) must not leave a stale status. */
    private const val QUERY_MS = 30_000L
    /** How long «Нет связи» stays after a failed session before falling back to «Не подключён». */
    private const val LOST_MS = 15_000L

    /** Last reported session state, e.g. "projecting"; "disconnected" until the app says otherwise. */
    @Volatile private var state: String = "disconnected"
    @Volatile private var failedAt = 0L
    @Volatile private var queriedAt = 0L
    @Volatile private var installed: Boolean? = null

    private var receiver: BroadcastReceiver? = null

    fun start(context: Context) {
        val ctx = context.applicationContext
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val s = i.getStringExtra("state") ?: return
                if (s == "failed") failedAt = SystemClock.elapsedRealtime()
                state = s
                Log.i(TAG, "session state $s")
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(SESSION_STATE))
        query(ctx)
    }

    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    /** Called from the 0.5 Hz poll. Cheap: the package check and the query run at most every [QUERY_MS]. */
    fun status(ctx: Context): LinkStatus {
        val now = SystemClock.elapsedRealtime()
        if (installed == null || now - queriedAt > QUERY_MS) query(ctx)
        if (installed == false) return LinkStatus.NOT_INSTALLED
        return when (state) {
            "connected", "projecting" -> LinkStatus.CONNECTED
            "connecting" -> LinkStatus.CONNECTING
            else -> if (now - failedAt < LOST_MS) LinkStatus.LOST else LinkStatus.DISCONNECTED
        }
    }

    val connected: Boolean get() = state == "connected" || state == "projecting"

    private fun isInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    /** Session state from the app itself: covers broadcasts we missed (it connected before we started, or died). */
    private fun query(ctx: Context) {
        queriedAt = SystemClock.elapsedRealtime()
        installed = isInstalled(ctx)
        if (installed != true) { state = "disconnected"; return }
        val i = Intent(ACT + "ACTION_QUERY_STATE").setClassName(PKG, RECEIVER)
        ctx.sendOrderedBroadcast(i, null, object : BroadcastReceiver() {
            override fun onReceive(c: Context, r: Intent) {
                val json = resultData ?: return
                val up = Regex("\"connected\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1) ?: return
                // Keep a finer state we already have ("connecting"/"projecting"); only correct a stale one.
                if (up == "true" && !connected) state = "connected"
                if (up == "false" && connected) state = "disconnected"
            }
        }, null, Activity.RESULT_OK, null, Bundle())
    }

    /** Brings Android Auto to the front if a session is up; otherwise opens the app. */
    fun open(ctx: Context): Boolean {
        if (connected) {
            ctx.sendBroadcast(Intent(ACT + "ACTION_RAISE_PROJECTION").setClassName(PKG, RECEIVER))
            return true
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
