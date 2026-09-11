package com.ms.screenreader.labels

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

/**
 * "Add label" - lets the person give a spoken name to whichever element
 * currently has accessibility focus, for the common case of an
 * icon-only button that has no usable text/contentDescription of its
 * own. Opened by [com.ms.screenreader.gestures.GestureAction.ADD_CUSTOM_LABEL]
 * (see MSScreenReaderService.openAddLabelDialog, which is what decides
 * *whether* the currently focused node can be labelled at all - by the
 * time this screen opens, [EXTRA_KEY] is always a valid, save-able key).
 *
 * A plain foreground Activity like ScreenSearchActivity/the settings
 * screens, not an overlay - same trade-off already made there.
 *
 * Pre-fills the field with the node's existing custom label if it has
 * one (so this doubles as "Edit label"), otherwise with whatever text
 * the element was already announcing (a starting point to tweak rather
 * than typing from scratch) - see [EXTRA_CURRENT_TEXT]. Submitting a
 * blank field clears the label entirely (falls back to the element's
 * own text/ID again) rather than saving an empty string - see
 * [CustomLabelManager.saveLabel].
 */
class CustomLabelActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KEY = "label_key"
        const val EXTRA_CURRENT_TEXT = "current_text"
        const val EXTRA_EXISTING_LABEL = "existing_label"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val key = intent.getStringExtra(EXTRA_KEY)
        if (key.isNullOrBlank()) {
            // Shouldn't happen - MSScreenReaderService only launches this
            // once it already has a valid key - but fail safely rather
            // than crash if it somehow does.
            finish()
            return
        }

        val currentText = intent.getStringExtra(EXTRA_CURRENT_TEXT).orEmpty()
        val existingLabel = intent.getStringExtra(EXTRA_EXISTING_LABEL)
        val manager = CustomLabelManager(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.add_label_title)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        if (currentText.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = getString(R.string.add_label_currently_announces, currentText)
                setPadding(0, 16, 0, 0)
            })
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.add_label_hint)
            setPadding(0, 16, 0, 16)
        })

        val labelInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.add_label_field_hint)
            setText(existingLabel ?: "")
            setSelection(text.length)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            requestFocus()
        }
        root.addView(labelInput)

        val submit = {
            manager.saveLabel(key, labelInput.text?.toString().orEmpty())
            Toast.makeText(
                this,
                if (labelInput.text.isNullOrBlank()) R.string.add_label_removed else R.string.add_label_saved,
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

        labelInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        val saveButton = Button(this).apply {
            text = getString(R.string.add_label_save)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setOnClickListener { submit() }
        }
        root.addView(saveButton)

        if (!existingLabel.isNullOrBlank()) {
            val removeButton = Button(this).apply {
                text = getString(R.string.add_label_remove)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                setOnClickListener {
                    manager.removeLabel(key)
                    Toast.makeText(this@CustomLabelActivity, R.string.add_label_removed, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            root.addView(removeButton)
        }

        val cancelButton = Button(this).apply {
            text = getString(R.string.add_label_cancel)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            setOnClickListener { finish() }
        }
        root.addView(cancelButton)

        setContentView(root)
    }
}
