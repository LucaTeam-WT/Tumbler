package com.phucho0237.lucaauth.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phucho0237.lucaauth.ui.accounts.AccountListScreen
import com.phucho0237.lucaauth.ui.accounts.AccountListViewModel
import com.phucho0237.lucaauth.ui.accounts.AddAccountScreen
import com.phucho0237.lucaauth.ui.accounts.copyCodeToClipboard

/** Duration of the slide and fade between the accounts list and the add screen. */
private const val SCREEN_TRANSITION_MILLIS = 300

/** The incoming and outgoing screens move by this fraction of the screen width. */
private const val SCREEN_SLIDE_FRACTION = 4

/**
 * Forward (to the add screen): the new screen enters from the end while the old one shifts a
 * little toward the start and fades. Back: the reverse. The direction follows the layout direction.
 */
internal fun screenTransition(forward: Boolean, layoutDirection: LayoutDirection): ContentTransform {
    val towardEnd = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
    val enterFrom = if (forward) towardEnd else -towardEnd
    val spec = tween<Float>(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing)
    val offsetSpec = tween<IntOffset>(SCREEN_TRANSITION_MILLIS, easing = FastOutSlowInEasing)
    val enter = slideInHorizontally(offsetSpec) { enterFrom * it / SCREEN_SLIDE_FRACTION } + fadeIn(spec)
    val exit = slideOutHorizontally(offsetSpec) { -enterFrom * it / SCREEN_SLIDE_FRACTION } + fadeOut(spec)
    return ContentTransform(enter, exit, sizeTransform = null)
}

/** Root of the UI: switches between the accounts list and the add screen. */
@Composable
fun LucaAuthApp(viewModel: AccountListViewModel) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addState by viewModel.addState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current

    fun closeAdd() {
        showAdd = false
        viewModel.resetAddState()
    }

    LaunchedEffect(addState.saved) {
        if (addState.saved) closeAdd()
    }

    BackHandler(enabled = showAdd, onBack = ::closeAdd)

    // Both screens are opaque, so the background shows through while they cross-fade.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = showAdd,
            transitionSpec = { screenTransition(forward = targetState, layoutDirection = layoutDirection) },
            label = "screen",
        ) { adding ->
            // The screen that is leaving is only drawn: no semantics, so TalkBack and tests
            // never find two screens at once.
            val leaving = adding != showAdd
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (leaving) Modifier.clearAndSetSemantics { } else Modifier),
            ) {
                if (adding) {
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
        }
    }
}
