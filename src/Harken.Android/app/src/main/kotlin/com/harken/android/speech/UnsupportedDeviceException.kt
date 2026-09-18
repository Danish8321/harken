package com.harken.android.speech

/**
 * This phone's CPU cannot execute the speech kernels, so no transcription on it will ever
 * succeed.
 *
 * Distinct from the `IllegalStateException` a failed model load raises, because the two
 * need opposite things from the user. A load failure is a corrupt download or a missing
 * file — retrying is the fix, and the message says so. This is a permanent fact about the
 * hardware: retrying is not the fix, and offering it is a loop with no exit.
 *
 * See ARC-070. The kernels are compiled `-march=armv8.2-a+fp16+dotprod`, which is 3.2x
 * faster on an fp16 model and illegal on an ARMv8.0 core.
 */
class UnsupportedDeviceException : Exception("CPU lacks the ARMv8.2 FP16/dotprod features the speech kernels require")
