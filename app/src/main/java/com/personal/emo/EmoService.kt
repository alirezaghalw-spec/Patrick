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
import android.service.quicksettings.TileService
import android.view.*
import java.util.Calendar
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
                Intent.ACTION_POWER_CONNECTED -> { setMood("love", 3000); sfx("love"); hop = .5f }
                Intent.ACTION_POWER_DISCONNECTED -> { setMood("wow", 1500); sfx("wow") }
                Intent.ACTION_BATTERY_LOW -> { setMood("sad", 8000); sfx("sleepy") }
                Intent.ACTION_SCREEN_OFF -> pause()
                Intent.ACTION_SCREEN_ON -> resume()
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

    fun notifPing() {
        handler.post {
            if (view == null || dragging || hidden) return@post
            setMood("wow", 1500); sfx("chirp"); hop = .5f
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
            fgKind = kindOf(fgPkg)
        } catch (e: Exception) { fgKind = "none" }
    }
