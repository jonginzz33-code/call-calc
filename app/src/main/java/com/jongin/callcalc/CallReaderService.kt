package com.jongin.callcalc

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.jongin.callcalc.core.CallParser
import com.jongin.callcalc.core.CallRow
import com.jongin.callcalc.core.EvalOutput
import com.jongin.callcalc.core.Evaluator
import com.jongin.callcalc.core.LiveRules
import com.jongin.callcalc.core.SeenMemory
import com.jongin.callcalc.core.TextItem

/**
 * 실시간 읽기 (설명서 v0.2, v0.3).
 * 고른 콜 앱이 화면에 있을 때만 동작한다. 글자를 읽고 계산해서 표시만 한다.
 * 자동 터치·자동 수락은 하지 않는다 (제스처 권한도 선언하지 않음).
 *
 * 읽는 방식 (설정):
 *  0 자동: 접근성으로 먼저 읽고, 콜을 못 찾으면 화면 캡처(켜져 있을 때) + OCR
 *  1 접근성만
 *  2 화면 캡처(OCR)만
 */
class CallReaderService : AccessibilityService() {
    private lateinit var store: Store
    private lateinit var overlay: Overlay
    private val handler = Handler(Looper.getMainLooper())
    private val memory = SeenMemory()
    private var lastScrollAt = 0L
    private var ocrBusy = false
    private var ocrAgain = false
    private var lastShown: Pair<EvalOutput, String?>? = null
    private var lastPkg: String? = null
    private var lastKey: String? = null
    private var overlayVisible = false

    private val refreshTask = Runnable { refresh() }
    // 터치 직후 한 번 더 (화면이 바뀌는 데 시간이 걸리는 경우)
    private val touchFollowUp = Runnable { schedule(0) }

    // 콜 목록이 시작되는 높이 (화면 좌표). 이보다 위는 OCR 안 함. 모르면 null
    private var listTop: Int? = null

    override fun onServiceConnected() {
        store = Store(this)
        overlay = Overlay(
            this, store,
            onLongPress = {
                memory.clear()
                Toast.makeText(this, "기억한 콜을 지웠어요", Toast.LENGTH_SHORT).show()
                schedule(50)
            },
            onToggle = { schedule(30) },
            onScreenTouch = { onScreenTouch() },
        )
        instance = this
        schedule(300)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        val target = store.watches(pkg)
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 다른 화면으로 넘어가면 (목록 새로고침, 상세 화면 등) 기억을 지운다
                if (target) { memory.clear(); listTop = null }
                schedule(60)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (target) {
                // 스크롤하는 동안은 숨기고, 멈추면 다시 읽는다
                lastScrollAt = SystemClock.uptimeMillis()
                hideLabels()
                schedule(SCROLL_SETTLE, postpone = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (target) {
                val sinceScroll = SystemClock.uptimeMillis() - lastScrollAt
                // 화면이 계속 바뀌어도 이미 잡힌 읽기를 뒤로 미루지 않는다 (예전엔 계속 밀려서 늦게 떴음)
                schedule(if (sinceScroll < SCROLL_SETTLE) SCROLL_SETTLE else 60)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.destroy()
        instance = null
    }

    /** 설정 화면에서 바꾸면 바로 반영 */
    fun settingsChanged() {
        overlay.applySettings()
        lastKey = null
        CaptureService.instance?.forgetLast()
        schedule(50)
    }

    fun captureStateChanged() = schedule(100)

    /** 화면 어디든 만지면 (손을 떼는 시점은 알 수 없어서) 조금 뒤 읽고, 한 번 더 확인 */
    private fun onScreenTouch() {
        schedule(TOUCH_DELAY)
        handler.removeCallbacks(touchFollowUp)
        handler.postDelayed(touchFollowUp, TOUCH_FOLLOW_UP)
    }

    private fun hideLabels() {
        overlay.hideLabels()
        overlayVisible = false
    }

    private var dueAt = 0L

    /**
     * delayMs 뒤에 읽기. 이미 더 이른 읽기가 잡혀 있으면 그대로 둔다.
     * postpone = true (스크롤 중) 일 때만 뒤로 미룬다.
     */
    private fun schedule(delayMs: Long, postpone: Boolean = false) {
        val at = SystemClock.uptimeMillis() + delayMs
        if (!postpone && dueAt > SystemClock.uptimeMillis() && dueAt <= at) return
        handler.removeCallbacks(refreshTask)
        dueAt = at
        handler.postAtTime(refreshTask, at)
    }

    private fun refresh() {
        dueAt = 0L
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val pkg = root?.packageName?.toString()
        // 콜 계산기 자기 화면 위에서는 표시하지 않음
        if (pkg == null || pkg == packageName || !store.watches(pkg)) {
            // 키보드나 알림창 등이 잠깐 위에 올라온 경우가 아니면 숨긴다
            if (pkg != null) { hideLabels(); overlay.hideBubble(); lastPkg = pkg }
            return
        }
        if (lastPkg != pkg) { memory.clear(); listTop = null; lastPkg = pkg }
        overlay.showBubble()
        if (!store.overlayOn) { hideLabels(); schedule(HEARTBEAT); return }
        val sinceScroll = SystemClock.uptimeMillis() - lastScrollAt
        if (sinceScroll < SCROLL_SETTLE) { schedule(SCROLL_SETTLE - sinceScroll + 10, postpone = true); return }

        val mode = store.readMode
        if (mode != 2) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val items = collect(root)
            val rows = LiveRules.dropEdgeFailures(CallParser.parse(items))
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
            if (rows.isNotEmpty() || mode == 1) {
                show(rows, ms, if (rows.isEmpty()) "콜을 찾지 못했어요 (접근성)" else null)
                schedule(HEARTBEAT)
                return
            }
        }
        readByOcr()
    }

    /** 화면 캡처 + OCR. 이미 읽는 중이면 끝난 뒤 한 번 더 */
    private fun readByOcr() {
        val cap = CaptureService.instance
        if (cap == null) {
            val note = if (CaptureService.stoppedBySystem)
                "⚠ 화면 캡처가 꺼졌어요 (화면이 꺼지면 안드로이드가 자동으로 꺼요) · 콜 계산기 앱에서 [화면 캡처 시작]을 다시 눌러 주세요"
            else "글자를 못 읽었어요 · 콜 계산기 앱에서 [화면 캡처 시작]을 눌러 주세요"
            show(emptyList(), -1, note)
            schedule(HEARTBEAT)
            return
        }
        if (ocrBusy) { ocrAgain = true; return }
        ocrBusy = true
        val startedAt = SystemClock.uptimeMillis()
        val cropTop = listTop ?: statusBarHeight()
        cap.captureAndRead(overlay.ownRects(), cropTop) { res ->
            ocrBusy = false
            val scrolledMeanwhile = lastScrollAt > startedAt
            when {
                scrolledMeanwhile -> schedule(SCROLL_SETTLE, postpone = true)
                res is CaptureService.Result.Items -> {
                    val rows = LiveRules.dropEdgeFailures(CallParser.parse(res.items))
                    if (rows.isEmpty() && listTop != null) {
                        // 잘라 읽어서 못 찾았을 수 있음 → 다음엔 화면 전체로
                        listTop = null
                        cap.forgetLast()
                        ocrAgain = true
                    } else {
                        if (rows.isNotEmpty()) updateListTop(rows)
                        show(rows, res.ms, if (rows.isEmpty()) "콜을 찾지 못했어요 (화면 캡처)" else null)
                    }
                }
                res is CaptureService.Result.Unchanged -> lastShown?.let { (out, banner) ->
                    if (store.overlayOn && !overlayVisible) { overlay.showLabels(out.rows, banner); overlayVisible = true }
                }
                res is CaptureService.Result.Black ->
                    show(emptyList(), -1, "화면 캡처가 막혀 있어요 (콜 앱이 캡처를 막음). 접근성 방식만 쓸 수 있어요")
                res is CaptureService.Result.Error -> show(emptyList(), -1, res.msg)
            }
            // 화면 캡처는 이벤트가 안 오는 변화(사진 넘기기 등)도 있어서 더 자주 확인 (같은 화면이면 OCR 안 함)
            if (ocrAgain) { ocrAgain = false; schedule(30) } else schedule(OCR_POLL)
        }
    }

    /**
     * 찾은 콜 중 가장 위 줄보다 한 줄 높이만큼 위를 목록 시작으로 기억 (더 위로만 넓힘).
     * 스크롤해도 목록 시작 높이는 그대로라서, 다음부터는 그 아래만 OCR 한다.
     */
    private fun updateListTop(rows: List<CallRow>) {
        val rowH = rows.map { it.bottom - it.top }.average()
        val est = (rows.minOf { it.top } - rowH).toInt().coerceAtLeast(statusBarHeight())
        listTop = minOf(listTop ?: est, est)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun show(rows: List<CallRow>, readMs: Long, note: String?) {
        if (!store.overlayOn) { hideLabels(); return }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val s = store.evalSettings()
        val (out, summary) = if (store.bestAllSeen) memory.evaluate(rows, s) else Evaluator.evaluate(rows, s) to null
        val calcMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        val parts = mutableListOf(MainActivity.pretty(store.selectedFormula.expr))
        if (summary != null) parts += summary
        if (store.showSpeed && readMs >= 0) parts += "⏱ ${readMs + calcMs}ms"
        if (note != null) parts += note
        val banner = parts.joinToString(" · ")
        // 내용이 같으면 다시 그리지 않음 (깜빡임 방지). 속도 숫자만 바뀐 건 무시
        val key = out.rows.hashCode().toString() + "|" + summary + "|" + note + "|" + store.overlayTextSp
        val wasHidden = lastShown == null || !overlayVisible
        lastShown = out to banner
        if (key == lastKey && !wasHidden) return
        lastKey = key
        overlayVisible = true
        overlay.showLabels(out.rows, banner)
    }

    /**
     * 접근성 트리에서 글자와 위치를 모은다.
     * 자식이 있는 묶음의 설명(contentDescription)은 줄 전체를 합친 글이라 건너뛰고, 글자 칸만 쓴다.
     * 여러 줄 글은 줄마다 나눠 위치를 나눈다.
     */
    private fun collect(root: AccessibilityNodeInfo): List<TextItem> {
        val out = ArrayList<TextItem>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        val r = Rect()
        var count = 0
        while (stack.isNotEmpty() && count < 4000) {
            val n = stack.removeLast()
            count++
            val txt = n.text?.toString()
            val t = when {
                !txt.isNullOrBlank() -> txt
                n.childCount == 0 -> n.contentDescription?.toString()
                else -> null
            }
            if (!t.isNullOrBlank() && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    val lines = t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    val lh = r.height() / maxOf(1, lines.size)
                    lines.forEachIndexed { i, line ->
                        val top = r.top + i * lh
                        val key = "$line|${r.left}|$top"
                        if (seen.add(key)) out += TextItem(line, r.left, top, r.right, top + lh)
                    }
                }
            }
            for (i in n.childCount - 1 downTo 0) {
                runCatching { n.getChild(i) }.getOrNull()?.let { stack.add(it) }
            }
        }
        return out
    }

    companion object {
        private const val HEARTBEAT = 1000L      // 변화가 없어도 다시 확인하는 간격
        private const val OCR_POLL = 500L        // 화면 캡처 방식 확인 간격
        private const val SCROLL_SETTLE = 250L   // 스크롤이 멈췄다고 보는 시간
        private const val TOUCH_DELAY = 150L     // 화면을 만진 뒤 읽기까지
        private const val TOUCH_FOLLOW_UP = 600L // 만진 뒤 한 번 더 확인
        @Volatile var instance: CallReaderService? = null
    }
}
