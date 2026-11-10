package by.carhome.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import by.carhome.AppSpec
import by.carhome.R
import by.carhome.data.Airflow
import by.carhome.data.ClimateState
import by.carhome.data.LinkStatus
import java.util.Locale
import kotlin.math.ceil

private fun fmt1(v: Float?): String = if (v == null) "—" else String.format(Locale.US, "%.1f", v)

/** Every value the climate card can show (HVAC range 15.0..33.0 in 0.5 steps): sizes the fixed temperature slot. */
private val SLOT_VALUES: List<String> = (30..66).map { fmt1(it / 2f) } + "—"

/** Tabular figures by hand (the Geely font has proportional digits): each digit sits centred in a max-digit-wide cell. */
private fun Paint.digitCell(): Float = (0..9).maxOf { measureText(it.toString()) }

private fun Paint.tabularWidth(s: String): Float {
    val cell = digitCell()
    return s.sumOf { (if (it.isDigit()) cell else measureText(it.toString())).toDouble() }.toFloat()
}

private fun Canvas.drawTabular(s: String, x: Float, y: Float, p: Paint) {
    val cell = p.digitCell()
    var cx = x
    for (ch in s) {
        val t = ch.toString()
        val w = p.measureText(t)
        if (ch.isDigit()) { drawText(t, cx + (cell - w) / 2f, y, p); cx += cell } else { drawText(t, cx, y, p); cx += w }
    }
}

/** Battery tile. */
internal class StatTileView(ctx: Context, private val lines: List<String>) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val label = ctx.textPaint(20f, Fonts.regular, R.color.text_dim)
    private val valueP = ctx.textPaint(52f, Fonts.medium, R.color.text)
    private val unitP = ctx.textPaint(24f, Fonts.regular, R.color.text_dim)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.crit) }
    private val glyph = ctx.textPaint(17f, Fonts.bold, R.color.on_accent)
    private var value = "—"
    private var unit = ""
    private var warn = false

    init {
        setBackgroundResource(R.drawable.tile)
    }

    fun set(value: String, unit: String, warn: Boolean, valueColor: Int) {
        if (value == this.value && unit == this.unit && warn == this.warn && valueP.color == valueColor) return
        if (warn != this.warn) setBackgroundResource(if (warn) R.drawable.tile_warn else R.drawable.tile)
        this.value = value; this.unit = unit; this.warn = warn
        valueP.color = valueColor
        invalidate()
    }

    // Half-width tile: label on top, value under it, both left-aligned.
    override fun onDraw(canvas: Canvas) {
        val x0 = 25 * d
        val lb = 42 * d
        val vb = 96 * d
        val off = if (warn) 32 * d else 0f
        canvas.drawText(lines[0], x0 + off, lb, label)
        if (warn) canvas.drawBadge(x0 + 12 * d, lb - 6 * d, 12 * d, badge, glyph)
        canvas.drawText(value, x0, vb, valueP)
        canvas.drawText(unit, x0 + valueP.measureText(value) + 6 * d, vb, unitP)
    }
}

/** Fallback icon: coloured rounded square with a letter. */
internal class LetterIconDrawable(ctx: Context, private val letter: String, colorRes: Int) : Drawable() {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(colorRes) }
    private val txt = ctx.textPaint(24f, Fonts.bold, R.color.on_accent)
    private val rect = RectF()
    private val d = ctx.dpf(1f)
    override fun getIntrinsicWidth() = (50 * d).toInt()
    override fun getIntrinsicHeight() = (50 * d).toInt()
    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b)
        val k = b.width() / (50 * d)
        canvas.drawRoundRect(rect, 16 * d * k, 16 * d * k, bg)
        txt.textSize = 24 * d * k
        canvas.drawText(letter, rect.centerX() - txt.measureText(letter) / 2f, txt.baselineForCenter(rect.centerY()), txt)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal class AppTileView(ctx: Context, spec: AppSpec, private val icon: Drawable, private val label: String? = null) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val labelP = ctx.textPaint(22f, Fonts.regular, R.color.text)

    init {
        setBackgroundResource(R.drawable.tile)
        setPressRipple(22f)
        isClickable = true
        contentDescription = ctx.getString(spec.label).replace('\n', ' ') // label is not drawn: icon only
    }

    override fun onDraw(canvas: Canvas) {
        val s = (72 * d).toInt()
        val t = (height - s) / 2
        val tw = if (label != null) labelP.measureText(label) + 16 * d else 0f
        val l = ((width - s - tw) / 2f).toInt()
        icon.setBounds(l, t, l + s, t + s)
        icon.draw(canvas)
        if (label != null) canvas.drawText(label, l + s + 16 * d, labelP.baselineForCenter(height / 2f), labelP)
    }
}

/** CarPlay / Android Auto card. */
internal class StatusCardView(ctx: Context, private val spec: AppSpec, private val on: Drawable, private val off: Drawable) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val title = ctx.textPaint(22f, Fonts.regular, R.color.text)
    private val name = ctx.getString(spec.label)
    private val status = ctx.textPaint(17f, Fonts.regular, R.color.text_dim)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dim = ctx.getColor(R.color.text_dim)
    private val okC = ctx.getColor(R.color.ok)
    private val inactive = ctx.getColor(R.color.inactive)
    private val warnC = ctx.getColor(R.color.warn)
    private var link: LinkStatus? = null
    private val connected: Boolean? get() = link?.let { it == LinkStatus.CONNECTED }

    init {
        setPressRipple(22f)
        isClickable = true
    }

    fun set(connected: Boolean) = set(if (connected) LinkStatus.CONNECTED else LinkStatus.DISCONNECTED)

    fun set(s: LinkStatus) {
        if (s == link) return
        link = s
        val on = s == LinkStatus.CONNECTED
        setBackgroundResource(if (on) R.drawable.tile_connected else R.drawable.tile)
        status.color = when (s) {
            LinkStatus.CONNECTED -> okC
            LinkStatus.CONNECTING, LinkStatus.LOST -> warnC
            else -> dim
        }
        dot.color = when (s) {
            LinkStatus.CONNECTED -> okC
            LinkStatus.CONNECTING, LinkStatus.LOST -> warnC
            else -> inactive
        }
        contentDescription = context.getString(R.string.a11y_link, name.replace('\n', ' '), context.getString(s.label))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, (height - 112 * d) / 2f)
        val c = connected == true
        val ic: Drawable = if (c) on else off
        val s = (60 * d).toInt()
        ic.setBounds((17 * d).toInt(), (26 * d).toInt(), (17 * d).toInt() + s, (26 * d).toInt() + s)
        ic.draw(canvas)
        val tx = 89 * d
        // Narrow cards (270 px) with the wide ECARX font: shrink the title to the space left of the edge.
        val avail = width - tx - 14 * d
        val tw = title.measureText(name)
        if (tw > avail && avail > 0) {
            canvas.save()
            canvas.scale(avail / tw, avail / tw, tx, 51.8f * d)
            canvas.drawText(name, tx, 51.8f * d, title)
            canvas.restore()
        } else {
            canvas.drawText(name, tx, 51.8f * d, title)
        }
        canvas.drawCircle(tx + 5 * d, 72.6f * d, 5 * d, dot)
        canvas.drawText(context.getString((link ?: LinkStatus.DISCONNECTED).label), tx + 18 * d, 79.6f * d, status)
        canvas.restore()
    }
}

/** Live climate card. */
internal class ClimateCardView(ctx: Context) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val ct = ctx.textPaint(64f, Fonts.light, R.color.text)
    private val offP = ctx.textPaint(18f, Fonts.regular, R.color.inactive)
    private val inactiveC = ctx.getColor(R.color.inactive)
    private val textC = ctx.getColor(R.color.text)
    private val dimC = ctx.getColor(R.color.text_dim)
    private val iconC = ctx.getColor(R.color.icon_2)
    private val ctSmall = ctx.textPaint(28f, Fonts.regular, R.color.text_dim)
    private val dualP = ctx.textPaint(38f, Fonts.light, R.color.text)
    private val em = ctx.textPaint(16f, Fonts.medium, R.color.text_dim)
    private val keyDriver = ctx.getString(R.string.climate_driver)
    private val keyPassenger = ctx.getString(R.string.climate_passenger)
    private val offText = ctx.getString(R.string.climate_off)
    private val chipP = ctx.textPaint(18f, Fonts.medium, R.color.chip_text_off)
    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colOn = ctx.getColor(R.color.fan_on)
    private val colOff = ctx.getColor(R.color.fan_off)
    private val chipOnText = ctx.getColor(R.color.chip_text_on)
    private val chipOffText = ctx.getColor(R.color.chip_text_off)
    private val chipOnBg = ctx.getColor(R.color.chip_on_bg)
    private val chipOnLine = ctx.getColor(R.color.chip_on_border)
    private val chipOffLine = ctx.getColor(R.color.line)
    private val fan = ctx.getDrawable(R.drawable.ic_fan)!!.mutate().apply { setTint(ctx.getColor(R.color.icon_2)) }
    // Airflow icon in the stock ECARX/Lunaris style: seat + three arrows (windshield / face / feet).
    private val fmBase = android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.hvac_fm_base)
    private val fmFace = android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.hvac_fm_face)
    private val fmFoot = android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.hvac_fm_foot)
    private val fmOn = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.PorterDuffColorFilter(ctx.getColor(R.color.icon_2), android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private val fmOff = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.PorterDuffColorFilter(ctx.getColor(R.color.fan_off), android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private val fmDim = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.PorterDuffColorFilter(ctx.getColor(R.color.inactive), android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private val fmDst = RectF()

    /** Draws the 68×68 layer set scaled into [size] at ([x],[y]). */
    private fun drawAirflow(canvas: Canvas, a: Airflow, x: Float, y: Float, size: Float, dim: Boolean = false) {
        val base = if (dim) fmDim else fmOn
        fmDst.set(x, y, x + size, y + size)
        canvas.drawBitmap(fmBase, null, fmDst, base)
        canvas.drawBitmap(fmFace, null, fmDst, if (a.face && !dim) fmOn else if (dim) fmDim else fmOff)
        canvas.drawBitmap(fmFoot, null, fmDst, if (a.feet && !dim) fmOn else if (dim) fmDim else fmOff)
        // Windshield arrow: the face arrow lifted and tilted upwards.
        val k = size / 68f
        canvas.save()
        canvas.translate(0f, -10f * k)
        canvas.rotate(-28f, x + 24f * k, y + 26f * k)
        canvas.drawBitmap(fmFace, null, fmDst, if (dim) fmDim else if (a.window) fmOn else fmOff)
        canvas.restore()
    }
    private val rect = RectF()
    private val clip = android.graphics.Path()
    private val chips = arrayOf("A/C", "AUTO", ctx.getString(R.string.climate_recirc))
    private var state: ClimateState? = null
    private var tempW = 0f
    private var t1 = ""
    private var t2 = ""
    private val ctBase = ct.textSize
    private val ctSmallBase = ctSmall.textSize
    private val dualBase = dualP.textSize
    private val digitBox = Rect()
    private val degBox = Rect()

    init {
        setBackgroundResource(R.drawable.tile)
        setPressRipple(22f)
        isClickable = true
    }

    /** Kept for HomeActivity's onPause/onStop; the airflow glow layer was removed (looked like a smudge by day). */
    fun cancelAnimations() {}

    fun set(c: ClimateState) {
        if (c == state) return
        state = c
        contentDescription = when {
            !c.power -> context.getString(R.string.a11y_climate_off)
            c.dualZone -> context.getString(R.string.a11y_climate_dual, fmt1(c.tempLeft), fmt1(c.tempRight), c.fanLevel)
            else -> context.getString(R.string.a11y_climate, fmt1(c.tempLeft), c.fanLevel)
        }
        measureTemp()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 22 * d, 22 * d, android.graphics.Path.Direction.CW)
        measureTemp()
    }

    private fun infoW(): Float = maxOf(50 * d + 34 * d + 12 * d + 7 * 9 * d + 6 * 4 * d, chipsW())

    /**
     * The temperature slot is sized once for the widest possible value, so changing the temperature never moves
     * the rest of the card or changes the font size. The value is drawn right-aligned in the slot.
     */
    private fun measureTemp() {
        val c = state ?: return
        t1 = fmt1(c.tempLeft) + if (c.dualZone) "°" else ""
        t2 = fmt1(c.tempRight) + "°"
        ct.textSize = ctBase; ctSmall.textSize = ctSmallBase; dualP.textSize = dualBase
        tempW = slotWidth(c.dualZone)
        val avail = width - 2 * 16 * d - 22 * d - infoW()
        if (width > 0 && tempW > avail && avail > 0) {
            val k = avail / tempW
            ct.textSize = ctBase * k; ctSmall.textSize = ctSmallBase * k; dualP.textSize = dualBase * k
            tempW = slotWidth(c.dualZone)
        }
    }

    private fun keyW() = maxOf(em.measureText(keyDriver), em.measureText(keyPassenger)) + 6 * d

    private fun slotWidth(dual: Boolean): Float {
        var w = 0f
        for (v in SLOT_VALUES) w = maxOf(w, if (dual) dualP.tabularWidth("$v°") else ct.tabularWidth(v))
        return if (dual) keyW() + w else w + ctSmall.measureText("°")
    }

    private fun chipsW(): Float {
        var w = 0f
        for (c in chips) w += chipP.measureText(c) + 22 * d + 12 * d
        return w - 12 * d
    }

    private fun chip(canvas: Canvas, i: Int, x: Float, on: Boolean, dimAll: Boolean = false): Float {
        val w = chipP.measureText(chips[i]) + 22 * d
        rect.set(x + d / 2, 63 * d + d / 2, x + w - d / 2, 97 * d - d / 2)
        if (on) {
            chipFill.color = chipOnBg
            canvas.drawRoundRect(rect, 9 * d, 9 * d, chipFill)
        }
        chipLine.color = if (on) chipOnLine else chipOffLine
        canvas.drawRoundRect(rect, 9 * d, 9 * d, chipLine)
        chipP.color = if (on) chipOnText else if (dimAll) inactiveC else chipOffText
        canvas.drawText(chips[i], x + 11 * d, chipP.baselineForCenter(80 * d), chipP)
        return w
    }

    override fun onDraw(canvas: Canvas) {
        val c = state ?: return
        // Climate switched off: same layout, everything dimmed, «выкл» instead of the fan bars.
        val off = !c.power
        ct.color = if (off) inactiveC else textC
        dualP.color = ct.color
        ctSmall.color = if (off) inactiveC else dimC
        em.color = ctSmall.color
        fan.setTint(if (off) inactiveC else iconC)
        canvas.save()
        canvas.translate(0f, (height - 112 * d) / 2f)
        // Row 1 geometry (relative to cinfo x): airflow icon, fan, 7 bars.
        val infoW = infoW()
        // The whole content is centred in the card.
        val x0 = (width - (tempW + 22 * d + infoW)) / 2f
        if (c.dualZone) {
            val right = x0 + tempW
            canvas.drawText(keyDriver, x0, 44f * d, em)
            val mid = (x0 + keyW() + right) / 2f
            canvas.drawTabular(t1, mid - dualP.tabularWidth(t1) / 2f, 44f * d, dualP)
            canvas.drawText(keyPassenger, x0, 88f * d, em)
            canvas.drawTabular(t2, mid - dualP.tabularWidth(t2) / 2f, 88f * d, dualP)
        } else {
            // All "dd.d" values have the same tabular width, so the number is centred in the slot and never moves.
            val vw = ct.tabularWidth(t1) + ctSmall.measureText("°")
            val tx = x0 + (tempW - vw) / 2f
            canvas.drawTabular(t1, tx, 75f * d, ct)
            // Small «°» on the baseline reads as a dot, so its top is aligned with the top of the digits.
            ct.getTextBounds("0", 0, 1, digitBox)
            ctSmall.getTextBounds("°", 0, 1, degBox)
            canvas.drawText("°", tx + ct.tabularWidth(t1), 75f * d + digitBox.top - degBox.top, ctSmall)
        }
        val cx = x0 + tempW + 22 * d
        val s = (34 * d).toInt()
        val top = (17 * d).toInt()
        drawAirflow(canvas, c.airflow, cx - 10 * d, 3 * d, 62 * d, off)
        val fx = (cx + 50 * d).toInt()
        fan.setBounds(fx, top, fx + s, top + s)
        fan.draw(canvas)
        val bx = fx + s + 12 * d
        if (off) {
            canvas.drawText(offText, bx, offP.baselineForCenter(34 * d), offP)
        } else {
            for (i in 0 until 7) {
                val h = (12 + 4 * i) * d
                barPaint.color = if (i < c.fanLevel) colOn else colOff
                rect.set(bx + i * 13 * d, 52 * d - h, bx + i * 13 * d + 9 * d, 52 * d)
                canvas.drawRoundRect(rect, 2 * d, 2 * d, barPaint)
            }
        }
        var x = cx
        x += chip(canvas, 0, x, c.ac && !off, off) + 12 * d
        x += chip(canvas, 1, x, c.auto && !off, off) + 12 * d
        chip(canvas, 2, x, c.recirc && !off, off)
        canvas.restore()
    }
}

/** Radio: same dark 72 px square as all apps, with the Material Symbols "radio" glyph. */
internal class RadioIconDrawable(ctx: Context) : Drawable() {
    private val d = ctx.dpf(1f)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.allapps_bg) }
    private val glyph = ctx.getDrawable(R.drawable.ic_radio)!!
    private val rect = RectF()
    override fun getIntrinsicWidth() = (72 * d).toInt()
    override fun getIntrinsicHeight() = (72 * d).toInt()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val k = b.width() / (72 * d)
        rect.set(b)
        canvas.drawRoundRect(rect, 16 * d * k, 16 * d * k, bg)
        val inset = (18 * d * k).toInt()
        glyph.setBounds(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        glyph.draw(canvas)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** «Все приложения» (all apps): dark 72 px square with a 2x2 grid. */
internal class AllAppsIconDrawable(ctx: Context) : Drawable() {
    private val d = ctx.dpf(1f)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.allapps_bg) }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.allapps_fg) }
    private val rect = RectF()
    override fun getIntrinsicWidth() = (72 * d).toInt()
    override fun getIntrinsicHeight() = (72 * d).toInt()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val k = b.width() / (72 * d)
        rect.set(b)
        canvas.drawRoundRect(rect, 16 * d * k, 16 * d * k, bg)
        val o = 16 * d * k
        for (i in 0..1) for (j in 0..1) {
            val x = b.left + o + (4 + 18 * i) * d * k
            val y = b.top + o + (4 + 18 * j) * d * k
            rect.set(x, y, x + 14 * d * k, y + 14 * d * k)
            canvas.drawRoundRect(rect, 3 * d * k, 3 * d * k, fg)
        }
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
