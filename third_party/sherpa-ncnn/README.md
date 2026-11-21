# Bundled offline speech dependencies

- sherpa-ncnn v2.1.10, Apache-2.0, source archive SHA-256 `67c39910e0e5bb48bf0d2e3873a9b6aef8f5aadde1f1d229fec2edb1f6c8163a`: https://github.com/k2-fsa/sherpa-ncnn/tree/v2.1.10
- kaldi-native-fbank v1.18.7, Apache-2.0, source archive SHA-256 `e78fd9d481d83d7d6d1be0012752e6531cb614e030558a3491e3c033cb8e0e4e`: https://github.com/csukuangfj/kaldi-native-fbank/tree/v1.18.7
- Chinese streaming Zipformer 14M model, Apache-2.0, release archive SHA-256 `b12f7af0ee53b9ec7089579349b8536d03ec0b4a0e1f4d8ea26be14c395e597f`: https://github.com/k2-fsa/sherpa-ncnn/releases/download/models/sherpa-ncnn-streaming-zipformer-zh-14M-2023-02-23.tar.bz2

Only the runtime source is compiled. CMake links the existing packaged ncnn static library once into the handwriting/speech JNI library. `features.cc`, `model.cc`, `recognizer.cc`, `resample.cc` and `symbol-table.cc` replace fatal exits with exceptions caught at the JNI boundary; model creation also throws instead of returning a null model. No tests, network access, GPU runtime, or external downloads are needed to build the app. The feature extractor includes Ooura FFT; its notice is bundled in the speech assets.
