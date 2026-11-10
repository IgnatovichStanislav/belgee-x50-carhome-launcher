package by.carhome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import by.carhome.R
import by.carhome.data.Opening

/** Squeezes the text horizontally if it does not fit [maxW]. */
private fun Paint.fit(text: String, maxW: Float) {
    textScaleX = 1f
    val w = measureText(text)
    if (w > maxW) textScaleX = maxW / w
}

private fun Context.grouped(n: Int): String {
    val s = kotlin.math.abs(n).toString()
    val sb = StringBuilder()
    for (i in s.indices) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
        sb.append(s[i])
    }
    return sb.toString()
}

/** Red circle with an exclamation mark. */
internal class BadgeView(ctx: Context, private val sizeDp: Float, glyphDp: Float) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.crit) }
    private val glyph = ctx.textPaint(glyphDp, Fonts.bold, R.color.on_accent)

    override fun onMeasure(w: Int, h: Int) {
        val s = context.dpf(sizeDp).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBadge(width / 2f, height / 2f, width / 2f, fill, glyph)
    }
}

/** «Состояние авто» (car status): 2x2 grid (coolant / oil / gearbox / tyres) + distance to service. Flag: true = problem, false = ok, null = unknown. */
internal class StateTileView(ctx: Context) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val names = arrayOf(R.string.state_coolant, R.string.state_oil, R.string.state_gearbox, R.string.state_tires)
        .map { ctx.getString(it) }
    private val problems = arrayOf(R.string.state_overheat, R.string.state_low_pressure, R.string.state_overheat, R.string.state_fault)
        .map { ctx.getString(it) }
    private val okText = ctx.getString(R.string.state_ok)
    private val noDataText = ctx.getString(R.string.state_no_data)
    private val titleText = ctx.getString(R.string.state_title)
    private val serviceLabel = ctx.getString(R.string.service)
    private val kmUnit = " " + ctx.getString(R.string.unit_km)
    private val flags = arrayOfNulls<Boolean>(4)
    private var service: Int? = null
    private var serviceText = "—"
    private var anyWarn = false
    private var inited = false
    private val title = ctx.textPaint(20f, Fonts.regular, R.color.text_dim)
    private val tP = ctx.textPaint(22f, Fonts.regular, R.color.text)
    private val sP = ctx.textPaint(17f, Fonts.regular, R.color.text_dim)
    private val svcL = ctx.textPaint(18f, Fonts.regular, R.color.text_dim)
    private val svcV = ctx.textPaint(18f, Fonts.regular, R.color.text)
    private val glyph = ctx.textPaint(22f, Fonts.bold, R.color.on_accent)
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint().apply { color = ctx.getColor(R.color.line) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ctx.getColor(R.color.on_ok)
    }
    private val path = Path()
    private val okC = ctx.getColor(R.color.ok)
    private val crit = ctx.getColor(R.color.crit)
    private val dim = ctx.getColor(R.color.text_dim)
    private val txt = ctx.getColor(R.color.text)
    private val inactive = ctx.getColor(R.color.inactive)

    init {
        setBackgroundResource(R.drawable.tile)
    }

    fun set(coolant: Boolean?, oil: Boolean?, gearbox: Boolean?, tires: Boolean?, serviceKm: Int?, anyWarn: Boolean) {
        if (inited && flags[0] == coolant && flags[1] == oil && flags[2] == gearbox && flags[3] == tires &&
            service == serviceKm && this.anyWarn == anyWarn
        ) return
        if (!inited || this.anyWarn != anyWarn) {
            setBackgroundResource(if (anyWarn) R.drawable.tile_warn else R.drawable.tile)
        }
        inited = true
        flags[0] = coolant; flags[1] = oil; flags[2] = gearbox; flags[3] = tires
        service = serviceKm
        serviceText = if (serviceKm == null) "—" else context.grouped(serviceKm) + kmUnit
        this.anyWarn = anyWarn
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText(titleText, 25 * d, 37 * d, title)
        for (i in 0 until 4) {
            val x = 25 * d + (i % 2) * 181 * d
            val cy = (72 + (i / 2) * 52) * d
            val cx = x + 17 * d
            val f = flags[i]
            circle.color = when (f) { true -> crit; false -> okC; null -> inactive }
            canvas.drawCircle(cx, cy, 17 * d, circle)
            when (f) {
                false -> {
                    path.reset()
                    path.moveTo(cx - 6.5f * d, cy + 0.5f * d)
                    path.lineTo(cx - 1.8f * d, cy + 5.2f * d)
                    path.lineTo(cx + 6.8f * d, cy - 5f * d)
                    canvas.drawPath(path, tick)
                }
                true -> canvas.drawText("!", cx - glyph.measureText("!") / 2f, glyph.baselineForCenter(cy), glyph)
                null -> canvas.drawText("—", cx - glyph.measureText("—") / 2f, glyph.baselineForCenter(cy), glyph)
            }
            val tx = x + 46 * d
            val status = when (f) { true -> problems[i]; false -> okText; null -> noDataText }
            tP.color = if (f == true) crit else txt
            sP.color = if (f == true) crit else dim
            sP.fit(status, 124 * d)
            canvas.drawText(names[i], tx, cy - 5 * d, tP)
            canvas.drawText(status, tx, cy + 15 * d, sP)
        }
        // service row
        canvas.drawRect(25 * d, 152 * d, width - 25 * d, 153 * d, linePaint)
        canvas.drawText(serviceLabel, 25 * d, 178 * d, svcL)
        canvas.drawText(serviceText, width - 25 * d - svcV.measureText(serviceText), 178 * d, svcV)
    }
}

/**
 * Seat belts tile: two figures, no text. Driver: green / red with "!" and a red tile / grey without data.
 * Passenger: green or neutral grey, never red (the seat has no occupancy sensor).
 */
internal class BeltsView(ctx: Context) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val icons = arrayOf<Drawable>(
        ctx.getDrawable(R.drawable.ic_seat)!!.mutate(), ctx.getDrawable(R.drawable.ic_seat)!!.mutate(),
    )
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.crit) }
    private val glyph = ctx.textPaint(17f, Fonts.bold, R.color.on_accent)
    private val okC = ctx.getColor(R.color.ok)
    private val crit = ctx.getColor(R.color.crit)
    private val inactive = ctx.getColor(R.color.inactive)
    private val neutral = ctx.getColor(R.color.neutral)
    private var driver: Boolean? = null
    private var passenger: Boolean? = null
    private var inited = false

    init {
        contentDescription = ctx.getString(R.string.belts)
    }

    fun set(driver: Boolean?, passenger: Boolean?) {
        if (inited && driver == this.driver && passenger == this.passenger) return
        val warnChanged = !inited || (driver == false) != (this.driver == false)
        inited = true
        this.driver = driver
        this.passenger = passenger
        if (warnChanged) setBackgroundResource(if (driver == false) R.drawable.tile_warn else R.drawable.tile)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val sz = 56 * d
        val gap = 40 * d
        var x = (width - 2 * sz - gap) / 2f
        val top = (height - sz) / 2f
        for (i in 0..1) {
            val color = if (i == 0) when (driver) { true -> okC; false -> crit; null -> inactive }
            else if (passenger == true) okC else neutral
            icons[i].setTint(color)
            icons[i].setBounds(x.toInt(), top.toInt(), (x + sz).toInt(), (top + sz).toInt())
            icons[i].draw(canvas)
            if (i == 0 && driver == false) canvas.drawBadge(x + 48 * d, top + 10 * d, 12 * d, fill, glyph)
            x += sz + gap
        }
    }
}

/** AutoHold: dimmed «выкл» at 0/null; 1 = armed (blue ring), 2 = holding (green). */
internal class AutoHoldView(ctx: Context) : View(ctx) {
    private val armedLabel = ctx.getString(R.string.autohold_armed)
    private val holdingText = ctx.getString(R.string.autohold_holding)
    private val offText = ctx.getString(R.string.autohold_off)
    private val d = ctx.dpf(1f)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letter = ctx.textPaint(20f, Fonts.regular, R.color.accent)
    private val title = ctx.textPaint(24f, Fonts.regular, R.color.text)
    private val sub = ctx.textPaint(18f, Fonts.regular, R.color.armed_text)
    private val accent = ctx.getColor(R.color.accent)
    private val okC = ctx.getColor(R.color.ok)
    private val armedText = ctx.getColor(R.color.armed_text)
    private val inactive = ctx.getColor(R.color.inactive)
    private val txt = ctx.getColor(R.color.text)
    private val onOk = ctx.getColor(R.color.hold_ring_text)
    private var st = -1

    fun set(level: Int?) {
        val v = if (level == 1 || level == 2) level else 0
        if (v == st) return
        st = v
        if (v == 0) {
            setBackgroundResource(R.drawable.tile)
            ring.color = inactive; letter.color = inactive; sub.color = inactive; title.color = inactive
        } else if (v == 1) {
            title.color = txt
            setBackgroundResource(R.drawable.tile_armed)
            ring.color = accent; letter.color = accent; sub.color = armedText
        } else if (v == 2) {
            title.color = txt
            setBackgroundResource(R.drawable.tile_hold)
            ring.color = okC; letter.color = onOk; sub.color = okC
            ringFill.color = okC
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Tile 402x94: ring 44 at the left padding 25, two text lines from x85.
        val cx = 47 * d
        val cy = height / 2f
        if (st == 2) canvas.drawCircle(cx, cy, 22 * d, ringFill)
        canvas.drawCircle(cx, cy, 20.5f * d, ring)
        canvas.drawText("A", cx - letter.measureText("A") / 2f, letter.baselineForCenter(cy), letter)
        canvas.drawText("AutoHold", 85 * d, cy - 2 * d, title)
        canvas.drawText(if (st == 1) armedLabel else if (st == 2) holdingText else offText, 85 * d, cy + 21 * d, sub)
    }
}

/** Heater button: grey icon when off, amber when on. */
internal class HeaterButton(ctx: Context, iconRes: Int, private val label: String) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val icon = ctx.getDrawable(iconRes)!!.mutate()
    private val p = ctx.textPaint(17f, Fonts.regular, R.color.text_dim)
    private val dim = ctx.getColor(R.color.text_dim)
    private val warn = ctx.getColor(R.color.warn_text)
    var on = false
        set(v) {
            if (v != field || !inited) {
                field = v
                inited = true
                setBackgroundResource(if (v) R.drawable.tile_heat_on else R.drawable.tile_heat)
                p.color = if (v) warn else dim
                icon.setTint(if (v) warn else dim)
                contentDescription = context.getString(if (v) R.string.a11y_heat_on else R.string.a11y_heat_off, label)
                invalidate()
            }
        }
    private var inited = false

    init {
        on = false
        setPressRipple(20f)
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val s = (40 * d).toInt()
        val top = ((height - 66 * d) / 2f).toInt()
        val l = (width - s) / 2
        icon.setBounds(l, top, l + s, top + s)
        icon.draw(canvas)
        canvas.drawText(label, (width - p.measureText(label)) / 2f, top + 40 * d + 4 * d + 16 * d, p)
    }
}

/** Banner above the car: «Открыта дверь водителя» / «Открыто: …». Hidden when nothing is open. */
internal class DoorBanner(ctx: Context) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val p = ctx.textPaint(21f, Fonts.regular, R.color.crit_soft)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.door_bg) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = ctx.getColor(R.color.crit)
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.crit) }
    private val glyph = ctx.textPaint(17f, Fonts.bold, R.color.on_accent)
    private val rect = RectF()
    private var text = ""
    private var ids: Set<Opening> = emptySet()

    fun set(openings: Set<Opening>) {
        if (openings == ids) return
        ids = openings
        if (openings.isEmpty()) {
            text = ""
            visibility = GONE
            return
        }
        val list = openings.sortedBy { it.ordinal }
        text = if (list.size == 1) {
            context.getString(list[0].single)
        } else {
            context.getString(R.string.openings_many, list.joinToString(", ") { context.getString(it.label) })
        }
        p.fit(text, width - 2 * (20 + 24 + 8 + 20) * d)
        visibility = VISIBLE
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (text.isNotEmpty()) p.fit(text, w - 2 * (20 + 24 + 8 + 20) * d)
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val tw = p.measureText(text)
        val bw = 20 * d + 24 * d + 8 * d + tw + 20 * d
        val l = (width - bw) / 2f
        rect.set(l + d, d, l + bw - d, height - d)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, bg)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, line)
        canvas.drawBadge(l + 20 * d + 12 * d, height / 2f, 12 * d, badge, glyph)
        canvas.drawText(text, l + 20 * d + 32 * d, p.baselineForCenter(height / 2f), p)
    }
}
