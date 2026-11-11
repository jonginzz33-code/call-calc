package com.jongin.callcalc

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jongin.callcalc.core.CallParser
import com.jongin.callcalc.core.CallRow
import com.jongin.callcalc.core.EvaluatedRow
import com.jongin.callcalc.core.Evaluator
import com.jongin.callcalc.core.RegionColor
import com.jongin.callcalc.core.RowStatus
import com.jongin.callcalc.core.Sample
import com.jongin.callcalc.core.TextItem
import com.jongin.callcalc.core.formatNumber

/**
 * v0.1 이미지 테스트 화면.
 * 스크린샷을 골라 글자를 읽고, 찾은 콜마다 A·B·C, 계산값, 색 표시를 보여 준다.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var store: Store

    private lateinit var formulaView: TextView
    private lateinit var infoView: TextView
    private lateinit var statusView: TextView
    private lateinit var speedView: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var rawToggle: TextView
    private lateinit var rawView: TextView
    private lateinit var preview: ImageView

    // 마지막으로 읽은 내용 (설정이 바뀌면 다시 계산만 한다)
    private var lastItems: List<TextItem> = emptyList()
    private var lastRows: List<CallRow> = emptyList()
    private var lastLoadMs = -1L
    private var lastOcrMs = -1L
    private var lastParseMs = -1L
    private var hasResult = false
    private var rawOpen = false
    private val openRows = mutableSetOf<Int>()

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) readImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        buildUi()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 돌아오면 새 설정으로 다시 계산
        showFormula()
        if (hasResult) render()
        checkPermissions()
    }

    private var guideDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * 꺼진 권한이 있으면 바로 안내 창. 설정에서 켜고 돌아오면 (onResume) 다시 확인해서
     * 다 켜졌으면 창을 닫는다. 권한 안내가 끝난 뒤에 새 버전을 확인한다.
     */
    private fun checkPermissions() {
        val missing = PermissionGuide.missing(this)
        if (missing.isEmpty()) {
            if (guideDialog?.isShowing == true) {
                guideDialog?.dismiss()
                android.widget.Toast.makeText(this, "권한 설정이 끝났어요 ✅", android.widget.Toast.LENGTH_SHORT).show()
            }
            guideDialog = null
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

    /** 앱을 켠 뒤 한 번만 조용히 새 버전 확인 */
    private fun checkUpdateOnce() {
        if (updateChecked || !store.autoUpdate) return
        updateChecked = true
        Updater.check(this, quiet = true)
    }

    private fun buildUi() {
        val root = vbox(16)
        scrollPage(root)

        val header = hbox()
        header.addView(text("콜 계산기", 28f, bold = true), lp(0, weight = 1f))
        header.addView(text("v" + versionName(), 15f, C.DIM))
        root.addView(header)

        val row1 = hbox().apply { margins(this@MainActivity, top = 14) }
        row1.addView(button("📷 스크린샷 고르기", big = true) {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, lp(0, weight = 1f))
        row1.addView(button("⚙ 설정", outlined = true, big = true) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, lp(dp(110)).also { it.marginStart = dp(8) })
        root.addView(row1)

        // 계산식 칸: 누르면 바로 바꾸기
        formulaView = text("", 17f, C.ACCENT).apply {
            background = roundBg(C.PANEL)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { chooseFormula() }
        }
        root.addView(formulaView.margins(this, top = 12))
        infoView = text("", 16f, C.SUB).apply { margins(this@MainActivity, top = 10) }
        root.addView(infoView)

        statusView = text("", 16f, C.FG).apply { margins(this@MainActivity, top = 6) }
        root.addView(statusView)

        resultBox = vbox()
        root.addView(resultBox, lp().also { it.topMargin = dp(4) })

        speedView = text("", 14f, C.DIM).apply { margins(this@MainActivity, top = 12); visibility = View.GONE }
        root.addView(speedView)

        rawToggle = text("", 16f, C.ACCENT).apply {
            setPadding(0, dp(14), 0, dp(6))
            visibility = View.GONE
            setOnClickListener { rawOpen = !rawOpen; renderRaw() }
        }
        root.addView(rawToggle)
        rawView = text("", 13f, C.SUB).apply {
            typeface = Typeface.MONOSPACE
            background = roundBg(C.PANEL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        root.addView(rawView)

        preview = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
        }
        root.addView(preview, lp().also { it.topMargin = dp(16) })

        root.addView(button("예시 3줄로 해 보기", outlined = true) { runSample() }.margins(this, top = 20))

        root.addView(text("이 앱은 읽고 계산해서 보여 주기만 해요. 콜 수락은 직접 누르세요.", 13f, C.GRAY).margins(this, top = 24))
        showFormula()
        statusView.text = "아직 읽은 화면이 없어요."
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun showFormula() {
        val f = store.selectedFormula
        formulaView.text = android.text.SpannableStringBuilder().apply {
            append("계산식  ")
            val st = length
            append(pretty(f.expr))
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(C.FG), st, length, 0)
            append("   바꾸기 ▾")
        }
        val parts = mutableListOf<String>()
        parts += "기준 단가 " + (store.threshold?.let { formatNumber(it) } ?: "없음")
        if (store.minPriceOn && store.minPrice != null) parts += "최소 " + formatNumber(store.minPrice!!) + "원"
        parts += "지역 단어 ${store.words.size}개"
        infoView.text = parts.joinToString(" · ")
    }

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
                showFormula()
                if (hasResult) render()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) readImage(uri)
    }

    private fun readImage(uri: Uri) {
        statusView.text = "글자 읽는 중…"
        statusView.setTextColor(C.FG)
        resultBox.removeAllViews()
        preview.setImageURI(uri)
        preview.visibility = View.VISIBLE
        Ocr.read(this, uri) { result ->
            result.onSuccess { r ->
                lastLoadMs = r.loadMs
                lastOcrMs = r.ocrMs
                parseAndShow(r.items)
            }.onFailure { e ->
                hasResult = false
                statusView.text = "글자를 읽지 못했어요: ${e.message ?: e.javaClass.simpleName}"
                statusView.setTextColor(C.BAD)
            }
        }
    }

    private fun runSample() {
        preview.visibility = View.GONE
        lastLoadMs = -1
        lastOcrMs = -1
        parseAndShow(Sample.screen())
    }

    private fun parseAndShow(items: List<TextItem>) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        lastItems = items
        lastRows = CallParser.parse(items)
        lastParseMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        hasResult = true
        openRows.clear()
        render()
    }

    /** 현재 설정으로 계산하고 결과 카드를 다시 그린다 */
    private fun render() {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val out = Evaluator.evaluate(lastRows, store.evalSettings())
        val calcMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000

        resultBox.removeAllViews()
        if (out.rows.isEmpty()) {
            statusView.text = if (lastItems.isEmpty()) "글자를 하나도 찾지 못했어요." else
                "콜 줄을 찾지 못했어요. 아래 '인식한 원본 글자'에서 km·분·금액이 제대로 읽혔는지 확인해 보세요."
            statusView.setTextColor(C.WARN)
        } else {
            val fails = out.rows.count { it.status == RowStatus.READ_FAIL }
            var s = "콜 ${out.rows.size}개"
            if (fails > 0) s += " · 읽기 실패 ${fails}개"
            if (out.hiddenByMinPrice > 0) s += " · 싼 콜 ${out.hiddenByMinPrice}개 숨김"
            statusView.text = s
            statusView.setTextColor(C.FG)
            out.rows.forEachIndexed { i, e -> resultBox.addView(card(i, e)) }
        }

        if (store.showSpeed) {
            val parts = mutableListOf<String>()
            if (lastLoadMs >= 0) parts += "이미지 불러오기 ${lastLoadMs}ms"
            if (lastOcrMs >= 0) parts += "글자 읽기(OCR) ${lastOcrMs}ms"
            parts += "줄 찾기 ${lastParseMs}ms"
            parts += "계산 ${calcMs}ms"
            val total = (lastOcrMs.coerceAtLeast(0)) + lastParseMs + calcMs
            speedView.text = "⏱ " + parts.joinToString(" · ") + "\n   합계 ${total}ms (목표 500ms 이내)"
            speedView.visibility = View.VISIBLE
        } else {
            speedView.visibility = View.GONE
        }
        rawToggle.visibility = View.VISIBLE
        renderRaw()
    }

    private fun card(index: Int, e: EvaluatedRow): View {
        val r = e.row
        val region = e.regionColor
        val fail = e.status == RowStatus.READ_FAIL || e.status == RowStatus.IMPOSSIBLE
        // 바탕·테두리: 제일 좋음(초록 테두리) > 빨강 지역 > 기타 지역색 > 기본
        val fill = when {
            e.best -> C.regionBg(RegionColor.GREEN)
            region != null -> C.regionBg(region)
            fail -> android.graphics.Color.parseColor("#1A1D22")
            else -> C.PANEL
        }
        val bg = when {
            e.best -> roundBg(fill, C.regionLine(RegionColor.GREEN), 3, 16)
            region == RegionColor.RED -> roundBg(fill, C.regionLine(RegionColor.RED), 3, 16)
            fail -> roundBg(fill, C.WARN, 2, 16, dashed = true)
            else -> roundBg(fill, fill, 3, 16)
        }
        val box = hbox().apply {
            background = bg
            setPadding(dp(4), dp(12), dp(12), dp(12))
            layoutParams = lp().also { it.setMargins(0, dp(6), 0, dp(6)) }
        }
        if (e.status == RowStatus.BELOW) box.alpha = 0.6f

        // 왼쪽: 별표 + 큰 계산값 + 한마디
        val valueColor = when {
            fail -> C.WARN
            e.status == RowStatus.BELOW -> C.GRAY
            e.best -> C.GOOD
            region == RegionColor.RED -> android.graphics.Color.parseColor("#FFB3B3")
            else -> C.FG
        }
        val left = vbox().apply { gravity = Gravity.CENTER }
        if (e.best) left.addView(text("★", 30f, C.GOOD, bold = true).apply { gravity = Gravity.CENTER })
        val big = !fail && !e.valueHidden
        left.addView(text(if (fail) e.valueText.replace(" ", "\n") else e.valueText, if (big) 40f else 22f, valueColor, bold = true).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        })
        val tag = when {
            e.best -> "제일 좋음"
            region == RegionColor.RED -> "가면 안 됨"
            e.status == RowStatus.BELOW -> "기준 미달"
            else -> ""
        }
        val tagColor = if (region == RegionColor.RED && !e.best) C.BAD else valueColor
        if (tag.isNotEmpty()) left.addView(text(tag, 15f, tagColor, bold = true).apply { gravity = Gravity.CENTER }.margins(this, top = 4))
        box.addView(left, lp(dp(120), android.view.ViewGroup.LayoutParams.MATCH_PARENT))

        // 오른쪽: 금액 크게, 거리·시간, 출발→도착, 지역 경고
        val right = vbox()
        right.addView(text(r.c?.let { "${formatNumber(it)}원" } ?: "금액 ?", 22f, C.FG, bold = true))
        val a = r.a?.let { "${Store.plain(it)}km" } ?: "?km"
        val b = r.b?.let { "${Store.plain(it)}분" } ?: "?분"
        val pay = if (r.payment.isNotBlank()) " · ${r.payment}" else ""
        right.addView(text("$a · $b$pay", 16f, C.SUB).margins(this, top = 2))
        val route = listOf(r.origin, r.destination).filter { it.isNotBlank() }.joinToString(" → ")
        if (route.isNotBlank()) right.addView(text(route, 16f, C.SUB).margins(this, top = 4))
        if (e.status == RowStatus.READ_FAIL) {
            right.addView(text("못 읽은 값: ${r.missingText()}", 16f, C.WARN, bold = true).margins(this, top = 4))
        }
        for (w in e.matched) {
            val memo = if (w.memo.isNotBlank()) ": ${w.memo}" else ""
            right.addView(text("${C.regionIcon(w.color)} ${w.word}$memo", 17f, C.region(w.color), bold = true).margins(this, top = 6))
        }
        if (index in openRows) {
            right.addView(text(r.rawLines.joinToString("\n"), 13f, C.DIM).apply {
                typeface = Typeface.MONOSPACE
            }.margins(this, top = 6))
        }
        box.addView(right, lp(0, weight = 1f))

        // 누르면 이 줄로 묶인 원본 글자를 펼친다
        box.setOnClickListener {
            if (!openRows.add(index)) openRows.remove(index)
            render()
        }
        return box
    }

    private fun renderRaw() {
        rawToggle.text = (if (rawOpen) "▾ " else "▸ ") + "인식한 원본 글자 보기 (${lastItems.size}줄)"
        if (!rawOpen) { rawView.visibility = View.GONE; return }
        rawView.visibility = View.VISIBLE
        rawView.text = if (lastItems.isEmpty()) "(없음)" else
            lastItems.sortedWith(compareBy({ it.top }, { it.left }))
                .joinToString("\n") { "y${it.top.toString().padStart(5)} x${it.left.toString().padStart(5)}  ${it.text}" }
    }

    companion object {
        // 앱이 켜져 있는 동안 기억 (화면 회전 등으로 다시 만들어져도 유지)
        private var guideSnoozed = false
        private var updateChecked = false

        /** 내부 표기 * / 를 화면 표기 × ÷ 로 */
        fun pretty(expr: String) = expr.replace("*", "×").replace("/", "÷")
    }
}
