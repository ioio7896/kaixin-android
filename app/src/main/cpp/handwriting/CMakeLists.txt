cmake_minimum_required(VERSION 3.22)
project(offline_handwriting LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
set(ncnn_DIR "${PROJECT_ROOT}/third_party/ncnn/ncnn-20260526-android/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED CONFIG)
add_library(offline_handwriting SHARED handwriting_jni.cpp preprocess.c)
# NCNN exports -fno-exceptions to consumers; enable them only for our JNI
# boundary so allocation failures are converted to the Java fallback result.
set_source_files_properties(handwriting_jni.cpp PROPERTIES COMPILE_OPTIONS "-fexceptions")
target_link_libraries(offline_handwriting PRIVATE ncnn android log)
target_compile_options(offline_handwriting PRIVATE -Wall -Wextra)
target_link_options(offline_handwriting PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
