# NCNN Android CPU runtime

The `ncnn-20260526-android` directory contains the unmodified official static
Android libraries and CMake files from Tencent/ncnn release `20260526`:

https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-android.zip

Archive SHA-256: `85b18b875488585c2d21360430e0e54abb6c04aa88094b471c20208ab55ff796`.

License text is bundled at `app/src/main/assets/handwriting/LICENSE-ncnn.txt`.
Original source: https://github.com/Tencent/ncnn/tree/20260526

The app links the CPU runtime statically into `liboffline_handwriting.so` for
armeabi-v7a, arm64-v8a, x86 and x86_64. Vulkan and external runtime downloads
are not used. CMake uses the installed NDK 27.2.12479018 and 16 KB alignment.

To restore this dependency, download the pinned archive, compare its SHA-256,
then extract it into this directory while preserving the archive root folder.
No inference or tests are performed by the build.
