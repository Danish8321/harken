package com.harken.android.ui

import com.harken.android.speech.ModelDownloadFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Where the one model file is, as far as a screen is concerned. */
enum class ModelDownloadState { NotStarted, Downloading, Ready, Failed }

/**
 * The model download, as Onboarding and Settings both show it.
 *
 * One value rather than four fields travelling together: both screens held
 * `modelDownloadState` / `modelDownloadProgress` / `modelDownloadError` separately and drove
 * them from their own copy of the same twenty-line collect block, which had to be kept in
 * step by hand and was checked by nothing (ARC-065).
 */
data class ModelDownloadUi(
    val state: ModelDownloadState = ModelDownloadState.NotStarted,
    val progress: Int = 0,
    val error: ModelDownloadFailure? = null,
    /**
     * Whether a usable model is installed *right now*, which is not the same question as
     * "did the last download succeed": an update that fails leaves the previous model in
     * place, and Settings has to say so rather than offer a first-time "Download".
     */
    val present: Boolean = false,
) {
    companion object {
        /** What a screen opens on, given what is already on disk. */
        fun of(present: Boolean) =
            ModelDownloadUi(
                state = if (present) ModelDownloadState.Ready else ModelDownloadState.NotStarted,
                present = present,
            )
    }
}

private fun ModelDownloadUi.starting() = copy(state = ModelDownloadState.Downloading, error = null)

private fun ModelDownloadUi.progressed(percent: Int) = copy(progress = percent)

/** [present] is re-read at the point of failure: a failed *update* still has the old model. */
private fun ModelDownloadUi.failed(
    e: Throwable,
    present: Boolean,
) = copy(state = ModelDownloadState.Failed, error = ModelDownloadFailure.of(e), present = present)

private fun ModelDownloadUi.finished() = copy(state = ModelDownloadState.Ready, progress = 100, present = true)

/**
 * Folds `ModelDownloadManager.downloadProgress()`'s percentages into the state a screen
 * renders, starting from [start] and ending on either a `Ready` or a `Failed` value.
 *
 * Written against `Flow<Int>` rather than the manager so the branches here are reachable
 * from a JVM test — the manager itself is OkHttp and the filesystem, and `ModelProvider`
 * declares only `ensureModel`.
 *
 * A [start] already `Downloading` emits nothing and never collects: that is the second tap
 * on Download, refused here rather than by a line each caller has to remember.
 *
 * Cancellation — the user leaving the screen mid-transfer — is rethrown untouched, leaving
 * the last emitted value standing. There is no terminal state for "went away", and inventing
 * one would have the screen claim an outcome the download never reached.
 */
internal fun Flow<Int>.asDownloadUi(
    start: ModelDownloadUi,
    presentOnFailure: () -> Boolean,
): Flow<ModelDownloadUi> =
    flow {
        if (start.state == ModelDownloadState.Downloading) return@flow
        var ui = start.starting()
        emit(ui)
        try {
            collect { percent ->
                ui = ui.progressed(percent)
                emit(ui)
            }
            emit(ui.finished())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ui.failed(e, presentOnFailure()))
        }
    }
