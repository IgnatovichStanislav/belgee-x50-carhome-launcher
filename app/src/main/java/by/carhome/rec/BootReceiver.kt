package by.carhome.rec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Resumes the drive recorder after the head unit boots, but only if the user left recording on
 * (long-press on the clock). Off by default.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (prefs(ctx).getBoolean(KEY, false) && !RecorderService.running) {
            ctx.startForegroundService(Intent(ctx, RecorderService::class.java))
        }
    }

    companion object {
        private const val KEY = "autostart"

        fun setAutostart(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY, on).apply()

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("rec", Context.MODE_PRIVATE)
    }
}
