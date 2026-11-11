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
    )

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

    fun all(): List<Perm> = listOf(installApps)

    fun missing(a: Activity): List<Perm> = all().filter { p -> runCatching { !p.granted(a) }.getOrDefault(false) }

    /** 안내 창. later 는 [나중에]를 눌렀을 때 */
    fun show(a: Activity, missing: List<Perm>, later: () -> Unit): AlertDialog {
        val box = a.vbox(20)
        box.addView(a.text("아래 권한이 꺼져 있어요. [켜러 가기]를 누르면 설정 화면이 바로 열려요.", 14f, C.SUB))
        for (p in missing) box.addView(card(a, p))
        return MaterialAlertDialogBuilder(a)
            .setTitle("권한 설정이 필요해요" + if (missing.size > 1) " (${missing.size}개)" else "")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("나중에") { _, _ -> later() }
            .show()
    }

    private fun card(a: Activity, p: Perm): View {
        val c = a.vbox().apply {
            background = a.roundBg(C.PANEL, C.WARN, 1)
            setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
            layoutParams = lp().also { it.setMargins(0, a.dp(12), 0, 0) }
        }
        c.addView(a.text("❌ " + p.title, 16f, C.WARN, bold = true))
        c.addView(a.text(p.why, 14f, C.FG).margins(a, top = 4))
        c.addView(a.text(p.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"), 14f, C.SUB).margins(a, top = 6))
        c.addView(a.button("켜러 가기") { p.open(a) }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = a.dp(8) }
        })
        return c
    }

    /** 설정 화면용: 권한마다 켜짐/꺼짐과 [켜러 가기] */
    fun statusRows(a: Activity): View {
        val box = a.vbox()
        for (p in all()) {
            val on = runCatching { p.granted(a) }.getOrDefault(false)
            val row = a.hbox().apply { layoutParams = lp().also { it.setMargins(0, a.dp(4), 0, a.dp(4)) } }
            row.addView(a.text((if (on) "✅ " else "❌ ") + p.title, 15f, if (on) C.GOOD else C.WARN), lp(0, weight = 1f))
            if (!on) row.addView(a.button("켜러 가기", outlined = true) { p.open(a) })
            box.addView(row)
        }
        return box
    }
}
