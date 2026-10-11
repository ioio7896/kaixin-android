package com.example.offlineime;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** One worker owns one native session. No disk writes, network, or stroke logging. */
final class NeuralHandwritingModel implements AutoCloseable {
    private static final int CLASSES = 3755;
    private static final int RENDER_SIZE = 256;
    private long handle;
    private String[] characters;
    private boolean unavailable;
    private boolean closed;

    static final class Prediction {
        final List<String> candidates;
        final float confidence;
        final boolean modelAvailable;

        Prediction(List<String> candidates, float confidence, boolean modelAvailable) {
            this.candidates = candidates;
            this.confidence = confidence;
            this.modelAvailable = modelAvailable;
        }
    }

    Prediction recognize(Context context, List<? extends List<OfflineHandwritingRecognizer.Point>> strokes) {
        if (Thread.currentThread().isInterrupted()) return empty(true);
        if (closed || unavailable) return empty(false);
        try {
            if (handle == 0) load(context.getApplicationContext().getAssets());
            if (Thread.currentThread().isInterrupted()) return empty(true);
            byte[] pixels = render(strokes);
            if (pixels == null) return empty(true);
            int[] indices = new int[12];
            float[] probabilities = new float[12];
            int count = nativeRecognize(handle, pixels, RENDER_SIZE, RENDER_SIZE, indices, probabilities);
            if (Thread.currentThread().isInterrupted()) return empty(true);
            if (count <= 0 || count > indices.length) throw new IOException("Invalid model output");
            ArrayList<String> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (indices[i] < 0 || indices[i] >= characters.length || !Float.isFinite(probabilities[i]))
                    throw new IOException("Invalid model class");
                result.add(characters[indices[i]]);
            }
            return new Prediction(result, probabilities[0], true);
        } catch (Exception | LinkageError error) {
            // Report only the failure type; never include users' strokes or predictions in logs.
            Log.w("OfflineImeHandwriting", "Neural model unavailable: " + error.getClass().getSimpleName());
            unavailable = true;
            release();
            return empty(false);
        }
    }

    private static Prediction empty(boolean available) {
        return new Prediction(Collections.emptyList(), 0, available);
    }

    private void load(AssetManager assets) throws Exception {
        System.loadLibrary("offline_handwriting");
        JSONObject root;
        try (InputStream stream = assets.open("handwriting/charset.json");
             ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) data.write(buffer, 0, count);
            root = new JSONObject(data.toString(StandardCharsets.UTF_8.name()));
        }
        JSONObject mapping = root.getJSONObject("char_to_idx");
        if (root.getInt("num_classes") != CLASSES || mapping.length() != CLASSES)
            throw new IOException("Unexpected character map size");
        String[] labels = new String[CLASSES];
        Iterator<String> keys = mapping.keys();
        while (keys.hasNext()) {
            String character = keys.next();
            int index = mapping.getInt(character);
            if (index < 0 || index >= CLASSES || labels[index] != null ||
                    character.codePointCount(0, character.length()) != 1)
                throw new IOException("Invalid character map");
            labels[index] = character;
        }
        handle = nativeCreate(assets);
        if (handle == 0) throw new IOException("Unable to load bundled model");
        characters = labels;
    }

    /** Draw theme-independent black ink; the upstream C preprocessor crops and normalizes it. */
    private static byte[] render(List<? extends List<OfflineHandwritingRecognizer.Point>> strokes) {
        if (strokes == null || strokes.isEmpty()) return null;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (List<OfflineHandwritingRecognizer.Point> stroke : strokes) {
            if (stroke == null || stroke.isEmpty()) return null;
            for (OfflineHandwritingRecognizer.Point point : stroke) {
                if (point == null || !Float.isFinite(point.x) || !Float.isFinite(point.y)) return null;
                minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y);
            }
        }
        float side = Math.max(Math.max(maxX - minX, maxY - minY), 1f);
        float scale = (RENDER_SIZE - 24f) / side;
        float offsetX = (RENDER_SIZE - (maxX - minX) * scale) / 2f;
        float offsetY = (RENDER_SIZE - (maxY - minY) * scale) / 2f;
        Bitmap bitmap = Bitmap.createBitmap(RENDER_SIZE, RENDER_SIZE, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(Color.BLACK);
            ink.setStrokeWidth(10f);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            for (List<OfflineHandwritingRecognizer.Point> stroke : strokes) {
                if (Thread.currentThread().isInterrupted()) return null;
                Path path = new Path();
                boolean first = true;
                for (OfflineHandwritingRecognizer.Point point : stroke) {
                    float x = offsetX + (point.x - minX) * scale;
                    float y = offsetY + (point.y - minY) * scale;
                    if (first) {
                        path.moveTo(x, y);
                        ink.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(x, y, 5f, ink);
                        first = false;
                    } else path.lineTo(x, y);
                }
                ink.setStyle(Paint.Style.STROKE);
                canvas.drawPath(path, ink);
            }
            int[] colors = new int[RENDER_SIZE * RENDER_SIZE];
            bitmap.getPixels(colors, 0, RENDER_SIZE, 0, 0, RENDER_SIZE, RENDER_SIZE);
            byte[] gray = new byte[colors.length];
            for (int i = 0; i < colors.length; i++) gray[i] = (byte) Color.red(colors[i]);
            return gray;
        } finally { bitmap.recycle(); }
    }

    private void release() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
        characters = null;
    }

    @Override public void close() {
        closed = true;
        release();
    }

    private static native long nativeCreate(AssetManager assets);
    private static native int nativeRecognize(long handle, byte[] gray, int width, int height,
                                              int[] indices, float[] probabilities);
    private static native void nativeDestroy(long handle);
}
