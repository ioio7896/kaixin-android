package com.example.offlineime;

import android.content.res.AssetManager;
import java.io.IOException;

/** Owned by the recording worker; recognition and audio never leave the process. */
final class OfflineVoiceModel implements AutoCloseable {
    private long handle;

    OfflineVoiceModel(AssetManager assets) throws IOException {
        try {
            System.loadLibrary("offline_handwriting");
            handle = nativeCreate(assets);
        } catch (LinkageError error) {
            throw new IOException("语音识别库不可用", error);
        }
        if (handle == 0) throw new IOException("语音模型不可用");
    }

    void beginRecording() throws IOException {
        if (handle == 0 || !nativeBeginRecording(handle)) throw new IOException("无法开始语音识别");
    }

    void endRecording() {
        if (handle != 0) nativeEndRecording(handle);
    }

    String accept(float[] samples, int count, boolean finished) throws IOException {
        if (handle == 0) throw new IOException("语音识别已关闭");
        String text = nativeAccept(handle, samples, count, finished);
        if (text == null) throw new IOException("语音识别失败");
        return text;
    }

    /** 0 = ongoing, 1 = silence endpoint, 2 = long-segment boundary. */
    int endpointKind() throws IOException {
        int kind = nativeEndpointKind(handle);
        if (kind < 0) throw new IOException("语音分段失败");
        return kind;
    }

    void resetSegment() throws IOException {
        if (handle == 0 || !nativeReset(handle)) throw new IOException("语音分段失败");
    }

    @Override public void close() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }

    private static native long nativeCreate(AssetManager assets);
    private static native boolean nativeBeginRecording(long handle);
    private static native void nativeEndRecording(long handle);
    private static native String nativeAccept(long handle, float[] samples, int count, boolean finished);
    private static native int nativeEndpointKind(long handle);
    private static native boolean nativeReset(long handle);
    private static native void nativeDestroy(long handle);
}
