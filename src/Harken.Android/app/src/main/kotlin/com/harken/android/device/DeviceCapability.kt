package com.harken.android.device

import android.app.ActivityManager
import android.content.Context

/**
 * How much memory this phone has, and whether that is enough to finish a transcription.
 *
 * ADR-0014 (docs/adr/0014-minimum-supported-device.md) sets 6 GB as the minimum supported
 * device: a decode peaks at ~610 MB of PSS and holds it for the whole run, which on a 4 GB
 * phone is low-memory-killer range. The Play listing can exclude those devices; a
 * sideloaded install has nothing to stop it, and the failure the user sees is a
 * transcription that vanishes with no reason given.
 *
 * This never refuses. 610 MB is a peak, not a floor, and a 4 GB phone with nothing else
 * running may well finish — refusing would take away a transcription that would have
 * worked. What it does is put the reason where the user asks for it: on the Settings model
 * card, and on the failure message the killed decode leaves behind.
 */
@JvmInline
value class DeviceCapability(val totalMemBytes: Long) {
    /**
     * True only when the device is *known* to be under the bar. A read that fails reports
     * zero, and an unknown device is left alone: a wrong warning is worse than the silence
     * this replaces, which at least never misled anyone.
     */
    val isBelowMinimum: Boolean
        get() = totalMemBytes in 1 until MinimumTotalMemBytes

    /**
     * For telemetry, where a magnitude is the whole point and GB is too coarse. This is the
     * only place the device's own figure is reported: the warning names the bar instead,
     * because turning a reported total back into the number on the box needs a table of the
     * sizes phones are actually sold in, and a warning that names the wrong number is worse
     * than one that names none.
     */
    val totalMemMb: Long
        get() = totalMemBytes / (1024L * 1024L)

    companion object {
        const val BytesPerGb = 1024L * 1024L * 1024L

        /** The bar ADR-0014 sets, as the user's phone box states it. */
        const val MinimumNominalGb = 6

        /**
         * The bar as `totalMem` actually reports it.
         *
         * `totalMem` is RAM minus what the kernel reserved, so it never reaches the
         * nominal figure: the reference Nothing Phone 2 is an 8 GB device and reports
         * 7,444,948 kB, or 89%. At that rate a 6 GB device reports ~5.3 GB and a 4 GB
         * device ~3.6 GB, so comparing against a literal 6 GB would fail every 6 GB phone
         * on the market. The bar sits midway between those two, where the gap is 1.7 GB
         * wide and no real device lands.
         */
        const val MinimumTotalMemBytes = 4_500L * 1024L * 1024L

        /**
         * Reads the device's memory once. [ActivityManager.getMemoryInfo] fills a struct
         * rather than returning one, and its `totalMem` is fixed for the life of the boot,
         * so there is nothing to observe over time.
         */
        fun of(context: Context): DeviceCapability {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return DeviceCapability(0)
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            return DeviceCapability(info.totalMem)
        }
    }
}
