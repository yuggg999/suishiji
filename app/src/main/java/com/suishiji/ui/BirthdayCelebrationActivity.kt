package com.suishiji.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.suishiji.R
import com.suishiji.view.CelebrationView

class BirthdayCelebrationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.parseColor("#FFF3E0")
        window.navigationBarColor = Color.parseColor("#FFF3E0")

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FFF3E0"))
        }

        // Confetti layer
        val celebrationView = CelebrationView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(celebrationView)

        // Content layer
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Big cake emoji
        val cake = TextView(this).apply {
            text = "🎂"
            textSize = 72f
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = -200f
        }
        content.addView(cake)

        // Main message
        val msg = TextView(this).apply {
            text = "生日快乐！"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF6F00"))
            alpha = 0f
            translationY = 80f
        }
        content.addView(msg)

        // Sub message
        val sub = TextView(this).apply {
            text = "愿你每天都开心！"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#A09890"))
            alpha = 0f
            translationY = 60f
        }
        content.addView(sub)

        // Emoji row
        val emojis = TextView(this).apply {
            text = "🎉 🎈 🎁 🎊 🎶"
            textSize = 24f
            gravity = Gravity.CENTER
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            setPadding(0, dp(24), 0, 0)
        }
        content.addView(emojis)

        container.addView(content)
        setContentView(container)

        celebrationView.post { celebrationView.start() }

        // Animate cake dropping in
        cake.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()

        // Scale pulse on cake
        cake.postDelayed({
            ObjectAnimator.ofFloat(cake, "scaleX", 1f, 1.3f, 1f).apply {
                duration = 400
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                start()
            }
            ObjectAnimator.ofFloat(cake, "scaleY", 1f, 1.3f, 1f).apply {
                duration = 400
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                start()
            }
        }, 600)

        // Animate message
        msg.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(300)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(2f))
            .start()

        // Animate sub
        sub.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(500)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(2f))
            .start()

        // Animate emojis
        emojis.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(700)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(2.5f))
            .start()

        // Auto finish after 5s with fade out
        Handler(Looper.getMainLooper()).postDelayed({
            container.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction { finish() }
                .start()
        }, 4500)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onBackPressed() {
        // Prevent manual dismiss, let it auto-finish
    }
}
