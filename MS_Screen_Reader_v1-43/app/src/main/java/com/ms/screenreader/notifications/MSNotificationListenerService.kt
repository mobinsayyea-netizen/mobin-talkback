package com.ms.screenreader.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ms.screenreader.sounds.SoundEvent
import com.ms.screenreader.sounds.SoundSchemeManager
import com.ms.screenreader.tts.TtsManager

/**
 * Listens for posted notifications system-wide and reads the ones that
 * pass NotificationReader's filters aloud via TTS, plus plays a
 * "notification" earcon if the user has one configured.
 *
 * This is a SEPARATE Android component from MSScreenReaderService
 * (AccessibilityService) - notifications are only visible through
 * NotificationListenerService, not through accessibility events. It
 * needs its own permission grant: the user must enable "Notification
 * access" for this app in system Settings (Settings > Apps > Special
 * app access > Notification access), which is a different toggle from
 * enabling the accessibility service.
 */
class MSNotificationListenerService : NotificationListenerService() {

    private lateinit var reader: NotificationReader
    private lateinit var tts: TtsManager
    private lateinit var soundScheme: SoundSchemeManager

    /**
     * Remembers the last spoken text per notification key (sbn.key -
     * stable per logical notification, survives updates). Android calls
     * onNotificationPosted() again every time an app UPDATES an existing
     * notification (not just for genuinely new ones) - e.g. a chat
     * app's unread-count badge changing, or a progress update. Without
     * this, every such update got read aloud again in full, which is
     * why notifications seemed to "keep announcing things repeatedly".
     * Only a key whose extracted text actually changed is spoken again.
     */
    private val lastSpokenTextByKey = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        reader = NotificationReader(this)
        tts = TtsManager(this)
        soundScheme = SoundSchemeManager(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!::reader.isInitialized) return
        if (!reader.shouldRead(sbn)) return

        val spokenText = reader.extractSpokenText(sbn) ?: return
        if (lastSpokenTextByKey[sbn.key] == spokenText) return // same content already announced, this is just an update repost
        lastSpokenTextByKey[sbn.key] = spokenText

        if (::tts.isInitialized) tts.speak(spokenText)
        if (::soundScheme.isInitialized) soundScheme.play(SoundEvent.NOTIFICATION)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not read aloud - dismissals are typically not something the
        // user needs announced. Left as an extension point.
        sbn?.let { lastSpokenTextByKey.remove(it.key) }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) tts.shutdown()
        if (::soundScheme.isInitialized) soundScheme.release()
        super.onDestroy()
    }
}
