package com.jongin.callcalc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

/** 2인 1조 · 로그인 · 팀 · 기록 */
class TeamActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = vbox(16)
        scrollPage(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
        if (Cloud.signedIn) runCatching {
            Cloud.sync(this, PermissionGuide.snapshot(this), Store(this).mode, BuildConfig.VERSION_NAME) { if (!isFinishing) render() }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun card() = vbox().apply {
        background = roundBg(C.PANEL, C.LINE, 2, 14)
        setPadding(dp(14), dp(12), dp(14), dp(14))
        layoutParams = lp().also { it.topMargin = dp(14) }
    }

    private fun render() {
        root.removeAllViews()
        root.addView(text("👥 2인 1조 · 기록", 26f, bold = true))
        root.addView(text("수락한 콜만 올라가요. 수락 창을 보기만 하거나 계산만 한 콜은 안 올라가요.", 14f, C.DIM).margins(this, top = 4))

        // ── 로그인 ──
        val login = card()
        if (!Cloud.signedIn) {
            login.addView(text("구글 로그인", 19f, bold = true))
            login.addView(text("팀 맺기와 기록 올리기에 필요해요.", 14f, C.DIM))
            login.addView(button("구글로 로그인", big = true) {
                Cloud.signIn(this) { err ->
                    if (err != null) toast(err) else {
                        toast("${Cloud.displayName}님 로그인 완료"); render()
                        Cloud.sync(this, PermissionGuide.snapshot(this), Store(this).mode, BuildConfig.VERSION_NAME) { render() }
                    }
                }
            }.margins(this, top = 10))
            root.addView(login)
            return
        }
        login.addView(text("✅ ${Cloud.displayName} 로그인됨", 17f, C.GOOD, bold = true))
        login.addView(text("로그아웃", 15f, C.ACCENT).apply {
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { Cloud.signOut(this@TeamActivity); render() }
        })
        if (Cloud.rulesOld(this)) {
            login.addView(text("⚠ 서버 규칙이 예전 것이에요. Firebase → Firestore Database → 규칙에 새 규칙을 붙여 넣고 [게시]해 주세요. (관리자 등록·기록이 안 돼요)", 14f, C.WARN, bold = true).margins(this, top = 8))
        }
        if (Cloud.adminMissing(this)) {
            login.addView(button("🛡 관리자로 등록 (처음 한 번, 사장님만)", outlined = true) {
                Cloud.claimAdmin(this) { err -> toast(err ?: "관리자로 등록했어요. 첫 화면 위에 [🛡 관리자]가 생겨요"); render() }
            }.margins(this, top = 10))
        }
        root.addView(login)
        if (Cloud.banned(this)) { root.addView(text("⛔ 사용이 막혀 있어요. 관리자에게 문의하세요.", 17f, C.BAD, bold = true).margins(this, top = 14)); return }

        // ── 팀 ──
        val team = card()
        team.addView(text("팀 (A가 수락하면 B에게 알림)", 19f, bold = true))
        val code = Cloud.teamCode(this)
        if (!Cloud.allowed(this, "team")) {
            team.addView(text("관리자가 팀 기능을 막아 두었어요.", 15f, C.WARN).margins(this, top = 4))
        } else if (code == null) {
            team.addView(text("한 사람이 [팀 만들기]로 코드를 만들고, 다른 사람이 그 코드를 넣으면 짝이 돼요.", 14f, C.DIM).margins(this, top = 4))
            team.addView(button("팀 만들기 (코드 받기)") {
                Cloud.createTeam(this) { c, err -> if (err != null) toast(err) else { toast("팀을 만들었어요: $c"); render() } }
            }.margins(this, top = 10))
            val input = EditText(this).apply {
                hint = "받은 코드 6글자"
                textSize = 20f
                setTextColor(C.FG); setHintTextColor(C.GRAY)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(InputFilter.LengthFilter(8), InputFilter.AllCaps())
            }
            team.addView(input.margins(this, top = 12))
            team.addView(button("코드로 들어가기", outlined = true) {
                Cloud.joinTeam(this, input.text.toString()) { err -> if (err != null) toast(err) else { toast("팀에 들어갔어요"); render() } }
            }.margins(this, top = 6))
        } else {
            team.addView(text("팀 코드", 14f, C.DIM).margins(this, top = 6))
            team.addView(text(code, 34f, C.WARN, bold = true))
            team.addView(button("코드 복사", outlined = true) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("팀 코드", code))
                toast("복사했어요. 같이 하는 분에게 보내 주세요")
            }.margins(this, top = 6))
            val names = text("팀원 불러오는 중…", 15f, C.SUB).margins(this, top = 8)
            team.addView(names)
            Cloud.teamNames(this) { list ->
                (names as android.widget.TextView).text = when {
                    list == null -> "팀원을 못 불러왔어요 (서버 규칙·인터넷 확인)"
                    list.size < 2 -> "팀원: ${list.joinToString(", ")} (아직 짝이 없어요. 코드를 보내 주세요)"
                    else -> "팀원: ${list.joinToString(", ")}"
                }
            }
            team.addView(switch("내가 수락하면 팀에 보내기 (화면 사진 + 주소)", Cloud.shareOn(this)) { Cloud.setShareOn(this, it) })
            team.addView(button("🧪 시험으로 보내기") { testSend() }.margins(this, top = 8))
            team.addView(text("가장 최근에 읽은 수락 창으로 팀원에게 시험 알림을 보내요 (기록은 안 남아요).", 13f, C.DIM))
            team.addView(text("팀 나가기", 15f, C.BAD).apply {
                setPadding(0, dp(14), 0, 0)
                setOnClickListener { Cloud.leaveTeam(this@TeamActivity) { render() } }
            })
        }
        root.addView(team)

        if (!Share.notificationsAllowed(this)) {
            root.addView(text("⚠ 알림 권한이 꺼져 있어서 팀 알림이 안 보여요", 15f, C.WARN, bold = true).margins(this, top = 12))
            root.addView(button("알림 켜기", outlined = true) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 72)
            }.margins(this, top = 6))
        }

        // ── 기록 ──
        val rec = card()
        rec.addView(text("콜 기록", 19f, bold = true))
        rec.addView(switch("수락한 콜 기록 올리기", Cloud.recordOn(this)) { Cloud.setRecordOn(this, it) })
        rec.addView(text("올라가는 것: 시간, 금액, 분당 금액, 거리·시간, 톨비, 출발·도착 시·구", 13f, C.DIM))
        rec.addView(button("📊 날짜별 기록 보기") { startActivity(Intent(this, RecordsActivity::class.java)) }.margins(this, top = 8))
        root.addView(rec)
    }

    private fun switch(label: String, on: Boolean, set: (Boolean) -> Unit) = MaterialSwitch(this).apply {
        text = label
        textSize = 16f
        setTextColor(C.FG)
        isChecked = on
        setOnCheckedChangeListener { _, v -> set(v) }
        layoutParams = lp().also { it.topMargin = dp(8) }
    }

    private fun testSend() {
        val svc = CallReaderService.instance
        if (svc == null) { toast("접근성(콜산)이 켜져 있어야 해요"); return }
        svc.testSend { err -> if (err != null) toast(err) }
    }
}
