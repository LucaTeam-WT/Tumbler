package com.phucho0237.lucaauth.ui.accounts

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.phucho0237.lucaauth.R
import kotlinx.coroutines.launch

/** Seconds left at which the countdown switches to the attention color. */
private const val ATTENTION_SECONDS = 5

/** From this font scale on, the code moves under the names so nothing gets squeezed or clipped. */
private const val STACKED_FONT_SCALE = 1.5f

/** From this font scale on, issuer and account name may use two lines instead of one. */
private const val LARGE_FONT_SCALE = 1.3f

/** Duration of the fade-in of the next TOTP code. */
private const val NEXT_CODE_FADE_MILLIS = 400

private val FAB_CLEARANCE = 88.dp
private val ROW_MIN_HEIGHT = 72.dp
private val RING_SIZE = 28.dp

/** Test tags for the parts of the list that tests need to reach. */
internal object AccountListTags {
    fun row(id: String) = "account-row-$id"
    const val MENU_COPY = "account-menu-copy"
    const val MENU_NEXT_CODE = "account-menu-next-code"
    const val MENU_DELETE = "account-menu-delete"
    const val NEXT_CODE = "account-next-code"
}

/**
 * Something the user can do with an account row. Each action is offered in the long-press
 * menu and as a TalkBack custom action; to add one (edit, show QR, pin), add a value here,
 * list it in [rowActions] and handle it in [AccountListScreen].
 *
 * @property label menu item text.
 * @property labelForAccount accessibility label that names the account (one `%1$s`).
 */
private enum class RowAction(
    @StringRes val label: Int,
    @StringRes val labelForAccount: Int,
    @DrawableRes val icon: Int,
    val tag: String,
) {
    COPY_CODE(
        R.string.action_copy_code,
        R.string.action_copy_code_for,
        R.drawable.ic_content_copy,
        AccountListTags.MENU_COPY,
    ),
    NEXT_CODE(
        R.string.account_next_code,
        R.string.action_next_code_for,
        R.drawable.ic_refresh,
        AccountListTags.MENU_NEXT_CODE,
    ),
    DELETE(
        R.string.account_delete,
        R.string.action_delete_for,
        R.drawable.ic_delete,
        AccountListTags.MENU_DELETE,
    ),
}

/** The code the row can copy, or `null` if there is none (unavailable, or HOTP before the first code). */
private fun AccountItem.copyableCode(): String? = when (this) {
    is AccountItem.TotpItem -> code
    is AccountItem.HotpItem -> code
    is AccountItem.UnavailableItem -> null
}

private fun AccountItem.rowActions(): List<RowAction> = when (this) {
    is AccountItem.TotpItem -> listOf(RowAction.COPY_CODE, RowAction.DELETE)
    is AccountItem.HotpItem -> listOfNotNull(
        RowAction.COPY_CODE.takeIf { code != null },
        RowAction.NEXT_CODE,
        RowAction.DELETE,
    )
    is AccountItem.UnavailableItem -> listOf(RowAction.DELETE)
}

/** "Issuer (name)", or just the name when there is no issuer. */
private fun AccountItem.displayName(): String =
    if (issuer.isEmpty()) accountName else "$issuer ($accountName)"

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

    val performAction: (AccountItem, RowAction) -> Unit = { item, action ->
        when (action) {
            RowAction.COPY_CODE -> item.copyableCode()?.let { code ->
                onCopyCode(code)
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(copiedMessage)
                }
            }
            RowAction.NEXT_CODE -> onNextHotpCode(item.id)
            RowAction.DELETE -> pendingDeleteId = item.id
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.accounts_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                // The extended FAB hides its label from accessibility and takes the button's name
                // from the icon, so the icon carries the description (not null).
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.accounts_add),
                    )
                },
                text = { Text(stringResource(R.string.accounts_add)) },
            )
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        AccountRow(item = item, onAction = { performAction(item, it) })
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

/**
 * One compact account row. Tap copies the code (for rows without a code it opens the menu),
 * long-press opens the options menu, and the same actions are TalkBack custom actions. The
 * HOTP "next code" icon button sits beside the clickable area, not inside it, so the two
 * never merge into one accessibility node.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountRow(item: AccountItem, onAction: (RowAction) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val name = item.displayName()
    val actions = item.rowActions()
    val hasCode = item.copyableCode() != null
    val customActions = actions.map { action ->
        CustomAccessibilityAction(stringResource(action.labelForAccount, name)) {
            onAction(action)
            true
        }
    }
    val showOptionsLabel = stringResource(R.string.action_show_options)
    val tapLabel = if (hasCode) stringResource(R.string.action_copy_code) else showOptionsLabel

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.heightIn(min = ROW_MIN_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .testTag(AccountListTags.row(item.id))
                        .combinedClickable(
                            onClickLabel = tapLabel,
                            onClick = { if (hasCode) onAction(RowAction.COPY_CODE) else menuOpen = true },
                            onLongClickLabel = showOptionsLabel,
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuOpen = true
                            },
                        )
                        .semantics { this.customActions = customActions }
                        .padding(
                            start = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp,
                            end = if (item is AccountItem.HotpItem) 4.dp else 16.dp,
                        ),
                ) {
                    RowContent(item)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(stringResource(action.label)) },
                            leadingIcon = { Icon(painterResource(action.icon), contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onAction(action)
                            },
                            modifier = Modifier.testTag(action.tag),
                        )
                    }
                }
            }
            if (item is AccountItem.HotpItem) {
                IconButton(onClick = { onAction(RowAction.NEXT_CODE) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.action_next_code_for, name),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowContent(item: AccountItem) {
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    if (stacked) {
        Column {
            Names(item, Modifier.fillMaxWidth())
            if (item !is AccountItem.UnavailableItem) {
                Spacer(Modifier.height(4.dp))
                CodeAndCountdown(item)
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Names(item, Modifier.weight(1f))
            if (item !is AccountItem.UnavailableItem) {
                Spacer(Modifier.width(12.dp))
                CodeAndCountdown(item)
            }
        }
    }
}

@Composable
private fun Names(item: AccountItem, modifier: Modifier = Modifier) {
    // Long names are cut with an ellipsis so one row cannot grow much taller than the others.
    // The Text semantics (and the dialog and custom actions) still carry the full text.
    val nameMaxLines = if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) 2 else 1
    Column(modifier) {
        if (item.issuer.isNotEmpty()) {
            Text(
                text = item.issuer,
                style = MaterialTheme.typography.titleMedium,
                maxLines = nameMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = item.accountName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = nameMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (item is AccountItem.UnavailableItem) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.account_unavailable_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.account_unavailable_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CodeAndCountdown(item: AccountItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item) {
            is AccountItem.TotpItem -> TotpCodeBlock(item)
            is AccountItem.HotpItem -> if (item.code != null) {
                Code(item.code)
            } else {
                val description = stringResource(R.string.account_hotp_no_code_description)
                Text(
                    text = stringResource(R.string.account_hotp_no_code_placeholder),
                    style = codeStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
            is AccountItem.UnavailableItem -> Unit
        }
    }
}

/** Monospace so the width of the code does not jump from one code to the next. */
@Composable
private fun codeStyle() = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace)

@Composable
private fun Code(code: String) {
    Text(
        text = formatCode(code),
        style = codeStyle(),
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Current code, the next code (see [NextCode]) and the countdown ring. The ring is centered on
 * the current code's line, not on the whole block.
 *
 * Normal font scales: the next code sits under the current code, aligned to its end edge.
 * From [STACKED_FONT_SCALE]: both codes share one line and the next code wraps below the
 * current one only if they do not fit, so nothing is clipped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TotpCodeBlock(item: AccountItem.TotpItem) {
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val codeLine = codeStyle().lineHeight
    val ringSlotHeight = with(LocalDensity.current) {
        if (codeLine.isSpecified) maxOf(codeLine.toDp(), RING_SIZE) else RING_SIZE
    }
    Row(verticalAlignment = Alignment.Top) {
        if (stacked) {
            FlowRow(
                modifier = Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Code(item.code)
                NextCode(item)
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Code(item.code)
                NextCode(item)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.height(ringSlotHeight), contentAlignment = Alignment.Center) {
            CountdownRing(item.secondsRemaining, item.period)
        }
    }
}

/**
 * The code that replaces the current one, shown in the last seconds of a step. It is
 * display-only: tapping the row copies the current code. Its semantics (description and test
 * tag) exist as soon as [AccountItem.TotpItem.nextCode] is set; the fade-in only affects what
 * is drawn, and there is no fade-out. Outside the window the line is still laid out,
 * invisible and without semantics, from the current code's digits (same text shape, so the
 * same size), so the row keeps its size when the next code appears or disappears.
 */
@Composable
private fun NextCode(item: AccountItem.TotpItem) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val nextCode = item.nextCode
    if (nextCode == null) {
        Text(
            text = stringResource(R.string.account_next_totp_code, formatCode(item.code)),
            style = style,
            maxLines = 1,
            modifier = Modifier.alpha(0f).clearAndSetSemantics { },
        )
    } else {
        // This branch enters the composition when the next code appears, so the fade runs
        // once per step and not on every tick.
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val alpha by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = tween(durationMillis = NEXT_CODE_FADE_MILLIS, easing = FastOutSlowInEasing),
            label = "nextCodeAlpha",
        )
        val formatted = formatCode(nextCode)
        val description = stringResource(R.string.account_next_totp_code_description, formatted)
        Text(
            text = stringResource(R.string.account_next_totp_code, formatted),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .graphicsLayer { this.alpha = alpha }
                .testTag(AccountListTags.NEXT_CODE)
                .semantics { contentDescription = description },
        )
    }
}

@Composable
private fun CountdownRing(secondsRemaining: Int, period: Int) {
    val color = if (secondsRemaining <= ATTENTION_SECONDS) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val description = stringResource(R.string.account_seconds_remaining, secondsRemaining)
    CircularProgressIndicator(
        progress = { secondsRemaining.toFloat() / period },
        modifier = Modifier.size(RING_SIZE).semantics { contentDescription = description },
        color = color,
        strokeWidth = 3.dp,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun DeleteDialog(item: AccountItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_dialog_title)) },
        text = { Text(stringResource(R.string.delete_dialog_body, item.displayName())) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.add_cancel)) }
        },
    )
}
