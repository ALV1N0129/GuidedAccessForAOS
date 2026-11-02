package com.guidedaccess.aos.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 빗금 무늬의 '차단된 영역'을 그리는 헬퍼. 편집기와 실제 차단 창이 같은 모양을 쓴다. */
internal class AreaPainter(context: Context) {
    private val d = context.resources.displayMetrics.density
    val corner = 10 * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC5F6368.toInt() }
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = 3 * d
        style = Paint.Style.STROKE
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        strokeWidth = 2 * d
        style = Paint.Style.STROKE
    }
    private val path = Path()

    fun draw(c: Canvas, r: RectF) {
        c.drawRoundRect(r, corner, corner, fill)
        c.save()
        path.reset()
        path.addRoundRect(r, corner, corner, Path.Direction.CW)
        c.clipPath(path)
        val step = 16 * d
        var x = r.left - r.height()
        while (x < r.right) {
            c.drawLine(x, r.bottom, x + r.height(), r.top, stripe)
            x += step
        }
        c.restore()
        c.drawRoundRect(r, corner, corner, border)
    }
}

/** 사용법 유도 중 터치를 막는 창의 내용물. 창 자체가 터치를 소비한다. */
class BlockedAreaView(context: Context) : View(context) {
    private val painter = AreaPainter(context)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val inset = 1f
        rect.set(inset, inset, width - inset, height - inset)
        painter.draw(canvas, rect)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}

/**
 * 차단할 영역을 손가락으로 그리는 편집기.
 * - 빈 곳 드래그: 새 영역
 * - 영역 드래그: 이동
 * - 오른쪽 아래 흰 점: 크기 조절
 * - 왼쪽 위 빨간 ×: 삭제
 */
class AreaEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 이 뷰 좌표계(px)의 영역들 */
    val areas = mutableListOf<RectF>()
    var onAreasChanged: (() -> Unit)? = null
    var dimAll = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val minSize = 40 * d
    private val handleR = 12 * d
    private val hitR = handleR * 1.8f
    private val painter = AreaPainter(context)

    private val scrim = Paint().apply { color = 0x59000000 }
    private val dimPaint = Paint().apply { color = 0xA6000000.toInt() }
    private val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private enum class Mode { NONE, CREATE, MOVE, RESIZE }

    private var mode = Mode.NONE
    private var active = -1
    private var startX = 0f
    private var startY = 0f
    private val orig = RectF()

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), if (dimAll) dimPaint else scrim)
        areas.forEach { r ->
            painter.draw(c, r)
            c.drawCircle(r.left, r.top, handleR, redFill)
            val k = handleR * 0.42f
            c.drawLine(r.left - k, r.top - k, r.left + k, r.top + k, xPaint)
            c.drawLine(r.left - k, r.top + k, r.left + k, r.top - k, xPaint)
            c.drawCircle(r.right, r.bottom, handleR, whiteFill)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        val w = width.toFloat()
        val h = height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.NONE
                active = -1
                for (i in areas.indices.reversed()) {
                    val r = areas[i]
                    if (hypot(x - r.left, y - r.top) < hitR) {
                        areas.removeAt(i)
                        invalidate()
                        onAreasChanged?.invoke()
                        return true
                    }
                    if (hypot(x - r.right, y - r.bottom) < hitR) {
                        mode = Mode.RESIZE; active = i; break
                    }
                    if (r.contains(x, y)) {
                        mode = Mode.MOVE; active = i; break
                    }
                }
                if (mode == Mode.NONE) {
                    areas += RectF(x, y, x, y)
                    active = areas.lastIndex
                    mode = Mode.CREATE
                } else {
                    // 선택한 영역을 맨 위로
                    areas += areas.removeAt(active)
                    active = areas.lastIndex
                }
                startX = x
                startY = y
                orig.set(areas[active])
            }
            MotionEvent.ACTION_MOVE -> {
                if (active < 0) return true
                val r = areas[active]
                val dx = x - startX
                val dy = y - startY
                when (mode) {
                    Mode.CREATE -> r.set(
                        max(0f, min(startX, x)), max(0f, min(startY, y)),
                        min(w, max(startX, x)), min(h, max(startY, y)),
                    )
                    Mode.MOVE -> {
                        val nx = min(max(0f, orig.left + dx), w - orig.width())
                        val ny = min(max(0f, orig.top + dy), h - orig.height())
                        r.offsetTo(nx, ny)
                    }
                    Mode.RESIZE -> {
                        r.right = min(w, max(orig.right + dx, r.left + minSize))
                        r.bottom = min(h, max(orig.bottom + dy, r.top + minSize))
                    }
                    Mode.NONE -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active >= 0 && mode == Mode.CREATE) {
                    val r = areas[active]
                    if (r.width() < minSize / 2 || r.height() < minSize / 2) {
                        areas.removeAt(active) // 그냥 탭한 경우
                    } else {
                        if (r.width() < minSize) r.right = min(w, r.left + minSize)
                        if (r.height() < minSize) r.bottom = min(h, r.top + minSize)
                    }
                }
                active = -1
                mode = Mode.NONE
                invalidate()
                onAreasChanged?.invoke()
            }
        }
        return true
    }
}
