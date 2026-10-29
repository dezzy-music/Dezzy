package com.dezzy.music.utils

import timber.log.Timber

/**
 * Senior C++20 JNI bridge for zero-GC audio spectrum FFT analysis
 * and SIMD 3D spherical point projection.
 */
object NativeDspEngine {

    private var isLoaded = false

    init {
        try {
            System.loadLibrary("dezzy_dsp")
            isLoaded = true
            Timber.i("[NativeDspEngine] libdezzy_dsp.so successfully loaded into memory")
        } catch (t: Throwable) {
            Timber.w(t, "[NativeDspEngine] Native library not found or failed to load. Using Kotlin fallback.")
            isLoaded = false
        }
    }

    fun isNativeSupported(): Boolean = isLoaded

    external fun initSphereNative(pointCount: Int)

    external fun computeSpherePointsNative(
        outBuffer: FloatArray,
        timeSec: Float,
        bandsArray: FloatArray,
        loudness: Float,
        cx: Float,
        cy: Float,
        baseR: Float,
        orbAlpha: Float
    )

    external fun processFftNative(
        fftByteArray: ByteArray,
        inOutBands: FloatArray,
        prevLoudness: Float
    ): Float
}
