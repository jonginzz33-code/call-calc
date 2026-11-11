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

/** 색: 콜 화면처럼 검은 바탕. 운전 중에도 색만으로 구분되게 진하게 */
object C {
    val BG = Color.parseColor("#0C0E12")
    val PANEL = Color.parseColor("#161A21")
    val FG = Color.parseColor("#EEF1F5")
    val SUB = Color.parseColor("#9AA5B3")
    val LINE = Color.parseColor("#2B323C")
    val ACCENT = Color.parseColor("#6AA9F2")
    val GOOD = Color.parseColor("#8FCE6A")
    val BAD = Color.parseColor("#F08A88")
    val WARN = Color.parseColor("#E5B755")
    val GRAY = Color.parseColor("#6B7380")

    fun region(c: RegionColor) = when (c) {
        RegionColor.RED -> BAD
        RegionColor.YELLOW -> WARN
        RegionColor.GREEN -> GOOD
    }

    /** 지역 색이 걸린 카드 바탕 (살짝 물든 검정) */
    fun regionBg(c: RegionColor) = when (c) {
        RegionColor.RED -> Color.parseColor("#2E1517")
        RegionColor.YELLOW -> Color.parseColor("#2B2410")
        RegionColor.GREEN -> Color.parseColor("#15291A")
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

fun Context.button(label: String, outlined: Boolean = false, onClick: () -> Unit) =
    MaterialButton(this, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle).apply {
        text = label
        textSize = 15f
        isAllCaps = false
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

fun Context.roundBg(fill: Int, stroke: Int = C.LINE, strokeDp: Int = 1, radiusDp: Int = 12) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radiusDp).toFloat()
    setStroke(dp(strokeDp), stroke)
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

fun Context.sectionTitle(s: String) = text(s, 18f, C.FG, bold = true).apply {
    layoutParams = lp().also { it.setMargins(0, dp(24), 0, dp(8)) }
}
