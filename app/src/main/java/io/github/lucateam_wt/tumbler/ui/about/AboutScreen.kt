package io.github.lucateam_wt.tumbler.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lucateam_wt.tumbler.R
import kotlinx.coroutines.launch

/** Test tags of the About screen. */
internal object AboutTags {
    const val BACK = "about-back"
    const val VERSION = "about-version"
    const val LICENSE = "about-license"
    const val SOURCE = "about-source"
}

/**
 * The app's name, version, license and a link to its source code. The link opens in another app
 * through [onOpenSource], which returns false if no app can open it; the screen then says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreen(
    version: AppVersion,
    onOpenSource: () -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val linkUnavailable = stringResource(R.string.about_link_unavailable)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(AboutTags.BACK)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.add_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.about_version, version.name, version.code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(AboutTags.VERSION),
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_license_title)) },
                supportingContent = { Text(LICENSE_ID) },
                modifier = Modifier.testTag(AboutTags.LICENSE),
            )
            val openLabel = stringResource(R.string.about_source_open)
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_source_title)) },
                supportingContent = { Text(SOURCE_CODE_URL) },
                trailingContent = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null) },
                modifier = Modifier
                    .testTag(AboutTags.SOURCE)
                    .clickable(onClickLabel = openLabel, role = Role.Button) {
                        if (!onOpenSource()) {
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(linkUnavailable)
                            }
                        }
                    },
            )
        }
    }
}
