package com.jongin.callcalc.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 사용자가 보낸 수락 창(오다창) 7장으로 만든 시험 */
class OrderScreenTest {
    private fun screen(price: String, move: String, origin: List<String>, ride: String, dest: List<String>): List<TextItem> {
        val out = mutableListOf(
            TextItem("현금", 52, 880, 136, 925), TextItem("실수익", 52, 935, 136, 970),
            TextItem(price, 175, 880, 475, 970), TextItem("P", 500, 900, 560, 955),
            TextItem("이동", 52, 1115, 116, 1160), TextItem(move, 175, 1110, 650, 1170),
            TextItem("출발", 52, 1250, 116, 1295),
        )
        var y = 1245
        for (o in origin) { out += TextItem(o, 175, y, 700, y + 60); y += 72 }
        out += TextItem(ride, 175, y + 10, 450, y + 65); y += 110
        out += TextItem("도착", 52, y + 5, 116, y + 50)
        for (d in dest) { out += TextItem(d, 175, y, 800, y + 60); y += 72 }
        out += TextItem("닫기", 140, 1740, 225, 1800)
        out += TextItem("콜 수락하기", 490, 1740, 720, 1800)
        // 지도 위 글자 (주소로 착각하면 안 됨)
        out += TextItem("출발", 410, 495, 470, 530); out += TextItem("도착", 455, 230, 510, 265)
        out += TextItem("약 69분 운행", 400, 160, 565, 200)
        return out
    }

    @Test fun readsAllSeven() {
        val a = assertNotNull(DetailParser.parse(screen("24,000", "도보 90분 약 6.7km", listOf("금호동3가 420-1"), "약 69분 운행",
            listOf("의정부시 장암동", "의정부장암동더샵포레스트"))))
        assertEquals(24000.0, a.price); assertEquals(6.7, a.pickupKm); assertEquals(69.0, a.rideMin)
        assertEquals("금호동3가 420-1", a.origin)
        assertEquals("의정부시 장암동 의정부장암동더샵포레스트", a.destination)

        val b = assertNotNull(DetailParser.parse(screen("12,000", "1시간 이상 약 5.3km", listOf("갈매동 614-5"), "약 25분 운행",
            listOf("중랑구 면목동 면목역부근(0~"))))
        assertEquals(5.3, b.pickupKm); assertEquals(25.0, b.rideMin)

        val c = assertNotNull(DetailParser.parse(screen("16,000", "1시간 이상 약 6.3km", listOf("공릉동 이안클래식"), "약 33분 운행",
            listOf("도봉구 쌍문동", "쌍문동494-22.청한가든빌라/카드"))))
        assertEquals(16000.0, c.price); assertEquals("공릉동 이안클래식", c.origin)

        val d = assertNotNull(DetailParser.parse(screen("23,200", "1시간 이상 약 9.5km", listOf("풍산동 하남수산물시장", "주차장"), "약 60분 운행",
            listOf("노원구 중계동", "동진신안아파트"))))
        assertEquals(23200.0, d.price); assertEquals("풍산동 하남수산물시장 주차장", d.origin); assertEquals(60.0, d.rideMin)
    }

    @Test fun paymentMemoIsNotABuilding() {
        val q = DetailParser.searchQueries("도봉구 쌍문동 쌍문동494-22.청한가든빌라/카드")
        assertTrue(q.none { it.contains("카드") }, q.toString())
        assertEquals("도봉구 쌍문동 청한가든빌라", q[1])
        assertEquals("도봉구 쌍문동 494-22", DetailParser.addressQuery("도봉구 쌍문동 쌍문동494-22.청한가든빌라/카드"))
    }

    @Test fun nearbyWordsAndNumbersAreTrimmed() {
        assertEquals("중랑구 면목동 면목역", DetailParser.searchQueries("중랑구 면목동 면목역부근(0~").first())
        assertEquals("노원구 중계동 중계동은행사거리", DetailParser.searchQueries("노원구 중계동 2)중계동은행사거리근처=").first())
    }

    @Test fun mergedRegionIsStrippedForBuilding() {
        val q = DetailParser.searchQueries("의정부시 장암동 의정부장암동더샵포레스트")
        assertEquals("의정부시 장암동 의정부장암동더샵포레스트", q[0])
        assertEquals("의정부시 장암동 더샵포레스트", q[1])
        // 동양파라곤처럼 '동'이 이름의 일부면 떼지 않음
        assertTrue(DetailParser.searchQueries("남양주시 호평동 호평동양파라곤아파트").none { it.endsWith(" 양파라곤아파트") })
    }

    @Test fun roadNumberIsSplit() {
        assertEquals("고양시 일산동구 마두동 강촌로 191", DetailParser.searchQueries("고양시 일산동구 마두동 일산마두동 강촌로191").first())
        assertEquals("고양시 일산동구 마두동 강촌로 191", DetailParser.addressQuery("고양시 일산동구 마두동 일산마두동 강촌로191"))
    }

    @Test fun jibunAddressesUseGeocoding() {
        assertEquals("금호동3가 420-1", DetailParser.addressQuery("금호동3가 420-1"))
        assertEquals("갈매동 614-5", DetailParser.addressQuery("갈매동 614-5"))
        assertEquals("풍산동 246-3", DetailParser.addressQuery("풍산동 246-3"))
        assertNull(DetailParser.addressQuery("공릉동 이안클래식"))
        assertNull(DetailParser.addressQuery("노원구 중계동 동진신안아파트"))
        assertFalse(DetailParser.searchQueries("금호동3가 420-1").isEmpty())
    }

    // ── 두 번째 묶음 (10장) ──

    @Test fun secondBatchReads() {
        val a = assertNotNull(DetailParser.parse(screen("40,000", "1시간 이상 약 6.8km", listOf("청담동 용강빌딩"), "약 77분 운행",
            listOf("수원시 권선구 곡반정동", "수원곡반정동,하나로마트"))))
        assertEquals(40000.0, a.price); assertEquals(6.8, a.pickupKm); assertEquals(77.0, a.rideMin)
        val b = assertNotNull(DetailParser.parse(screen("16,000", "1시간 이상 약 9.4km", listOf("동선동4가", "서울미래새마을금고본사 사옥"), "약 17분 운행",
            listOf("성북구 정릉동 정릉동청수장"))))
        assertEquals("동선동4가 서울미래새마을금고본사 사옥", b.origin)
        val c = assertNotNull(DetailParser.parse(screen("18,400", "1시간 이상 약 6.1km", listOf("서교동 메세나폴리스", "상가주차장"), "약 36분 운행",
            listOf("구로구 오류동 이좋은집"))))
        assertEquals(18400.0, c.price); assertEquals("서교동 메세나폴리스 상가주차장", c.origin)
    }

    @Test fun genericWordsAreNotBuildings() {
        val q1 = DetailParser.searchQueries("동선동4가 서울미래새마을금고본사 사옥")
        assertTrue(q1.none { it == "동선동4가 사옥" }, q1.toString())
        assertEquals("동선동4가 서울미래새마을금고본사", q1[1])
        val q2 = DetailParser.searchQueries("서교동 메세나폴리스 상가주차장")
        assertTrue(q2.none { it == "서교동 상가주차장" }, q2.toString())
        assertEquals("서교동 메세나폴리스", q2[1])
    }

    @Test fun echoesAndNearbyWords() {
        assertEquals("파주시 야당동 야당역", DetailParser.searchQueries("파주시 야당동 야당역인근").first())
        assertEquals("수원시 권선구 곡반정동 하나로마트", DetailParser.searchQueries("수원시 권선구 곡반정동 수원곡반정동,하나로마트").first())
        assertEquals("송파구 방이동 오륜동올림픽", DetailParser.searchQueries("송파구 방이동 송파오륜동올림픽@")[1])
    }

    @Test fun areaOnlyIsDetected() {
        assertTrue(DetailParser.isAreaOnly("금천구 독산동 독산동"))
        assertFalse(DetailParser.isAreaOnly("구로구 오류동 이좋은집"))
        assertFalse(DetailParser.isAreaOnly("갈매동 614-5"))
    }

    // ── 세 번째·네 번째 묶음 (14장) ──

    @Test fun thirdBatch() {
        // 현석동 17: 동 뒤 숫자는 번지
        assertEquals("현석동 17", DetailParser.addressQuery("현석동 17"))
        assertEquals("현석동 17", DetailParser.searchQueries("현석동 17").first())
        // 붙은 104동 떼기
        assertEquals("서대문구 천연동 서대문천연뜨란채", DetailParser.searchQueries("서대문구 천연동 서대문천연뜨란채104동").first())
        assertTrue(DetailParser.searchQueries("서대문구 천연동 서대문천연뜨란채104동").contains("서대문구 천연동 천연뜨란채"))
        // 동 이름(화곡1동)은 그대로
        assertEquals("강서구 화곡1동", DetailParser.searchQueries("강서구 화곡1동").first())
        // 겹친 지역 이름
        assertEquals("서울특별시 강서구 방화동 831", DetailParser.addressQuery("강서구 방화동 서울특별시 강서구 방화동 831"))
        assertEquals("서울특별시 강서구 방화동 831", DetailParser.searchQueries("강서구 방화동 서울특별시 강서구 방화동 831").first())
        // 춘천.강촌로393
        assertEquals("춘천시 남산면 강촌로 393", DetailParser.addressQuery("춘천시 남산면 춘천.강촌로393"))
        assertTrue(DetailParser.searchQueries("춘천시 남산면 춘천.강촌로393").none { it.contains("춘천 ") || it.endsWith(" 춘천") })
        // 검단구: 동 + 건물로도
        assertTrue(DetailParser.searchQueries("검단구 원당동 혜정빌 1동").contains("원당동 혜정빌"))
        // 강서로5가길24
        assertEquals("강서구 화곡동 강서로5가길 24", DetailParser.addressQuery("강서구 화곡동 강서로5가길24"))
    }

    @Test fun fourthBatch() {
        // 뚝섬역[2호선]: 호선은 꼭 같이 (환승역은 출입구가 멀어서)
        val q = DetailParser.searchQueries("성동구 성수동1가 뚝섬역[2호선]")
        assertEquals("성동구 성수동1가 뚝섬역 2호선", q[0])
        assertTrue(q.none { it == "성동구 성수동1가 2호선" }, q.toString())
        // 띄어 쓴 건물 이름은 통째로
        assertTrue(DetailParser.searchQueries("성수동1가 메가박스 스퀘어").none { it == "성수동1가 스퀘어" })
        assertTrue(DetailParser.searchQueries("행당동 오늘보쌈칼국수 왕십리본점").none { it == "행당동 왕십리본점" })
        // 구 이름 조각 떼기
        assertTrue(DetailParser.searchQueries("금천구 독산동 금천가산초교근처").contains("금천구 독산동 가산초교"))
        assertEquals("하안동 890", DetailParser.addressQuery("하안동 890"))
        assertEquals("서대문구 홍은동 홍은동벽산아파트", DetailParser.searchQueries("서대문구 홍은동 +홍은동벽산아파트").first())
        assertEquals("석수동 석수두산위브", DetailParser.searchQueries("석수동 석수두산위브 106동").first())
    }

    // ── 엉뚱한 동네 결과 걸러 내기 ──
    @Test fun picksResultInTheSameDong() {
        // 청담동 용강빌딩: 1등이 다른 동네(여리수)면 버리고 청담동 결과
        assertEquals(1, DetailParser.pickPlace("청담동 용강빌딩", listOf("가평군" to "청평면", "강남구" to "청담동")))
        // 하나도 안 맞으면 못 찾음
        assertNull(DetailParser.pickPlace("양평동4가 영등포세무서", listOf("양평군" to "양평읍", "수원시 권선구" to "탑동")))
        // 양평동4가 ↔ 양평동4가, 성수동1가
        assertEquals(0, DetailParser.pickPlace("양평동4가 영등포세무서", listOf("영등포구" to "양평동4가")))
        // 같은 이름 빌라가 여러 동네에: 원당동 것을 고름
        assertEquals(2, DetailParser.pickPlace("검단구 원당동 혜정빌", listOf("부천시" to "원미동", "남양주시" to "평내동", "서구" to "원당동")))
        // 화곡1동(행정동) ↔ 화곡동(법정동)
        assertEquals(0, DetailParser.pickPlace("강서구 화곡1동 우장산역", listOf("강서구" to "화곡동")))
        // 동은 달라도 구가 맞으면 받아 줌
        assertEquals(0, DetailParser.pickPlace("시흥시 목감동 퍼스트리움", listOf("시흥시" to "조남동")))
        // 지역 이름이 없는 검색어는 1등
        assertEquals(0, DetailParser.pickPlace("메가박스", listOf("성동구" to "성수동1가")))
        // 짧은 동 이름(신동)으로 신정동을 잘못 고르지 않음
        assertNull(DetailParser.pickPlace("신동 어린이집", listOf("양천구" to "신정동")))
    }
}
