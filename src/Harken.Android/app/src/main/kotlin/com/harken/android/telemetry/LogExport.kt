package com.harken.android.telemetry

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundles [FileLogSink]'s rotated files into one zip for the Settings "Export logs" share
 * action. Rebuilt fresh on every export rather than kept in sync incrementally: exporting
 * is a rare manual tap, and a stale zip sitting next to files it no longer matches is worse
 * than the cost of zipping a few small text files again.
 */
object LogExport {
    fun zip(
        sink: FileLogSink,
        target: File,
    ): File {
        target.delete()
        ZipOutputStream(target.outputStream()).use { zip ->
            sink.files().forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return target
    }
}
