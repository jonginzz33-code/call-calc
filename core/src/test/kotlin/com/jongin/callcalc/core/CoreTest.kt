package com.jongin.callcalc.core

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExprTest {
    private val v1 = mapOf('A' to 4.1, 'B' to 72.0, 'C' to 24000.0)

    private fun calc(e: String, vars: Map<Char, Double> = v1): Double =
        (Expr.parse(e).eval(vars) as CalcResult.Value).v

    @Test fun precedenceAndParens() {
        assertEquals(7.0, calc("1 + 2 * 3"))
        assertEquals(9.0, calc("(1 + 2) * 3"))
        assertEquals(-1.0, calc("-(2 - 1)"))
        assertEquals(2.0, calc("8 / 2 / 2"))
        assertEquals(1.5, calc("3 ÷ 2"))
        assertEquals(6.0, calc("3 × 2"))
        assertEquals(24000.0 / 76.1, calc("c / (a + b)"), 1e-9)
    }

    @Test fun divideByZeroIsImpossible() {
        assertEquals(CalcResult.Impossible, Expr.parse("C / (A - A)").eval(v1))
    }

    @Test fun invalidFormulasAreRejected() {
        for (bad in listOf("", "C /", "(A + B", "A + B)", "C / D", "A ** B", "1..2", "* A")) {
            assertNotNull(Expr.validate(bad), "should reject: '$bad'")
        }
        assertNull(Expr.validate("C / (A + B)"))
        assertNull(Expr.validate("C/(A+B)*1.5 - 100"))
    }
}

class ExtractTest {
    @Test fun numbers() {
        assertEquals(4.1, Extract.km("4.1km"))
        assertEquals(72.0, Extract.minutes("오픈콜 | 72분"))
        assertEquals(72.0, Extract.minutes("1시간 12분"))
        assertEquals(120.0, Extract.minutes("2시간"))
        assertEquals(24000.0, Extract.money("24,000"))
        assertEquals(24000.0, Extract.money("2.4만"))
        assertEquals(1234567.0, Extract.money("1,234,567"))
        assertNull(Extract.money("용인시 수지구"))
    }

    @Test fun ocrFixOnlyInsideNumbers() {
        assertEquals("4.1km", Extract.fixOcr("4.lkm"))
        assertEquals("24,000", Extract.fixOcr("24,O00"))
        assertEquals("58분", Extract.fixOcr("5B분"))
        assertEquals("15분", Extract.fixOcr("1S분"))
        // 숫자가 없는 글자, 영어 단어는 그대로
        assertEquals("OK SBS", Extract.fixOcr("OK SBS"))
        assertEquals("Bus 10", Extract.fixOcr("Bus 10"))
    }
}

class CallParserTest {
    @Test fun sampleScreenGivesThreeCalls() {
        val rows = CallParser.parse(Sample.screen())
        assertEquals(3, rows.size, rows.toString())
        assertEquals(listOf(4.1, 8.8, 5.3), rows.map { it.a })
        assertEquals(listOf(72.0, 45.0, 11.0), rows.map { it.b })
        assertEquals(listOf(24000.0, 22400.0, 12000.0), rows.map { it.c })
        assertEquals(listOf("고잔동", "조남동", "이동"), rows.map { it.origin })
        assertEquals(listOf("카드", "현금", "현금"), rows.map { it.payment })
        assertTrue(rows[1].destination.startsWith("부천시 원미구 심곡동"), rows[1].destination)
    }

    @Test fun manyCallsAreAllFound() {
        val many = List(25) { Sample.calls[it % 3] }
        assertEquals(25, CallParser.parse(Sample.screen(many)).size)
    }

    @Test fun mergedLinesStillWork() {
        // OCR이 한 줄을 통째로 묶어 준 경우
        val items = listOf(
            TextItem("4.1km 용인시 수지구 동천동 … 24,000", 30, 300, 1050, 345),
            TextItem("고잔동 오픈콜 | 1시간 12분 카드", 30, 360, 1050, 395),
        )
        val r = CallParser.parse(items).single()
        assertEquals(4.1, r.a); assertEquals(72.0, r.b); assertEquals(24000.0, r.c)
        assertEquals("고잔동", r.origin)
        assertTrue(r.destination.startsWith("용인시 수지구 동천동"), r.destination)
    }

    @Test fun missingValueIsReadFailure() {
        val items = listOf(
            TextItem("4.1km", 30, 300, 150, 345),
            TextItem("24,000", 880, 300, 1050, 345),
            TextItem("고잔동", 30, 360, 150, 395), // 시간이 잘려서 안 보임
        )
        val r = CallParser.parse(items).single()
        assertNull(r.b)
        val out = Evaluator.evaluate(listOf(r), EvalSettings())
        assertEquals(RowStatus.READ_FAIL, out.rows.single().status)
        assertEquals("읽기 실패", out.rows.single().valueText)
    }
}

class EvaluatorTest {
    private val rows = CallParser.parse(Sample.screen())

    private fun values(expr: String) =
        Evaluator.evaluate(rows, EvalSettings(formulaExpr = expr)).rows.map { it.value!!.roundToLong() }

    @Test fun expectedResultsFromSpec() {
        assertEquals(listOf(315L, 416L, 736L), values("C / (A + B)"))
        assertEquals(listOf(333L, 498L, 1091L), values("C / B"))
        assertEquals(listOf(5854L, 2545L, 2264L), values("C / A"))
        val text = Evaluator.evaluate(rows, EvalSettings(formulaExpr = "C / B")).rows.map { it.valueText }
        assertEquals(listOf("333", "498", "1,091"), text)
    }

    @Test fun bestThresholdAndRegions() {
        val words = listOf(
            RegionWord("안산", RegionColor.RED, "가지 말 것"),
            RegionWord("부천", RegionColor.YELLOW),
            RegionWord("심곡동", RegionColor.GREEN),
        )
        val out = Evaluator.evaluate(rows, EvalSettings(threshold = 400.0, words = words)).rows
        // 3번(안산, 736)이 최고지만 빨강이라 제외 → 2번(416)이 제일 좋음
        assertEquals(listOf(false, true, false), out.map { it.best })
        assertEquals(listOf(RowStatus.BELOW, RowStatus.OK, RowStatus.OK), out.map { it.status })
        assertEquals(listOf(null, RegionColor.YELLOW, RegionColor.RED), out.map { it.regionColor })
        assertEquals(2, out[1].matched.size) // 부천 + 심곡동, 노랑이 먼저
        assertEquals("736", out[2].valueText) // 기본: 보여 주고 경고

        val hidden = Evaluator.evaluate(rows, EvalSettings(words = words, hideRedValue = true)).rows
        assertEquals("—", hidden[2].valueText)
    }

    @Test fun originCheckAndMinPrice() {
        val words = listOf(RegionWord("고잔", RegionColor.RED))
        assertNull(Evaluator.evaluate(rows, EvalSettings(words = words)).rows[0].regionColor)
        assertEquals(RegionColor.RED, Evaluator.evaluate(rows, EvalSettings(words = words, checkOrigin = true)).rows[0].regionColor)

        val out = Evaluator.evaluate(rows, EvalSettings(minPrice = 20000.0))
        assertEquals(2, out.rows.size)
        assertEquals(1, out.hiddenByMinPrice)
    }

    @Test fun badFormulaShowsImpossible() {
        val out = Evaluator.evaluate(rows, EvalSettings(formulaExpr = "C / (A - A)")).rows
        assertTrue(out.all { it.status == RowStatus.IMPOSSIBLE && it.valueText == "계산 불가" })
        assertTrue(out.none { it.best })
    }
}
