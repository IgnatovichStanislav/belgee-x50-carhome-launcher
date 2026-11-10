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

/** Largest size ≤ [basePx] at which every text fits [maxW]; one size for all keeps a row even. */
private fun Paint.commonSize(texts: List<String>, basePx: Float, maxW: Float): Float {
    textSize = basePx
    textScaleX = 1f
    val widest = texts.maxOf { measureText(it) }
    return if (widest > maxW) basePx * maxW / widest else basePx
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

/**
 * «Состояние авто», right column (402x471), no frame of its own. Rows line up with the left column
 * (0..210 / 224..355 / 369..471): trip / odometer / to service + the four systems; battery + seat belts; AutoHold.
 * A problem paints its own cell red; AutoHold holding is not a problem.
 */
internal class CarStateBlockView(ctx: Context) : View(ctx) {
    private val d = ctx.dpf(1f)
    private val titleText = ctx.getString(R.string.state_title)
    private val infoLabels = arrayOf(R.string.trip_km, R.string.odometer_km, R.string.service_km).map { ctx.getString(it) }
    private val names = arrayOf(R.string.state_coolant, R.string.state_oil, R.string.state_gearbox, R.string.state_tires)
        .map { ctx.getString(it) }
    private val problems = arrayOf(R.string.state_overheat, R.string.state_low_pressure, R.string.state_overheat, R.string.state_fault)
        .map { ctx.getString(it) }
    private val okText = ctx.getString(R.string.state_ok)
    private val noDataText = ctx.getString(R.string.state_no_data)
    private val batteryText = ctx.getString(R.string.battery)
    private val beltsText = ctx.getString(R.string.belts)
    private val unitV = ctx.getString(R.string.unit_v)
    private val armedLabel = ctx.getString(R.string.autohold_armed)
    private val holdingText = ctx.getString(R.string.autohold_holding)
    private val offText = ctx.getString(R.string.autohold_off)

    private val infoKey = ctx.textPaint(16f, Fonts.regular, R.color.text_dim)
    private val infoVal = ctx.textPaint(28f, Fonts.regular, R.color.text)
    private val nameP = ctx.textPaint(22f, Fonts.regular, R.color.text)
    private val statusP = ctx.textPaint(16f, Fonts.regular, R.color.text_dim)
    private val cellKey = ctx.textPaint(18f, Fonts.regular, R.color.text_dim)
    private val battVal = ctx.textPaint(48f, Fonts.medium, R.color.ok)
    private val battUnit = ctx.textPaint(22f, Fonts.regular, R.color.text_dim)
    private val ahTitle = ctx.textPaint(24f, Fonts.regular, R.color.text)
    private val ahState = ctx.textPaint(22f, Fonts.regular, R.color.text)
    private val ahLetter = ctx.textPaint(20f, Fonts.regular, R.color.accent)
    private val glyph = ctx.textPaint(22f, Fonts.bold, R.color.on_accent)
    private val badgeGlyph = ctx.textPaint(17f, Fonts.bold, R.color.on_accent)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val linePaint = Paint().apply { color = ctx.getColor(R.color.line) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ctx.getColor(R.color.on_ok)
    }
    private val seats = arrayOf<Drawable>(
        ctx.getDrawable(R.drawable.ic_seat)!!.mutate(), ctx.getDrawable(R.drawable.ic_seat)!!.mutate(),
    )
    private val path = Path()
    private val rect = RectF()

    private val okC = ctx.getColor(R.color.ok)
    private val crit = ctx.getColor(R.color.crit)
    private val critBg = ctx.getColor(R.color.crit_bg)
    private val tileC = ctx.getColor(R.color.tile)
    private val lineC = ctx.getColor(R.color.line)
    private val dim = ctx.getColor(R.color.text_dim)
    private val txt = ctx.getColor(R.color.text)
    private val inactive = ctx.getColor(R.color.inactive)
    private val neutral = ctx.getColor(R.color.neutral)
    private val accent = ctx.getColor(R.color.accent)
    private val connStart = ctx.getColor(R.color.conn_start)
    private val armedBorder = ctx.getColor(R.color.armed_border)
    private val holdBorder = ctx.getColor(R.color.hold_border)
    private val onOk = ctx.getColor(R.color.hold_ring_text)

    private var info = arrayOf("—", "—", "—")
    private val flags = arrayOfNulls<Boolean>(4)
    private var battery = "—"
    private var batteryLow = false
    private var driver: Boolean? = null
    private var passenger: Boolean? = null
    private var autoHold = 0
    init {
        contentDescription = titleText
    }

    fun set(
        tripKm: Float?, odometerKm: Int?, serviceKm: Int?,
        coolant: Boolean?, oil: Boolean?, gearbox: Boolean?, tires: Boolean?,
        batteryV: Float?, batteryLow: Boolean,
        driver: Boolean?, passenger: Boolean?,
        autoHold: Int?,
    ) {
        info = arrayOf(oneDecimal(tripKm), odometerKm?.let { groupThousands(it) } ?: "—", serviceKm?.let { groupThousands(it) } ?: "—")
        flags[0] = coolant; flags[1] = oil; flags[2] = gearbox; flags[3] = tires
        battery = oneDecimal(batteryV)
        this.batteryLow = batteryLow
        this.driver = driver
        this.passenger = passenger
        this.autoHold = if (autoHold == 1 || autoHold == 2) autoHold else 0
        invalidate()
    }

    /** Cell styled like the other tiles; a problem turns it red. */
    private fun cell(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, bad: Boolean) {
        rect.set(x * d, y * d, (x + w) * d, (y + h) * d)
        fill.color = if (bad) critBg else tileC
        canvas.drawRoundRect(rect, 22 * d, 22 * d, fill)
        stroke.color = if (bad) crit else lineC
        stroke.strokeWidth = (if (bad) 2 else 1) * d
        rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        canvas.drawRoundRect(rect, 22 * d, 22 * d, stroke)
    }

    override fun onDraw(canvas: Canvas) {
        // Trip / odometer / to service: three equal columns; labels and values each share one size,
        // shrunk together when the widest one does not fit, so nothing overlaps.
        val colW = 402f / 3
        val maxW = (colW - 12) * d
        // One frame 402x66 around the three, like the cells below.
        cell(canvas, 0f, 0f, 402f, 66f, false)
        infoKey.textSize = infoKey.commonSize(infoLabels, 16 * d, maxW)
        infoVal.textSize = infoVal.commonSize(info.toList(), 28 * d, maxW)
        for (i in 0..2) {
            val cx = (colW * i + colW / 2) * d
            if (i > 0) canvas.drawRect(colW * i * d, 12 * d, colW * i * d + d, 54 * d, linePaint)
            canvas.drawText(infoLabels[i], cx - infoKey.measureText(infoLabels[i]) / 2f, 25 * d, infoKey)
            infoVal.color = if (info[i] == "—") inactive else txt
            canvas.drawText(info[i], cx - infoVal.measureText(info[i]) / 2f, 55 * d, infoVal)
        }

        // Systems 2x2: cells 194x61, the second row ends level with «Скорость» (210).
        for (i in 0 until 4) {
            val x = if (i % 2 == 0) 0f else 208f
            val y = if (i < 2) 80f else 149f
            val f = flags[i]
            cell(canvas, x, y, 194f, 61f, f == true)
            val cy = (y + 30.5f) * d
            val ccx = (x + 16 + 17) * d
            circle.color = when (f) { true -> crit; false -> okC; null -> inactive }
            canvas.drawCircle(ccx, cy, 17 * d, circle)
            when (f) {
                false -> {
                    path.reset()
                    path.moveTo(ccx - 6.5f * d, cy + 0.5f * d)
                    path.lineTo(ccx - 1.8f * d, cy + 5.2f * d)
                    path.lineTo(ccx + 6.8f * d, cy - 5f * d)
                    canvas.drawPath(path, tick)
                }
                true -> canvas.drawText("!", ccx - glyph.measureText("!") / 2f, glyph.baselineForCenter(cy), glyph)
                null -> canvas.drawText("—", ccx - glyph.measureText("—") / 2f, glyph.baselineForCenter(cy), glyph)
            }
            val status = when (f) { true -> problems[i]; false -> okText; null -> noDataText }
            nameP.color = if (f == true) crit else txt
            statusP.color = if (f == true) crit else dim
            nameP.textSize = nameP.commonSize(listOf(names[i]), 22 * d, 120 * d)
            statusP.textSize = statusP.commonSize(listOf(status), 16 * d, 120 * d)
            canvas.drawText(names[i], (x + 64) * d, cy - d, nameP)
            canvas.drawText(status, (x + 64) * d, cy + 18 * d, statusP)
        }

        // Battery 194x131, level with «Запас хода».
        cell(canvas, 0f, 224f, 194f, 131f, batteryLow)
        cellKey.color = if (batteryLow) crit else dim
        cellKey.textSize = cellKey.commonSize(listOf(batteryText), 18 * d, (if (batteryLow) 128 else 162) * d)
        canvas.drawText(batteryText, 18 * d, 258 * d, cellKey)
        if (batteryLow) canvas.drawBadge(170 * d, 252 * d, 12 * d, circle.apply { color = crit }, badgeGlyph)
        battVal.color = if (batteryLow) crit else okC
        canvas.drawText(battery, 18 * d, 326 * d, battVal)
        canvas.drawText(unitV, 18 * d + battVal.measureText(battery) + 6 * d, 326 * d, battUnit)

        // Seat belts 194x131: driver green / red with "!" / grey without data; passenger green or grey, never red.
        cell(canvas, 208f, 224f, 194f, 131f, driver == false)
        cellKey.color = if (driver == false) crit else dim
        cellKey.textSize = cellKey.commonSize(listOf(beltsText), 18 * d, 162 * d)
        canvas.drawText(beltsText, 226 * d, 258 * d, cellKey)
        for (i in 0..1) {
            val x = (222 + 64 * i) * d
            val y = 276 * d
            seats[i].setTint(
                if (i == 0) when (driver) { true -> okC; false -> crit; null -> inactive }
                else if (passenger == true) okC else neutral,
            )
            seats[i].setBounds(x.toInt(), y.toInt(), (x + 56 * d).toInt(), (y + 56 * d).toInt())
            seats[i].draw(canvas)
        }
        if (driver == false) canvas.drawBadge(270 * d, 286 * d, 12 * d, circle.apply { color = crit }, badgeGlyph)

        // AutoHold 402x102, level with the heaters: off plain, ready blue border, holding green.
        rect.set(0f, 369 * d, 402 * d, 471 * d)
        fill.color = if (autoHold == 2) connStart else tileC
        canvas.drawRoundRect(rect, 22 * d, 22 * d, fill)
        stroke.color = when (autoHold) { 1 -> armedBorder; 2 -> holdBorder; else -> lineC }
        stroke.strokeWidth = (if (autoHold == 0) 1 else 2) * d
        rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        canvas.drawRoundRect(rect, 22 * d, 22 * d, stroke)
        val rcx = 47 * d
        val rcy = 420 * d
        ring.color = when (autoHold) { 1 -> accent; 2 -> okC; else -> inactive }
        if (autoHold == 2) canvas.drawCircle(rcx, rcy, 22 * d, circle.apply { color = okC })
        canvas.drawCircle(rcx, rcy, 20.5f * d, ring)
        ahLetter.color = when (autoHold) { 1 -> accent; 2 -> onOk; else -> inactive }
        canvas.drawText("A", rcx - ahLetter.measureText("A") / 2f, ahLetter.baselineForCenter(rcy), ahLetter)
        ahTitle.color = if (autoHold == 0) dim else txt
        canvas.drawText("AutoHold", 85 * d, ahTitle.baselineForCenter(rcy), ahTitle)
        val st = when (autoHold) { 1 -> armedLabel; 2 -> holdingText; else -> offText }
        ahState.color = when (autoHold) { 1 -> accent; 2 -> okC; else -> inactive }
        canvas.drawText(st, 380 * d - ahState.measureText(st), ahState.baselineForCenter(rcy), ahState)
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
