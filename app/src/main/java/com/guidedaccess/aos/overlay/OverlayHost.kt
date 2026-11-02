package com.guidedaccess.aos.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 접근성 오버레이(TYPE_ACCESSIBILITY_OVERLAY) 창을 추가/제거한다.
 * 접근성 오버레이는 다른 모든 앱 위에 그려지며, 별도의 '다른 앱 위에 표시' 권한이 필요 없다.
 * 반드시 AccessibilityService 컨텍스트의 WindowManager 를 사용해야 한다.
 */
class OverlayHost(private val service: Context) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val fullScreen = LinkedHashSet<View>()

    fun screenSize(): Point {
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(p)
        return p
    }

    /** 화면 전체를 덮는 창. 화면 회전 시 크기를 자동으로 맞춘다. */
    fun addFullScreen(view: View) {
        if (view.parent != null) return
        val s = screenSize()
        if (safeAdd(view, params(s.x, s.y, 0, 0))) fullScreen += view
    }

    /** 지정한 화면 좌표(px)에 놓이는 창. */
    fun addAt(view: View, x: Int, y: Int, width: Int, height: Int) {
        if (view.parent != null) return
        safeAdd(view, params(width, height, x, y))
    }

    fun remove(view: View?) {
        if (view == null) return
        fullScreen -= view
        if (view.parent != null) {
            try {
                wm.removeViewImmediate(view)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed", e)
            }
        }
    }

    fun onScreenChanged() {
        val s = screenSize()
        fullScreen.forEach { v ->
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            lp.width = s.x
            lp.height = s.y
            try {
                wm.updateViewLayout(v, lp)
            } catch (e: Exception) {
                Log.w(TAG, "updateViewLayout failed", e)
            }
        }
    }

    private fun safeAdd(view: View, lp: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, lp)
        true
    } catch (e: Exception) {
        Log.e(TAG, "addView failed", e)
        false
    }

    private fun params(width: Int, height: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            fitInsetsSides = 0
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    companion object {
        private const val TAG = "OverlayHost"
    }
}
