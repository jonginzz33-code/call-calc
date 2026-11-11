package com.jongin.callcalc.core

/** 설명서 2절의 예시 화면 3줄 */
object Sample {
    data class Call(val a: String, val origin: String, val dest: String, val b: String, val c: String, val pay: String)

    val calls = listOf(
        Call("4.1km", "고잔동", "용인시 수지구 동천동 …", "오픈콜 | 72분", "24,000", "카드"),
        Call("8.8km", "조남동", "부천시 원미구 심곡동 …", "오픈콜 | 45분", "22,400", "현금"),
        Call("5.3km", "이동", "안산시 단원구 초지동 …", "오픈콜 | 11분", "12,000", "현금"),
    )

    /** 실제 화면처럼 위치를 붙인 글자 덩어리 (1080 폭 화면) */
    fun screen(list: List<Call> = calls, startY: Int = 300, gap: Int = 150): List<TextItem> {
        val items = mutableListOf(
            TextItem("안산시 단원구 원곡동", 30, 90, 600, 130),   // 현재 위치 (콜 아님)
            TextItem("10km | 금액순", 30, 200, 330, 240),          // 필터 (콜 아님)
        )
        list.forEachIndexed { i, c ->
            val y = startY + i * gap
            items += TextItem(c.a, 30, y, 150, y + 45)
            items += TextItem(c.dest, 200, y, 800, y + 45)
            items += TextItem(c.c, 880, y - 5, 1050, y + 50)
            items += TextItem(c.origin, 30, y + 60, 150, y + 95)
            items += TextItem(c.b, 200, y + 60, 450, y + 95)
            items += TextItem(c.pay, 950, y + 60, 1050, y + 95)
        }
        return items.shuffled(java.util.Random(7)) // 순서가 섞여 들어와도 되는지 확인
    }
}
