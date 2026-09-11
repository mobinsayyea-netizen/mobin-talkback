package com.ms.screenreader.gestures

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Lets a person step between the windows currently on screen - e.g. a
 * split-screen pane, a dialog sitting on top of an app, or the on-screen
 * keyboard - the way TalkBack's own window-navigation mode does (4-finger
 * swipe down/up). This is a different axis of movement from
 * [NodeNavigator], which only ever walks nodes *inside* whichever single
 * window is currently active; WindowNavigator instead walks
 * AccessibilityService#getWindows() - the full set of windows the
 * platform reports as present right now - and moves accessibility focus
 * onto a useful node inside whichever window comes next/previous.
 *
 * Windows are kept in the order the platform returns them from
 * getWindows(). Rather than guessing which one is "active" from
 * rootInActiveWindow (which only ever refers to the one application
 * window, never the status bar or keyboard), the starting point for a
 * fresh swipe is whichever window AccessibilityWindowInfo itself
 * reports as focused (isFocused()) - the same signal TalkBack's own
 * window-navigation mode is built on.
 *
 * Requires flagRetrieveInteractiveWindows in
 * accessibility_service_config.xml (already set) - without it,
 * getWindows() always returns an empty list.
 */
class WindowNavigator(private val service: AccessibilityService) {

    /** Index into the last-seen navigableWindows() list, so repeated swipes in the same direction keep advancing instead of re-finding the active window's index each time. */
    private var currentIndex = -1

    /** Moves to the next window (4-finger swipe down) and focuses it. Returns a spoken description, or null if there's nothing to switch to. */
    fun next(): String? = move(1)

    /** Moves to the previous window (4-finger swipe up) and focuses it. Returns a spoken description, or null if there's nothing to switch to. */
    fun previous(): String? = move(-1)

    /** Drops the remembered position - call this on an actual window-state change event so the next swipe re-anchors on whatever window is now active, rather than an index left over from before the change. */
    fun reset() {
        currentIndex = -1
    }

    private fun move(step: Int): String? {
        val windows = navigableWindows()
        if (windows.isEmpty()) return null

        val anchor = if (currentIndex in windows.indices) currentIndex
        else windows.indexOfFirst { it.isFocused }.let { if (it == -1) 0 else it }

        val nextIndex = ((anchor + step) % windows.size + windows.size) % windows.size
        currentIndex = nextIndex

        val chosen = windows[nextIndex]
        val result = focusWindow(chosen)

        // Recycle every window object we aren't returning a live node
        // from (API 32 and below only - see NodeNavigator's collect()
        // for why this gate exists; recycle() is a documented no-op
        // from API 33 onward since per-window node pooling was
        // removed).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            windows.forEach { it.recycle() }
        }

        return result
    }

    /**
     * The windows worth cycling through - restricted to the three
     * kinds TalkBack's own window-navigation mode switches between
     * (status/notification bar, main app content, and the on-screen
     * keyboard), each with an actual node tree to focus into. Other
     * window types Android can report (accessibility overlays,
     * split-screen dividers, etc.) are left out, matching that
     * documented three-way behavior rather than exposing every
     * technically-present window.
     */
    private fun navigableWindows(): List<AccessibilityWindowInfo> =
        (service.windows ?: emptyList()).filter {
            it.root != null && it.type in NAVIGABLE_TYPES
        }

    /**
     * Sets accessibility focus on the best available node inside
     * [window] - the first focusable node found (depth-first), falling
     * back to the window's own root if nothing inside is focusable -
     * and returns a spoken description: the window's title if the
     * platform provides one (most app windows don't; system windows
     * like the keyboard sometimes do), else a generic label naming its
     * type.
     */
    private fun focusWindow(window: AccessibilityWindowInfo): String? {
        val root = window.root ?: return null
        val target = findFocusable(root)
        (target ?: root).performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        val title = window.title?.toString()?.takeIf { it.isNotBlank() }
        return title ?: genericLabelFor(window)
    }

    /** Depth-first search for the first focusable descendant, recycling everything it doesn't keep (API 32 and below - see [move]'s kdoc). */
    private fun findFocusable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocusable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusable(child)
            if (found != null) return found
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return null
    }

    private fun genericLabelFor(window: AccessibilityWindowInfo): String = when (window.type) {
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "Keyboard window"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "System window"
        AccessibilityWindowInfo.TYPE_APPLICATION -> "App window"
        else -> "Window"
    }

    companion object {
        /** The three window types TalkBack's window-navigation mode cycles through - see [navigableWindows]'s kdoc. */
        private val NAVIGABLE_TYPES = setOf(
            AccessibilityWindowInfo.TYPE_APPLICATION,
            AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            AccessibilityWindowInfo.TYPE_SYSTEM
        )
    }
}
