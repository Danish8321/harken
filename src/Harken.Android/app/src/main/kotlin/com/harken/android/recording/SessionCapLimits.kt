package com.harken.android.recording

import java.util.concurrent.TimeUnit

/**
 * The one place a recording's length limit is a number. [RecordingForegroundService] stops
 * the recording at [CAP_MS]; RecordScreen shows an "ending soon" warning [WARNING_LEAD_MS]
 * before that. `strings.xml`'s `settings_session_cap_value` states the same limit in copy
 * and has no way to read this constant, so it must be kept in sync by hand if this changes.
 */
object SessionCapLimits {
    val CAP_MS: Long = TimeUnit.HOURS.toMillis(3)
    val WARNING_LEAD_MS: Long = TimeUnit.MINUTES.toMillis(5)
}
