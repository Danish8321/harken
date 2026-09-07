package com.harken.android

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.harken.android.data.PartOfDay
import com.harken.android.data.SessionRepository

/**
 * The name a recording shows before anyone renames it.
 *
 * The repository used to compose this text itself — the literals "Morning", "Afternoon",
 * "Evening", "Late night", plus " recording" — which made it unlocalizable and put
 * presentation in the data layer (ARC-017). The repository now reports the kind; the name
 * is resolved here, against the reader's language, at the point of display.
 *
 * In the root package rather than `ui`, because the notification and the export both need
 * a name and neither is a screen.
 */
@StringRes
fun PartOfDay.titleRes(): Int =
    when (this) {
        PartOfDay.Morning -> R.string.recording_title_morning
        PartOfDay.Afternoon -> R.string.recording_title_afternoon
        PartOfDay.Evening -> R.string.recording_title_evening
        PartOfDay.LateNight -> R.string.recording_title_late_night
        PartOfDay.Unknown -> R.string.recording_title_unknown
    }

/** For the surfaces with a Context but no composition: the notification, the export. */
fun Context.recordingTitle(
    localTitle: String?,
    partOfDay: PartOfDay,
): String = localTitle ?: getString(partOfDay.titleRes())

@Composable
fun SessionRepository.SessionView.displayTitle(): String = localTitle ?: stringResource(partOfDay.titleRes())
