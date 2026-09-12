// JNI bridge between com.harken.android.speech.OnDeviceTranscriber (Kotlin, added
// in a later task) and the vendored whisper.cpp core built by CMakeLists.txt.
//
// Exposes:
//   nativeLoadModel(String path): Long        -> opaque whisper_context* handle
//   nativeTranscribe(long handle, short[] pcm16, int sampleRate): String
//       -> JSON array of {"offsetMs":N,"text":"..."} objects, or throws
//          IllegalStateException if the decode failed. An empty array means whisper heard
//          nothing in this span, and never that something went wrong (ARC-058).
//   nativeFreeModel(long handle): void
//
// JNI function names below follow the standard Java_<package>_<Class>_<method>
// mangling for the fully-qualified class com.harken.android.speech.OnDeviceTranscriber.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "HarkenWhisperJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// Set from Kotlin when the user cancels; read by ggml before each graph computation.
// One flag rather than one per context because decoding is single-flight — see
// TranscriptionCoordinator, which will not start a second decode while one is running.
std::atomic<bool> g_abortRequested{false};

bool AbortRequested(void* /*user_data*/) {
    return g_abortRequested.load(std::memory_order_relaxed);
}

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

// Raises a Java exception and returns the null jstring JNI requires on that path.
//
// java.lang.IllegalStateException rather than an exception class of our own: a class
// referenced only from C++ has no Java-side reference for R8 to see, which is precisely
// what it renames or removes — and this bridge already has a scar from that (see
// parseNativeSegments' doc on R8 breaking a reflective mapper on release builds only). A
// platform class cannot be stripped.
//
// The message is for logcat and telemetry. The user never sees it: the coordinator's catch
// reports a localized string and deliberately never reads Throwable.message (ARC-042).
jstring ThrowDecodeFailure(JNIEnv* env, const char* message) {
    LOGE("%s", message);
    // A pending exception is already on its way to Kotlin — typically the OutOfMemoryError
    // that made GetShortArrayElements fail. Throwing over it would lose the real cause, and
    // FindClass is not safe to call with one pending.
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    jclass illegalState = env->FindClass("java/lang/IllegalStateException");
    if (illegalState != nullptr) {
        env->ThrowNew(illegalState, message);
    }
    return nullptr;
}

// Unpins a jshortArray however the scope is left. ToWhisperPcm allocates a vector of one
// float per sample — 19 MB for a 300-second span — so it can throw std::bad_alloc, and a
// bare Release call after it would then never run: the array would stay pinned for the life
// of the process and a C++ exception would unwind through a JNI frame (ARC-058).
class PinnedShorts {
 public:
    PinnedShorts(JNIEnv* env, jshortArray array) : env_(env), array_(array), elements_(env->GetShortArrayElements(array, nullptr)) {}

    ~PinnedShorts() {
        if (elements_ != nullptr) {
            // JNI_ABORT: nothing here writes to the samples, so there is no copy to commit.
            env_->ReleaseShortArrayElements(array_, elements_, JNI_ABORT);
        }
    }

    PinnedShorts(const PinnedShorts&) = delete;
    PinnedShorts& operator=(const PinnedShorts&) = delete;

    jshort* get() const { return elements_; }

 private:
    JNIEnv* env_;
    jshortArray array_;
    jshort* elements_;
};

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
    // Every failure below raises rather than returning "[]". An empty array is a real
    // answer — a span whisper heard nothing in — so returning it for a failure made a
    // broken decode indistinguishable from silence, and the transcription completed and
    // reported success with a hole where that span's audio was (ARC-058).
    if (handle == 0) {
        return ThrowDecodeFailure(env, "nativeTranscribe called with null model handle");
    }

    auto* ctx = reinterpret_cast<struct whisper_context*>(handle);

    const jsize sampleCount = env->GetArrayLength(pcm16);
    std::vector<float> pcmf32;
    {
        PinnedShorts pinned(env, pcm16);
        if (pinned.get() == nullptr) {
            return ThrowDecodeFailure(env, "nativeTranscribe could not pin the sample array");
        }
        pcmf32 = ToWhisperPcm(reinterpret_cast<int16_t*>(pinned.get()), sampleCount, sampleRate);
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = "en";
    wparams.n_threads = 4;
    wparams.no_timestamps = false;
    // Without this whisper_full runs a whole span to completion — up to 300 seconds of
    // audio, minutes of compute — and a Cancel tap does nothing until it returns.
    wparams.abort_callback = AbortRequested;
    wparams.abort_callback_user_data = nullptr;

    const int result = whisper_full(ctx, wparams, pcmf32.data(), static_cast<int>(pcmf32.size()));
    if (result != 0) {
        // Not the cancel path. An aborted whisper_full returns 0 with no segments, and
        // OnDeviceTranscriber turns that into a CancellationException itself so the
        // coordinator can report "cancelled" rather than "failed". This is the decoder
        // actually failing.
        char message[64];
        snprintf(message, sizeof(message), "whisper_full failed with code %d", result);
        return ThrowDecodeFailure(env, message);
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
Java_com_harken_android_speech_OnDeviceTranscriber_nativeSetAbort(JNIEnv* /*env*/, jobject /*thiz*/, jboolean abort) {
    g_abortRequested.store(abort == JNI_TRUE, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_harken_android_speech_OnDeviceTranscriber_nativeFreeModel(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) {
        return;
    }
    auto* ctx = reinterpret_cast<struct whisper_context*>(handle);
    whisper_free(ctx);
}
