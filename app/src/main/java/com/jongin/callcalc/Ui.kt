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
}
