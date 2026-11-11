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

    fun evalSettings() = EvalSettings(
        formulaExpr = selectedFormula.expr,
        threshold = threshold,
        minPrice = if (minPriceOn) minPrice else null,
        words = words,
        checkOrigin = checkOrigin,
        hideRedValue = hideRedValue,
    )

    companion object {
        /** 24000.0 → "24000", 1.5 → "1.5" */
        fun plain(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }
}
