package by.carhome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import by.carhome.AppSpec
import by.carhome.Apps
import by.carhome.R
import by.carhome.TileKind
import by.carhome.data.BatteryLevel
import by.carhome.data.CarState
import by.carhome.data.Heat
import by.carhome.data.Thresholds
import kotlin.math.roundToInt

/**
 * Home screen, 1760x720 window (home-review5 design: 1760x670 mockup, the extra 50 px spread vertically).
 * [iconLoader] returns an app icon (null → letter tile).
 */
class HomeView(
    ctx: Context,
    private val iconLoader: (AppSpec) -> Drawable? = { null },
) : FrameLayout(ctx) {
    var onAppClick: ((AppSpec) -> Unit)? = null
    var onClockLongPress: (() -> Unit)? = null
    var onClockTap: (() -> Unit)? = null
    var onDateLongPress: (() -> Unit)? = null

    /** A heater button was tapped; the button has already switched itself (optimistic). */
    var onHeatClick: ((Heat, Boolean) -> Unit)? = null

    /** Tests turn the streak animation off. */
    var motionEnabled = true

    private val d = ctx.dpf(1f)
    private val WRAP = LayoutParams.WRAP_CONTENT

    private fun px(v: Float) = (v * d).roundToInt()
    private fun paints(vararg p: Paint) = arrayOf(*p)

    // --- widgets updated from the car state ---
    private val clock = RunView(ctx, paints(ctx.textPaint(64f, Fonts.light, R.color.text)))
    private val date = RunView(ctx, paints(ctx.textPaint(22f, Fonts.regular, R.color.text_dim)))
    private val wifi = StatusRow(ctx, R.drawable.ic_wifi)
    private val bt = StatusRow(ctx, R.drawable.ic_bt)
    private val tripVal = valueRun(32f, Fonts.regular, R.color.text, 20f)
    private val odoVal = RunView(ctx, paints(ctx.textPaint(32f, Fonts.regular, R.color.text_2), ctx.textPaint(20f, Fonts.regular, R.color.text_dim)), gapL = 3 * d, gapR = 0f)
    private val speed = RunView(
        ctx,
        paints(ctx.textPaint(150f, Fonts.light, R.color.text).apply { letterSpacing = -2f / 150f },
            ctx.textPaint(28f, Fonts.regular, R.color.text_dim)),
        gapL = 6 * d, fixedW = px(352f),
    )
    private val rangeKey = RunView(ctx, paints(ctx.textPaint(20f, Fonts.regular, R.color.text_dim)))
    private val rangeBig = RunView(
        ctx,
        paints(ctx.textPaint(56f, Fonts.regular, R.color.text), ctx.textPaint(24f, Fonts.regular, R.color.text_dim)),
        gapL = 6 * d, gapR = 8 * d, fixedW = px(352f),
    )
    private val fuelBar = ProgressBarView(ctx, R.color.bar_track, R.color.accent, 10f)
    private val gear = GearView(ctx)
    private val rangeTile = View(ctx).apply { setBackgroundResource(R.drawable.tile) }
    private val rangeBadge = BadgeView(ctx, 24f, 17f)
    private val state = StateTileView(ctx)
    private val belts = BeltsView(ctx)
    private val autoHold = AutoHoldView(ctx)
    private val doors = DoorBanner(ctx)
    private val battery = StatTileView(ctx, listOf(ctx.getString(R.string.battery)))
    private val heaters = arrayOf(
        HeaterButton(ctx, R.drawable.ic_heat_wheel, ctx.getString(R.string.heat_wheel)),
        HeaterButton(ctx, R.drawable.ic_heat_nozzle, ctx.getString(R.string.heat_nozzle)),
        HeaterButton(ctx, R.drawable.ic_heat_glass, ctx.getString(R.string.heat_glass)),
    )
    private val streaks = StreakLayer(ctx, floatArrayOf(300f, 340f, 380f), longArrayOf(0, 500, 1100))
    private val climate = ClimateCardView(ctx)
    private val carPlay: StatusCardView
    private val androidAuto: StatusCardView

    private val critColor = ctx.getColor(R.color.crit)
    private val softCrit = ctx.getColor(R.color.crit_soft)
    private val textColor = ctx.getColor(R.color.text)
    private val dimColor = ctx.getColor(R.color.text_dim)
    private val okColor = ctx.getColor(R.color.ok)
    private val accentColor = ctx.getColor(R.color.accent)

    private val km = ctx.getString(R.string.unit_km)
    private val rangeText = ctx.getString(R.string.range)
    private val fuelLowText = ctx.getString(R.string.fuel_low)

    private var last: CarState? = null
    private var paused = false

    private fun heatOf(s: CarState, i: Int): Boolean? = when (i) {
        0 -> s.steeringHeat
        1 -> s.nozzleHeat
        else -> s.windshieldHeat
    }

    /** No answer from the car after a tap (command rejected): show the car's real state again. */
    private val heatRevert = Array(3) { i -> Runnable { heaters[i].on = last?.let { heatOf(it, i) } == true } }

    private fun valueRun(sizeDp: Float, tf: android.graphics.Typeface, color: Int, unitDp: Float) =
        RunView(context, paints(context.textPaint(sizeDp, tf, color), context.textPaint(unitDp, Fonts.regular, R.color.text_dim)),
            gapL = 3 * d, gapR = 8 * d)

    private fun put(v: View, x: Float, y: Float, w: Int = WRAP, h: Int = WRAP) {
        addView(v, LayoutParams(w, h).apply { leftMargin = px(x); topMargin = px(y) })
    }

    private fun putRun(v: RunView, x: Float, baseline: Float) {
        addView(v, LayoutParams(if (v.fixedW > 0) v.fixedW else WRAP, WRAP).apply {
            leftMargin = px(x); topMargin = (baseline * d - v.ascent).roundToInt()
        })
    }

    private fun tile(x: Float, y: Float, w: Float, h: Float) = put(View(context).apply { setBackgroundResource(R.drawable.tile) }, x, y, px(w), px(h))

    private fun group(key: RunView?, keyBase: Float, valueView: View, valueTop: Float, h: Float, divider: Boolean, trailing: Boolean = true): InfoGroup {
        val g = InfoGroup(context, px(h), divider, trailing)
        if (key != null) g.addView(key, LayoutParams(WRAP, WRAP).apply { topMargin = (keyBase * d - key.ascent).roundToInt() })
        g.addView(valueView, LayoutParams(WRAP, WRAP).apply { topMargin = px(valueTop) })
        return g
    }

    private fun vTop(r: RunView) = 57f - r.ascent / d

    private fun keyRun(text: String): RunView =
        RunView(context, paints(context.textPaint(18f, Fonts.regular, R.color.text_dim))).apply { setParts(text) }

    init {
        setBackgroundResource(R.drawable.scene)
        clipChildren = false
        // Rounded left corners like the system nav bar next to us; the right edge is the screen edge, so the
        // outline is extended past it to keep those corners square.
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                val r = px(CORNER_DP)
                o.setRoundRect(0, 0, v.width + r, v.height, r.toFloat())
            }
        }
        clipToOutline = true

        // car scene (baked: centred, masked edges) + speed streaks.
        // 87% of the 842x390 artwork, bottom edge kept at 500 so the wheels stay above the gear row.
        put(ImageView(ctx).apply {
            setImageResource(R.drawable.car_image); scaleType = ImageView.ScaleType.FIT_XY
        }, 504f, 161f, px(733f), px(339f))
        put(streaks, 440f, 110f, px(880f), px(400f))

        // clock / date
        putRun(clock, 16f, 64f)
        putRun(date, 20f, 102f)
        clock.setOnLongClickListener { onClockLongPress?.invoke(); true }
        clock.setOnClickListener { onClockTap?.invoke() }
        date.setOnLongClickListener { onDateLongPress?.invoke(); true }
        clock.isLongClickable = true

        // Wi-Fi / Bluetooth
        val lgrp = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun lp(h: Float) = LinearLayout.LayoutParams(WRAP, px(h)).apply { gravity = Gravity.CENTER_VERTICAL }
        lgrp.addView(group(keyRun("Wi-Fi"), 17f, wifi, 25f, 66f, true), lp(66f))
        lgrp.addView(group(keyRun("Bluetooth"), 17f, bt, 25f, 66f, true), lp(66f))
        put(lgrp, 266f, 16f, WRAP, px(80f))
        // Long dates ("понедельник, 30 сентября") are wider than the clock: keep the divider clear of them.
        val pushWifi = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val lp = lgrp.layoutParams as LayoutParams
            val left = maxOf(px(266f), maxOf(clock.right, date.right) + px(22f))
            if (lp.leftMargin != left) { lp.leftMargin = left; lgrp.post { lgrp.requestLayout() } }
        }
        clock.addOnLayoutChangeListener(pushWifi)
        date.addOnLayoutChangeListener(pushWifi)

        // trip / odometer
        val trg = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        trg.addView(group(keyRun(ctx.getString(R.string.trip)), 20f, tripVal, vTop(tripVal), 71f, false), lp(71f))
        trg.addView(group(keyRun(ctx.getString(R.string.odometer)), 20f, odoVal, vTop(odoVal), 71f, true, false), lp(71f))
        addView(trg, LayoutParams(WRAP, px(80f)).apply { gravity = Gravity.END or Gravity.TOP; rightMargin = px(20f); topMargin = px(16f) })

        // left tiles
        // Columns end 14 px above the app row (y600), like every other gap: rows 115..366 and 381..586.
        tile(16f, 115f, 402f, 251f)
        putRun(RunView(ctx, paints(ctx.textPaint(20f, Fonts.regular, R.color.text_dim))).apply { setParts(ctx.getString(R.string.speed)) }, 41f, 149f)
        putRun(speed, 41f, 300f)
        put(rangeTile, 16f, 381f, px(402f), px(205f))
        putRun(rangeKey.apply { setParts(rangeText) }, 41f, 418f)
        put(rangeBadge, 41f, 401f)
        rangeBadge.visibility = View.GONE
        putRun(rangeBig, 41f, 482f)
        put(fuelBar, 41f, 516f, px(352f), px(18f))
        fuelBar.mark = Thresholds.FUEL_LOW_LITERS / by.carhome.data.EcarxCarDataSource.FUEL_TANK_L

        // right column
        put(state, 1342f, 115f, px(402f), px(251f))
        put(battery, 1342f, 381f, px(402f), px(97f))
        val heat = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val kinds = Heat.values()
        for ((i, b) in heaters.withIndex()) {
            b.setOnClickListener {
                b.on = !b.on
                removeCallbacks(heatRevert[i])
                postDelayed(heatRevert[i], HEAT_CONFIRM_MS)
                onHeatClick?.invoke(kinds[i], b.on)
            }
            heat.addView(b, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = px(10f) })
        }
        put(heat, 1342f, 492f, px(402f), px(94f))

        // under the car: gear, AutoHold, belts; door banner above the car
        // Row under the car is symmetric inside the car area (x 434..1326, centre 880):
        // AutoHold flush left, gears centred under the car (238 wide → 761), belts chip flush right next to the heaters.
        // The two chips appear only when needed (AutoHold on, driver unbuckled); fixed slots keep the gears in place.
        put(autoHold, 434f, 513f, px(240f), px(52f))
        put(gear, 761f, 513f)
        addView(belts, LayoutParams(WRAP, px(52f)).apply {
            gravity = Gravity.TOP or Gravity.END; rightMargin = px(434f); topMargin = px(513f)
        })
        put(doors, 530f, 124f, px(700f), px(46f))
        doors.visibility = View.GONE

        // bottom row
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var cp: StatusCardView? = null
        var aa: StatusCardView? = null
        for ((i, spec) in Apps.list.withIndex()) {
            val (w, v) = when (spec.kind) {
                TileKind.PLAIN -> 169f to AppTileView(ctx, spec, iconLoader(spec)
                    ?: LetterIconDrawable(ctx, ctx.getString(spec.letter), spec.color)) as View
                // Radio + all apps share the old 350 px all-apps slot: 169 + 12 + 169.
                TileKind.RADIO -> 169f to AppTileView(ctx, spec, RadioIconDrawable(ctx)) as View
                TileKind.ALL_APPS -> 169f to AppTileView(ctx, spec, AllAppsIconDrawable(ctx)) as View
                TileKind.CLIMATE -> 440f to climate
                TileKind.CARPLAY -> 270f to StatusCardView(ctx, spec, ctx.getDrawable(R.drawable.car_play_icon)!!,
                    ctx.getDrawable(R.drawable.car_play_dis_icon)!!).also { cp = it }
                TileKind.ANDROID_AUTO -> 270f to StatusCardView(ctx, spec, ctx.getDrawable(R.drawable.android_auto_icon)!!,
                    ctx.getDrawable(R.drawable.android_auto_dis_icon)!!).also { aa = it }
            }
            v.setOnClickListener { onAppClick?.invoke(spec) }
            row.addView(v, LinearLayout.LayoutParams(px(w), px(110f)).apply { if (i > 0) leftMargin = px(12f) })
        }
        carPlay = cp!!
        androidAuto = aa!!
        put(row, 16f, 600f, WRAP, px(110f))
    }

    fun setClock(time: String, dateText: String) {
        clock.setParts(time)
        date.setParts(dateText)
        clock.contentDescription = context.getString(R.string.a11y_clock, time)
        date.contentDescription = context.getString(R.string.a11y_date, dateText)
    }

    /** onPause/onStop: stop every animator (streaks, climate crossfade). */
    fun pauseAnimations() {
        paused = true
        streaks.pause()
        climate.cancelAnimations()
    }

    fun resumeAnimations() {
        paused = false
        updateMotion()
    }

    private fun updateMotion() {
        if (!paused && motionEnabled) streaks.setActive((last?.speedKmh ?: 0) > 0)
    }

    /** Updates only the widgets whose values changed. */
    fun render(n: CarState) {
        val o = last
        last = n
        if (o == null || o.wifiSsid != n.wifiSsid) wifi.set(n.wifiSsid ?: context.getString(R.string.no_wifi), n.wifiSsid != null)
        if (o == null || o.btDevice != n.btDevice) bt.set(n.btDevice ?: context.getString(R.string.no_bt), n.btDevice != null)

        if (o == null || o.tripKm != n.tripKm) tripVal.setParts(oneDecimal(n.tripKm), km)
        if (o == null || o.odometerKm != n.odometerKm) odoVal.setParts(distanceText(n.odometerKm), km)

        if (o == null || o.speedKmh != n.speedKmh) {
            speed.setParts(n.speedKmh?.toString() ?: "—", context.getString(R.string.unit_kmh))
            updateMotion()
        }
        if (o == null || o.rangeKm != n.rangeKm || o.fuelLiters != n.fuelLiters) {
            rangeBig.setParts(n.rangeKm?.toString() ?: "—", context.getString(R.string.unit_km_slash), oneDecimal(n.fuelLiters), context.getString(R.string.unit_l))
            fuelBar.progress = (n.fuelLiters ?: 0f) / by.carhome.data.EcarxCarDataSource.FUEL_TANK_L
        }
        if (o == null || o.lowFuel != n.lowFuel) {
            rangeKey.setParts(if (n.lowFuel) fuelLowText else rangeText)
            rangeKey.setColor(0, if (n.lowFuel) critColor else dimColor)
            rangeKey.translationX = if (n.lowFuel) 33 * d else 0f
            rangeBig.setColor(0, if (n.lowFuel) softCrit else textColor)
            rangeBadge.visibility = if (n.lowFuel) View.VISIBLE else View.GONE
            rangeTile.setBackgroundResource(if (n.lowFuel) R.drawable.tile_warn else R.drawable.tile)
            fuelBar.setFillColor(if (n.lowFuel) critColor else accentColor)
        }

        gear.gear = n.gear
        state.set(n.coolantOverheat, n.oilPressureLow, n.gearboxOverheat, n.tireWarning, n.serviceKm, n.anyCarWarning)
        belts.set(n.beltDriver, n.beltPassenger)
        autoHold.set(n.autoHold)
        doors.set(n.openings)
        if (o == null || o.batteryV != n.batteryV || o.engineRunning != n.engineRunning) {
            val low = n.batteryLevel == by.carhome.data.BatteryLevel.LOW
            battery.set(oneDecimal(n.batteryV), context.getString(R.string.unit_v), low, if (low) critColor else if (n.batteryV == null) dimColor else okColor)
        }
        // Heaters: take the car's state only when it changes (keeps the optimistic toggle until then);
        // a change is the car's answer, so the pending revert is dropped.
        for (i in heaters.indices) {
            val v = heatOf(n, i)
            if (o == null || heatOf(o, i) != v) {
                removeCallbacks(heatRevert[i])
                heaters[i].on = v == true
            }
        }

        climate.set(n.climate)
        carPlay.set(n.carPlayConnected)
        androidAuto.set(n.androidAuto)
    }
}

/** Corner radius of the system nav bar on this head unit (measured from a screenshot: ~20 px at 160 dpi). */
private const val CORNER_DP = 20f

/** How long a tapped heater button waits for the car to confirm before it flips back. */
private const val HEAT_CONFIRM_MS = 3000L
