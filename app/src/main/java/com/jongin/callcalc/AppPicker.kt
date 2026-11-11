package com.jongin.callcalc

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** '감시할 앱' 고르기 (설명서 4-8). 고른 앱이 화면에 있을 때만 실시간 표시가 동작 */
object AppPicker {
    data class App(val pkg: String, val label: String)

    fun installedApps(a: Activity): List<App> {
        val pm = a.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != a.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label }
    }

    fun labelOf(a: Activity, pkg: String): String = runCatching {
        val pm = a.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** 고른 앱 이름들. 없으면 "아직 안 골랐어요" */
    fun summary(a: Activity, store: Store): String {
        val t = store.targetPackages
        return if (t.isEmpty()) "아직 안 골랐어요" else t.map { labelOf(a, it) }.sorted().joinToString(", ")
    }

    fun show(a: Activity, store: Store, title: String = "감시할 콜 앱 고르기", done: () -> Unit = {}) {
        Toast.makeText(a, "앱 목록 불러오는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val apps = installedApps(a)
            Handler(Looper.getMainLooper()).post {
                if (a.isFinishing) return@post
                val chosen = store.targetPackages.toMutableSet()
                val names = apps.map { it.label }.toTypedArray()
                val checked = apps.map { it.pkg in chosen }.toBooleanArray()
                MaterialAlertDialogBuilder(a)
                    .setTitle(title)
                    .setMultiChoiceItems(names, checked) { _, i, on ->
                        if (on) chosen += apps[i].pkg else chosen -= apps[i].pkg
                    }
                    .setPositiveButton("저장") { _, _ ->
                        store.targetPackages = chosen
                        CallReaderService.instance?.settingsChanged()
                        done()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }.start()
    }
}
