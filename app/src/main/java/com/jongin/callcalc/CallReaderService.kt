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
                if (target) memory.clear()
                schedule(150)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (target) {
                // 스크롤하는 동안은 숨기고, 멈추면 다시 읽는다
                lastScrollAt = SystemClock.uptimeMillis()
                hideLabels()
                schedule(350)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (target) {
                val sinceScroll = SystemClock.uptimeMillis() - lastScrollAt
                schedule(if (sinceScroll < 350) 350 else 150)
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

    private fun hideLabels() {
        overlay.hideLabels()
        overlayVisible = false
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, delayMs)
    }

    private fun refresh() {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val pkg = root?.packageName?.toString()
        // 콜 계산기 자기 화면 위에서는 표시하지 않음
        if (pkg == null || pkg == packageName || !store.watches(pkg)) {
            // 키보드나 알림창 등이 잠깐 위에 올라온 경우가 아니면 숨긴다
            if (pkg != null) { hideLabels(); overlay.hideBubble(); lastPkg = pkg }
            return
        }
        if (lastPkg != pkg) { memory.clear(); lastPkg = pkg }
        overlay.showBubble()
        if (!store.overlayOn) { hideLabels(); schedule(HEARTBEAT); return }
        if (SystemClock.uptimeMillis() - lastScrollAt < 300) { schedule(300); return }

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
            show(emptyList(), -1, "글자를 못 읽었어요 · 콜 계산기 앱에서 [화면 캡처 시작]을 눌러 주세요")
            schedule(HEARTBEAT)
            return
        }
        if (ocrBusy) { ocrAgain = true; return }
        ocrBusy = true
        val startedAt = SystemClock.uptimeMillis()
        cap.captureAndRead(overlay.ownRects()) { res ->
            ocrBusy = false
            val scrolledMeanwhile = lastScrollAt > startedAt
            when {
                scrolledMeanwhile -> schedule(350)
                res is CaptureService.Result.Items -> {
                    val rows = LiveRules.dropEdgeFailures(CallParser.parse(res.items))
                    show(rows, res.ms, if (rows.isEmpty()) "콜을 찾지 못했어요 (화면 캡처)" else null)
                }
                res is CaptureService.Result.Unchanged -> lastShown?.let { (out, banner) ->
                    if (store.overlayOn && !overlayVisible) { overlay.showLabels(out.rows, banner); overlayVisible = true }
                }
                res is CaptureService.Result.Black ->
                    show(emptyList(), -1, "화면 캡처가 막혀 있어요 (콜 앱이 캡처를 막음). 접근성 방식만 쓸 수 있어요")
                res is CaptureService.Result.Error -> show(emptyList(), -1, res.msg)
            }
            if (ocrAgain) { ocrAgain = false; schedule(50) } else schedule(HEARTBEAT)
        }
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
        private const val HEARTBEAT = 1500L
        @Volatile var instance: CallReaderService? = null
    }
}
