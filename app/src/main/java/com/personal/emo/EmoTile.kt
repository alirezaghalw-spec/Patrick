package com.personal.emo

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class EmoTile : TileService() {
    override fun onStartListening() { setState(EmoService.running) }

    override fun onClick() {
        val turningOn = !EmoService.running
        if (!turningOn) {
            startService(Intent(this, EmoService::class.java).setAction(EmoService.STOP))
        } else if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, EmoService::class.java))
        } else {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            else startActivityAndCollapse(i)
            return
        }
        setState(turningOn)
    }

    private fun setState(on: Boolean) {
        qsTile?.let {
            it.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = "ایمو"
            it.updateTile()
        }
    }
}
