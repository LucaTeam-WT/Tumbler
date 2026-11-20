package io.github.lucateam_wt.tumbler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lucateam_wt.tumbler.ui.LucaAuthApp
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListViewModel
import io.github.lucateam_wt.tumbler.ui.theme.LucaAuthTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LucaAuthTheme {
                LucaAuthApp(viewModel(factory = AccountListViewModel.Factory))
            }
        }
    }
}
