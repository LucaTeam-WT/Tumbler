package io.github.lucateam_wt.tumbler.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lucateam_wt.tumbler.R
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListUiState
import io.github.lucateam_wt.tumbler.ui.accounts.writesBlocked

/** Test tags of the navigation drawer and its button. */
internal object DrawerTags {
    const val OPEN_BUTTON = "drawer-open-button"
    const val SHEET = "drawer-sheet"
    const val ACCOUNTS = "drawer-accounts"
    const val EXPORT = "drawer-export"
    const val IMPORT = "drawer-import"
    const val SETTINGS = "drawer-settings"
    const val ABOUT = "drawer-about"
}

/** Whether the accounts screen offers the navigation drawer, and what its Import entry does. */
internal enum class DrawerAccess {
    /** No menu button and no swipe: accounts are being moved to the storage key ("N of M"). */
    HIDDEN,

    /** The drawer works and every entry does what it says. */
    AVAILABLE,

    /**
     * The drawer works, but Import only says why it is refused (a new storage key must be
     * started first); Export, Settings and About work.
     */
    IMPORT_REFUSED,
}

/**
 * The one place that decides the drawer from the accounts screen's storage state. While the
 * migration progress is shown, nothing may add, import or export, so there is no drawer at all;
 * while a new storage key is required, imports are refused but the rest of the drawer works.
 * Loading, an unreadable-accounts banner and a temporary storage failure do not change it.
 */
internal fun drawerAccess(state: AccountListUiState): DrawerAccess = when {
    state.migration != null -> DrawerAccess.HIDDEN
    state.writesBlocked -> DrawerAccess.IMPORT_REFUSED
    else -> DrawerAccess.AVAILABLE
}

/**
 * The drawer's content: the app name, then Accounts (the screen behind it, so always selected),
 * the Backup entries, Settings and About. Each entry is a Material navigation item, which
 * TalkBack reads with its role and selected state. System Back closes the drawer
 * ([ModalDrawerSheet] with [drawerState] handles it, with the predictive back animation).
 */
@Composable
internal fun AppDrawerSheet(
    drawerState: DrawerState,
    onAccounts: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    ModalDrawerSheet(drawerState = drawerState, modifier = Modifier.testTag(DrawerTags.SHEET)) {
        // Scrolls when the entries do not fit (large font scale, landscape).
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 28.dp, vertical = 20.dp)
                    .semantics { heading() },
            )
            DrawerEntry(R.string.accounts_title, R.drawable.ic_list, DrawerTags.ACCOUNTS, selected = true, onClick = onAccounts)
            DrawerDivider()
            DrawerSectionTitle(R.string.drawer_backup)
            DrawerEntry(R.string.backup_export_menu, R.drawable.ic_file_upload, DrawerTags.EXPORT, onClick = onExport)
            DrawerEntry(R.string.backup_import_menu, R.drawable.ic_file_download, DrawerTags.IMPORT, onClick = onImport)
            DrawerDivider()
            DrawerEntry(R.string.settings_title, R.drawable.ic_settings, DrawerTags.SETTINGS, onClick = onSettings)
            DrawerEntry(R.string.about_title, R.drawable.ic_info, DrawerTags.ABOUT, onClick = onAbout)
        }
    }
}

@Composable
private fun DrawerEntry(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    tag: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(stringResource(label)) },
        icon = { Icon(painterResource(icon), contentDescription = null) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .testTag(tag),
    )
}

@Composable
private fun DrawerSectionTitle(@StringRes label: Int) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 28.dp, vertical = 16.dp)
            .semantics { heading() },
    )
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
}
