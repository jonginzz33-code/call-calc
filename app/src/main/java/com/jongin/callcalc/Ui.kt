package com.jongin.callcalc

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.jongin.callcalc.core.RegionColor
import kotlin.math.roundToInt

/** 색: 콜 화면처럼 검은 바탕. 운전 중에도 색만으로 구분되게 진하게 (시안 기준) */
object C {
    val BG = Color.parseColor("#0C0E12")
    val PANEL = Color.parseColor("#161A21")
    val FG = Color.parseColor("#FFFFFF")
    val SUB = Color.parseColor("#C7CED8")
    val DIM = Color.parseColor("#9AA5B3")
    val LINE = Color.parseColor("#2B323C")
    val BTN = Color.parseColor("#4C8FE0")
    val ACCENT = Color.parseColor("#9CC7FF")
    val GOOD = Color.parseColor("#7DFF7D")
    val BAD = Color.parseColor("#FF8A8A")
    val WARN = Color.parseColor("#FFD54A")
    val GRAY = Color.parseColor("#8A929E")

    /** 지역 글자색 */
    fun region(c: RegionColor) = when (c) {
        RegionColor.RED -> BAD
        RegionColor.YELLOW -> WARN
        RegionColor.GREEN -> GOOD
    }

    /** 지역 테두리색 */
    fun regionLine(c: RegionColor) = when (c) {
        RegionColor.RED -> Color.parseColor("#FF5A5A")
        RegionColor.YELLOW -> Color.parseColor("#FFD54A")
        RegionColor.GREEN -> Color.parseColor("#5FD35F")
    }

    /** 지역 색이 걸린 카드 바탕 */
    fun regionBg(c: RegionColor) = when (c) {
        RegionColor.RED -> Color.parseColor("#4A1416")
        RegionColor.YELLOW -> Color.parseColor("#3D3210")
        RegionColor.GREEN -> Color.parseColor("#123A1C")
    }

    fun regionIcon(c: RegionColor) = when (c) {
        RegionColor.RED -> "⛔"
        RegionColor.YELLOW -> "⚠"
        RegionColor.GREEN -> "👍"
    }
}

fun Context.dp(v: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.text(s: CharSequence, sizeSp: Float = 15f, color: Int = C.FG, bold: Boolean = false) =
    TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.button(label: String, outlined: Boolean = false, big: Boolean = false, onClick: () -> Unit) =
    MaterialButton(this, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle).apply {
        text = label
        textSize = if (big) 20f else 16f
        setTypeface(typeface, Typeface.BOLD)
        isAllCaps = false
        cornerRadius = dp(14)
        insetTop = 0
        insetBottom = 0
        minHeight = dp(if (big) 60 else 46)
        if (outlined) {
            setTextColor(C.ACCENT)
            strokeColor = android.content.res.ColorStateList.valueOf(C.BTN)
            strokeWidth = dp(2)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1F252E"))
        } else {
            setTextColor(C.FG)
            backgroundTintList = android.content.res.ColorStateList.valueOf(C.BTN)
        }
        setOnClickListener { onClick() }
    }

/** 점선 테두리 "+ 추가" 버튼 */
fun Context.dashedButton(label: String, onClick: () -> Unit) = text(label, 18f, C.ACCENT, bold = true).apply {
    gravity = Gravity.CENTER
    setPadding(0, dp(14), 0, dp(14))
    background = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setStroke(dp(2), C.BTN, dp(8).toFloat(), dp(5).toFloat())
    }
    layoutParams = lp().also { it.setMargins(0, dp(8), 0, 0) }
    setOnClickListener { onClick() }
}

fun Context.vbox(padDp: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val p = dp(padDp)
    setPadding(p, p, p, p)
}

fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.roundBg(fill: Int, stroke: Int = C.LINE, strokeDp: Int = 2, radiusDp: Int = 14, dashed: Boolean = false) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radiusDp).toFloat()
    if (dashed) setStroke(dp(strokeDp), stroke, dp(8).toFloat(), dp(5).toFloat()) else setStroke(dp(strokeDp), stroke)
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun View.margins(context: Context, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0): View {
    val l = (layoutParams as? LinearLayout.LayoutParams) ?: lp()
    l.setMargins(context.dp(start), context.dp(top), context.dp(end), context.dp(bottom))
    layoutParams = l
    return this
}

/** 화면 전체를 세로 스크롤로 감싸고, 상태 표시줄·내비게이션 바 자리만큼 띄운다 */
fun Activity.scrollPage(content: View): ScrollView {
    val scroll = ScrollView(this).apply {
        setBackgroundColor(C.BG)
        isFillViewport = true
        addView(content)
    }
    ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    setContentView(scroll)
    return scroll
}

fun Context.sectionTitle(s: String) = text(s, 21f, C.FG, bold = true).apply {
    layoutParams = lp().also { it.setMargins(0, dp(24), 0, dp(8)) }
    tag = SECTION_TAG
}

const val SECTION_TAG = "section"

/**
 * 커튼(접었다 펴기): root 안에서 sectionTitle 다음에 오는 칸들을 그 제목 아래 상자에 담아 접어 둔다.
 * 제목을 누르면 펼치고, 다시 누르면 접는다. 펼침 상태는 기억한다.
 */
fun Context.makeCurtains(root: LinearLayout, prefs: android.content.SharedPreferences) {
    val kids = (0 until root.childCount).map { root.getChildAt(it) }
    root.removeAllViews()
    var body: LinearLayout? = null
    for (v in kids) {
        if (v.tag == SECTION_TAG && v is TextView) {
            val title = v.text.toString()
            val key = "curtain_$title"
            val box = vbox().apply { setPadding(dp(4), 0, dp(4), dp(8)) }
            val head = hbox().apply {
                background = roundBg(C.PANEL, C.LINE, 2, 14)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = lp().also { it.setMargins(0, dp(10), 0, 0) }
            }
            head.addView(text(title, 19f, C.FG, bold = true), lp(0, weight = 1f))
            val arrow = text("", 18f, C.ACCENT, bold = true)
            head.addView(arrow)
            fun apply(open: Boolean) {
                box.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "접기 ▴" else "펼치기 ▾"
            }
            apply(prefs.getBoolean(key, false))
            head.setOnClickListener {
                val open = box.visibility != View.VISIBLE
                prefs.edit().putBoolean(key, open).apply()
                apply(open)
            }
            root.addView(head)
            root.addView(box)
            body = box
        } else {
            (body ?: root).addView(v)
        }
    }
}

/**
 * 픽업 1km당 분 슬라이더 (1~10분, 1분 단위) + 큰 숫자 + 예시 계산 + 빠른 버튼(2·3·5분).
 * onChange 는 바꾼 뒤 (화면 다시 그리기 등)
 */
fun Context.pickupSlider(store: Store, onChange: () -> Unit): LinearLayout {
    val box = vbox()
    val head = hbox()
    head.addView(text("픽업 1km당", 16f, C.DIM), lp(0, weight = 1f))
    val big = text("", 28f, C.GOOD, bold = true)
    head.addView(big)
    box.addView(head)
    val bar = android.widget.SeekBar(this).apply {
        max = 9
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    box.addView(bar)
    val ticks = hbox().apply { setPadding(dp(10), 0, dp(10), 0) }
    val tickViews = (1..10).map { n ->
        text("$n", 13f, C.GRAY).apply { gravity = Gravity.CENTER }.also { ticks.addView(it, lp(0, weight = 1f)) }
    }
    box.addView(ticks)
    val example = text("", 15f, C.SUB).apply {
        background = roundBg(C.PANEL, C.PANEL, 1, 12)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = lp().also { it.topMargin = dp(8) }
    }
    box.addView(example)

    fun show() {
        val m = store.pickupMinPerKm.roundToInt().coerceIn(1, 10)
        big.text = "${m}분"
        if (bar.progress != m - 1) bar.progress = m - 1
        tickViews.forEachIndexed { i, t ->
            val on = i == m - 1
            t.setTextColor(if (on) C.GOOD else C.GRAY)
            t.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        val raw = 8.4 * m
        example.text = "예) 픽업 8.4km → 8.4 × $m = ${String.format(java.util.Locale.US, "%.1f", raw)} → ${Math.round(raw)}분"
    }
    fun set(m: Int) {
        if (store.pickupMinPerKm == m.toDouble()) { show(); return }
        store.pickupMinPerKm = m.toDouble()
        show()
        CallReaderService.instance?.settingsChanged()
        onChange()
    }
    bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) set(p + 1) }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
    })
    show()
    return box
}

/**
 * 밑에서 올라오는 설정 창. build 에 그 주제의 설정만 넣는다. 창 바깥을 누르거나 아래로 끌면 닫힘.
 */
fun android.app.Activity.sheet(title: String, onClose: () -> Unit = {}, build: (LinearLayout) -> Unit) {
    val d = com.google.android.material.bottomsheet.BottomSheetDialog(this)
    val content = vbox(18)
    val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
    head.addView(text(title, 22f, bold = true), lp(0, weight = 1f))
    head.addView(text("✕", 24f, C.SUB).apply { setPadding(dp(16), dp(4), dp(4), dp(4)); setOnClickListener { d.dismiss() } })
    content.addView(head)
    build(content)
    content.addView(button("완료", big = true) { d.dismiss() }.margins(this, top = 18))
    val scroll = androidx.core.widget.NestedScrollView(this).apply {
        addView(content)
        background = GradientDrawable().apply {
            setColor(C.BG)
            val r = dp(20).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }
    d.setContentView(scroll)
    d.setOnShowListener {
        d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        d.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
    }
    d.setOnDismissListener { onClose() }
    d.show()
}

/** [-] 값 [+] 줄. 값을 누르면 직접 입력 */
fun android.app.Activity.stepper(label: String, unit: String, get: () -> Double?, step: Double, min: Double, max: Double, set: (Double?) -> Unit): LinearLayout {
    val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = lp().also { it.topMargin = dp(10) } }
    row.addView(text(label, 17f), lp(0, weight = 1f))
    val v = text("", 18f, C.GOOD, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0) }
    fun show() { v.text = get()?.let { com.jongin.callcalc.core.formatNumber(it) + unit } ?: "안 씀" }
    fun btn(s: String, d: Double) = text(s, 22f, C.ACCENT, bold = true).apply {
        gravity = Gravity.CENTER
        background = roundBg(Color.parseColor("#1F252E"), C.BTN, 2, 10)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        setOnClickListener { set(((get() ?: min) + d).coerceIn(min, max)); show() }
    }
    row.addView(btn("−", -step))
    row.addView(v)
    row.addView(btn("+", step))
    v.setOnClickListener {
        val e = android.widget.EditText(this).apply {
            setText(get()?.let { Store.plain(it) } ?: ""); textSize = 22f; setTextColor(C.FG)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(label).setView(vbox(20).apply { addView(e) })
            .setPositiveButton("저장") { _, _ -> set(e.text.toString().replace(",", "").toDoubleOrNull()); show() }
            .setNegativeButton("취소", null).show()
    }
    show()
    return row
}
