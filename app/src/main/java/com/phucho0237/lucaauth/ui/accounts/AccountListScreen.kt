package com.phucho0237.lucaauth.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phucho0237.lucaauth.R
import kotlinx.coroutines.launch

/** Seconds left at which the countdown switches to the attention color. */
private const val ATTENTION_SECONDS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountListScreen(
    state: AccountListUiState,
    onAddClick: () -> Unit,
    onCopyCode: (String) -> Unit,
    onNextHotpCode: (String) -> Unit,
    onDelete: (String) -> Unit,
    onActionFailureShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.account_code_copied)
    val actionFailedMessage = stringResource(R.string.accounts_action_failed)
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.actionFailed) {
        if (state.actionFailed) {
            onActionFailureShown()
            // Launched in the screen's scope: clearing the flag restarts this effect, which
            // would cancel (and dismiss) a snackbar shown from inside it.
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(actionFailedMessage)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.accounts_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddClick) {
                Text(stringResource(R.string.accounts_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingState(Modifier.fillMaxSize().padding(innerPadding))
            state.accounts.isEmpty() && state.loadFailed ->
                MessageState(
                    title = stringResource(R.string.accounts_load_failed_title),
                    body = stringResource(R.string.accounts_load_failed_body),
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            state.accounts.isEmpty() ->
                MessageState(
                    title = stringResource(R.string.accounts_empty_title),
                    body = stringResource(R.string.accounts_empty_body),
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            else -> {
                val layoutDirection = LocalLayoutDirection.current
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + FAB_CLEARANCE,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.loadFailed) {
                        item(key = "load-failed") {
                            Text(
                                text = stringResource(R.string.accounts_load_failed_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    items(state.accounts, key = { it.id }) { item ->
                        AccountCard(
                            item = item,
                            onCopy = { code ->
                                onCopyCode(code)
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar(copiedMessage)
                                }
                            },
                            onNextHotpCode = { onNextHotpCode(item.id) },
                            onDelete = { pendingDeleteId = item.id },
                        )
                    }
                }
            }
        }
    }

    val deleteTarget = state.accounts.firstOrNull { it.id == pendingDeleteId }
    if (deleteTarget != null) {
        DeleteDialog(
            item = deleteTarget,
            onConfirm = {
                pendingDeleteId = null
                onDelete(deleteTarget.id)
            },
            onDismiss = { pendingDeleteId = null },
        )
    }
}

private val FAB_CLEARANCE = 88.dp

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.accounts_loading)
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = description })
    }
}

@Composable
private fun MessageState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AccountCard(
    item: AccountItem,
    onCopy: (String) -> Unit,
    onNextHotpCode: () -> Unit,
    onDelete: () -> Unit,
) {
    val code = when (item) {
        is AccountItem.TotpItem -> item.code
        is AccountItem.HotpItem -> item.code
        is AccountItem.UnavailableItem -> null
    }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (item.issuer.isNotEmpty()) {
                        Text(item.issuer, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = item.accountName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item is AccountItem.TotpItem) {
                    CountdownRing(item.secondsRemaining, item.period)
                }
            }
            Spacer(Modifier.height(8.dp))
            when (item) {
                is AccountItem.TotpItem -> Code(item.code)
                is AccountItem.HotpItem -> if (item.code != null) {
                    Code(item.code)
                } else {
                    Text(
                        text = stringResource(R.string.account_hotp_no_code),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is AccountItem.UnavailableItem -> {
                    Text(
                        text = stringResource(R.string.account_unavailable_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.account_unavailable_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (item is AccountItem.HotpItem) {
                    TextButton(onClick = onNextHotpCode) {
                        Text(stringResource(R.string.account_next_code))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.account_delete))
                }
            }
        }
    }

    if (code != null) {
        Card(onClick = { onCopy(code) }, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) { content() }
    }
}

@Composable
private fun Code(code: String) {
    Text(
        text = formatCode(code),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun CountdownRing(secondsRemaining: Int, period: Int) {
    val color = if (secondsRemaining <= ATTENTION_SECONDS) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val description = stringResource(R.string.account_seconds_remaining, secondsRemaining)
    Box(
        modifier = Modifier.size(40.dp).semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { secondsRemaining.toFloat() / period },
            modifier = Modifier.fillMaxSize(),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(text = secondsRemaining.toString(), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun DeleteDialog(item: AccountItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val name = if (item.issuer.isEmpty()) item.accountName else "${item.issuer} (${item.accountName})"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_dialog_title)) },
        text = { Text(stringResource(R.string.delete_dialog_body, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.add_cancel)) }
        },
    )
}
