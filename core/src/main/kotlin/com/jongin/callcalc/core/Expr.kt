package com.jongin.callcalc.core

/**
 * 계산식 파서. 변수 A(픽업 거리 km), B(운행 시간 분), C(금액), P(픽업 시간 분 = A × 1km당 분, 반올림), 숫자, + - * / × ÷, 괄호를 지원한다.
 * 외부 라이브러리 없이 재귀 하향 방식으로 만든다.
 *
 *   식   := 항 (('+' | '-') 항)*
 *   항   := 인수 (('*' | '/') 인수)*
 *   인수 := ('+' | '-') 인수 | 숫자 | 변수 | '(' 식 ')'
 */
class ExprError(message: String) : Exception(message)

/** 계산 결과: 숫자, 또는 0으로 나누는 등 계산 불가 */
sealed class CalcResult {
    data class Value(val v: Double) : CalcResult()
    object Impossible : CalcResult()
}

sealed class Expr {
    data class Num(val v: Double) : Expr()
    data class Var(val name: Char) : Expr()
    data class Neg(val e: Expr) : Expr()
    data class Bin(val op: Char, val l: Expr, val r: Expr) : Expr()

    /** vars: 'A','B','C','P' → 값. 0으로 나누거나 결과가 숫자가 아니면 Impossible */
    fun eval(vars: Map<Char, Double>): CalcResult {
        val v = evalRaw(vars) ?: return CalcResult.Impossible
        return if (v.isNaN() || v.isInfinite()) CalcResult.Impossible else CalcResult.Value(v)
    }

    private fun evalRaw(vars: Map<Char, Double>): Double? = when (this) {
        is Num -> v
        is Var -> vars[name]
        is Neg -> e.evalRaw(vars)?.let { -it }
        is Bin -> {
            val a = l.evalRaw(vars)
            val b = r.evalRaw(vars)
            if (a == null || b == null) null
            else when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                else -> if (b == 0.0) null else a / b
            }
        }
    }

    companion object {
        /** 화면 표기(× ÷, 소문자 변수, 전각 괄호)를 내부 표기로 바꾼다 */
        fun normalize(src: String): String = src
            .replace('×', '*').replace('x', '*').replace('X', '*').replace('÷', '/')
            .replace('（', '(').replace('）', ')')
            .replace('a', 'A').replace('b', 'B').replace('c', 'C').replace('p', 'P')

        /** 식을 해석한다. 잘못된 식이면 ExprError(한국어 설명)를 던진다 */
        fun parse(src: String): Expr {
            val s = normalize(src)
            if (s.isBlank()) throw ExprError("식이 비어 있어요")
            return Parser(s).parseAll()
        }

        /** 저장 전 검사용: 문제가 없으면 null, 있으면 오류 설명 */
        fun validate(src: String): String? = try {
            parse(src); null
        } catch (e: ExprError) {
            e.message
        }
    }
}

private class Parser(val s: String) {
    var i = 0

    fun parseAll(): Expr {
        val e = expr()
        skip()
        if (i < s.length) throw ExprError("${i + 1}번째 글자 '${s[i]}'를 이해할 수 없어요")
        return e
    }

    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun peek(): Char? { skip(); return if (i < s.length) s[i] else null }

    private fun expr(): Expr {
        var left = term()
        while (true) {
            val c = peek()
            if (c == '+' || c == '-') { i++; left = Expr.Bin(c, left, term()) } else return left
        }
    }

    private fun term(): Expr {
        var left = factor()
        while (true) {
            val c = peek()
            if (c == '*' || c == '/') { i++; left = Expr.Bin(c, left, factor()) } else return left
        }
    }

    private fun factor(): Expr {
        val c = peek() ?: throw ExprError("식이 중간에 끝났어요")
        return when {
            c == '-' -> { i++; Expr.Neg(factor()) }
            c == '+' -> { i++; factor() }
            c == '(' -> {
                i++
                val e = expr()
                if (peek() != ')') throw ExprError("닫는 괄호 ')'가 없어요")
                i++
                e
            }
            c == 'A' || c == 'B' || c == 'C' || c == 'P' -> { i++; Expr.Var(c) }
            c.isDigit() || c == '.' -> number()
            c == ')' -> throw ExprError("여는 괄호 없이 ')'가 나왔어요")
            c == '*' || c == '/' -> throw ExprError("'${c}' 앞에 숫자나 변수가 필요해요")
            else -> throw ExprError("'${c}'는 쓸 수 없어요. A, B, C, P, 숫자, + - × ÷, 괄호만 돼요")
        }
    }

    private fun number(): Expr {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        val t = s.substring(start, i)
        val v = t.toDoubleOrNull() ?: throw ExprError("'$t'는 올바른 숫자가 아니에요")
        return Expr.Num(v)
    }
}
