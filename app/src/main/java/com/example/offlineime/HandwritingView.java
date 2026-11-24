package com.example.offlineime;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Collects ink and keeps handwriting results local to this input session. */
@SuppressLint("ViewConstructor")
public final class HandwritingView extends View {
    interface Callback {
        void onChoices(List<String> candidates, String draft);
        void onInkChanged(boolean hasInk);
        void onStatus(String message);
        void onConfirm(String value);
    }

    private static final int MAX_STROKES = OfflineHandwritingRecognizer.MAX_STROKES;
    private final SharedPreferences settings;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private final List<Path> paths = new ArrayList<>();
    private final List<List<OfflineHandwritingRecognizer.Point>> strokes = new ArrayList<>();
    private final List<Float> strokeWidths = new ArrayList<>();
    private final List<Integer> boundaries = new ArrayList<>();
    // A marked segment ends at a stroke index. The unsegmented line uses its
    // recognized character positions and keeps edits while that prefix still matches.
    private final Map<Integer, String> segmentEdits = new HashMap<>();
    private final Map<Integer, String> lineEdits = new HashMap<>();
    private final List<Integer> draftPositions = new ArrayList<>();
    private String recognizedLine = "";
    private String draft = "";
    private List<String> candidates = Collections.emptyList();
    private HandwritingEngine.Result result;
    private boolean continuous;

    private ExecutorService worker;
    private HandwritingEngine engine;
    private Future<?> preloadTask;
    private Future<?> recognitionTask;
    private int revision;
    private boolean recognitionRunning;
    private boolean confirmWhenReady;
    private final Runnable recognizeRunnable = this::recognize;

    private Path currentPath;
    private List<OfflineHandwritingRecognizer.Point> currentStroke;
    private float currentStrokeWidthScale = 1f;
    private int activePointer = -1;
    private float inkScale = 1f;
    private long stylusPalmRejectUntilMs;
    private static final long PALM_REJECT_WINDOW_MS = 4000L;

    HandwritingView(Context context, boolean singleOnly, Callback callback) {
        super(context);
        this.callback = callback;
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        continuous = !singleOnly && settings.getBoolean("handwriting_continuous", false);
        density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("手写输入区域");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClickable(true);
    }

    boolean isContinuous() { return continuous; }
    boolean hasInk() { return currentStroke != null || !strokes.isEmpty(); }
    int revision() { return revision; }

    void applyTheme(KeyboardTheme theme) {
        paint.setColor(KeyboardTheme.readableText(theme.text, theme.key));
        setBackgroundColor(theme.key);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(inkScale, inkScale);
        float baseWidth = 5f * density / inkScale;
        for (int i = 0; i < strokes.size(); i++) {
            float scale = i < strokeWidths.size() ? strokeWidths.get(i) : 1f;
            paint.setStrokeWidth(baseWidth * scale);
            drawStroke(canvas, paths.get(i), strokes.get(i));
        }
        if (currentStroke != null) {
            paint.setStrokeWidth(baseWidth * currentStrokeWidthScale);
            drawStroke(canvas, currentPath, currentStroke);
        }
        canvas.restore();
    }

    private void drawStroke(Canvas canvas, Path path, List<OfflineHandwritingRecognizer.Point> stroke) {
        if (stroke.size() == 1) {
            OfflineHandwritingRecognizer.Point point = stroke.get(0);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(point.x, point.y, paint.getStrokeWidth() / 2, paint);
            paint.setStyle(Paint.Style.STROKE);
        } else {
            canvas.drawPath(path, paint);
        }
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Keep the stored coordinates and aspect ratio so expansion restores the
        // original drawing. New strokes use the inverse of the same transform.
        float right = 1f, bottom = 1f;
        for (List<OfflineHandwritingRecognizer.Point> stroke : strokes) {
            for (OfflineHandwritingRecognizer.Point point : stroke) {
                right = Math.max(right, point.x);
                bottom = Math.max(bottom, point.y);
            }
        }
        if (currentStroke != null) {
            for (OfflineHandwritingRecognizer.Point point : currentStroke) {
                right = Math.max(right, point.x);
                bottom = Math.max(bottom, point.y);
            }
        }
        float margin = 3f * density;
        inkScale = Math.min(1f, Math.min(Math.max(1f, width - margin) / right,
                Math.max(1f, height - margin) / bottom));
    }

    private static float stylusWidthScale(MotionEvent event, int pointerIndex) {
        if (event.getToolType(pointerIndex) != MotionEvent.TOOL_TYPE_STYLUS) return 1f;
        float pressure = event.getPressure(pointerIndex);
        if (Float.isNaN(pressure) || pressure <= 0f) return 0.9f;
        return Math.max(0.65f, Math.min(1.55f, 0.55f + pressure * 1.0f));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        int toolType = event.getToolType(actionIndex);
        boolean isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS;
        boolean isEraser = toolType == MotionEvent.TOOL_TYPE_ERASER;
        long now = android.os.SystemClock.uptimeMillis();
        if (isStylus || isEraser) {
            stylusPalmRejectUntilMs = now + PALM_REJECT_WINDOW_MS;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (isEraser) {
                undoLastStroke();
                return true;
            }
            boolean stylusOnly = settings.getBoolean("handwriting_stylus_only", false);
            if (!isStylus && (stylusOnly || now < stylusPalmRejectUntilMs)) {
                if (stylusOnly) callback.onStatus("已开启仅手写笔书写");
                return true;
            }
        } else if (action == MotionEvent.ACTION_POINTER_DOWN && !isStylus && now < stylusPalmRejectUntilMs) {
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                int limit = continuous ? MAX_STROKES * CtcHandwritingDecoder.MAX_CHARACTERS : MAX_STROKES;
                if (strokes.size() >= limit) {
                    callback.onStatus("已达到笔画上限，请选字或撤销一笔");
                    return true;
                }
                cancelRecognition();
                clearChoices();
                activePointer = event.getPointerId(0);
                currentPath = new Path();
                currentStroke = new ArrayList<>();
                currentStrokeWidthScale = stylusWidthScale(event, 0);
                addPoint(event.getX(), event.getY());
                callback.onInkChanged(true);
                callback.onStatus(continuous ? "从左向右书写，可点「分字」" : "单字书写");
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                int pointerIndex = event.findPointerIndex(activePointer);
                if (pointerIndex < 0 || currentStroke == null) return true;
                if (event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS) {
                    stylusPalmRejectUntilMs = now + PALM_REJECT_WINDOW_MS;
                    float sampleScale = stylusWidthScale(event, pointerIndex);
                    currentStrokeWidthScale = currentStrokeWidthScale * 0.7f + sampleScale * 0.3f;
                }
                for (int i = 0; i < event.getHistorySize(); i++) {
                    addPoint(event.getHistoricalX(pointerIndex, i), event.getHistoricalY(pointerIndex, i));
                }
                addPoint(event.getX(pointerIndex), event.getY(pointerIndex));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                int index = event.getActionIndex();
                if (currentStroke != null && event.getPointerId(index) == activePointer) {
                    addPoint(event.getX(index), event.getY(index));
                    boolean dot = currentStroke.size() == 1;
                    if (boundaries.isEmpty() || boundaries.get(boundaries.size() - 1) != strokes.size()) {
                        segmentEdits.remove(strokes.size());
                    }
                    strokes.add(currentStroke);
                    paths.add(currentPath);
                    strokeWidths.add(currentStrokeWidthScale);
                    endStroke();
                    scheduleRecognition();
                    if (dot && action == MotionEvent.ACTION_UP) performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (currentStroke != null) {
                    endStroke();
                    scheduleRecognition();
                    if (strokes.isEmpty()) callback.onStatus(idlePrompt());
                }
                return true;
            default:
                return true;
        }
    }

    private void endStroke() {
        currentPath = null;
        currentStroke = null;
        activePointer = -1;
        callback.onInkChanged(hasInk());
        invalidate();
    }

    private void addPoint(float x, float y) {
        x = Math.max(0f, Math.min(getWidth(), x)) / inkScale;
        y = Math.max(0f, Math.min(getHeight(), y)) / inkScale;
        if (currentStroke.isEmpty()) {
            currentPath.moveTo(x, y);
        } else {
            OfflineHandwritingRecognizer.Point last = currentStroke.get(currentStroke.size() - 1);
            if (Math.hypot(x - last.x, y - last.y) * inkScale < 1.5f * density) return;
            currentPath.lineTo(x, y);
        }
        if (currentStroke.size() >= 2048) {
            ArrayList<OfflineHandwritingRecognizer.Point> compact = new ArrayList<>(1024);
            for (int i = 0; i < currentStroke.size(); i += 2) compact.add(currentStroke.get(i));
            currentStroke = compact;
        }
        currentStroke.add(new OfflineHandwritingRecognizer.Point(x, y));
    }

    private void cancelRecognition() {
        revision++;
        confirmWhenReady = false;
        recognitionRunning = false;
        handler.removeCallbacks(recognizeRunnable);
        if (recognitionTask != null) {
            recognitionTask.cancel(true);
            recognitionTask = null;
        }
    }

    void cancelPendingConfirmation() { confirmWhenReady = false; }

    private void scheduleRecognition() {
        handler.removeCallbacks(recognizeRunnable);
        if (worker == null || strokes.isEmpty()) return;
        int delay = continuous ? 900 : 400;
        try {
            delay = Integer.parseInt(settings.getString(continuous ? "handwriting_line_delay" : "handwriting_delay",
                    String.valueOf(delay)));
        } catch (NumberFormatException ignored) { }
        handler.postDelayed(recognizeRunnable, Math.max(200, Math.min(continuous ? 2000 : 1000, delay)));
    }

    private void recognize() {
        handler.removeCallbacks(recognizeRunnable);
        if (worker == null || strokes.isEmpty() || currentStroke != null || recognitionRunning) return;
        int request = ++revision;
        boolean line = continuous;
        // Completed strokes are immutable. Only the outer list is edited on the UI thread.
        List<List<OfflineHandwritingRecognizer.Point>> snapshot = new ArrayList<>(strokes);
        List<Integer> marks = new ArrayList<>(boundaries);
        HandwritingEngine recognizer = engine;
        Context context = getContext().getApplicationContext();
        recognitionRunning = true;
        callback.onStatus("正在识别…");
        recognitionTask = worker.submit(() -> {
            HandwritingEngine.Result recognized = recognizer.recognize(context, snapshot, line, marks);
            if (Thread.currentThread().isInterrupted()) return;
            handler.post(() -> {
                if (request != revision || worker == null) return;
                recognitionRunning = false;
                recognitionTask = null;
                result = recognized;
                if (line && marks.isEmpty()) updateRecognizedLine(recognized.candidates);
                rebuildChoices();
                callback.onStatus(recognized.status);
                boolean confirm = confirmWhenReady;
                confirmWhenReady = false;
                if (confirm && !candidates.isEmpty() && isShown() && getWindowVisibility() == VISIBLE) {
                    choose(candidates.get(0));
                }
            });
        });
    }

    /** Returns true when Enter belongs to handwriting and must not reach the editor. */
    boolean confirm() {
        if (!hasInk()) return false;
        if (currentStroke != null) return true;
        if (!candidates.isEmpty()) choose(candidates.get(0));
        else if (result == null && worker != null) {
            confirmWhenReady = true;
            recognize();
        } else {
            callback.onStatus("暂无可确认的候选，请补写或清空");
        }
        return true;
    }

    void choose(String value) {
        if (result != null && currentStroke == null && candidates.contains(value)) {
            callback.onConfirm(value);
        }
    }

    void undoLastStroke() {
        cancelRecognition();
        if (currentStroke != null) {
            endStroke();
        } else if (!strokes.isEmpty()) {
            int last = strokes.size();
            strokes.remove(last - 1);
            paths.remove(last - 1);
            if (!strokeWidths.isEmpty()) strokeWidths.remove(strokeWidths.size() - 1);
            segmentEdits.remove(last);
            boundaries.removeIf(end -> end > strokes.size());
            callback.onInkChanged(hasInk());
            invalidate();
        }
        clearChoices();
        if (strokes.isEmpty()) {
            clearEdits();
            inkScale = 1f;
            callback.onStatus(idlePrompt());
        } else {
            callback.onStatus("已撤销一笔");
            scheduleRecognition();
        }
    }

    void clear() {
        cancelRecognition();
        strokes.clear();
        paths.clear();
        strokeWidths.clear();
        boundaries.clear();
        inkScale = 1f;
        clearEdits();
        endStroke();
        clearChoices();
        callback.onStatus(idlePrompt());
    }

    private void clearEdits() {
        lineEdits.clear();
        segmentEdits.clear();
        recognizedLine = "";
    }

    private void clearChoices() {
        result = null;
        if (candidates.isEmpty() && draft.isEmpty()) return;
        draftPositions.clear();
        candidates = Collections.emptyList();
        draft = "";
        callback.onChoices(candidates, draft);
    }

    String idlePrompt() { return continuous ? "从左向右书写" : "请写一个字"; }

    void toggleContinuous() {
        cancelRecognition();
        continuous = !continuous;
        settings.edit().putBoolean("handwriting_continuous", continuous).apply();
        boundaries.clear();
        clearEdits();
        clearChoices();
        callback.onStatus(idlePrompt());
        scheduleRecognition();
    }

    void markCharacterBoundary() {
        if (!continuous || strokes.isEmpty() || currentStroke != null) return;
        int end = strokes.size();
        if (!boundaries.isEmpty() && boundaries.get(boundaries.size() - 1) == end) return;
        if (boundaries.size() >= CtcHandwritingDecoder.MAX_CHARACTERS - 1) {
            callback.onStatus("最多连写 12 个字，请确认后继续");
            return;
        }
        if (boundaries.isEmpty()) {
            if (result != null && recognizedLine.codePointCount(0, recognizedLine.length()) == 1
                    && lineEdits.containsKey(0)) {
                segmentEdits.put(end, lineEdits.get(0));
            }
            lineEdits.clear();
            recognizedLine = "";
        }
        cancelRecognition();
        boundaries.add(end);
        clearChoices();
        callback.onStatus("已分字，请写下一个字");
        scheduleRecognition();
    }

    private void updateRecognizedLine(List<String> values) {
        if (values.isEmpty()) return;
        String next = values.get(0);
        int[] previous = recognizedLine.codePoints().toArray();
        int[] current = next.codePoints().toArray();
        int shared = 0;
        while (shared < previous.length && shared < current.length && previous[shared] == current[shared]) {
            shared++;
        }
        int prefixLength = shared;
        lineEdits.keySet().removeIf(position -> position >= prefixLength);
        recognizedLine = next;
    }

    private void rebuildChoices() {
        StringBuilder text = new StringBuilder();
        draftPositions.clear();
        if (!result.segments.isEmpty()) {
            for (HandwritingEngine.Segment part : result.segments) {
                String choice = segmentEdits.get(part.end);
                if (choice == null) choice = part.candidates.isEmpty() ? "□" : part.candidates.get(0);
                appendDraft(text, choice, part.end);
            }
            candidates = HandwritingEngine.combine(result.segments, segmentEdits);
        } else if (continuous && !result.candidates.isEmpty()) {
            int[] points = recognizedLine.codePoints().toArray();
            for (int i = 0; i < points.length; i++) {
                String choice = lineEdits.get(i);
                appendDraft(text, choice == null ? new String(Character.toChars(points[i])) : choice, i);
            }
            candidates = lineEdits.isEmpty() ? result.candidates
                    : text.length() == 0 ? Collections.emptyList() : Collections.singletonList(text.toString());
        } else {
            candidates = result.candidates;
        }
        draft = text.toString();
        callback.onChoices(candidates, draft);
    }

    private void appendDraft(StringBuilder text, String choice, int position) {
        if (choice.isEmpty()) return;
        draftPositions.add(position);
        text.append(choice);
    }

    List<String> alternativesFor(int position) {
        if (result == null || position < 0 || position >= draftPositions.size()) return Collections.emptyList();
        int key = draftPositions.get(position);
        if (!result.segments.isEmpty()) {
            for (HandwritingEngine.Segment part : result.segments) {
                if (part.end == key) return part.candidates;
            }
            return Collections.emptyList();
        }
        ArrayList<String> alternatives = new ArrayList<>();
        int length = recognizedLine.codePointCount(0, recognizedLine.length());
        for (String value : result.candidates) {
            int[] points = value.codePoints().toArray();
            if (points.length != length) continue;
            String character = new String(Character.toChars(points[key]));
            if (!alternatives.contains(character)) alternatives.add(character);
        }
        return alternatives;
    }

    void replaceDraftCharacter(int expectedRevision, int position, String replacement) {
        if (revision != expectedRevision || result == null || position < 0 || position >= draftPositions.size()) return;
        if (!replacement.isEmpty() && !alternativesFor(position).contains(replacement)) return;
        Map<Integer, String> edits = result.segments.isEmpty() ? lineEdits : segmentEdits;
        edits.put(draftPositions.get(position), replacement);
        revision++;
        rebuildChoices();
        callback.onStatus(candidates.isEmpty() ? "可继续书写或清空" : "点候选或按确认上屏");
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        engine = new HandwritingEngine();
        worker = Executors.newSingleThreadExecutor();
        HandwritingEngine recognizer = engine;
        Context context = getContext().getApplicationContext();
        preloadTask = worker.submit(() -> recognizer.prepare(context));
        if (result == null) scheduleRecognition();
    }

    @Override protected void onDetachedFromWindow() {
        cancelRecognition();
        handler.removeCallbacksAndMessages(null);
        currentStroke = null;
        currentPath = null;
        activePointer = -1;
        if (worker != null) {
            preloadTask.cancel(false);
            // Native inference may still be running. Close the model on its own worker.
            worker.execute(engine::close);
            worker.shutdown();
            worker = null;
            engine = null;
            preloadTask = null;
        }
        super.onDetachedFromWindow();
    }
}
