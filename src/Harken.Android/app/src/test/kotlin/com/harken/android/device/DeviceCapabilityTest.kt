package com.harken.android.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityTest {
    /** What a device of [nominalGb] actually reports, at the reference device's 89%. */
    private fun reported(nominalGb: Int): DeviceCapability = DeviceCapability((nominalGb * DeviceCapability.BYTES_PER_GB * 89) / 100)

    @Test
    fun aFourGigabyteDeviceIsBelowTheBar() {
        assertTrue(reported(4).isBelowMinimum)
    }

    /**
     * Below the bar since ADR-0017. A small.en decode peaks at 1.15 GB, which is more than
     * half of what a 6 GB device has available — the ratio ADR-0014 called low-memory-killer
     * range when it put 4 GB below the bar at base.en's 610 MB.
     */
    @Test
    fun aSixGigabyteDeviceIsBelowTheBar() {
        assertTrue(reported(6).isBelowMinimum)
    }

    /**
     * The device every measurement in ADR-0014 and ADR-0017 was taken on, at the exact
     * `MemTotal` it reports. It is an 8 GB phone that never reports 8 GB, which is the whole
     * reason the bar is not a literal 8.
     */
    @Test
    fun theReferenceDeviceClearsIt() {
        val nothingPhone2 = DeviceCapability(7_444_948L * 1024L)
        assertFalse(nothingPhone2.isBelowMinimum)
    }

    /**
     * A device that cannot be read is not a device that is too small. Warning on a failed
     * system-service lookup would tell a 12 GB phone it is underpowered.
     */
    @Test
    fun aDeviceThatCouldNotBeReadIsLeftAlone() {
        assertFalse(DeviceCapability(0).isBelowMinimum)
    }

    /** The bar itself is supported — it is a minimum, not a value to exceed. */
    @Test
    fun aDeviceExactlyAtTheBarClearsIt() {
        assertFalse(DeviceCapability(DeviceCapability.MINIMUM_TOTAL_MEM_BYTES).isBelowMinimum)
        assertTrue(DeviceCapability(DeviceCapability.MINIMUM_TOTAL_MEM_BYTES - 1).isBelowMinimum)
    }

    @Test
    fun telemetryReportsMegabytes() {
        assertEquals(1024L, DeviceCapability(DeviceCapability.BYTES_PER_GB).totalMemMb)
    }
}
