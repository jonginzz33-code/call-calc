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
        // 처음 켤 때만 조용히 새 버전 확인 (화면 회전 등으로 다시 만들어질 땐 안 함)
        if (savedInstanceState == null && store.autoUpdate) Updater.check(this, quiet = true)
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
    }

    private fun buildUi() {
        val root = vbox(16)
        scrollPage(root)

        val header = hbox()
        header.addView(text("콜 계산기", 26f, bold = true), lp(0, weight = 1f))
        header.addView(text("v" + versionName(), 14f, C.SUB))
        root.addView(header)
        root.addView(text("콜 목록 스크린샷을 고르면 줄마다 계산값과 색을 보여 줘요.", 14f, C.SUB).margins(this, top = 4))

        val row1 = hbox().apply { margins(this@MainActivity, top = 16) }
        row1.addView(button("스크린샷 고르기") {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, lp(0, weight = 1f))
        row1.addView(button("설정", outlined = true) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }.margins(this, start = 8))
        root.addView(row1)

        root.addView(button("예시 3줄로 해 보기", outlined = true) { runSample() })

        formulaView = text("", 15f, C.ACCENT).apply {
            background = roundBg(C.PANEL)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { chooseFormula() }
        }
        root.addView(formulaView.margins(this, top = 12))
        infoView = text("", 13f, C.SUB).apply { margins(this@MainActivity, top = 6) }
        root.addView(infoView)

        speedView = text("", 13f, C.SUB).apply { margins(this@MainActivity, top = 10); visibility = View.GONE }
        root.addView(speedView)
        statusView = text("", 15f, C.FG).apply { margins(this@MainActivity, top = 10) }
        root.addView(statusView)

        resultBox = vbox()
        root.addView(resultBox, lp().also { it.topMargin = dp(8) })

        rawToggle = text("", 15f, C.ACCENT).apply {
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

        root.addView(text("이 앱은 읽고 계산해서 보여 주기만 해요. 콜 수락은 직접 누르세요.", 12f, C.GRAY).margins(this, top = 24))
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
        formulaView.text = "계산식: ${f.name}   ${pretty(f.expr)}   ▾"
        val parts = mutableListOf<String>()
        parts += "기준 단가: " + (store.threshold?.let { formatNumber(it) } ?: "없음")
        if (store.minPriceOn && store.minPrice != null) parts += "최소 금액: " + formatNumber(store.minPrice!!) + "원"
        parts += "지역 단어 ${store.words.size}개"
        infoView.text = parts.joinToString("  ·  ")
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
            var s = "콜 ${out.rows.size}개를 찾았어요."
            if (fails > 0) s += " (읽기 실패 ${fails}개)"
            if (out.hiddenByMinPrice > 0) s += " 최소 금액 미만 ${out.hiddenByMinPrice}개는 숨겼어요."
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
            speedView.text = "⏱ " + parts.joinToString(" · ") + "\n   읽기+계산 합계 ${total}ms (목표: OCR 방식 500ms 이내)"
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
        val fill = region?.let { C.regionBg(it) } ?: C.PANEL
        val (stroke, strokeDp) = when {
            e.best -> C.GOOD to 3
            region == RegionColor.RED -> C.BAD to 2
            else -> C.LINE to 1
        }
        val box = hbox().apply {
            background = roundBg(fill, stroke, strokeDp)
            setPadding(dp(12), dp(12), dp(14), dp(12))
            gravity = Gravity.TOP
            layoutParams = lp().also { it.setMargins(0, dp(6), 0, dp(6)) }
        }
        // 기준 미달은 카드 전체를 흐리게
        if (e.status == RowStatus.BELOW) box.alpha = 0.55f

        // 왼쪽: 큰 계산값 + 별표
        val left = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val valueColor = when (e.status) {
            RowStatus.OK -> if (e.best) C.GOOD else C.FG
            RowStatus.BELOW -> C.GRAY
            RowStatus.READ_FAIL -> C.WARN
            RowStatus.IMPOSSIBLE -> C.SUB
        }
        val big = e.status == RowStatus.OK || e.status == RowStatus.BELOW
        left.addView(text(if (e.best) "★" else "${index + 1}", if (e.best) 22f else 14f, if (e.best) C.GOOD else C.SUB, bold = true).apply {
            gravity = Gravity.CENTER
        })
        left.addView(text(e.valueText, if (big) 28f else 16f, valueColor, bold = true).apply { gravity = Gravity.CENTER })
        val tag = when {
            e.best -> "제일 좋음"
            e.status == RowStatus.BELOW -> "기준 미달"
            else -> ""
        }
        if (tag.isNotEmpty()) left.addView(text(tag, 12f, valueColor).apply { gravity = Gravity.CENTER })
        box.addView(left, lp(dp(104)))

        // 오른쪽: A·B·C, 출발→도착, 지역 경고
        val right = vbox().apply { setPadding(dp(10), 0, 0, 0) }
        val a = r.a?.let { "${Store.plain(it)}km" } ?: "km ?"
        val b = r.b?.let { "${Store.plain(it)}분" } ?: "분 ?"
        val c = r.c?.let { "${formatNumber(it)}원" } ?: "금액 ?"
        right.addView(text("A $a   B $b   C $c", 15f, C.FG, bold = true))
        val route = listOf(r.origin, r.destination).filter { it.isNotBlank() }.joinToString(" → ")
        val pay = if (r.payment.isNotBlank()) "  ·  ${r.payment}" else ""
        if (route.isNotBlank() || pay.isNotBlank()) right.addView(text(route + pay, 14f, C.SUB).margins(this, top = 2))
        if (e.status == RowStatus.READ_FAIL) {
            right.addView(text("못 읽은 값: ${r.missingText()}", 13f, C.WARN).margins(this, top = 4))
        }
        for (w in e.matched) {
            val memo = if (w.memo.isNotBlank()) ": ${w.memo}" else ""
            right.addView(text("${C.regionIcon(w.color)} ${w.word}$memo", 14f, C.region(w.color), bold = w.color == RegionColor.RED)
                .margins(this, top = 4))
        }
        if (index in openRows) {
            right.addView(text(r.rawLines.joinToString("\n"), 12f, C.GRAY).apply {
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
        /** 내부 표기 * / 를 화면 표기 × ÷ 로 */
        fun pretty(expr: String) = expr.replace("*", "×").replace("/", "÷")
    }
}
