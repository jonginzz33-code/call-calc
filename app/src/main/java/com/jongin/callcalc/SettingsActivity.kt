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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val root = vbox(16)
        scrollPage(root)

        val header = hbox()
        header.addView(text("설정", 24f, bold = true), lp(0, weight = 1f))
        header.addView(button("완료", outlined = true) { finish() })
        root.addView(header)

        // ── 계산식 ──
        root.addView(sectionTitle("계산식"))
        root.addView(text("A = 거리(km), B = 시간(분), C = 금액(원). 줄을 누르면 그 식을 써요.", 13f, C.SUB))
        formulaBox = vbox()
        root.addView(formulaBox, lp().also { it.topMargin = dp(6) })
        val fButtons = hbox()
        fButtons.addView(button("+ 식 추가") { editFormula(null) }, lp(0, weight = 1f))
        fButtons.addView(button("기본값으로 되돌리기", outlined = true) { confirmResetFormulas() }.margins(this, start = 8))
        root.addView(fButtons)
        renderFormulas()

        // ── 기준값 ──
        root.addView(sectionTitle("기준값"))
        root.addView(text("기준 단가: 계산값이 이보다 작으면 회색(기준 미달)으로 흐리게 보여요. 비워 두면 안 써요.", 13f, C.SUB))
        root.addView(numberField("예: 300", store.threshold) { store.threshold = it })

        val minRow = hbox().apply { margins(this@SettingsActivity, top = 16) }
        minRow.addView(text("최소 금액 (이보다 싼 콜은 숨김)", 15f), lp(0, weight = 1f))
        val minField = numberField("예: 15000", store.minPrice) { store.minPrice = it }
        minField.isEnabled = store.minPriceOn
        minRow.addView(MaterialSwitch(this).apply {
            isChecked = store.minPriceOn
            setOnCheckedChangeListener { _, on -> store.minPriceOn = on; minField.isEnabled = on }
        })
        root.addView(minRow)
        root.addView(minField)

        // ── 지역 단어 ──
        root.addView(sectionTitle("지역 단어"))
        root.addView(text("주소에 이 단어가 들어 있으면 색으로 표시해요. \"안산\"을 등록하면 안산시 단원구, 상록구 모두 걸려요.", 13f, C.SUB))
        wordBox = vbox()
        root.addView(wordBox, lp().also { it.topMargin = dp(6) })
        root.addView(button("+ 단어 추가") { editWord(-1) })
        renderWords()

        root.addView(text("검사할 주소", 15f, bold = true).margins(this, top = 14))
        root.addView(radios(listOf("도착지만", "출발지도 포함"), if (store.checkOrigin) 1 else 0) { store.checkOrigin = it == 1 })
        root.addView(text("빨강(가면 안 됨) 지역 콜은", 15f, bold = true).margins(this, top = 10))
        root.addView(radios(listOf("계산값을 보여 주고 경고", "계산값 숨기기"), if (store.hideRedValue) 1 else 0) { store.hideRedValue = it == 1 })
        root.addView(text("빨강 지역 콜은 어느 쪽이든 '제일 좋음'에서 빠져요.", 13f, C.SUB))

        // ── 기타 ──
        root.addView(sectionTitle("기타"))
        val speedRow = hbox()
        speedRow.addView(text("속도 표시 (읽기·계산에 걸린 시간)", 15f), lp(0, weight = 1f))
        speedRow.addView(MaterialSwitch(this).apply {
            isChecked = store.showSpeed
            setOnCheckedChangeListener { _, on -> store.showSpeed = on }
        })
        root.addView(speedRow)

        // ── 업데이트 ──
        root.addView(sectionTitle("업데이트"))
        val upRow = hbox()
        upRow.addView(text("앱을 켤 때 새 버전 자동 확인", 15f), lp(0, weight = 1f))
        upRow.addView(MaterialSwitch(this).apply {
            isChecked = store.autoUpdate
            setOnCheckedChangeListener { _, on -> store.autoUpdate = on }
        })
        root.addView(upRow)
        root.addView(button("지금 새 버전 확인", outlined = true) { Updater.check(this, quiet = false) })
        root.addView(text("인터넷은 새 버전 확인과 받기에만 써요. 화면에서 읽은 콜 내용은 밖으로 보내지 않아요.", 13f, C.SUB))

        root.addView(text("콜 앱에 따라 약관으로 보조 프로그램 사용을 금지할 수 있으니, 쓰기 전에 확인하세요.", 12f, C.GRAY).margins(this, top = 24))
    }

    // ───────── 계산식 ─────────

    private fun renderFormulas() {
        formulaBox.removeAllViews()
        val selected = store.selectedFormula.id
        for (f in store.formulas) {
            val on = f.id == selected
            val row = hbox().apply {
                background = roundBg(C.PANEL, if (on) C.ACCENT else C.LINE, if (on) 2 else 1)
                setPadding(dp(12), dp(8), dp(6), dp(8))
                layoutParams = lp().also { it.setMargins(0, dp(4), 0, dp(4)) }
                setOnClickListener { store.selectedId = f.id; renderFormulas() }
            }
            row.addView(text(if (on) "●" else "○", 18f, if (on) C.ACCENT else C.SUB).apply { setPadding(0, 0, dp(10), 0) })
            val col = vbox()
            col.addView(text(f.name, 15f, bold = on))
            col.addView(text(MainActivity.pretty(f.expr), 14f, C.SUB))
            row.addView(col, lp(0, weight = 1f))
            row.addView(button("수정", outlined = true) { editFormula(f) })
            row.addView(button("삭제", outlined = true) { deleteFormula(f) }.margins(this, start = 4))
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
        val keys = listOf("A", "B", "C", "+", "−", "×", "÷", "(", ")")
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
                val res = e.eval(mapOf('A' to sample.a!!, 'B' to sample.b!!, 'C' to sample.c!!))
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
            wordBox.addView(text("등록한 단어가 없어요.", 14f, C.GRAY).margins(this, top = 4, bottom = 4))
        }
        words.forEachIndexed { i, w ->
            val row = hbox().apply {
                background = roundBg(C.regionBg(w.color), C.region(w.color), 1)
                setPadding(dp(12), dp(8), dp(6), dp(8))
                layoutParams = lp().also { it.setMargins(0, dp(4), 0, dp(4)) }
                setOnClickListener { editWord(i) }
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.region(w.color)) }
            }, LinearLayout.LayoutParams(dp(14), dp(14)).also { it.marginEnd = dp(10) })
            val col = vbox()
            col.addView(text(w.word, 16f, C.region(w.color), bold = true))
            if (w.memo.isNotBlank()) col.addView(text(w.memo, 13f, C.SUB))
            row.addView(col, lp(0, weight = 1f))
            row.addView(button("수정", outlined = true) { editWord(i) })
            row.addView(button("삭제", outlined = true) { deleteWord(i) }.margins(this, start = 4))
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
        textSize = 16f
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
                textSize = 15f
                setTextColor(C.FG)
                isChecked = i == checked
            })
        }
        setOnCheckedChangeListener { g, id ->
            val idx = (0 until g.childCount).indexOfFirst { g.getChildAt(it).id == id }
            if (idx >= 0) onPick(idx)
        }
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
