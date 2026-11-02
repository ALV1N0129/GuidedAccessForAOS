package com.guidedaccess.aos.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.guidedaccess.aos.Prefs
import com.guidedaccess.aos.R

/**
 * 화면 전체를 덮는 숫자 키패드. 키보드(IME) 없이 동작하므로 오버레이에서도 안정적이다.
 * - VERIFY: 저장된 암호 확인 (자릿수가 차면 자동 확인, 5회 실패 시 잠시 잠금)
 * - CREATE: 새 암호를 두 번 입력해서 설정
 */
class PinOverlay(
    private val ctx: Context,
    private val prefs: Prefs,
    private val mode: Mode,
    private val onSuccess: () -> Unit,
    private val onCancel: () -> Unit,
) {
    enum class Mode { VERIFY, CREATE }

    private val d = ctx.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val entered = StringBuilder()
    private var firstEntry: String? = null

    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var dots: TextView
    private lateinit var okKey: TextView
    val view: View = build()

    private val lockTicker = object : Runnable {
        override fun run() {
            val left = lockedUntil - SystemClock.elapsedRealtime()
            if (left <= 0) {
                message.setText(R.string.pin_enter_message)
                return
            }
            message.text = ctx.getString(R.string.pin_locked, ((left + 999) / 1000).toInt())
            handler.postDelayed(this, 500)
        }
    }

    init {
        if (mode == Mode.VERIFY && isLocked()) lockTicker.run()
    }

    fun release() = handler.removeCallbacksAndMessages(null)

    private fun build(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF2101214.toInt())
            isClickable = true // 뒤의 앱으로 터치가 새지 않게
            setPadding(px(24), px(24), px(24), px(24))
        }
        title = text(22f, Color.WHITE, bold = true).apply {
            setText(if (mode == Mode.VERIFY) R.string.pin_enter else R.string.pin_new)
        }
        message = text(14f, 0xB3FFFFFF.toInt()).apply {
            setText(if (mode == Mode.VERIFY) R.string.pin_enter_message else R.string.pin_new_message)
            setPadding(0, px(8), 0, px(16))
        }
        dots = text(26f, Color.WHITE).apply { letterSpacing = 0.35f; minHeight = px(44) }
        root.addView(title)
        root.addView(message)
        root.addView(dots)

        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(CANCEL, "0", BACK))
        rows.forEach { row ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { k -> line.addView(key(k)) }
            root.addView(line)
        }
        okKey = text(17f, Color.WHITE, bold = true).apply {
            setText(R.string.ok)
            setPadding(px(32), px(14), px(32), px(14))
            background = GradientDrawable().apply { cornerRadius = px(24).toFloat(); setColor(0xFF3D5AFE.toInt()) }
            visibility = if (mode == Mode.CREATE) View.VISIBLE else View.GONE
            setOnClickListener { confirmCreate() }
        }
        root.addView(okKey, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(16) })
        updateDots()
        return root
    }

    private fun key(k: String): View {
        val size = px(76)
        val isDigit = k.length == 1 && k[0].isDigit()
        return text(if (isDigit) 28f else 16f, Color.WHITE).apply {
            text = when (k) {
                CANCEL -> ctx.getString(R.string.cancel)
                BACK -> "⌫"
                else -> k
            }
            gravity = Gravity.CENTER
            if (isDigit) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x26FFFFFF)
                }
            }
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                when (k) {
                    CANCEL -> onCancel()
                    BACK -> if (entered.isNotEmpty()) { entered.deleteCharAt(entered.lastIndex); updateDots() }
                    else -> press(k)
                }
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(px(10), px(8), px(10), px(8)) }
        }
    }

    private fun press(digit: String) {
        if (mode == Mode.VERIFY && isLocked()) return
        if (entered.length >= MAX_LEN) return
        entered.append(digit)
        updateDots()
        if (mode == Mode.VERIFY && entered.length == prefs.pinLength) {
            handler.postDelayed({ verify() }, 120)
        }
    }

    private fun verify() {
        if (prefs.checkPin(entered.toString())) {
            failures = 0
            onSuccess()
            return
        }
        failures++
        entered.clear()
        updateDots()
        shake()
        if (failures >= MAX_FAILURES) {
            failures = 0
            lockedUntil = SystemClock.elapsedRealtime() + LOCK_MS
            lockTicker.run()
        } else {
            message.text = ctx.getString(R.string.pin_wrong, failures, MAX_FAILURES)
        }
    }

    private fun confirmCreate() {
        val pin = entered.toString()
        if (pin.length < MIN_LEN) {
            message.setText(R.string.pin_too_short)
            shake()
            return
        }
        val first = firstEntry
        entered.clear()
        when {
            first == null -> {
                firstEntry = pin
                title.setText(R.string.pin_confirm)
                message.setText(R.string.pin_confirm_message)
            }
            first == pin -> {
                prefs.setPin(pin)
                onSuccess()
                return
            }
            else -> {
                firstEntry = null
                title.setText(R.string.pin_new)
                message.setText(R.string.pin_mismatch)
                shake()
            }
        }
        updateDots()
    }

    private fun updateDots() {
        val n = entered.length
        dots.text = if (mode == Mode.VERIFY) {
            "●".repeat(n) + "○".repeat((prefs.pinLength - n).coerceAtLeast(0))
        } else {
            if (n == 0) "○○○○" else "●".repeat(n)
        }
    }

    private fun shake() {
        ObjectAnimator.ofFloat(dots, View.TRANSLATION_X, 0f, 24f, -24f, 16f, -16f, 8f, 0f)
            .setDuration(360).start()
        dots.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun px(dp: Int) = (dp * d).toInt()

    companion object {
        private const val CANCEL = "cancel"
        private const val BACK = "back"
        private const val MIN_LEN = 4
        private const val MAX_LEN = 8
        private const val MAX_FAILURES = 5
        private const val LOCK_MS = 30_000L

        // 오버레이를 닫았다 다시 열어도 실패 횟수/잠금이 유지되도록 정적으로 둔다.
        private var failures = 0
        private var lockedUntil = 0L
        private fun isLocked() = SystemClock.elapsedRealtime() < lockedUntil
    }
}
