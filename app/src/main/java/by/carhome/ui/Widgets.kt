package by.carhome.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import by.carhome.R
import by.carhome.data.Gear
import kotlin.math.ceil

/**
 * One font everywhere (design: regular only). res/font/bg_sans.ttf = the HU's GEELY_Regular (Latin,
 * digits) + Tektur Cyrillic (OFL), since no Geely font has Cyrillic; built by tools/make_font.py.
 * Until [init] runs (e.g. in previews) the system sans-serif is used.
 */
internal object Fonts {
    var regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private set
    val light: Typeface get() = regular
    val medium: Typeface get() = regular
    val bold: Typeface get() = regular

    fun init(ctx: Context) {
        runCatching { ctx.resources.getFont(R.font.bg_sans) }.getOrNull()?.let { regular = it }
    }
}

internal fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

internal fun Context.textPaint(sizeDp: Float, tf: Typeface, colorRes: Int): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpf(sizeDp)
        typeface = tf
        color = getColor(colorRes)
    }

/** Baseline that centres the text vertically around [cy]. */
internal fun Paint.baselineForCenter(cy: Float): Float = cy - (ascent() + descent()) / 2f

internal fun View.setPressRipple(radiusDp: Float) {
    val mask = GradientDrawable().apply {
        setColor(0xFFFFFFFF.toInt())
        cornerRadius = context.dpf(radiusDp)
    }
    foreground = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.ripple)), null, mask)
}

internal fun Canvas.drawBadge(cx: Float, cy: Float, r: Float, fill: Paint, glyph: Paint) {
    drawCircle(cx, cy, r, fill)
    drawText("!", cx - glyph.measureText("!") / 2f, glyph.baselineForCenter(cy), glyph)
}

/**
 * Text of alternating parts: even = value (paints[0]), odd = unit (paints[1], with [gapL]/[gapR]).
 * Calls requestLayout only when the width changed and [fixedW] == 0.
 */
internal class RunView(
    ctx: Context,
    private val paints: Array<Paint>,
    private val gapL: Float = 0f,
    private val gapR: Float = 0f,
    private val alignEnd: Boolean = false,
    val fixedW: Int = 0,
) : View(ctx) {
    private var parts: Array<out String> = emptyArray()
    private var contentW = 0f
    val ascent: Float = -paints[0].ascent()
    private val hgt = ceil(paints[0].descent() - paints[0].ascent()).toInt()

    fun setParts(vararg p: String) {
        if (p.contentEquals(parts)) return
        parts = p
        var w = 0f
        for (i in p.indices) {
            w += paintAt(i).measureText(p[i])
            if (i % 2 == 1) w += gapL + gapR
        }
        contentW = w
        if (fixedW == 0 && ceil(w).toInt() != measuredWidth) requestLayout()
        invalidate()
    }

    fun setColor(idx: Int, c: Int) {
        if (paints[idx].color != c) {
            paints[idx].color = c
            invalidate()
        }
    }

    private fun paintAt(i: Int) = paints[if (paints.size > 1) i % 2 else 0]

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(if (fixedW > 0) fixedW else ceil(contentW).toInt(), hgt)
    }

    override fun onDraw(canvas: Canvas) {
        // Fixed-width runs shrink to fit (e.g. «450 км / 45.4 л» on a full tank), scaled around the baseline.
        val k = if (fixedW > 0 && contentW > width && contentW > 0f) width / contentW else 1f
        if (k < 1f) {
            canvas.save()
            canvas.scale(k, k, 0f, ascent)
        }
        var x = if (alignEnd) width / k - contentW else 0f
        for (i in parts.indices) {
            if (i % 2 == 1) x += gapL
            val p = paintAt(i)
            canvas.drawText(parts[i], x, ascent, p)
            x += p.measureText(parts[i])
            if (i % 2 == 1) x += gapR
        }
        if (k < 1f) canvas.restore()
    }
}

/** "Label + value" group with a vertical divider on the left. Children are added by the caller. */
internal class InfoGroup(
    ctx: Context,
    private val heightPx: Int,
    private val divider: Boolean,
    trailingPad: Boolean = true,
) : android.widget.FrameLayout(ctx) {
    private val linePaint = Paint().apply { color = ctx.getColor(R.color.line) }

    init {
        setWillNotDraw(false)
        val pad = ctx.dpf(28f).toInt()
        setPadding(pad, 0, if (trailingPad) pad else 0, 0)
    }

    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        if (divider) canvas.drawRect(0f, 0f, context.dpf(1f), height.toFloat(), linePaint)
    }
}

/** Wi-Fi / Bluetooth: icon + name (or a "not connected" text). */
internal class StatusRow(ctx: Context, iconRes: Int) : View(ctx) {
    private val icon = ctx.getDrawable(iconRes)!!.mutate()
    private val paint = ctx.textPaint(22f, Fonts.regular, R.color.icon_2)
    private val on = ctx.getColor(R.color.icon_2)
    private val off = ctx.getColor(R.color.inactive)
    private val iconOn = ctx.getColor(R.color.status_icon)
    private val iconSize = ctx.dpf(28f)
    private val gap = ctx.dpf(8f)
    private val h = ctx.dpf(38f).toInt()
    private var text = ""
    private var active = false

    fun set(text: String, active: Boolean) {
        if (text == this.text && active == this.active) return
        val oldW = measuredWidth
        this.text = text
        this.active = active
        paint.color = if (active) on else off
        icon.setTint(if (active) iconOn else off)
        if (ceil(iconSize + gap + paint.measureText(text)).toInt() != oldW) requestLayout()
        invalidate()
    }

    override fun onMeasure(w: Int, hs: Int) {
        setMeasuredDimension(ceil(iconSize + gap + paint.measureText(text)).toInt(), h)
    }

    override fun onDraw(canvas: Canvas) {
        val top = (height - iconSize) / 2f
        icon.setBounds(0, top.toInt(), iconSize.toInt(), (top + iconSize).toInt())
        icon.draw(canvas)
        canvas.drawText(text, iconSize + gap, paint.baselineForCenter(height / 2f), paint)
    }
}

internal class ProgressBarView(ctx: Context, trackColorRes: Int, fillColorRes: Int, private val barHDp: Float = 0f) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(trackColorRes) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(fillColorRes) }
    private val markP = Paint().apply { color = (ctx.getColor(R.color.text) and 0x00FFFFFF) or 0xB3000000.toInt() }
    private val clip = Path()
    private val rect = RectF()
    private val d = ctx.dpf(1f)
    private var barTop = 0f
    private var barBottom = 0f

    /** Position of a threshold tick as a fraction of the bar (negative = none). */
    var mark = -1f
        set(v) { if (v != field) { field = v; invalidate() } }

    fun setFillColor(c: Int) {
        if (fill.color != c) {
            fill.color = c
            invalidate()
        }
    }

    var progress = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c != field) {
                field = c
                invalidate()
            }
        }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val bh = if (barHDp > 0f) barHDp * d else h.toFloat()
        barTop = (h - bh) / 2f
        barBottom = barTop + bh
        rect.set(0f, barTop, w.toFloat(), barBottom)
        clip.reset()
        clip.addRoundRect(rect, bh / 2f, bh / 2f, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(clip, track)
        if (progress > 0f) {
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(0f, barTop, width * progress, barBottom, fill)
            canvas.restore()
        }
        if (mark >= 0f) {
            val x = width * mark
            canvas.drawRect(x - d, barTop - 4 * d, x + d, barBottom + 4 * d, markP)
        }
    }
}

internal class GearView(ctx: Context) : View(ctx) {
    private val letters = arrayOf("P", "R", "N", "D")
    private val cell = ctx.dpf(52f)
    private val gap = ctx.dpf(8f)
    private val radius = ctx.dpf(14f)
    private val stroke = ctx.dpf(1f)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = ctx.getColor(R.color.line)
    }
    private val activeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.accent) }
    private val cellFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.chip_bg) }
    private val activeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = ctx.getColor(R.color.accent)
    }
    private val idle = ctx.textPaint(26f, Fonts.regular, R.color.inactive)
    private val active = ctx.textPaint(26f, Fonts.medium, R.color.on_accent)
    private val rect = RectF()
    var gear: Gear? = null
        set(v) {
            if (v != field) {
                field = v
                invalidate()
            }
        }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((cell * 4 + gap * 3).toInt(), cell.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        for (i in letters.indices) {
            val l = i * (cell + gap)
            val on = gear?.ordinal == i
            val half = stroke / 2f
            rect.set(l + half, half, l + cell - half, cell - half)
            canvas.drawRoundRect(rect, radius, radius, cellFill)
            if (on) canvas.drawRoundRect(rect, radius, radius, activeFill)
            canvas.drawRoundRect(rect, radius, radius, if (on) activeBorder else border)
            val p = if (on) active else idle
            canvas.drawText(letters[i], l + (cell - p.measureText(letters[i])) / 2f, p.baselineForCenter(cell / 2f), p)
        }
    }
}
