package com.jongin.callcalc.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTest {
    @Test fun slotIsBetweenTimeAndPayment() {
        val r = CallParser.parse(Sample.screen()).first()
        // 예시 화면: "오픈콜 | 72분" 오른쪽 끝 450, "카드" 왼쪽 950, 아랫줄 가운데 y=377
        assertEquals(450, r.slotLeft)
        assertEquals(950, r.slotRight)
        assertEquals(377, r.slotY)
    }

    @Test fun edgeFailuresAreDropped() {
        val items = Sample.screen() + listOf(
            // 맨 아래에 반쯤 잘린 줄 (윗줄만 보임)
            TextItem("3.0km", 30, 750, 150, 795),
            TextItem("15,000", 880, 750, 1050, 795),
        )
        val rows = CallParser.parse(items)
        assertEquals(4, rows.size)
        assertEquals(3, LiveRules.dropEdgeFailures(rows).size)
    }

    @Test fun overlayNumberHasNoComma() {
        assertEquals("1091", LiveRules.overlayNumber(1090.9))
        assertEquals("동천동", LiveRules.shortPlace("용인시 수지구 동천동 …"))
    }

    @Test fun seenMemoryKeepsBestAcrossScroll() {
        val rows = CallParser.parse(Sample.screen())
        val mem = SeenMemory()
        // 처음 화면: 1번, 3번 콜이 보임 → 최고는 3번(736)
        val (o1, s1) = mem.evaluate(listOf(rows[0], rows[2]), EvalSettings(formulaExpr = "C / (A + B)"))
        assertEquals(listOf(false, true), o1.rows.map { it.best })
        assertEquals("최고: 초지동 12,000 (736)", s1)
        // 스크롤 후: 2번만 보임 → 전체 최고는 여전히 3번이라 별표 없음
        val (o2, s2) = mem.evaluate(listOf(rows[1]), EvalSettings(formulaExpr = "C / (A + B)"))
        assertEquals(listOf(false), o2.rows.map { it.best })
        assertEquals("최고: 초지동 12,000 (736)", s2)
        assertEquals(3, mem.size)
        mem.clear()
        val (_, s3) = mem.evaluate(emptyList(), EvalSettings(formulaExpr = "C / (A + B)"))
        assertNull(s3)
    }

    @Test fun seenMemorySkipsRedRegion() {
        val rows = CallParser.parse(Sample.screen())
        val mem = SeenMemory()
        val s = EvalSettings(formulaExpr = "C / (A + B)", words = listOf(RegionWord("안산", RegionColor.RED)))
        val (_, summary) = mem.evaluate(rows, s)
        assertTrue(summary!!.startsWith("최고: 심곡동"), summary)
    }
}

class StickyTest {
    private val rows = CallParser.parse(Sample.screen())

    @Test fun missedRowIsKeptBriefly() {
        val s = StickyRows(1500)
        assertEquals(3, s.apply(rows, 0).size)
        // 다음 읽기에서 3번을 놓침 → 1.5초 안이면 유지
        assertEquals(3, s.apply(rows.take(2), 500).size)
        // 2초 지나면 버림
        assertEquals(2, s.apply(rows.take(2), 2100).size)
    }

    @Test fun replacedRowIsNotKept() {
        val s = StickyRows(1500)
        s.apply(rows, 0)
        // 3번 자리에 다른 콜이 읽힘 → 예전 3번은 버림
        val other = rows[2].copy(c = 99000.0, destination = "다른 곳")
        val out = s.apply(listOf(rows[0], rows[1], other), 300)
        assertEquals(3, out.size)
        assertTrue(out.none { it.c == 12000.0 })
        s.clear()
        assertEquals(1, s.apply(listOf(rows[0]), 400).size)
    }
}
