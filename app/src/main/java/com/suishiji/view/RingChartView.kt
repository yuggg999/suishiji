package com.suishiji.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class RingChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    data class Segment(val label: String, val value: Float, val color: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val segments = mutableListOf<Segment>()
    private val rect = RectF()

    private val legendColors = intArrayOf(
        Color.parseColor("#5B67CA"),
        Color.parseColor("#F0966B"),
        Color.parseColor("#E74C3C"),
        Color.parseColor("#27AE60"),
        Color.parseColor("#F1C40F"),
        Color.parseColor("#9B59B6"),
        Color.parseColor("#1ABC9C"),
        Color.parseColor("#E67E22"),
    )

    fun setData(data: List<Pair<String, Double>>) {
        segments.clear()
        data.forEachIndexed { i, (label, value) ->
            segments.add(Segment(label, value.toFloat(), legendColors[i % legendColors.size]))
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty()) return

        val total = segments.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) return

        val size = minOf(width, height).toFloat()
        val stroke = size * 0.22f
        val radius = size / 2f - stroke / 2f
        val cx = width / 2f
        val cy = height / 2f

        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        var startAngle = -90f
        for (seg in segments) {
            val sweep = seg.value / total * 360f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = seg.color
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(rect, startAngle, sweep, false, paint)
            startAngle += sweep
        }

        // White center
        paint.style = Paint.Style.FILL
        paint.color = if (isInEditMode) Color.WHITE else (parent as? android.view.View)?.let {
            try { it.background } catch (_: Exception) { null }
        }?.let { Color.WHITE } ?: Color.WHITE
        canvas.drawCircle(cx, cy, radius - stroke / 2f, paint)
    }

    fun getLegend(): List<Pair<Int, String>> {
        val total = segments.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) return emptyList()
        return segments.map { seg ->
            val pct = (seg.value / total * 100).toInt()
            seg.color to "${seg.label}  ¥%.0f  %d%%".format(seg.value, pct)
        }
    }
}
