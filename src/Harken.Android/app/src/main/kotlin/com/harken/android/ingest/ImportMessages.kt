package com.harken.android.ingest

import androidx.annotation.StringRes
import com.harken.android.R

/**
 * What an import that did not happen is told to the user.
 *
 * One mapping, in one place, because there are two callers: [ImportService], which has only
 * a notification to say it on, and `ImportViewModel`, which asks the same question before
 * copying anything. Two copies of these five strings would have drifted the first time one
 * of them was reworded.
 *
 * Both `when`s below are exhaustive and neither has an `else`. That is the point: a new
 * failure or a new refusal is a compile error here rather than a silent fall-through to
 * "something went wrong".
 */
@StringRes
fun ImportOutcome.messageRes(): Int =
    when (this) {
        ImportOutcome.NoAudioTrack -> R.string.import_failed_no_audio
        ImportOutcome.UnsupportedFormat -> R.string.import_failed_unsupported
        ImportOutcome.DecodeFailed -> R.string.import_failed_decode
        ImportOutcome.StorageFailed -> R.string.import_failed_storage
        // Neither of these is ever shown: one succeeded, and the other the user did on
        // purpose — reporting their own Cancel back to them is the app talking to itself.
        // They resolve to something rather than throwing, so a caller that gets the branch
        // wrong shows a clumsy message instead of crashing an import that worked.
        is ImportOutcome.Imported -> R.string.import_failed_unknown
        ImportOutcome.Cancelled -> R.string.import_failed_unknown
    }

/** Why an import was refused, or null for one that was admitted and has nothing to say. */
@StringRes
fun ImportAdmission.messageRes(): Int? =
    when (this) {
        ImportAdmission.RecordingInProgress -> R.string.import_refused_recording
        ImportAdmission.AlreadyImporting -> R.string.import_refused_busy
        is ImportAdmission.Admitted -> null
    }
