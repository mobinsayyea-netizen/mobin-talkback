package com.ms.screenreader.calls

import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.ms.screenreader.settings.SettingsRepository
import com.ms.screenreader.tts.TtsManager

/**
 * Watches phone call state (ringing / answered / ended) and:
 *  - announces incoming calls and speaks the duration of a finished
 *    call via TTS, controlled by [SettingsRepository.callerAnnouncerEnabled]
 *  - answers a ringing call on request (called from
 *    MSScreenReaderService.onKeyEvent when a volume key is pressed
 *    while ringing and [SettingsRepository.volumeAnswerEnabled] is on)
 *
 * The caller's phone number is only announced when the platform hands
 * it to the call-state listener without an extra permission grant
 * (still the case on many devices/OS versions with just
 * READ_PHONE_STATE). We deliberately do NOT request READ_CALL_LOG just
 * to guarantee the number on every device - that permission is far
 * more sensitive (full call history) than the caller-ID feature is
 * worth. When the number isn't available we just announce "Incoming
 * call" with no number.
 *
 * Power-button-ends-call is NOT implemented here: Android reserves
 * KEYCODE_POWER as a system key and never delivers it to
 * AccessibilityService.onKeyEvent, even with flagRequestFilterKeyEvents
 * set. Only the OS's own built-in accessibility setting
 * ("Accessibility > Power button ends call") can do this - a
 * third-party service has no API for it. [powerButtonEndCallSupported]
 * returns false for that reason, not because the feature is
 * unfinished - do not spend time trying to make this work.
 */
class CallHandlingManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val tts: TtsManager
) {
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val telecomManager =
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    private var isRegistered = false
    private var isCurrentlyRinging = false
    private var callStartTimeMs: Long = 0L

    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> onRinging(phoneNumber)
                TelephonyManager.CALL_STATE_OFFHOOK -> onAnswered()
                TelephonyManager.CALL_STATE_IDLE -> onIdle()
            }
        }
    }

    /**
     * Starts listening for call-state changes. Call from
     * MSScreenReaderService.onServiceConnected(). Safe to call even if
     * READ_PHONE_STATE hasn't been granted yet - it just silently won't
     * receive events until the user grants it from MainActivity.
     */
    @Suppress("DEPRECATION")
    fun register() {
        if (isRegistered) return
        try {
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            isRegistered = true
        } catch (_: SecurityException) {
            // READ_PHONE_STATE not granted yet.
        }
    }

    @Suppress("DEPRECATION")
    fun unregister() {
        if (!isRegistered) return
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
        isRegistered = false
    }

    private fun onRinging(phoneNumber: String?) {
        isCurrentlyRinging = true
        if (!settings.callerAnnouncerEnabled) return
        val announcement = if (!phoneNumber.isNullOrBlank()) {
            val contactName = lookupContactName(phoneNumber)
            "Incoming call from ${contactName ?: phoneNumber}"
        } else {
            "Incoming call"
        }
        tts.speak(announcement)
    }

    /**
     * Looks up the saved contact name for [phoneNumber], or null if
     * READ_CONTACTS isn't granted, no match is found, or the lookup
     * fails for any reason - callers fall back to the raw number.
     * Uses PhoneLookup, which matches numbers the same
     * loose way the system Phone/Contacts apps do (ignoring spacing,
     * formatting, and leading + or 0 differences).
     */
    private fun lookupContactName(phoneNumber: String): String? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            }
        } catch (_: SecurityException) {
            null // READ_CONTACTS not granted
        } catch (_: Exception) {
            null
        }
    }

    private fun onAnswered() {
        if (isCurrentlyRinging) callStartTimeMs = System.currentTimeMillis()
        isCurrentlyRinging = false
    }

    private fun onIdle() {
        if (callStartTimeMs > 0L) {
            val durationSec = (System.currentTimeMillis() - callStartTimeMs) / 1000
            if (settings.callerAnnouncerEnabled) {
                tts.speak(formatDuration(durationSec))
            }
        }
        callStartTimeMs = 0L
        isCurrentlyRinging = false
    }

    private fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "Call ended, duration $minutes minutes $seconds seconds"
        } else {
            "Call ended, duration $seconds seconds"
        }
    }

    /**
     * True while the phone is currently ringing - used by
     * MSScreenReaderService.onKeyEvent to decide whether a volume-key
     * press should be treated as "answer the call" instead of a normal
     * volume adjustment.
     */
    fun isRinging(): Boolean = isCurrentlyRinging

    /**
     * Answers the currently ringing call. Requires ANSWER_PHONE_CALLS to
     * have been granted (requested at runtime from MainActivity) and
     * Android 8.0+ (API 26) - already this app's minSdk, so no extra
     * version check is needed before calling this.
     *
     * Previously this just called acceptRingingCall() inside a bare
     * try/catch(SecurityException) that swallowed the failure with no
     * feedback at all - so if it silently failed, the person had no way
     * to tell "the permission isn't really granted" from "the button
     * press wasn't registered" from "something else broke". Two
     * concrete things this adds:
     *  1. Re-checks the permission right before calling, rather than
     *     trusting a past grant - Android auto-revokes runtime
     *     permissions for apps that go unopened for months, which is
     *     an easy trap for an app that mostly just runs as a
     *     background accessibility service and is rarely "opened" in
     *     the ordinary sense. If it's been silently revoked, this
     *     speaks that clearly instead of just failing again.
     *  2. Speaks a clear failure reason via TTS on any failure, instead
     *     of silence - both so the person isn't left guessing, and so
     *     the actual cause (permission vs. platform-refused vs. no
     *     ringing call) is distinguishable during testing.
     *
     * One thing this can't rule out or fix from app code: some OEM
     * Android skins (MIUI/Xiaomi, Vivo, Oppo/Realme, and some Samsung
     * One UI versions in particular) apply their own extra background-
     * restriction layer on top of the standard Android permission -
     * an accessibility service can hold ANSWER_PHONE_CALLS and still
     * have telecom actions silently blocked unless the app is also
     * separately whitelisted from battery optimization / given
     * "autostart"/"other permissions" in that OEM's own settings app
     * (outside the standard Android Settings > Apps > Permissions
     * screen entirely). There's no TalkBack reference behavior to
     * compare against here since TalkBack doesn't implement this
     * feature at all - so if the permission genuinely shows as granted
     * in standard Settings and this still fails, that OEM-level
     * whitelist is the next thing to check on the actual device.
     */
    fun answerCall(): Boolean {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ANSWER_PHONE_CALLS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            tts.speak("Cannot answer - permission not granted")
            return false
        }
        if (telecomManager == null) {
            tts.speak("Cannot answer - telecom service unavailable")
            return false
        }
        return try {
            telecomManager.acceptRingingCall()
            true
        } catch (e: SecurityException) {
            tts.speak("Cannot answer - permission denied by the system")
            false
        } catch (e: Exception) {
            tts.speak("Cannot answer - unexpected error")
            false
        }
    }

    /**
     * Always false - see class kdoc for why power-button-ends-call
     * cannot be implemented by a third-party accessibility service.
     */
    fun powerButtonEndCallSupported() = false

    /** True once the device is new enough for ANSWER_PHONE_CALLS/acceptRingingCall() to exist. */
    fun volumeAnswerSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
