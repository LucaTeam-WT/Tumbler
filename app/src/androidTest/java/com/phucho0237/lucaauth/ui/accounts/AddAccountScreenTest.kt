package com.phucho0237.lucaauth.ui.accounts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.phucho0237.lucaauth.R
import com.phucho0237.lucaauth.ui.theme.LucaAuthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AddAccountScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun show(
        error: AddError? = null,
        saving: Boolean = false,
        onSave: (String) -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            LucaAuthTheme {
                AddAccountScreen(
                    error = error,
                    saving = saving,
                    onSave = onSave,
                    onCancel = onCancel,
                    onInputChange = {},
                )
            }
        }
    }

    @Test
    fun invalidLinkErrorIsShownInline() {
        show(error = AddError.INVALID_LINK)

        composeRule.onNodeWithText(string(R.string.add_error_invalid_link)).assertIsDisplayed()
    }

    @Test
    fun storageErrorIsShownInline() {
        show(error = AddError.STORAGE_FAILED)

        composeRule.onNodeWithText(string(R.string.add_error_storage)).assertIsDisplayed()
    }

    @Test
    fun saveIsDisabledUntilALinkIsEntered() {
        show()

        composeRule.onNodeWithText(string(R.string.add_save)).assertIsNotEnabled()
    }

    @Test
    fun saveSendsTheEnteredLink() {
        var saved: String? = null
        show(onSave = { saved = it })

        composeRule.onNodeWithText(string(R.string.add_link_label)).performTextInput("otpauth://totp/a?secret=AAAA")
        composeRule.onNodeWithText(string(R.string.add_save)).assertIsEnabled().performClick()

        assertEquals("otpauth://totp/a?secret=AAAA", saved)
    }

    @Test
    fun saveTrimsSurroundingWhitespaceAndNewlines() {
        var saved: String? = null
        show(onSave = { saved = it })

        composeRule.onNodeWithText(string(R.string.add_link_label))
            .performTextInput("  otpauth://totp/a?secret=AAAA \n\t")
        composeRule.onNodeWithText(string(R.string.add_save)).performClick()

        assertEquals("otpauth://totp/a?secret=AAAA", saved)
    }

    @Test
    fun saveIsDisabledWhileSaving() {
        show(saving = true)

        composeRule.onNodeWithText(string(R.string.add_link_label)).performTextInput("otpauth://totp/a?secret=AAAA")
        composeRule.onNodeWithText(string(R.string.add_save)).assertIsNotEnabled()
    }
}
