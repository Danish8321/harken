package com.harken.android.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a recording was made, coarsely enough to name it.
 *
 * A recording with no title of its own is named after its time of day, because "Morning
 * recording" is what a person would call it and "Untitled recording" is what a database
 * would. The *name* is not decided here: this layer reports the kind and the presentation
 * layer resolves it against the user's language (ARC-017).
 */
enum class PartOfDay {
    Morning,
    Afternoon,
    Evening,
    LateNight,

    /** The recording's timestamp could not be read, so its time of day is not known. */
    Unknown,
    ;

    companion object {
        fun of(
            startedAtIso: String,
            zone: ZoneId = ZoneId.systemDefault(),
        ): PartOfDay {
            // Parsed into the reader's zone, not read off the string: startedAt is stored
            // as UTC, so a 1:41 pm capture in UTC+5:30 carried the hour "08" and was
            // titled "Morning recording" while the card beside it read "1:41 pm".
            val hour =
                runCatching { Instant.parse(startedAtIso).atZone(zone).hour }
                    .getOrElse { startedAtIso.substringAfter('T', "").take(2).toIntOrNull() }
                    ?: return Unknown
            return ofHour(hour)
        }

        /** What a recording starting now will be called, for the surfaces that need a
         * name before there is a saved row to read one off. */
        fun now(zone: ZoneId = ZoneId.systemDefault()): PartOfDay = ofHour(ZonedDateTime.now(zone).hour)

        private fun ofHour(hour: Int): PartOfDay =
            when (hour) {
                in 5..11 -> Morning
                in 12..16 -> Afternoon
                in 17..21 -> Evening
                else -> LateNight
            }
    }
}
