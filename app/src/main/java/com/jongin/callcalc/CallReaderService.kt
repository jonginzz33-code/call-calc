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
import com.jongin.callcalc.core.DetailParser
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
    private lateinit var stats: StatsStore
    private var source = ""                       // 이번에 읽은 방식: 접근성 / 캡처
    private var lastItems: List<TextItem> = emptyList()
    private val alerted = HashSet<String>()       // 이미 알린 좋은 콜
    private var alertNote: String? = null
    private var alertUntil = 0L
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
        stats = StatsStore(this, store)
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
            onCheck = { checkCall() },
            onTrash = {
                // 쓰레기통에 버림 = [앱 종료]와 같음. 앱을 다시 열면 켜짐
                store.paused = true
                if (CaptureService.instance != null) CaptureService.stop(this)
                pause()
                Toast.makeText(this, "종료했어요. 앱을 다시 열면 켜져요", Toast.LENGTH_SHORT).show()
            },
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
        if (store.paused) return // [앱 종료] 상태: 아무것도 안 함
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
            // 터치할 때만 모드에선 화면이 바뀌어도 저절로 다시 읽지 않음
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (target && store.refreshAuto) {
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
        CallCheck.stopLocation(this)
        if (::stats.isInitialized) stats.save()
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

    /** 정기 확인: 자동 모드에서만 */
    private fun idle(ms: Long) {
        if (store.refreshAuto) schedule(ms)
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

    /** [앱 종료]: 표시와 버튼을 모두 내리고 멈춤 */
    fun pause() {
        handler.removeCallbacks(refreshTask)
        hideLabels()
        overlay.hideBubble()
        memory.clear(); sticky.clear()
        lastPkg = null
    }

    /** 통계 기록 지우기 (설정에서) */
    fun clearStats() { stats.clear(); alerted.clear(); lastKey = null; schedule(50) }

    /** 버튼 위치를 처음으로 → 다시 그리기 */
    fun resetBubble() {
        overlay.hideBubble()
        schedule(50)
    }

    /** 앱을 다시 열면 이어서 */
    fun resume() = schedule(100)

    private fun refresh() {
        dueAt = 0L
        if (store.paused) { hideLabels(); overlay.hideBubble(); CallCheck.stopLocation(this); return }
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val pkg = root?.packageName?.toString()
        // 계산기 자기 화면 위에서는 표시하지 않음
        if (pkg == null || pkg == packageName || !store.watches(pkg)) {
            // 키보드나 알림창 등이 잠깐 위에 올라온 경우가 아니면 숨긴다
            if (pkg != null) { hideLabels(); overlay.hideBubble(); lastPkg = pkg; CallCheck.stopLocation(this) }
            return
        }
        if (lastPkg != pkg) { memory.clear(); listTop = null; sticky.clear(); lastPkg = pkg }
        curPkg = pkg
        if (overlay.editing) return // 영역 정하는 중엔 읽지 않음
        overlay.showBubble()
        CallCheck.startLocation(this) // 감시하는 앱이 떠 있는 동안 위치를 미리 잡아 둠
        if (!store.overlayOn) { hideLabels(); idle(HEARTBEAT); return }
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
            lastItems = items
            // 콜 상세 화면(수락 전)이면: 목록이 아니니 "못 읽었어요" 안내 없이, [콜 확인]을 쓰면 됨
            val detail = if (rows.isEmpty()) DetailParser.parse(items) else null
            if (detail != null) {
                prefetch(detail)
                source = "접근성"
                show(emptyList(), ms, "상세 화면 · [콜 확인]으로 픽업·운행 계산")
                idle(HEARTBEAT)
                return
            }
            if (rows.isNotEmpty() || mode == 1) {
                source = "접근성"
                show(rows, ms, if (rows.isEmpty()) "콜을 찾지 못했어요 (접근성)" else null)
                idle(HEARTBEAT)
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
                "⚠ 화면 캡처 꺼짐 (화면이 꺼져서) · 앱에서 [화면 캡처 시작]"
            else "글자를 못 읽었어요 · 앱에서 [화면 캡처 시작]"
            show(emptyList(), -1, note)
            idle(HEARTBEAT)
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
                    source = "캡처"
                    lastItems = res.items
                    val capDetail = DetailParser.parse(res.items)
                    capDetail?.let { prefetch(it) }
                    val rows = stick(LiveRules.dropEdgeFailures(CallParser.parse(res.items)))
                    val manual = curPkg?.let { store.region(it) } != null
                    if (rows.isEmpty() && listTop != null && !manual) {
                        // 잘라 읽어서 못 찾았을 수 있음 → 다음엔 화면 전체로
                        listTop = null
                        cap.forgetLast()
                        ocrAgain = true
                    } else {
                        if (rows.isNotEmpty()) updateListTop(rows)
                        show(rows, res.ms, when {
                            rows.isNotEmpty() -> null
                            capDetail != null -> "상세 화면 · [콜 확인]으로 픽업·운행 계산"
                            else -> "콜을 찾지 못했어요 (화면 캡처)"
                        })
                    }
                }
                res is CaptureService.Result.Unchanged -> if (!confirmPending()) lastShown?.let { (out, banner) ->
                    if (store.overlayOn && !overlayVisible) { overlay.showLabels(out.rows, banner, labelBounds()); overlayVisible = true }
                }
                res is CaptureService.Result.Black ->
                    show(emptyList(), -1, "⚠ 이 앱은 화면 캡처를 막아 둠 · 접근성만 가능")
                res is CaptureService.Result.Error -> show(emptyList(), -1, res.msg)
            }
            // 화면 캡처는 이벤트가 안 오는 변화(사진 넘기기 등)도 있어서 더 자주 확인 (같은 화면이면 OCR 안 함)
            if (ocrAgain) { ocrAgain = false; schedule(30) } else idle(OCR_POLL)
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
        // 콜이 0개로 읽힌 것도 확정이 필요 (한 번 놓쳤다고 표시가 다 사라지지 않게)
        if (!visible) {
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
        // 오늘 통계 기록 (중복 빼고) + 좋은 콜 알림
        stats.record(rows)
        checkGoodCalls(out.rows)

        // 맨 위 투명 글씨: 1줄 = 오늘 통계 · 읽은 방식 · 속도, 2줄 = 알림/안내 (있을 때만)
        val line1 = mutableListOf<String>()
        stats.summary()?.let { line1 += it }
        if (summary != null) line1 += summary
        if (source.isNotEmpty()) line1 += source
        if (store.showSpeed && readMs >= 0) line1 += "⏱${readMs + calcMs}ms"
        val line2 = listOfNotNull(
            alertNote?.takeIf { SystemClock.uptimeMillis() < alertUntil },
            note,
        )
        val banner = (listOf(line1.joinToString(" · ")) + line2).filter { it.isNotBlank() }.joinToString("\n")
        // 내용이 같으면 다시 그리지 않음 (깜빡임 방지). 속도 숫자만 바뀐 건 무시
        val key = out.rows.hashCode().toString() + "|" + summary + "|" + note + "|" + store.overlayTextSp +
            "|" + stats.summary() + "|" + line2 + "|" + source
        val wasHidden = lastShown == null || !overlayVisible
        lastShown = out to banner
        if (key == lastKey && !wasHidden) return
        lastKey = key
        overlayVisible = true
        overlay.showLabels(out.rows, banner, labelBounds())
    }

    /** 분당 금액이 기준 이상인 새 콜이 있으면 진동 + 소리 (같은 콜은 한 번만, 빨강 지역은 빼고) */
    private fun checkGoodCalls(rows: List<com.jongin.callcalc.core.EvaluatedRow>) {
        if (!store.goodAlertOn) return
        val min = store.goodAlertMin
        val good = rows.filter {
            it.value != null && it.value!! >= min && it.regionColor != com.jongin.callcalc.core.RegionColor.RED &&
                alerted.add(it.row.key)
        }
        if (good.isEmpty()) return
        val best = good.maxByOrNull { it.value!! }!!
        alertNote = "🔥 분당 ${com.jongin.callcalc.core.formatNumber(best.value!!)} · " +
            "${LiveRules.shortPlace(best.row.destination)} · ${com.jongin.callcalc.core.formatNumber(best.row.c!!)}"
        alertUntil = SystemClock.uptimeMillis() + 6000
        Alert.play(this)
        if (alerted.size > 500) alerted.clear()
        // 6초 뒤 알림 글씨 지우기
        handler.postDelayed({ lastKey = null; schedule(0) }, 6100)
    }

    /**
     * [콜 확인]: 상세 화면에서 출발지를 읽고, 내 위치 → 출발지 도로 거리(km)를 보여 준다.
     * 누를 때 화면을 새로 읽는다: 접근성 먼저, 안 되면 (화면 캡처가 켜져 있으면) 그 자리에서 찍어서 글자 인식.
     */
    private fun checkCall() {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val accItems = root?.let { collect(it) }.orEmpty()
        DetailParser.parse(accItems)?.let { runPickup(it); return }

        val cap = CaptureService.instance
        if (cap == null) {
            overlay.showResult("콜 확인", listOf(
                "출발·도착 글자를 못 찾았어요." to C.FG,
                "상세 화면([콜 수락하기]가 있는 화면)에서 눌러 주세요." to C.SUB,
                "사진(갤러리)으로 시험할 땐 앱에서 [화면 캡처 시작]을 먼저 눌러 주세요." to C.WARN,
            ), null, C.FG)
            return
        }
        overlay.showResult("콜 확인 · 화면 읽는 중…", emptyList(), null, C.FG)
        cap.forgetLast() // 같은 화면이어도 새로 글자 인식
        cap.captureAndRead(overlay.ownRects(), cap.defaultCrop(statusBarHeight())) { res ->
            val items = (res as? CaptureService.Result.Items)?.items ?: lastItems
            val d = DetailParser.parse(items)
            if (d == null) {
                overlay.showResult("콜 확인", listOf(
                    "출발·도착 글자를 못 찾았어요." to C.FG,
                    "상세 화면([콜 수락하기]가 있는 화면)에서 눌러 주세요." to C.SUB,
                ), null, C.FG)
            } else runPickup(d)
        }
    }

    // ── [콜 확인] 미리 계산 + 기억 ──
    // 상세 화면이 열리면 뒤에서 미리 계산해 두고 (같은 콜은 3분 동안 기억), 누르면 바로 보여 준다.

    private class CheckJob(val key: String, val at: Long) {
        var latest: CallCheck.Result? = null
        var listener: ((CallCheck.Result) -> Unit)? = null
    }

    private val jobs = LinkedHashMap<String, CheckJob>()
    private var shownJobKey: String? = null

    private fun jobKey(d: com.jongin.callcalc.core.CallDetail) = "${d.origin}|${d.destination}|${d.price}"

    /** 이 콜의 계산 (이미 있으면 그것, 없으면 새로 시작) */
    private fun jobFor(d: com.jongin.callcalc.core.CallDetail): CheckJob {
        val k = jobKey(d)
        val now = SystemClock.uptimeMillis()
        jobs[k]?.takeIf { now - it.at < 180_000 }?.let { return it }
        val job = CheckJob(k, now)
        jobs[k] = job
        while (jobs.size > 20) jobs.remove(jobs.keys.first())
        CallCheck.run(d, store.tmapKey, CallCheck.freshLocation(this), store.pickupMinPerKm) { r ->
            job.latest = r
            job.listener?.invoke(r)
        }
        return job
    }

    /** 상세 화면이 보이면 미리 계산 시작 (누르기 전에) */
    private fun prefetch(d: com.jongin.callcalc.core.CallDetail) {
        if (store.tmapKey.isBlank() || !store.showCheckOn) return
        jobFor(d)
    }

    /** 계산 결과 → 결과 카드 (나오는 대로 갱신) */
    private fun runPickup(detail: com.jongin.callcalc.core.CallDetail) {
        val job = jobFor(detail)
        shownJobKey = job.key
        render(job.latest ?: CallCheck.Result(detail, detail.origin, detail.destination, null, null, false))
        job.listener = { r -> if (shownJobKey == job.key && overlay.resultShowing) render(r) }
    }

    private fun render(r: CallCheck.Result) {
        fun km(v: Double?) = v?.let { String.format(java.util.Locale.US, "%.1fkm", it) } ?: "—"
        fun legKm(l: CallCheck.Leg?) = when {
            l == null -> "계산 중…"
            l.estimated && l.km != null -> "약 ${km(l.km)}"
            else -> km(l.km)
        }
        fun legMin(l: CallCheck.Leg?) = when {
            l == null -> ""
            l.min == null -> "—"
            l.estimated -> "약 ${l.min}분"
            else -> "${l.min}분"
        }
        val price = com.jongin.callcalc.core.formatNumber(r.detail.price)
        val per = r.perMinute
        val perColor = when {
            per == null -> C.SUB
            store.goodAlertOn && per >= store.goodAlertMin -> C.GOOD
            store.threshold != null && per < store.threshold!! -> C.GRAY
            else -> C.FG
        }
        val total = r.totalMin
        val anyEstimate = r.pickup?.estimated == true || r.ride?.estimated == true
        val totalKm = listOfNotNull(r.pickup?.km, r.ride?.km).takeIf { it.size == 2 }?.sum()
        val footer = listOfNotNull(
            totalKm?.let { "총 거리 ${km(it)}" },
            r.detail.rideMin?.let { "콜 앱 안내 운행 약 ${Store.plain(it)}분" },
            if (anyEstimate) "\"약\"은 추정값" else null,
            "아무 데나 누르면 닫혀요",
        ).joinToString(" · ")
        overlay.showCheckCard(Overlay.CheckCard(
            pickupPlace = r.originName, pickupKm = legKm(r.pickup), pickupMin = legMin(r.pickup), pickupNote = r.pickup?.note,
            ridePlace = r.destName, rideKm = legKm(r.ride), rideMin = legMin(r.ride), rideNote = r.ride?.note,
            total = total?.let { "${it}분" } ?: "…",
            price = "${price}원",
            perMin = per?.let { "${com.jongin.callcalc.core.formatNumber(it)}원/분" } ?: if (r.done) "—" else "계산 중…",
            perFormula = if (total != null) "분당 금액 = $price ÷ ${total}분" else "분당 금액",
            perColor = perColor,
            footer = footer,
        ))
    }

    /** 표시가 나올 수 있는 범위: 읽은 영역(네모) 안, 없으면 화면 안 */
    private fun labelBounds(): Rect? = curPkg?.let { store.region(it) }

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
        private const val TOUCH_DELAY = 50L      // 화면을 만진 뒤 읽기까지 (0.15 → 0.05초)
        private const val CONFIRM_DELAY = 80L    // 확정하려고 한 번 더 읽기까지
        private const val TOUCH_FOLLOW_UP = 600L // 만진 뒤 한 번 더 확인
        @Volatile var instance: CallReaderService? = null
    }
}
