package com.jongin.callcalc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 화면 캡처 허락 받기 전용 투명 화면.
 * 메인 화면 [화면 캡처 시작]과, 화면이 꺼졌다 켜졌을 때 자동으로 다시 묻는 데 같이 쓴다.
 * 허락하면 캡처 서비스를 켜고 바로 닫힌다 (보던 화면으로 돌아감).
 */
class CaptureStartActivity : AppCompatActivity() {
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, res.resultCode)
                .putExtra(CaptureService.EXTRA_DATA, res.data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        finish()
    }

    // 알림 권한 (캡처 중 알림 표시용, 안드로이드 13+). 거절해도 캡처는 동작
    private val notif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ask() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !intent.getBooleanExtra(EXTRA_AUTO, false)
        ) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else ask()
    }

    private fun ask() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection.launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        const val EXTRA_AUTO = "auto"
    }
}
