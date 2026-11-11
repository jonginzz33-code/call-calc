package com.jongin.callcalc.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeterTest {
    @Test fun metersAreDistance() {
        assertEquals(0.753, Extract.km("753m"))
        assertEquals(5.3, Extract.km("5.3km"))
        assertNull(Extract.km("마포구 공덕동"))
        // 사용자 화면: 두 번째 콜 753m · 39분 · 20,000 → 픽업 2분 → 20000/41 = 488
        val items = listOf(
            TextItem("753m", 52, 605, 165, 650), TextItem("화성시 효행구 매송면 …", 297, 597, 700, 642),
            TextItem("20,000", 728, 597, 870, 642), TextItem("신길동", 52, 660, 163, 705),
            TextItem("오픈콜 | 39분", 297, 660, 530, 705), TextItem("현금", 797, 660, 870, 705),
        )
        val r = CallParser.parse(items).single()
        assertEquals(0.753, r.a)
        assertEquals("488", Evaluator.evaluate(listOf(r), EvalSettings()).rows.single().valueText)
    }
}

class StatsTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun workDayIs5pmTo6am() {
        assertEquals(LocalDate.of(2026, 11, 11), WorkDay.of(at(11, 23), zone, 17, 6))
        assertEquals(LocalDate.of(2026, 11, 11), WorkDay.of(at(12, 3), zone, 17, 6))
        assertNull(WorkDay.of(at(12, 14), zone, 17, 6))
        assertEquals(LocalDate.of(2026, 11, 12), WorkDay.of(at(12, 17), zone, 17, 6))
    }

    @Test fun duplicatesAreCountedOnce() {
        val rows = CallParser.parse(Sample.screen())
        val log = CallLog()
        rows.forEach { assertTrue(log.add(it, at(11, 20))) }
        // 스크롤해서 다시 보임, 글자를 조금 다르게 읽음 → 같은 콜
        assertFalse(log.add(rows[0].copy(destination = "용인시 수지구 동천"), at(11, 20, 3)))
        // 금액이 바뀌면 새 콜
        assertTrue(log.add(rows[0].copy(c = 25000.0), at(11, 20, 4)))
        // 한참 뒤 같은 콜 → 새 콜
        assertTrue(log.add(rows[1], at(11, 22)))
        val st = log.stats(LocalDate.of(2026, 11, 11), zone, 17, 6, EvalSettings())
        assertEquals(5, st.count)
        assertEquals(545L, st.max!!.roundToLong())
        assertEquals(300L, st.min!!.roundToLong())
    }

    @Test fun similarPlaces() {
        assertTrue(CallLog.similar("신월5동", "신월동"))
        assertTrue(CallLog.similar("마포구 공덕동 모든오…", "마포구 공덕동 모든오피스"))
        assertFalse(CallLog.similar("고잔동", "초지동동동"))
    }
}

class DetailTest {
    // 사용자가 보낸 상세 화면 (923 폭 기준)
    private val screen = listOf(
        TextItem("티맵콜", 220, 760, 325, 805),
        TextItem("카드", 222, 870, 300, 915), TextItem("실수익", 222, 922, 302, 958),
        TextItem("17,600", 340, 870, 625, 955),
        TextItem("이동", 220, 1100, 285, 1140), TextItem("도보 69분", 340, 1090, 575, 1150), TextItem("약 5.4km", 598, 1095, 805, 1148),
        TextItem("출발", 220, 1235, 285, 1275), TextItem("초지동 한신포차 안산초지점", 340, 1225, 985, 1285),
        TextItem("약 40분 운행", 340, 1340, 615, 1395),
        TextItem("도착", 220, 1458, 285, 1498), TextItem("수원시 권선구 호매실동", 340, 1450, 880, 1508),
        TextItem("호매실능실마을19단지아파트", 340, 1520, 1010, 1578),
        TextItem("닫기", 300, 1715, 390, 1770), TextItem("콜 수락하기", 650, 1715, 880, 1770),
    )

    @Test fun readsDetailScreen() {
        val d = assertNotNull(DetailParser.parse(screen))
        assertEquals(17600.0, d.price)
        assertEquals("초지동 한신포차 안산초지점", d.origin)
        assertEquals("수원시 권선구 호매실동 호매실능실마을19단지아파트", d.destination)
        assertEquals(40.0, d.rideMin)
        assertEquals(5.4, d.pickupKm)
    }

    @Test fun readsMergedLabelLines() {
        // 글자 인식이 "출발 + 주소"를 한 줄로 붙여 읽은 경우 (사용자 화면)
        val items = listOf(
            TextItem("현금", 222, 945, 300, 985), TextItem("실수익", 222, 995, 302, 1030),
            TextItem("16,000", 340, 870, 625, 955),
            TextItem("이동 도보 44분 약 3.5km", 220, 1090, 810, 1150),
            TextItem("출발 선부동 선부3동주민센터", 220, 1225, 900, 1285),
            TextItem("약 27분 운행", 340, 1340, 605, 1395),
            TextItem("도착 안양시 만안구 박달동 박달동", 220, 1450, 1000, 1508),
            TextItem("대림한숲타운아파트", 340, 1520, 800, 1578),
            TextItem("닫기", 300, 1715, 390, 1770), TextItem("콜 수락하기", 650, 1715, 880, 1770),
        )
        val d = assertNotNull(DetailParser.parse(items))
        assertEquals(16000.0, d.price)
        assertEquals("선부동 선부3동주민센터", d.origin)
        assertEquals("안양시 만안구 박달동 박달동 대림한숲타운아파트", d.destination)
        assertEquals(27.0, d.rideMin)
        assertEquals(3.5, d.pickupKm)
    }

    @Test fun readsStackedDetail() {
        // 두 번째 모양: 사용자가 보낸 화면 1
        val a = listOf(
            TextItem("제휴", 550, 435, 698, 510),
            TextItem("도보 1.7km / 약 34분", 318, 605, 912, 675),
            TextItem("시흥시 거모동 고구려", 418, 712, 830, 765),
            TextItem("♥ 시흥시 거모동 시흥군자우체국", 300, 898, 945, 955),
            TextItem("약 6분 운행", 512, 1068, 738, 1120),
            TextItem("카드 | 확정", 320, 1275, 470, 1320), TextItem("실제수익", 520, 1278, 640, 1318),
            TextItem("12,000", 665, 1260, 912, 1335),
            TextItem("닫기", 305, 1520, 390, 1575), TextItem("수락", 698, 1520, 788, 1575),
        )
        val d1 = assertNotNull(DetailParser.parse(a))
        assertEquals(12000.0, d1.price)
        assertEquals("시흥시 거모동 고구려", d1.origin)
        assertEquals("시흥시 거모동 시흥군자우체국", d1.destination)
        assertEquals(6.0, d1.rideMin)
        assertEquals(1.7, d1.pickupKm)

        // 화면 2: 출발지가 두 줄, 꺾쇠가 "V" 로 읽힘
        val b = listOf(
            TextItem("제휴", 550, 400, 698, 475),
            TextItem("도보 2.3km / 약 45분", 318, 572, 912, 640),
            TextItem("안산시단원구 원곡동", 422, 678, 826, 732),
            TextItem("안산다문화이주민플러스센터", 342, 745, 906, 800),
            TextItem("V", 592, 848, 656, 885),
            TextItem("안산시상록구 본오동 본오동 772-3", 280, 932, 966, 990),
            TextItem("약 22분 운행", 498, 1100, 750, 1155),
            TextItem("현금 | 확정", 320, 1310, 470, 1355), TextItem("16,000", 665, 1295, 912, 1370),
            TextItem("닫기", 305, 1555, 390, 1610), TextItem("수락", 698, 1555, 788, 1610),
        )
        val d2 = assertNotNull(DetailParser.parse(b))
        assertEquals(16000.0, d2.price)
        assertEquals("안산시단원구 원곡동 안산다문화이주민플러스센터", d2.origin)
        assertEquals("안산시상록구 본오동 본오동 772-3", d2.destination)
        assertEquals(22.0, d2.rideMin)
        assertEquals(2.3, d2.pickupKm)
    }

    @Test fun listScreenIsNotDetail() {
        assertNull(DetailParser.parse(Sample.screen()))
    }

    @Test fun apartmentBuildingNumberIsDropped() {
        assertEquals("남양주시 호평동 호평동양파라곤아파트",
            DetailParser.searchQueries("남양주시 호평동 호평동양파라곤아파트 114동").first())
        assertEquals("수원시 권선구 호매실동 호매실로166번길",
            DetailParser.searchQueries("수원시 권선구 호매실동 호매실동 호매실로166번길 근처").first())
        // 동 이름(호평동)은 그대로
        assertTrue(DetailParser.searchQueries("남양주시 호평동").first().contains("호평동"))
    }

    @Test fun specialCharactersAreRemoved() {
        val q1 = DetailParser.searchQueries("시흥시 은행동 시흥은행동661.한양수자인@")
        assertEquals("시흥시 은행동 시흥은행동661 한양수자인", q1[0])
        assertEquals("시흥시 은행동 한양수자인", q1[1])   // 지역 + 건물 이름
        assertEquals("영종구 운남동 영종베르힐스카이시티아파트",
            DetailParser.searchQueries("영종구 운남동 영종베르힐스카이시티아파트 \\").first())
        assertEquals("시흥시 하중동 하중동관곡마을동아",
            DetailParser.searchQueries("시흥시 하중동 ♥하중동관곡마을동아@").first())
        assertEquals("거모동 1753-3", DetailParser.searchQueries("거모동 1753-3").first())
    }

    @Test fun viaIsDetected() {
        val items = listOf(
            TextItem("20,000", 340, 870, 625, 955),
            TextItem("출발", 220, 1235, 285, 1275), TextItem("거모동 1753-3", 340, 1225, 700, 1285),
            TextItem("약 21분 운행 | 경유지 1", 340, 1340, 810, 1395),
            TextItem("도착", 220, 1458, 285, 1498), TextItem("시흥시 하중동", 340, 1450, 660, 1508),
            TextItem("♥하중동관곡마을동아@", 340, 1520, 905, 1578),
            TextItem("콜 수락하기", 650, 1715, 880, 1770),
        )
        val d = assertNotNull(DetailParser.parse(items))
        assertTrue(d.hasVia)
        assertEquals(21.0, d.rideMin)
        assertEquals("거모동 1753-3", d.origin)
    }

    @Test fun searchGetsShorter() {
        assertEquals(
            listOf("수원시 권선구 호매실동 호매실능실마을19단지아파트", "수원시 권선구 호매실동", "수원시 권선구", "수원시"),
            DetailParser.searchQueries("수원시 권선구 호매실동 호매실능실마을19단지아파트"),
        )
    }
}
