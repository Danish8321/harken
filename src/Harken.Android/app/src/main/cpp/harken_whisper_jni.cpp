// JNI bridge between com.harken.android.speech.OnDeviceTranscriber (Kotlin, added
// in a later task) and the vendored whisper.cpp core built by CMakeLists.txt.
//
// Exposes:
//   nativeLoadModel(String path): Long        -> opaque whisper_context* handle
//   nativeTranscribe(long handle, short[] pcm16, int sampleRate): String
//       -> JSON array of {"offsetMs":N,"text":"..."} objects
//   nativeFreeModel(long handle): void
//
// JNI function names below follow the standard Java_<package>_<Class>_<method>
// mangling for the fully-qualified class com.harken.android.speech.OnDeviceTranscriber.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "HarkenWhisperJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// whisper.cpp always expects mono float32 PCM at 16kHz. Convert/resample
// whatever the recorder captured into that shape.
std::vector<float> ToWhisperPcm(const int16_t* samples, int sampleCount, int sampleRate) {
    std::vector<float> mono(sampleCount);
    for (int i = 0; i < sampleCount; ++i) {
        mono[i] = static_cast<float>(samples[i]) / 32768.0f;
    }

    if (sampleRate == WHISPER_SAMPLE_RATE || mono.empty()) {
        return mono;
    }

    // Simple linear-interpolation resample. Good enough for speech; whisper.cpp
    // itself does no better internally for arbitrary input rates.
    const double ratio = static_cast<double>(sampleRate) / static_cast<double>(WHISPER_SAMPLE_RATE);
    const int outCount = static_cast<int>(mono.size() / ratio);
    std::vector<float> resampled(outCount);
    for (int i = 0; i < outCount; ++i) {
        const double srcPos = i * ratio;
        const int srcIndex = static_cast<int>(srcPos);
        const double frac = srcPos - srcIndex;
        const int nextIndex = std::min(srcIndex + 1, static_cast<int>(mono.size()) - 1);
        resampled[i] = static_cast<float>(mono[srcIndex] * (1.0 - frac) + mono[nextIndex] * frac);
    }
    return resampled;
}

std::string EscapeJson(const std::string& text) {
    std::string escaped;
    escaped.reserve(text.size());
    for (char c : text) {
        switch (c) {
            case '"': escaped += "\\\""; break;
            case '\\': escaped += "\\\\"; break;
            case '\n': escaped += "\\n"; break;
            case '\r': escaped += "\\r"; break;
            case '\t': escaped += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    escaped += buf;
                } else {
                    escaped += c;
                }
        }
    }
    return escaped;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_harken_android_speech_OnDeviceTranscriber_nativeLoadModel(JNIEnv* env, jobject /*thiz*/, jstring path) {
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    if (pathChars == nullptr) {
        return 0;
    }

    whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context* ctx = whisper_init_from_file_with_params(pathChars, cparams);

    env->ReleaseStringUTFChars(path, pathChars);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed to load model");
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_harken_android_speech_OnDeviceTranscriber_nativeTranscribe(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jshortArray pcm16, jint sampleRate) {
    if (handle == 0) {
        LOGE("nativeTranscribe called with null model handle");
        return env->NewStringUTF("[]");
    }

    auto* ctx = reinterpret_cast<struct whisper_context*>(handle);

    const jsize sampleCount = env->GetArrayLength(pcm16);
    jshort* samples = env->GetShortArrayElements(pcm16, nullptr);
    if (samples == nullptr) {
        return env->NewStringUTF("[]");
    }

    std::vector<float> pcmf32 = ToWhisperPcm(reinterpret_cast<int16_t*>(samples), sampleCount, sampleRate);
    env->ReleaseShortArrayElements(pcm16, samples, JNI_ABORT);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = "en";
    wparams.n_threads = 4;
    wparams.no_timestamps = false;

    const int result = whisper_full(ctx, wparams, pcmf32.data(), static_cast<int>(pcmf32.size()));
    if (result != 0) {
        LOGE("whisper_full failed with code %d", result);
        return env->NewStringUTF("[]");
    }

    const int segmentCount = whisper_full_n_segments(ctx);
    std::string json = "[";
    for (int i = 0; i < segmentCount; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i);  // in 10ms units
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (i > 0) {
            json += ",";
        }
        json += "{\"offsetMs\":";
        json += std::to_string(t0 * 10);
        json += ",\"text\":\"";
        json += EscapeJson(text != nullptr ? text : "");
        json += "\"}";
    }
    json += "]";

    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_harken_android_speech_OnDeviceTranscriber_nativeFreeModel(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) {
        return;
    }
    auto* ctx = reinterpret_cast<struct whisper_context*>(handle);
    whisper_free(ctx);
}
