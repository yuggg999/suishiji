package com.suishiji.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.abs

class SwipeableCalendarLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var startX = 0f
    private var startY = 0f
    private var isDragging = false
    private val swipeThreshold = 60f

    var onSwipeListener: ((direction: Int) -> Unit)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                if (abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.5f) {
                    isDragging = true
                    return true
                }
                if (abs(dy) > abs(dx)) {
                    isDragging = false
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val dx = ev.x - startX
                    if (abs(dx) > swipeThreshold) {
                        onSwipeListener?.invoke(if (dx > 0) -1 else 1)
                    }
                    isDragging = false
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}
