package com.ztdrop.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/** Four expanding rings from the reference HTML; animation stops when detached or hidden. */
internal class ShareCodeRippleView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    var active = false
        set(value) { field = value; updateAnimation(); invalidate() }

    private fun updateAnimation() {
        if (active && isAttachedToWindow && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
            if (!animator.isStarted) animator.start()
        } else animator.cancel()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return
        val density = resources.displayMetrics.density
        repeat(4) { index ->
            val progress = (phase + index * .25f) % 1f
            paint.color = Color.argb(((1f - progress) * 90).toInt(), 52, 199, 89)
            paint.strokeWidth = (2.5f - 1.5f * progress) * density
            canvas.drawCircle(width / 2f, height / 2f, 70f * density * (1f + progress * 1.4f), paint)
        }
    }
}
