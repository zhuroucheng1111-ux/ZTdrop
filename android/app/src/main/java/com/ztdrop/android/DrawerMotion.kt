package com.ztdrop.android

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView

internal object DrawerMotion {
    private var cleanup: (() -> Unit)? = null
    fun finish() { cleanup?.invoke(); cleanup=null }
    fun snapshot(page: android.view.View): Bitmap? {
        if (!ValueAnimator.areAnimatorsEnabled() || page.width == 0 || page.height == 0) return null
        return Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888).also { page.draw(Canvas(it)) }
    }
    fun transition(page: android.view.View, previous: Bitmap?, returning: Boolean) {
        previous ?: return
        val parent = page.parent as? ViewGroup ?: run { previous.recycle(); return }
        val image = ImageView(page.context).apply { setImageBitmap(previous); scaleType=ImageView.ScaleType.FIT_XY }
        cleanup = {
            page.animate().cancel(); image.animate().cancel()
            page.translationX=0f; page.alpha=1f
            parent.removeView(image); image.setImageDrawable(null)
            if(!previous.isRecycled) previous.recycle()
        }
        val width=page.width.toFloat()
        val curve=PathInterpolator(.22f,1f,.36f,1f)
        if (returning) {
            parent.addView(image,ViewGroup.LayoutParams(page.width,page.height))
            page.translationX=-width*.22f
            page.animate().translationX(0f).setDuration(380).setInterpolator(curve).start()
            image.animate().translationX(width).setDuration(380).setInterpolator(curve).withEndAction {
                finish()
            }.start()
        } else {
            parent.addView(image,parent.indexOfChild(page),ViewGroup.LayoutParams(page.width,page.height))
            page.translationX=width
            image.animate().translationX(-width*.22f).alpha(.72f).setDuration(380).setInterpolator(curve).start()
            page.animate().translationX(0f).setDuration(380).setInterpolator(curve).withEndAction {
                finish()
            }.start()
        }
    }
}
