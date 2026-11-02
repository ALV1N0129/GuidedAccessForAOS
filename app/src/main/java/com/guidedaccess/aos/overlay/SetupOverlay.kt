package com.guidedaccess.aos.overlay

import android.content.Context
import android.graphics.Point
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.materialswitch.MaterialSwitch
import com.guidedaccess.aos.AppConfig
import com.guidedaccess.aos.R
import kotlin.math.max

/**
 * iOS 사용법 유도의 '설정 화면'. 실제 앱 화면 위에 반투명하게 떠서
 * 차단할 영역을 그리고, 옵션을 고르고, 시작/재개/종료한다.
 */
class SetupOverlay(
    private val ctx: Context,
    private val host: OverlayHost,
    appLabel: CharSequence,
    private val config: AppConfig,
    private val mode: Mode,
    private val listener: Listener,
) {
    enum class Mode { NEW, ACTIVE }

    interface Listener {
        fun onCancel()
        fun onStart(cfg: AppConfig)
        fun onEnd(cfg: AppConfig)
        fun onResume(cfg: AppConfig)
    }

    private val root: View = LayoutInflater.from(ctx).inflate(R.layout.overlay_setup, null)
    private val editor: AreaEditorView = root.findViewById(R.id.editor)
    private val hint: TextView = root.findViewById(R.id.hint)
    private val options: View = root.findViewById(R.id.options)
    private val btnOptions: Button = root.findViewById(R.id.btn_options)
    private val tvTime: TextView = root.findViewById(R.id.tv_time)
    private var timeIndex = max(0, AppConfig.TIME_LIMIT_OPTIONS.indexOf(config.timeLimitMinutes))
    private var lastSize = Point()

    init {
        root.findViewById<TextView>(R.id.title).text = appLabel
        val left: Button = root.findViewById(R.id.btn_left)
        val right: Button = root.findViewById(R.id.btn_right)
        left.setText(if (mode == Mode.NEW) R.string.cancel else R.string.end)
        right.setText(if (mode == Mode.NEW) R.string.start else R.string.resume)
        left.setOnClickListener { if (mode == Mode.NEW) listener.onCancel() else listener.onEnd(collect()) }
        right.setOnClickListener { if (mode == Mode.NEW) listener.onStart(collect()) else listener.onResume(collect()) }

        root.findViewById<MaterialSwitch>(R.id.sw_touch).apply {
            isChecked = config.touchEnabled
            setOnCheckedChangeListener { _, c -> config.touchEnabled = c; updateHint() }
        }
        root.findViewById<MaterialSwitch>(R.id.sw_volume).apply {
            isChecked = config.volumeEnabled
            setOnCheckedChangeListener { _, c -> config.volumeEnabled = c }
        }
        root.findViewById<Button>(R.id.btn_time_minus).setOnClickListener { changeTime(-1) }
        root.findViewById<Button>(R.id.btn_time_plus).setOnClickListener { changeTime(+1) }
        root.findViewById<Button>(R.id.btn_clear).setOnClickListener {
            editor.areas.clear()
            editor.invalidate()
        }
        btnOptions.setOnClickListener {
            val show = options.visibility != View.VISIBLE
            options.visibility = if (show) View.VISIBLE else View.GONE
            btnOptions.setText(if (show) R.string.options_close else R.string.options)
        }
        changeTime(0)
        updateHint()

        // 상태 표시줄/내비게이션 바와 겹치지 않도록 상단·하단 패널만 여백을 준다.
        // (편집 영역 자체는 화면 전체를 덮어서 어디든 차단할 수 있게 한다.)
        val d = ctx.resources.displayMetrics.density
        val topBar: View = root.findViewById(R.id.top_bar)
        val bottom: View = root.findViewById(R.id.bottom_panel)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.updatePadding(top = max(b.top, (28 * d).toInt()) + (8 * d).toInt())
            bottom.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = max(b.bottom, (16 * d).toInt()) + (12 * d).toInt()
            }
            insets
        }
    }

    fun show() {
        host.addFullScreen(root)
        root.post { loadAreas() }
    }

    fun dismiss() = host.remove(root)

    /** 화면이 회전되면 비율 좌표로 저장했다가 새 크기에 다시 배치한다. */
    fun onScreenChanged() {
        config.areas.clear()
        config.areas += normalized(lastSize)
        root.post { loadAreas() }
    }

    private fun changeTime(delta: Int) {
        val opts = AppConfig.TIME_LIMIT_OPTIONS
        timeIndex = (timeIndex + delta).coerceIn(0, opts.lastIndex)
        config.timeLimitMinutes = opts[timeIndex]
        tvTime.text = AppConfig.formatMinutes(ctx, config.timeLimitMinutes)
    }

    private fun updateHint() {
        hint.setText(if (config.touchEnabled) R.string.setup_hint else R.string.setup_hint_touch_off)
        editor.dimAll = !config.touchEnabled
    }

    private fun loadAreas() {
        val s = host.screenSize()
        lastSize = s
        val loc = IntArray(2).also { editor.getLocationOnScreen(it) }
        editor.areas.clear()
        config.areas.forEach { n ->
            editor.areas += RectF(
                n.left * s.x - loc[0], n.top * s.y - loc[1],
                n.right * s.x - loc[0], n.bottom * s.y - loc[1],
            )
        }
        editor.invalidate()
    }

    private fun normalized(s: Point): List<RectF> {
        if (s.x == 0 || s.y == 0) return emptyList()
        val loc = IntArray(2).also { editor.getLocationOnScreen(it) }
        return editor.areas.map { r ->
            RectF(
                ((r.left + loc[0]) / s.x).coerceIn(0f, 1f),
                ((r.top + loc[1]) / s.y).coerceIn(0f, 1f),
                ((r.right + loc[0]) / s.x).coerceIn(0f, 1f),
                ((r.bottom + loc[1]) / s.y).coerceIn(0f, 1f),
            )
        }
    }

    private fun collect(): AppConfig {
        config.areas.clear()
        config.areas += normalized(host.screenSize())
        return config
    }
}
