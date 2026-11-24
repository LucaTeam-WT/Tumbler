package io.github.lucateam_wt.tumbler.ui.backup

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Stable
import io.github.lucateam_wt.tumbler.backup.PasswordCheck
import io.github.lucateam_wt.tumbler.backup.validatePassword
import io.github.lucateam_wt.tumbler.backup.validatePasswordConfirmation

/**
 * The text of one backup password field.
 *
 * It is owned by [BackupViewModel] and created with `TextFieldState()`, never with
 * `rememberTextFieldState`, `rememberSaveable` or a `SavedStateHandle`: those put the text in
 * the activity's saved instance state, a `Bundle` the system may keep after the process is
 * gone. A password must not outlive the process, so this text lives in memory only: it
 * survives a configuration change (the view model does) but not process death, after which
 * the flow starts again with empty fields.
 *
 * The field's text is held by Compose in buffers and strings this app cannot wipe; [clear]
 * empties the field and its undo history so nothing here refers to the password any more.
 * The service only ever gets a [CharArray] copy made at the last moment
 * ([toCharArrayCopy]), which the caller wipes. [toString] never prints the text
 * (`TextFieldState.toString` does).
 */
@Stable
internal class PasswordInput {

    val state = TextFieldState()

    val isEmpty: Boolean get() = state.text.isEmpty()

    @OptIn(ExperimentalFoundationApi::class)
    fun clear() {
        state.clearText()
        state.undoState.clearHistory()
    }

    override fun toString(): String = "PasswordInput(<redacted>)"
}

/** Copies [this] into a new array without making a `String`. The caller must wipe it. */
internal fun CharSequence.toCharArrayCopy(): CharArray = CharArray(length) { this[it] }

/** What the export password dialog shows while the user types. */
internal data class NewPasswordFeedback(
    /** The password's problem, or null if it meets the policy (or is still empty). */
    val password: PasswordCheck?,
    /** True if the confirmation is known not to match (not while it is still a prefix of the password). */
    val mismatch: Boolean,
    /** True if both fields are filled in and they pass [validatePasswordConfirmation]. */
    val canSubmit: Boolean,
) {
    override fun toString(): String = "NewPasswordFeedback(password=$password, mismatch=$mismatch, canSubmit=$canSubmit)"
}

/**
 * Live feedback for a new backup password and its confirmation, with the same rules as the
 * export ([validatePasswordConfirmation]). The copies it makes (through [chars], a seam for
 * tests) are wiped before it returns.
 */
internal fun newPasswordFeedback(
    password: CharSequence,
    confirmation: CharSequence,
    chars: (CharSequence) -> CharArray = CharSequence::toCharArrayCopy,
): NewPasswordFeedback {
    if (password.isEmpty()) return NewPasswordFeedback(password = null, mismatch = false, canSubmit = false)
    val first = chars(password)
    val second = chars(confirmation)
    try {
        val alone = validatePassword(first)
        if (alone != PasswordCheck.Ok) return NewPasswordFeedback(alone, mismatch = false, canSubmit = false)
        if (confirmation.isEmpty()) return NewPasswordFeedback(password = null, mismatch = false, canSubmit = false)
        val both = validatePasswordConfirmation(first, second)
        val matches = both == PasswordCheck.Ok
        // While the confirmation is still being typed, a prefix of the password is not a mismatch yet.
        val typing = confirmation.length < password.length && password.startsWith(confirmation)
        return NewPasswordFeedback(password = null, mismatch = !matches && !typing, canSubmit = matches)
    } finally {
        first.fill(WIPED_CHAR)
        second.fill(WIPED_CHAR)
    }
}

internal const val WIPED_CHAR = '\u0000'
