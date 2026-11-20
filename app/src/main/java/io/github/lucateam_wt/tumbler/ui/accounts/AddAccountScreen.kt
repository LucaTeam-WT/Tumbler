package io.github.lucateam_wt.tumbler.ui.accounts

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lucateam_wt.tumbler.R

/**
 * Lets the user paste an `otpauth://` link. The text is deliberately kept with `remember`
 * and not `rememberSaveable`: the link contains the secret and must not be written to
 * saved instance state. For the same reason the field asks the keyboard not to learn or
 * suggest the text (`KeyboardType.Password` and auto-correct off; the text stays visible),
 * and the link is trimmed before it is passed to [onSave].
 *
 * @param error why the last save failed, shown inline; [onInputChange] is called when the
 *   user edits the text so the caller can clear it.
 * @param saving a save is running; the Save button is disabled so it cannot be sent twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    error: AddError?,
    saving: Boolean,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onInputChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var link by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.add_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    onInputChange()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.add_link_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            when (error) {
                                AddError.INVALID_LINK -> R.string.add_error_invalid_link
                                AddError.STORAGE_FAILED -> R.string.add_error_storage
                                null -> R.string.add_link_hint
                            },
                        ),
                    )
                },
                isError = error != null,
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onCancel) {
                    ButtonIcon(R.drawable.ic_close)
                    Text(stringResource(R.string.add_cancel))
                }
                Button(onClick = { onSave(link.trim()) }, enabled = link.isNotBlank() && !saving) {
                    ButtonIcon(R.drawable.ic_check)
                    Text(stringResource(R.string.add_save))
                }
            }
        }
    }
}

/** Decorative icon in front of a button label; the label already says what the button does. */
@Composable
private fun ButtonIcon(@DrawableRes icon: Int) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(ButtonDefaults.IconSize),
    )
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
}
