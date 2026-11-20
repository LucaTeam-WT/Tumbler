package io.github.lucateam_wt.tumbler

import android.app.Application
import io.github.lucateam_wt.tumbler.di.AppContainer

class TumblerApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}
