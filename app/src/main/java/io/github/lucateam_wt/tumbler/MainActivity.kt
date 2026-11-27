package io.github.lucateam_wt.tumbler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lucateam_wt.tumbler.ui.TumblerApp
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListViewModel
import io.github.lucateam_wt.tumbler.ui.backup.BackupViewModel
import io.github.lucateam_wt.tumbler.ui.theme.TumblerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = (application as TumblerApplication).container.settingsRepository
        setContent {
            // The theme choice from Settings applies to every screen and dialog at once.
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()
            val darkTheme = themeMode.useDarkTheme(isSystemInDarkTheme())
            SystemBarsAppearance(darkTheme)
            TumblerTheme(darkTheme = darkTheme) {
                TumblerApp(
                    viewModel = viewModel(factory = AccountListViewModel.Factory),
                    backupViewModel = viewModel(factory = BackupViewModel.Factory),
                )
            }
        }
    }

    /**
     * Light or dark status and navigation bar icons to match the app's theme, which may differ
     * from the system's (`enableEdgeToEdge` follows the system).
     */
    @Composable
    private fun SystemBarsAppearance(darkTheme: Boolean) {
        val view = LocalView.current
        SideEffect {
            WindowCompat.getInsetsController(window, view).run {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
}
