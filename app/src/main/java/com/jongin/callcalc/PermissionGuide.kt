package com.jongin.callcalc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 권한 안내. 꺼진 권한이 있으면 앱을 켤 때 바로 안내 창을 띄우고,
 * "켜러 가기" 한 번으로 해당 설정 화면을 연다.
 * 새 권한이 필요해지면 (v0.2 접근성, v0.3 다른 앱 위에 표시) all() 목록에만 추가하면 된다.
 */
object PermissionGuide {
    data class Perm(
        val title: String,
        val why: String,
        val steps: List<String>,
        val granted: (Activity) -> Boolean,
        val open: (Activity) -> Unit,
        val note: String? = null,                          // 막혔을 때 안내
        val extra: Pair<String, (Activity) -> Unit>? = null, // 보조 버튼
    )

    private val accessibility = Perm(
        title = "접근성 (콜 계산기)",
        why = "콜 앱 화면의 글자를 읽고, 콜 옆에 계산값을 띄워요.",
        steps = listOf(
            "아래 [켜러 가기]를 눌러요",
            "'설치된 앱'(또는 '설치된 서비스')에서 콜 계산기를 눌러요",
            "스위치를 켜고 [허용]을 눌러요",
            "뒤로 가기를 눌러 돌아와요",
        ),
        note = "스위치가 회색으로 잠겨 안 켜지면: [앱 정보 열기] → 오른쪽 위 ⋮ → '제한된 설정 허용' → 다시 [켜러 가기]",
        extra = "앱 정보 열기" to { a ->
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
        },
        granted = { isAccessibilityOn(it) },
        open = { it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
    )

    fun isAccessibilityOn(a: Activity): Boolean {
        val enabled = Settings.Secure.getString(a.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = android.content.ComponentName(a, CallReaderService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), ignoreCase = true) || it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    private val installApps = Perm(
        title = "이 출처의 앱 허용",
        why = "새 버전이 나오면 앱이 스스로 받아서 설치할 수 있게 해요.",
        steps = listOf(
            "아래 [켜러 가기]를 눌러요",
            "'이 출처 허용' 스위치를 켜요",
            "뒤로 가기를 누르면 이 앱으로 돌아와요",
        ),
        granted = { it.packageManager.canRequestPackageInstalls() },
        open = { it.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${it.packageName}"))) },
    )

    fun all(): List<Perm> = listOf(accessibility, installApps)

    fun missing(a: Activity): List<Perm> = all().filter { p -> runCatching { !p.granted(a) }.getOrDefault(false) }

    /** 안내 창. later 는 [나중에]를 눌렀을 때 */
    fun show(a: Activity, missing: List<Perm>, later: () -> Unit): AlertDialog {
        val box = a.vbox(20)
        box.addView(a.text("버튼을 누르면 설정 화면이 바로 열려요.", 17f, C.SUB))
        for (p in missing) box.addView(card(a, p))
        return MaterialAlertDialogBuilder(a)
            .setTitle("권한을 켜 주세요" + if (missing.size > 1) " (${missing.size}개)" else "")
            .setView(android.widget.ScrollView(a).apply { addView(box) })
            .setCancelable(false)
            .setNegativeButton("나중에") { _, _ -> later() }
            .show()
    }

    private fun card(a: Activity, p: Perm): View {
        val c = a.vbox().apply {
            background = a.roundBg(android.graphics.Color.parseColor("#2A1F0A"), C.WARN, 2, 14)
            setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14))
            layoutParams = lp().also { it.setMargins(0, a.dp(14), 0, 0) }
        }
        c.addView(a.text("❌ " + p.title, 20f, C.WARN, bold = true))
        c.addView(a.text(p.why, 16f, C.FG).margins(a, top = 4))
        c.addView(a.text(p.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"), 17f, C.SUB).apply {
            setLineSpacing(0f, 1.3f)
        }.margins(a, top = 8))
        c.addView(a.button("켜러 가기 →", big = true) { p.open(a) }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = a.dp(12) }
        })
        p.note?.let { c.addView(a.text(it, 15f, C.DIM).margins(a, top = 10)) }
        p.extra?.let { (label, act) ->
            c.addView(a.button(label, outlined = true) { act(a) }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.topMargin = a.dp(6) }
            })
        }
        return c
    }

    /** 설정 화면용: 권한마다 켜짐/꺼짐과 [켜러 가기] */
    fun statusRows(a: Activity): View {
        val box = a.vbox()
        for (p in all()) {
            val on = runCatching { p.granted(a) }.getOrDefault(false)
            val row = a.hbox().apply {
                background = a.roundBg(C.PANEL, if (on) C.LINE else C.WARN, 2, 12)
                setPadding(a.dp(14), a.dp(12), a.dp(10), a.dp(12))
                layoutParams = lp().also { it.setMargins(0, a.dp(4), 0, a.dp(4)) }
            }
            row.addView(a.text((if (on) "✅ " else "❌ ") + p.title, 18f, if (on) C.FG else C.WARN, bold = !on), lp(0, weight = 1f))
            if (!on) row.addView(a.button("켜러 가기", outlined = true) { p.open(a) })
            box.addView(row)
        }
        return box
    }
}
