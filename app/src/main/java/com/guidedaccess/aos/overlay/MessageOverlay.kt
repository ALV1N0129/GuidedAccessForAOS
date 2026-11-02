package com.guidedaccess.aos.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** '사용법 유도 사용 중', '시간 종료' 처럼 화면 전체를 막고 안내하는 창. */
object MessageOverlay {
    fun create(
        ctx: Context,
        title: CharSequence,
        message: CharSequence,
        footer: CharSequence? = null,
        button: CharSequence? = null,
        onButton: (() -> Unit)? = null,
    ): View {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF2101214.toInt())
            isClickable = true
            setPadding(px(32), px(32), px(32), px(32))

            addView(TextView(ctx).apply {
                text = "🔒"
                textSize = 48f
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = title
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, px(16), 0, px(8))
            })
            addView(TextView(ctx).apply {
                text = message
                textSize = 15f
                setTextColor(0xCCFFFFFF.toInt())
                gravity = Gravity.CENTER
            })
            if (button != null) {
                addView(TextView(ctx).apply {
                    text = button
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(px(32), px(14), px(32), px(14))
                    background = GradientDrawable().apply {
                        cornerRadius = px(24).toFloat()
                        setColor(0xFF3D5AFE.toInt())
                    }
                    setOnClickListener { onButton?.invoke() }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(28) })
            }
            if (footer != null) {
                addView(TextView(ctx).apply {
                    text = footer
                    textSize = 13f
                    setTextColor(0x80FFFFFF.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, px(28), 0, 0)
                })
            }
        }
    }
}
