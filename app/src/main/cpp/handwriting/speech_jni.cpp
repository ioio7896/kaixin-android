#include <android/asset_manager_jni.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "sherpa-ncnn/csrc/recognizer.h"

namespace {
struct VoiceSession {
    explicit VoiceSession(AAssetManager* assets, const sherpa_ncnn::RecognizerConfig& config)
            : recognizer(assets, config) {}
    sherpa_ncnn::Recognizer recognizer;
    std::unique_ptr<sherpa_ncnn::Stream> stream;
    std::vector<float> waveform;
    float rule2_silence_sec = 1.4f;
};

int adaptiveThreads() {
    const unsigned int cores = std::thread::hardware_concurrency();
    if (cores >= 8) return 4;
    if (cores >= 6) return 3;
    return 2;
}

sherpa_ncnn::EndpointConfig makeEndpointConfig(float rule2_silence_sec) {
    const float clamped = std::max(0.5f, std::min(4.0f, rule2_silence_sec));
    sherpa_ncnn::EndpointConfig ep;
    ep.rule1 = sherpa_ncnn::EndpointRule(false, std::max(2.4f, clamped + 1.0f), 0.f);
    ep.rule2 = sherpa_ncnn::EndpointRule(true, clamped, 0.f);
    ep.rule3 = sherpa_ncnn::EndpointRule(false, 0.f, 20.f);
    return ep;
}

sherpa_ncnn::RecognizerConfig makeConfig() {
    sherpa_ncnn::RecognizerConfig config;
    const std::string base = "speech/";
    const int threads = adaptiveThreads();
    auto& model = config.model_config;
    model.encoder_param = base + "encoder_jit_trace-pnnx.ncnn.param";
    model.encoder_bin = base + "encoder_jit_trace-pnnx.ncnn.bin";
    model.decoder_param = base + "decoder_jit_trace-pnnx.ncnn.param";
    model.decoder_bin = base + "decoder_jit_trace-pnnx.ncnn.bin";
    model.joiner_param = base + "joiner_jit_trace-pnnx.ncnn.param";
    model.joiner_bin = base + "joiner_jit_trace-pnnx.ncnn.bin";
    model.tokens = base + "tokens.txt";
    model.use_vulkan_compute = false;
    model.encoder_opt.num_threads = threads;
    model.decoder_opt.num_threads = std::min(threads, 2);
    model.joiner_opt.num_threads = std::min(threads, 2);
#if defined(__aarch64__)
    model.encoder_opt.use_fp16_storage = true;
    model.encoder_opt.use_fp16_packed = true;
    model.encoder_opt.use_fp16_arithmetic = false;
    model.decoder_opt.use_fp16_storage = true;
    model.decoder_opt.use_fp16_packed = true;
    model.decoder_opt.use_fp16_arithmetic = false;
    model.joiner_opt.use_fp16_storage = true;
    model.joiner_opt.use_fp16_packed = true;
    model.joiner_opt.use_fp16_arithmetic = false;
#endif
    config.decoder_config.method = "modified_beam_search";
    config.decoder_config.num_active_paths = 4;
    // Reset recognition at pauses or after a long uninterrupted segment; the
    // microphone remains open and recognizer state is retained across resets.
    config.enable_endpoint = true;
    config.endpoint_config = makeEndpointConfig(1.4f);
    return config;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_offlineime_OfflineVoiceModel_nativeCreate(JNIEnv* env, jclass, jobject assets) {
    try {
        AAssetManager* manager = AAssetManager_fromJava(env, assets);
        if (!manager) return 0;
        auto session = std::make_unique<VoiceSession>(manager, makeConfig());
        return reinterpret_cast<jlong>(session.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_offlineime_OfflineVoiceModel_nativeBeginRecording(JNIEnv* env, jclass,
                                                                   jlong handle,
                                                                   jfloat rule2SilenceSec,
                                                                   jbyteArray hotwordsUtf8,
                                                                   jfloat hotwordsScore) {
    if (!handle) return JNI_FALSE;
    try {
        auto* session = reinterpret_cast<VoiceSession*>(handle);
        const float clamped = std::isfinite(rule2SilenceSec)
                ? std::max(0.5f, std::min(4.0f, rule2SilenceSec)) : 1.4f;
        session->rule2_silence_sec = clamped;
        session->recognizer.SetEndpointConfig(makeEndpointConfig(clamped));

        std::string hotwords;
        if (hotwordsUtf8) {
            const jsize len = env->GetArrayLength(hotwordsUtf8);
            if (len > 0) {
                hotwords.resize(static_cast<size_t>(len));
                env->GetByteArrayRegion(hotwordsUtf8, 0, len,
                                        reinterpret_cast<jbyte*>(hotwords.data()));
                if (env->ExceptionCheck()) return JNI_FALSE;
            }
        }
        const float score = std::isfinite(hotwordsScore) && hotwordsScore > 0.f
                ? hotwordsScore : 1.5f;
        session->stream = hotwords.empty()
                ? session->recognizer.CreateStream()
                : session->recognizer.CreateStream(hotwords, score);
        return session->stream ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlineime_OfflineVoiceModel_nativeEndRecording(JNIEnv*, jclass, jlong handle) {
    if (handle) reinterpret_cast<VoiceSession*>(handle)->stream.reset();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_offlineime_OfflineVoiceModel_nativeAccept(JNIEnv* env, jclass, jlong handle,
                                                           jfloatArray samples, jint count,
                                                           jboolean finish) {
    if (!handle || count < 0 || (count && (!samples || count > env->GetArrayLength(samples)))) return nullptr;
    try {
        auto* session = reinterpret_cast<VoiceSession*>(handle);
        if (!session->stream) return nullptr;
        if (count) {
            session->waveform.resize(static_cast<size_t>(count));
            env->GetFloatArrayRegion(samples, 0, count, session->waveform.data());
            if (env->ExceptionCheck()) return nullptr;
            session->stream->AcceptWaveform(16000, session->waveform.data(), count);
        }
        if (finish) {
            // Same tail padding as the upstream Android recognizer.
            static const std::vector<float> padding(5120, 0.f);
            session->stream->AcceptWaveform(16000, padding.data(), static_cast<int32_t>(padding.size()));
            session->stream->InputFinished();
        }
        while (session->recognizer.IsReady(session->stream.get()))
            session->recognizer.DecodeStream(session->stream.get());
        const std::string text = session->recognizer.GetResult(session->stream.get()).text;
        const jsize outLen = static_cast<jsize>(text.size());
        jbyteArray out = env->NewByteArray(outLen);
        if (!out) return nullptr;
        if (outLen > 0) {
            env->SetByteArrayRegion(out, 0, outLen, reinterpret_cast<const jbyte*>(text.data()));
            if (env->ExceptionCheck()) return nullptr;
        }
        return out;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_offlineime_OfflineVoiceModel_nativeEndpointKind(JNIEnv*, jclass, jlong handle) {
    if (!handle) return -1;
    try {
        auto* session = reinterpret_cast<VoiceSession*>(handle);
        if (!session->stream) return -1;
        if (!session->recognizer.IsEndpoint(session->stream.get())) return 0;
        // Sherpa's decoder uses 40 ms per trailing blank frame (10 ms shift * 4 subsampling).
        // Distinguish a configured silence pause from the 20s time-only safeguard.
        const float trailing_sec = session->stream->GetResult().num_trailing_blanks * 0.04f;
        return trailing_sec >= session->rule2_silence_sec - 0.02f ? 1 : 2;
    } catch (...) {
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_offlineime_OfflineVoiceModel_nativeTrailingSilenceMs(JNIEnv*, jclass, jlong handle) {
    if (!handle) return 0;
    try {
        auto* session = reinterpret_cast<VoiceSession*>(handle);
        if (!session->stream) return 0;
        return session->stream->GetResult().num_trailing_blanks * 40;
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_offlineime_OfflineVoiceModel_nativeReset(JNIEnv*, jclass, jlong handle) {
    if (!handle) return JNI_FALSE;
    try {
        auto* session = reinterpret_cast<VoiceSession*>(handle);
        if (!session->stream) return JNI_FALSE;
        session->recognizer.Reset(session->stream.get());
        return JNI_TRUE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlineime_OfflineVoiceModel_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<VoiceSession*>(handle);
}
