package com.example.offlineime;

import android.content.Context;
import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Offline writing pad backed by local trajectory and five-stroke recognition. */
@SuppressLint("ViewConstructor")
public final class HandwritingView extends View {
    public interface Callback {
        void onCandidates(List<String> candidates);
        void onClose();
    }

    private static final long RECOGNITION_DELAY_MS = 320;
    private static final int MAX_STROKES = 30;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Path> paths = new ArrayList<>();
    private final List<List<OfflineHandwritingRecognizer.Point>> strokes = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService recognitionExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Callback callback;
    private final Runnable recognizeRunnable;
    private Path currentPath;
    private List<OfflineHandwritingRecognizer.Point> currentStroke;
    private float downX;
    private float downY;
    private boolean currentStrokeMoved;
    private int recognitionGeneration;

    public HandwritingView(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        KeyboardTheme theme = KeyboardTheme.from(context,
                context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("dark", false));
        paint.setColor(theme.text);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f * getResources().getDisplayMetrics().density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setBackgroundColor(theme.key);
        setContentDescription("手写输入区域");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClickable(true);
        recognizeRunnable = () -> {
            int generation = ++recognitionGeneration;
            List<List<OfflineHandwritingRecognizer.Point>> snapshot = copyStrokes();
            recognitionExecutor.execute(() -> {
                List<String> result = OfflineHandwritingRecognizer.recognize(getContext(), snapshot);
                handler.post(() -> {
                    if (generation == recognitionGeneration && isAttachedToWindow())
                        callback.onCandidates(result);
                });
            });
        };
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Path path : paths) canvas.drawPath(path, paint);
        if (currentPath != null) canvas.drawPath(currentPath, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (strokes.size() >= MAX_STROKES) return true;
                handler.removeCallbacks(recognizeRunnable);
                currentPath = new Path();
                currentPath.moveTo(event.getX(), event.getY());
                currentStroke = new ArrayList<>();
                downX = event.getX();
                downY = event.getY();
                currentStrokeMoved = false;
                addPoint(event.getX(), event.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (currentPath != null && currentStroke != null) {
                    if (Math.hypot(event.getX() - downX, event.getY() - downY)
                            > android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        currentStrokeMoved = true;
                    }
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        addPoint(event.getHistoricalX(i), event.getHistoricalY(i));
                    }
                    addPoint(event.getX(), event.getY());
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (currentPath != null && currentStroke != null) {
                    addPoint(event.getX(), event.getY());
                    paths.add(currentPath);
                    strokes.add(currentStroke);
                    currentPath = null;
                    currentStroke = null;
                    invalidate();
                    scheduleRecognition();
                    if (!currentStrokeMoved) performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                currentPath = null;
                currentStroke = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void addPoint(float x, float y) {
        if (currentStroke == null || currentPath == null) return;
        currentPath.lineTo(x, y);
        currentStroke.add(new OfflineHandwritingRecognizer.Point(x, y));
    }

    private void scheduleRecognition() {
        handler.removeCallbacks(recognizeRunnable);
        handler.postDelayed(recognizeRunnable, RECOGNITION_DELAY_MS);
    }

    private List<List<OfflineHandwritingRecognizer.Point>> copyStrokes() {
        ArrayList<List<OfflineHandwritingRecognizer.Point>> copy = new ArrayList<>(strokes.size());
        for (List<OfflineHandwritingRecognizer.Point> stroke : strokes) copy.add(new ArrayList<>(stroke));
        return copy;
    }

    public void undoLastStroke() {
        if (strokes.isEmpty()) return;
        strokes.remove(strokes.size() - 1);
        paths.remove(paths.size() - 1);
        invalidate();
        if (strokes.isEmpty()) {
            handler.removeCallbacks(recognizeRunnable);
            callback.onCandidates(new ArrayList<>());
        } else {
            scheduleRecognition();
        }
    }

    public void clear() {
        handler.removeCallbacks(recognizeRunnable);
        recognitionGeneration++;
        strokes.clear();
        paths.clear();
        currentStroke = null;
        currentPath = null;
        callback.onCandidates(new ArrayList<>());
        invalidate();
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        recognitionGeneration++;
        handler.removeCallbacksAndMessages(null);
        recognitionExecutor.shutdownNow();
        super.onDetachedFromWindow();
    }
}
