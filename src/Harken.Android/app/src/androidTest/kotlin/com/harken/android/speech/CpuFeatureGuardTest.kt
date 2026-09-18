package com.harken.android.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ARC-070 guard, driven in both directions.
 *
 * `nativeLoadModel` refuses to load when the CPU lacks the ARMv8.2 features the kernels are
 * compiled for, because executing them anyway is a SIGILL inside the first matmul rather
 * than a failure the app can report. Every device this suite has ever run on has those
 * features, so the accepting branch is the only one hardware can exercise — and a guard
 * only ever tested in the direction that says yes is not a tested guard. An inverted
 * comparison or the wrong HWCAP constant would pass on this phone and crash on a user's.
 *
 * So the predicate takes `hwcap` as an argument and the real reading lives in its caller.
 * These are synthetic values, and the bit positions below are written out independently of
 * the C++ rather than read back from it: two copies that must agree is the point, since a
 * test that asks the implementation what it thinks the answer is proves nothing.
 *
 * Instrumented because the predicate is native, and `OnDeviceTranscriber`'s companion loads
 * the library in its initializer — touching any of this on the JVM runner is an
 * `UnsatisfiedLinkError`.
 */
class CpuFeatureGuardTest {
    /** arm64 `HWCAP_ASIMDHP`, bit 10 — the vector half-precision arithmetic `+fp16` emits. */
    private val asimdhp = 1L shl 10

    /** arm64 `HWCAP_ASIMDDP`, bit 20 — the dot product `+dotprod` emits. */
    private val asimddp = 1L shl 20

    @Test
    fun aCpuWithNeitherFeatureIsRefused() {
        assertFalse(OnDeviceTranscriber.nativeCpuFeaturesSatisfied(0L))
    }

    @Test
    fun aCpuWithOnlyHalfPrecisionIsRefused() {
        // ARMv8.2-A made FP16 and dotprod separately optional, so "has one" is a real
        // configuration and not a hypothetical: it must still be refused, because the
        // kernels are compiled for both.
        assertFalse(OnDeviceTranscriber.nativeCpuFeaturesSatisfied(asimdhp))
    }

    @Test
    fun aCpuWithOnlyDotProductIsRefused() {
        assertFalse(OnDeviceTranscriber.nativeCpuFeaturesSatisfied(asimddp))
    }

    @Test
    fun aCpuWithBothFeaturesIsAccepted() {
        assertTrue(OnDeviceTranscriber.nativeCpuFeaturesSatisfied(asimdhp or asimddp))
    }

    @Test
    fun unrelatedCapabilityBitsDoNotSatisfyTheCheck() {
        // Guards against a truthiness test — `hwcap != 0`, or a mask built from the wrong
        // constant — which every real CPU would pass regardless of what it can execute.
        val everythingElse = (asimdhp or asimddp).inv()
        assertFalse(OnDeviceTranscriber.nativeCpuFeaturesSatisfied(everythingElse))
    }

    // Deliberately no test of the live `getauxval` reading. It would have to hardcode the
    // answer it expects, which is this file's other five tests again under a name that
    // claims more. That the real CPU is accepted is proven where it matters: the rest of
    // the instrumented suite loads a model on this device and decodes with it.
}
