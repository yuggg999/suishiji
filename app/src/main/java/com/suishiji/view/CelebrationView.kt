package com.suishiji.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.random.Random
import kotlin.math.cos
import kotlin.math.sin

class CelebrationView(context: Context) : View(context) {
    private val particles = mutableListOf<Particle>()
    private val random = Random
    private var frameAnimator: ValueAnimator? = null
    private var burstAnimator: ValueAnimator? = null
    private var started = false
    private var elapsed = 0f

    data class Particle(
        var x: Float,
        var y: Float,
        var w: Float,
        var h: Float,
        var color: Int,
        var velocityX: Float,
        var velocityY: Float,
        var rotation: Float,
        var rotationSpeed: Float,
        var alpha: Float = 1f,
        var shape: Int,       // 0=ribbon, 1=circle, 2=star, 3=square
        var gravity: Float,
        var drag: Float,
        var wobble: Float,
        var wobbleSpeed: Float,
        var wobblePhase: Float
    )

    private val palette = intArrayOf(
        Color.parseColor("#4285F4"),  // Google Blue
        Color.parseColor("#EA4335"),  // Google Red
        Color.parseColor("#FBBC05"),  // Google Yellow
        Color.parseColor("#34A853"),  // Google Green
        Color.parseColor("#FF6D01"),  // Orange
        Color.parseColor("#46BDC6"),  // Teal
        Color.parseColor("#7B1FA2"),  // Purple
        Color.parseColor("#E91E63"),  // Pink
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun start() {
        if (started) return
        started = true
        elapsed = 0f
        particles.clear()

        // Frame-by-frame animation
        frameAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 33L  // ~30fps
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                elapsed += 0.033f
                updateParticles()
                invalidate()
            }
            start()
        }

        // Initial burst from center-top
        performBurst()
    }

    private fun performBurst() {
        val cx = width / 2f
        val cy = height * 0.15f
        val density = resources.displayMetrics.density

        for (i in 0 until 60) {
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = (random.nextFloat() * 14f + 6f) * density
            val size = (random.nextFloat() * 10f + 5f) * density

            particles.add(Particle(
                x = cx,
                y = cy,
                w = size * (random.nextFloat() * 0.6f + 0.7f),
                h = size * (random.nextFloat() * 0.4f + 0.3f),
                color = palette[random.nextInt(palette.size)],
                velocityX = cos(angle) * speed,
                velocityY = sin(angle) * speed - random.nextFloat() * 6f * density,
                rotation = random.nextFloat() * 360f,
                rotationSpeed = (random.nextFloat() - 0.5f) * 18f,
                shape = random.nextInt(4),
                gravity = (random.nextFloat() * 0.3f + 0.15f) * density,
                drag = random.nextFloat() * 0.02f + 0.01f,
                wobble = random.nextFloat() * 2f * density,
                wobbleSpeed = random.nextFloat() * 3f + 1f,
                wobblePhase = random.nextFloat() * 6.28f
            ))
        }

        // Delayed second wave
        postDelayed({
            for (i in 0 until 30) {
                val angle = random.nextFloat() * Math.PI.toFloat() * 2f
                val speed = (random.nextFloat() * 10f + 4f) * density
                val size = (random.nextFloat() * 8f + 4f) * density

                particles.add(Particle(
                    x = cx + (random.nextFloat() - 0.5f) * width * 0.5f,
                    y = cy,
                    w = size,
                    h = size * 0.5f,
                    color = palette[random.nextInt(palette.size)],
                    velocityX = cos(angle) * speed * 0.6f,
                    velocityY = -random.nextFloat() * 8f * density,
                    rotation = random.nextFloat() * 360f,
                    rotationSpeed = (random.nextFloat() - 0.5f) * 12f,
                    shape = 0,
                    gravity = (random.nextFloat() * 0.2f + 0.1f) * density,
                    drag = random.nextFloat() * 0.015f + 0.008f,
                    wobble = random.nextFloat() * 3f * density,
                    wobbleSpeed = random.nextFloat() * 4f + 2f,
                    wobblePhase = random.nextFloat() * 6.28f
                ))
            }
        }, 200)
    }

    private fun updateParticles() {
        val density = resources.displayMetrics.density
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            // Physics: gravity + drag + wobble
            p.velocityY += p.gravity
            p.velocityX *= (1f - p.drag)
            p.velocityY *= (1f - p.drag * 0.5f)

            val wobbleOffset = sin(elapsed * p.wobbleSpeed + p.wobblePhase).toFloat() * p.wobble
            p.x += p.velocityX + wobbleOffset * 0.1f
            p.y += p.velocityY
            p.rotation += p.rotationSpeed
            p.rotationSpeed *= 0.99f

            // Fade out when falling below 80% height
            if (p.y > height * 0.8f) {
                p.alpha -= 0.03f
            }
            if (p.alpha <= 0f || p.y > height + 50f) {
                iterator.remove()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in particles) {
            paint.color = p.color
            paint.alpha = (p.alpha * 255).toInt().coerceIn(0, 255)

            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rotation)

            when (p.shape) {
                0 -> drawRibbon(canvas, p)
                1 -> canvas.drawCircle(0f, 0f, p.w / 2f, paint)
                2 -> drawStar(canvas, p.w / 2f)
                3 -> {
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(-p.w / 2, -p.h / 2, p.w / 2, p.h / 2, paint)
                }
            }
            canvas.restore()
        }
    }

    private fun drawRibbon(canvas: Canvas, p: Particle) {
        paint.style = Paint.Style.FILL
        path.reset()
        val hw = p.w / 2
        val hh = p.h / 2
        path.moveTo(-hw, 0f)
        path.cubicTo(-hw, -hh, hw, -hh, hw, 0f)
        path.cubicTo(hw, hh, -hw, hh, -hw, 0f)
        canvas.drawPath(path, paint)
    }

    private fun drawStar(canvas: Canvas, radius: Float) {
        paint.style = Paint.Style.FILL
        path.reset()
        for (i in 0 until 5) {
            val outerAngle = Math.toRadians((i * 72 - 90).toDouble())
            val innerAngle = Math.toRadians(((i * 72 + 36) - 90).toDouble())
            val ox = (radius * cos(outerAngle)).toFloat()
            val oy = (radius * sin(outerAngle)).toFloat()
            val ix = (radius * 0.4f * cos(innerAngle)).toFloat()
            val iy = (radius * 0.4f * sin(innerAngle)).toFloat()
            if (i == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
            path.lineTo(ix, iy)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    fun stop() {
        started = false
        frameAnimator?.cancel()
        frameAnimator = null
        burstAnimator?.cancel()
        burstAnimator = null
    }
}
