cmake_minimum_required(VERSION 3.22)
project(offline_handwriting LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
set(ncnn_DIR "${PROJECT_ROOT}/third_party/ncnn/ncnn-20260526-android/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED CONFIG)
add_library(offline_handwriting SHARED handwriting_jni.cpp preprocess.c)
# Build the speech recognizer into the same library as handwriting so both
# features share the existing statically linked ncnn runtime.
set(SHERPA_ROOT "${PROJECT_ROOT}/third_party/sherpa-ncnn")
set(FBANK_ROOT "${PROJECT_ROOT}/third_party/kaldi-native-fbank")
set(SHERPA_SOURCES
    context-graph.cc conv-emformer-model.cc decoder.cc endpoint.cc features.cc
    greedy-search-decoder.cc hypothesis.cc lstm-model.cc meta-data.cc model.cc
    modified-beam-search-decoder.cc poolingmodulenoproj.cc recognizer.cc
    resample.cc simpleupsample.cc stack.cc stream.cc symbol-table.cc
    tensorasstrided.cc wave-reader.cc zipformer-model.cc)
list(TRANSFORM SHERPA_SOURCES PREPEND "${SHERPA_ROOT}/sherpa-ncnn/csrc/")
set(FBANK_SOURCES
    feature-fbank.cc feature-functions.cc feature-window.cc fftsg.cc
    mel-computations.cc online-feature.cc rfft.cc whisper-feature.cc)
list(TRANSFORM FBANK_SOURCES PREPEND "${FBANK_ROOT}/kaldi-native-fbank/csrc/")
target_sources(offline_handwriting PRIVATE speech_jni.cpp ${SHERPA_SOURCES} ${FBANK_SOURCES})
target_include_directories(offline_handwriting PRIVATE "${SHERPA_ROOT}" "${FBANK_ROOT}")
# NCNN exports -fno-exceptions to consumers; enable them only for our JNI
# boundary so allocation failures are converted to the Java fallback result.
set_source_files_properties(handwriting_jni.cpp speech_jni.cpp ${SHERPA_SOURCES}
    PROPERTIES COMPILE_OPTIONS "-fexceptions")
target_link_libraries(offline_handwriting PRIVATE ncnn android log)
target_compile_options(offline_handwriting PRIVATE -Wall -Wextra -O3 -ffunction-sections -fdata-sections)
target_compile_options(offline_handwriting PRIVATE "-ffile-prefix-map=${PROJECT_ROOT}=.")
target_link_options(offline_handwriting PRIVATE "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
