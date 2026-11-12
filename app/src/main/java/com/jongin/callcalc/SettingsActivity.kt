package com.jongin.callcalc

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jongin.callcalc.core.CalcResult
import com.jongin.callcalc.core.CallParser
import com.jongin.callcalc.core.Expr
import com.jongin.callcalc.core.ExprError
import com.jongin.callcalc.core.Formula
import com.jongin.callcalc.core.RegionColor
import com.jongin.callcalc.core.RegionWord
import com.jongin.callcalc.core.Sample
import com.jongin.callcalc.core.formatNumber

/** 설정 화면. 바꾸는 즉시 저장된다 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var formulaBox: LinearLayout
    private lateinit var wordBox: LinearLayout

    private lateinit var list: LinearLayout

    override fun onResume() {
        super.onResume()
        Updater.resumePending(this)
        if (::list.isInitialized) renderList() // 권한을 켜고 돌아오면 다시 그림
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val root = vbox(16)
        scrollPage(root)
        val header = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("설정", 28f, bold = true), lp(0, weight = 1f))
        header.addView(button("완료", outlined = true) { finish() })
        root.addView(header)
        root.addView(text("줄을 누르면 아래에서 그 설정 창이 올라와요. 바꾸면 바로 저장돼요.", 14f, C.DIM).margins(this, top = 4))
        list = vbox()
        root.addView(list, lp().also { it.topMargin = dp(8) })
        root.addView(text("콜 앱에 따라 약관으로 보조 프로그램 사용을 금지할 수 있으니, 쓰기 전에 확인하세요.", 13f, C.GRAY).margins(this, top = 20))
        renderList()
    }

    /** 주제 목록 (오른쪽에 지금 값) */
    private fun renderList() {
        list.removeAllViews()
        val missing = PermissionGuide.all().count { p -> runCatching { !p.granted(this) }.getOrDefault(false) }
        fun item(icon: String, title: String, value: String, warn: Boolean = false, open: () -> Unit) {
            val row = hbox().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = roundBg(C.PANEL, if (warn) C.WARN else C.LINE, 2, 14)
                setPadding(dp(14), dp(16), dp(12), dp(16))
                layoutParams = lp().also { it.topMargin = dp(8) }
                setOnClickListener { open() }
            }
            row.addView(text("$icon  $title", 18f, bold = true), lp(0, weight = 1f))
            row.addView(text("$value  ›", 15f, if (warn) C.WARN else C.SUB))
            list.addView(row)
        }
        val close: () -> Unit = { renderList(); live() }
        item("🚗", "톨비 계산", CallCheck.carName(store.tollCar)) { sheet("🚗 톨비 계산", close) { carSheet(it) } }
        item("🧮", "계산식 · 픽업", "${MainActivity.pretty(store.selectedFormula.expr)} · ${Store.plain(store.pickupMinPerKm)}분") { sheet("🧮 계산식 · 픽업", close) { formulaSheet(it) } }
        item("💰", "기준 단가 · 최소 금액", listOfNotNull(store.threshold?.let { formatNumber(it) + "원/분" },
            if (store.minPriceOn) store.minPrice?.let { "최소 " + formatNumber(it) } else null).joinToString(" · ").ifEmpty { "안 씀" }) {
            sheet("💰 기준 단가 · 최소 금액", close) { priceSheet(it) }
        }
        item("📍", "지역 단어", "${store.words.size}개") { sheet("📍 지역 단어", close) { wordSheet(it) } }
        item("📱", "실시간 표시", if (store.alwaysOn) "항상 표시" else AppPicker.summary(this, store).take(14)) { sheet("📱 실시간 표시 (콜 앱 위)", close) { liveSheet(it) } }
        item("🗺", "콜 확인 (TMAP)", "키 " + if (store.tmapKey.isBlank()) "❌" else "✅", warn = store.tmapKey.isBlank()) { sheet("🗺 콜 확인 (TMAP)", close) { tmapSheet(it) } }
        item("📊", "통계 시간", "${store.statsStartHour}시~${store.statsEndHour}시") { sheet("📊 통계 시간", close) { statsSheet(it) } }
        item("🔐", "권한", if (missing == 0) "전부 ✅" else "${missing}개 꺼짐", warn = missing > 0) { sheet("🔐 권한", close) { it.addView(PermissionGuide.statusRows(this)) } }
        item("⟳", "업데이트", "v" + Updater.versionText(this)) { sheet("⟳ 업데이트", close) { updateSheet(it) } }
        item("🧪", "시험하기 (사진)", "") { startActivity(android.content.Intent(this, TestActivity::class.java)) }
    }

    // ───────── 주제별 창 ─────────

    private fun carSheet(root: LinearLayout) {
        root.addView(text("혼자 할 땐 '없음'. 2인 1조면 뒤따라가는 내 차를 고르면 그 차 톨비를 빼고 분당 금액을 계산해요.", 15f, C.DIM))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for ((car, name) in CallCheck.CARS) {
            group.addView(com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
                id = 9000 + car; text = name; textSize = 19f; setTextColor(C.FG); setPadding(dp(4), dp(8), 0, dp(8))
            })
        }
        group.check(9000 + store.tollCar)
        group.setOnCheckedChangeListener { _, id -> store.tollCar = id - 9000; live() }
        root.addView(group.margins(this, top = 6))
    }

    private fun formulaSheet(root: LinearLayout) {
        root.addView(text("A = 픽업 거리(km), B = 운행 시간(분), C = 금액(원)\nP = 픽업 시간(분) = A × 1km당 분, 반올림\n줄을 누르면 그 식을 써요.", 15f, C.DIM))
        formulaBox = vbox()
        root.addView(formulaBox, lp().also { it.topMargin = dp(6) })
        root.addView(dashedButton("+ 식 추가") { editFormula(null) })
        root.addView(text("기본값으로 되돌리기", 15f, C.ACCENT).apply {
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { confirmResetFormulas() }
        })
        renderFormulas()
        root.addView(text("픽업 1km당 몇 분", 18f, bold = true).margins(this, top = 16))
        root.addView(pickupSlider(store) {}.margins(this, top = 8))
    }

    private fun priceSheet(root: LinearLayout) {
        // 기준 단가: 켜고 끄기 + [-] [+]
        val tRow = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        tRow.addView(text("기준 단가", 19f, bold = true), lp(0, weight = 1f))
        val tStep = stepper("이보다 낮으면 회색으로 흐리게", "원/분", { store.threshold }, 50.0, 50.0, 5000.0) { store.threshold = it; live() }
        tRow.addView(MaterialSwitch(this).apply {
            isChecked = store.threshold != null
            setOnCheckedChangeListener { _, on ->
                store.threshold = if (on) (store.threshold ?: 300.0) else null
                tStep.alpha = if (on) 1f else 0.4f
                (tStep.getChildAt(2) as? android.widget.TextView)?.text = store.threshold?.let { formatNumber(it) + "원/분" } ?: "안 씀"
                live()
            }
        })
        tStep.alpha = if (store.threshold != null) 1f else 0.4f
        root.addView(tRow)
        root.addView(tStep)

        val mRow = hbox().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp().also { it.topMargin = dp(22) } }
        mRow.addView(text("최소 금액", 19f, bold = true), lp(0, weight = 1f))
        val mStep = stepper("이보다 싼 콜은 숨김", "원", { store.minPrice ?: 15000.0 }, 1000.0, 1000.0, 200000.0) { store.minPrice = it; live() }
        mRow.addView(MaterialSwitch(this).apply {
            isChecked = store.minPriceOn
            setOnCheckedChangeListener { _, on ->
                store.minPriceOn = on
                if (on && store.minPrice == null) store.minPrice = 15000.0
                mStep.alpha = if (on) 1f else 0.4f
                live()
            }
        })
        mStep.alpha = if (store.minPriceOn) 1f else 0.4f
        root.addView(mRow)
        root.addView(mStep)
    }

    private fun wordSheet(root: LinearLayout) {
        root.addView(text("주소에 이 단어가 들어 있으면 색으로 표시해요.\n\"안산\" → 안산시 단원구, 상록구 모두 걸려요.", 15f, C.DIM))
        wordBox = vbox()
        root.addView(wordBox, lp().also { it.topMargin = dp(6) })
        root.addView(dashedButton("+ 단어 추가") { editWord(-1) })
        renderWords()
        root.addView(text("검사할 주소", 18f, bold = true).margins(this, top = 18))
        root.addView(radios(listOf("도착지만", "출발지도 포함"), if (store.checkOrigin) 1 else 0) { store.checkOrigin = it == 1 })
        root.addView(text("⛔ 빨강 지역 콜은", 18f, bold = true).margins(this, top = 14))
        root.addView(radios(listOf("계산값을 보여 주고 경고", "계산값 숨기기"), if (store.hideRedValue) 1 else 0) { store.hideRedValue = it == 1 })
        root.addView(text("어느 쪽이든 '제일 좋음'에서 빠져요.", 15f, C.DIM))
    }

    private fun liveSheet(root: LinearLayout) {
        fun switchRow(label: String, on: Boolean, set: (Boolean) -> Unit) {
            val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL; margins(this@SettingsActivity, top = 12) }
            row.addView(text(label, 17f), lp(0, weight = 1f))
            row.addView(MaterialSwitch(this).apply { isChecked = on; setOnCheckedChangeListener { _, v -> set(v); live() } })
            root.addView(row)
        }
        // 감시할 앱
        val appRow = hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(C.PANEL, C.LINE, 2, 12)
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }
        val appText = text("", 17f)
        fun refreshApps() { appText.text = "감시할 앱\n" + AppPicker.summary(this, store) }
        refreshApps()
        appRow.addView(appText, lp(0, weight = 1f))
        appRow.addView(button("고르기", outlined = true) { AppPicker.show(this, store) { refreshApps(); live() } })
        root.addView(appRow)
        switchRow("항상 표시 (모든 앱에서, 테스트용)", store.alwaysOn) { store.alwaysOn = it }
        root.addView(text("켜면 감시할 앱과 상관없이 어느 화면에서나 읽어요. 갤러리 스크린샷으로 시험할 때만 쓰세요.", 14f, C.DIM))

        // 화면 캡처
        val capOn = CaptureService.instance != null
        root.addView(button(if (capOn) "화면 캡처 중지" else "화면 캡처 시작", outlined = true) {
            if (CaptureService.instance != null) CaptureService.stop(this)
            else startActivity(android.content.Intent(this, CaptureStartActivity::class.java))
        }.margins(this, top = 12))
        root.addView(text("접근성으로 글자가 안 읽히는 콜 앱에서만 필요해요. 지금: " + if (capOn) "켜짐" else "꺼짐", 14f, C.DIM))

        root.addView(text("화면에 띄울 버튼", 18f, bold = true).margins(this, top = 16))
        switchRow("콜 ON 버튼", store.showCallOn) { store.showCallOn = it }
        switchRow("콜 확인 버튼", store.showCheckOn) { store.showCheckOn = it }
        switchRow("수락 창이 뜨면 콜 확인 창 자동으로", store.autoCheck) { store.autoCheck = it }
        root.addView(text("[콜 ON] 사용법: 한 번 톡 = 표시 켜기/끄기 · 끌기 = 옮기기 · 두 번 톡(또는 길게) = 메뉴(읽을 영역, 기억 지우기) · 맨 위 쓰레기통에 버리기 = 정지", 14f, C.DIM).margins(this, top = 6))
        root.addView(button("버튼 위치 처음으로", outlined = true) {
            store.resetBubble()
            CallReaderService.instance?.resetBubble()
            android.widget.Toast.makeText(this, "버튼을 처음 위치로 옮겼어요", android.widget.Toast.LENGTH_SHORT).show()
        }.margins(this, top = 8))

        root.addView(text("새로고침 방식", 18f, bold = true).margins(this, top = 16))
        root.addView(radios(listOf("터치할 때만 (화면을 만지면 다시 읽기)", "자동 + 터치 (화면이 바뀌면 저절로도 읽기)"), if (store.refreshAuto) 1 else 0) {
            store.refreshAuto = it == 1; live()
        })
        root.addView(text("읽는 방식", 18f, bold = true).margins(this, top = 14))
        root.addView(radios(listOf("자동 (접근성 먼저, 안 되면 화면 캡처)", "접근성만", "화면 캡처(OCR)만"), store.readMode) {
            store.readMode = it; live()
        })
        root.addView(text("'제일 좋음' 범위", 18f, bold = true).margins(this, top = 14))
        root.addView(radios(listOf("지금 보이는 콜 중 최고", "스크롤로 지나온 콜 전체 중 최고"), if (store.bestAllSeen) 1 else 0) {
            store.bestAllSeen = it == 1; live()
        })

        root.addView(text("표시 방식", 18f, bold = true).margins(this, top = 14))
        lateinit var alphaLabel: android.widget.TextView
        lateinit var alphaBar: android.widget.SeekBar
        root.addView(radios(listOf("터치 통과 (표시 위를 눌러도 콜이 눌려요)", "진하게 (누를 땐 둥근 버튼으로 숨기기)"), if (store.overlaySolid) 1 else 0) {
            store.overlaySolid = it == 1
            if (!store.overlaySolid && store.overlayAlpha > 80) store.overlayAlpha = 80
            alphaBar.progress = store.overlayAlpha - 20
            live()
        })
        alphaLabel = text("", 17f).margins(this, top = 10) as android.widget.TextView
        root.addView(alphaLabel)
        alphaBar = slider(20, 100, store.overlayAlpha) { v ->
            val fixed = minOf(v, if (store.overlaySolid) 100 else 80)
            store.overlayAlpha = fixed
            alphaLabel.text = "불투명도 $fixed%" + if (!store.overlaySolid) " (터치 통과는 최대 80%)" else ""
            live()
        }
        root.addView(alphaBar)
        alphaLabel.text = "불투명도 ${store.overlayAlpha}%" + if (!store.overlaySolid) " (터치 통과는 최대 80%)" else ""
        val sizeLabel = text("표시 글자 크기 ${store.overlayTextSp}", 17f).margins(this, top = 10) as android.widget.TextView
        root.addView(sizeLabel)
        root.addView(slider(12, 30, store.overlayTextSp) { v -> store.overlayTextSp = v; sizeLabel.text = "표시 글자 크기 $v"; live() })

        root.addView(text("화면 꺼짐", 18f, bold = true).margins(this, top = 14))
        switchRow("콜 앱이 떠 있는 동안 화면 꺼짐 방지", store.keepScreenOn) { store.keepScreenOn = it }
        switchRow("화면을 다시 켜면 캡처 시작 창 자동으로", store.autoAskCapture) { store.autoAskCapture = it }

        root.addView(text("읽을 영역 (네모)", 18f, bold = true).margins(this, top = 14))
        root.addView(text("콜 앱에서 [콜 ON] 두 번 톡 → [읽을 영역 정하기]. 지금 정해 둔 앱: ${store.regionCount()}개", 14f, C.DIM))
        root.addView(button("읽을 영역 모두 지우기", outlined = true) {
            store.clearAllRegions(); live()
            android.widget.Toast.makeText(this, "읽을 영역을 모두 지웠어요", android.widget.Toast.LENGTH_SHORT).show()
        }.margins(this, top = 6))
        switchRow("속도 표시 (⏱ 걸린 시간)", store.showSpeed) { store.showSpeed = it }
    }

    private fun tmapSheet(root: LinearLayout) {
        root.addView(text("수락 창에서 내 위치 → 출발지 → 도착지 실제 도로 거리·시간과 톨비를 TMAP으로 계산해요.", 15f, C.DIM))
        val keyState = text("", 17f, bold = true).margins(this, top = 10) as android.widget.TextView
        fun showKeyState() { keyState.text = "TMAP 앱 키 " + if (store.tmapKey.isBlank()) "❌ 없음" else "✅ 들어 있음" }
        showKeyState()
        root.addView(keyState)
        root.addView(editText("TMAP 앱 키 붙여 넣기 (SK오픈API → 앱 → 앱키)", store.tmapKey).apply {
            addTextChangedListener(afterChange { store.tmapKey = it; showKeyState() })
        })
        val used = CallCheck.todayUsage(this)
        root.addView(text("오늘 TMAP 사용: ${String.format(java.util.Locale.US, "%,d", used)}건 / 무료 10,000건", 16f, if (used > 8000) C.WARN else C.SUB).margins(this, top = 10))
        root.addView(text("이 폰에서 센 수예요. 공식 숫자는 SK오픈API → 앱(계산기) → 통계.", 13f, C.DIM))
    }

    private fun statsSheet(root: LinearLayout) {
        root.addView(text("하루 = 시작 시각 ~ 끝 시각. 밤을 넘기면 같은 하루로 묶어요. 맨 위 통계 글씨와 날짜별 기록에 써요.", 15f, C.DIM))
        root.addView(stepper("시작", "시", { store.statsStartHour.toDouble() }, 1.0, 0.0, 23.0) { it?.let { v -> store.statsStartHour = v.toInt(); live() } })
        root.addView(stepper("끝", "시", { store.statsEndHour.toDouble() }, 1.0, 0.0, 23.0) { it?.let { v -> store.statsEndHour = v.toInt(); live() } })
        root.addView(button("오늘 통계 기록 지우기", outlined = true) {
            CallReaderService.instance?.clearStats() ?: StatsStore(this, store).clear(); live()
            android.widget.Toast.makeText(this, "통계 기록을 지웠어요", android.widget.Toast.LENGTH_SHORT).show()
        }.margins(this, top = 14))
    }

    private fun updateSheet(root: LinearLayout) {
        root.addView(text("지금 버전: " + Updater.versionText(this), 17f, bold = true))
        root.addView(button("⟳ 업데이트 확인", big = true) { Updater.check(this, quiet = false) }.margins(this, top = 8))
        root.addView(text("브라우저로 받기", 15f, C.ACCENT).apply {
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { Updater.openInBrowser(this@SettingsActivity) }
        })
        val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL; margins(this@SettingsActivity, top = 12) }
        row.addView(text("켤 때 새 버전 자동 확인", 17f), lp(0, weight = 1f))
        row.addView(MaterialSwitch(this).apply { isChecked = store.autoUpdate; setOnCheckedChangeListener { _, on -> store.autoUpdate = on } })
        root.addView(row)
    }

    // ───────── 계산식 ─────────

    private fun renderFormulas() {
        formulaBox.removeAllViews()
        val selected = store.selectedFormula.id
        for (f in store.formulas) {
            val on = f.id == selected
            val row = hbox().apply {
                background = roundBg(C.PANEL, if (on) C.BTN else C.LINE, 2, 12)
                setPadding(dp(14), dp(12), dp(10), dp(12))
                layoutParams = lp().also { it.setMargins(0, dp(4), 0, dp(4)) }
                setOnClickListener { store.selectedId = f.id; renderFormulas() }
            }
            row.addView(text(if (on) "●" else "○", 20f, if (on) C.BTN else C.GRAY).apply { setPadding(0, 0, dp(12), 0) })
            val col = vbox()
            col.addView(text(f.name, 18f, bold = on))
            col.addView(text(MainActivity.pretty(f.expr), 16f, C.DIM))
            row.addView(col, lp(0, weight = 1f))
            row.addView(button("수정", outlined = true) { editFormula(f) })
            formulaBox.addView(row)
        }
    }

    private fun deleteFormula(f: Formula) {
        val list = store.formulas
        if (list.size <= 1) {
            toastDialog("식은 하나 이상 있어야 해요.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("식 삭제")
            .setMessage("'${f.name}'을(를) 지울까요?")
            .setPositiveButton("삭제") { _, _ ->
                val rest = list.filter { it.id != f.id }
                store.formulas = rest
                if (store.selectedId == f.id) store.selectedId = rest.first().id
                renderFormulas()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmResetFormulas() {
        MaterialAlertDialogBuilder(this)
            .setTitle("기본값으로 되돌리기")
            .setMessage("직접 만든 식은 지워지고 기본 식 3개로 돌아가요.")
            .setPositiveButton("되돌리기") { _, _ -> store.resetFormulas(); renderFormulas() }
            .setNegativeButton("취소", null)
            .show()
    }

    /** f 가 null 이면 새 식 추가 */
    private fun editFormula(f: Formula?) {
        val box = vbox(20)
        val name = editText("이름 (예: 시간당 금액)", f?.name ?: "")
        val expr = editText("식 (예: C / (A + B))", f?.expr?.let { MainActivity.pretty(it) } ?: "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val check = text("", 14f, C.SUB).apply { margins(this@SettingsActivity, top = 6) }
        box.addView(name)
        box.addView(expr.margins(this, top = 8))

        // 자주 쓰는 글자 넣기 버튼
        val keys = listOf("A", "B", "C", "P", "+", "−", "×", "÷", "(", ")")
        val keyRow = hbox().apply { margins(this@SettingsActivity, top = 6) }
        for (k in keys) {
            keyRow.addView(text(k, 18f, C.ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                background = roundBg(C.PANEL, radiusDp = 8)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    val ins = if (k == "−") "-" else k
                    val st = expr.selectionStart.coerceAtLeast(0)
                    val en = expr.selectionEnd.coerceAtLeast(0)
                    expr.text.replace(minOf(st, en), maxOf(st, en), ins)
                }
            }, lp(0, weight = 1f).also { it.setMargins(dp(2), 0, dp(2), 0) })
        }
        box.addView(keyRow)
        box.addView(check)

        // 입력할 때마다 검사하고, 예시 1번 콜로 미리 계산
        val sample = CallParser.parse(Sample.screen()).first()
        fun validate(): Boolean {
            val src = expr.text.toString()
            return try {
                val e = Expr.parse(src)
                val res = e.eval(com.jongin.callcalc.core.vars(sample.a!!, sample.b!!, sample.c!!, store.pickupMinPerKm))
                check.setTextColor(C.GOOD)
                check.text = "✓ 올바른 식이에요. 예시 1번 콜(4.1km, 72분, 24,000원) → " +
                    (if (res is CalcResult.Value) formatNumber(res.v) else "계산 불가")
                true
            } catch (ex: ExprError) {
                check.setTextColor(C.BAD)
                check.text = "✗ " + ex.message
                false
            }
        }
        expr.addTextChangedListener(afterChange { validate() })
        if (f != null) validate()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (f == null) "식 추가" else "식 수정")
            .setView(box)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .apply { if (f != null) setNeutralButton("삭제") { _, _ -> deleteFormula(f) } }
            .show()
        // 잘못된 식이면 창을 닫지 않는다
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!validate()) return@setOnClickListener
            val src = Expr.normalize(expr.text.toString()).trim()
            val n = name.text.toString().trim().ifEmpty { MainActivity.pretty(src) }
            val list = store.formulas.toMutableList()
            if (f == null) {
                val nf = Formula("f" + System.currentTimeMillis(), n, src)
                list += nf
                store.formulas = list
                store.selectedId = nf.id
            } else {
                store.formulas = list.map { if (it.id == f.id) it.copy(name = n, expr = src) else it }
            }
            renderFormulas()
            dialog.dismiss()
        }
    }

    // ───────── 지역 단어 ─────────

    private fun renderWords() {
        wordBox.removeAllViews()
        val words = store.words
        if (words.isEmpty()) {
            wordBox.addView(text("등록한 단어가 없어요.", 16f, C.GRAY).margins(this, top = 4, bottom = 4))
        }
        words.forEachIndexed { i, w ->
            val row = hbox().apply {
                background = roundBg(C.regionBg(w.color), C.regionLine(w.color), 2, 12)
                setPadding(dp(14), dp(12), dp(10), dp(12))
                layoutParams = lp().also { it.setMargins(0, dp(4), 0, dp(4)) }
                setOnClickListener { editWord(i) }
            }
            val col = vbox()
            col.addView(text("${C.regionIcon(w.color)} ${w.word}", 19f, C.region(w.color), bold = true))
            if (w.memo.isNotBlank()) col.addView(text(w.memo, 16f, C.SUB))
            row.addView(col, lp(0, weight = 1f))
            row.addView(button("수정", outlined = true) { editWord(i) })
            wordBox.addView(row)
        }
    }

    private fun deleteWord(i: Int) {
        val list = store.words
        val w = list.getOrNull(i) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("단어 삭제")
            .setMessage("'${w.word}'을(를) 지울까요?")
            .setPositiveButton("삭제") { _, _ ->
                store.words = list.filterIndexed { j, _ -> j != i }
                renderWords()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** i 가 -1 이면 새 단어 추가 */
    private fun editWord(i: Int) {
        val old = store.words.getOrNull(i)
        val box = vbox(20)
        val word = editText("단어 (예: 안산, 부천, 심곡동)", old?.word ?: "")
        box.addView(word)
        val colors = RegionColor.values().toList()
        var chosen = colors.indexOf(old?.color ?: RegionColor.RED)
        box.addView(radios(colors.map { it.label }, chosen) { chosen = it }.apply {
            for (k in 0 until childCount) (getChildAt(k) as RadioButton).setTextColor(C.region(colors[k]))
        })
        val memo = editText("메모 (예: 금액 좋아도 가지 말 것)", old?.memo ?: "")
        box.addView(memo.margins(this, top = 8))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (old == null) "단어 추가" else "단어 수정")
            .setView(box)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .apply { if (old != null) setNeutralButton("삭제") { _, _ -> deleteWord(i) } }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val w = word.text.toString().trim()
            if (w.isEmpty()) {
                word.error = "단어를 입력하세요"
                return@setOnClickListener
            }
            val nw = RegionWord(w, colors[chosen], memo.text.toString().trim())
            val list = store.words.toMutableList()
            if (old == null) list += nw else list[i] = nw
            store.words = list
            renderWords()
            dialog.dismiss()
        }
    }

    // ───────── 공용 ─────────

    private fun editText(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(C.FG)
        setHintTextColor(C.GRAY)
        textSize = 20f
        isSingleLine = true
    }

    /** 숫자 입력칸. 비우면 null 로 저장 */
    private fun numberField(hint: String, value: Double?, save: (Double?) -> Unit) =
        editText(hint, value?.let { Store.plain(it) } ?: "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(afterChange { save(it.replace(",", "").toDoubleOrNull()) })
        }

    private fun radios(labels: List<String>, checked: Int, onPick: (Int) -> Unit) = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        labels.forEachIndexed { i, l ->
            addView(RadioButton(this@SettingsActivity).apply {
                id = View.generateViewId()
                text = l
                textSize = 18f
                setTextColor(C.FG)
                setPadding(dp(4), dp(6), 0, dp(6))
                isChecked = i == checked
            })
        }
        setOnCheckedChangeListener { g, id ->
            val idx = (0 until g.childCount).indexOfFirst { g.getChildAt(it).id == id }
            if (idx >= 0) onPick(idx)
        }
    }

    /** 실시간 표시에 바로 반영 */
    private fun live() = CallReaderService.instance?.settingsChanged()

    override fun onPause() {
        super.onPause()
        live() // 기준 단가, 지역 단어 등도 반영
    }

    private fun slider(min: Int, max: Int, value: Int, onChange: (Int) -> Unit) = android.widget.SeekBar(this).apply {
        this.max = max - min
        progress = value - min
        setPadding(dp(8), dp(12), dp(8), dp(12))
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p + min) }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
    }

    private fun hourField(value: Int, save: (Int) -> Unit) = editText("", value.toString()).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        gravity = Gravity.CENTER
        addTextChangedListener(afterChange { t -> t.toIntOrNull()?.takeIf { it in 0..23 }?.let(save) })
    }

    private fun afterChange(f: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = f(s?.toString() ?: "")
    }

    private fun toastDialog(msg: String) {
        MaterialAlertDialogBuilder(this).setMessage(msg).setPositiveButton("확인", null).show()
    }
}
