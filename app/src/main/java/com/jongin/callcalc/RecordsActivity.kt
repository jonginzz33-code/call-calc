package com.jongin.callcalc

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jongin.callcalc.core.WorkDay
import com.jongin.callcalc.core.formatNumber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 날짜별 콜 기록 (수락한 콜만). 하루 = 설정의 통계 시간 (기본 17시 ~ 다음 날 6시) */
class RecordsActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var store: Store
    private val open = mutableSetOf<LocalDate>()
    private var calls: List<Cloud.Call> = emptyList()
    private val who: String? get() = intent.getStringExtra("uid")       // 관리자가 다른 사람 기록을 볼 때
    private val whoName: String? get() = intent.getStringExtra("name")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        root = vbox(16)
        scrollPage(root)
        load()
    }

    private fun load() {
        root.removeAllViews()
        root.addView(text("📊 날짜별 기록", 26f, bold = true))
        root.addView(text("불러오는 중…", 15f, C.SUB).margins(this, top = 8))
        Cloud.loadCalls(who) { list, err ->
            if (list == null) { root.removeAllViews(); root.addView(text(err ?: "못 불러왔어요", 16f, C.WARN)); return@loadCalls }
            calls = list
            render()
        }
    }

    private fun n(v: Double) = formatNumber(v)

    private fun render() {
        root.removeAllViews()
        root.addView(text("📊 날짜별 기록" + (whoName?.let { " · $it" } ?: ""), 26f, bold = true))
        root.addView(text("하루 = ${store.statsStartHour}시 ~ 다음 날 ${store.statsEndHour}시 · 날짜를 누르면 콜 목록 · 콜을 길게 누르면 지우기", 13f, C.DIM).margins(this, top = 4))
        if (calls.isEmpty()) { root.addView(text("아직 기록이 없어요. 콜을 수락하면 여기 쌓여요.", 16f, C.SUB).margins(this, top = 16)); return }
        val zone = ZoneId.systemDefault()
        val s = store.statsStartHour; val e = store.statsEndHour
        // 하루 밖 시간이면 그날 날짜로
        val byDay = calls.groupBy { WorkDay.of(it.at, zone, s, e) ?: Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        val dayFmt = DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN)
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        // 전체 요약
        val all = calls.mapNotNull { it.num("perMinute") }
        if (all.isNotEmpty()) {
            root.addView(text("전체 ${calls.size}콜 · 평균 ${n(all.average())}원/분 · 합계 ${n(calls.sumOf { it.num("net") ?: 0.0 })}원", 15f, C.ACCENT, bold = true).margins(this, top = 10))
        }

        for ((day, list) in byDay.toSortedMap(compareByDescending { it })) {
            val per = list.mapNotNull { it.num("perMinute") }
            val card = vbox().apply {
                background = roundBg(C.PANEL, C.LINE, 2, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = lp().also { it.topMargin = dp(10) }
                setOnClickListener { if (!open.remove(day)) open += day; render() }
            }
            card.addView(text("${day.format(dayFmt)} · ${list.size}콜", 19f, bold = true))
            card.addView(text(
                if (per.isEmpty()) "분당 금액 없음"
                else "평균 ${n(per.average())} · 최고 ${n(per.max())} · 최저 ${n(per.min())} 원/분", 15f, C.WARN))
            card.addView(text("합계 ${n(list.sumOf { it.num("net") ?: 0.0 })}원 (톨비 뺀 금액)", 14f, C.SUB))
            if (day in open) {
                for (c in list.sortedBy { it.at }) {
                    val t = Instant.ofEpochMilli(c.at).atZone(zone).format(timeFmt)
                    val line = "$t  ${c.str("originArea").orEmpty()} → ${c.str("destArea").orEmpty()}\n" +
                        "   ${n(c.num("net") ?: 0.0)}원" + (c.num("perMinute")?.let { " · ${n(it)}원/분" } ?: "") +
                        (c.num("totalMin")?.let { " · 총 ${it.toInt()}분" } ?: "") +
                        (if (c.data["estimated"] == true) " · 추정" else "")
                    card.addView(text(line, 14f, C.FG).apply {
                        setPadding(0, dp(8), 0, 0)
                        setOnLongClickListener {
                            MaterialAlertDialogBuilder(this@RecordsActivity)
                                .setTitle("이 기록을 지울까요?")
                                .setMessage(line)
                                .setPositiveButton("지우기") { _, _ -> Cloud.deleteCall(c.id, who) { load() } }
                                .setNegativeButton("취소", null).show()
                            true
                        }
                    })
                }
            }
            root.addView(card)
        }
    }
}
