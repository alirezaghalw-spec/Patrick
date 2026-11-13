package com.personal.emo

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.*
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.Network
import android.service.quicksettings.TileService
import android.speech.tts.TextToSpeech
import android.view.*
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import kotlin.math.*

class EmoService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var instance: EmoService? = null
        const val STOP = "com.personal.emo.STOP"
        const val CH = "emo"
        private val CALM = setOf(
            "com.google.android.youtube", "com.netflix.mediaclient", "tv.twitch.android.app",
            "com.disney.disneyplus", "org.videolan.vlc", "com.mxtech.videoplayer.ad",
            "com.amazon.avod.thirdpartyclient", "com.google.android.apps.youtube.music"
        )
        private val MONTHS = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    }

    private lateinit var wm: WindowManager
    private var view: EmoView? = null
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val prefs get() = getSharedPreferences("emo", MODE_PRIVATE)
    private var size = 0
    private var dens = 1f
    private var x = 0f; private var y = 0f; private var tx = 0f; private var ty = 0f
    private var vx = 0f; private var vy = 0f
    private var wait = 1f; private var t = 0f; private var hop = 0f
    private var moodUntil = 0L; private var lastAct = 0L; private var lastNs = 0L
    private var lastTap = 0L; private var lastTapUp = 0L
    private var nextSlow = 0L; private var nextFg = 0L; private var sleepyDelay = 22000L
    private var affection = 50f
    private var peek = 0
    private var dragging = false; private var moved = false
    private var downX = 0f; private var downY = 0f
    private var lastHit = 0L; private var lastShake = 0L; private var shakeHits = 0
    private var sm: SensorManager? = null
    private var gxl = 0f; private var gz = 0f; private var gravOn = false; private var gravWas = false
    private var hidden = false; private var dancing = false
    private var fgKind = "none"; private var fgPkg = ""; private var fgQ = 0L
    private val kinds = HashMap<String, String>()
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var pendingSay: String? = null
    private var quietUntil = 0L; private var danceUntil = 0L
    private var longFired = false
    private var taps = 0
    private var speedMul = 1f
    private var hideSet: Set<String> = emptySet()
    private var netLost = false
    private var fullNotified = false
    private var lastUnlock = 0L
    private var cm: ConnectivityManager? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == STOP) {
            prefs.edit().putBoolean("enabled", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        if (running) return START_STICKY
        startFg()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        prefs.edit().putBoolean("enabled", true).apply()
        createOverlay()
        running = true
        instance = this
        tileRefresh()
        return START_STICKY
    }

    private fun tileRefresh() {
        try { TileService.requestListeningState(this, ComponentName(this, EmoTile::class.java)) } catch (e: Exception) { }
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Emo", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, EmoService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_emo)
            .setContentTitle("ایمو فعاله")
            .setContentText("برای خاموش کردن، دکمه‌ی خاموش رو بزن")
            .setContentIntent(open)
            .addAction(R.drawable.ic_emo, "خاموش", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun bounds(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val dm = resources.displayMetrics; dm.widthPixels to dm.heightPixels
        }
    }

    private fun rnd() = Math.random().toFloat()

    private fun newTarget() {
        val (w, h) = bounds()
        val maxY = (h - size - 48 * dens).coerceAtLeast(1f)
        val r = rnd()
        peek = 0
        ty = rnd() * maxY
        when {
            r < .14f -> { peek = -1; tx = -size * .45f }
            r < .28f -> { peek = 1; tx = w - size * .55f }
            else -> tx = rnd() * (w - size).coerceAtLeast(1)
        }
    }

    private fun sfx(name: String, spont: Boolean = false) {
        if (hidden) return
        if (SystemClock.uptimeMillis() < quietUntil) return
        if (spont && (fgKind == "calm" || dancing)) return
        val p = prefs
        if (!p.getBoolean("sound", true)) return
        val am = getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        Sounds.play(name, p.getFloat("vol", 0.6f))
    }

    private fun setMood(m: String, ms: Long) {
        view?.mood = m
        moodUntil = SystemClock.uptimeMillis() + ms
    }

    // ---- battery saver: pause everything while the screen is off ----
    private fun pause() {
        Choreographer.getInstance().removeFrameCallback(frame)
        handler.removeCallbacksAndMessages(null)
        sm?.unregisterListener(shakeL)
        tts?.stop()
    }

    private fun resume() {
        lastNs = 0L
        registerSensor()
        Choreographer.getInstance().postFrameCallback(frame)
        handler.postDelayed(blinker, 500)
    }

    private fun registerSensor() {
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm?.registerListener(shakeL, it, SensorManager.SENSOR_DELAY_UI) }
    }

    private val recv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> situ { setMood("love", 3000); sfx("love"); hop = .5f }
                Intent.ACTION_POWER_DISCONNECTED -> situ { setMood("wow", 1500); sfx("wow") }
                Intent.ACTION_BATTERY_LOW -> situ { setMood("sad", 8000); sfx("sleepy"); say("باتری کمه") }
                Intent.ACTION_SCREEN_OFF -> pause()
                Intent.ACTION_SCREEN_ON -> resume()
                Intent.ACTION_HEADSET_PLUG -> if (i.getIntExtra("state", 0) == 1) situ { setMood("happy", 2500); sfx("chirp"); hop = .5f }
                Intent.ACTION_USER_PRESENT -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastUnlock > 1800000L) {
                        lastUnlock = now
                        situ { setMood("happy", 1800); sfx("chirp"); hop = .5f }
                    }
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    val pct = if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1
                    if (pct >= 100 && plugged != 0) {
                        if (!fullNotified) {
                            fullNotified = true
                            situ { setMood("love", 3000); sfx("love"); hop = .5f; say("پر شدم") }
                        }
                    } else if (pct in 0..94) fullNotified = false
                }
            }
        }
    }

    private val shakeL = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val ax = e.values[0]; val ay = e.values[1]; val az = e.values[2]
            // gravity sliding: low-pass tilt
            gxl = gxl * .8f + (-ax) * .2f
            gz = gz * .8f + az * .2f
            gravOn = abs(gxl) > 2.5f && abs(gz) < 8.5f
            // shake
            val g = sqrt(ax * ax + ay * ay + az * az)
            if (abs(g - 9.8f) > 11f) {
                val now = SystemClock.uptimeMillis()
                shakeHits = if (now - lastHit < 700) shakeHits + 1 else 1
                lastHit = now
                if (shakeHits >= 3 && now - lastShake > 4000) {
                    lastShake = now; shakeHits = 0
                    setMood("dizzy", 2500); sfx("dizzy")
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun notifPing(cat: String? = null) {
        handler.post {
            if (view == null || dragging || hidden) return@post
            if (!prefs.getBoolean("notif", true)) return@post
            when (cat) {
                Notification.CATEGORY_CALL -> { setMood("wow", 3000); sfx("trill"); hop = .5f }
                Notification.CATEGORY_ALARM -> { setMood("wow", 3000); sfx("dizzy"); hop = .5f }
                Notification.CATEGORY_MESSAGE -> { setMood("love", 1800); sfx("chirp"); hop = .5f }
                Notification.CATEGORY_EMAIL -> { setMood("happy", 1500); sfx("boop") }
                else -> { setMood("wow", 1500); sfx("chirp"); hop = .5f }
            }
            lastAct = SystemClock.uptimeMillis()
        }
    }

    // ---- app awareness (needs Usage Access) ----
    private fun hasUsage(): Boolean {
        val ao = getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        else @Suppress("DEPRECATION") ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun kindOf(p: String): String = kinds.getOrPut(p) {
        when {
            p == packageName -> "none"
            p.contains("camera", true) -> "camera"
            p in CALM -> "calm"
            else -> try {
                if (packageManager.getApplicationInfo(p, 0).category == ApplicationInfo.CATEGORY_GAME) "game" else "none"
            } catch (e: Exception) { "none" }
        }
    }

    private fun pollFg() {
        if (!hasUsage()) { fgKind = "none"; return }
        try {
            val usm = getSystemService(UsageStatsManager::class.java)
            val wall = System.currentTimeMillis()
            val ev = usm.queryEvents(if (fgQ == 0L) wall - 3600000 else fgQ - 1000, wall)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == 1) fgPkg = e.packageName }
            fgQ = wall
            fgKind = if (fgPkg in hideSet) "hide" else kindOf(fgPkg)
        } catch (e: Exception) { fgKind = "none" }
    }

    // ---- memory & personality ----
    private fun level() = 1 + taps / 40
    private fun floorAff() = min(30f, (level() - 1) * 3f)

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when { h in 5..11 -> "صبح بخیر"; h in 12..16 -> "ظهر بخیر"; h in 17..20 -> "عصر بخیر"; else -> "شب بخیر" }
    }

    private fun remember(): Pair<String, String?> {
        val p = prefs
        val today = LocalDate.now().toEpochDay()
        val last = p.getLong("lastDay", -1L)
        var streak = p.getInt("streak", 0)
        var mood = "happy"
        var text: String? = null
        if (last < 0L) {
            streak = 1
            text = "سلام! من ایمو هستم"
        } else {
            val gap = today - last
            if (gap == 1L) {
                streak += 1
                text = if (streak == 3 || streak == 7 || streak == 14 || streak == 30) "$streak روزه با همیم" else greeting()
            } else if (gap >= 2L) {
                streak = 1
                affection = max(15f, affection - 4f * min(gap, 10L).toInt())
                mood = if (gap >= 4L) "cry" else "sad"
                text = if (gap >= 4L) "دلم برات تنگ شده بود" else "دیروز نبودی"
            }
        }
        p.edit().putLong("lastDay", today).putInt("streak", streak).apply()
        return Pair(mood, text)
    }

    // ---- voice ----
    private fun awake() = getSystemService(PowerManager::class.java).isInteractive

    private fun situ(block: () -> Unit) {
        if (view == null || hidden || !awake() || !prefs.getBoolean("react", true)) return
        block()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("fa", "IR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setPitch(1.5f)
                tts?.setSpeechRate(1.1f)
            }
            prefs.edit().putBoolean("ttsFa", ttsOk).apply()
            val ps = pendingSay
            pendingSay = null
            if (ttsOk && ps != null) say(ps, true)
        }
    }

    private fun say(text: String, force: Boolean = false) {
        if (!ttsOk || hidden || !awake()) return
        if (SystemClock.uptimeMillis() < quietUntil) return
        if (!force && (fgKind == "calm" || dancing)) return
        val p = prefs
        if (!p.getBoolean("talk", true) || !p.getBoolean("sound", true)) return
        val am = getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val b = Bundle()
        b.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, p.getFloat("vol", 0.6f))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, b, "emo")
    }

    fun command(raw: String?) {
        handler.post {
            if (view == null) return@post
            val t = (raw ?: "").replace('ي', 'ی').replace('ك', 'ک').replace('\u200c', ' ').trim()
            lastAct = SystemClock.uptimeMillis()
            when {
                t.isEmpty() -> { setMood("wow", 1200); say("نشنیدم", true) }
                "ساعت" in t || "تاریخ" in t -> showClock()
                "ساکت" in t -> { say("باشه", true); quietUntil = SystemClock.uptimeMillis() + 600000L }
                "بخواب" in t || "خوابم" in t -> { setMood("sleepy", 20000); sfx("sleepy"); say("شب بخیر", true) }
                "برقص" in t -> { danceUntil = SystemClock.uptimeMillis() + 12000L; setMood("happy", 12000); sfx("trill"); say("بزن بریم", true) }
                "سلام" in t -> { setMood("happy", 2500); sfx("hello"); say(greeting(), true) }
                "دوستت" in t || "عاشق" in t -> {
                    setMood("love", 3500); sfx("love"); hop = .5f
                    affection = min(100f, affection + 8f)
                    say("منم دوستت دارم", true)
                }
                "بخند" in t || "جوک" in t -> { setMood("laugh", 2500); sfx("giggle"); say("هه هه هه", true) }
                "گریه" in t || "ناراحت" in t -> { setMood("cry", 3000); sfx("cry"); say("وای نه", true) }
                "بپر" in t -> { hop = .5f; setMood("happy", 1500); sfx("boop") }
                "چطور" in t || "حالت" in t -> {
                    val a = affection
                    setMood(if (a > 60) "love" else if (a < 20) "sad" else "happy", 2500)
                    say(if (a > 60) "عالیم" else if (a < 20) "دلخورم" else "خوبم", true)
                }
                else -> { setMood("wow", 1500); sfx("chirp"); say("نفهمیدم", true) }
            }
        }
    }

    private val longRun = Runnable {
        if (dragging && !moved) {
            longFired = true
            dragging = false
            setMood("wow", 5000); sfx("chirp")
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            try { startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }
    }

    // ---- settings applied live ----
    private fun applySize() {
        val v = view ?: return
        size = (prefs.getInt("size", 110) * dens).toInt()
        lp.width = size; lp.height = size
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
    }

    private val prefL = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "size" -> applySize()
            "color" -> view?.setBody(prefs.getInt("color", 0))
            "speed" -> speedMul = prefs.getInt("speed", 100) / 100f
            "hideApps" -> hideSet = prefs.getStringSet("hideApps", emptySet()) ?: emptySet()
        }
    }

    // ---- network ----
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            handler.postDelayed({
                if (!netLost && cm?.activeNetwork == null) {
                    situ { netLost = true; setMood("sad", 4000); sfx("cry"); say("اینترنت قطع شد") }
                }
            }, 2500)
        }
        override fun onAvailable(network: Network) {
            handler.post {
                if (netLost) {
                    netLost = false
                    situ { setMood("happy", 2500); sfx("chirp"); say("اینترنت وصل شد") }
                }
            }
        }
    }

    // ---- clock (double tap) ----
    private fun toJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
        val jm = if (days < 186) 1 + days / 31 else 7 + (days - 186) / 30
        val jd = 1 + (if (days < 186) days % 31 else (days - 186) % 30)
        return Triple(jy, jm, jd)
    }

    private fun fa(s: String) = s.map { if (it in '0'..'9') ('۰' + (it - '0')) else it }.joinToString("")

    private fun showClock() {
        val v = view ?: return
        val c = Calendar.getInstance()
        val (_, jm, jd) = toJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        v.clockTime = fa(String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
        v.clockDate = fa("$jd") + " " + MONTHS[jm - 1]
        setMood("clock", 4500)
        sfx("chirp")
    }

    private fun createOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        dens = resources.displayMetrics.density
        size = (prefs.getInt("size", 110) * dens).toInt()
        lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val v = EmoView(this)
        v.setOnTouchListener { _, e -> touch(e) }
        view = v
        val (w, h) = bounds()
        x = (w - size) / 2f; y = (h - size) / 2f
        lp.x = x.toInt(); lp.y = y.toInt()
        wm.addView(v, lp)
        affection = prefs.getFloat("aff", 50f)
        taps = prefs.getInt("taps", 0)
        speedMul = prefs.getInt("speed", 100) / 100f
        hideSet = prefs.getStringSet("hideApps", emptySet()) ?: emptySet()
        v.setBody(prefs.getInt("color", 0))
        prefs.registerOnSharedPreferenceChangeListener(prefL)
        lastAct = SystemClock.uptimeMillis(); lastTap = lastAct
        newTarget(); peek = 0
        Sounds.init()
        sfx("hello")
        val (m0, say0) = remember()
        setMood(m0, 2500)
        pendingSay = say0
        initTts()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_HEADSET_PLUG); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val lv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            fullNotified = lv >= 0 && sc > 0 && lv * 100 / sc >= 100 && bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(recv, f, Context.RECEIVER_EXPORTED) else registerReceiver(recv, f)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        try {
            cm = getSystemService(ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(netCb)
        } catch (e: Exception) { }
        resume()
    }

    private val blinker = object : Runnable {
        override fun run() {
            view?.blink = true
            handler.postDelayed({ view?.blink = false; handler.postDelayed(this, 1800L + (rnd() * 3200).toLong()) }, 130)
        }
    }

    private fun react() {
        val lvBefore = level()
        val a = affection
        val pool = when {
            a > 60 -> listOf("love", "laugh", "wink", "happy", "wow")
            a < 20 -> listOf("angry", "cry", "wow", "happy")
            else -> listOf("happy", "laugh", "wink", "love", "wow", "angry")
        }
        val m = pool.random()
        val snd = when (m) {
            "happy" -> listOf("giggle", "trill").random()
            "laugh" -> "giggle"; "wink" -> "chirp"; "cry" -> "cry"
            else -> m
        }
        setMood(m, 1700)
        sfx(snd)
        affection = min(100f, affection + 6f)
        lastTap = SystemClock.uptimeMillis()
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        taps++
        prefs.edit().putInt("taps", taps).apply()
        if (level() > lvBefore) {
            setMood("love", 2500); sfx("love"); hop = .5f
            say("رفتیم سطح ${level()}", true)
        }
    }

    private fun touch(e: MotionEvent): Boolean {
        val v = view ?: return false
        if (longFired && e.actionMasked != MotionEvent.ACTION_DOWN) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                longFired = false
                lastAct = SystemClock.uptimeMillis()
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true; moved = false; longFired = false; downX = e.rawX; downY = e.rawY
                lastAct = SystemClock.uptimeMillis()
                handler.removeCallbacks(longRun)
                handler.postDelayed(longRun, 650)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.rawX - downX) + abs(e.rawY - downY) > 12 * dens) { moved = true; handler.removeCallbacks(longRun) }
                if (moved) {
                    x = e.rawX - size / 2f; y = e.rawY - size / 2f; tx = x; ty = y; vx = 0f; vy = 0f; peek = 0
                    if (v.mood != "wow") setMood("wow", 600)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRun)
                dragging = false
                val now = SystemClock.uptimeMillis()
                if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                    if (now - lastTapUp < 350) { lastTapUp = 0; showClock() } else { lastTapUp = now; react() }
                } else { wait = 1.2f; newTarget() }
                lastAct = now
            }
        }
        return true
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            val v = view ?: return
            val idle = v.mood == "sleepy" && !dragging && abs(vx) + abs(vy) < 5f
            val minNs = if (hidden) 400_000_000L else if (idle) 90_000_000L else 30_000_000L
            if (lastNs != 0L && ns - lastNs < minNs) { Choreographer.getInstance().postFrameCallback(this); return }
            if (lastNs == 0L) lastNs = ns
            val dt = min(0.05f, (ns - lastNs) / 1e9f)
            lastNs = ns
            t += dt
            if (hop > 0f) hop -= dt
            val now = SystemClock.uptimeMillis()

            if (now > nextSlow) {
                nextSlow = now + 20000
                affection = max(floorAff(), affection - 1f)
                val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                sleepyDelay = if (hr >= 23 || hr < 7) 6000L else 22000L
                if (now - lastTap > 600000 && v.mood == "normal") {
                    setMood("hungry", 5000); sfx("boop", true); lastTap = now
                }
            }
            val (w, h) = bounds()
            if (now > nextFg) {
                nextFg = now + 2000
                pollFg()
            }
            val music = getSystemService(AudioManager::class.java).isMusicActive
            val calm = fgKind == "calm"
            val excited = fgKind == "game"
            val newHidden = w > h || fgKind == "camera" || fgKind == "hide"
            if (newHidden != hidden) { hidden = newHidden; v.visibility = if (hidden) View.GONE else View.VISIBLE }
            if (hidden) { Choreographer.getInstance().postFrameCallback(this); return }
            dancing = (music || now < danceUntil) && !calm && !dragging && !gravOn

            if (dancing && (v.mood == "normal" || v.mood == "sleepy")) v.mood = "happy"
            if (v.mood != "normal" && now > moodUntil && !dragging) {
                v.mood = if (dancing) "happy" else if (now - lastAct > sleepyDelay) "sleepy" else "normal"
            }
            var arrived = false
            if (!dragging) {
                if (gravOn) {
                    lastAct = now
                    vx += gxl * 90f * dens * dt
                    vx *= .995f; vy *= .9f
                    x += vx * dt
                    val maxX = (w - size).toFloat()
                    if (x < 0f || x > maxX) {
                        if (abs(vx) > 200f * dens) { setMood("wow", 800); sfx("boop") }
                        x = x.coerceIn(0f, maxX); vx = -vx * .5f
                    }
                    if (v.mood == "sleepy" || v.mood == "normal") setMood("laugh", 600)
                } else {
                    if (gravWas) { newTarget(); wait = 1f }
                    val dx = tx - x; val dy = ty - y; val d = hypot(dx, dy)
                    arrived = d <= 12 * dens
                    val spd = (if (excited) 1.6f else if (calm) .5f else 1f) * speedMul
                    if (v.mood == "sleepy" || dancing) { vx *= .9f; vy *= .9f }
                    else if (d > 6 * dens && wait <= 0f) {
                        val sp = min(70f * dens * spd, d * 2f + 20f * dens)
                        val k = min(1f, dt * 4f)
                        vx += (dx / d * sp - vx) * k; vy += (dy / d * sp - vy) * k
                    } else {
                        vx *= .85f; vy *= .85f
                        if (wait > 0f) wait -= dt
                        if (d <= 6 * dens && wait <= 0f) {
                            wait = 1f + rnd() * 2.5f
                            if (calm) wait = 10f
                            else if (peek != 0) { wait += 3f; setMood("happy", 1500); sfx("chirp", true) }
                            else if (rnd() < (if (excited) .7f else .3f) && v.mood == "normal") {
                                if (excited || rnd() < .5f) { hop = .5f; setMood(if (excited) "wow" else "happy", 1100); sfx(if (excited) "chirp" else "boop", true) }
                                else { setMood("wow", 1100); sfx("chirp", true) }
                            }
                            newTarget()
                        }
                    }
                    x += vx * dt; y += vy * dt
                    if (v.mood == "normal" && now - lastAct > sleepyDelay) { v.mood = "sleepy"; sfx("sleepy", true) }
                }
            }
            gravWas = gravOn
            x = x.coerceIn(-size * .5f, w - size * .5f)
            y = y.coerceIn(0f, (h - size).toFloat())
            val sp2 = hypot(vx, vy) / dens
            val lean = if (arrived && peek != 0) 14f * peek else 0f
            val hopOff = if (hop > 0f) sin(PI.toFloat() * (1f - hop / .5f)) * 28f * dens else 0f
            var tilt = (vx / dens * 0.18f).coerceIn(-14f, 14f) + lean
            var bob = sin(t * 10f) * min(3f, sp2 / 18f) * dens - hopOff
            var sway = 0f
            if (dancing) {
                bob -= abs(sin(PI.toFloat() * 2.07f * t)) * 12f * dens
                tilt += sin(PI.toFloat() * 2.07f * t) * 10f
                sway = sin(PI.toFloat() * 1.03f * t) * 14f * dens
            }
            v.tilt = tilt; v.bob = bob
            v.gx = if (arrived && peek != 0) -peek * 5f else (vx / dens * 0.12f).coerceIn(-6f, 6f)
            v.gy = (vy / dens * 0.08f).coerceIn(-4f, 4f)
            val ix = (x + sway).toInt(); val iy = y.toInt()
            if (ix != lp.x || iy != lp.y) {
                lp.x = ix; lp.y = iy
                try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
            }
            v.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frame)
        try { unregisterReceiver(recv) } catch (e: Exception) { }
        sm?.unregisterListener(shakeL)
        prefs.edit().putFloat("aff", affection).putInt("taps", taps).apply()
        prefs.unregisterOnSharedPreferenceChangeListener(prefL)
        try { cm?.unregisterNetworkCallback(netCb) } catch (e: Exception) { }
        tts?.stop(); tts?.shutdown(); tts = null
        view?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        view = null
        Sounds.release()
        running = false
        instance = null
        tileRefresh()
        super.onDestroy()
    }
}
