package com.ms.screenreader.labels

import android.content.Context

/**
 * Lets the person give their own spoken name to an element that has no
 * usable text/contentDescription of its own (an icon-only button, say) -
 * TalkBack's "Add label" feature, requested via the pasted document's
 * "Custom Labeling" section.
 *
 * Storage is a single SharedPreferences file, keyed by
 * "<packageName>/<viewIdResourceName>" (see [keyFor]). That resource ID
 * is the *only* stable handle accessibility gives us for an element
 * across app restarts, screen rotations, and list recycling - there is
 * no persistent per-instance node ID. Two real consequences of that:
 *
 *  - An element with no `android:id` at all (common for a plain
 *    icon inside a custom view) can't be labelled here - [keyFor]
 *    returns null for it, and the "Add label" action tells the person
 *    why instead of silently doing nothing.
 *  - A label is shared by every element on screen that happens to
 *    carry that same resource ID (e.g. every row's "more options"
 *    icon in a list all sharing one `R.id.btn_more`) - this matches
 *    how TalkBack's own labelling works for the same reason, but is
 *    worth knowing before labelling something inside a repeating list
 *    row.
 */
class CustomLabelManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("custom_labels", Context.MODE_PRIVATE)

    /** "<packageName>/<viewIdResourceName>", or null if the node has no resource ID to key off of. */
    fun keyFor(packageName: CharSequence?, viewIdResourceName: String?): String? {
        if (viewIdResourceName.isNullOrBlank()) return null
        return "$packageName/$viewIdResourceName"
    }

    fun getLabel(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() }
    }

    /** Blank [label] removes any existing label for [key] instead of storing an empty string. */
    fun saveLabel(key: String, label: String) {
        if (label.isBlank()) {
            removeLabel(key)
        } else {
            prefs.edit().putString(key, label.trim()).apply()
        }
    }

    fun removeLabel(key: String) {
        prefs.edit().remove(key).apply()
    }
}
