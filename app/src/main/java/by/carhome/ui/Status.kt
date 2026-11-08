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
    private val names = arrayOf("ОЖ", "Масло", "КПП", "Шины")
    private val problems = arrayOf("Перегрев", "Низкое давление", "Перегрев", "Неисправность")
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
        serviceText = if (serviceKm == null) "—" else context.grouped(serviceKm) + " км"
        this.anyWarn = anyWarn
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText("Состояние авто", 25 * d, 37 * d, title)
        for (i in 0 until 4) {
            val x = 25 * d + (i % 2) * 181 * d
            val cy = (86 + (i / 2) * 66) * d
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
            val status = when (f) { true -> problems[i]; false -> "Норма"; null -> "нет данных" }
            tP.color = if (f == true) crit else txt
            sP.color = if (f == true) crit else dim
            sP.fit(status, 124 * d)
            canvas.drawText(names[i], tx, cy - 5 * d, tP)
            canvas.drawText(status, tx, cy + 15 * d, sP)
        }
        // service row
        canvas.drawRect(25 * d, 192 * d, width - 25 * d, 193 * d, linePaint)
        canvas.drawText("До ТО", 25 * d, 216 * d, svcL)
        canvas.drawText(serviceText, width - 25 * d - svcV.measureText(serviceText), 216 * d, svcV)
    }
}

/**
 * «Ремни» (seat belts). Driver: green / red with "!" and a red chip border / grey without data.
 * Passenger: green or neutral grey, never red (the seat has no occupancy sensor).
 */
internal class BeltsView(ctx: Context) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val label = ctx.textPaint(20f, Fonts.regular, R.color.text_dim)
    private val icons = arrayOf<Drawable>(
        ctx.getDrawable(R.drawable.ic_seat)!!.mutate(), ctx.getDrawable(R.drawable.ic_seat)!!.mutate(),
    )
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.crit) }
    private val glyph = ctx.textPaint(11f, Fonts.bold, R.color.on_accent)
    private val okC = ctx.getColor(R.color.ok)
    private val crit = ctx.getColor(R.color.crit)
    private val inactive = ctx.getColor(R.color.inactive)
    private val neutral = ctx.getColor(R.color.neutral)
    private val dim = ctx.getColor(R.color.text_dim)
    private var driver: Boolean? = null
    private var passenger: Boolean? = null
    private var inited = false

    fun set(driver: Boolean?, passenger: Boolean?) {
        if (inited && driver == this.driver && passenger == this.passenger) return
        val warnChanged = !inited || (driver == false) != (this.driver == false)
        inited = true
        this.driver = driver
        this.passenger = passenger
        if (warnChanged) {
            val warn = driver == false
            setBackgroundResource(if (warn) R.drawable.chip_warn else R.drawable.chip)
            label.color = if (warn) crit else dim
        }
        invalidate()
    }

    /** Wraps the content: label + two seat icons, no trailing space. */
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = 16 * d + label.measureText("Ремни") + 12 * d + 30 * d + 12 * d + 30 * d + 16 * d
        setMeasuredDimension(kotlin.math.ceil(w).toInt(), MeasureSpec.getSize(hSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val pad = (if (driver == false) 17 else 16) * d
        canvas.drawText("Ремни", pad, label.baselineForCenter(cy), label)
        var x = pad + label.measureText("Ремни") + 12 * d
        val sz = (30 * d).toInt()
        for (i in 0..1) {
            val color = if (i == 0) when (driver) { true -> okC; false -> crit; null -> inactive }
            else if (passenger == true) okC else neutral
            icons[i].setTint(color)
            val top = (cy - sz / 2f).toInt()
            icons[i].setBounds(x.toInt(), top, x.toInt() + sz, top + sz)
            icons[i].draw(canvas)
            // Small badge kept inside the icon box so it does not merge with the red chip border.
            if (i == 0 && driver == false) canvas.drawBadge(x + sz - 4 * d, top + 5 * d, 7 * d, fill, glyph)
            x += sz + 12 * d
        }
    }
}

/** AutoHold: dimmed «выкл» at 0/null; 1 = armed (blue ring), 2 = holding (green). */
internal class AutoHoldView(ctx: Context) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letter = ctx.textPaint(17f, Fonts.regular, R.color.accent)
    private val title = ctx.textPaint(19f, Fonts.regular, R.color.text)
    private val sub = ctx.textPaint(15f, Fonts.regular, R.color.armed_text)
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
            setBackgroundResource(R.drawable.chip)
            ring.color = inactive; letter.color = inactive; sub.color = inactive; title.color = inactive
        } else if (v == 1) {
            title.color = txt
            setBackgroundResource(R.drawable.chip_armed)
            ring.color = accent; letter.color = accent; sub.color = armedText
        } else if (v == 2) {
            title.color = txt
            setBackgroundResource(R.drawable.chip_hold)
            ring.color = okC; letter.color = onOk; sub.color = okC
            ringFill.color = okC
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = 33 * d
        val cy = height / 2f
        if (st == 2) canvas.drawCircle(cx, cy, 16 * d, ringFill)
        canvas.drawCircle(cx, cy, 14.75f * d, ring)
        canvas.drawText("A", cx - letter.measureText("A") / 2f, letter.baselineForCenter(cy), letter)
        canvas.drawText("AutoHold", 61 * d, 21.5f * d, title)
        canvas.drawText(if (st == 1) "готов" else if (st == 2) "держит" else "выкл", 61 * d, 39.5f * d, sub)
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
                invalidate()
            }
        }
    private var inited = false

    init {
        on = false
        setPressRipple(20f)
        isClickable = true
        contentDescription = label
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
            val o = list[0]
            (if (o == Opening.TRUNK || o == Opening.HOOD) "Открыт " else "Открыта ") + o.label
        } else {
            "Открыто: " + list.joinToString(", ") { it.label }
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
