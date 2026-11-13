package com.diriktv.sidekick.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Prefs

/**
 * Settings → Chat → Muted words / Blocked users (also in the player's chat filters): the list,
 * with selecting an entry removing it, and an Add item that opens the TV's keyboard.
 */
object ChatFilterLists {
    fun mutedWords(act: Activity, onChanged: () -> Unit = {}) = show(act, onChanged,
        title = "Muted words",
        subtitle = "Messages with any of these, as a whole word or phrase, are hidden. Select one to remove it.",
        addLabel = "Add a word or phrase",
        hint = "Word or phrase",
        read = { it.chatMutedWords },
        write = { p, v -> p.chatMutedWords = v },
    )

    fun blockedUsers(act: Activity, onChanged: () -> Unit = {}) = show(act, onChanged,
        title = "Blocked users",
        subtitle = "Their messages never show in chat, on the TV or your phone. Select one to unblock them.",
        addLabel = "Block someone",
        hint = "Kick username",
        read = { it.chatBlockedUsers },
        write = { p, v -> p.chatBlockedUsers = v },
    )

    private fun show(
        act: Activity,
        onChanged: () -> Unit,
        title: String,
        subtitle: String,
        addLabel: String,
        hint: String,
        read: (Prefs) -> Set<String>,
        write: (Prefs, Set<String>) -> Unit,
    ) {
        val prefs = Prefs(act)
        val sheet = OptionSheet(act)
        sheet.onDismiss = onChanged
        sheet.show(OptionSheet.Page(title, eyebrow = "Chat filters", subtitle = subtitle) {
            val current = read(prefs).sorted()
            listOf(OptionSheet.Item(addLabel, icon = R.drawable.ic_add) { s ->
                askText(act, addLabel, hint) { text ->
                    write(prefs, read(prefs) + text)
                    s.refresh()
                }
            }) + current.map { entry ->
                OptionSheet.Item(entry, value = "Remove", icon = R.drawable.ic_close) { s ->
                    write(prefs, read(prefs) - entry)
                    s.refresh()
                }
            }
        })
    }

    /** A one-line text box (the TV shows its keyboard); [onDone] gets the trimmed text, if any. */
    private fun askText(act: Activity, title: String, hint: String, onDone: (String) -> Unit) {
        val input = EditText(act).apply {
            this.hint = hint
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(act.getColor(R.color.text_primary))
            setHintTextColor(act.getColor(R.color.text_secondary))
        }
        val box = FrameLayout(act).apply {
            setPadding(act.dp(24), act.dp(8), act.dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(act, R.style.KickDialog)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Add") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onDone) }
            .setNegativeButton("Cancel", null)
            .create()
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onDone)
                dialog.dismiss()
                true
            } else false
        }
        dialog.show()
        input.requestFocus()
    }
}
