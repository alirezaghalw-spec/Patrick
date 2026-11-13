package com.personal.emo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var btn: Button
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("emo", MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF14161A.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply { text = "ایمو"; textSize = 44f; setTextColor(0xFF4DE3FF.toInt()); gravity = Gravity.CENTER })
        status = TextView(this).apply { textSize = 16f; setTextColor(0xFF8A93A0.toInt()); gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, pad) }
        root.addView(status)
        btn = Button(this).apply { textSize = 18f; setOnClickListener { toggle() } }
        root.addView(btn)
        val sw = Switch(this).apply {
            text = "صدا"; textSize = 16f; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("sound", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("sound", on).apply() }
            setPadding(0, pad, 0, 0)
        }
        root.addView(sw)
        root.addView(Button(this).apply {
            text = "اجازه‌ی تشخیص برنامه‌ها (بازی، ویدیو، دوربین)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        })
        root.addView(TextView(this).apply { text = "بلندی صدا"; textSize = 14f; setTextColor(0xFF8A93A0.toInt()); setPadding(0, pad / 2, 0, 0) })
        root.addView(SeekBar(this).apply {
            max = 100; progress = (prefs.getFloat("vol", 0.6f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { prefs.edit().putFloat("vol", p / 100f).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { Sounds.init(); Sounds.play("chirp", prefs.getFloat("vol", 0.6f)) }
            })
        })
        root.addView(Button(this).apply {
            text = "اجازه‌ی واکنش به اعلان‌ها"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        setContentView(root)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val on = EmoService.running
        btn.text = if (on) "خاموش کن" else "روشن کن"
        status.text = if (on) "ایمو روی صفحه می‌چرخه" else "ایمو خاموشه"
    }

    private fun toggle() {
        if (EmoService.running) {
            startService(Intent(this, EmoService::class.java).setAction(EmoService.STOP))
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "اجازه‌ی نمایش روی بقیه‌ی برنامه‌ها رو روشن کن، بعد برگرد", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            startForegroundService(Intent(this, EmoService::class.java))
        }
        btn.postDelayed({ refresh() }, 500)
    }
}
