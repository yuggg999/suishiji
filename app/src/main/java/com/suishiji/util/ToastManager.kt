package com.suishiji.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.suishiji.R

object ToastManager {

    private const val MAX_VISIBLE = 3
    private const val DURATION_SHORT = 2000L
    private const val DURATION_LONG = 3500L

    private val activeToasts = mutableListOf<ToastEntry>()
    private val handler = Handler(Looper.getMainLooper())

    fun show(context: Context, message: String, duration: Int = android.widget.Toast.LENGTH_SHORT) {
        val activity = getActivity(context) ?: return
        val delay = if (duration == android.widget.Toast.LENGTH_LONG) DURATION_LONG else DURATION_SHORT
        showInternal(activity, message, delay)
    }

    private fun showInternal(activity: Activity, message: String, delay: Long) {
        val toastView = createToastView(activity, message)

        if (canDrawOverlays(activity)) {
            showOverlay(activity, toastView, delay)
        } else {
            showLegacy(activity, toastView, delay)
        }
    }

    private fun createToastView(activity: Activity, message: String): LinearLayout {
        val icon = ImageView(activity).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(10)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val textView = TextView(activity).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC333333"))
                cornerRadius = dp(24).toFloat()
            }
            addView(icon)
            addView(textView)
            alpha = 0f
        }
    }

    private fun showOverlay(activity: Activity, toastView: View, delay: Long) {
        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bottomOffset = getBottomOffset(activity)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = bottomOffset + (activeToasts.size * (dp(52) + 8))
        }

        wm.addView(toastView, params)
        val entry = ToastEntry(toastView, { wm.removeView(toastView) }, { idx ->
            params.y = bottomOffset + (idx * (dp(52) + 8))
            wm.updateViewLayout(toastView, params)
        })
        activeToasts.add(entry)

        animateIn(toastView)

        while (activeToasts.size > MAX_VISIBLE) {
            removeToast(activeToasts.first())
        }

        entry.runnable = Runnable { removeToast(entry) }
        handler.postDelayed(entry.runnable!!, delay)
    }

    private fun showLegacy(activity: Activity, toastView: View, delay: Long) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val bottomY = getBottomOffset(activity)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = bottomY + (activeToasts.size * (dp(52) + 8))
        }

        rootView.addView(toastView, params)
        val entry = ToastEntry(toastView, { rootView.removeView(toastView) }, { idx ->
            params.bottomMargin = bottomY + (idx * (dp(52) + 8))
            toastView.layoutParams = params
        })
        activeToasts.add(entry)

        animateIn(toastView)

        while (activeToasts.size > MAX_VISIBLE) {
            removeToast(activeToasts.first())
        }

        entry.runnable = Runnable { removeToast(entry) }
        handler.postDelayed(entry.runnable!!, delay)
    }

    private fun animateIn(view: View) {
        view.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    private fun removeToast(entry: ToastEntry) {
        if (!activeToasts.remove(entry)) return
        entry.runnable?.let { handler.removeCallbacks(it) }
        entry.view.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                entry.view.alpha = 0f
                entry.view.visibility = View.INVISIBLE
                entry.removeView.run()
                repositionToasts()
            }
            .start()
    }

    private fun repositionToasts() {
        activeToasts.forEachIndexed { index, entry ->
            entry.reposition(index)
        }
    }

    private fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    private fun getBottomOffset(activity: Activity): Int {
        val navBar = getNavBarHeight(activity)
        val bottomNav = activity.findViewById<View>(R.id.bottom_nav)
        val bottomNavHeight = bottomNav?.height ?: 0
        return navBar + bottomNavHeight
    }

    private fun getNavBarHeight(activity: Activity): Int {
        val id = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
    }

    private fun getActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            android.content.res.Resources.getSystem().displayMetrics).toInt()

    private class ToastEntry(
        val view: View,
        val removeView: Runnable,
        val reposition: (Int) -> Unit,
        var runnable: Runnable? = null
    )
}
