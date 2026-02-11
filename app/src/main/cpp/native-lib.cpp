#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"
#include <fstream>
#include <vector>
#include <cmath>
#include <mutex>
#include <thread>
#include <algorithm>

#define TAG "AppNative"
#define WHISPER_TAG "WhisperCpp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static struct whisper_context *g_ctx = nullptr;
static std::mutex g_ctx_mutex;

static void whisper_android_log_callback(enum ggml_log_level level, const char * text, void * user_data) {
    (void) user_data;
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            prio = ANDROID_LOG_ERROR;
            break;
        case GGML_LOG_LEVEL_WARN:
            prio = ANDROID_LOG_WARN;
            break;
        case GGML_LOG_LEVEL_INFO:
            prio = ANDROID_LOG_INFO;
            break;
        case GGML_LOG_LEVEL_DEBUG:
            prio = ANDROID_LOG_DEBUG;
            break;
        default:
            prio = ANDROID_LOG_INFO;
            break;
    }
    __android_log_print(prio, WHISPER_TAG, "%s", text ? text : "");
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_wads_motoridecallconnect_stt_WhisperLib_initModel(
    JNIEnv *env,
    jobject /* this */,
    jstring modelPathStr) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);

    const char *model_path = env->GetStringUTFChars(modelPathStr, nullptr);
    LOGD("Loading model from %s", model_path);

    whisper_log_set(whisper_android_log_callback, nullptr);

    if (g_ctx != nullptr) {
        LOGW("Whisper context already existed. Releasing old context before re-initialization.");
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = true;
    cparams.gpu_device = 0;
    // Keep flash attention disabled for stability on CPU fallback paths.
    cparams.flash_attn = false;

    LOGI("Initializing Whisper context (use_gpu=%d, gpu_device=%d, flash_attn=%d)",
         cparams.use_gpu, cparams.gpu_device, cparams.flash_attn);

    g_ctx = whisper_init_from_file_with_params(model_path, cparams);

    if (g_ctx == nullptr) {
        LOGW("GPU backend init failed. Retrying with CPU backend.");
        cparams.use_gpu = false;
        cparams.flash_attn = false;
        g_ctx = whisper_init_from_file_with_params(model_path, cparams);
    }

    env->ReleaseStringUTFChars(modelPathStr, model_path);

    if (g_ctx == nullptr) {
        LOGE("Failed to initialize whisper context");
        return 0;
    }

    const char * sysInfo = whisper_print_system_info();
    if (sysInfo != nullptr) {
        LOGI("Whisper system info: %s", sysInfo);
    }

    LOGI("Model loaded successfully.");
    return (jlong) g_ctx;
}

std::vector<float> read_wav(const char* fname) {
    std::ifstream f(fname, std::ios::binary);
    if (!f.is_open()) {
        LOGE("Failed to open WAV file: %s", fname);
        return {};
    }

    f.seekg(0, std::ios::end);
    int fileSize = f.tellg();
    f.seekg(0, std::ios::beg);

    if (fileSize < 44) {
        LOGE("WAV file too small");
        return {};
    }

    std::vector<char> buffer(fileSize);
    f.read(buffer.data(), fileSize);

    int16_t channels = *(int16_t*)(&buffer[22]);
    int32_t sampleRate = *(int32_t*)(&buffer[24]);
    int16_t bitsPerSample = *(int16_t*)(&buffer[34]);

    if (sampleRate != 16000) {
        LOGE("WAV must be 16kHz, got %d", sampleRate);
    }

    int headerSize = 44; // Assume standard header
    int numSamples = (fileSize - headerSize) / (bitsPerSample / 8);
    
    std::vector<float> pcm32;
    pcm32.reserve(numSamples);
    
    int16_t* data = (int16_t*)(&buffer[headerSize]);
    
    for (int i = 0; i < numSamples; i++) {
        pcm32.push_back((float)data[i] / 32768.0f);
    }
    
    if (channels == 2) {
        std::vector<float> mono;
        mono.reserve(pcm32.size() / 2);
        for (size_t i = 0; i < pcm32.size(); i+=2) {
            mono.push_back((pcm32[i] + pcm32[i+1]) / 2.0f);
        }
        return mono;
    }

    return pcm32;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_wads_motoridecallconnect_stt_WhisperLib_transcribe(
    JNIEnv *env,
    jobject /* this */,
    jstring wavPathStr) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);

    if (g_ctx == nullptr) {
        return env->NewStringUTF("Error: Model not initialized");
    }

    const char *wav_path = env->GetStringUTFChars(wavPathStr, nullptr);
    LOGD("Transcribing %s", wav_path);

    std::vector<float> pcmf32 = read_wav(wav_path);
    env->ReleaseStringUTFChars(wavPathStr, wav_path);

    if (pcmf32.empty()) {
        return env->NewStringUTF("Error: Failed to read WAV or empty");
    }
    LOGD("transcribe(file): sampleCount=%zu", pcmf32.size());

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    
    if (whisper_full(g_ctx, wparams, pcmf32.data(), pcmf32.size()) != 0) {
        return env->NewStringUTF("Error: Transcription failed");
    }

    int n_segments = whisper_full_n_segments(g_ctx);
    LOGD("transcribe(file): segments=%d", n_segments);
    std::string result = "";
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(g_ctx, i);
        result += text;
    }

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_wads_motoridecallconnect_stt_WhisperLib_transcribeBuffer(
    JNIEnv *env,
    jobject /* this */,
    jfloatArray floatArray) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);

    if (g_ctx == nullptr) {
        return env->NewStringUTF("Error: Model not initialized");
    }

    jsize len = env->GetArrayLength(floatArray);
    if (len <= 0) {
        LOGW("transcribeBuffer: empty input");
        return env->NewStringUTF("");
    }
    std::vector<float> pcmf32(len);
    env->GetFloatArrayRegion(floatArray, 0, len, pcmf32.data());
    LOGD("transcribeBuffer: sampleCount=%d", (int) len);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const unsigned int hw_threads = std::thread::hardware_concurrency();
    wparams.n_threads = std::max(1, std::min(4, hw_threads > 0 ? (int) hw_threads : 2));
    wparams.no_context = true;
    wparams.single_segment = true;
    wparams.no_timestamps = true;
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    
    if (whisper_full(g_ctx, wparams, pcmf32.data(), pcmf32.size()) != 0) {
        LOGE("transcribeBuffer: whisper_full failed");
        return env->NewStringUTF("Error: Transcription failed");
    }

    int n_segments = whisper_full_n_segments(g_ctx);
    LOGD("transcribeBuffer: segments=%d", n_segments);
    if (n_segments == 0) {
        LOGW("transcribeBuffer: no text segments returned for this chunk");
    }
    std::string result = "";
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(g_ctx, i);
        result += text;
    }

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wads_motoridecallconnect_stt_WhisperLib_free(
    JNIEnv *env,
    jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}
