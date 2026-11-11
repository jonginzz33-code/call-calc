package com.jongin.callcalc.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 오늘 통계 (분당 금액의 평균·최고·최저).
 * "하루" = 시작 시각(기본 오후 5시) ~ 끝 시각(기본 다음 날 오전 6시).
 * 밤을 넘겨도 같은 하루로 묶고, 그 밖의 시간에 본 콜은 넣지 않는다.
 */
object WorkDay {
    /** 이 시각이 속한 하루(시작한 날짜). 하루 밖이면 null */
    fun of(epochMs: Long, zone: ZoneId, startHour: Int, endHour: Int): LocalDate? {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val h = t.hour
        val date = t.toLocalDate()
        return if (startHour > endHour) {
            // 밤을 넘기는 경우 (예: 17시 ~ 다음 날 6시)
            when {
                h >= startHour -> date
                h < endHour -> date.minusDays(1)
                else -> null
            }
        } else if (startHour < endHour) {
            if (h in startHour until endHour) date else null
        } else date // 시작 = 끝: 하루 종일
    }
}

/** 기록된 콜 하나 (원본 숫자를 저장 → 계산식을 바꿔도 다시 계산 가능) */
data class SeenCall(
    val at: Long,
    val a: Double, val b: Double, val c: Double,
    val origin: String, val destination: String,
)

data class DayStats(val count: Int, val avg: Double?, val max: Double?, val min: Double?)

/**
 * 중복을 뺀 콜 기록.
 * 같은 콜 = 거리·시간·금액이 똑같고, 출발지·도착지가 거의 같고(두 글자 차이까지), 10분 안에 다시 보인 것.
 */
class CallLog(private val sameWithinMs: Long = 10 * 60_000L) {
    val calls = mutableListOf<SeenCall>()

    /** 새 콜이면 기록하고 true, 이미 있던 콜이면 false */
    fun add(r: CallRow, now: Long): Boolean {
        if (!r.complete) return false
        val dup = calls.any {
            it.a == r.a && it.b == r.b && it.c == r.c &&
                now - it.at <= sameWithinMs &&
                similar(it.origin, r.origin) && similar(it.destination, r.destination)
        }
        if (dup) return false
        calls += SeenCall(now, r.a!!, r.b!!, r.c!!, r.origin, r.destination)
        return true
    }

    /** 이 하루의 통계 (지금 계산식으로) */
    fun stats(day: LocalDate, zone: ZoneId, startHour: Int, endHour: Int, s: EvalSettings): DayStats {
        val expr = try { Expr.parse(s.formulaExpr) } catch (e: ExprError) { return DayStats(0, null, null, null) }
        val values = calls
            .filter { WorkDay.of(it.at, zone, startHour, endHour) == day }
            .mapNotNull { (expr.eval(vars(it.a, it.b, it.c, s.pickupMinPerKm)) as? CalcResult.Value)?.v }
        if (values.isEmpty()) return DayStats(0, null, null, null)
        return DayStats(values.size, values.average(), values.max(), values.min())
    }

    /** 오래된 기록 정리 (keepFrom 이전 하루는 버림) */
    fun prune(keepFromMs: Long) { calls.removeAll { it.at < keepFromMs } }

    companion object {
        /** 띄어쓰기·말줄임 빼고 두 글자 차이까지는 같은 곳 */
        fun similar(x: String, y: String): Boolean {
            val a = norm(x); val b = norm(y)
            if (a == b) return true
            if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty()
            // 말줄임으로 잘린 경우: 짧은 쪽이 긴 쪽의 앞부분
            if (a.startsWith(b) || b.startsWith(a)) return true
            return editDistance(a, b) <= 2
        }

        private fun norm(s: String) = s.replace("…", "").replace("...", "").replace(" ", "")

        private fun editDistance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }
    }
}

/**
 * 콜 상세 화면 (수락 전) 읽기:
 *   실수익 17,600 / 이동 도보 69분 약 5.4km / 출발 초지동 한신포차 안산초지점 / 약 40분 운행 / 도착 수원시 … / [콜 수락하기]
 */
data class CallDetail(
    val price: Double,
    val origin: String,
    val destination: String,
    val rideMin: Double?,   // 콜 앱이 알려 준 운행 시간 (참고용)
    val pickupKm: Double? = null, // 콜 앱이 알려 준 내 위치 → 출발지 거리 (직선, "이동 도보 78분 약 5.9km")
)

object DetailParser {
    private val rideRe = Regex("""약?\s*(\d+)\s*분\s*운행""")
    private val stopWords = listOf("닫기", "콜 수락하기", "수락하기", "콜수락하기")

    fun parse(raw: List<TextItem>): CallDetail? {
        val items = raw.filter { it.text.isNotBlank() }
        if (items.none { it.text.replace(" ", "").contains("수락") }) return null
        fun label(name: String) = items.filter { it.text.trim() == name }.minByOrNull { it.left }
        val start = label("출발")
        val end = label("도착")
        if (start == null || end == null) return parseStacked(items)

        // 금액: 쉼표 숫자 중 글씨가 가장 큰 것 (실수익)
        val price = items.mapNotNull { it -> Extract.money(it.text)?.let { v -> v to it.height } }
            .maxByOrNull { it.second }?.first ?: return null

        val stopTop = items.filter { t -> stopWords.any { t.text.replace(" ", "") == it.replace(" ", "") } }
            .minOfOrNull { it.top } ?: Int.MAX_VALUE

        fun valueLines(lab: TextItem, untilTop: Int) = items
            .filter { it !== lab && it.left > lab.right && it.centerY >= lab.top && it.centerY < untilTop }
            .filter { rideRe.find(it.text) == null }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .joinToString(" ") { it.text.trim() }
            .trim()

        val origin = valueLines(start, end.top)
        val destination = valueLines(end, stopTop)
        if (origin.isEmpty() || destination.isEmpty()) return null
        val ride = items.firstNotNullOfOrNull { rideRe.find(it.text)?.groupValues?.get(1)?.toDoubleOrNull() }
        // "이동" 줄의 km (출발 줄보다 위)
        val move = label("이동")
        val pickupKm = items
            .filter { it.centerY < start.top && (move == null || kotlin.math.abs(it.centerY - move.centerY) <= move.height) }
            .firstNotNullOfOrNull { Extract.km(it.text) }
        return CallDetail(price, origin, destination, ride, pickupKm)
    }

    // "도보 1.7km / 약 34분" 처럼 맨 위에 있는 내 위치 → 출발지 거리 줄
    private val moveRe = Regex("""\d+(?:\.\d+)?\s*k?m\s*/""", RegexOption.IGNORE_CASE)

    /**
     * 두 번째 모양 (출발/도착 글자 없이 위아래로 쌓인 창):
     *   [제휴] / 도보 1.7km / 약 34분 / 출발지 주소(1~2줄) / ⌄ / ♥ 도착지 주소 / 약 6분 운행 / 실제수익 12,000 / [닫기] [수락]
     * 거리 줄과 운행 줄 사이의 주소 줄들을, 가장 크게 벌어진 틈(꺾쇠 자리)에서 나눠 위 = 출발지, 아래 = 도착지.
     */
    private fun parseStacked(items: List<TextItem>): CallDetail? {
        val move = items.filter { moveRe.containsMatchIn(it.text) && Extract.km(it.text) != null }.minByOrNull { it.top }
            ?: return null
        val ride = items.filter { rideRe.containsMatchIn(it.text) && it.top > move.bottom }.minByOrNull { it.top }
            ?: return null
        val price = items.filter { it.top > move.bottom }
            .mapNotNull { Extract.money(it.text)?.let { v -> v to it.height } }
            .maxByOrNull { it.second }?.first ?: return null
        val hangul = Regex("[가-힣]")
        val lines = items
            .filter { it.centerY > move.bottom && it.centerY < ride.top }
            .filter { hangul.containsMatchIn(it.text) }       // 꺾쇠(⌄, V) 같은 기호 줄은 빼기
            .sortedWith(compareBy({ it.top }, { it.left }))
        if (lines.size < 2) return null
        // 가장 큰 틈에서 나누기
        var cut = 1
        var gap = Int.MIN_VALUE
        for (i in 1 until lines.size) {
            val g = lines[i].top - lines[i - 1].bottom
            if (g > gap) { gap = g; cut = i }
        }
        fun clean(t: String) = t.replace(Regex("^[^가-힣0-9]+"), "").trim() // 앞의 ♥ 같은 기호 빼기
        val origin = lines.take(cut).joinToString(" ") { clean(it.text) }.trim()
        val destination = lines.drop(cut).joinToString(" ") { clean(it.text) }.trim()
        if (origin.isEmpty() || destination.isEmpty()) return null
        val rideMin = rideRe.find(ride.text)?.groupValues?.get(1)?.toDoubleOrNull()
        return CallDetail(price, origin, destination, rideMin, Extract.km(move.text))
    }

    /**
     * 지도 검색용으로 점점 짧게: "수원시 권선구 호매실동 호매실능실마을19단지아파트"
     *  → [전체, "수원시 권선구 호매실동", "수원시 권선구", ...] 
     */
    fun searchQueries(place: String): List<String> {
        val words = place.split(' ').filter { it.isNotBlank() }
        val out = LinkedHashSet<String>()
        out += words.joinToString(" ")
        for (n in words.size - 1 downTo 1) out += words.take(n).joinToString(" ")
        return out.toList()
    }
}
