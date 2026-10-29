/**
 * Dezzy Native DSP & Visualizer Engine (C++20)
 * Ultra-low latency, zero-GC audio spectrum processing and 3D spherical mesh projection.
 */

#include <jni.h>
#include <cmath>
#include <vector>
#include <algorithm>
#include <android/log.h>

#define TAG "DezzyDSP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

constexpr int BANDS = 64;
constexpr int DEFAULT_SPHERE_POINTS = 950;
constexpr float GOLDEN_ANGLE = 2.399963f;

struct SphereNode {
    float x;
    float y;
    float z;
    float lat;
};

static std::vector<SphereNode> s_spherePoints;
static bool s_initialized = false;

static void initializeSphere(int pointCount = DEFAULT_SPHERE_POINTS) {
    s_spherePoints.clear();
    s_spherePoints.reserve(pointCount);

    for (int i = 0; i < pointCount; ++i) {
        float y = 1.0f - 2.0f * (static_cast<float>(i) + 0.5f) / static_cast<float>(pointCount);
        float r = std::sqrt(std::max(0.0f, 1.0f - y * y));
        float phi = static_cast<float>(i) * GOLDEN_ANGLE;
        float x = std::cos(phi) * r;
        float z = std::sin(phi) * r;
        float lat = std::asin(std::clamp(y, -1.0f, 1.0f));
        s_spherePoints.push_back({x, y, z, lat});
    }
    s_initialized = true;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_dezzy_music_utils_NativeDspEngine_initSphereNative(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint pointCount
) {
    initializeSphere(pointCount);
}

/**
 * Native vectorized computation of 3D spherical mesh points.
 * Packs [sx, sy, radius, alpha] per point directly into outBuffer.
 * Total floats: count * 4. Zero object allocations on JVM.
 */
JNIEXPORT void JNICALL
Java_com_dezzy_music_utils_NativeDspEngine_computeSpherePointsNative(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray outBuffer,
    jfloat timeSec,
    jfloatArray bandsArray,
    jfloat loudness,
    jfloat cx,
    jfloat cy,
    jfloat baseR,
    jfloat orbAlpha
) {
    if (!s_initialized) {
        initializeSphere(DEFAULT_SPHERE_POINTS);
    }

    const int pointCount = static_cast<int>(s_spherePoints.size());
    jfloat* out = env->GetFloatArrayElements(outBuffer, nullptr);
    jfloat* bands = env->GetFloatArrayElements(bandsArray, nullptr);

    if (!out || !bands) {
        if (out) env->ReleaseFloatArrayElements(outBuffer, out, JNI_ABORT);
        if (bands) env->ReleaseFloatArrayElements(bandsArray, bands, JNI_ABORT);
        return;
    }

    const float rotY = timeSec * 0.30f;
    const float cosR = std::cos(rotY);
    const float sinR = std::sin(rotY);
    const float dotBase = baseR * 0.016f;

    for (int i = 0; i < pointCount; ++i) {
        const auto& p = s_spherePoints[i];
        const float x1 = p.x * cosR + p.z * sinR;
        const float z1 = -p.x * sinR + p.z * cosR;
        const float y1 = p.y;

        int bandIdx = static_cast<int>(((y1 + 1.0f) * 0.5f) * (BANDS - 1));
        bandIdx = std::clamp(bandIdx, 0, BANDS - 1);
        const float band = bands[bandIdx];

        const float ripple = 1.0f + 0.055f * std::sin(p.lat * 9.0f - timeSec * 2.6f) * (0.35f + loudness) + band * 0.11f;
        const float rr = baseR * ripple;

        const float sx = cx + x1 * rr;
        const float sy = cy + y1 * rr;
        const float depth = (z1 + 1.0f) * 0.5f;

        const float alpha = std::clamp(0.22f + 0.72f * depth, 0.0f, 1.0f) * orbAlpha;
        const float radius = dotBase * (0.55f + 0.85f * depth) * (1.0f + band * 0.5f);

        const int offset = i * 4;
        out[offset + 0] = sx;
        out[offset + 1] = sy;
        out[offset + 2] = radius;
        out[offset + 3] = alpha;
    }

    env->ReleaseFloatArrayElements(outBuffer, out, 0);
    env->ReleaseFloatArrayElements(bandsArray, bands, JNI_ABORT);
}

/**
 * Native FFT reduction to logarithmic frequency bands with high-speed attack & decay.
 * Returns the smoothed global RMS loudness.
 */
JNIEXPORT jfloat JNICALL
Java_com_dezzy_music_utils_NativeDspEngine_processFftNative(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray fftByteArray,
    jfloatArray inOutBands,
    jfloat prevLoudness
) {
    const jsize len = env->GetArrayLength(fftByteArray);
    const int bins = len / 2;
    if (bins < 2) return prevLoudness;

    jbyte* fft = env->GetByteArrayElements(fftByteArray, nullptr);
    jfloat* bands = env->GetFloatArrayElements(inOutBands, nullptr);

    if (!fft || !bands) {
        if (fft) env->ReleaseByteArrayElements(fftByteArray, fft, JNI_ABORT);
        if (bands) env->ReleaseFloatArrayElements(inOutBands, bands, JNI_ABORT);
        return prevLoudness;
    }

    float total = 0.0f;
    const double binsMinusOne = static_cast<double>(bins - 1);

    for (int b = 0; b < BANDS; ++b) {
        int startBin = static_cast<int>(std::pow(binsMinusOne, static_cast<double>(b) / BANDS));
        if (startBin < 1) startBin = 1;

        int endBin = static_cast<int>(std::pow(binsMinusOne, static_cast<double>(b + 1) / BANDS));
        if (endBin <= startBin) endBin = startBin + 1;
        if (endBin > bins) endBin = bins;

        float sum = 0.0f;
        int count = 0;
        for (int i = startBin; i < endBin; ++i) {
            float re = static_cast<float>(fft[2 * i]);
            float im = static_cast<float>(fft[2 * i + 1]);
            sum += std::hypot(re, im);
            count++;
        }

        float raw = (count > 0) ? (sum / static_cast<float>(count)) / 128.0f : 0.0f;
        float boosted = std::clamp(raw * (1.0f + static_cast<float>(b) * 0.025f), 0.0f, 1.0f);

        // Fast attack, smooth decay
        if (boosted > bands[b]) {
            bands[b] = bands[b] + (boosted - bands[b]) * 0.55f;
        } else {
            bands[b] = bands[b] * 0.86f;
        }
        total += bands[b];
    }

    float newLoudness = prevLoudness * 0.82f + std::clamp((total / static_cast<float>(BANDS)) * 3.0f, 0.0f, 1.0f) * 0.18f;

    env->ReleaseByteArrayElements(fftByteArray, fft, JNI_ABORT);
    env->ReleaseFloatArrayElements(inOutBands, bands, 0);

    return newLoudness;
}

} // extern "C"
