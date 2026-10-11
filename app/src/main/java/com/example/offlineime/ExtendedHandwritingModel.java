package com.example.offlineime;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Bundled PP-OCRv5 Mobile line recognizer, owned exclusively by the handwriting worker. */
final class ExtendedHandwritingModel implements AutoCloseable {
    private long handle;
    private String[] labels;
    private boolean unavailable, closed;

    NeuralHandwritingModel.Prediction recognize(Context context,
            List<? extends List<OfflineHandwritingRecognizer.Point>> strokes, boolean single) {
        if (closed || unavailable) return empty(false);
        if (Thread.currentThread().isInterrupted()) return empty(true);
        try {
            if (handle == 0) load(context.getApplicationContext().getAssets());
            Bitmap bitmap = render(strokes, single);
            if (bitmap == null) return empty(true);
            byte[] gray;
            int width = bitmap.getWidth();
            try {
                int[] colors = new int[width * 48];
                bitmap.getPixels(colors, 0, width, 0, 0, width, 48);
                gray = new byte[colors.length];
                for (int i = 0; i < colors.length; i++) gray[i] = (byte) Color.red(colors[i]);
            } finally { bitmap.recycle(); }
            if (Thread.currentThread().isInterrupted()) return empty(true);
            int[] indices = new int[192 * CtcHandwritingDecoder.CHOICES];
            float[] probabilities = new float[indices.length];
            int steps = nativePredict(handle, gray, width, indices, probabilities);
            if (Thread.currentThread().isInterrupted()) return empty(true);
            if (steps <= 0 || steps > 192) throw new IOException("Invalid extended model shape");
            CtcHandwritingDecoder.Result result = CtcHandwritingDecoder.decode(indices, probabilities, steps, labels, single);
            return new NeuralHandwritingModel.Prediction(result.candidates, result.confidence, true);
        } catch (Exception | LinkageError error) {
            Log.w("OfflineImeHandwriting", "Extended model unavailable: " + error.getClass().getSimpleName());
            unavailable = true; release(); return empty(false);
        }
    }

    private static NeuralHandwritingModel.Prediction empty(boolean ready) {
        return new NeuralHandwritingModel.Prediction(Collections.emptyList(), 0, ready);
    }
    private void load(AssetManager assets) throws Exception {
        System.loadLibrary("offline_handwriting");
        JSONObject root;
        try (InputStream in = assets.open("handwriting/extended/characters.json");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int size;
            while ((size = in.read(buffer)) != -1) bytes.write(buffer, 0, size);
            root = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        }
        JSONArray characters = root.getJSONArray("characters");
        if (root.getInt("num_classes") != 18385 || root.getInt("blank_index") != 0 || characters.length() != 18385)
            throw new IOException("Invalid extended character map");
        labels = new String[characters.length()];
        for (int i = 0; i < labels.length; i++) labels[i] = characters.getString(i);
        handle = nativeCreate(assets);
        if (handle == 0) throw new IOException("Unable to load extended model");
    }

    private static Bitmap render(List<? extends List<OfflineHandwritingRecognizer.Point>> strokes, boolean single) {
        if (strokes.isEmpty()) return null;
        float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (List<OfflineHandwritingRecognizer.Point> stroke : strokes) {
            if (stroke == null || stroke.isEmpty()) return null;
            for (OfflineHandwritingRecognizer.Point p : stroke) {
                if (p == null || !Float.isFinite(p.x) || !Float.isFinite(p.y)) return null;
                left = Math.min(left, p.x); right = Math.max(right, p.x);
                top = Math.min(top, p.y); bottom = Math.max(bottom, p.y);
            }
        }
        float height = Math.max(1, bottom - top), span = Math.max(1, right - left);
        float scale = single ? 40f / Math.max(height, span) : Math.min(40f / height, 752f / span);
        int width = single ? 48 : Math.max(48, Math.min(768, ((int) Math.ceil(span * scale + 16) + 7) / 8 * 8));
        float offsetX = (width - span * scale) / 2, offsetY = (48 - height * scale) / 2;
        Bitmap bitmap = Bitmap.createBitmap(width, 48, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG); pen.setColor(Color.BLACK);
        pen.setStrokeWidth(2.2f); pen.setStrokeCap(Paint.Cap.ROUND); pen.setStrokeJoin(Paint.Join.ROUND);
        for (List<OfflineHandwritingRecognizer.Point> stroke : strokes) {
            Path path = new Path(); boolean first = true;
            for (OfflineHandwritingRecognizer.Point p : stroke) {
                float x = offsetX + (p.x - left) * scale, y = offsetY + (p.y - top) * scale;
                if (first) {
                    path.moveTo(x, y); pen.setStyle(Paint.Style.FILL); canvas.drawCircle(x, y, 1.1f, pen); first = false;
                } else path.lineTo(x, y);
            }
            pen.setStyle(Paint.Style.STROKE); canvas.drawPath(path, pen);
        }
        return bitmap;
    }
    private void release() { if (handle != 0) { nativeDestroy(handle); handle = 0; } labels = null; }
    @Override public void close() { closed = true; release(); }
    private static native long nativeCreate(AssetManager assets);
    private static native int nativePredict(long handle, byte[] gray, int width, int[] indices, float[] probabilities);
    private static native void nativeDestroy(long handle);
}
