package com.ztdrop.android

import android.content.Context
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

internal class ZoomImageView(context: Context) : ImageView(context) {
    private val transform=Matrix()
    private var zoom=1f
    private val scale=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector:ScaleGestureDetector):Boolean {
            val target=(zoom*detector.scaleFactor).coerceIn(1f,5f)
            transform.postScale(target/zoom,target/zoom,detector.focusX,detector.focusY)
            zoom=target; constrain(); imageMatrix=transform; return true
        }
    })
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onDoubleTap(e:MotionEvent):Boolean {
            if(zoom>1f) resetZoom() else { transform.postScale(2f,2f,e.x,e.y); zoom=2f; constrain(); imageMatrix=transform }
            return true
        }
        override fun onScroll(a:MotionEvent?,b:MotionEvent,dx:Float,dy:Float):Boolean {
            if(zoom>1f) { transform.postTranslate(-dx,-dy); constrain(); imageMatrix=transform }; return true
        }
    })
    init { scaleType=ScaleType.MATRIX; contentDescription="图片预览，可双击或捏合缩放" }
    fun zoomBy(factor:Float) {
        val target=(zoom*factor).coerceIn(1f,5f)
        transform.postScale(target/zoom,target/zoom,width/2f,height/2f)
        zoom=target; constrain(); imageMatrix=transform
    }
    fun resetZoom() {
        val d=drawable ?: return
        if(width==0 || height==0 || d.intrinsicWidth<=0 || d.intrinsicHeight<=0) return
        val ratio=minOf(width/d.intrinsicWidth.toFloat(),height/d.intrinsicHeight.toFloat())
        transform.setScale(ratio,ratio)
        transform.postTranslate((width-d.intrinsicWidth*ratio)/2,(height-d.intrinsicHeight*ratio)/2)
        zoom=1f; imageMatrix=transform
    }
    private fun constrain() {
        val d=drawable ?: return
        val bounds=android.graphics.RectF(0f,0f,d.intrinsicWidth.toFloat(),d.intrinsicHeight.toFloat())
        transform.mapRect(bounds)
        val dx=if(bounds.width()<width) (width-bounds.width())/2-bounds.left else if(bounds.left>0) -bounds.left else if(bounds.right<width) width-bounds.right else 0f
        val dy=if(bounds.height()<height) (height-bounds.height())/2-bounds.top else if(bounds.top>0) -bounds.top else if(bounds.bottom<height) height-bounds.bottom else 0f
        transform.postTranslate(dx,dy)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { super.onSizeChanged(w,h,oldw,oldh); resetZoom() }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scale.onTouchEvent(event); gestures.onTouchEvent(event)
        return true
    }
}
