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
    // 잠깐 놓친 콜 유지 (깜빡임 방지)
    private val sticky = com.jongin.callcalc.core.StickyRows(1500)
    // 지금 화면에 있는 앱
    private var curPkg: String? = null
    private var lastAskAt = 0L

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            // 잠금 해제 직후 잠깐 기다렸다가 (잠금 화면이 사라진 뒤) 묻기
            handler.postDelayed({ maybeAskCapture() }, 700)
        }
    }

    /** 화면이 꺼지면서 캡처가 끊겼고, 캡처가 필요한 설정이면 시작 창을 자동으로 띄운다 (1분에 한 번까지) */
    private fun maybeAskCapture() {
        if (!store.autoAskCapture || !CaptureService.stoppedBySystem || CaptureService.instance != null) return
        if (store.readMode == 1) return
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km.isKeyguardLocked) return
        val now = SystemClock.uptimeMillis()
        if (now - lastAskAt < 60_000) return
        lastAskAt = now
        runCatching {
            startActivity(android.content.Intent(this, CaptureStartActivity::class.java)
                .putExtra(CaptureStartActivity.EXTRA_AUTO, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 읽을 영역 정하기 (지금 앱 기준으로 저장) */
    private fun editRegion() {
        val pkg = curPkg ?: return
        overlay.editRegion(store.region(pkg)) { r ->
            store.setRegion(pkg, r)
            listTop = null
            sticky.clear()
            lastKey = null
            CaptureService.instance?.forgetLast()
            Toast.makeText(this, if (r == null) "전체 화면을 읽어요" else "네모 안만 읽어요", Toast.LENGTH_SHORT).show()
            schedule(50)
        }
    }

    override fun onServiceConnected() {
        store = Store(this)
        overlay = Overlay(
            this, store,
            onToggle = { lastKey = null; schedule(30) },
            onScreenTouch = { onScreenTouch() },
            onClearMemory = {
                memory.clear()
                sticky.clear()
                Toast.makeText(this, "기억한 콜을 지웠어요", Toast.LENGTH_SHORT).show()
                schedule(50)
            },
            onEditRegion = { editRegion() },
        )
        // 화면이 다시 켜지면: 꺼지면서 끊긴 화면 캡처를 다시 묻기
        androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_USER_PRESENT)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
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
                if (target) { memory.clear(); listTop = null; sticky.clear() }
                schedule(60)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (target) {
                // 스크롤하는 동안은 숨기고, 멈추면 다시 읽는다
                lastScrollAt = SystemClock.uptimeMillis()
                sticky.clear() // 스크롤하면 위치가 바뀌니 예전 줄은 버림
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
        runCatching { unregisterReceiver(screenReceiver) }
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
        shownSig = null
        candSig = null; candTries = 0; cand = null
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
        if (lastPkg != pkg) { memory.clear(); listTop = null; sticky.clear(); lastPkg = pkg }
        curPkg = pkg
        if (overlay.editing) return // 영역 정하는 중엔 읽지 않음
        overlay.showBubble()
        if (!store.overlayOn) { hideLabels(); schedule(HEARTBEAT); return }
        val sinceScroll = SystemClock.uptimeMillis() - lastScrollAt
        if (sinceScroll < SCROLL_SETTLE) { schedule(SCROLL_SETTLE - sinceScroll + 10, postpone = true); return }

        val mode = store.readMode
        if (mode != 2) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val region = store.region(pkg)
            val items = collect(root).let { all ->
                if (region == null) all else all.filter { region.contains(it.centerX.toInt(), it.centerY.toInt()) }
            }
            val rows = stick(LiveRules.dropEdgeFailures(CallParser.parse(items)))
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
        // 읽을 네모: 직접 정한 영역 > 자동(목록 시작 높이 아래) > 상태바 아래 전체
        val crop = curPkg?.let { store.region(it) } ?: cap.defaultCrop(listTop ?: statusBarHeight())
        cap.captureAndRead(overlay.ownRects(), crop) { res ->
            ocrBusy = false
            val scrolledMeanwhile = lastScrollAt > startedAt
            when {
                scrolledMeanwhile -> schedule(SCROLL_SETTLE, postpone = true)
                res is CaptureService.Result.Items -> {
                    val rows = stick(LiveRules.dropEdgeFailures(CallParser.parse(res.items)))
                    val manual = curPkg?.let { store.region(it) } != null
                    if (rows.isEmpty() && listTop != null && !manual) {
                        // 잘라 읽어서 못 찾았을 수 있음 → 다음엔 화면 전체로
                        listTop = null
                        cap.forgetLast()
                        ocrAgain = true
                    } else {
                        if (rows.isNotEmpty()) updateListTop(rows)
                        show(rows, res.ms, if (rows.isEmpty()) "콜을 찾지 못했어요 (화면 캡처)" else null)
                    }
                }
                res is CaptureService.Result.Unchanged -> if (!confirmPending()) lastShown?.let { (out, banner) ->
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

    /** 바로 전에 보였는데 이번에만 놓친 콜은 잠깐 유지 */
    private fun stick(rows: List<CallRow>): List<CallRow> =
        if (SystemClock.uptimeMillis() - lastScrollAt < 600) rows else sticky.apply(rows, SystemClock.uptimeMillis())

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    // ── 한꺼번에 표시 ──
    // 읽을 때마다 바로 바꾸면 콜이 하나씩 붙는 것처럼 보여서,
    // 같은 결과가 두 번 연속 나와 "확정"됐을 때만 표시를 바꾼다. 그 전엔 이전 표시를 그대로 둔다.
    private var shownSig: String? = null
    private var candSig: String? = null
    private var candTries = 0
    private var cand: Triple<List<CallRow>, Long, String?>? = null

    /** 콜 목록 지문: 어떤 콜이 어느 높이에 있는지 */
    private fun signature(rows: List<CallRow>) = rows.joinToString(";") { "${it.key}@${it.top / 24}" }

    /** 이번 읽기 결과를 받아서, 확정됐으면 표시 */
    private fun show(rows: List<CallRow>, readMs: Long, note: String?) {
        val sig = signature(rows)
        val visible = overlayVisible && shownSig == sig
        if (rows.isNotEmpty() && !visible) {
            if (sig != candSig) {
                candTries++
                candSig = sig
                cand = Triple(rows, readMs, note)
                // 세 번 연속 다르게 읽히면 더 기다리지 않고 마지막 결과를 보여 줌
                if (candTries < 3) { schedule(CONFIRM_DELAY); return }
            }
        }
        candSig = null; candTries = 0; cand = null
        shownSig = sig
        display(rows, readMs, note)
    }

    /** 화면이 그대로라 OCR 을 건너뛴 경우 = 지난 결과가 확정된 것 */
    private fun confirmPending(): Boolean {
        val (rows, ms, note) = cand ?: return false
        candSig = null; candTries = 0; cand = null
        shownSig = signature(rows)
        display(rows, ms, note)
        return true
    }

    private fun display(rows: List<CallRow>, readMs: Long, note: String?) {
        if (!store.overlayOn) { hideLabels(); return }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val s = store.evalSettings()
        val (out, summary) = if (store.bestAllSeen) memory.evaluate(rows, s) else Evaluator.evaluate(rows, s) to null
        val calcMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        val parts = mutableListOf(MainActivity.pretty(store.selectedFormula.expr) + " (픽업 1km=${Store.plain(store.pickupMinPerKm)}분)")
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
        private const val CONFIRM_DELAY = 80L    // 확정하려고 한 번 더 읽기까지
        private const val TOUCH_FOLLOW_UP = 600L // 만진 뒤 한 번 더 확인
        @Volatile var instance: CallReaderService? = null
    }
}
