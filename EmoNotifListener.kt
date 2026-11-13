package com.personal.emo

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class EmoNotifListener : NotificationListenerService() {
    override fun onNotificationPosted(s: StatusBarNotification) {
        if (s.packageName == packageName || s.isOngoing) return
        EmoService.instance?.notifPing()
    }
}
