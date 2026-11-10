package by.carhome.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Opens the stock climate panel (the overlay the nav bar shows) through ecarx.hvac.app's exported HvacAppService.
 * AIDL `ecarx.hvac.app.IOpenHvacAidlInterface`, called by raw transaction to avoid copying the interface.
 */
class HvacPanel(private val ctx: Context) {
    private var binder: IBinder? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
        override fun onBindingDied(name: ComponentName?) { unbind(); bind() }
    }

    fun bind() {
        if (bound) return
        val i = Intent(ACTION).setPackage(PKG)
        bound = runCatching { ctx.bindService(i, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) Log.w(TAG, "HvacAppService not available")
    }

    fun unbind() {
        if (bound) runCatching { ctx.unbindService(conn) }
        bound = false
        binder = null
    }

    /** false: not connected or the call failed; the caller falls back to the climate app. */
    fun openMain(): Boolean {
        // Under boot load the HVAC process can be killed on start timeout and the binding stays pending: rebind for next time.
        val b = binder ?: run { unbind(); bind(); return false }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(TX_OPEN_HVAC_MAIN, data, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) {
            Log.w(TAG, "openHvacMain failed", e)
            false
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private companion object {
        const val TAG = "CarHomeHvac"
        const val PKG = "ecarx.hvac.app"
        const val ACTION = "ecarx.hvac.app.HvacAppService"
        const val DESCRIPTOR = "ecarx.hvac.app.IOpenHvacAidlInterface"
        const val TX_OPEN_HVAC_MAIN = IBinder.FIRST_CALL_TRANSACTION // openHvacMain = 1
    }
}
