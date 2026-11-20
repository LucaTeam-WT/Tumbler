package io.github.lucateam_wt.tumbler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lucateam_wt.tumbler.ui.TumblerApp
import io.github.lucateam_wt.tumbler.ui.accounts.AccountListViewModel
import io.github.lucateam_wt.tumbler.ui.theme.TumblerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TumblerTheme {
                TumblerApp(viewModel(factory = AccountListViewModel.Factory))
            }
        }
    }
}
