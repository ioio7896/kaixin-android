#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>
#include "net.h"
#include "preprocess.h"

namespace {
constexpr int kClasses = 3755;

struct Session {
    ncnn::Net net;
    AAsset* model_asset = nullptr;
    std::vector<uint8_t> pixels;
    std::vector<jint> ids;
    std::vector<jfloat> scores;

    ~Session() {
        // Release NCNN weight references before closing the mapped asset.
        net.clear();
        if (model_asset) {
            AAsset_close(model_asset);
            model_asset = nullptr;
        }
    }
};

bool loadModelZeroCopy(Session& session, AAssetManager* manager, const char* param_path, const char* bin_path) {
    if (session.net.load_param(manager, param_path) != 0) return false;
    AAsset* asset = AAssetManager_open(manager, bin_path, AASSET_MODE_BUFFER);
    if (asset) {
        const void* buffer = AAsset_getBuffer(asset);
        // NCNN requires 32-bit aligned external memory for zero-copy weight mapping.
        if (buffer && (reinterpret_cast<uintptr_t>(buffer) & 3u) == 0u) {
            if (session.net.load_model(static_cast<const unsigned char*>(buffer)) > 0) {
                session.model_asset = asset;
                return true;
            }
            // Partial load may have left net in an inconsistent state; reset and fall back cleanly.
            session.net.clear();
            AAsset_close(asset);
            if (session.net.load_param(manager, param_path) != 0) return false;
            return session.net.load_model(manager, bin_path) == 0;
        }
        AAsset_close(asset);
    }
    return session.net.load_model(manager, bin_path) == 0;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_offlineime_NeuralHandwritingModel_nativeCreate(
        JNIEnv* env, jclass, jobject assets) {
    AAssetManager* manager = AAssetManager_fromJava(env, assets);
    if (!manager) return 0;
    try {
        auto session = std::make_unique<Session>();
        session->net.opt.use_vulkan_compute = false;
        session->net.opt.num_threads = 2;
        session->net.opt.use_fp16_arithmetic = false;
#if defined(__aarch64__)
        session->net.opt.use_fp16_storage = true;
        session->net.opt.use_fp16_packed = true;
#endif
        if (!loadModelZeroCopy(*session, manager, "handwriting/model.ncnn.param", "handwriting/model.ncnn.bin")) {
            return 0;
        }
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception&) { return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_offlineime_NeuralHandwritingModel_nativeRecognize(
        JNIEnv* env, jclass, jlong handle, jbyteArray gray, jint width, jint height,
        jintArray indices, jfloatArray probabilities) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || !gray || !indices || !probabilities || width <= 0 || height <= 0 ||
        width > 512 || height > 512 || env->GetArrayLength(gray) != width * height) return 0;
    const int k = std::min({env->GetArrayLength(indices),
            env->GetArrayLength(probabilities), 32});
    if (k <= 0) return 0;
    try {
        const size_t pixel_count = static_cast<size_t>(width) * height;
        session->pixels.resize(pixel_count);
        env->GetByteArrayRegion(gray, 0, width * height, reinterpret_cast<jbyte*>(session->pixels.data()));
        if (env->ExceptionCheck()) return 0;
        ncnn::Mat input(64, 64, 1);
        if (input.empty() || !hccr_preprocess(session->pixels.data(), width, height, input)) return 0;
        auto extractor = session->net.create_extractor();
        if (extractor.input("in0", input) != 0) return 0;
        ncnn::Mat output;
        if (extractor.extract("out0", output) != 0 || output.empty() ||
            output.elempack != 1 || output.total() != kClasses || output.elemsize != sizeof(float)) return 0;
        const float* logits = output;
        float maximum = -INFINITY;
        for (int i = 0; i < kClasses; ++i) {
            if (!std::isfinite(logits[i])) return 0;
            maximum = std::max(maximum, logits[i]);
        }
        jint top_ids[32];
        float top_scores[32];
        int filled = 0;
        double total = 0;
        for (int i = 0; i < kClasses; ++i) {
            const float score = std::exp(logits[i] - maximum);
            total += score;
            if (filled < k || score > top_scores[filled - 1]) {
                int pos = filled < k ? filled++ : k - 1;
                while (pos > 0 && score > top_scores[pos - 1]) {
                    top_scores[pos] = top_scores[pos - 1];
                    top_ids[pos] = top_ids[pos - 1];
                    --pos;
                }
                top_scores[pos] = score;
                top_ids[pos] = i;
            }
        }
        if (!std::isfinite(total) || total <= 0) return 0;
        jfloat top[32];
        for (int i = 0; i < k; ++i) top[i] = static_cast<float>(top_scores[i] / total);
        env->SetIntArrayRegion(indices, 0, k, top_ids);
        env->SetFloatArrayRegion(probabilities, 0, k, top);
        return env->ExceptionCheck() ? 0 : k;
    } catch (const std::exception&) { return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlineime_NeuralHandwritingModel_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_offlineime_ExtendedHandwritingModel_nativeCreate(JNIEnv* env, jclass, jobject assets) {
    auto* manager = AAssetManager_fromJava(env, assets);
    if (!manager) return 0;
    try {
        auto session = std::make_unique<Session>();
        session->net.opt.num_threads = 2;
        session->net.opt.use_vulkan_compute = false;
        // Keep FP32 storage/packed for PP-OCRv5 Mobile Rec to avoid FP16 overflow in attention/norm layers.
        session->net.opt.use_fp16_arithmetic = false;
        session->net.opt.use_fp16_storage = false;
        session->net.opt.use_fp16_packed = false;
        if (!loadModelZeroCopy(*session, manager,
                "handwriting/extended/PP_OCRv5_mobile_rec.ncnn.param",
                "handwriting/extended/PP_OCRv5_mobile_rec.ncnn.bin")) {
            return 0;
        }
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception&) { return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_offlineime_ExtendedHandwritingModel_nativePredict(
        JNIEnv* env, jclass, jlong handle, jbyteArray gray, jint width,
        jintArray indices, jfloatArray probabilities) {
    auto* session = reinterpret_cast<Session*>(handle);
    constexpr int height = 48, classes = 18385, choices = 7, non_blank = choices - 1;
    if (!session || !gray || !indices || !probabilities || width < 48 || width > 768 ||
        env->GetArrayLength(gray) != width * height) return 0;
    try {
        const int pixel_count = width * height;
        session->pixels.resize(static_cast<size_t>(pixel_count));
        env->GetByteArrayRegion(gray, 0, pixel_count, reinterpret_cast<jbyte*>(session->pixels.data()));
        if (env->ExceptionCheck()) return 0;
        ncnn::Mat input(width, height, 3);
        if (input.empty()) return 0;
        // The official recognizer uses BGR channels normalized to [-1, 1].
        // Black/white handwriting has identical values in all three channels.
        float* c0 = input.channel(0);
        float* c1 = input.channel(1);
        float* c2 = input.channel(2);
        const uint8_t* src = session->pixels.data();
        constexpr float kScale = 1.0f / 127.5f;
        for (int i = 0; i < pixel_count; ++i) c0[i] = src[i] * kScale - 1.f;
        std::copy_n(c0, pixel_count, c1);
        std::copy_n(c0, pixel_count, c2);
        auto extractor = session->net.create_extractor();
        if (extractor.input("in0", input)) return 0;
        ncnn::Mat output;
        if (extractor.extract("out0", output) || output.empty() || output.dims != 2 ||
            output.w != classes || output.h <= 0 || output.h > 192 ||
            output.elempack != 1 || output.elemsize != sizeof(float)) return 0;
        const int size = output.h * choices;
        if (env->GetArrayLength(indices) < size || env->GetArrayLength(probabilities) < size) return 0;
        session->ids.resize(static_cast<size_t>(size));
        session->scores.resize(static_cast<size_t>(size));
        for (int t = 0; t < output.h; ++t) {
            const float* row = output.row(t);
            const float blank = row[0];
            if (!(blank >= 0.f && blank <= 1.001f)) return 0;
            int top_id[non_blank];
            float top_score[non_blank];
            int filled = 0;
            float threshold = -1.f;
            for (int i = 1; i < classes; ++i) {
                const float value = row[i];
                if (!(value >= 0.f && value <= 1.001f)) return 0;
                if (value > threshold) {
                    int pos = filled < non_blank ? filled++ : non_blank - 1;
                    while (pos > 0 && value > top_score[pos - 1]) {
                        top_score[pos] = top_score[pos - 1];
                        top_id[pos] = top_id[pos - 1];
                        --pos;
                    }
                    top_score[pos] = value;
                    top_id[pos] = i;
                    if (filled == non_blank) threshold = top_score[non_blank - 1];
                }
            }
            const int base = t * choices;
            session->ids[base] = 0;
            session->scores[base] = blank;
            for (int j = 0; j < non_blank; ++j) {
                session->ids[base + 1 + j] = top_id[j];
                session->scores[base + 1 + j] = top_score[j];
            }
        }
        env->SetIntArrayRegion(indices, 0, size, session->ids.data());
        env->SetFloatArrayRegion(probabilities, 0, size, session->scores.data());
        return env->ExceptionCheck() ? 0 : output.h;
    } catch (const std::exception&) { return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlineime_ExtendedHandwritingModel_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
