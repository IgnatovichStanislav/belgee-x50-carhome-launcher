package by.carhome.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import by.carhome.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Light streaks sweeping left→right over the car scene while the car moves.
 * Animators run only while [setActive] is true and are cancelled by [pause].
 */
internal class StreakLayer(ctx: Context, private val ys: FloatArray, private val delaysMs: LongArray) : FrameLayout(ctx) {
    private val d = ctx.dpf(1f)
    private val streakW = (260 * d).toInt()
    private val streaks = ys.map { y ->
        View(ctx).apply {
            setBackgroundResource(R.drawable.streak)
            alpha = 0f
            this@StreakLayer.addView(this, LayoutParams(streakW, (3 * d).toInt()).apply { topMargin = (y * d).toInt() })
        }
    }
    private val anims = ArrayList<ObjectAnimator>()
    private var running = false

    init {
        clipChildren = true
        alpha = 0f
        visibility = GONE
    }

    private fun startAll() {
        if (anims.isEmpty()) {
            for ((i, v) in streaks.withIndex()) {
                val a = ObjectAnimator.ofFloat(v, TRANSLATION_X, -streakW.toFloat(), 880 * d)
                a.duration = 1600
                a.repeatCount = ObjectAnimator.INFINITE
                a.interpolator = LinearInterpolator()
                a.startDelay = delaysMs[i]
                a.addUpdateListener { v.alpha = sin(PI * it.animatedFraction).toFloat() }
                anims.add(a)
            }
        }
        for (a in anims) a.start()
    }

    private fun cancelAll() {
        for (a in anims) a.cancel()
        for (v in streaks) v.alpha = 0f
    }

    fun setActive(on: Boolean) {
        if (on == running) return
        running = on
        animate().cancel()
        if (on) {
            visibility = VISIBLE
            alpha = 1f
            startAll()
        } else {
            animate().alpha(0f).setDuration(300).withEndAction {
                if (!running) {
                    cancelAll()
                    visibility = GONE
                }
            }
        }
    }

    /** onPause/onStop: stop everything immediately. */
    fun pause() {
        animate().cancel()
        cancelAll()
        running = false
        alpha = 0f
        visibility = GONE
    }
}
