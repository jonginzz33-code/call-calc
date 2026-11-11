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
    private val onToggle: () -> Unit,
    private val onScreenTouch: () -> Unit,
    private val onClearMemory: () -> Unit,
    private val onEditRegion: () -> Unit,
    private val onTrash: () -> Unit,
    private val onCheck: () -> Unit,
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
        watcher?.let { w -> watcherParams?.let { p -> p.flags = watcherFlags(); runCatching { wm.updateViewLayout(w, p) } } }
        // 버튼 띄우기 체크칸 반영 (지금 버튼이 떠 있는 화면일 때만)
        if (watcher != null) showBubble()
        val r = root ?: return
        val lp = r.layoutParams as WindowManager.LayoutParams
        lp.alpha = windowAlpha()
        runCatching { wm.updateViewLayout(r, lp) }
        updateBubbleLook()
        bubble?.let { b -> bubbleParams?.let { p -> p.flags = bubbleFlags(); runCatching { wm.updateViewLayout(b, p) } } }
    }

    /**
     * 콜 줄마다 표시를 붙인다. 좌표는 화면 기준 (CallRow.slot*).
     * banner 는 화면 위쪽 작은 요약 (계산식, 최고, 속도, 안내).
     */
    private val labelViews = mutableListOf<TextView>()
    private var bannerView: TextView? = null

    /**
     * 콜 줄마다 표시를 붙인다. 좌표는 화면 기준 (CallRow.slot*).
     * banner 는 화면 위쪽 작은 요약 (계산식, 최고, 속도, 안내).
     * 있던 표시는 지우지 않고 글자·색·위치만 바꿔서 깜빡이지 않게 한다.
     */
    fun showLabels(rows: List<EvaluatedRow>, banner: String?, bounds: Rect? = null) {
        val r = ensureRoot()
        shownRects.clear()
        r.visibility = View.VISIBLE
        val loc = IntArray(2)
        r.getLocationOnScreen(loc)
        val textSp = store.overlayTextSp.toFloat()
        val gap = dp(6)
        // 표시는 읽은 영역(네모) 안, 없으면 화면 안에서만 (화면 밖으로 나가지 않게)
        val screen = screenRect()
        val area = bounds?.let { Rect(it).apply { if (!intersect(screen)) set(screen) } } ?: screen

        while (labelViews.size < rows.size) {
            val v = TextView(svc)
            labelViews += v
            r.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        labelViews.forEachIndexed { i, v ->
            val e = rows.getOrNull(i)
            if (e == null) { v.visibility = View.GONE; return@forEachIndexed }
            val row = e.row
            style(v, e, textSp)
            v.visibility = View.VISIBLE
            v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            // 빈자리가 좁으면 글자를 줄여서 맞춤 (최소 70%) → 옆 글자를 덜 가림
            val room = row.slotRight - row.slotLeft - 2 * gap
            if (v.measuredWidth > room && room > 0) {
                val sp = maxOf(textSp * 0.7f, textSp * room / v.measuredWidth)
                v.textSize = sp
                v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            }
            val w = v.measuredWidth
            val h = v.measuredHeight
            // 빈자리 오른쪽에 붙이되, 자리가 좁으면 왼쪽 끝에서 시작
            var x = row.slotRight - w - gap
            if (x < row.slotLeft + gap) x = row.slotLeft + gap
            var y = row.slotY - h / 2
            x = x.coerceIn(area.left + gap, maxOf(area.left + gap, area.right - w - gap))
            y = y.coerceIn(area.top, maxOf(area.top, area.bottom - h))
            v.layoutParams = (v.layoutParams as FrameLayout.LayoutParams).apply {
                width = w; height = h
                leftMargin = x - loc[0]
                topMargin = y - loc[1]
            }
            shownRects += Rect(x, y, x + w, y + h)
        }

        // 맨 위 안내: 배경·테두리 없이 반투명 글씨만 (그림자로 밝은 화면에서도 보이게)
        val b = bannerView ?: TextView(svc).apply {
            // 흰 화면(카톡 등) 위에서도 보이게: 불투명 흰 글씨 + 굵은 검은 그림자 + 아주 옅은 검은 바탕
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(90, 0, 0, 0))
                cornerRadius = dp(8).toFloat()
            }
            maxLines = 3
            bannerView = this
            r.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        if (banner.isNullOrBlank()) {
            b.visibility = View.GONE
        } else {
            b.text = banner
            b.visibility = View.VISIBLE
            b.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val screenW = svc.resources.displayMetrics.widthPixels
            val w = minOf(b.measuredWidth, screenW - dp(24))
            val x = (screenW - w) / 2
            val y = statusBarHeight() + dp(2)
            b.layoutParams = (b.layoutParams as FrameLayout.LayoutParams).apply {
                width = w
                leftMargin = x - loc[0]
                topMargin = y - loc[1]
            }
            shownRects += Rect(x, y, x + w, y + b.measuredHeight + dp(20))
        }
        r.requestLayout()
    }

    private fun style(v: TextView, e: EvaluatedRow, textSp: Float) {
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
        v.apply {
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
        root?.visibility = View.GONE
        shownRects.clear()
    }

    /** 화면 캡처 OCR 이 자기 표시를 다시 읽지 않도록, 지금 떠 있는 표시들의 화면 위치 */
    fun ownRects(): List<Rect> {
        val list = shownRects.toMutableList()
        bubbleParams?.let { p -> bubble?.let { b -> list += Rect(p.x, p.y, p.x + b.width, p.y + b.height) } }
        checkParams?.let { p -> check?.let { c -> list += Rect(p.x, p.y, p.x + c.width, p.y + c.height) } }
        return list
    }

    // ───────── 둥근 버튼 ─────────

    private fun bubbleFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    // ───────── 터치 감지 칸 (안 보임, 1픽셀) ─────────
    // 버튼을 꺼도 "화면을 만졌다" 신호(터치 새로고침)와 화면 꺼짐 방지는 계속 되도록 따로 둔다.

    private var watcher: View? = null
    private var watcherParams: WindowManager.LayoutParams? = null

    private fun watcherFlags(): Int {
        // FLAG_WATCH_OUTSIDE_TOUCH: 화면 어디를 만져도 신호만 받음 (위치는 모름, 터치는 그대로 아래 앱으로)
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (store.keepScreenOn) f = f or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        return f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showWatcher() {
        if (watcher != null) return
        val v = View(svc)
        v.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) onScreenTouch()
            false
        }
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, watcherFlags(), PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        runCatching { wm.addView(v, lp) }
        watcher = v
        watcherParams = lp
    }

    private fun hideWatcher() {
        watcher?.let { runCatching { wm.removeView(it) } }
        watcher = null
        watcherParams = null
    }

    /**
     * 콜 앱 위 조작 칸 맞추기: 터치 감지 칸은 항상,
     * [콜 ON] / [콜 확인] 은 설정 맨 위 체크칸에 따라 각각.
     */
    fun showBubble() {
        showWatcher()
        if (store.showCallOn) showOnButton() else hideOnButton()
        if (store.showCheckOn) showCheck() else hideCheck()
    }

    /**
     * 둥근 버튼: 한 번 톡 = 표시 켜기/끄기, 두 번 톡 또는 길게 = 메뉴, 끌기 = 이동(위치 저장)
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showOnButton() {
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
            bubbleFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 처음 위치: 아래쪽 가운데 (내비게이션 바 바로 위)
            x = if (store.bubbleX >= 0) store.bubbleX else (dm.widthPixels - size) / 2
            y = if (store.bubbleY >= 0) store.bubbleY else dm.heightPixels - size - navBarHeight() - dp(24)
            alpha = 0.9f
        }

        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longFired = false
        var lastTapAt = 0L
        val singleTap = Runnable {
            store.overlayOn = !store.overlayOn
            updateBubbleLook()
            onToggle()
        }
        val longPress = Runnable {
            if (!moved) { longFired = true; showMenu() }
        }
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> { onScreenTouch(); return@setOnTouchListener false }
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y
                    moved = false; longFired = false
                    v.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true; v.removeCallbacks(longPress)
                        showTrash() // 끌기 시작하면 맨 위에 쓰레기통
                    }
                    if (moved) {
                        lp.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - size)
                        lp.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - size)
                        runCatching { wm.updateViewLayout(v, lp) }
                        highlightTrash(overTrash(lp.x + size / 2, lp.y + size / 2))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (moved) {
                        val drop = overTrash(lp.x + size / 2, lp.y + size / 2)
                        hideTrash()
                        if (drop) {
                            // 쓰레기통에 버림 → 앱 종료. 버튼 자리는 원래대로 (다음에 켤 때)
                            lp.x = startX; lp.y = startY
                            onTrash()
                        } else {
                            store.bubbleX = lp.x
                            store.bubbleY = lp.y
                        }
                    } else if (!longFired && ev.actionMasked == MotionEvent.ACTION_UP) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapAt < 300) {
                            // 두 번 톡 → 메뉴
                            v.removeCallbacks(singleTap)
                            lastTapAt = 0L
                            showMenu()
                        } else {
                            lastTapAt = now
                            v.postDelayed(singleTap, 300)
                        }
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

    private fun hideOnButton() {
        hideMenu()
        hideTrash()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    // ───────── [콜 확인] 버튼 (콜 ON 옆) ─────────

    private var check: TextView? = null
    private var checkParams: WindowManager.LayoutParams? = null

    private fun showCheck() {
        if (check != null) return
        val size = dp(54)
        val c = TextView(svc).apply {
            text = "콜\n확인"
            gravity = Gravity.CENTER
            textSize = 13f
            setLineSpacing(0f, 0.9f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#2F9E58"))
                setStroke(dp(2), Color.WHITE)
            }
        }
        val dm = svc.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.9f
            // 처음 위치: [콜 ON] 처음 자리(아래 가운데)의 왼쪽
            x = if (store.checkX >= 0) store.checkX else ((dm.widthPixels - size) / 2 - size - dp(12)).coerceAtLeast(0)
            y = if (store.checkY >= 0) store.checkY else dm.heightPixels - size - navBarHeight() - dp(24)
        }
        // 톡 = 콜 확인, 끌기 = 이동 (위치 따로 기억)
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        c.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - size)
                        lp.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - size)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) { store.checkX = lp.x; store.checkY = lp.y } else onCheck()
                }
            }
            true
        }
        runCatching { wm.addView(c, lp) }
        check = c
        checkParams = lp
    }

    private fun hideCheck() {
        check?.let { runCatching { wm.removeView(it) } }
        check = null
        checkParams = null
    }

    // ───────── 콜 확인 결과 창 (화면 가운데, 누르면 닫힘) ─────────

    private var result: View? = null

    fun showResult(title: String, lines: List<Pair<String, Int>>, big: String?, bigColor: Int) {
        hideResult()
        val box = svc.vbox(18).apply {
            background = svc.roundBg(Color.parseColor("#F21E232B"), Color.parseColor("#2F9E58"), 2, 18)
            setOnClickListener { hideResult() }
        }
        box.addView(svc.text(title, 17f, Color.WHITE, bold = true))
        for ((t, color) in lines) box.addView(svc.text(t, 16f, color).margins(svc, top = 6))
        if (big != null) box.addView(svc.text(big, 30f, bigColor, bold = true).margins(svc, top = 10))
        box.addView(svc.text("눌러서 닫기", 13f, C.GRAY).margins(svc, top = 10))
        val dm = svc.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.86).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        runCatching { wm.addView(box, lp) }
        result = box
    }

    fun hideResult() {
        result?.let { runCatching { wm.removeView(it) } }
        result = null
    }

    private fun navBarHeight(): Int {
        val id = svc.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) svc.resources.getDimensionPixelSize(id) else 0
    }

    // ───────── 쓰레기통 (끌어다 놓으면 종료) ─────────

    private var trash: TextView? = null
    private var trashCx = 0
    private var trashCy = 0

    private fun showTrash() {
        if (trash != null) return
        val dm = svc.resources.displayMetrics
        val size = dp(84)
        val t = TextView(svc).apply {
            text = "🗑\n종료"
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - size) / 2
            y = statusBarHeight() + dp(24)
        }
        trashCx = lp.x + size / 2
        trashCy = lp.y + size / 2
        wm.addView(t, lp)
        trash = t
        highlightTrash(false)
    }

    /** 버튼 가운데가 쓰레기통 가까이 왔는지 */
    private fun overTrash(cx: Int, cy: Int): Boolean {
        if (trash == null) return false
        val r = dp(70)
        val dx = cx - trashCx; val dy = cy - trashCy
        return dx * dx + dy * dy < r * r
    }

    private fun highlightTrash(on: Boolean) {
        val t = trash ?: return
        t.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (on) Color.parseColor("#E5D63B3B") else Color.parseColor("#CC2B323C"))
            setStroke(dp(3), if (on) Color.WHITE else Color.parseColor("#FF8A8A"))
        }
        t.scaleX = if (on) 1.2f else 1f
        t.scaleY = if (on) 1.2f else 1f
    }

    private fun hideTrash() {
        trash?.let { runCatching { wm.removeView(it) } }
        trash = null
    }

    // ───────── 메뉴 ─────────

    private var menu: View? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        hideMenu()
        val bp = bubbleParams ?: return
        val box = svc.vbox(10).apply {
            background = svc.roundBg(Color.parseColor("#F21E232B"), Color.parseColor("#4C8FE0"), 2, 14)
        }
        fun item(label: String, act: () -> Unit) = box.addView(svc.text(label, 17f, Color.WHITE, bold = true).apply {
            setPadding(dp(14), dp(12), dp(20), dp(12))
            setOnClickListener { hideMenu(); act() }
        })
        item("▢  읽을 영역 정하기") { onEditRegion() }
        item("🧹  기억한 콜 지우기") { onClearMemory() }
        item(if (store.overlayOn) "🙈  표시 끄기" else "👁  표시 켜기") {
            store.overlayOn = !store.overlayOn
            updateBubbleLook()
            onToggle()
        }
        item("✕  닫기") {}
        box.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) hideMenu()
            false
        }
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val dm = svc.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 버튼 왼쪽에 (자리가 없으면 오른쪽에)
            x = if (bp.x - box.measuredWidth - dp(8) >= 0) bp.x - box.measuredWidth - dp(8) else bp.x + dp(62)
            y = bp.y.coerceAtMost(dm.heightPixels - box.measuredHeight - dp(16)).coerceAtLeast(0)
        }
        wm.addView(box, lp)
        menu = box
    }

    private fun hideMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    // ───────── 읽을 영역 정하기 ─────────

    private var editor: View? = null
    val editing: Boolean get() = editor != null

    /**
     * 반투명 네모 틀을 띄운다. 가운데를 끌면 이동, 모서리를 끌면 크기 조절.
     * done(rect) : 저장(화면 좌표) / done(null) : 전체 화면으로 / 취소는 done 안 부름
     */
    fun editRegion(initial: Rect?, done: (Rect?) -> Unit) {
        closeEditor()
        hideLabels()
        hideMenu()
        val dm = svc.resources.displayMetrics
        val root = FrameLayout(svc)
        val start = initial ?: Rect(dp(16), dm.heightPixels / 5, dm.widthPixels - dp(16), dm.heightPixels * 4 / 5)
        val box = RegionView(svc, Rect(start))
        root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val bar = svc.hbox().apply {
            background = svc.roundBg(Color.parseColor("#F21E232B"), Color.parseColor("#4C8FE0"), 2, 14)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun btn(label: String, color: Int, act: () -> Unit) = bar.addView(svc.text(label, 17f, color, bold = true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { act() }
        })
        btn("저장", C.GOOD) {
            val loc = IntArray(2); root.getLocationOnScreen(loc)
            val r = Rect(box.rect).apply { offset(loc[0], loc[1]) }
            closeEditor(); done(r)
        }
        btn("전체 화면", C.ACCENT) { closeEditor(); done(null) }
        btn("취소", C.GRAY) { closeEditor(); onToggle() }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(90)
        })
        root.addView(svc.text("네모 안의 콜만 읽어요\n가운데를 끌면 이동 · 모서리를 끌면 크기 조절", 15f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = svc.roundBg(Color.parseColor("#E6161A21"), Color.parseColor("#4C8FE0"), 1, 10)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(60)
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(root, lp)
        editor = root
        // 창이 놓인 위치만큼 틀 좌표를 보정 (화면 좌표 → 창 좌표)
        root.post {
            val loc = IntArray(2); root.getLocationOnScreen(loc)
            box.rect.offset(-loc[0], -loc[1]); box.invalidate()
        }
    }

    private fun closeEditor() {
        editor?.let { runCatching { wm.removeView(it) } }
        editor = null
    }

    /** 반투명 바깥 + 테두리 + 모서리 동그라미. 끌어서 이동·크기 조절 */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class RegionView(ctx: android.content.Context, val rect: Rect) : View(ctx) {
        private val dim = android.graphics.Paint().apply { color = Color.parseColor("#88000000") }
        private val line = android.graphics.Paint().apply {
            color = Color.parseColor("#4C8FE0"); style = android.graphics.Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat()
        }
        private val dot = android.graphics.Paint().apply { color = Color.WHITE; isAntiAlias = true }
        private val dotLine = android.graphics.Paint().apply {
            color = Color.parseColor("#4C8FE0"); style = android.graphics.Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); isAntiAlias = true
        }
        private val handle = ctx.dp(14).toFloat()
        private val grab = ctx.dp(36)
        private val minSize = ctx.dp(60)
        private var mode = 0 // 0 없음, 1 이동, 2 왼위, 3 오위, 4 왼아래, 5 오아래
        private var lx = 0f; private var ly = 0f

        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawRect(0f, 0f, w, rect.top.toFloat(), dim)
            c.drawRect(0f, rect.bottom.toFloat(), w, h, dim)
            c.drawRect(0f, rect.top.toFloat(), rect.left.toFloat(), rect.bottom.toFloat(), dim)
            c.drawRect(rect.right.toFloat(), rect.top.toFloat(), w, rect.bottom.toFloat(), dim)
            c.drawRect(rect, line)
            for ((x, y) in listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom)) {
                c.drawCircle(x.toFloat(), y.toFloat(), handle, dot)
                c.drawCircle(x.toFloat(), y.toFloat(), handle, dotLine)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val x = ev.x; val y = ev.y
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fun near(px: Int, py: Int) = abs(x - px) < grab && abs(y - py) < grab
                    mode = when {
                        near(rect.left, rect.top) -> 2
                        near(rect.right, rect.top) -> 3
                        near(rect.left, rect.bottom) -> 4
                        near(rect.right, rect.bottom) -> 5
                        rect.contains(x.toInt(), y.toInt()) -> 1
                        else -> 0
                    }
                    lx = x; ly = y
                    return mode != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (x - lx).toInt(); val dy = (y - ly).toInt()
                    lx = x; ly = y
                    when (mode) {
                        1 -> {
                            val ox = dx.coerceIn(-rect.left, width - rect.right)
                            val oy = dy.coerceIn(-rect.top, height - rect.bottom)
                            rect.offset(ox, oy)
                        }
                        2 -> { rect.left = (rect.left + dx).coerceIn(0, rect.right - minSize); rect.top = (rect.top + dy).coerceIn(0, rect.bottom - minSize) }
                        3 -> { rect.right = (rect.right + dx).coerceIn(rect.left + minSize, width); rect.top = (rect.top + dy).coerceIn(0, rect.bottom - minSize) }
                        4 -> { rect.left = (rect.left + dx).coerceIn(0, rect.right - minSize); rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, height) }
                        5 -> { rect.right = (rect.right + dx).coerceIn(rect.left + minSize, width); rect.bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, height) }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
            }
            return true
        }
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

    /** 콜 앱이 아닌 화면으로 가면: 버튼·감지 칸·결과 창 모두 내림 */
    fun hideBubble() {
        hideOnButton()
        hideCheck()
        hideResult()
        hideWatcher()
    }

    fun hideAll() {
        hideLabels()
        hideBubble()
        hideMenu()
    }

    fun destroy() {
        hideBubble()
        hideMenu()
        closeEditor()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        labelViews.clear()
        bannerView = null
    }

    /** 화면 전체 (화면 좌표) */
    private fun screenRect(): Rect = if (Build.VERSION.SDK_INT >= 30) Rect(wm.currentWindowMetrics.bounds)
    else svc.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    private fun statusBarHeight(): Int {
        val id = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) svc.resources.getDimensionPixelSize(id) else dp(24)
    }
}
