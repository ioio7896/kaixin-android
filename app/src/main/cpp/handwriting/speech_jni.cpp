#include <android/asset_manager_jni.h>
#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "sherpa-ncnn/csrc/recognizer.h"

namespace {
struct VoiceSession {
    explicit VoiceSession(AAssetManager* assets, const sherpa_ncnn::RecognizerConfig& config)
            : recognizer(assets, config) {}
    sherpa_ncnn::Recognizer recognizer;
    std::unique_ptr<sherpa_ncnn::Stream> stream;
    std::vector<float> waveform;
};

sherpa_ncnn::RecognizerConfig makeConfig() {
    sherpa_ncnn::RecognizerConfig config;
    const std::string base = "speech/";
    auto& model = config.model_config;
    model.encoder_param = base + "encoder_jit_trace-pnnx.ncnn.param";
    model.encoder_bin = base + "encoder_jit_trace-pnnx.ncnn.bin";
    model.decoder_param = base + "decoder_jit_trace-pnnx.ncnn.param";
    model.decoder_bin = base + "decoder_jit_trace-pnnx.ncnn.bin";
    model.joiner_param = base + "joiner_jit_trace-pnnx.ncnn.param";
    model.joiner_bin = base + "joiner_jit_trace-pnnx.ncnn.bin";
    model.tokens = base + "tokens.txt";
    model.use_vulkan_compute = false;
    model.encoder_opt.num_threads = 2;
    model.decoder_opt.num_threads = 2;
    model.joiner_opt.num_threads = 2;
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
    config.decoder_config.method = "greedy_search";
    // Reset recognition at pauses or after a long uninterrupted segment; the
    // microphone remains open and recognizer state is retained across resets.
    config.enable_endpoint = true;
    config.endpoint_config.rule1 = sherpa_ncnn::EndpointRule(false, 2.4f, 0.f);
    config.endpoint_config.rule2 = sherpa_ncnn::EndpointRule(true, 1.4f, 0.f);
    config.endpoint_config.rule3 = sherpa_ncnn::EndpointRule(false, 0.f, 20.f);
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
Java_com_example_offlineime_OfflineVoiceModel_nativeBeginRecording(JNIEnv*, jclass, jlong handle) {
    if (!handle) return JNI_FALSE;
    try {
        auto* session = reinterpret_cast<VoiceSession*>(handle);
        session->stream = session->recognizer.CreateStream();
        return session->stream ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlineime_OfflineVoiceModel_nativeEndRecording(JNIEnv*, jclass, jlong handle) {
    if (handle) reinterpret_cast<VoiceSession*>(handle)->stream.reset();
}

extern "C" JNIEXPORT jstring JNICALL
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
            session->stream->AcceptWaveform(16000, padding.data(), padding.size());
            session->stream->InputFinished();
        }
        while (session->recognizer.IsReady(session->stream.get()))
            session->recognizer.DecodeStream(session->stream.get());
        const std::string text = session->recognizer.GetResult(session->stream.get()).text;
        return env->NewStringUTF(text.c_str());
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
        // Sherpa's decoder uses 40 ms per trailing blank frame. Distinguish a
        // pause from the time-only safeguard so a forced split is not treated
        // as the end of a sentence.
        return session->stream->GetResult().num_trailing_blanks >= 35 ? 1 : 2;
    } catch (...) {
        return -1;
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
