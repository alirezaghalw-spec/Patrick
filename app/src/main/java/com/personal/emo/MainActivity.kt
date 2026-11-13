package com.personal.emo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    private lateinit var stats: TextView
    private lateinit var ttsNote: TextView
    private lateinit var prefs: SharedPreferences
    private var dp = 1f
    private val grey = 0xFF8A93A0.toInt()
    private val cyan = 0xFF4DE3FF.toInt()

    private fun fa(n: Int) = n.toString().map { if (it in '0'..'9') ('۰' + (it - '0')) else it }.joinToString("")

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(cyan)
        setPadding(0, (22 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(grey)
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun sw(label: String, key: String, def: Boolean) = Switch(this).apply {
        text = label; textSize = 16f; setTextColor(Color.WHITE)
        isChecked = prefs.getBoolean(key, def)
        setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun bar(label: String, key: String, lo: Int, hi: Int, def: Int, unit: String): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cur = prefs.getInt(key, def).coerceIn(lo, hi)
        val tv = TextView(this).apply {
            text = "$label: ${fa(cur)}$unit"; textSize = 14f; setTextColor(grey)
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }
        val sb = SeekBar(this).apply {
            max = hi - lo; progress = cur - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    prefs.edit().putInt(key, p + lo).apply()
                    tv.text = "$label: ${fa(p + lo)}$unit"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv)
        box.addView(sb)
        return box
    }

    private fun colorRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val views = ArrayList<View>()
        fun paint() {
            val sel = prefs.getInt("color", 0)
            views.forEachIndexed { i, v ->
                (v.background as GradientDrawable).setStroke(((if (i == sel) 3f else 0f) * dp).toInt(), Color.WHITE)
            }
        }
        for (i in EmoView.BODY.indices) {
            val v = View(this)
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(EmoView.BODY[i][0]) }
            val sz = (40 * dp).toInt()
            v.layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins((6 * dp).toInt(), 0, (6 * dp).toInt(), 0) }
            v.setOnClickListener { prefs.edit().putInt("color", i).apply(); paint() }
            views.add(v)
            row.addView(v)
        }
        paint()
        return row
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("emo", MODE_PRIVATE)
        dp = resources.displayMetrics.density
        val pad = (24 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad * 2)
        }
        root.addView(TextView(this).apply { text = "ایمو"; textSize = 44f; setTextColor(cyan); gravity = Gravity.CENTER })
        status = TextView(this).apply { textSize = 16f; setTextColor(grey); gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        btn = Button(this).apply { textSize = 18f; setOnClickListener { toggle() } }
        root.addView(btn)
        stats = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, 0) }
        root.addView(stats)

        root.addView(title("صدا"))
        root.addView(sw("صدا", "sound", true))
        root.addView(sw("حرف زدن (صدای فارسی)", "talk", true))
        ttsNote = note("")
        root.addView(ttsNote)
        root.addView(TextView(this).apply {
            text = "بلندی صدا"; textSize = 14f; setTextColor(grey); setPadding(0, pad / 3, 0, 0)
        })
        root.addView(SeekBar(this).apply {
            max = 100; progress = (prefs.getFloat("vol", 0.6f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { prefs.edit().putFloat("vol", p / 100f).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { Sounds.init(); Sounds.play("chirp", prefs.getFloat("vol", 0.6f)) }
            })
        })
        root.addView(note("لمس طولانی روی ایمو = دستور صوتی. بگو: سلام، ساعت، برقص، بخواب، دوستت دارم، بخند، ساکت."))

        root.addView(title("ظاهر و رفتار"))
        root.addView(bar("اندازه", "size", 70, 170, 110, ""))
        root.addView(bar("سرعت حرکت", "speed", 50, 200, 100, "٪"))
        root.addView(note("رنگ بدنه"))
        root.addView(colorRow())

        root.addView(title("واکنش‌ها"))
        root.addView(sw("واکنش به اعلان‌ها", "notif", true))
        root.addView(sw("واکنش به موقعیت (شارژ، هدفون، اینترنت، باز شدن قفل)", "react", true))
        root.addView(Button(this).apply {
            text = "مخفی شدن در برنامه‌های انتخابی"
            setOnClickListener { pickHideApps() }
        })
        root.addView(note("برای مخفی شدن در برنامه‌ها، «اجازه‌ی تشخیص برنامه‌ها» لازم است."))

        root.addView(title("اجازه‌ها"))
        root.addView(Button(this).apply {
            text = "اجازه‌ی تشخیص برنامه‌ها (بازی، ویدیو، دوربین)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "اجازه‌ی واکنش به اعلان‌ها"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })

        val sv = ScrollView(this).apply {
            setBackgroundColor(0xFF14161A.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(root)
        }
        setContentView(sv)
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onDestroy() {
        // the settings screen may have initialised the sounds (volume preview); free them if the service isn't using them
        if (!EmoService.running) Sounds.release()
        super.onDestroy()
    }

    private fun refresh() {
        val on = EmoService.running
        btn.text = if (on) "خاموش کن" else "روشن کن"
        status.text = if (on) "ایمو روی صفحه می‌چرخه" else "ایمو خاموشه"
        val lv = 1 + prefs.getInt("taps", 0) / 40
        val rank = when { lv >= 10 -> "بهترین دوست"; lv >= 6 -> "رفیق"; lv >= 3 -> "دوست"; else -> "آشنا" }
        stats.text = "سطح دوستی ${fa(lv)} ($rank) · ${fa(prefs.getInt("streak", 0))} روز پشت‌سرهم"
        ttsNote.text = when {
            !prefs.contains("ttsFa") -> "وضعیت صدای فارسی بعد از اولین روشن شدن مشخص می‌شه."
            prefs.getBoolean("ttsFa", false) -> "صدای فارسی روی این گوشی فعاله."
            else -> "صدای فارسی (TTS) پیدا نشد؛ ایمو فقط صداهای بامزه درمیاره. با نصب موتور TTS فارسی حرف هم می‌زنه."
        }
    }

    private fun pickHideApps() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val cur = (prefs.getStringSet("hideApps", emptySet()) ?: emptySet()).toMutableSet()
        val names = apps.map { it.second }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in cur }
        AlertDialog.Builder(this)
            .setTitle("در این برنامه‌ها مخفی شو")
            .setMultiChoiceItems(names, checked) { _, i, on ->
                if (on) cur.add(apps[i].first) else cur.remove(apps[i].first)
            }
            .setPositiveButton("ذخیره") { _, _ -> prefs.edit().putStringSet("hideApps", HashSet(cur)).apply() }
            .setNegativeButton("لغو", null)
            .show()
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
