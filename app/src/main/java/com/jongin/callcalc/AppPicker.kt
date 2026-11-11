package com.jongin.callcalc

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
                if (!a.isFinishing) dialog(a, store, title, apps, done)
            }
        }.start()
    }

    /** 검색칸 + 체크 목록. 고른 앱이 맨 위에 */
    private fun dialog(a: Activity, store: Store, title: String, apps: List<App>, done: () -> Unit) {
        val chosen = store.targetPackages.toMutableSet()
        val box = a.vbox(16)
        val search = EditText(a).apply {
            hint = "🔍 앱 이름 검색 (예: 대리, 카카오)"
            textSize = 18f
            isSingleLine = true
            setTextColor(C.FG)
            setHintTextColor(C.GRAY)
        }
        box.addView(search)
        val count = a.text("", 14f, C.DIM).margins(a, top = 4) as android.widget.TextView
        box.addView(count)
        val list = ListView(a).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        box.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(380)))

        var shown: List<App> = emptyList()
        fun refresh() {
            val q = search.text.toString().trim().replace(" ", "").lowercase()
            shown = apps
                .filter { q.isEmpty() || it.label.replace(" ", "").lowercase().contains(q) || it.pkg.lowercase().contains(q) }
                .sortedWith(compareBy({ it.pkg !in chosen }, { it.label }))
            list.adapter = ArrayAdapter(a, android.R.layout.simple_list_item_multiple_choice, shown.map { it.label })
            shown.forEachIndexed { i, app -> list.setItemChecked(i, app.pkg in chosen) }
            count.text = "고른 앱 ${chosen.size}개 · 목록 ${shown.size}개"
        }
        list.setOnItemClickListener { _, _, i, _ ->
            val app = shown[i]
            if (app.pkg in chosen) chosen -= app.pkg else chosen += app.pkg
            list.setItemChecked(i, app.pkg in chosen)
            count.text = "고른 앱 ${chosen.size}개 · 목록 ${shown.size}개"
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()

        MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                store.targetPackages = chosen
                CallReaderService.instance?.settingsChanged()
                done()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
