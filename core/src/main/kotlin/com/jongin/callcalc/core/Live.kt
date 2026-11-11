package com.jongin.callcalc.core

import kotlin.math.roundToLong

/** 실시간 모드에서만 쓰는 규칙 (설명서 4-6) */
object LiveRules {
    /** 화면 위아래로 잘려서 값이 빠진 줄은 건너뛴다 (가운데 줄의 읽기 실패는 그대로 보여 줌) */
    fun dropEdgeFailures(rows: List<CallRow>): List<CallRow> {
        if (rows.isEmpty()) return rows
        return rows.filterIndexed { i, r -> r.complete || (i != 0 && i != rows.lastIndex) }
    }

    /** 표시 창용 숫자: 쉼표 없이 (OCR이 자기 표시를 금액으로 다시 읽지 않게) */
    fun overlayNumber(v: Double): String = v.roundToLong().toString()

    /** "용인시 수지구 동천동 …" → "동천동" */
    fun shortPlace(dest: String): String =
        dest.replace("…", " ").replace("...", " ").trim().split(' ').lastOrNull { it.isNotBlank() } ?: dest
}

/**
 * "지나온 것 전체 중 최고" 모드용 기억. 스크롤하며 본 콜을 모아 두고 그중 최고를 찾는다.
 * 목록이 바뀌면 (화면 전환) clear() 한다.
 */
class SeenMemory {
    private val rows = LinkedHashMap<String, CallRow>()

    val size: Int get() = rows.size

    fun clear() = rows.clear()

    /**
     * 보이는 줄을 기억에 더하고, 제일 좋음 표시를 "전체 중 최고"에 맞춘 결과와 요약 글을 돌려준다.
     * 요약 예: "최고: 초지동 12,000 (736)"
     */
    fun evaluate(visible: List<CallRow>, s: EvalSettings): Pair<EvalOutput, String?> {
        for (r in visible) if (r.complete) rows[r.key] = r
        val all = Evaluator.evaluate(rows.values.toList(), s)
        val best = all.rows.firstOrNull { it.best }
        val out = Evaluator.evaluate(visible, s)
        val fixed = out.rows.map { it.copy(best = best != null && it.row.key == best.row.key) }
        val summary = best?.let {
            "최고: ${LiveRules.shortPlace(it.row.destination)} ${formatNumber(it.row.c!!)} (${formatNumber(it.value!!)})"
        }
        return EvalOutput(fixed, out.hiddenByMinPrice) to summary
    }
}
