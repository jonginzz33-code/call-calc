package com.jongin.callcalc

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** 🛡 관리자 (관리자 계정만 열 수 있음. 서버 규칙으로도 막혀 있음) */
class AdminActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var tab = 0 // 0 사람, 1 팀

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Cloud.isAdmin(this)) { finish(); return }
        root = vbox(16)
        scrollPage(root)
        load()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun ago(ms: Long): String {
        if (ms <= 0) return "기록 없음"
        val m = (System.currentTimeMillis() - ms) / 60_000
        return when { m < 1 -> "방금"; m < 60 -> "${m}분 전"; m < 1440 -> "${m / 60}시간 전"; else -> "${m / 1440}일 전" }
    }

    private fun top() {
        root.removeAllViews()
        root.addView(text("🛡 관리자", 26f, C.WARN, bold = true))
        val tabs = hbox().apply { margins(this@AdminActivity, top = 10) }
        tabs.addView(button("사람", outlined = tab != 0) { tab = 0; load() }, lp(0, weight = 1f))
        tabs.addView(button("팀", outlined = tab != 1) { tab = 1; load() }, lp(0, weight = 1f).also { it.marginStart = dp(8) })
        root.addView(tabs)
    }

    private fun load() {
        top()
        root.addView(text("불러오는 중…", 15f, C.SUB).margins(this, top = 10))
        if (tab == 0) Cloud.loadPeople { list, err -> top(); if (list == null) err(err) else people(list) }
        else Cloud.loadTeams { list, err -> top(); if (list == null) err(err) else teams(list) }
    }

    private fun err(e: String?) {
        root.addView(text(e ?: "못 불러왔어요", 16f, C.WARN).margins(this, top = 12))
        root.addView(text("서버 규칙을 v0.8.0 것으로 바꿨는지 확인해 주세요.", 14f, C.DIM))
    }

    // ───────── 사람 ─────────

    private fun people(list: List<Cloud.Person>) {
        root.addView(text("${list.size}명 · 누르면 기능 허락·접근 금지·기록", 14f, C.DIM).margins(this, top = 8))
        for (p in list) {
            val card = vbox().apply {
                background = roundBg(C.PANEL, if (p.banned) C.BAD else C.LINE, 2, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = lp().also { it.topMargin = dp(10) }
                setOnClickListener { person(p) }
            }
            val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(text((if (p.banned) "⛔ " else "") + p.name, 19f, if (p.banned) C.BAD else C.FG, bold = true), lp(0, weight = 1f))
            head.addView(text(if (p.data["mode"] == "B") "B 뒷방" else "A 오더", 14f, C.ACCENT, bold = true))
            card.addView(head)
            card.addView(text(p.email, 13f, C.DIM))
            card.addView(text("v${p.data["version"] ?: "?"} · 마지막 접속 ${ago(p.lastSeen)}", 14f, C.SUB).margins(this, top = 4))
            card.addView(text(p.perms.entries.joinToString("  ") { (k, v) -> (if (v == true) "✅" else "❌") + k }, 14f, C.SUB).margins(this, top = 2))
            val off = Cloud.FEATURES.filter { p.features[it.first] == false }.map { it.second }
            if (off.isNotEmpty()) card.addView(text("막은 기능: " + off.joinToString(", "), 14f, C.WARN).margins(this, top = 2))
            root.addView(card)
        }
    }

    private fun person(p: Cloud.Person) {
        sheet(p.name, onClose = { load() }) { box ->
            box.addView(text(p.email, 14f, C.DIM))
            box.addView(text("폰 권한 (그 사람이 직접 켜야 해요)", 17f, bold = true).margins(this, top = 14))
            box.addView(text(p.perms.entries.joinToString("\n") { (k, v) -> (if (v == true) "✅ " else "❌ ") + k }.ifEmpty { "아직 정보 없음" }, 16f, C.SUB))

            box.addView(text("쓸 수 있는 기능", 17f, bold = true).margins(this, top = 16))
            val f = p.features.toMutableMap()
            for ((k, label) in Cloud.FEATURES) {
                val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp().also { it.topMargin = dp(6) } }
                row.addView(text(label, 17f), lp(0, weight = 1f))
                row.addView(MaterialSwitch(this).apply {
                    isChecked = f[k] != false
                    setOnCheckedChangeListener { _, on ->
                        f[k] = on
                        Cloud.setPerson(p.uid, mapOf("features" to f)) { e -> if (e != null) toast(e) }
                    }
                })
                box.addView(row)
            }
            box.addView(text("바꾼 건 그 사람이 앱을 열 때 적용돼요.", 13f, C.DIM))

            box.addView(button("📊 이 사람 기록 보기", outlined = true) {
                startActivity(Intent(this, RecordsActivity::class.java).putExtra("uid", p.uid).putExtra("name", p.name))
            }.margins(this, top = 14))

            if (p.uid != Cloud.uid) {
                box.addView(button(if (p.banned) "접근 금지 풀기" else "⛔ 접근 금지", outlined = true) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (p.banned) "접근 금지를 풀까요?" else "${p.name}님을 접근 금지할까요?")
                        .setMessage(if (p.banned) "다시 앱을 쓸 수 있어요." else "그 사람이 앱을 열면 '사용이 막혀 있어요'만 보이고, 서버에 올리기도 막혀요.")
                        .setPositiveButton("네") { _, _ ->
                            Cloud.setPerson(p.uid, mapOf("banned" to !p.banned)) { e -> toast(e ?: "바꿨어요"); load() }
                        }
                        .setNegativeButton("취소", null).show()
                }.apply { setTextColor(C.BAD) }.margins(this, top = 8))
            }
        }
    }

    // ───────── 팀 ─────────

    private fun teams(list: List<Cloud.Team>) {
        root.addView(text("${list.size}개 팀", 14f, C.DIM).margins(this, top = 8))
        for (t in list) {
            val card = vbox().apply {
                background = roundBg(C.PANEL, C.LINE, 2, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = lp().also { it.topMargin = dp(10) }
            }
            card.addView(text("코드 ${t.code}", 19f, C.WARN, bold = true))
            for (m in t.members) {
                val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp().also { it.topMargin = dp(4) } }
                row.addView(text(t.names[m]?.toString() ?: m.take(6), 16f), lp(0, weight = 1f))
                row.addView(text("빼기", 15f, C.BAD).apply {
                    setPadding(dp(10), dp(6), dp(4), dp(6))
                    setOnClickListener { Cloud.removeMember(t.code, m) { load() } }
                })
                card.addView(row)
            }
            card.addView(text("팀 지우기", 15f, C.BAD).apply {
                setPadding(0, dp(10), 0, 0)
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@AdminActivity).setTitle("팀 ${t.code}을 지울까요?")
                        .setPositiveButton("지우기") { _, _ -> Cloud.deleteTeam(t.code) { load() } }
                        .setNegativeButton("취소", null).show()
                }
            })
            root.addView(card)
        }
    }
}
