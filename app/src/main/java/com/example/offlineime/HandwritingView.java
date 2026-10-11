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

/** Offline writing pad with a bundled neural model and stroke-index fallback. */
@SuppressLint("ViewConstructor")
public final class HandwritingView extends View {
    public interface Callback {
        void onCandidates(List<String> candidates);
        void onClose();
        default void onStatus(String message) { }
        default void onDraft(String value) { }
    }

    private static final int MAX_STROKES = OfflineHandwritingRecognizer.MAX_STROKES;
    private HandwritingEngine engine = new HandwritingEngine();
    private boolean continuous;
    private final List<Integer> boundaries = new ArrayList<>();
    private List<String> lastCandidates = new ArrayList<>();
    private String draft = "";
    public boolean isContinuous() { return continuous; }
    public String getDraft() { return draft; }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Path> paths = new ArrayList<>();
    private final List<List<OfflineHandwritingRecognizer.Point>> strokes = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private java.util.concurrent.ExecutorService recognitionExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Callback callback;
    private final Runnable recognizeRunnable;
    private Path currentPath;
    private List<OfflineHandwritingRecognizer.Point> currentStroke;
    private float downX;
    private float downY;
    private boolean currentStrokeMoved;
    private int recognitionGeneration;
    private java.util.concurrent.Future<?> recognitionTask;
    private boolean detached;
    private int activePointer = -1;
    boolean hasStrokes() { return !strokes.isEmpty(); }
    private void cancelRecognition() {
        recognitionGeneration++;
        handler.removeCallbacks(recognizeRunnable);
        if (recognitionTask != null) recognitionTask.cancel(true);
    }

    public HandwritingView(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        continuous = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("handwriting_continuous", false);
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
            if (detached) return;
            if (recognitionTask != null) recognitionTask.cancel(true);
            callback.onStatus("正在离线识别…");
            HandwritingEngine recognizer = engine;
            boolean line = continuous;
            List<Integer> marks = new ArrayList<>(boundaries);
            recognitionTask = recognitionExecutor.submit(() -> {
                HandwritingEngine.Result result = recognizer.recognize(getContext(), snapshot, line, marks);
                if (Thread.currentThread().isInterrupted()) return;
                handler.post(() -> {
                    if (generation == recognitionGeneration && isAttachedToWindow()) {
                        lastCandidates = new ArrayList<>(result.candidates);
                        draft = result.candidates.isEmpty() ? "" : result.candidates.get(0);
                        callback.onStatus(result.status);
                        callback.onCandidates(result.candidates);
                        callback.onDraft(continuous ? draft : "");
                    }
                });
            });
        };
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Path path : paths) canvas.drawPath(path, paint);
        if (currentPath != null) canvas.drawPath(currentPath, paint);
        // Android does not consistently paint a zero-length Path as a visible dot.
        for (List<OfflineHandwritingRecognizer.Point> stroke : strokes) drawDot(canvas, stroke);
        drawDot(canvas, currentStroke);
    }

    private void drawDot(Canvas canvas, List<OfflineHandwritingRecognizer.Point> stroke) {
        if (stroke == null || stroke.size() != 1) return;
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(stroke.get(0).x, stroke.get(0).y, paint.getStrokeWidth() / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelRecognition();
                activePointer = event.getPointerId(0);
                if (strokes.size() >= (continuous ? MAX_STROKES * CtcHandwritingDecoder.MAX_CHARACTERS : MAX_STROKES)) {
                    android.widget.Toast.makeText(getContext(), "已达到笔画上限，请选字或撤销一笔", android.widget.Toast.LENGTH_SHORT).show();
                    scheduleRecognition();
                    return true;
                }
                discardDraft();
                callback.onStatus(continuous ? "从左向右连续写字 · 停笔识别，可点「分字」标记字界" : "单字书写 · 支持繁体和生僻字");
                currentPath = new Path();
                currentPath.moveTo(event.getX(), event.getY());
                currentStroke = new ArrayList<>();
                downX = event.getX();
                downY = event.getY();
                currentStrokeMoved = false;
                addPoint(event.getX(), event.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN: return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == activePointer) { currentPath = null; currentStroke = null; activePointer = -1; invalidate(); scheduleRecognition(); }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.findPointerIndex(activePointer) != 0) return true;
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
                activePointer = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelRecognition(); activePointer = -1;
                currentPath = null;
                currentStroke = null;
                invalidate();
                if (!strokes.isEmpty()) scheduleRecognition();
                return true;
            default:
                return true;
        }
    }

    private void addPoint(float x, float y) {
        if (currentStroke == null || currentPath == null) return;
        x = Math.max(0f, Math.min(getWidth(), x));
        y = Math.max(0f, Math.min(getHeight(), y));
        currentPath.lineTo(x, y);
        if (!currentStroke.isEmpty()) {
            OfflineHandwritingRecognizer.Point last = currentStroke.get(currentStroke.size() - 1);
            if (Math.hypot(x - last.x, y - last.y) < 1.5f * getResources().getDisplayMetrics().density) return;
        }
        if (currentStroke.size() >= 2048) {
            // Retain the entire gesture rather than dropping all points after the cap.
            ArrayList<OfflineHandwritingRecognizer.Point> compact = new ArrayList<>(1024);
            for (int i = 0; i < currentStroke.size(); i += 2) compact.add(currentStroke.get(i));
            currentStroke = compact;
        }
        currentStroke.add(new OfflineHandwritingRecognizer.Point(x, y));
    }

    private void scheduleRecognition() {
        handler.removeCallbacks(recognizeRunnable);
        long delay = continuous ? 900 : 400;
        try { delay = Long.parseLong(getContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(continuous ? "handwriting_line_delay" : "handwriting_delay", continuous ? "900" : "400")); }
        catch (NumberFormatException ignored) { }
        if (!detached) handler.postDelayed(recognizeRunnable, Math.max(200, Math.min(continuous ? 2000 : 1000, delay)));
    }

    private List<List<OfflineHandwritingRecognizer.Point>> copyStrokes() {
        ArrayList<List<OfflineHandwritingRecognizer.Point>> copy = new ArrayList<>(strokes.size());
        for (List<OfflineHandwritingRecognizer.Point> stroke : strokes) copy.add(new ArrayList<>(stroke));
        return copy;
    }

    public void undoLastStroke() {
        cancelRecognition();
        currentStroke = null;
        currentPath = null;
        activePointer = -1;
        if (strokes.isEmpty()) { discardDraft(); invalidate(); return; }
        strokes.remove(strokes.size() - 1);
        paths.remove(paths.size() - 1);
        boundaries.removeIf(end -> end >= strokes.size());
        invalidate();
        discardDraft();
        callback.onStatus("已撤销一笔 · 可继续书写");
        if (strokes.isEmpty()) {
            handler.removeCallbacks(recognizeRunnable);
            callback.onCandidates(new ArrayList<>());
        } else {
            scheduleRecognition();
        }
    }

    public void clear() {
        cancelRecognition();
        activePointer = -1;
        strokes.clear();
        paths.clear();
        boundaries.clear();
        currentStroke = null;
        currentPath = null;
        discardDraft();
        callback.onStatus(continuous ? "连续多字 · 从左向右书写，最多 12 字" : "扩展离线模型 · 请书写一个汉字");
        invalidate();
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void discardDraft() {
        draft = ""; lastCandidates = new ArrayList<>();
        callback.onCandidates(new ArrayList<>()); callback.onDraft("");
    }

    public void setContinuous(boolean enabled) {
        setContinuous(enabled, true);
    }

    public void forceSingleCharacter() { setContinuous(false, false); }

    private void setContinuous(boolean enabled, boolean remember) {
        cancelRecognition(); continuous = enabled; boundaries.clear(); discardDraft();
        if (remember) getContext().getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                    .putBoolean("handwriting_continuous", enabled).apply();
        callback.onStatus(enabled ? "连续多字 · 从左向右书写，最多 12 字" : "单字书写 · 支持繁体和生僻字");
        if (!strokes.isEmpty()) scheduleRecognition();
    }

    public void markCharacterBoundary() {
        if (!continuous || strokes.isEmpty() || currentStroke != null) return;
        int end = strokes.size();
        if (!boundaries.isEmpty() && boundaries.get(boundaries.size() - 1) == end) return;
        if (boundaries.size() >= CtcHandwritingDecoder.MAX_CHARACTERS - 1) {
            callback.onStatus("已标记 12 字范围，请选候选上屏或清空后继续"); return;
        }
        cancelRecognition(); boundaries.add(end); discardDraft();
        callback.onStatus("已标记第 " + boundaries.size() + " 个字的结束位置，请继续写下一字");
        scheduleRecognition();
    }

    public List<String> alternativesFor(int position) {
        ArrayList<String> alternatives = new ArrayList<>();
        int[] selected = draft.codePoints().toArray();
        if (position < 0 || position >= selected.length) return alternatives;
        alternatives.add(new String(Character.toChars(selected[position])));
        for (String value : lastCandidates) {
            int[] points = value.codePoints().toArray();
            if (points.length != selected.length) continue;
            String character = new String(Character.toChars(points[position]));
            if (!alternatives.contains(character)) alternatives.add(character);
        }
        return alternatives;
    }

    public void replaceDraftCharacter(int position, String replacement) {
        int[] points = draft.codePoints().toArray();
        if (position < 0 || position >= points.length) return;
        cancelRecognition();
        StringBuilder changed = new StringBuilder();
        for (int i = 0; i < points.length; i++) {
            if (i == position) changed.append(replacement); else changed.appendCodePoint(points[i]);
        }
        if (replacement.isEmpty()) {
            ArrayList<String> shortened = new ArrayList<>();
            for (String candidate : lastCandidates) {
                int[] original = candidate.codePoints().toArray();
                if (original.length != points.length) continue;
                StringBuilder shorter = new StringBuilder();
                for (int i = 0; i < original.length; i++) if (i != position) shorter.appendCodePoint(original[i]);
                String value = shorter.toString();
                if (!shortened.contains(value)) shortened.add(value);
            }
            lastCandidates = shortened;
        }
        draft = changed.toString();
        ArrayList<String> candidates = new ArrayList<>();
        if (!draft.isEmpty()) candidates.add(draft);
        callback.onCandidates(candidates); callback.onDraft(draft);
        callback.onStatus("已修改预览 · 点候选整段上屏");
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (detached) {
            engine = new HandwritingEngine();
            recognitionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
            detached = false;
            if (!strokes.isEmpty()) scheduleRecognition();
        }
    }

    @Override protected void onDetachedFromWindow() {
        detached = true; cancelRecognition();
        handler.removeCallbacksAndMessages(null);
        // Native inference cannot be interrupted by Future.cancel(). Release on the
        // same worker, after any in-flight call, so closing the pad never races JNI.
        if (!recognitionExecutor.isShutdown()) {
            recognitionExecutor.execute(engine::close);
            recognitionExecutor.shutdown();
        }
        super.onDetachedFromWindow();
    }
}
