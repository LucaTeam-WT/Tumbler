package com.phucho0237.lucaauth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phucho0237.lucaauth.ui.LucaAuthApp
import com.phucho0237.lucaauth.ui.accounts.AccountListViewModel
import com.phucho0237.lucaauth.ui.theme.LucaAuthTheme

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
