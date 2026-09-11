package com.ms.screenreader.menu

import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ms.screenreader.R
import com.ms.screenreader.accessibility.MSScreenReaderService

/**
 * "Search screen" - opened by a 3-finger tap-and-hold from anywhere
 * (see GestureManager: GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD ->
 * GestureAction.SCREEN_SEARCH), the requested "search on screen"
 * feature.
 *
 * Deliberately a plain (non-dialog-themed) Activity like the settings
 * screens rather than an accessibility overlay: TalkBack's real "Find
 * on screen" draws its search box as a system overlay so it can show
 * live results without leaving the app being searched, but that needs
 * TYPE_ACCESSIBILITY_OVERLAY - same complexity/permission trade-off
 * MainMenuActivity's kdoc already decided against for the main menu.
 * This screen briefly takes over the foreground instead, the same way
 * MainMenuActivity, the gesture-register screens, etc. all already do.
 *
 * Doesn't search anything itself - by the time this screen is open,
 * whatever app the person wants to search is no longer the active
 * window, so its nodes aren't available to search here anyway. This
 * only collects the query and hands it to the running service via
 * [MSScreenReaderService.requestScreenSearch], which stashes it and
 * runs the actual search once this screen closes and the original
 * app's window comes back to the foreground (see that function's kdoc,
 * and MSScreenReaderService.tryRunPendingScreenSearch).
 */
class ScreenSearchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.screen_search_title)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.screen_search_hint)
            setPadding(0, 16, 0, 16)
        })

        val queryInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            hint = getString(R.string.screen_search_field_hint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            requestFocus()
        }
        root.addView(queryInput)

        val submit = {
            val query = queryInput.text?.toString().orEmpty()
            if (query.isBlank()) {
                Toast.makeText(this, R.string.screen_search_empty_query, Toast.LENGTH_SHORT).show()
            } else {
                val service = MSScreenReaderService.getRunningInstance()
                if (service != null) {
                    service.requestScreenSearch(query)
                } else {
                    Toast.makeText(this, R.string.main_menu_service_not_running, Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }

        queryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else {
                false
            }
        }

        val searchButton = Button(this).apply {
            text = getString(R.string.screen_search_button)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setOnClickListener { submit() }
        }
        root.addView(searchButton)

        val cancelButton = Button(this).apply {
            text = getString(R.string.screen_search_cancel)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            setOnClickListener { finish() }
        }
        root.addView(cancelButton)

        setContentView(root)
    }
}
