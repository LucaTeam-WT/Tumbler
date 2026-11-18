package com.phucho0237.lucaauth

import android.app.Application
import com.phucho0237.lucaauth.di.AppContainer

class LucaAuthApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}
