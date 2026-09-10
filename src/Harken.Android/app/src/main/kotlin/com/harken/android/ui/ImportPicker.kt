package com.harken.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.R
import com.harken.android.export.LibraryExporter
import com.harken.android.ui.components.HarkenErrorDialog

/** What a screen needs to offer an import: something to call, and whether it is busy. */
@Immutable
class ImportPicker internal constructor(
    val busy: Boolean,
    val launch: () -> Unit,
)

/**
 * The import entry point, dialogs included.
 *
 * Two screens offer an import — the record button's counterpart and the Library's empty
 * state — and both need the same picker, the same size question and the same refusals. This
 * renders those where it is called and hands back the launcher, so neither screen carries a
 * copy of the flow.
 *
 * Video containers are offered as well as audio: an mp4 holding a lecture is an audio file
 * with a picture attached, and refusing it would be refusing the format people are most
 * often sent (ADR-0016 §1).
 */
@Composable
fun rememberImportPicker(viewModel: ImportViewModel = viewModel()): ImportPicker {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            viewModel.pick(uri)
        }

    when (val current = state) {
        is ImportUiState.Failed ->
            HarkenErrorDialog(
                title = stringResource(current.titleRes),
                body = stringResource(current.messageRes),
                onDismiss = viewModel::dismiss,
            )

        is ImportUiState.Confirm ->
            ImportSizeDialog(
                recordingBytes = current.recordingBytes,
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::dismiss,
            )

        else -> Unit
    }

    return ImportPicker(busy = state is ImportUiState.Staging) { launcher.launch(IMPORT_MIME_TYPES) }
}

/**
 * The one place the user is told what an import costs.
 *
 * It names the size of the finished recording rather than of the file they picked, because
 * the surprise is precisely that the two differ — a 5 MB voice note is 75 MB once it is a
 * canonical Recording (ADR-0016).
 */
@Composable
private fun ImportSizeDialog(
    recordingBytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.import_confirm_title)) },
        text = { Text(stringResource(R.string.import_confirm_body, LibraryExporter.formatBytes(recordingBytes))) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.import_confirm_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_confirm_cancel)) }
        },
    )
}

/**
 * What the picker will show.
 *
 * Broad on purpose: the decoder's answer is the real one, and narrowing to a list of
 * extensions here would hide files this phone can in fact decode. A file with no audio in
 * it comes back as [com.harken.android.ingest.ImportOutcome.NoAudioTrack] and is told so.
 */
val IMPORT_MIME_TYPES = arrayOf("audio/*", "video/*")
