package com.jongin.callcalc.core

import kotlin.math.max
import kotlin.math.min

/** OCR(또는 접근성)이 준 글자 덩어리 하나와 그 위치(픽셀) */
data class TextItem(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val height: Int get() = max(1, bottom - top)
    val centerY: Float get() = (top + bottom) / 2f
    val centerX: Float get() = (left + right) / 2f
}

/** 화면에서 찾은 콜 한 줄 */
data class CallRow(
    val a: Double?,           // 거리 km
    val b: Double?,           // 시간 분
    val c: Double?,           // 금액 원
    val origin: String,       // 출발지 (아랫줄 왼쪽)
    val destination: String,  // 도착지 (윗줄 가운데)
    val payment: String,      // 카드 / 현금 / ""
    val top: Int,
    val bottom: Int,
    val rawLines: List<String>, // 이 콜로 묶인 원본 글자
    // 실시간 표시를 붙일 빈자리 (화면 좌표). 아랫줄 "72분" 오른쪽 ~ "카드" 왼쪽
    val slotLeft: Int = 0,
    val slotRight: Int = 0,
    val slotY: Int = 0,
) {
    /** 같은 콜인지 알아보는 열쇠 (스크롤해도 같은 값) */
    val key: String get() = "$a|$b|$c|$destination"

    val complete: Boolean get() = a != null && b != null && c != null

    /** 못 읽은 값 설명. 다 읽었으면 빈 문자열 */
    fun missingText(): String = buildList {
        if (a == null) add("거리(A)")
        if (b == null) add("시간(B)")
        if (c == null) add("금액(C)")
    }.joinToString(", ")
}

/** 숫자 추출 규칙 (설명서 3절) */
object Extract {
    private val kmRe = Regex("""(\d+(?:\.\d+)?)\s*[kK][mM]""")
    private val hourMinRe = Regex("""(\d+)\s*시간\s*(\d+)\s*분""")
    private val hourRe = Regex("""(\d+)\s*시간""")
    private val minRe = Regex("""(\d+)\s*분""")
    private val manRe = Regex("""(\d+(?:\.\d+)?)\s*만(?:\s*원)?""")
    private val commaRe = Regex("""(?<![\d.,])\d{1,3}(?:,\d{3})+(?![\d,])""")
    private val payRe = Regex("카드|현금")

    // 숫자 자리 후보: 숫자 또는 숫자로 잘못 읽힌 글자(O o l I S B)로 이루어진 덩어리
    private val digitRun = Regex("""[0-9OolISB][0-9OolISB.,]*""")

    /**
     * OCR 오인식 보정. 숫자 패턴 안에서만 O→0, l/I→1, S→5, B→8.
     * 덩어리에 진짜 숫자가 하나 이상 있고, 앞뒤가 다른 영어 단어의 일부가 아닐 때만 바꾼다.
     * (뒤에 붙는 km 은 허용)
     */
    fun fixOcr(s: String): String = digitRun.replace(s) { m ->
        val t = m.value
        if (t.none { it.isDigit() }) return@replace t
        val before = if (m.range.first > 0) s[m.range.first - 1] else ' '
        val afterIdx = m.range.last + 1
        val after = if (afterIdx < s.length) s[afterIdx] else ' '
        val afterIsKm = s.regionMatches(afterIdx, "km", 0, 2, ignoreCase = true)
        if (before.isAsciiLetter()) return@replace t
        if (after.isAsciiLetter() && !afterIsKm) return@replace t
        t.map {
            when (it) {
                'O', 'o' -> '0'
                'l', 'I' -> '1'
                'S' -> '5'
                'B' -> '8'
                else -> it
            }
        }.joinToString("")
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    fun km(s: String): Double? = kmRe.find(s)?.groupValues?.get(1)?.toDoubleOrNull()

    /** "72분" → 72, "1시간 12분" → 72, "2시간" → 120 */
    fun minutes(s: String): Double? {
        hourMinRe.find(s)?.let { return it.groupValues[1].toDouble() * 60 + it.groupValues[2].toDouble() }
        minRe.find(s)?.let { return it.groupValues[1].toDouble() }
        hourRe.find(s)?.let { return it.groupValues[1].toDouble() * 60 }
        return null
    }

    /** "24,000" → 24000, "2.4만" → 24000. km·시간 부분은 먼저 지우고 찾는다 */
    fun money(s: String): Double? {
        val cleaned = stripKmAndTime(s)
        commaRe.findAll(cleaned).lastOrNull()?.let { return it.value.replace(",", "").toDouble() }
        manRe.find(cleaned)?.let { return Math.round(it.groupValues[1].toDouble() * 10000).toDouble() }
        return null
    }

    fun payment(s: String): String? = payRe.find(s)?.value

    fun stripKmAndTime(s: String): String =
        minRe.replace(hourRe.replace(hourMinRe.replace(kmRe.replace(s, " "), " "), " "), " ")

    /** 주소 글자만 남긴다: km, 금액, 시간, 결제 수단, 구분선 제거 */
    fun placeText(s: String): String {
        var t = stripKmAndTime(s)
        t = commaRe.replace(t, " ")
        t = manRe.replace(t, " ")
        t = payRe.replace(t, " ")
        t = t.replace('|', ' ').replace('/', ' ')
        return t.replace(Regex("""\s+"""), " ").trim()
    }
}

/**
 * 글자 덩어리 목록에서 콜 줄을 찾는다 (설명서 3절 "줄 묶기").
 *
 * 1. km 가 있는 덩어리를 기준(앵커)으로 삼는다.
 * 2. 앵커마다 위아래 범위를 잡는다. 아래쪽 끝은 다음 앵커 바로 위까지, 최대 앵커 높이의 3배.
 * 3. 범위 안 덩어리에서 시간(B), 금액(C), 출발지·도착지를 찾는다.
 * 4. 시간도 금액도 없는 앵커는 콜이 아니다 (예: 위쪽 필터 "10km | 금액순").
 */
object CallParser {
    fun parse(rawItems: List<TextItem>): List<CallRow> {
        val items = rawItems
            .map { it.copy(text = Extract.fixOcr(it.text)) }
            .filter { it.text.isNotBlank() }
        val anchors = items.filter { Extract.km(it.text) != null }.sortedBy { it.centerY }
        if (anchors.isEmpty()) return emptyList()

        val rows = mutableListOf<CallRow>()
        for ((idx, anchor) in anchors.withIndex()) {
            val h = anchor.height.toFloat()
            val regionTop = anchor.centerY - 0.8f * h
            val next = anchors.getOrNull(idx + 1)
            val limit = anchor.centerY + 3f * h
            val regionBottom = if (next != null) min(next.centerY - 0.8f * next.height, limit) else limit

            val inRegion = items.filter { it.centerY >= regionTop && it.centerY < regionBottom }
                .sortedWith(compareBy({ it.centerY }, { it.left }))
            // 윗줄: 앵커와 세로 중심이 가까운 것. 나머지는 아랫줄
            val topLine = inRegion.filter { kotlin.math.abs(it.centerY - anchor.centerY) <= 0.6f * h }
                .sortedBy { it.left }
            val bottomLine = inRegion.filter { it !in topLine && it.centerY > anchor.centerY }
                .sortedBy { it.left }

            val a = Extract.km(anchor.text)
            val b = bottomLine.firstNotNullOfOrNull { Extract.minutes(it.text) }
                ?: topLine.firstNotNullOfOrNull { Extract.minutes(it.text) }
            // 금액은 윗줄 오른쪽에서 먼저 찾는다
            val c = topLine.asReversed().firstNotNullOfOrNull { Extract.money(it.text) }
                ?: bottomLine.asReversed().firstNotNullOfOrNull { Extract.money(it.text) }

            if (b == null && c == null) continue // 콜이 아님 (필터 등)

            val payment = inRegion.firstNotNullOfOrNull { Extract.payment(it.text) } ?: ""
            val destination = topLine.joinToString(" ") { Extract.placeText(it.text) }.trim()
            val origin = bottomLine.firstOrNull()?.let { originText(it.text) } ?: ""

            // 표시 자리: 아랫줄의 시간 글자 오른쪽 ~ 결제 글자(또는 금액) 왼쪽
            val timeItem = bottomLine.firstOrNull { Extract.minutes(it.text) != null }
            val payItem = bottomLine.lastOrNull { Extract.payment(it.text) != null }
            val priceItem = topLine.lastOrNull { Extract.money(it.text) != null }
            var slotY = if (bottomLine.isNotEmpty()) bottomLine.map { it.centerY }.average().toInt()
            else (anchor.bottom + 0.7f * h).toInt()
            var slotLeft = timeItem?.right ?: bottomLine.firstOrNull()?.right ?: anchor.right
            var slotRight = payItem?.left ?: priceItem?.left ?: (slotLeft + (6 * h).toInt())
            if (slotRight - slotLeft < 2 * h) {
                // 아랫줄에 빈자리가 없으면 윗줄의 km 오른쪽
                slotY = anchor.centerY.toInt()
                slotLeft = anchor.right
                slotRight = maxOf(slotLeft + (4 * h).toInt(), (priceItem?.left ?: 0))
            }

            rows += CallRow(
                a = a, b = b, c = c,
                origin = origin, destination = destination, payment = payment,
                top = inRegion.minOf { it.top }, bottom = inRegion.maxOf { it.bottom },
                rawLines = inRegion.map { it.text },
                slotLeft = slotLeft, slotRight = slotRight, slotY = slotY,
            )
        }
        return rows
    }

    /** 아랫줄 왼쪽 덩어리에서 출발지 이름만 꺼낸다. "고잔동 오픈콜 | 72분" → "고잔동" */
    private fun originText(s: String): String {
        val first = s.split('|').first()
        val words = Extract.placeText(first).split(' ').filter { it.isNotBlank() }
        val kept = if (words.size > 1 && words.last().endsWith("콜")) words.dropLast(1) else words
        return kept.joinToString(" ")
    }
}
