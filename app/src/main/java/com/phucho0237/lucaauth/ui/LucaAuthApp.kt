package com.phucho0237.lucaauth.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phucho0237.lucaauth.ui.accounts.AccountListScreen
import com.phucho0237.lucaauth.ui.accounts.AccountListViewModel
import com.phucho0237.lucaauth.ui.accounts.AddAccountScreen
import com.phucho0237.lucaauth.ui.accounts.copyCodeToClipboard

/** Root of the UI: switches between the accounts list and the add screen. */
@Composable
fun LucaAuthApp(viewModel: AccountListViewModel) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun closeAdd() {
        showAdd = false
        viewModel.resetAddState()
    }

    LaunchedEffect(addState.saved) {
        if (addState.saved) closeAdd()
    }

    if (showAdd) {
        BackHandler(onBack = ::closeAdd)
        AddAccountScreen(
            error = addState.error,
            saving = addState.saving,
            onSave = viewModel::add,
            onCancel = ::closeAdd,
            onInputChange = viewModel::resetAddState,
        )
    } else {
        AccountListScreen(
            state = state,
            onAddClick = { showAdd = true },
            onCopyCode = { copyCodeToClipboard(context, it) },
            onNextHotpCode = viewModel::nextHotpCode,
            onDelete = viewModel::delete,
            onActionFailureShown = viewModel::actionFailureShown,
        )
    }
}
