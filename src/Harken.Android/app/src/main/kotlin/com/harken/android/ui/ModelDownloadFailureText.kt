package com.harken.android.ui

import androidx.annotation.StringRes
import com.harken.android.R
import com.harken.android.speech.ModelDownloadFailure

/**
 * The message a person sees for a failed model download. Onboarding and Settings both show
 * this, and both used to render `Throwable.message` — which is how *"Software caused
 * connection abort"* reached the screen.
 */
@StringRes
fun ModelDownloadFailure.messageRes(): Int =
    when (this) {
        ModelDownloadFailure.NoConnection -> R.string.model_download_failed_no_connection
        ModelDownloadFailure.ServerUnavailable -> R.string.model_download_failed_server
        ModelDownloadFailure.OutOfSpace -> R.string.model_download_failed_no_space
        ModelDownloadFailure.Corrupt -> R.string.model_download_failed_corrupt
        ModelDownloadFailure.Unknown -> R.string.settings_model_download_failed
    }
