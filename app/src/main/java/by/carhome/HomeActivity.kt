package by.carhome

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import by.carhome.data.CarDataSource
import by.carhome.data.EcarxCarDataSource
import by.carhome.ui.HomeView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : Activity() {
    // Real car data (ECARX adapt API + VHAL).
    private lateinit var real: CarDataSource
    private val dataSource: CarDataSource get() = real

    private lateinit var home: HomeView
    private lateinit var host: android.widget.FrameLayout
    private lateinit var recBadge: android.widget.TextView
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    // Day and month names in the language of the strings, not the system locale.
    private val dateFmt by lazy {
        SimpleDateFormat(getString(R.string.date_format), Locale.forLanguageTag(getString(R.string.locale_tag)))
    }
    private var timeText = ""
    private var dateText = ""

    // Day/night: auto follows the sun (the car's DAY_NIGHT signal is stuck at NIGHT on this X50),
    // or a manual choice; long-press the date cycles Auto → Day → Night. 5 s of stability before switching.
    private enum class ThemeMode(val label: Int) { AUTO(R.string.theme_auto), DAY(R.string.theme_day), NIGHT(R.string.theme_night) }
    private val uiPrefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }
    private var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(uiPrefs.getString("theme", "AUTO")!!) }.getOrDefault(ThemeMode.AUTO)
        set(v) { uiPrefs.edit().putString("theme", v.name).apply() }
    private var sunCheckedAt = 0L
    private var sunNight = true
    private var night = true
    private var firstSignal = true
    private var pendingNight: Boolean? = null
    private var lastState: by.carhome.data.CarState? = null
    private var resumed = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val applyPending = Runnable {
        pendingNight?.let { switchTheme(it) }
        pendingNight = null
    }

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = updateClock()
    }

    /** Context whose resources use the night (values/) or day (values-notnight/) palette and art. */
    private fun themeContext(base: Context, night: Boolean): Context {
        val cfg = android.content.res.Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
        return base.createConfigurationContext(cfg)
    }

    // Start in the night theme regardless of the system setting (no wrong-colour flash).
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(themeContext(base, true))
    }

    private fun buildHome(night: Boolean): HomeView = HomeView(themeContext(this, night), ::loadIcon).also { h ->
        h.onAppClick = ::launch
        h.onHeatClick = { heat, on -> dataSource.setHeat(heat, on) }
        // Drive recorder: long-press the clock to start/stop logging all car signals to a file.
        h.onClockLongPress = {
            val on = by.carhome.rec.RecorderService.toggle(this)
            updateRecBadge(on)
            Toast.makeText(this, if (on) R.string.rec_on else R.string.rec_off, Toast.LENGTH_SHORT).show()
        }
        h.onClockTap = ::onClockTap
        h.onDateLongPress = ::cycleThemeMode
        h.setClock(timeText, dateText)
    }

    // Easter egg, like Android's build-number taps: 7 quick taps on the clock show the author for 3 s.
    private var taps = 0
    private var firstTapAt = 0L
    private var aboutView: android.view.View? = null

    private fun onClockTap() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - firstTapAt > 2500) { taps = 0; firstTapAt = now }
        if (++taps < 7) return
        taps = 0
        showAbout()
    }

    private fun showAbout() {
        if (aboutView != null) return
        val ctx = themeContext(this, night)
        val dp = resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val text = ctx.getColor(R.color.text)
        val dim = ctx.getColor(R.color.text_dim)

        // The HU has no browser, so the links are QR codes for the passenger's phone.
        fun qr(res: Int, label: String) = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(res)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                // Nearest-neighbour keeps the modules sharp when scaled up.
                drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.paint?.isFilterBitmap = false }
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = 10 * dp }
                setPadding(px(6f), px(6f), px(6f), px(6f))
            }, android.widget.LinearLayout.LayoutParams(px(132f), px(132f)))
            addView(android.widget.TextView(ctx).apply {
                this.text = label
                gravity = android.view.Gravity.CENTER
                textSize = 15f
                setTextColor(dim)
                typeface = by.carhome.ui.Fonts.regular
                setPadding(0, px(6f), 0, 0)
            }, android.widget.LinearLayout.LayoutParams(px(132f), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val v = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(px(36f), px(20f), px(36f), px(20f))
            // Light, solid-ish card: readable over the car picture without looking like a dialog.
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor((ctx.getColor(R.color.bg_edge) and 0x00FFFFFF) or (0xEB shl 24))
                cornerRadius = 28 * dp
                setStroke(dp.toInt().coerceAtLeast(1), ctx.getColor(R.color.line))
            }
            addView(android.widget.TextView(ctx).apply {
                // Localizable: res/values/strings.xml «about_author».
                this.text = ctx.getString(R.string.about_author)
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setLineSpacing(0f, 1.25f)
                setTextColor(text)
                typeface = by.carhome.ui.Fonts.regular
            }, android.widget.LinearLayout.LayoutParams(px(600f), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(android.widget.TextView(ctx).apply {
                this.text = ctx.getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(dim)
                typeface = by.carhome.ui.Fonts.regular
                setPadding(0, px(4f), 0, 0)
            })
            addView(android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(qr(R.drawable.qr_github, "GitHub"))
                addView(qr(R.drawable.qr_linkedin, "LinkedIn"), android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = px(40f) })
            }, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = px(16f) })
            alpha = 0f
            scaleX = 0.96f
            scaleY = 0.96f
        }
        host.addView(v, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER,
        ))
        aboutView = v
        val hide = Runnable {
            if (aboutView !== v) return@Runnable
            aboutView = null
            v.animate().alpha(0f).setDuration(400).withEndAction { host.removeView(v) }.start()
        }
        // Long enough to scan a QR code; a tap closes it earlier.
        v.setOnClickListener { v.removeCallbacks(hide); hide.run() }
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        v.postDelayed(hide, 10_000)
    }

    private fun buildBadge(night: Boolean): android.widget.TextView {
        val ctx = themeContext(this, night)
        return android.widget.TextView(ctx).apply {
            text = ctx.getString(R.string.rec_badge)
            typeface = by.carhome.ui.Fonts.regular
            textSize = 18f
            setTextColor(ctx.getColor(R.color.crit))
            setPadding(24, 8, 24, 8)
            background = ctx.getDrawable(R.drawable.tile)
            visibility = if (by.carhome.rec.RecorderService.running) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun badgeParams() = android.widget.FrameLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL,
    ).apply { topMargin = 16 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        by.carhome.ui.Fonts.init(this)
        real = EcarxCarDataSource(this)
        updateClockText()
        home = buildHome(true)
        recBadge = buildBadge(true)
        // The window may be shorter than the 720 px design (e.g. with the firmware status bar):
        // HomeView is laid out at its design size and scaled to fit.
        host = object : android.widget.FrameLayout(this) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                val availW = (r - l) - paddingLeft - paddingRight
                for (i in 0 until childCount) {
                    val v = getChildAt(i) as? HomeView ?: continue
                    val k = minOf(1f, (b - t).toFloat() / v.height.coerceAtLeast(1), availW.toFloat() / v.width.coerceAtLeast(1))
                    v.pivotX = v.width / 2f
                    v.pivotY = 0f
                    v.scaleX = k
                    v.scaleY = k
                    // The nav bar window ends with a 34 px dark strip (x 126..160) that we cannot draw over.
                    // Shift our 16 px left inset under it: left gap = 34 − 17 + 16 = 33 px, right = 16 + 17 = 33 px.
                    v.translationX = (availW - v.width) / 2f - NAV_GAP_SHIFT * resources.displayMetrics.density
                }
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.BLACK) // shows around the rounded corners, like the nav bar gap
            clipChildren = false
            addView(home, android.widget.FrameLayout.LayoutParams(homeW(), homeH()))
            addView(recBadge, badgeParams())
            // Nothing may sit under the nav bar: pad by its inset (0 if the window does not extend under it).
            setOnApplyWindowInsetsListener { v, ins ->
                v.setPadding(ins.systemWindowInsetLeft, 0, ins.systemWindowInsetRight, 0)
                ins
            }
        }
        setContentView(host)
        goFullscreen()
    }

    private fun homeW() = (1760 * resources.displayMetrics.density).toInt()
    private fun homeH() = (720 * resources.displayMetrics.density).toInt()

    private fun updateRecBadge(on: Boolean) {
        recBadge.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun onState(s: by.carhome.data.CarState) {
        lastState = s
        home.render(s)
        onNightSignal(desiredNight())
    }

    private fun desiredNight(): Boolean = when (themeMode) {
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
        ThemeMode.AUTO -> {
            val now = System.currentTimeMillis()
            if (now - sunCheckedAt > 60_000) {
                sunCheckedAt = now
                val loc = lastLocation()
                sunNight = by.carhome.data.SunTimes.isNight(
                    now, loc?.latitude ?: by.carhome.data.SunTimes.DEFAULT_LAT, loc?.longitude ?: by.carhome.data.SunTimes.DEFAULT_LON,
                )
            }
            sunNight
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun lastLocation(): android.location.Location? = runCatching {
        val lm = getSystemService(android.location.LocationManager::class.java)
        listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()

    private fun cycleThemeMode() {
        themeMode = ThemeMode.values()[(themeMode.ordinal + 1) % ThemeMode.values().size]
        sunCheckedAt = 0L
        firstSignal = true // apply the new choice immediately, no 5 s wait
        onNightSignal(desiredNight())
        Toast.makeText(this, themeMode.label, Toast.LENGTH_SHORT).show()
    }

    private fun onNightSignal(sig: Boolean?) {
        if (sig == night) syncSystemNightMode(night)
        if (sig == null || sig == night) {
            handler.removeCallbacks(applyPending)
            pendingNight = null
            if (sig != null) firstSignal = false
            return
        }
        if (firstSignal) {
            firstSignal = false
            switchTheme(sig)
        } else if (pendingNight != sig) {
            pendingNight = sig
            handler.removeCallbacks(applyPending)
            handler.postDelayed(applyPending, 5000)
        }
    }

    /** Builds the other theme's view tree on top of the old one and fades it in (300 ms); the old one is dropped. */
    /** Stock nav bar / status bar (SystemUI plugin) switch their own day/night art on the system ui mode. */
    private var syncedNight: Boolean? = null

    private fun syncSystemNightMode(toNight: Boolean) {
        if (syncedNight == toNight) return
        syncedNight = toNight
        runCatching {
            val um = getSystemService(android.app.UiModeManager::class.java)
            val want = if (toNight) android.app.UiModeManager.MODE_NIGHT_YES else android.app.UiModeManager.MODE_NIGHT_NO
            if (um.nightMode != want) um.nightMode = want
        }.onFailure { android.util.Log.w("CarHome", "setNightMode failed: $it") }
    }

    private fun switchTheme(toNight: Boolean) {
        syncSystemNightMode(toNight)
        if (toNight == night) return
        night = toNight
        val old = home
        val oldBadge = recBadge
        val nu = buildHome(toNight)
        recBadge = buildBadge(toNight)
        lastState?.let { nu.render(it) }
        if (!resumed) nu.pauseAnimations()
        nu.alpha = 0f
        home = nu
        host.addView(nu, host.indexOfChild(old) + 1, android.widget.FrameLayout.LayoutParams(homeW(), homeH()))
        host.removeView(oldBadge)
        host.addView(recBadge, badgeParams())
        val bg = android.graphics.Color.BLACK
        nu.animate().alpha(1f).setDuration(300).withEndAction {
            old.pauseAnimations()
            host.removeView(old)
            host.setBackgroundColor(bg)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateClock()
        updateRecBadge(by.carhome.rec.RecorderService.running)
        dataSource.observe { onState(it) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        home.resumeAnimations()
    }

    override fun onPause() {
        resumed = false
        home.pauseAnimations()
        super.onPause()
    }

    override fun onStop() {
        home.pauseAnimations()
        handler.removeCallbacks(applyPending)
        dataSource.stop()
        unregisterReceiver(tick)
        super.onStop()
    }

    override fun onDestroy() {
        dataSource.release()
        super.onDestroy()
    }

    // Fullscreen: the stock pull-down shade is not available over a third-party home anyway,
    // and our top strip already shows clock, Wi-Fi and BT.
    @Suppress("DEPRECATION")
    private fun goFullscreen() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // LAYOUT_HIDE_NAVIGATION lets the window extend under the system nav bar (which stays visible, not
        // HIDE_NAVIGATION); the content is padded by the reported inset in the host's insets listener.
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    private fun updateClockText() {
        val now = Date()
        timeText = timeFmt.format(now)
        dateText = dateFmt.format(now)
    }

    private fun updateClock() {
        updateClockText()
        home.setClock(timeText, dateText)
        // Sunrise/sunset must switch the theme even when no car value changes (parked).
        onNightSignal(desiredNight())
    }

    private fun loadIcon(spec: AppSpec): Drawable? = try {
        packageManager.getApplicationIcon(spec.pkg)
    } catch (e: Exception) {
        null
    }

    /** Stock app list: ecarx.launcher3/.AppPane (as the firmware nav bar does); else the system all-apps. */
    private fun openAllApps() {
        val pane = Intent().setClassName("ecarx.launcher3", "ecarx.launcher3.AppPane")
            .putExtra("APPPANE_EXIT_TYPE", 4)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(pane)
            return
        } catch (e: Exception) {
            // fall through
        }
        val all = Intent(Intent.ACTION_ALL_APPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(all)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.all_apps_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launch(spec: AppSpec) {
        if (spec.kind == TileKind.ALL_APPS) {
            openAllApps()
            return
        }
        if (spec.kind == TileKind.ANDROID_AUTO) {
            if (!by.carhome.data.OpenHeadunit.open(this)) {
                Toast.makeText(this, R.string.headunit_missing, Toast.LENGTH_LONG).show()
            }
            return
        }
        val i = packageManager.getLaunchIntentForPackage(spec.pkg)
        if (i == null) {
            Toast.makeText(this, getString(R.string.app_not_found, getString(spec.label).replace('\n', ' ')), Toast.LENGTH_SHORT).show()
        } else {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        /** dp: moves the layout left so the gaps to the nav bar and to the right screen edge are equal. */
        const val NAV_GAP_SHIFT = 0f
    }
}
