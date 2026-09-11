package com.ms.screenreader.gestures

import android.accessibilityservice.AccessibilityService

/**
 * The navigation actions our gestures can trigger. Kept separate from
 * the raw Android gesture IDs so MSScreenReaderService doesn't need to
 * know about GestureDescription internals - it just asks "what action
 * does this gesture map to" and executes it.
 */
enum class GestureAction {
    NEXT_ELEMENT,
    PREVIOUS_ELEMENT,
    ACTIVATE,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    GO_BACK,
    GO_HOME,
    OPEN_NOTIFICATIONS,
    OPEN_QUICK_SETTINGS,
    RECENT_APPS,
    GO_TO_FIRST,
    GO_TO_LAST,
    TOGGLE_SPEECH,
    NEXT_GRANULARITY,
    PREVIOUS_GRANULARITY,
    OPEN_MAIN_MENU,
    NEXT_WINDOW,
    PREVIOUS_WINDOW,
    PASTE,
    SCREEN_SEARCH,
    NEXT_CONTAINER,
    PREVIOUS_CONTAINER,
    OPEN_GESTURE_PRACTICE,
    ADD_CUSTOM_LABEL,
    SWIPE_ACTIVATE
}

/**
 * Translates the system's built-in single-finger swipe gestures
 * (detected automatically by Android once touch-exploration mode is on -
 * see accessibility_service_config.xml's flagRequestTouchExplorationMode)
 * into GestureAction values.
 *
 * Mapping (TalkBack-style defaults):
 *   swipe right  -> next element
 *   swipe left   -> previous element
 *   double-tap / swipe down-then-... -> handled by system as click, not here
 *   swipe up     -> scroll backward / previous group
 *   swipe down   -> scroll forward / next group
 *   swipe left+up (L shape)  -> go back
 *   swipe left+down          -> go home
 *   swipe right+up           -> open notifications
 *   swipe right+down         -> open quick settings
 *   swipe down+left          -> recent apps (overview)
 *   swipe down+right         -> jump to last element on screen ("to end")
 *   swipe up+left            -> jump to first element on screen ("to top")
 *   swipe up+right           -> open the screen reader's main menu
 *   swipe right+left         -> suspend/resume voice feedback (moved
 *                               here from up+right - see below)
 *   swipe up-then-down       -> next reading granularity (Default/Character/Word/Line/List/Copy)
 *   swipe down-then-up       -> previous reading granularity
 *   swipe left-then-right    -> paste (pastes the system clipboard into
 *                               whichever node currently has
 *                               accessibility focus, if it's an
 *                               editable field - see
 *                               MSScreenReaderService.pasteFromClipboard
 *                               and NodeNavigator.pasteIntoCurrent).
 *                               Pairs with the COPY reading granularity,
 *                               which writes to that same clipboard.
 *   4-finger swipe down      -> next window (matches TalkBack's window-navigation mode)
 *   4-finger swipe up        -> previous window
 *   3-finger single-tap-and-hold -> "Search screen" (opens a small text
 *                               field over whatever app is open; typing
 *                               a query and submitting jumps
 *                               accessibility focus to the first
 *                               matching element on that screen - see
 *                               ScreenSearchActivity, NodeNavigator.
 *                               searchAndFocus and MSScreenReaderService.
 *                               requestScreenSearch/tryRunPendingScreenSearch)
 *   4-finger swipe left      -> previous container (jumps to the
 *                               previous list/grid on screen, skipping
 *                               over that container's individual items -
 *                               see NodeNavigator.moveToNextContainer)
 *   4-finger swipe right     -> next container
 *   4-finger single tap      -> opens Gesture Practice mode (a
 *                               tutorial/sandbox screen where every
 *                               gesture is just announced by name
 *                               instead of performing its real action -
 *                               see GesturePracticeActivity and
 *                               MSScreenReaderService.practiceModeEnabled)
 *   2-finger single tap      -> "Swipe activate" - dispatches a real
 *                               swipe-up (not a tap) at the focused
 *                               element's position. Manual fallback for
 *                               elements that only respond to a swipe,
 *                               never a click (e.g. Microsoft
 *                               Launcher's "Apps" handle) - use this
 *                               when double-tap does nothing on such an
 *                               element (see MSScreenReaderService's
 *                               SWIPE_ACTIVATE case and dispatchSwipeUpAt)
 *
 * These four newest additions (PASTE plus the three multi-finger
 * defaults above) are the first *hardcoded* defaults on gestures that
 * previously had none at all (see GestureRegister's kdoc on why
 * multi-finger gestures didn't used to have defaults) - they're
 * regular defaults like any other, so a person's own per-app/global
 * override still takes priority over them exactly as with every other
 * entry in this file (see MSScreenReaderService.resolveAction).
 *
 * All eight single-stroke and L-shaped (two-stroke) gestures Android's
 * touch-exploration mode detects are now wired up here, plus all four
 * of the single-finger "reversal" gestures (see GestureRegister's
 * kdoc) - left-then-right (the last one that had no default) now
 * carries PASTE.
 *
 * The two 4-finger swipe defaults are the first *multi-finger* gesture
 * defaults hardcoded here; every other 2/3/4-finger register still has
 * no default (see GestureRegister's kdoc and resolveAction()'s
 * comment on why that's normal) until the person assigns one, or
 * until a future default is added the same way these two were.
 *
 * Note on the up+right reassignment: earlier versions used swipe
 * up+right for TOGGLE_SPEECH. It now opens the main menu instead (a
 * screen-reader-menu entry point was requested to live on that
 * gesture specifically), so TOGGLE_SPEECH moved to the previously
 * unused right-then-left reversal gesture to keep a quick way to
 * mute/unmute speech. The on-screen Accessibility Button still
 * triggers TOGGLE_SPEECH directly regardless of this mapping (see
 * MSScreenReaderService.onAccessibilityButtonClicked).
 *
 * The two granularity reversal gestures don't change what swipe
 * up/down *mean* by themselves - they just fire
 * GestureAction.NEXT_GRANULARITY / PREVIOUS_GRANULARITY. It's
 * MSScreenReaderService that tracks which ReadingGranularity is
 * currently active and, once it's anything other than DEFAULT,
 * reinterprets SCROLL_FORWARD/SCROLL_BACKWARD (still what swipe
 * down/up map to here) as that granularity's forward/backward step
 * instead of a container scroll.
 *
 * mapGesture() only ever returns the *hardcoded* action for a given
 * Android gesture id - it has no knowledge of the person's own
 * customizations. MSScreenReaderService.resolveAction() is what
 * actually decides what a gesture does at dispatch time: it checks
 * gesture-launches-app, then the per-app override for whichever app is
 * in the foreground, then the global override, and only falls back to
 * this hardcoded mapping if none of those are set. See
 * MSScreenReaderService's kdoc and SettingsRepository's per-app/global
 * override sections.
 */
class GestureManager {

    fun mapGesture(gestureId: Int): GestureAction? = when (gestureId) {
        AccessibilityService.GESTURE_SWIPE_RIGHT -> GestureAction.NEXT_ELEMENT
        AccessibilityService.GESTURE_SWIPE_LEFT -> GestureAction.PREVIOUS_ELEMENT
        AccessibilityService.GESTURE_SWIPE_UP -> GestureAction.SCROLL_BACKWARD
        AccessibilityService.GESTURE_SWIPE_DOWN -> GestureAction.SCROLL_FORWARD
        AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP -> GestureAction.GO_BACK
        AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN -> GestureAction.GO_HOME
        AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP -> GestureAction.OPEN_NOTIFICATIONS
        AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN -> GestureAction.OPEN_QUICK_SETTINGS
        AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT -> GestureAction.RECENT_APPS
        AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT -> GestureAction.GO_TO_LAST
        AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT -> GestureAction.GO_TO_FIRST
        AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT -> GestureAction.OPEN_MAIN_MENU
        AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT -> GestureAction.TOGGLE_SPEECH
        AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN -> GestureAction.NEXT_GRANULARITY
        AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP -> GestureAction.PREVIOUS_GRANULARITY
        AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT -> GestureAction.PASTE
        AccessibilityService.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD -> GestureAction.SCREEN_SEARCH
        AccessibilityService.GESTURE_4_FINGER_SWIPE_LEFT -> GestureAction.PREVIOUS_CONTAINER
        AccessibilityService.GESTURE_4_FINGER_SWIPE_RIGHT -> GestureAction.NEXT_CONTAINER
        AccessibilityService.GESTURE_4_FINGER_SINGLE_TAP -> GestureAction.OPEN_GESTURE_PRACTICE
        AccessibilityService.GESTURE_4_FINGER_SWIPE_DOWN -> GestureAction.NEXT_WINDOW
        AccessibilityService.GESTURE_4_FINGER_SWIPE_UP -> GestureAction.PREVIOUS_WINDOW
        AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP -> GestureAction.SWIPE_ACTIVATE
        else -> null
    }

    /** Legacy no-op kept for source compatibility with earlier v1.0 callers. */
    fun handleGesture(gesture: String) { /* superseded by mapGesture(gestureId: Int) */ }
}
