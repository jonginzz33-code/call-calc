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
    }

    @Test fun listScreenIsNotDetail() {
        assertNull(DetailParser.parse(Sample.screen()))
    }

    @Test fun searchGetsShorter() {
        assertEquals(
            listOf("수원시 권선구 호매실동 호매실능실마을19단지아파트", "수원시 권선구 호매실동", "수원시 권선구", "수원시"),
            DetailParser.searchQueries("수원시 권선구 호매실동 호매실능실마을19단지아파트"),
        )
    }
}
