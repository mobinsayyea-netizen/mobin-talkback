package com.ms.screenreader.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.ms.screenreader.R
import com.ms.screenreader.calls.CallHandlingManager
import com.ms.screenreader.gestures.GestureAction
import com.ms.screenreader.gestures.GestureManager
import com.ms.screenreader.gestures.GestureRegister
import com.ms.screenreader.gestures.NodeNavigator
import com.ms.screenreader.gestures.ReadingGranularity
import com.ms.screenreader.gestures.WindowNavigator
import com.ms.screenreader.labels.CustomLabelActivity
import com.ms.screenreader.labels.CustomLabelManager
import com.ms.screenreader.menu.MainMenuActivity
import com.ms.screenreader.menu.GesturePracticeActivity
import com.ms.screenreader.menu.ScreenSearchActivity
import com.ms.screenreader.settings.SettingsRepository
import com.ms.screenreader.sounds.SoundEvent
import com.ms.screenreader.sounds.SoundSchemeManager
import com.ms.screenreader.tts.TtsManager

/**
 * Core navigation pipeline:
 * - v1.1: speaks focused/clicked elements via TTS.
 * - v1.2: plays user-configured earcons per event (SoundSchemeManager).
 * - v1.3: single-finger swipe gestures for TalkBack-style linear
 *   navigation - swipe right/left moves to the next/previous element,
 *   swipe down/up scrolls forward/backward. Only 3 of the 8 possible
 *   L-shaped (two-stroke) gestures were wired up.
 * - v1.4: notification reading with filtering (see NotificationReader).
 * - v1.5: all 8 L-shaped swipe gestures wired up - back, home,
 *   notifications, quick settings, recent apps, jump to first/last
 *   element on screen, and suspend/resume voice feedback.
 * - This version: call handling (CallHandlingManager) - announces
 *   incoming calls and finished-call duration via TTS, and answers a
 *   ringing call on a volume-key press (onKeyEvent below) when the
 *   user has that turned on. Power-button-ends-call is intentionally
 *   NOT implemented - see CallHandlingManager's kdoc for why.
 * - v1.8/v1.9: per-app gesture scheme groundwork - GestureRegister
 *   (all 43 possible 1-4 finger gestures) and SettingsRepository
 *   storage for per-app overrides and gesture-launches-app, data only.
 * - v1.10: foreground app tracking (currentForegroundPackage, updated
 *   from TYPE_WINDOW_STATE_CHANGED) - Step 2 of the per-app gesture
 *   scheme. Nothing reads this yet.
 * - v1.12: reading granularity (ReadingGranularity) - the two
 *   previously-unused swipe up-then-down / down-then-up reversal
 *   gestures cycle through Default/Character/Word/Line/List/Copy.
 *   Once a non-Default granularity is active, plain swipe down/up stop
 *   scrolling and instead step through the focused node's text a
 *   character/word/line at a time, jump between list-item nodes, or
 *   copy/append the focused text to the clipboard - see
 *   handleGranularityStep(). Every mode change is spoken by name since
 *   there's no visual indicator of which one is active.
 * - v1.13: gesture dispatch honors customizations for single-finger
 *   gestures. resolveAction() checks, in order:
 *     gesture-launches-app (works from anywhere) -> per-app override
 *     for whichever app is in the foreground -> global override ->
 *     GestureManager's hardcoded default. This is what made the
 *     "Default/Per-App Register Setting" screens actually do something
 *     instead of just storing choices nobody read yet.
 * - This version: **multi-finger gesture detection** (Step 3 of the
 *   per-app gesture scheme, docs/REMAINING_WORK.md item #1) - added
 *   onGesture(AccessibilityGestureEvent), API 33+ only, which is how
 *   Android reports 2/3/4-finger swipes and taps. Shares the same
 *   dispatchGesture() as the older single-finger onGesture(Int)
 *   overload, so all 43 GestureRegister entries (not just the 16
 *   single-finger ones) now actually fire when their gesture is
 *   performed - provided the person has assigned them something via
 *   Default/Per-App Register Setting or gesture-launches-app, since
 *   multi-finger gestures have no hardcoded default action. See
 *   dispatchGesture()'s kdoc for how the two onGesture overloads avoid
 *   double-firing on API 33+, and its own kdoc for why API 26-32
 *   simply can't reach multi-finger gestures at all.
 *   resolveAction() checks, in order:
 *     gesture-launches-app (works from anywhere) -> per-app override
 *     for whichever app is in the foreground -> global override ->
 *     GestureManager's hardcoded default. This is what makes the
 *     "Default/Per-App Register Setting" screens actually do something
 *     instead of just storing choices nobody reads yet.
 *   - **Main menu**: swipe up+right now opens MainMenuActivity (moved
 *     off TOGGLE_SPEECH, which relocated to the swipe right-then-left
 *     reversal gesture - see GestureManager's kdoc for why).
 *   - **Reading granularity now respects SettingsRepository.enabledGranularities**
 *     - cycling only visits the modes the person has left checked in
 *       the new Reading Granularities settings screen, always falling
 *       back to just DEFAULT if they somehow unchecked everything.
 *   - **Remember focus per app**: leaving an app and coming back to it
 *     restores accessibility focus to wherever it was left (e.g. typing
 *     a message in WhatsApp, going back to the home screen, then
 *     reopening WhatsApp lands back on the same element) instead of
 *     resetting. Governed by SettingsRepository.rememberFocusEnabled /
 *     readRememberedFocusOnReturn - see rememberCurrentFocus() and the
 *     TYPE_WINDOW_STATE_CHANGED handling in onAccessibilityEvent().
 *
 * Still pending: per-app scheme UI's installed-apps picker (currently
 * manual package-name entry), volume-key quick on/off toggle for the
 * service itself, real-device testing of all gestures and call
 * handling (including whether the two onGesture overloads behave on a
 * real device the way their kdoc above assumes). See
 * docs/REMAINING_WORK.md.
 */
class MSScreenReaderService : AccessibilityService() {

    companion object {
        /**
         * Lets MainMenuActivity reach the running service to trigger
         * actions like suspend/resume voice feedback without a bound
         * service connection - simplest option for a menu that's only
         * ever launched while the service is already connected (it's
         * the one that opens the menu). Nulled out in onDestroy so a
         * dead service isn't held onto by mistake.
         */
        private var instance: MSScreenReaderService? = null

        /** The running service instance, or null if the accessibility service isn't connected right now. */
        fun getRunningInstance(): MSScreenReaderService? = instance

        /** Minimum gap between two NodeNavigator rebuilds triggered by TYPE_WINDOW_CONTENT_CHANGED (see onAccessibilityEvent) - a single scroll can fire many of these events in quick succession. */
        private const val CONTENT_REFRESH_THROTTLE_MS = 250L
    }

    /**
     * Called from MainActivity right after the person grants
     * READ_PHONE_STATE/ANSWER_PHONE_CALLS. CallHandlingManager.register()
     * is normally only called once, in onServiceConnected() - if that
     * ran before the permissions existed yet, the phone-state listener
     * silently never attached (SecurityException swallowed) and stayed
     * that way forever, even after the person granted the permission
     * later. This lets MainActivity retry it once permissions are
     * actually in place, without needing the service to restart.
     */
    fun retryCallHandlingRegistration() {
        if (::callHandling.isInitialized) callHandling.register()
    }

    private lateinit var tts: TtsManager
    private lateinit var soundScheme: SoundSchemeManager
    private lateinit var gestureManager: GestureManager
    private lateinit var nodeNavigator: NodeNavigator
    private lateinit var windowNavigator: WindowNavigator
    private lateinit var settings: SettingsRepository
    private lateinit var callHandling: CallHandlingManager
    private lateinit var screenStateAnnouncer: ScreenStateAnnouncer

    private var lastSpoken: String? = null
    private var lastEventTimeMs: Long = 0L
    private var lastScrollY = -1
    private var lastContentRefreshMs: Long = 0L

    /**
     * Which granularity plain swipe up/down currently perform. Cycled
     * by the swipe-up-then-down / swipe-down-then-up reversal gestures
     * (GestureAction.NEXT_GRANULARITY / PREVIOUS_GRANULARITY - see
     * GestureManager's kdoc). Not persisted: always starts fresh at
     * DEFAULT when the service (re)connects, see ReadingGranularity's
     * kdoc for why.
     */
    private var granularity: ReadingGranularity = ReadingGranularity.DEFAULT

    /**
     * Accumulates text across repeated COPY-granularity "append" swipes
     * (swipe up while in COPY mode) so a user can build up a multi-element
     * selection before it lands on the clipboard. Reset every time a
     * fresh "copy" (swipe down in COPY mode) starts a new clipboard
     * entry rather than appending to the previous one.
     */
    private val clipboardAccumulator = StringBuilder()

    /**
     * Package name of whichever app is currently in the foreground,
     * updated from TYPE_WINDOW_STATE_CHANGED events. This is Step 2 of
     * the per-app gesture scheme (see docs/REMAINING_WORK.md item #1) -
     * tracking alone, nothing reads this yet to change gesture
     * behavior. That lookup (per-app override / gesture-launches-app)
     * gets wired in on top of this in a later step.
     *
     * Not reliable on every launcher/OS skin the instant the service
     * connects (there's no window-state event until something changes),
     * so treat null as "unknown yet" rather than "no app open".
     */
    private var currentForegroundPackage: String? = null

    /** Package name of the app currently in the foreground, or null if not known yet. */
    fun getCurrentForegroundPackage(): String? = currentForegroundPackage

    /**
     * True while a finger is actively touching the screen in touch-
     * exploration (set on TYPE_TOUCH_INTERACTION_START, cleared on
     * TYPE_TOUCH_INTERACTION_END). Real TalkBack's own accessibility-
     * volume behavior keys off exactly this - not off whether TTS
     * happens to be speaking at that instant, which is what v1.14's
     * passive AudioAttributes-only approach assumed. See onKeyEvent()
     * for where this is used.
     */
    private var touchIsDown = false

    // TYPE_VIEW_SELECTED deliberately excluded: launchers/grids fire it
    // for every icon as the grid populates or settles after a scroll,
    // with no actual user touch involved - that caused every home
    // screen icon to be auto-spoken one after another. Real user
    // navigation (swipe, touch-explore) always also produces
    // TYPE_VIEW_FOCUSED/TYPE_VIEW_HOVER_ENTER, so nothing is lost by
    // dropping TYPE_VIEW_SELECTED from here.
    private val handledEventTypes = setOf(
        AccessibilityEvent.TYPE_VIEW_FOCUSED,
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED,
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
    )

    override fun onServiceConnected() {
        super.onServiceConnected()

        // The OS can call onServiceConnected() more than once on the
        // same living instance (e.g. the service is restarted without
        // the process dying). Without this cleanup, re-running the
        // block below would silently overwrite tts/soundScheme/
        // callHandling with fresh instances while the old ones leaked:
        // the old TextToSpeech engine binding never released, the old
        // MediaPlayer never released, and the old PhoneStateListener
        // never unregistered from TelephonyManager (still firing on an
        // orphaned CallHandlingManager with no reference left to stop
        // it).
        if (::tts.isInitialized) tts.shutdown()
        if (::soundScheme.isInitialized) soundScheme.release()
        if (::callHandling.isInitialized) callHandling.unregister()
        if (::screenStateAnnouncer.isInitialized) screenStateAnnouncer.unregister()
        tts = TtsManager(this)
        soundScheme = SoundSchemeManager(this)
        gestureManager = GestureManager()
        settings = SettingsRepository(this)
        nodeNavigator = NodeNavigator(this, settings)
        windowNavigator = WindowNavigator(this)
        callHandling = CallHandlingManager(this, settings, tts)
        callHandling.register()
        screenStateAnnouncer = ScreenStateAnnouncer(this, settings, tts)
        screenStateAnnouncer.register()
        instance = this

        // The Accessibility Button isn't exposed as a plain override on
        // AccessibilityService - it has to be picked up via
        // AccessibilityButtonController, and only after the service is
        // connected (registering earlier has no effect).
        accessibilityButtonController.registerAccessibilityButtonCallback(
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    onAccessibilityButtonClicked()
                }
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Tracks whether a finger is currently down on the screen (see
        // touchIsDown's kdoc / onKeyEvent()'s accessibility-volume
        // handling). These two event types are always delivered
        // regardless of handledEventTypes since the config requests
        // typeAllMask, so this check has to happen before the
        // handledEventTypes filter below, not after.
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> touchIsDown = true
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> touchIsDown = false
        }

        // Window/content changed under us - the flattened node list from
        // NodeNavigator is now stale, so drop it and rebuild lazily on
        // the next gesture.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // preservePosition = false: a genuine new screen/window has
            // no reliable "same node" to land back on - see refresh()'s
            // kdoc for why blind text-matching here caused focus to
            // occasionally jump to an unrelated item (e.g. opening an
            // app list). Deliberately restoring a remembered spot is
            // tryRestoreRememberedFocus() below, which is opt-in and
            // per-app, not this.
            if (::nodeNavigator.isInitialized) nodeNavigator.refresh(preservePosition = false)
            if (::windowNavigator.isInitialized) windowNavigator.reset()
            // Track which app just came to the foreground (Step 2 of the
            // per-app gesture scheme). Ignore our own overlay/settings
            // window changes with no package, and same-package repeats
            // (e.g. a dialog opening inside the same app) don't need
            // re-tracking but overwriting with the same value is harmless.
            event.packageName?.toString()?.let { pkg ->
                if (pkg.isNotBlank()) {
                    currentForegroundPackage = pkg
                    tryRestoreRememberedFocus(pkg)
                    tryRunPendingScreenSearch(pkg)
                }
            }
        }

        // A *sub-tree* content change (not TYPE_WINDOW_STATE_CHANGED,
        // which fires for a whole new window) - e.g. a RecyclerView/
        // GridView (an app drawer, a long list) recycling its child
        // views as it's scrolled. Without this, NodeNavigator's
        // flatNodes list only ever reflects whichever items happened
        // to exist at the last full window change, so continuing to
        // swipe through a long recycled list eventually runs out of
        // (now-stale) nodes and feels "stuck" partway through, even
        // though the real on-screen list keeps going. Throttled since
        // a single scroll gesture can fire many of these in quick
        // succession, and refresh() rebuilding the whole node tree on
        // every one of them would be wasteful.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0
        ) {
            val now = System.currentTimeMillis()
            if (::nodeNavigator.isInitialized && now - lastContentRefreshMs > CONTENT_REFRESH_THROTTLE_MS) {
                lastContentRefreshMs = now
                // preservePosition = true: this is the same window's
                // list recycling views mid-scroll, not a new screen -
                // see refresh()'s kdoc.
                nodeNavigator.refresh(preservePosition = true)
            }
        }

        if (event.eventType !in handledEventTypes) return

        playEarconFor(event)
        speakFor(event)
        syncAccessibilityFocusFromTouch(event)
    }

    /**
     * Mirrors what real TalkBack does: whenever touch-exploration or
     * focus lands on an element - in this app's own window, or any
     * other window (system nav bar, quick settings, notification
     * shade, etc.) - give it real, system-tracked accessibility focus
     * ([AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS]), not just an
     * announcement. Android then remembers this as *the* focused node
     * globally, retrievable later via
     * `findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)` regardless
     * of which window it's in - which is exactly how [performAction]'s
     * ACTIVATE case below finds the right node to click even when it
     * lives outside [NodeNavigator]'s own app-window-only list (e.g.
     * the system nav bar's Back/Home/Overview buttons).
     */
    private fun syncAccessibilityFocusFromTouch(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_HOVER_ENTER &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            return
        }
        val node = event.source ?: return
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        } finally {
            node.recycle()
        }
    }

    /**
     * If remember-focus is on and we have a remembered element for
     * [packageName] (see NodeNavigator.rememberFocus), tries to move
     * accessibility focus back onto it now that this app's window has
     * just come to the foreground and its node list has been rebuilt.
     * Silently does nothing if the setting is off, nothing was
     * remembered, the remembered element isn't found this time (e.g.
     * the screen has changed since), or [packageName] is the home
     * screen / launcher itself (see [defaultLauncherPackage]'s kdoc).
     */
    private fun tryRestoreRememberedFocus(packageName: String) {
        if (!::settings.isInitialized || !settings.rememberFocusEnabled) return
        if (!::nodeNavigator.isInitialized) return
        if (packageName == defaultLauncherPackage()) return
        val restored = nodeNavigator.restoreRememberedFocus(packageName) ?: return
        if (::soundScheme.isInitialized) soundScheme.play(SoundEvent.FOCUS_CHANGE)
        if (settings.readRememberedFocusOnReturn) announce(restored)
    }

    private var cachedLauncherPackage: String? = null

    /**
     * The package name of whichever app is currently set as the
     * phone's Home app, or null if it can't be resolved. Cached after
     * the first lookup since it only changes if the person changes
     * their default launcher, which isn't worth re-checking on every
     * window change.
     *
     * Remember-focus is meant for apps the person navigates INTO and
     * then leaves - not the launcher itself, which is passed through
     * every single time Back/Home/Overview is used. Without this
     * exclusion, returning to the home screen by any route kept
     * restoring accessibility focus to whatever icon was focused
     * before, which felt like the reader "jumping" to an unrelated
     * spot instead of landing fresh the way TalkBack does.
     */
    private fun defaultLauncherPackage(): String? {
        cachedLauncherPackage?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName?.also { cachedLauncherPackage = it }
    }

    /**
     * Handles the system's built-in single-finger swipe gestures, detected
     * automatically once touch exploration is active (see
     * accessibility_service_config.xml). Deprecated on newer APIs in favor
     * of onGesture(AccessibilityGestureEvent), but this overload is kept
     * for broad compatibility down to minSdk 26.
     */
    @Suppress("DEPRECATION")
    override fun onGesture(gestureId: Int): Boolean {
        if (dispatchGesture(gestureId)) return true
        return super.onGesture(gestureId)
    }

    /**
     * Handles 2/3/4-finger gestures - Step 3 of the per-app gesture
     * scheme (docs/REMAINING_WORK.md item #1). Only exists from API 33
     * (Tiramisu) onward, since AccessibilityGestureEvent itself was
     * added then; on API 26-32 these registers simply aren't reachable
     * from an actual finger gesture (a platform limitation, not
     * something fixable in app code) - the deprecated onGesture(Int)
     * below still covers single-finger gestures on every supported
     * API level.
     *
     * Deliberately reuses the same dispatchGesture() as the
     * single-finger path below rather than duplicating its logic. On
     * API 33+, the two overloads never double-fire for the same
     * gesture: when this one returns true, super.onGesture() (and
     * therefore the framework's default onGesture(int) forwarding) is
     * never reached; when it returns false, falling through to
     * super.onGesture(gestureEvent) invokes onGesture(Int) as a
     * fallback with the same id, which safely finds nothing new to do
     * and also returns false.
     *
     * Multi-finger gestures have no hardcoded default action in
     * GestureManager - unlike single-finger swipes, a raw 2/3/4-finger
     * swipe means nothing until the person assigns it something via
     * the Default/Per-App Register Setting screens or
     * gesture-launches-app. That's why dispatchGesture() finding no
     * override for one of these registers is the normal, expected
     * outcome, not a bug.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        if (dispatchGesture(gestureEvent.gestureId)) return true
        return super.onGesture(gestureEvent)
    }

    /**
     * Shared by both onGesture overloads. Resolves and performs
     * whatever [gestureId] should do, in priority order:
     * gesture-launches-app (works from anywhere, any finger count) ->
     * per-app override for the foreground app -> global override ->
     * (single-finger gestures only) GestureManager's hardcoded
     * default. Returns false if nothing is assigned to this gesture at
     * all, so the caller can let the system handle it normally
     * instead.
     */
    private fun dispatchGesture(gestureId: Int): Boolean {
        if (!::gestureManager.isInitialized || !::settings.isInitialized) return false

        // Gesture Practice mode (GesturePracticeActivity, opened by a
        // 4-finger single tap - see openGesturePractice) intercepts
        // every gesture here, before any of the normal resolution
        // below runs, and just names it instead of doing anything.
        if (practiceModeEnabled) {
            val label = GestureRegister.fromAndroidGestureId(gestureId)?.label ?: return false
            announce(label)
            return true
        }

        val register = GestureRegister.fromAndroidGestureId(gestureId)
        if (register != null) {
            val appToLaunch = settings.getGestureAppLaunch(register)
            if (appToLaunch != null) {
                launchApp(appToLaunch)
                return true
            }
        }

        val action = resolveAction(register, gestureId) ?: return false
        performAction(action)
        return true
    }

    /**
     * Decides what a single-finger gesture should do, in priority
     * order: per-app override for whichever app is currently in the
     * foreground, then a global override, then GestureManager's
     * hardcoded default. Gesture-launches-app is checked separately in
     * onGesture() before this, since it isn't a GestureAction at all -
     * it opens an app directly.
     */
    private fun resolveAction(register: GestureRegister?, gestureId: Int): GestureAction? {
        if (register != null) {
            currentForegroundPackage?.let { pkg ->
                settings.getAppGestureOverrides(pkg)[register]?.let { return it }
            }
            settings.getGlobalGestureOverride(register)?.let { return it }
        }
        return gestureManager.mapGesture(gestureId)
    }

    /** Launches [packageName]'s default launch activity, if it's installed. Silently no-ops otherwise (e.g. the app was uninstalled since the gesture was set up). */
    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * Simulates a single real finger tap at [x],[y] using the same
     * gesture-dispatch mechanism as swipe navigation. Used as
     * ACTIVATE's fallback when a node's own ACTION_CLICK does nothing -
     * a real touch at the node's own on-screen position works
     * regardless of whether the view properly exposes a click action to
     * accessibility services.
     */
    private fun dispatchTapAt(x: Float, y: Float) {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * A real synthetic swipe (not a tap) from (x,y) straight up by
     * [distance] pixels. For elements that are fundamentally swipe
     * targets, not click targets - e.g. Microsoft Launcher's "Apps"
     * handle, which opens the app list on a swipe-up gesture and has no
     * click handler at all. No amount of tapping (real, double, or
     * coordinate-injected) will ever open such an element; this is why
     * SWIPE_ACTIVATE is a separate, deliberately-triggered action
     * rather than an automatic ACTIVATE fallback - we can't safely
     * guess which elements need a swipe instead of a click.
     */
    private fun dispatchSwipeUpAt(x: Float, y: Float, distance: Float = 400f) {
        val path = android.graphics.Path().apply {
            moveTo(x, y)
            lineTo(x, (y - distance).coerceAtLeast(0f))
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Intercepts hardware key events (enabled via
     * flagRequestFilterKeyEvents in accessibility_service_config.xml).
     * Used only to answer a ringing call on a volume-key press when the
     * user has turned that on in settings - every other key passes
     * through untouched so normal volume control still works.
     */
    private var volumeUpDownAtMs = 0L
    private var volumeDownDownAtMs = 0L
    private var volumeUpLongPressFired = false
    private var volumeDownLongPressFired = false
    private var pendingVolumeLongPress: Runnable? = null
    private val volumeKeyHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** How close together both volume keys need to go down to count as "pressed simultaneously". */
    private val volumeSimultaneousWindowMs = 150L

    /** How long a single volume key needs to be held before it counts as a long-press instead of a normal tap. Matches the system's own long-press timeout so it feels consistent with everything else on the device. */
    private fun longPressTimeoutMs(): Long = android.view.ViewConfiguration.getLongPressTimeout().toLong()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isVolUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isVolDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolUp && !isVolDown) return super.onKeyEvent(event)

        // Ringing-answer takes priority over the shortcuts below - a
        // ringing call is a more urgent context than "toggle speech".
        if (event.action == KeyEvent.ACTION_DOWN &&
            ::settings.isInitialized && settings.volumeAnswerEnabled &&
            ::callHandling.isInitialized && callHandling.isRinging()
        ) {
            callHandling.answerCall()
            return true // consume it - don't also change ringer volume
        }

        // Accessibility volume: real TalkBack lets a person touch and
        // hold anywhere on the screen, then press a volume key, to
        // adjust speech/earcon volume specifically (via
        // AudioManager.adjustStreamVolume(STREAM_ACCESSIBILITY, ...)) -
        // independent of whether anything happens to be speaking right
        // then, and independent of the volume-key hold/simultaneous
        // shortcuts below (own toggle, own key-down check, always runs
        // first since a touch-down volume press is a more specific,
        // deliberate signal than the shortcuts).
        if (touchIsDown && event.action == KeyEvent.ACTION_DOWN &&
            ::settings.isInitialized && settings.accessibilityVolumeViaTouchEnabled
        ) {
            val audioManager = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
            if (audioManager != null) {
                val direction = if (isVolUp) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
                audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_ACCESSIBILITY, direction, 0)
                return true // consume - don't also let it fall through to music/ringer
            }
        }

        if (!::settings.isInitialized || !settings.volumeShortcutsEnabled) return super.onKeyEvent(event)

        val now = android.os.SystemClock.uptimeMillis()

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            // Simultaneous check: did the other volume key already go
            // down recently and is still being held?
            val otherKeyRecentlyDown = if (isVolUp) {
                volumeDownDownAtMs != 0L && now - volumeDownDownAtMs < volumeSimultaneousWindowMs
            } else {
                volumeUpDownAtMs != 0L && now - volumeUpDownAtMs < volumeSimultaneousWindowMs
            }
            if (otherKeyRecentlyDown) {
                pendingVolumeLongPress?.let { volumeKeyHandler.removeCallbacks(it) }
                pendingVolumeLongPress = null
                performAction(settings.volumeSimultaneousAction)
                return true
            }

            if (isVolUp) { volumeUpDownAtMs = now; volumeUpLongPressFired = false }
            else { volumeDownDownAtMs = now; volumeDownLongPressFired = false }

            val runnable = Runnable {
                if (isVolUp) {
                    volumeUpLongPressFired = true
                    performAction(settings.volumeUpLongPressAction)
                } else {
                    volumeDownLongPressFired = true
                    performAction(settings.volumeDownLongPressAction)
                }
            }
            pendingVolumeLongPress = runnable
            volumeKeyHandler.postDelayed(runnable, longPressTimeoutMs())
            return false // don't consume yet - a short tap should still adjust volume normally
        }

        if (event.action == KeyEvent.ACTION_UP) {
            pendingVolumeLongPress?.let { volumeKeyHandler.removeCallbacks(it) }
            pendingVolumeLongPress = null
            val firedLongPress = if (isVolUp) volumeUpLongPressFired else volumeDownLongPressFired
            if (isVolUp) volumeUpDownAtMs = 0L else volumeDownDownAtMs = 0L
            if (firedLongPress) return true // consume the release too, so it doesn't also nudge the volume
        }

        return super.onKeyEvent(event)
    }

    /**
     * Called when the user taps the on-screen "Accessibility Button"
     * (enabled via the flagRequestAccessibilityButton flag inside
     * android:accessibilityFlags in accessibility_service_config.xml -
     * appears as an extra icon on
     * the navigation bar, or reachable via the floating accessibility
     * menu on gesture-nav devices). Reuses the same suspend/resume
     * logic as the swipe-up+right gesture, giving a second physical
     * way to quickly mute/unmute speech - handy on devices where the
     * volume-key "Accessibility Shortcut" is already used for
     * something else, or where the person prefers a tap over a swipe.
     */
    private fun onAccessibilityButtonClicked() {
        if (::settings.isInitialized && !settings.accessibilityShortcutEnabled) return
        performAction(GestureAction.TOGGLE_SPEECH)
    }

    /**
     * Suspends/resumes voice feedback, same as the swipe right-then-left
     * gesture or the on-screen Accessibility Button. Exposed as a public
     * entry point so MainMenuActivity (a separate Activity, not part of
     * this service) can trigger it via [getRunningInstance] without
     * needing a bound-service connection.
     */
    fun requestToggleSpeech() {
        performAction(GestureAction.TOGGLE_SPEECH)
    }

    /**
     * Fully disables this accessibility service - not just voice
     * suspend/resume (TOGGLE_SPEECH above), but the same thing as the
     * person switching it off from system Settings > Accessibility.
     * This is item #5's missing piece (docs/REMAINING_WORK.md):
     * the OS's own volume-key "Accessibility Shortcut" and the
     * on-screen Accessibility Button already exist as OS-level
     * on/off toggles for the service, but there was no way to turn
     * the whole service off *from inside* the app itself until now.
     *
     * Uses AccessibilityService.disableSelf() (public API since 24,
     * well within our minSdk 26) - the platform's own sanctioned way
     * for a service to switch itself off; nothing home-grown here.
     *
     * One-way from in-app: once disabled, this class stops running
     * entirely (onDestroy fires), so there's no in-app method to turn
     * it back on again - the person has to either go back into
     * Settings > Accessibility, or use the OS's volume-key shortcut /
     * on-screen Accessibility Button if they've set this service as
     * that shortcut's target (both are OS-level and work independently
     * of whether the service process is currently running). The
     * confirmation dialog before calling this (see MainMenuActivity)
     * exists specifically because of this one-wayness.
     */
    fun disableServiceCompletely() {
        disableSelf()
    }

    /**
     * Wraps NEXT_ELEMENT/PREVIOUS_ELEMENT so a [NodeNavigator.SCROLL_PENDING]
     * result (we hit the edge of what's currently rendered, and asked
     * the container to scroll for more) doesn't get spoken as-is or
     * silently swallowed. Waits for the scroll to actually happen, then
     * refreshes and retries the same move once. 300ms is a starting
     * point, not verified on a real device yet - some lists/launchers
     * may need it shorter or longer; if this session's fix doesn't feel
     * responsive or reliable, that delay is the first thing to tune.
     */
    private fun handleMoveWithScrollRetry(forward: Boolean, move: () -> String?) {
        val description = move()
        if (description == NodeNavigator.SCROLL_PENDING) {
            android.os.Handler(mainLooper).postDelayed({
                nodeNavigator.refresh()
                val retried = move()
                if (retried != null && retried != NodeNavigator.SCROLL_PENDING) {
                    soundScheme.play(SoundEvent.FOCUS_CHANGE)
                    announce(retried)
                    rememberCurrentFocus(retried)
                } else if (retried == null) {
                    announceListBoundary(forward)
                }
            }, 300)
        } else if (description != null) {
            soundScheme.play(SoundEvent.FOCUS_CHANGE)
            announce(description)
            rememberCurrentFocus(description)
        } else {
            // Genuinely nothing further in that direction (not just
            // "off the currently-rendered/scrollable portion") - real
            // TalkBack never goes silent here (its own strings.xml has
            // dedicated "No next/previous <item>" announcements for
            // exactly this). Previously this branch did nothing at
            // all, which is why swiping past the last item in any list
            // or menu felt like navigation had silently broken -
            // there was no way to tell "ended" from "stuck".
            announceListBoundary(forward)
        }
    }

    /** Speaks that there's nothing further in [forward]'s direction, and plays the matching boundary earcon (SCROLL_DOWN/SCROLL_UP - already labelled "reached top/bottom", previously unused for this case). */
    private fun announceListBoundary(forward: Boolean) {
        soundScheme.play(if (forward) SoundEvent.SCROLL_DOWN else SoundEvent.SCROLL_UP)
        tts.speak(if (forward) "No more items" else "No previous items")
    }

    private fun performAction(action: GestureAction) {
        when (action) {
            GestureAction.NEXT_ELEMENT -> handleMoveWithScrollRetry(forward = true) { nodeNavigator.moveNext() }
            GestureAction.PREVIOUS_ELEMENT -> handleMoveWithScrollRetry(forward = false) { nodeNavigator.movePrevious() }
            GestureAction.ACTIVATE -> {
                // Same lookup TalkBack itself relies on: whatever node
                // currently has real accessibility focus, in ANY window -
                // set a moment ago by syncAccessibilityFocusFromTouch for
                // touch-exploration, or by NodeNavigator for swipe
                // navigation. This is what lets double-tap correctly hit
                // elements outside the app's own window (e.g. the system
                // nav bar's Back/Home/Overview buttons), the same way it
                // does for elements inside it.
                val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                if (focusedNode != null) {
                    val clicked = focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        soundScheme.play(SoundEvent.CLICK)
                    } else {
                        val bounds = android.graphics.Rect()
                        focusedNode.getBoundsInScreen(bounds)
                        if (!bounds.isEmpty) {
                            dispatchTapAt(bounds.exactCenterX(), bounds.exactCenterY())
                            soundScheme.play(SoundEvent.CLICK)
                        }
                    }
                    focusedNode.recycle()
                    return
                }
                val activated = nodeNavigator.activateCurrent()
                if (activated) {
                    soundScheme.play(SoundEvent.CLICK)
                } else {
                    // ACTION_CLICK silently did nothing - some views (notably
                    // Microsoft Launcher's own "Apps" button) don't honor it
                    // even though they're clearly tappable on-screen. Fall
                    // back to simulating an actual finger tap at the node's
                    // own screen position, which works the same way a real
                    // touch would regardless of what accessibility actions
                    // the view declares support for.
                    nodeNavigator.currentNodeBoundsInScreen()?.let { bounds ->
                        if (!bounds.isEmpty) {
                            dispatchTapAt(bounds.exactCenterX(), bounds.exactCenterY())
                            soundScheme.play(SoundEvent.CLICK)
                        }
                    }
                }
            }
            GestureAction.SWIPE_ACTIVATE -> {
                // Deliberate manual fallback: use on an element where
                // double-tap (ACTION_CLICK, then coordinate-tap) does
                // nothing because the element only responds to an
                // actual swipe gesture, not a click of any kind.
                nodeNavigator.currentNodeBoundsInScreen()?.let { bounds ->
                    if (!bounds.isEmpty) {
                        dispatchSwipeUpAt(bounds.exactCenterX(), bounds.exactCenterY())
                        soundScheme.play(SoundEvent.CLICK)
                    }
                }
            }
            GestureAction.SCROLL_FORWARD -> handleGranularityStep(forward = true)
            GestureAction.SCROLL_BACKWARD -> handleGranularityStep(forward = false)
            GestureAction.GO_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GestureAction.GO_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GestureAction.OPEN_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            GestureAction.OPEN_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            GestureAction.RECENT_APPS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GestureAction.GO_TO_FIRST -> {
                val description = nodeNavigator.moveToFirst()
                soundScheme.play(SoundEvent.FOCUS_CHANGE)
                description?.let { announce(it); rememberCurrentFocus(it) }
            }
            GestureAction.GO_TO_LAST -> {
                val description = nodeNavigator.moveToLast()
                soundScheme.play(SoundEvent.FOCUS_CHANGE)
                description?.let { announce(it); rememberCurrentFocus(it) }
            }
            GestureAction.TOGGLE_SPEECH -> {
                if (::tts.isInitialized) {
                    val nowMuted = tts.toggleMute()
                    // Speech itself may just have been turned off, so rely on
                    // the earcon (and haptics, via SoundSchemeManager) rather
                    // than a spoken announcement to confirm the new state.
                    if (::soundScheme.isInitialized) {
                        soundScheme.play(if (nowMuted) SoundEvent.SPEECH_SUSPENDED else SoundEvent.SPEECH_RESUMED)
                    }
                }
            }
            GestureAction.NEXT_GRANULARITY -> cycleGranularity(forward = true)
            GestureAction.PREVIOUS_GRANULARITY -> cycleGranularity(forward = false)
            GestureAction.OPEN_MAIN_MENU -> openMainMenu()
            GestureAction.NEXT_WINDOW -> {
                val description = windowNavigator.next()
                soundScheme.play(SoundEvent.WINDOW_CHANGE)
                description?.let { announce(it) }
            }
            GestureAction.PREVIOUS_WINDOW -> {
                val description = windowNavigator.previous()
                soundScheme.play(SoundEvent.WINDOW_CHANGE)
                description?.let { announce(it) }
            }
            GestureAction.PASTE -> pasteFromClipboard()
            GestureAction.SCREEN_SEARCH -> openScreenSearch()
            GestureAction.NEXT_CONTAINER -> {
                val description = nodeNavigator.moveToNextContainer(forward = true)
                soundScheme.play(SoundEvent.FOCUS_CHANGE)
                description?.let { announce(it); rememberCurrentFocus(it) }
            }
            GestureAction.PREVIOUS_CONTAINER -> {
                val description = nodeNavigator.moveToNextContainer(forward = false)
                soundScheme.play(SoundEvent.FOCUS_CHANGE)
                description?.let { announce(it); rememberCurrentFocus(it) }
            }
            GestureAction.OPEN_GESTURE_PRACTICE -> openGesturePractice()
            GestureAction.ADD_CUSTOM_LABEL -> openAddLabelDialog()
        }
    }

    /** Records the currently focused node's description against the current foreground package, for remember-focus-per-app to use later (see NodeNavigator.rememberFocus). */
    private fun rememberCurrentFocus(description: String) {
        if (!::settings.isInitialized || !settings.rememberFocusEnabled) return
        nodeNavigator.rememberFocus(currentForegroundPackage, description)
    }

    /**
     * The subset of ReadingGranularity values the person has left
     * checked in the Reading Granularities settings screen (see
     * SettingsRepository.enabledGranularities). Falls back to just
     * DEFAULT if that set is somehow empty, so cycling never gets
     * stuck with nothing to land on.
     */
    private fun activeGranularities(): List<ReadingGranularity> {
        val enabledNames = if (::settings.isInitialized) settings.enabledGranularities else emptySet()
        val filtered = ReadingGranularity.entries.filter { it.name in enabledNames }
        return filtered.ifEmpty { listOf(ReadingGranularity.DEFAULT) }
    }

    /** Moves to the next/previous granularity within the person's enabled subset (wrapping), and announces the new mode. */
    private fun cycleGranularity(forward: Boolean) {
        val active = activeGranularities()
        val currentPosition = active.indexOf(granularity).let { if (it == -1) 0 else it }
        val nextPosition = if (forward) {
            (currentPosition + 1) % active.size
        } else {
            (currentPosition - 1 + active.size) % active.size
        }
        changeGranularity(active[nextPosition])
    }

    /** Opens the screen reader's main menu (swipe up+right). A normal Activity launched with NEW_TASK since accessibility services aren't themselves Activities. */
    private fun openMainMenu() {
        val intent = Intent(this, MainMenuActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * Applies swipe down (forward=true) / swipe up (forward=false)
     * according to whichever [ReadingGranularity] is currently active.
     * DEFAULT keeps the original v1.3 behavior (scroll the focused
     * container); every other mode steps through the focused node's
     * text (or, for LIST, jumps between list-item nodes; for COPY,
     * copies/appends to the clipboard) instead of scrolling.
     */
    private fun handleGranularityStep(forward: Boolean) {
        when (granularity) {
            ReadingGranularity.DEFAULT -> {
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                val scrolled = scrollFocusedContainer(action)
                if (scrolled) soundScheme.play(if (forward) SoundEvent.SCROLL_DOWN else SoundEvent.SCROLL_UP)
            }
            ReadingGranularity.CHARACTER -> {
                nodeNavigator.stepCharacter(forward)?.let { announce(it) }
            }
            ReadingGranularity.WORD -> {
                nodeNavigator.stepWord(forward)?.let { announce(it) }
            }
            ReadingGranularity.LINE -> {
                nodeNavigator.stepLine(forward)?.let { announce(it) }
            }
            ReadingGranularity.LIST -> {
                val description = if (forward) nodeNavigator.moveNextListItem() else nodeNavigator.movePreviousListItem()
                soundScheme.play(SoundEvent.FOCUS_CHANGE)
                description?.let { announce(it); rememberCurrentFocus(it) }
            }
            ReadingGranularity.COPY -> {
                if (forward) copyCurrentToClipboard() else appendCurrentToClipboard()
            }
        }
    }

    /** Switches the active granularity, resets its sub-node cursors, and announces the new mode by name (there's no on-screen indicator). */
    private fun changeGranularity(next: ReadingGranularity) {
        granularity = next
        nodeNavigator.resetSubNodeCursors()
        soundScheme.play(SoundEvent.GRANULARITY_CHANGE)
        announce(granularity.label)
    }

    /** COPY granularity, swipe down: replaces the clipboard with the focused node's text and starts a fresh accumulator for any following "append" swipes. */
    private fun copyCurrentToClipboard() {
        val text = nodeNavigator.currentText() ?: return
        clipboardAccumulator.clear()
        clipboardAccumulator.append(text)
        writeClipboard(clipboardAccumulator.toString())
        soundScheme.play(SoundEvent.COPIED)
        announce("Copied")
    }

    /** COPY granularity, swipe up: appends the focused node's text to whatever's already been copied/appended this session. */
    private fun appendCurrentToClipboard() {
        val text = nodeNavigator.currentText() ?: return
        if (clipboardAccumulator.isNotEmpty()) clipboardAccumulator.append("\n")
        clipboardAccumulator.append(text)
        writeClipboard(clipboardAccumulator.toString())
        soundScheme.play(SoundEvent.APPENDED)
        announce("Appended")
    }

    private fun writeClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MS Screen Reader", text))
    }

    /**
     * Swipe left-then-right: pastes whatever's on the system clipboard
     * (typically put there by the COPY granularity's copy/append swipes,
     * but could be anything - we don't restrict to our own clipboard
     * writes) into whichever node currently has accessibility focus.
     * Only works if that node is an editable text field; otherwise
     * announces that paste isn't possible there instead of doing
     * nothing silently, since a swipe that appears to do nothing is
     * hard to tell apart from one that simply wasn't registered.
     */
    private fun pasteFromClipboard() {
        val pasted = nodeNavigator.pasteIntoCurrent()
        if (pasted) {
            soundScheme.play(SoundEvent.PASTED)
            announce("Pasted")
        } else {
            announce("Cannot paste here")
        }
    }

    /**
     * "Add label" (item requested in the pasted document's "Custom
     * Labeling" section) - opens [CustomLabelActivity] pre-loaded with
     * whichever node currently has accessibility focus, so the person
     * can give it a spoken name of their own (typically an icon-only
     * button with no usable text/contentDescription). Unlike
     * [openScreenSearch], nothing needs to be deferred until this
     * screen closes - [NodeNavigator.currentLabelKey] is read *before*
     * launching the dialog, while the target node is still focused and
     * its resource ID/package are still available, and saving the
     * label afterwards is a plain SharedPreferences write that doesn't
     * need the original app back in the foreground.
     *
     * If the focused node has no resource ID at all, there's no stable
     * key to save a label against (see [CustomLabelManager]'s kdoc) -
     * this announces that instead of opening a dialog that couldn't
     * save anything.
     */
    private fun openAddLabelDialog() {
        if (!::nodeNavigator.isInitialized) return
        val key = nodeNavigator.currentLabelKey()
        if (key == null) {
            announce(getString(R.string.add_label_no_id))
            return
        }
        val intent = Intent(this, CustomLabelActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(CustomLabelActivity.EXTRA_KEY, key)
            putExtra(CustomLabelActivity.EXTRA_CURRENT_TEXT, nodeNavigator.currentText().orEmpty())
            putExtra(CustomLabelActivity.EXTRA_EXISTING_LABEL, nodeNavigator.currentLabel())
        }
        startActivity(intent)
    }

    /**
     * 3-finger tap-and-hold: opens [ScreenSearchActivity], a small text
     * field over whatever app is currently open. That Activity doesn't
     * search anything itself - it just collects the query and hands it
     * to [requestScreenSearch], which stashes it until the person's own
     * screen comes back to the foreground (see [tryRunPendingScreenSearch]),
     * since searching has to run against *that* screen's nodes, not
     * against ScreenSearchActivity's own (which briefly becomes the
     * active window while it's open).
     */
    private fun openScreenSearch() {
        val intent = Intent(this, ScreenSearchActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * A search query typed into [ScreenSearchActivity], waiting to be
     * run once that screen closes and the person's original app is back
     * in the foreground (see [tryRunPendingScreenSearch]). Null when
     * there's no pending search.
     */
    private var pendingScreenSearchQuery: String? = null

    /**
     * Called by [ScreenSearchActivity] (via [getRunningInstance]) once
     * the person submits a query. Doesn't search immediately - the
     * active window at this exact moment is still ScreenSearchActivity
     * itself, which has nothing worth searching. Stashes the query and
     * waits for the next TYPE_WINDOW_STATE_CHANGED that isn't our own
     * package, which is the original app's window coming back once
     * ScreenSearchActivity finishes.
     */
    fun requestScreenSearch(query: String) {
        pendingScreenSearchQuery = query
    }

    /**
     * Runs a pending [requestScreenSearch] query once the foreground
     * window belongs to something other than this app - i.e.
     * ScreenSearchActivity has actually closed and whatever app the
     * person was searching is active again. Ignores window-state
     * changes that are still within our own package (ScreenSearchActivity
     * opening in the first place also fires one of these) so the search
     * doesn't fire early against the search screen's own empty node
     * list. Same reasoning as [tryRestoreRememberedFocus], which this
     * mirrors.
     */
    private fun tryRunPendingScreenSearch(foregroundPackage: String) {
        val query = pendingScreenSearchQuery ?: return
        if (foregroundPackage == packageName) return
        pendingScreenSearchQuery = null
        if (!::nodeNavigator.isInitialized) return
        val description = nodeNavigator.searchAndFocus(query)
        if (::soundScheme.isInitialized) soundScheme.play(SoundEvent.FOCUS_CHANGE)
        if (description != null) {
            announce(description)
            rememberCurrentFocus(description)
        } else {
            announce("No match found")
        }
    }

    /**
     * 4-finger single tap: opens [GesturePracticeActivity], a
     * tutorial/sandbox screen (item #4 of the requested three, see
     * docs/REMAINING_WORK.md) where the person can try any gesture and
     * just hear its name spoken back, without it performing its real
     * action anywhere - mirrors TalkBack's own "practice gestures"
     * screen. Actually suppressing real actions while this is open is
     * [practiceModeEnabled]'s job, not this function's; this only
     * launches the screen that turns it on/off.
     */
    private fun openGesturePractice() {
        val intent = Intent(this, GesturePracticeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * True while [GesturePracticeActivity] is open. While this is on,
     * [dispatchGesture] short-circuits before gesture-launches-app/
     * per-app/global-override/hardcoded-default resolution entirely -
     * every gesture just gets its name spoken via TTS and nothing else
     * happens, so a person can safely try any gesture (including
     * destructive-sounding ones like "disable service" - which isn't
     * even reachable by gesture, but the principle holds for anything
     * that is) without it actually firing. Set/cleared by
     * [setPracticeMode], called from GesturePracticeActivity's
     * onCreate/onDestroy via [getRunningInstance].
     */
    private var practiceModeEnabled = false

    /** Turns Gesture Practice mode on/off - see [practiceModeEnabled]'s kdoc. */
    fun setPracticeMode(enabled: Boolean) {
        practiceModeEnabled = enabled
    }

    /**
     * Finds a scrollable node in the current window (starting from the
     * root, first scrollable container found) and performs the given
     * scroll action on it. Returns true if a scrollable node was found
     * and the action was dispatched.
     */
    private fun scrollFocusedContainer(action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollable(root) ?: return false
        return scrollable.performAction(action)
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstScrollable(child)
            if (found != null) return found
        }
        return null
    }

    /** Speaks arbitrary text immediately (used for gesture-driven navigation, not just events). */
    private fun announce(text: String) {
        if (!::tts.isInitialized || text.isBlank()) return
        lastSpoken = text
        lastEventTimeMs = System.currentTimeMillis()
        tts.speak(text)
    }

    private fun playEarconFor(event: AccessibilityEvent) {
        if (!::soundScheme.isInitialized) return

        val soundEvent = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> SoundEvent.CLICK
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> SoundEvent.LONG_PRESS
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> SoundEvent.FOCUS_CHANGE
            AccessibilityEvent.TYPE_VIEW_SELECTED -> SoundEvent.SELECTION
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> SoundEvent.WINDOW_CHANGE
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> SoundEvent.TEXT_CHANGED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> resolveScrollDirection(event)
            else -> null
        } ?: return

        soundScheme.play(soundEvent)
    }

    private fun resolveScrollDirection(event: AccessibilityEvent): SoundEvent {
        val scrollY = event.scrollY
        val direction = if (lastScrollY >= 0 && scrollY < lastScrollY) {
            SoundEvent.SCROLL_UP
        } else {
            SoundEvent.SCROLL_DOWN
        }
        lastScrollY = scrollY
        return direction
    }

    private fun speakFor(event: AccessibilityEvent) {
        if (!::tts.isInitialized) return

        // Verbosity: "Speak window names" gates only the window-change
        // announcement itself - view focus/click/etc. from inside that
        // window still speak normally regardless of this setting.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            ::settings.isInitialized && !settings.speakWindowNamesEnabled
        ) return

        // System navigation-bar buttons (Back/Home/Recents) fire both a
        // focus/hover event and a click event within milliseconds of each
        // other, often with slightly different text between the two (and
        // between gesture-nav vs 3-button-nav on the same phone) - which
        // used to slip past the identical-text dedupe below and sound
        // like a double announcement. Speaking only on click (skipping
        // hover entirely) fixed the double-announce, but broke touch-
        // exploration: dragging a finger across the nav bar to *locate*
        // Back/Home/Recents by touch before double-tapping went totally
        // silent, since only the activating click ever spoke. Real
        // TalkBack announces on hover like any other element. Fix: speak
        // on hover too (using the same fixed/live label so it's stable),
        // then treat a click for the *same* label arriving within
        // 800ms (comfortably longer than a double-tap's own timing) as
        // the expected activation-confirmation of what was just
        // announced, and skip re-speaking it - only a click with no
        // recent matching hover (e.g. a direct double-tap without
        // dragging first) speaks on the click itself.
        val navBarLabel = navigationBarButtonLabel(event)
        if (navBarLabel != null) {
            if (event.eventType != AccessibilityEvent.TYPE_VIEW_HOVER_ENTER &&
                event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
            ) return
            val now = System.currentTimeMillis()
            if (navBarLabel == lastSpoken && now - lastEventTimeMs < 800) {
                lastEventTimeMs = now
                return
            }
            lastSpoken = navBarLabel
            lastEventTimeMs = now
            tts.speak(navBarLabel)
            return
        }

        val description = extractDescription(event)
        if (description.isNullOrBlank()) return

        val now = System.currentTimeMillis()
        if (description == lastSpoken && now - lastEventTimeMs < 400) return

        lastSpoken = description
        lastEventTimeMs = now
        tts.speak(description)

        // Natural focus/hover events (touch-explore, not our own swipe
        // navigation) are just as valid a "this is where the user left
        // off" signal as a swipe - remember them too, so remember-focus
        // works whether the person swipes or drags a finger around.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
        ) {
            rememberCurrentFocus(description)
        }
    }

    /**
     * Nav-bar double-announce fix: returns a fixed, clean label ("Back",
     * "Home", "Recent apps") if [event] came from one of the system
     * navigation bar's own buttons, or null for everything else
     * (including every other systemui element, like quick settings
     * tiles or the notification shade, which should speak normally).
     * Matching is by resource ID rather than text/contentDescription,
     * since those can vary between gesture-navigation and 3-button-
     * navigation modes on the same phone - the ID is stable either way.
     *
     * The resource ID identifies which physical slot was pressed, but
     * not necessarily what it currently *does* - some phones let a
     * person swap Back and Overview in system navigation settings, and
     * the swapped button keeps its original slot-based resource ID
     * (still ":id/back" for the physically-leftmost button, say) even
     * though it now performs Overview. Android itself updates that
     * button's own contentDescription/text to reflect its real,
     * possibly-remapped function, so that live label is preferred here
     * over our fixed per-ID guess - the fixed guess is only a fallback
     * for when the system doesn't supply one.
     */
    private fun navigationBarButtonLabel(event: AccessibilityEvent): String? {
        if (event.packageName?.toString() != "com.android.systemui") return null
        val source = event.source ?: return null
        val resourceId: String?
        val liveLabel: String?
        try {
            resourceId = source.viewIdResourceName
            liveLabel = source.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: source.text?.toString()?.takeIf { it.isNotBlank() }
        } finally {
            source.recycle()
        }
        val fallback = when {
            resourceId?.endsWith(":id/back") == true -> "Back"
            resourceId?.endsWith(":id/home") == true -> "Home"
            resourceId?.endsWith(":id/home_handle") == true -> "Home"
            resourceId?.endsWith(":id/recent_apps") == true -> "Recent apps"
            else -> null
        } ?: return null
        return liveLabel ?: fallback
    }

    private fun extractDescription(event: AccessibilityEvent): String? {
        event.contentDescription?.toString()?.let { if (it.isNotBlank()) return it }

        val eventText = event.text?.filter { it.isNotBlank() }?.joinToString(", ")
        if (!eventText.isNullOrBlank()) return eventText

        val source: AccessibilityNodeInfo? = event.source
        try {
            source?.let { node ->
                node.contentDescription?.toString()?.let { if (it.isNotBlank()) return it }
                node.text?.toString()?.let { if (it.isNotBlank()) return it }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    try {
                        child.contentDescription?.toString()?.let { if (it.isNotBlank()) return it }
                        child.text?.toString()?.let { if (it.isNotBlank()) return it }
                    } finally {
                        child.recycle()
                    }
                }
            }
        } finally {
            source?.recycle()
        }
        return null
    }

    override fun onInterrupt() {
        if (::tts.isInitialized) tts.stop()
        if (::soundScheme.isInitialized) soundScheme.release()
    }

    override fun onDestroy() {
        if (::tts.isInitialized) tts.shutdown()
        if (::soundScheme.isInitialized) soundScheme.release()
        if (::callHandling.isInitialized) callHandling.unregister()
        if (::screenStateAnnouncer.isInitialized) screenStateAnnouncer.unregister()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
