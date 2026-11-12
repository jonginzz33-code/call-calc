package com.jongin.callcalc

import android.content.Context
import com.jongin.callcalc.core.Defaults
import com.jongin.callcalc.core.EvalSettings
import com.jongin.callcalc.core.Formula
import com.jongin.callcalc.core.RegionColor
import com.jongin.callcalc.core.RegionWord
import org.json.JSONArray
import org.json.JSONObject

/** 설정 저장 (SharedPreferences). 계산식과 지역 단어 목록은 JSON 으로 */
class Store(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var formulas: List<Formula>
        get() {
            val raw = p.getString("formulas", null) ?: return Defaults.formulas
            return try {
                val arr = JSONArray(raw)
                List(arr.length()) {
                    val o = arr.getJSONObject(it)
                    Formula(o.getString("id"), o.getString("name"), o.getString("expr"))
                }.ifEmpty { Defaults.formulas }
            } catch (e: Exception) {
                Defaults.formulas
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("expr", it.expr)) }
            p.edit().putString("formulas", arr.toString()).apply()
        }

    var selectedId: String
        get() = p.getString("selectedFormula", Defaults.formulas[0].id)!!
        set(v) = p.edit().putString("selectedFormula", v).apply()

    val selectedFormula: Formula
        get() = formulas.let { list -> list.find { it.id == selectedId } ?: list.first() }

    fun resetFormulas() {
        p.edit().remove("formulas").putString("selectedFormula", Defaults.formulas[0].id).apply()
    }

    /** 픽업 1km당 몇 분 (시간대마다 다름). 기본 2분 */
    var pickupMinPerKm: Double
        get() = p.getString("pickupMinPerKm", "")!!.toDoubleOrNull()?.takeIf { it > 0 } ?: Defaults.PICKUP_MIN_PER_KM
        set(v) = p.edit().putString("pickupMinPerKm", plain(v)).apply()

    /** 기준 단가. 비어 있으면 null (처음엔 비워 둠) */
    var threshold: Double?
        get() = p.getString("threshold", "")!!.toDoubleOrNull()
        set(v) = p.edit().putString("threshold", v?.let { plain(it) } ?: "").apply()

    var minPriceOn: Boolean
        get() = p.getBoolean("minPriceOn", false)
        set(v) = p.edit().putBoolean("minPriceOn", v).apply()

    var minPrice: Double?
        get() = p.getString("minPrice", "")!!.toDoubleOrNull()
        set(v) = p.edit().putString("minPrice", v?.let { plain(it) } ?: "").apply()

    var words: List<RegionWord>
        get() {
            val raw = p.getString("words", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                List(arr.length()) {
                    val o = arr.getJSONObject(it)
                    RegionWord(
                        o.getString("word"),
                        runCatching { RegionColor.valueOf(o.getString("color")) }.getOrDefault(RegionColor.YELLOW),
                        o.optString("memo", ""),
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("word", it.word).put("color", it.color.name).put("memo", it.memo)) }
            p.edit().putString("words", arr.toString()).apply()
        }

    var checkOrigin: Boolean
        get() = p.getBoolean("checkOrigin", false)
        set(v) = p.edit().putBoolean("checkOrigin", v).apply()

    var hideRedValue: Boolean
        get() = p.getBoolean("hideRedValue", false)
        set(v) = p.edit().putBoolean("hideRedValue", v).apply()

    var showSpeed: Boolean
        get() = p.getBoolean("showSpeed", true)
        set(v) = p.edit().putBoolean("showSpeed", v).apply()

    /** 앱을 켤 때 새 버전 자동 확인 */
    var autoUpdate: Boolean
        get() = p.getBoolean("autoUpdate", true)
        set(v) = p.edit().putBoolean("autoUpdate", v).apply()

    // ───── 실시간 모드 ─────

    /** 감시할 앱 (패키지 이름). 이 앱이 화면에 있을 때만 동작 */
    var targetPackages: Set<String>
        get() = p.getStringSet("targetPackages", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("targetPackages", v).apply()

    /** 항상 표시: 감시할 앱과 상관없이 모든 앱 위에서 동작 (테스트용, 예: 갤러리에서 스크린샷 보기) */
    var alwaysOn: Boolean
        get() = p.getBoolean("alwaysOn", false)
        set(v) = p.edit().putBoolean("alwaysOn", v).apply()

    /** 이 앱 화면 위에서 실시간 표시를 할지 */
    fun watches(pkg: String) = alwaysOn || pkg in targetPackages

    /** 읽는 방식: 0 자동(접근성 먼저, 안 되면 화면 캡처) / 1 접근성만 / 2 화면 캡처(OCR)만 */
    var readMode: Int
        get() = p.getInt("readMode", 0)
        set(v) = p.edit().putInt("readMode", v).apply()

    /** 제일 좋음 범위: false 보이는 것 중 최고 / true 지나온 것 전체 중 최고 */
    var bestAllSeen: Boolean
        get() = p.getBoolean("bestAllSeen", false)
        set(v) = p.edit().putBoolean("bestAllSeen", v).apply()

    /** 표시 방식: false 터치 통과(불투명도 80% 이하) / true 진하게(버튼으로 숨기기) */
    var overlaySolid: Boolean
        get() = p.getBoolean("overlaySolid", false)
        set(v) = p.edit().putBoolean("overlaySolid", v).apply()

    /** 불투명도 % (터치 통과 방식은 최대 80) */
    var overlayAlpha: Int
        get() = p.getInt("overlayAlpha", 80)
        set(v) = p.edit().putInt("overlayAlpha", v).apply()

    /** 표시 글자 크기 sp */
    var overlayTextSp: Int
        get() = p.getInt("overlayTextSp", 18)
        set(v) = p.edit().putInt("overlayTextSp", v).apply()

    /** 둥근 버튼으로 켠/끈 상태와 위치 */
    var overlayOn: Boolean
        get() = p.getBoolean("overlayOn", true)
        set(v) = p.edit().putBoolean("overlayOn", v).apply()

    var bubbleX: Int
        get() = p.getInt("bubbleX", -1)
        set(v) = p.edit().putInt("bubbleX", v).apply()

    var bubbleY: Int
        get() = p.getInt("bubbleY", -1)
        set(v) = p.edit().putInt("bubbleY", v).apply()

    /** 앱마다 읽을 영역 (화면 좌표). 없으면 null = 자동 */
    fun region(pkg: String): android.graphics.Rect? {
        val v = p.getString("region_$pkg", null) ?: return null
        val n = v.split(',').mapNotNull { it.toIntOrNull() }
        return if (n.size == 4 && n[2] > n[0] && n[3] > n[1]) android.graphics.Rect(n[0], n[1], n[2], n[3]) else null
    }

    fun setRegion(pkg: String, r: android.graphics.Rect?) {
        val e = p.edit()
        if (r == null) e.remove("region_$pkg") else e.putString("region_$pkg", "${r.left},${r.top},${r.right},${r.bottom}")
        e.apply()
    }

    fun clearAllRegions() {
        val e = p.edit()
        p.all.keys.filter { it.startsWith("region_") }.forEach { e.remove(it) }
        e.apply()
    }

    fun regionCount(): Int = p.all.keys.count { it.startsWith("region_") }

    /** 감시하는 앱이 화면에 있는 동안 화면이 저절로 꺼지지 않게 */
    /** 새로고침 방식: false 터치할 때만 (기본) / true 자동 + 터치 */
    var refreshAuto: Boolean
        get() = p.getBoolean("refreshAuto", false)
        set(v) = p.edit().putBoolean("refreshAuto", v).apply()

    // ───── 좋은 콜 알림 ─────
    var goodAlertOn: Boolean
        get() = p.getBoolean("goodAlertOn", true)
        set(v) = p.edit().putBoolean("goodAlertOn", v).apply()

    /** 분당 이 금액 이상이면 진동 + 소리 (기본 1,000원) */
    var goodAlertMin: Double
        get() = p.getString("goodAlertMin", "")!!.toDoubleOrNull() ?: 1000.0
        set(v) = p.edit().putString("goodAlertMin", plain(v)).apply()

    // ───── 오늘 통계: 하루 = 시작 시각 ~ 끝 시각 (기본 오후 5시 ~ 다음 날 오전 6시) ─────
    var statsStartHour: Int
        get() = p.getInt("statsStartHour", 17)
        set(v) = p.edit().putInt("statsStartHour", v.coerceIn(0, 23)).apply()

    var statsEndHour: Int
        get() = p.getInt("statsEndHour", 6)
        set(v) = p.edit().putInt("statsEndHour", v.coerceIn(0, 23)).apply()

    // ───── 콜 확인 (TMAP 길찾기) ─────
    /** SK오픈API 에서 받은 TMAP 앱 키 (폰 안에만 저장) */
    var tmapKey: String
        get() = p.getString("tmapKey", "")!!.trim()
        set(v) = p.edit().putString("tmapKey", v.trim()).apply()

    /** 콜 앱 위에 [콜 ON] / [콜 확인] 버튼 띄우기 (설정 맨 위 체크칸) */
    var showCallOn: Boolean
        get() = p.getBoolean("showCallOn", true)
        set(v) = p.edit().putBoolean("showCallOn", v).apply()

    var showCheckOn: Boolean
        get() = p.getBoolean("showCheckOn", false)
        set(v) = p.edit().putBoolean("showCheckOn", v).apply()

    /** 톨비 기준 차 (2인 1조로 뒤따라가는 내 차). 0 = 톨비 계산 없음 (혼자) */
    var tollCar: Int
        get() = p.getInt("tollCar", 0)
        set(v) = p.edit().putInt("tollCar", v).apply()

    /** 수락 창(상세 화면)이 뜨면 콜 확인 창을 자동으로 띄우기 */
    var autoCheck: Boolean
        get() = p.getBoolean("autoCheck", true)
        set(v) = p.edit().putBoolean("autoCheck", v).apply()

    /** [콜 확인] 버튼 위치 (따로 움직임) */
    var checkX: Int
        get() = p.getInt("checkX", -1)
        set(v) = p.edit().putInt("checkX", v).apply()

    var checkY: Int
        get() = p.getInt("checkY", -1)
        set(v) = p.edit().putInt("checkY", v).apply()

    /** 두 버튼 위치를 처음으로 ([콜 ON] 아래 가운데, [콜 확인] 그 왼쪽) */
    fun resetBubble() = p.edit().putInt("bubbleX", -1).putInt("bubbleY", -1).putInt("checkX", -1).putInt("checkY", -1).apply()

    /** [앱 종료]를 누른 상태. 앱을 다시 열면 false */
    var paused: Boolean
        get() = p.getBoolean("paused", false)
        set(v) = p.edit().putBoolean("paused", v).apply()

    var keepScreenOn: Boolean
        get() = p.getBoolean("keepScreenOn", true)
        set(v) = p.edit().putBoolean("keepScreenOn", v).apply()

    /** 화면이 꺼져서 캡처가 꺼졌으면, 화면을 다시 켤 때 시작 창 자동으로 띄우기 */
    var autoAskCapture: Boolean
        get() = p.getBoolean("autoAskCapture", true)
        set(v) = p.edit().putBoolean("autoAskCapture", v).apply()

    fun evalSettings() = EvalSettings(
        formulaExpr = selectedFormula.expr,
        pickupMinPerKm = pickupMinPerKm,
        threshold = threshold,
        minPrice = if (minPriceOn) minPrice else null,
        words = words,
        checkOrigin = checkOrigin,
        hideRedValue = hideRedValue,
    )

    init {
        // v0.7.0: [콜 확인] 버튼은 기본 꺼짐 (수락 창이 뜨면 콜 확인 창이 자동으로 뜨니까). 한 번만 끔
        if (!p.getBoolean("checkOffV070", false)) {
            p.edit().putBoolean("checkOffV070", true).putBoolean("showCheckOn", false).apply()
        }
        // v0.6.3: [콜 확인] 처음 위치가 왼쪽 맨 위로 바뀌어서, 저장된 위치를 한 번 지움
        if (!p.getBoolean("checkResetV063", false)) {
            p.edit().putBoolean("checkResetV063", true).putInt("checkX", -1).putInt("checkY", -1).apply()
        }
        // v0.5.0: 버튼 처음 위치가 아래 가운데로 바뀌어서, 저장된 위치를 한 번 지움
        if (!p.getBoolean("bubbleResetV050", false)) {
            p.edit().putBoolean("bubbleResetV050", true).putInt("bubbleX", -1).putInt("bubbleY", -1).apply()
        }
        // v0.3.0: 식을 직접 저장해 둔 사람에게도 새 기본 식(1분당 금액)을 맨 앞에 넣고 선택
        if (!p.getBoolean("migratedPerMinute", false)) {
            p.edit().putBoolean("migratedPerMinute", true).apply()
            if (p.getString("formulas", null) != null) {
                val list = formulas
                if (list.none { it.id == Defaults.PER_MINUTE_ID }) formulas = listOf(Defaults.formulas[0]) + list
            }
            selectedId = Defaults.PER_MINUTE_ID
        }
    }

    companion object {
        /** 24000.0 → "24000", 1.5 → "1.5" */
        fun plain(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }
}
