package com.jongin.callcalc

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.jongin.callcalc.core.EvaluatedRow
import com.jongin.callcalc.core.LiveRules
import com.jongin.callcalc.core.RegionColor
import com.jongin.callcalc.core.RowStatus
import kotlin.math.abs

/**
 * 콜 앱 위에 뜨는 표시 (설명서 4-5).
 * 접근성 서비스의 창(TYPE_ACCESSIBILITY_OVERLAY)이라 '다른 앱 위에 표시' 권한이 필요 없다.
 *
 * - 표시 창: 화면 전체 크기, 터치는 아래 콜 앱으로 그대로 통과 (FLAG_NOT_TOUCHABLE)
 * - 둥근 버튼: 누르면 표시 켜기/끄기, 끌어서 이동(위치 저장), 길게 누르면 기억 지우기
 */
class Overlay(
    private val svc: AccessibilityService,
    private val store: Store,
    private val onLongPress: () -> Unit,
    private val onToggle: () -> Unit,
) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val shownRects = mutableListOf<Rect>()

    private fun dp(v: Number) = svc.dp(v)

    // ───────── 표시 창 ─────────

    private fun ensureRoot(): FrameLayout {
        root?.let { return it }
        val r = FrameLayout(svc)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            alpha = windowAlpha()
        }
        wm.addView(r, lp)
        root = r
        return r
    }

    /** 터치 통과 방식은 안드로이드 12 규칙에 맞춰 80% 이하 */
    private fun windowAlpha(): Float {
        val a = store.overlayAlpha.coerceIn(20, 100) / 100f
        return if (store.overlaySolid) a else minOf(a, 0.8f)
    }

    /** 설정이 바뀌면 창 투명도 다시 적용 */
    fun applySettings() {
        val r = root ?: return
        val lp = r.layoutParams as WindowManager.LayoutParams
        lp.alpha = windowAlpha()
        runCatching { wm.updateViewLayout(r, lp) }
        updateBubbleLook()
    }

    /**
     * 콜 줄마다 표시를 붙인다. 좌표는 화면 기준 (CallRow.slot*).
     * banner 는 화면 위쪽 작은 요약 (계산식, 최고, 속도, 안내).
     */
    fun showLabels(rows: List<EvaluatedRow>, banner: String?) {
        val r = ensureRoot()
        r.removeAllViews()
        shownRects.clear()
        r.visibility = View.VISIBLE
        val loc = IntArray(2)
        r.getLocationOnScreen(loc)
        val textSp = store.overlayTextSp.toFloat()
        val gap = dp(6)

        for (e in rows) {
            val v = label(e, textSp)
            v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val w = v.measuredWidth
            val h = v.measuredHeight
            val row = e.row
            // 빈자리 오른쪽에 붙이되, 자리가 좁으면 왼쪽 끝에서 시작
            var x = row.slotRight - w - gap
            if (x < row.slotLeft + gap) x = row.slotLeft + gap
            val y = row.slotY - h / 2
            r.addView(v, FrameLayout.LayoutParams(w, h).apply {
                leftMargin = x - loc[0]
                topMargin = y - loc[1]
            })
            shownRects += Rect(x, y, x + w, y + h)
        }

        if (!banner.isNullOrBlank()) {
            val b = TextView(svc).apply {
                text = banner
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = svc.roundBg(Color.parseColor("#E6161A21"), Color.parseColor("#4C8FE0"), 1, 10)
                maxLines = 2
            }
            b.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val screenW = svc.resources.displayMetrics.widthPixels
            val w = minOf(b.measuredWidth, screenW - dp(24))
            val x = (screenW - w) / 2
            val y = statusBarHeight() + dp(2)
            r.addView(b, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = x - loc[0]
                topMargin = y - loc[1]
            })
            shownRects += Rect(x, y, x + w, y + b.measuredHeight + dp(20))
        }
    }

    private fun label(e: EvaluatedRow, textSp: Float): TextView {
        val fail = e.status == RowStatus.READ_FAIL || e.status == RowStatus.IMPOSSIBLE
        val num = e.value?.let { LiveRules.overlayNumber(it) }
        val region = e.regionColor
        val (txt, fg, bg, line) = when {
            fail -> Quad(if (e.status == RowStatus.READ_FAIL) "?" else "×", C.WARN, Color.parseColor("#1A1D22"), C.WARN)
            e.best -> Quad("★ $num", C.GOOD, C.regionBg(RegionColor.GREEN), C.regionLine(RegionColor.GREEN))
            region == RegionColor.RED -> Quad(if (e.valueHidden) "⛔" else "⛔ $num", Color.parseColor("#FFB3B3"), C.regionBg(RegionColor.RED), C.regionLine(RegionColor.RED))
            e.status == RowStatus.BELOW -> Quad(num ?: "", C.GRAY, Color.parseColor("#1A1D22"), Color.parseColor("#3A4048"))
            region == RegionColor.YELLOW -> Quad("⚠ $num", C.WARN, C.regionBg(RegionColor.YELLOW), C.regionLine(RegionColor.YELLOW))
            region == RegionColor.GREEN -> Quad("👍 $num", C.GOOD, C.regionBg(RegionColor.GREEN), C.regionBg(RegionColor.GREEN))
            else -> Quad(num ?: "", Color.WHITE, Color.parseColor("#161A21"), Color.parseColor("#4C8FE0"))
        }
        return TextView(svc).apply {
            text = txt
            textSize = textSp
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(fg)
            includeFontPadding = false
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = svc.roundBg(bg, line, if (e.best || region == RegionColor.RED) 2 else 1, 8)
        }
    }

    private data class Quad(val text: String, val fg: Int, val bg: Int, val line: Int)

    fun hideLabels() {
        root?.let { it.removeAllViews(); it.visibility = View.GONE }
        shownRects.clear()
    }

    /** 화면 캡처 OCR 이 자기 표시를 다시 읽지 않도록, 지금 떠 있는 표시들의 화면 위치 */
    fun ownRects(): List<Rect> {
        val list = shownRects.toMutableList()
        bubbleParams?.let { p -> bubble?.let { b -> list += Rect(p.x, p.y, p.x + b.width, p.y + b.height) } }
        return list
    }

    // ───────── 둥근 버튼 ─────────

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (bubble != null) return
        val size = dp(54)
        val b = TextView(svc).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val dm = svc.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (store.bubbleX >= 0) store.bubbleX else dm.widthPixels - size - dp(8)
            y = if (store.bubbleY >= 0) store.bubbleY else dm.heightPixels / 2
            alpha = 0.9f
        }

        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var downAt = 0L
        val longPress = Runnable {
            if (!moved) { moved = true; onLongPress() }
        }
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y
                    moved = false; downAt = SystemClock.uptimeMillis()
                    v.postDelayed(longPress, 700)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) { moved = true; v.removeCallbacks(longPress) }
                    if (moved && SystemClock.uptimeMillis() - downAt < 100000) {
                        lp.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - size)
                        lp.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - size)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                        store.overlayOn = !store.overlayOn
                        updateBubbleLook()
                        onToggle()
                    } else {
                        store.bubbleX = lp.x
                        store.bubbleY = lp.y
                    }
                }
            }
            true
        }
        wm.addView(b, lp)
        bubble = b
        bubbleParams = lp
        updateBubbleLook()
    }

    private fun updateBubbleLook() {
        val b = bubble ?: return
        val on = store.overlayOn
        b.text = if (on) "콜\nON" else "콜\nOFF"
        b.textSize = 13f
        b.setLineSpacing(0f, 0.9f)
        b.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (on) Color.parseColor("#4C8FE0") else Color.parseColor("#3A4048"))
            setStroke(dp(2), Color.WHITE)
        }
    }

    fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    fun hideAll() {
        hideLabels()
        hideBubble()
    }

    fun destroy() {
        hideBubble()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun statusBarHeight(): Int {
        val id = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) svc.resources.getDimensionPixelSize(id) else dp(24)
    }
}
