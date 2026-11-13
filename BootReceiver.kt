package com.personal.emo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED &&
            c.getSharedPreferences("emo", Context.MODE_PRIVATE).getBoolean("enabled", false) &&
            Settings.canDrawOverlays(c)
        ) {
            try { c.startForegroundService(Intent(c, EmoService::class.java)) } catch (e: Exception) { }
        }
    }
}
