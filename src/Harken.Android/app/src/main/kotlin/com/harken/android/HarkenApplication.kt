package com.harken.android

import android.app.Application

/**
 * Owns the app's single [AppContainer].
 *
 * Nothing is built here: the container's members are lazy, so process start still does no
 * database or JNI work. This class exists to give the graph one owner with the right
 * lifetime — the process — rather than five ViewModels each building their own (ARC-014).
 */
class HarkenApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
