package com.jongin.callcalc.core

import kotlin.math.roundToLong

/** 지역 단어 색. 우선순위: 빨강 > 노랑 > 초록 */
enum class RegionColor(val label: String, val rank: Int) {
    RED("빨강 (가면 안 됨)", 3),
    YELLOW("노랑 (주의)", 2),
    GREEN("초록 (선호)", 1),
}

data class RegionWord(val word: String, val color: RegionColor, val memo: String = "")

data class Formula(val id: String, val name: String, val expr: String)

object Defaults {
    val formulas = listOf(
        Formula("default1", "금액 ÷ (거리 + 시간)", "C / (A + B)"),
        Formula("default2", "금액 ÷ 시간", "C / B"),
        Formula("default3", "금액 ÷ 거리", "C / A"),
    )
}

data class EvalSettings(
    val formulaExpr: String = Defaults.formulas[0].expr,
    val threshold: Double? = null,       // 기준 단가. null 이면 안 씀
    val minPrice: Double? = null,        // 최소 금액. null 이면 꺼짐
    val words: List<RegionWord> = emptyList(),
    val checkOrigin: Boolean = false,    // 출발지도 검사
    val hideRedValue: Boolean = false,   // 빨강 지역은 계산값 숨김 (기본: 보여 주고 경고)
)

enum class RowStatus { OK, BELOW, READ_FAIL, IMPOSSIBLE }

data class EvaluatedRow(
    val row: CallRow,
    val value: Double?,              // 계산값 (못 하면 null)
    val status: RowStatus,
    val regionColor: RegionColor?,   // 걸린 단어 중 가장 강한 색
    val matched: List<RegionWord>,   // 걸린 단어들
    val best: Boolean,               // 제일 좋음 (초록 테두리 + 별표)
    val valueHidden: Boolean,        // 빨강 지역이라 값 숨김
) {
    /** 반올림한 정수, 천 단위 쉼표 */
    val valueText: String
        get() = when {
            status == RowStatus.READ_FAIL -> "읽기 실패"
            status == RowStatus.IMPOSSIBLE -> "계산 불가"
            valueHidden -> "—"
            else -> formatNumber(value!!)
        }
}

data class EvalOutput(val rows: List<EvaluatedRow>, val hiddenByMinPrice: Int)

fun formatNumber(v: Double): String = String.format(java.util.Locale.US, "%,d", v.roundToLong())

object RegionJudge {
    /** "포함" 방식 비교. 띄어쓰기는 무시한다 */
    fun match(text: String, words: List<RegionWord>): List<RegionWord> {
        val t = text.replace(" ", "")
        return words.filter { it.word.isNotBlank() && t.contains(it.word.replace(" ", "")) }
            .sortedByDescending { it.color.rank }
    }
}

object Evaluator {
    fun evaluate(rows: List<CallRow>, s: EvalSettings): EvalOutput {
        val expr = try { Expr.parse(s.formulaExpr) } catch (e: ExprError) { null }
        var hidden = 0
        val list = mutableListOf<EvaluatedRow>()
        for (r in rows) {
            if (s.minPrice != null && r.c != null && r.c < s.minPrice) { hidden++; continue }
            val target = if (s.checkOrigin) r.destination + " " + r.origin else r.destination
            val matched = RegionJudge.match(target, s.words)
            val color = matched.firstOrNull()?.color
            var value: Double? = null
            val status = when {
                !r.complete -> RowStatus.READ_FAIL
                expr == null -> RowStatus.IMPOSSIBLE
                else -> when (val res = expr.eval(mapOf('A' to r.a!!, 'B' to r.b!!, 'C' to r.c!!))) {
                    is CalcResult.Value -> {
                        value = res.v
                        if (s.threshold != null && res.v < s.threshold) RowStatus.BELOW else RowStatus.OK
                    }
                    CalcResult.Impossible -> RowStatus.IMPOSSIBLE
                }
            }
            list += EvaluatedRow(
                row = r, value = value, status = status, regionColor = color, matched = matched,
                best = false, valueHidden = color == RegionColor.RED && s.hideRedValue && value != null,
            )
        }
        // 제일 좋음: 계산된 줄 중 빨강 지역을 뺀 최고값 (같은 값이면 위쪽 줄)
        val bestIdx = list.indices
            .filter { list[it].value != null && list[it].regionColor != RegionColor.RED }
            .maxByOrNull { list[it].value!! }
        val out = if (bestIdx == null) list else list.mapIndexed { i, e -> if (i == bestIdx) e.copy(best = true) else e }
        return EvalOutput(out, hidden)
    }
}
