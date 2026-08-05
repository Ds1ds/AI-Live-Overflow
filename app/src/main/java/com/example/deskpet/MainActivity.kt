package com.example.deskpet

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import com.example.deskpet.service.OverlayService

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val btn = Button(this).apply {
            text = "启动桌宠"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                } else {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
        setContentView(btn)
    }
}
