package com.jongin.callcalc

import android.content.Context
import com.jongin.callcalc.core.CallLog
import com.jongin.callcalc.core.CallRow
import com.jongin.callcalc.core.DayStats
import com.jongin.callcalc.core.SeenCall
import com.jongin.callcalc.core.WorkDay
import com.jongin.callcalc.core.formatNumber
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId

/** 오늘 통계용 콜 기록. 폰 안 파일에만 저장 (밖으로 보내지 않음). 3일 지난 기록은 지움 */
class StatsStore(context: Context, private val store: Store) {
    private val file = File(context.filesDir, "seen_calls.json")
    private val log = CallLog()
    private var dirty = false
    private var lastSave = 0L

    init {
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    log.calls += SeenCall(
                        o.getLong("t"), o.getDouble("a"), o.getDouble("b"), o.getDouble("c"),
                        o.optString("o"), o.optString("d"),
                    )
                }
            }
        }
        log.prune(System.currentTimeMillis() - 3 * 86_400_000L)
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 하루 시간대 안이면 기록 (중복은 빼고) */
    fun record(rows: List<CallRow>) {
        val now = System.currentTimeMillis()
        if (WorkDay.of(now, zone, store.statsStartHour, store.statsEndHour) == null) return
        for (r in rows) if (log.add(r, now)) dirty = true
        if (dirty && now - lastSave > 3000) save()
    }

    fun today(): DayStats? {
        val day = WorkDay.of(System.currentTimeMillis(), zone, store.statsStartHour, store.statsEndHour) ?: return null
        return log.stats(day, zone, store.statsStartHour, store.statsEndHour, store.evalSettings())
    }

    /** 맨 위 투명 글씨용: "오늘 평균 312 · 최고 556 · 최저 210 · 37건" */
    fun summary(): String? {
        val st = today() ?: return null
        if (st.count == 0) return "오늘 0건"
        return "오늘 평균 ${formatNumber(st.avg!!)} · 최고 ${formatNumber(st.max!!)} · 최저 ${formatNumber(st.min!!)} · ${st.count}건"
    }

    fun save() {
        dirty = false
        lastSave = System.currentTimeMillis()
        val arr = JSONArray()
        log.calls.forEach {
            arr.put(JSONObject().put("t", it.at).put("a", it.a).put("b", it.b).put("c", it.c).put("o", it.origin).put("d", it.destination))
        }
        runCatching { file.writeText(arr.toString()) }
    }

    fun clear() { log.calls.clear(); save() }
}
