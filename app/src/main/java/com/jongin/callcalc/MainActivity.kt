package com.jongin.callcalc

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jongin.callcalc.core.formatNumber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 첫 화면.
 * 맨 위: [A 오더] [B 뒷방] 모드 → [▶ 시작 / ■ 정지]. 앱을 열어도 저절로 시작하지 않는다.
 * A 오더: 상태 한 줄, 좋은 콜, 계산식, 픽업 1km당. B 뒷방: 받은 콜 카드 + 오늘 목록.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var body: LinearLayout
    private var usageBar: TextView? = null
    private val refreshB: () -> Unit = { runOnUiThread { if (store.mode == "B") render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        buildFrame()
        showCrash()
        // 갤러리 공유로 들어오면 시험 화면으로
        forwardShare(intent)
        checkUpdateOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardShare(intent)
    }

    private fun forwardShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) startActivity(Intent(i).setClass(this, TestActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        BackroomService.listeners += refreshB
        // B 뒷방이 켜져 있어야 하는데 꺼져 있으면 (재부팅 등) 다시 켬
        if (store.running && store.mode == "B" && BackroomService.instance == null && canRunB()) runCatching { BackroomService.start(this) }
        render()
        runCatching { syncCloud() }
        runCatching { Updater.resumePending(this) }
        runCatching { checkPermissions() }
        loadUsage()
    }

    /** 지난번에 앱이 꺼졌으면 이유를 보여 줌 (사진 찍어 보내면 바로 고칠 수 있게) */
    private fun showCrash() {
        val f = App.crashFile(this)
        if (!f.exists()) return
        val t = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        if (t.isBlank()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠ 지난번에 앱이 꺼졌어요")
            .setMessage("이 화면을 찍어서 보내 주시면 고칠게요.\n\n" + t.take(1500))
            .setPositiveButton("확인", null)
            .setNeutralButton("복사") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("오류", t))
            }
            .show()
    }

    /** 맨 아래 줄: 모든 사람 TMAP 합계 (볼 수 있는 사람만). 서버는 열 때 한 번만 읽음 */
    private var usageMap: Map<String, Int>? = null
    private fun loadUsage() {
        if (!Cloud.canSeeUsage(this)) return
        runCatching { Cloud.loadUsage(this) { m -> if (m != null) { usageMap = m; renderUsage() } } }
    }

    override fun onPause() {
        super.onPause()
        BackroomService.listeners -= refreshB
    }

    /** 서버에 내 상태 올리고, 관리자가 정한 것 받기 */
    private fun syncCloud() {
        if (!Cloud.signedIn) return
        Cloud.sync(this, PermissionGuide.snapshot(this), store.mode, versionName()) { changed ->
            if (isFinishing) return@sync
            if (changed) CallReaderService.instance?.settingsChanged()
            render()
            loadUsage()
        }
    }

    // ───────── 틀: 위 내용(스크롤) + 맨 아래 TMAP 사용량 줄 ─────────

    private fun buildFrame() {
        body = vbox(16)
        val scroll = scrollPage(body)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        (scroll.parent as? android.view.ViewGroup)?.removeView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll, null)
        scroll.setPadding(0, 0, 0, 0)
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        usageBar = text("", 15f, C.SUB, bold = true).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(C.PANEL)
            setOnClickListener { showUsage() }
        }
        outer.addView(View(this).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        outer.addView(usageBar, lp())
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(outer)
    }

    private fun render() {
        try {
            renderInner()
        } catch (e: Exception) {
            // 그리다 문제가 생겨도 앱은 꺼지지 않게
            body.removeAllViews()
            body.addView(text("⚠ 화면을 그리다 문제가 생겼어요", 20f, C.WARN, bold = true))
            body.addView(text(android.util.Log.getStackTraceString(e).lines().take(12).joinToString("\n"), 12f, C.SUB).margins(this, top = 8))
            body.addView(button("설정 열기", outlined = true) { startActivity(Intent(this, SettingsActivity::class.java)) }.margins(this, top = 12))
            body.addView(button("앱 종료", outlined = true) { exitApp() }.margins(this, top = 8))
        }
    }

    private fun renderInner() {
        body.removeAllViews()
        renderUsage()

        // 머리: 계산기 · [🛡 관리자] · v0.8.0 ⟳
        val header = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("콜산", 28f, bold = true), lp(0, weight = 1f))
        if (Cloud.isAdmin(this)) {
            header.addView(text("🛡 관리자", 15f, C.WARN, bold = true).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundBg(C.PANEL, C.WARN, 2, 10)
                setOnClickListener { startActivity(Intent(this@MainActivity, AdminActivity::class.java)) }
            })
        }
        header.addView(text("v" + versionName() + " ⟳", 15f, C.ACCENT).apply {
            setPadding(dp(10), dp(8), 0, dp(8))
            setOnClickListener { Updater.check(this@MainActivity, quiet = false) }
        })
        body.addView(header)

        // 접근 금지
        if (Cloud.banned(this)) {
            body.addView(text("⛔ 사용이 막혀 있어요", 22f, C.BAD, bold = true).margins(this, top = 24))
            body.addView(text("관리자에게 문의하세요.", 17f, C.SUB).margins(this, top = 6))
            if (store.running) stopAll()
            return
        }

        // 모드 고르기
        val modes = hbox().apply { margins(this@MainActivity, top = 10) }
        fun modeBtn(m: String, label: String) = button(label, outlined = store.mode != m, big = true) {
            if (store.mode == m) return@button
            if (!Cloud.allowed(this, m)) { toast("관리자가 막아 둔 모드예요"); return@button }
            val wasRunning = store.running
            if (wasRunning) stopAll()
            store.mode = m
            if (wasRunning) startMode()
            render()
            checkPermissionsNow()
        }.apply { alpha = if (Cloud.allowed(this@MainActivity, m)) 1f else 0.4f }
        modes.addView(modeBtn("A", "A 오더"), lp(0, weight = 1f))
        modes.addView(modeBtn("B", "B 뒷방"), lp(0, weight = 1f).also { it.marginStart = dp(8) })
        body.addView(modes)

        // ▶ 시작 / ■ 정지
        val run = store.running
        body.addView(button(if (run) "■ 정지" else "▶ 시작", big = true) {
            if (store.running) { stopAll(); toast("멈췄어요") } else startMode()
            render()
        }.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (run) android.graphics.Color.parseColor("#3A2A2A") else android.graphics.Color.parseColor("#2F9E58"))
            minHeight = dp(68)
            textSize = 22f
        }.margins(this, top = 10))

        if (store.mode == "B") renderB() else renderA()

        // 2인 1조 · 설정
        val row = hbox().apply { margins(this@MainActivity, top = 16) }
        row.addView(button("👥 2인 1조 · 기록", outlined = true) { startActivity(Intent(this, TeamActivity::class.java)) }, lp(0, weight = 1f))
        row.addView(button("⚙ 설정", outlined = true) { startActivity(Intent(this, SettingsActivity::class.java)) },
            lp(dp(110)).also { it.marginStart = dp(8) })
        body.addView(row)

        // 앱 종료
        body.addView(button("앱 종료", outlined = true) { exitApp() }.apply {
            setTextColor(C.BAD)
            strokeColor = android.content.res.ColorStateList.valueOf(C.BAD)
        }.margins(this, top = 10))
        body.addView(text("이 앱은 읽고 계산해서 보여 주기만 해요. 콜 수락은 직접 누르세요.", 13f, C.GRAY).margins(this, top = 18))
    }

    // ───────── A 오더 ─────────

    private fun renderA() {
        // 상태 한 줄 (문제 있을 때만 경고 줄)
        val acc = PermissionGuide.isAccessibilityOn(this)
        val stats = StatsStore(this, store).summary()
        val parts = mutableListOf(
            if (acc) "✅ 접근성" else "❌ 접근성",
            "📊 " + (stats ?: "오늘 0건"),
            "읽기 " + listOf("자동", "접근성만", "캡처만")[store.readMode.coerceIn(0, 2)],
        )
        body.addView(text(parts.joinToString(" · "), 15f, C.SUB).margins(this, top = 12))
        fun warn(msg: String, label: String, act: () -> Unit) {
            val r = hbox().apply {
                background = roundBg(android.graphics.Color.parseColor("#2A1F0A"), C.WARN, 2, 12)
                setPadding(dp(12), dp(8), dp(8), dp(8))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp().also { it.topMargin = dp(6) }
            }
            r.addView(text(msg, 15f, C.WARN, bold = true), lp(0, weight = 1f))
            r.addView(button(label, outlined = true) { act() })
            body.addView(r)
        }
        if (!acc) warn("⚠ 접근성이 꺼져 있어요", "켜기") { checkPermissionsNow() }
        else if (store.targetPackages.isEmpty() && !store.alwaysOn) warn("⚠ 감시할 콜 앱을 안 골랐어요", "고르기") { AppPicker.show(this, store) { render() } }
        if (store.readMode != 1 && CaptureService.instance == null && CaptureService.stoppedBySystem) {
            warn("⚠ 화면 캡처가 꺼졌어요 (화면 꺼짐)", "켜기") { startActivity(Intent(this, CaptureStartActivity::class.java)) }
        }
        if (store.alwaysOn) body.addView(text("항상 표시(테스트용)가 켜져 있어요 · 설정 → 실시간 표시", 13f, C.WARN).margins(this, top = 4))

        // 🔔 좋은 콜 한 줄: [-] 1,000원/분 [+]  (스위치)
        val good = hbox().apply {
            background = roundBg(C.PANEL, if (store.goodAlertOn) C.GOOD else C.LINE, 2, 14)
            setPadding(dp(12), dp(6), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp().also { it.topMargin = dp(12) }
        }
        good.addView(text("🔔 좋은 콜", 16f, bold = true))
        good.addView(View(this), lp(0, weight = 1f))
        val value = text("", 18f, C.GOOD, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), 0) }
        fun show() { value.text = "${formatNumber(store.goodAlertMin)}원/분" }
        show()
        fun step(d: Int) {
            store.goodAlertMin = (store.goodAlertMin + d).coerceIn(100.0, 5000.0)
            show(); CallReaderService.instance?.settingsChanged()
        }
        good.addView(stepBtn("−") { step(-100) })
        good.addView(value.apply { setOnClickListener { askNumber("좋은 콜 기준 (원/분)", store.goodAlertMin) { store.goodAlertMin = it; show(); CallReaderService.instance?.settingsChanged() } } })
        good.addView(stepBtn("+") { step(100) })
        good.addView(MaterialSwitch(this).apply {
            isChecked = store.goodAlertOn
            setOnCheckedChangeListener { _, on -> store.goodAlertOn = on; CallReaderService.instance?.settingsChanged(); render() }
        })
        body.addView(good)

        // 계산식
        val f = store.selectedFormula
        body.addView(text(android.text.SpannableStringBuilder().apply {
            append("계산식  ")
            val st = length
            append(pretty(f.expr))
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(C.FG), st, length, 0)
            append("   바꾸기 ▾")
        }, 17f, C.ACCENT).apply {
            background = roundBg(C.PANEL)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { chooseFormula() }
        }.margins(this, top = 10))

        // 픽업 1km당 (슬라이더만)
        body.addView(pickupSlider(store) {}.margins(this, top = 14))
    }

    private fun stepBtn(s: String, act: () -> Unit) = text(s, 22f, C.ACCENT, bold = true).apply {
        gravity = Gravity.CENTER
        background = roundBg(android.graphics.Color.parseColor("#1F252E"), C.BTN, 2, 10)
        setOnClickListener { act() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }

    private fun askNumber(title: String, cur: Double, save: (Double) -> Unit) {
        val e = android.widget.EditText(this).apply {
            setText(Store.plain(cur)); textSize = 22f; setTextColor(C.FG)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(vbox(20).apply { addView(e) })
            .setPositiveButton("저장") { _, _ -> e.text.toString().replace(",", "").toDoubleOrNull()?.let(save) }
            .setNegativeButton("취소", null).show()
    }

    // ───────── B 뒷방 ─────────

    private fun canRunB() = Cloud.signedIn && Cloud.teamCode(this) != null

    private fun renderB() {
        val waiting = BackroomService.instance != null
        body.addView(text(
            when {
                !Cloud.signedIn -> "❌ 로그인이 필요해요 · [👥 2인 1조 · 기록]에서 로그인"
                Cloud.teamCode(this) == null -> "❌ 팀이 없어요 · [👥 2인 1조 · 기록]에서 코드 넣기"
                waiting -> "✅ 대기 중 · 팀원이 수락하면 화면이 켜지고 소리가 나요"
                else -> "▶ 시작을 누르면 대기해요"
            }, 15f, if (waiting) C.GOOD else C.SUB, bold = true).margins(this, top = 12))
        if (!Share.notificationsAllowed(this)) {
            body.addView(button("⚠ 알림 켜기 (꼭 필요)", outlined = true) { checkPermissionsNow() }.margins(this, top = 6))
        }

        val list = Share.received(this)
        val latest = list.firstOrNull()
        if (latest == null) {
            body.addView(text("아직 받은 콜이 없어요.", 16f, C.DIM).margins(this, top = 16))
            return
        }
        // 방금 받은 콜 (크게)
        val card = vbox().apply {
            background = roundBg(C.PANEL, C.GOOD, 2, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp().also { it.topMargin = dp(12) }
            setOnClickListener { startActivity(Share.openIntent(this@MainActivity, latest)) }
        }
        val ago = (System.currentTimeMillis() - latest.optLong("at")) / 60_000
        card.addView(text("${if (latest.optBoolean("test")) "🧪 " else "🚗 "}${latest.optString("fromName", "팀원")} 콜 수락 · ${if (ago < 1) "방금" else "${ago}분 전"}", 15f, C.SUB))
        card.addView(text("도착", 13f, C.DIM).margins(this, top = 6))
        card.addView(text(latest.optString("destination"), 22f, C.FG, bold = true))
        latest.optString("warn").ifEmpty { null }?.let { card.addView(text(it, 14f, C.WARN, bold = true)) }
        card.addView(text("출발: " + latest.optString("origin"), 15f, C.SUB).margins(this, top = 4))
        card.addView(button("TMAP 길안내 (도착)", big = true) {
            runCatching { startActivity(Share.navFor(this, latest)) }.onFailure { toast("지도 앱을 못 열었어요") }
        }.margins(this, top = 10))
        card.addView(text("눌러서 사진·주소 크게 보기 ›", 14f, C.ACCENT).margins(this, top = 8))
        body.addView(card)

        // 오늘 받은 목록
        val today = list.drop(1).filter { System.currentTimeMillis() - it.optLong("at") < 18 * 3600_000L }
        if (today.isNotEmpty()) {
            body.addView(text("오늘 받은 콜", 17f, bold = true).margins(this, top = 16))
            val fmt = DateTimeFormatter.ofPattern("HH:mm")
            for (o in today) {
                val t = Instant.ofEpochMilli(o.optLong("at")).atZone(ZoneId.systemDefault()).format(fmt)
                body.addView(text("$t  ${o.optString("destination")}", 15f, C.FG).apply {
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnClickListener { startActivity(Share.openIntent(this@MainActivity, o)) }
                })
            }
        }
    }

    // ───────── 시작 · 정지 · 종료 ─────────

    private fun startMode() {
        if (!Cloud.allowed(this, store.mode)) { toast("관리자가 막아 둔 모드예요"); return }
        if (store.mode == "B") {
            if (!canRunB()) { toast("B 뒷방은 로그인하고 팀을 맺어야 해요"); startActivity(Intent(this, TeamActivity::class.java)); return }
            store.running = true
            CallReaderService.instance?.pause()
            runCatching { BackroomService.start(this) }.onFailure { toast("대기 시작 실패: ${it.message}") }
            toast("뒷방 대기를 시작했어요")
        } else {
            store.running = true
            BackroomService.stop(this)
            CallReaderService.instance?.resume()
            if (!PermissionGuide.isAccessibilityOn(this)) checkPermissionsNow() else toast("시작했어요. 콜 앱을 열어 주세요")
        }
    }

    private fun stopAll() {
        store.running = false
        CallReaderService.instance?.pause()
        BackroomService.stop(this)
    }

    private fun exitApp() {
        val accOn = CallReaderService.instance != null
        if (!accOn) { finishExit(false); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("접근성도 끌까요?")
            .setMessage("끄면 토스·은행 앱을 쓸 때 콜산이 화면을 읽을 수 없는 상태가 돼서 더 안전해요.\n\n대신 다음에 [▶ 시작]을 누르면 접근성 스위치를 다시 켜야 해요 (안내가 떠요).")
            .setPositiveButton("끄고 종료") { _, _ -> finishExit(true) }
            .setNegativeButton("그냥 종료") { _, _ -> finishExit(false) }
            .setNeutralButton("취소", null)
            .show()
    }

    private fun finishExit(accOff: Boolean) {
        stopAll()
        if (CaptureService.instance != null) CaptureService.stop(this)
        if (accOff) CallReaderService.instance?.turnOff()
        toast(if (accOff) "접근성까지 끄고 종료했어요" else "앱을 종료했어요. 다시 열면 [▶ 시작]을 눌러 주세요")
        finishAndRemoveTask()
    }

    // ───────── 권한 · 업데이트 ─────────

    private var guideDialog: androidx.appcompat.app.AlertDialog? = null

    private fun checkPermissions() {
        val missing = PermissionGuide.missing(this)
        if (missing.isEmpty()) {
            if (guideDialog?.isShowing == true) {
                guideDialog?.dismiss()
                toast("권한 설정이 끝났어요 ✅")
            }
            guideDialog = null
            if (store.mode == "A" && store.targetPackages.isEmpty() && !store.alwaysOn && !appAsked) {
                appAsked = true
                AppPicker.show(this, store, "어느 콜 앱 위에 띄울까요?") { render() }
                return
            }
            checkUpdateOnce()
            return
        }
        if (guideSnoozed) { checkUpdateOnce(); return }
        guideDialog?.dismiss()
        guideDialog = PermissionGuide.show(this, missing) {
            guideSnoozed = true
            guideDialog = null
            checkUpdateOnce()
        }
    }

    private fun checkPermissionsNow() {
        guideSnoozed = false
        checkPermissions()
    }

    private fun checkUpdateOnce() {
        if (updateChecked || !store.autoUpdate) return
        updateChecked = true
        Updater.check(this, quiet = true)
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** 계산식 빠르게 바꾸기 */
    private fun chooseFormula() {
        val list = store.formulas
        val names = list.map { "${it.name}   ${pretty(it.expr)}" }.toTypedArray()
        val cur = list.indexOfFirst { it.id == store.selectedFormula.id }
        MaterialAlertDialogBuilder(this)
            .setTitle("계산식 고르기")
            .setSingleChoiceItems(names, cur) { d, which ->
                store.selectedId = list[which].id
                d.dismiss()
                CallReaderService.instance?.settingsChanged()
                render()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ───────── 맨 아래 TMAP 사용량 ─────────

    private fun usageText(n: Int) = String.format(java.util.Locale.US, "%,d", n)

    private fun renderUsage() {
        val bar = usageBar ?: return
        if (!Cloud.canSeeUsage(this)) { bar.visibility = View.GONE; return }
        val n = usageMap?.values?.sum() ?: Cloud.cachedTotal(this).takeIf { it >= 0 } ?: CallCheck.todayUsage(this)
        bar.text = "📡 오늘 TMAP 합계 ${usageText(n)}건 / 10,000   (눌러서 사람별 ›)"
        bar.setTextColor(if (n > 8000) C.WARN else C.SUB)
        bar.visibility = View.VISIBLE
    }

    private fun showUsage() {
        renderUsage()
        val m = usageMap.orEmpty()
        val n = m.values.sum().takeIf { m.isNotEmpty() } ?: CallCheck.todayUsage(this)
        val left = (10_000 - n).coerceAtLeast(0)
        val people = if (m.isEmpty()) "아직 서버에서 못 읽었어요" else m.entries.sortedByDescending { it.value }.joinToString("\n") { "• ${it.key}: ${usageText(it.value)}건" }
        MaterialAlertDialogBuilder(this)
            .setTitle("오늘 TMAP 사용량 (모든 사람)")
            .setMessage(
                "합계: ${usageText(n)}건 · 남은 무료 ${usageText(left)}건 (하루 10,000건)\n\n" +
                    people + "\n\n" +
                    "• 각 폰이 1분에 한 번 모아서 올려서 조금 늦을 수 있어요.\n" +
                    "• 로그인한 사람만 들어가요.\n" +
                    "• 정확한 공식 숫자는 SK오픈API 사이트 → 앱(계산기) → 통계."
            )
            .setPositiveButton("닫기", null)
            .setNeutralButton("SK 사이트 열기") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openapi.sk.com/"))) }
            }
            .show()
    }

    companion object {
        private var guideSnoozed = false
        private var updateChecked = false
        private var appAsked = false

        /** 내부 표기 * / 를 화면 표기 × ÷ 로 */
        fun pretty(expr: String) = expr.replace("*", "×").replace("/", "÷")
    }
}
