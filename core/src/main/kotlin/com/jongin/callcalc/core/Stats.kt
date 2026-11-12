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
    val hasVia: Boolean = false,  // "경유지 1" 처럼 중간에 들르는 곳이 있음
)

object DetailParser {
    private val rideRe = Regex("""약?\s*(\d+)\s*분\s*운행""")
    private val stopWords = listOf("닫기", "콜 수락하기", "수락하기", "콜수락하기")

    /** 글자 인식이 "출발 선부동 선부3동주민센터"처럼 붙여 읽으면 "출발" 칸과 주소 칸으로 나눈다 */
    private fun splitLabels(items: List<TextItem>): List<TextItem> = items.flatMap { t ->
        val m = Regex("""^\s*(출발|도착|이동)\s+(.+)$""").find(t.text) ?: return@flatMap listOf(t)
        val label = m.groupValues[1]
        val rest = m.groupValues[2]
        val total = t.text.trim().length.coerceAtLeast(1)
        val cut = t.left + (t.right - t.left) * label.length / total
        listOf(
            TextItem(label, t.left, t.top, cut - 1, t.bottom),
            TextItem(rest, cut, t.top, t.right, t.bottom),
        )
    }

    fun parse(raw: List<TextItem>): CallDetail? {
        val items = splitLabels(raw.filter { it.text.isNotBlank() })
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
        return CallDetail(price, origin, destination, ride, pickupKm, hasVia(items))
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
        return CallDetail(price, origin, destination, rideMin, Extract.km(move.text), hasVia(items))
    }

    /**
     * 지도 검색용으로 점점 짧게: "수원시 권선구 호매실동 호매실능실마을19단지아파트"
     *  → [전체, "수원시 권선구 호매실동", "수원시 권선구", ...] 
     */
    private fun hasVia(items: List<TextItem>) = items.any { it.text.contains("경유") }

    /**
     * 내비 검색용으로 주소 다듬기:
     * - 결제 메모(/카드, /현금) 빼기, 앞의 "2)" 같은 번호 빼기
     * - 특수 문자(♥ @ \\ / | # * ~ ^ ( ) = 등) 빼기, 점·쉼표는 띄어쓰기로
     * - 아파트 동·호수(114동), 층, "근처" 빼기, 단어 끝의 부근·근처·건너편·맞은편 떼기
     * - 도로명과 번지 띄우기 (강촌로191 → 강촌로 191), 혼자 남은 1~2자리 숫자 빼기
     * - 같은 단어가 연달아 나오면 하나만
     */
    fun cleanPlace(place: String): List<String> {
        val t = place
            .replace(Regex("""/\s*(카드|현금|계좌|선결제)\S*"""), " ")
            .replace(Regex("""(^|\s)\d{1,2}\)"""), " ")
            .replace(Regex("[.,·]"), " ")
            .replace(Regex("[^가-힣0-9A-Za-z\\s-]"), " ")
        val skip = Regex("""^(\d+-?\d*(동|호|층)|근처|부근|인근|-+|카드|현금|카드결제|현금결제)$""")
        val road = Regex("""^([가-힣0-9]*[가-힣](?:로|길))(\d+(?:-\d+)?)$""")
        val near = Regex("""(부근|근처|인근|건너편|맞은편)$""")
        val out = mutableListOf<String>()
        for (raw in t.split(Regex("\\s+"))) {
            if (raw.isBlank() || skip.matches(raw)) continue
            var w = raw
            near.find(w)?.let { m -> if (m.range.first >= 2) w = w.substring(0, m.range.first) }
            val r = road.find(w)
            if (r != null) { out += r.groupValues[1]; out += r.groupValues[2]; continue }
            // 혼자 남은 1~2자리 숫자 (목록 번호, "(0~" 같은 찌꺼기). 도로명 뒤 건물 번호는 위에서 처리
            if (Regex("""^\d{1,2}$""").matches(w) && out.lastOrNull()?.let { it.endsWith("로") || it.endsWith("길") } != true) continue
            out += w
        }
        return out.filterIndexed { i, w -> i == 0 || w != out[i - 1] }
    }

    /** 지역 이름 (시·군·구·읍·면·동·리·가). "금호동3가"처럼 숫자가 들어간 것도 */
    private val regionWord = Regex("""^[가-힣][가-힣0-9]{0,5}(특별시|광역시|시|군|구|읍|면|동|리|가)$""")
    private val numberish = Regex("""^\d+(-\d+)?$""")

    /** 붙여 쓴 건물 이름 앞의 지역 이름 떼기: 의정부장암동더샵포레스트 → 더샵포레스트 (시 이름이 붙은 경우만) */
    private fun stripRegionPrefix(word: String, region: List<String>): String {
        var s = word
        var cut = false
        for (r in region) {
            val stem = Regex("(특별시|광역시|시|군|구)$").replace(r, "")
            if (stem != r && stem.length >= 2 && s.startsWith(stem) && s.length - stem.length >= 2) { s = s.removePrefix(stem); cut = true }
        }
        if (cut) for (r in region) if (s.startsWith(r) && s.length - r.length >= 2) s = s.removePrefix(r)
        return s
    }

    /**
     * 지도 검색어 순서:
     *  ① 다듬은 전체  ② 지역 + 건물 이름(마지막 단어)  ③ 뒤에서부터 한 단어씩 줄이기 (동 이름까지)
     *  예) "시흥시 은행동 시흥은행동661.한양수자인@" → "시흥시 은행동 시흥은행동661 한양수자인", "시흥시 은행동 한양수자인", ...
     */
    /** 건물 이름으로 쓰면 안 되는 흔한 말 (서교동 상가주차장 → 아무 주차장이나 나옴) */
    private val generic = Regex("""^(사옥|본사|본점|정문|후문|입구|출구|상가|본관|별관|로비|(상가|지하|지상|옥외|공영|야외)?주차장)$""")

    /** 동 이름까지만 있고 건물·번지가 없는 주소 ("금천구 독산동 독산동") → 위치가 대략 */
    fun isAreaOnly(place: String): Boolean {
        val w = cleanPlace(place)
        return w.isNotEmpty() && w.all { regionWord.matches(it) }
    }

    /** 앞에 나온 지역 이름을 품은 지역 단어 빼기: "곡반정동 수원곡반정동" → "곡반정동" */
    private fun dropEchoes(words: List<String>): List<String> = words.filterIndexed { i, w ->
        !(regionWord.matches(w) && words.take(i).any { r -> regionWord.matches(r) && r != w && w.endsWith(r) })
    }

    fun searchQueries(place: String): List<String> {
        val words = dropEchoes(cleanPlace(place)).let { l -> l.filterIndexed { i, w -> i == 0 || w != l[i - 1] } }
        if (words.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += words.joinToString(" ")
        val region = words.takeWhile { regionWord.matches(it) }
        val building = words.lastOrNull { !regionWord.matches(it) && !numberish.matches(it) && !generic.matches(it) }
        if (region.isNotEmpty() && building != null) out += (region + stripRegionPrefix(building, region)).joinToString(" ")
        for (n in words.size - 1 downTo 1) out += words.take(n).joinToString(" ")
        return out.toList()
    }

    /**
     * 번지·도로명 주소면 주소 → 위치 검색용 글자 (지역 + 도로명 + 번호). 번호가 없으면 null.
     *  "도봉구 쌍문동 쌍문동494-22.청한가든빌라/카드" → "도봉구 쌍문동 494-22"
     *  "고양시 일산동구 마두동 일산마두동 강촌로191" → "고양시 일산동구 마두동 강촌로 191"
     */
    fun addressQuery(place: String): String? {
        val merged = Regex("""^([가-힣][가-힣0-9]*(?:동|가|리))(\d+(?:-\d+)?)$""")
        val out = mutableListOf<String>()
        var hasNumber = false
        for (w in cleanPlace(place)) {
            val m = merged.find(w)
            val parts = if (m != null) listOf(m.groupValues[1], m.groupValues[2]) else listOf(w)
            for (p in parts) when {
                numberish.matches(p) -> { if (out.isNotEmpty() && !hasNumber) { out += p; hasNumber = true } }
                hasNumber -> {}
                regionWord.matches(p) -> if (out.none { it == p || p.endsWith(it) }) out += p // 시흥은행동·일산마두동 같은 겹친 이름 빼기
                p.endsWith("로") || p.endsWith("길") -> out += p
            }
        }
        return if (hasNumber && out.size >= 2) out.joinToString(" ") else null
    }
}
