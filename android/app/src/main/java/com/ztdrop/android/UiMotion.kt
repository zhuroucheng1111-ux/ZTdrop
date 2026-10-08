package com.ztdrop.android

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.EditText
import java.util.WeakHashMap

/** Native feedback belongs to actionable controls, never an entire message row. */
internal object UiMotion {
    private val installed = WeakHashMap<View, Boolean>()
    private fun mask(view: View): Drawable =
        (view.background?.constantState?.newDrawable()?.mutate() as? GradientDrawable)?.apply { setColor(Color.WHITE) }
            ?: GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16f * view.resources.displayMetrics.density }

    fun install(view: View, shape: Drawable? = null) {
        if (view is EditText || view is AbsListView || view.tag == "segmented-item") return
        if (shape != null || installed[view] != true) {
            val clip = (shape?.constantState?.newDrawable()?.mutate() as? GradientDrawable)?.apply { setColor(Color.WHITE) }
                ?: shape ?: mask(view)
            view.foreground = RippleDrawable(ColorStateList.valueOf(Color.argb(28, 0, 0, 0)), null, clip)
        }
        if (installed.put(view, true) == true) return
        view.isSoundEffectsEnabled = true
        view.isHapticFeedbackEnabled = true
        view.setOnTouchListener { control, event ->
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) control.isPressed = false
            false // Android still owns clicking, long-pressing and scroll cancellation.
        }
    }

    fun apply(view: View) {
        if (view.isClickable || view.isLongClickable) install(view)
        if (view is ViewGroup && view !is AbsListView) for (index in 0 until view.childCount) apply(view.getChildAt(index))
    }

    fun enter(view: View, direction: Int = 0) {
        view.animate().cancel()
        view.alpha = 1f; view.translationX = 0f; view.translationY = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.alpha = .65f
        if (direction == 0) view.translationY = 8f * view.resources.displayMetrics.density
        else view.translationX = direction * 24f * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator()).start()
    }
}
