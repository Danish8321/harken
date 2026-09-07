package com.harken.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import com.harken.android.data.AppSettings
import com.harken.android.data.SessionRepository
import com.harken.android.data.local.HarkenDatabase
import com.harken.android.speech.ModelDownloadManager
import com.harken.android.speech.NativeDecodeBreadcrumb
import com.harken.android.speech.OnDeviceTranscriber

/**
 * The app's object graph, built once and owned by [HarkenApplication].
 *
 * Five ViewModels, an activity and two services each used to construct their own
 * repository, download manager and transcriber from concrete types (ARC-014). Assembling
 * the graph seven times meant there was no "the" download manager to hold a lock on
 * (ARC-019 had to reach for a companion flag instead), and it meant opening the Library
 * pulled in the whisper JNI library on whatever thread got there first.
 *
 * Deliberately not a DI framework: this is the container pattern from the Android
 * architecture guide, which is a class with some properties. Everything is `by lazy`, so a
 * dependency is built the first time something actually asks for it — the transcriber, and
 * with it `System.loadLibrary`, only when a decode is about to run.
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    val database: HarkenDatabase by lazy { HarkenDatabase.get(appContext) }

    val repository: SessionRepository by lazy { SessionRepository(db = database) }

    val settings: AppSettings by lazy { AppSettings(appContext) }

    /**
     * One manager, so its download lock has something to be the lock *of*. Two of these
     * could append to the same `.tmp` and interleave their writes into it (ARC-019).
     */
    val modelDownloadManager: ModelDownloadManager by lazy { ModelDownloadManager(appContext) }

    val decodeBreadcrumb: NativeDecodeBreadcrumb by lazy { NativeDecodeBreadcrumb(appContext.filesDir) }

    /**
     * Shared safely because decoding is single-flight: `TranscriptionCoordinator` will not
     * start a second decode while one is running, so there is never a second caller holding
     * this object's model handle.
     */
    val transcriber: OnDeviceTranscriber by lazy { OnDeviceTranscriber(decodeBreadcrumb) }
}

/** The container, from inside a `ViewModelProvider.Factory` initializer. */
val CreationExtras.container: AppContainer
    get() = (checkNotNull(this[APPLICATION_KEY]) as HarkenApplication).container

/** The container, from an [Application] a Service or Activity already holds. */
val Application.container: AppContainer
    get() = (this as HarkenApplication).container
