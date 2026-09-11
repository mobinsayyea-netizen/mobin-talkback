package com.ms.screenreader.menu

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ms.screenreader.R
import com.ms.screenreader.accessibility.MSScreenReaderService

/**
 * "Gesture Practice" - a tutorial/sandbox screen (item #3 of the
 * requested three multi-finger features, docs/REMAINING_WORK.md),
 * opened by a 4-finger single tap from anywhere (see GestureManager:
 * GESTURE_4_FINGER_SINGLE_TAP -> GestureAction.OPEN_GESTURE_PRACTICE).
 * Mirrors TalkBack's own "practice gestures" screen: while this is
 * open, any gesture the person performs - anywhere, not just on this
 * screen's own view - is just spoken back by name via TTS instead of
 * doing whatever it would normally do, so someone learning the app's
 * gestures can try them safely without accidentally opening an app,
 * going back a screen, or (in principle, though it isn't itself
 * gesture-reachable) disabling the service.
 *
 * The actual interception happens in
 * MSScreenReaderService.dispatchGesture, gated on
 * [MSScreenReaderService.practiceModeEnabled] - this Activity's only
 * job is flipping that flag on when it opens and off when it closes,
 * via [MSScreenReaderService.getRunningInstance] (no bound-service
 * connection, same pattern MainMenuActivity already uses for
 * suspend/resume speech and disable-service).
 *
 * Turning practice mode off happens in both onDestroy() and the
 * explicit "Done" button (which just calls finish() - onDestroy does
 * the actual work either way) so it's also cleared if the person
 * leaves via the system Back button/gesture or task-switches away,
 * rather than only when they tap Done.
 */
class GesturePracticeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val service = MSScreenReaderService.getRunningInstance()
        if (service == null) {
            Toast.makeText(this, R.string.main_menu_service_not_running, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        service.setPracticeMode(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.gesture_practice_title)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.gesture_practice_hint)
            setPadding(0, 16, 0, 32)
        })

        val doneButton = Button(this).apply {
            text = getString(R.string.gesture_practice_done)
            setOnClickListener { finish() }
        }
        root.addView(doneButton)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        MSScreenReaderService.getRunningInstance()?.setPracticeMode(false)
        super.onDestroy()
    }
}
