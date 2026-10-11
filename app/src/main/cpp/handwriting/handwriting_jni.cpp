#include <jni.h>
#include <android/asset_manager_jni.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <numeric>
#include <vector>
#include "net.h"
#include "preprocess.h"

namespace {
constexpr int kClasses = 3755;
struct Session { ncnn::Net net; };
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
        // The asset loader owns/copies the weights; no dangling temporary model buffer.
        if (session->net.load_param(manager, "handwriting/model.ncnn.param") != 0 ||
            session->net.load_model(manager, "handwriting/model.ncnn.bin") != 0) return 0;
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
        std::vector<uint8_t> pixels(static_cast<size_t>(width) * height);
        env->GetByteArrayRegion(gray, 0, width * height, reinterpret_cast<jbyte*>(pixels.data()));
        if (env->ExceptionCheck()) return 0;
        ncnn::Mat input(64, 64, 1);
        if (input.empty() || !hccr_preprocess(pixels.data(), width, height, input)) return 0;
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
        std::vector<float> scores(kClasses);
        double total = 0;
        for (int i = 0; i < kClasses; ++i) total += (scores[i] = std::exp(logits[i] - maximum));
        if (!std::isfinite(total) || total <= 0) return 0;
        std::vector<jint> order(kClasses);
        std::iota(order.begin(), order.end(), 0);
        std::partial_sort(order.begin(), order.begin() + k, order.end(),
                [&scores](int a, int b) { return scores[a] == scores[b] ? a < b : scores[a] > scores[b]; });
        std::vector<jfloat> top(k);
        for (int i = 0; i < k; ++i) top[i] = static_cast<float>(scores[order[i]] / total);
        env->SetIntArrayRegion(indices, 0, k, order.data());
        env->SetFloatArrayRegion(probabilities, 0, k, top.data());
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
        session->net.opt.use_fp16_arithmetic = false;
        session->net.opt.use_fp16_storage = false;
        session->net.opt.use_fp16_packed = false;
        if (session->net.load_param(manager, "handwriting/extended/PP_OCRv5_mobile_rec.ncnn.param") ||
            session->net.load_model(manager, "handwriting/extended/PP_OCRv5_mobile_rec.ncnn.bin")) return 0;
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception&) { return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_offlineime_ExtendedHandwritingModel_nativePredict(
        JNIEnv* env, jclass, jlong handle, jbyteArray gray, jint width,
        jintArray indices, jfloatArray probabilities) {
    auto* session = reinterpret_cast<Session*>(handle);
    constexpr int height = 48, classes = 18385, choices = 7;
    if (!session || !gray || !indices || !probabilities || width < 48 || width > 768 ||
        env->GetArrayLength(gray) != width * height) return 0;
    try {
        std::vector<uint8_t> pixels(static_cast<size_t>(width) * height);
        env->GetByteArrayRegion(gray, 0, width * height, reinterpret_cast<jbyte*>(pixels.data()));
        if (env->ExceptionCheck()) return 0;
        ncnn::Mat input(width, height, 3);
        if (input.empty()) return 0;
        // The official recognizer uses BGR channels normalized to [-1, 1].
        // Black/white handwriting has identical values in all three channels.
        for (int c = 0; c < 3; ++c) {
            float* channel = input.channel(c);
            for (int i = 0; i < width * height; ++i) channel[i] = pixels[i] / 127.5f - 1.f;
        }
        auto extractor = session->net.create_extractor();
        if (extractor.input("in0", input)) return 0;
        ncnn::Mat output;
        if (extractor.extract("out0", output) || output.empty() || output.dims != 2 ||
            output.w != classes || output.h <= 0 || output.h > 192 ||
            output.elempack != 1 || output.elemsize != sizeof(float)) return 0;
        int size = output.h * choices;
        if (env->GetArrayLength(indices) < size || env->GetArrayLength(probabilities) < size) return 0;
        std::vector<jint> ids(size);
        std::vector<jfloat> scores(size);
        for (int t = 0; t < output.h; ++t) {
            const float* row = output.row(t);
            std::vector<int> order(classes - 1);
            std::iota(order.begin(), order.end(), 1);
            for (int i = 0; i < classes; ++i)
                if (!std::isfinite(row[i]) || row[i] < 0 || row[i] > 1.001f) return 0;
            std::partial_sort(order.begin(), order.begin() + choices - 1, order.end(),
                    [row](int a, int b) { return row[a] == row[b] ? a < b : row[a] > row[b]; });
            ids[t * choices] = 0;
            scores[t * choices] = row[0];
            for (int j = 1; j < choices; ++j) {
                ids[t * choices + j] = order[j - 1];
                scores[t * choices + j] = row[order[j - 1]];
            }
        }
        env->SetIntArrayRegion(indices, 0, size, ids.data());
        env->SetFloatArrayRegion(probabilities, 0, size, scores.data());
        return env->ExceptionCheck() ? 0 : output.h;
    } catch (const std::exception&) { return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_offlineime_ExtendedHandwritingModel_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
