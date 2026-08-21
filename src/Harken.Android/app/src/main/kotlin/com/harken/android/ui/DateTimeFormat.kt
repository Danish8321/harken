package com.harken.android.ui

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Backend sends ISO-8601 offset timestamps like "+00:00" (not "Z"), so Instant.parse
// throws — OffsetDateTime.parse accepts both forms.
private val displayFormatter = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())

fun formatSessionTimestamp(iso: String): String =
    try {
        displayFormatter.format(OffsetDateTime.parse(iso))
    } catch (e: Exception) {
        iso
    }
