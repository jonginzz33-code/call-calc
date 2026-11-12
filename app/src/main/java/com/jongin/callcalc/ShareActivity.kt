package com.jongin.callcalc

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jongin.callcalc.core.formatNumber

/** B 폰: 팀원이 수락한 콜 (알림을 누르면 열림) */
class ShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox(16)
        scrollPage(root)
        val i = intent
        fun d(k: String) = if (i.hasExtra(k)) i.getDoubleExtra(k, 0.0) else null
        val origin = i.getStringExtra("origin").orEmpty()
        val dest = i.getStringExtra("destination").orEmpty()

        root.addView(text("🚗 ${i.getStringExtra("from") ?: "팀원"} 콜 수락", 24f, bold = true))
        val price = i.getDoubleExtra("price", 0.0)
        if (price > 0) root.addView(text("${formatNumber(price)}원", 18f, C.WARN, bold = true))
        i.getStringExtra("warn")?.let { root.addView(text(it, 15f, C.WARN, bold = true).margins(this, top = 6)) }

        fun place(label: String, addr: String, found: String?) {
            root.addView(text(label, 14f, C.DIM).margins(this, top = 14))
            root.addView(text(addr, 20f, C.FG, bold = true))
            found?.let { root.addView(text("내비: $it", 14f, C.SUB)) }
            root.addView(text("주소 복사", 15f, C.ACCENT).apply {
                setPadding(0, dp(6), 0, 0)
                setOnClickListener {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("주소", addr))
                    Toast.makeText(this@ShareActivity, "복사했어요", Toast.LENGTH_SHORT).show()
                }
            })
        }
        place("도착", dest, i.getStringExtra("destName"))
        root.addView(button("TMAP 길안내 (도착)", big = true) {
            runCatching { startActivity(Share.nav(this, i.getStringExtra("destName"), d("destX"), d("destY"), dest)) }
                .onFailure { Toast.makeText(this, "지도 앱을 못 열었어요", Toast.LENGTH_SHORT).show() }
        }.margins(this, top = 10))
        place("출발", origin, i.getStringExtra("originName"))
        root.addView(button("TMAP 길안내 (출발)", outlined = true) {
            runCatching { startActivity(Share.nav(this, i.getStringExtra("originName"), d("originX"), d("originY"), origin)) }
        }.margins(this, top = 10))

        i.getStringExtra("image")?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let { bmp ->
                root.addView(text("콜 앱 화면 (A 폰)", 14f, C.DIM).margins(this, top = 18))
                root.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                }, lp().also { it.topMargin = dp(6) })
            }
        }
    }
}
