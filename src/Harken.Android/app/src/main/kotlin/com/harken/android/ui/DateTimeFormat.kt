package com.harken.android.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Backend sends ISO-8601 instants (UTC); render in device locale/timezone, not raw ISO.
private val displayFormatter = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())

fun formatSessionTimestamp(iso: String): String =
    try {
        displayFormatter.format(Instant.parse(iso))
    } catch (e: Exception) {
        iso
    }
