package com.example.offlineime;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Both bundled models live and close on the same worker; all results stay in memory. */
final class HandwritingEngine implements AutoCloseable {
    private static final float SINGLE_FAST_PATH_CONFIDENCE = .90f;
    private final ExtendedHandwritingModel extended = new ExtendedHandwritingModel();
    private final NeuralHandwritingModel common = new NeuralHandwritingModel();

    static final class Result {
        final List<String> candidates;
        final List<Segment> segments;
        final String status;
        Result(List<String> candidates, String status) {
            this(candidates, Collections.emptyList(), status);
        }
        Result(List<String> candidates, List<Segment> segments, String status) {
            this.candidates = candidates;
            this.segments = segments;
            this.status = status;
        }
    }

    static final class Segment {
        final int end;
        final List<String> candidates;
        Segment(int end, List<String> candidates) {
            this.end = end;
            this.candidates = candidates;
        }
    }

    void prepare(Context context) { extended.prepare(context); }

    Result recognize(Context context, List<List<OfflineHandwritingRecognizer.Point>> strokes,
                     boolean continuous, List<Integer> boundaries) {
        if (strokes.isEmpty()) return new Result(Collections.emptyList(), "请书写后停笔");
        if (!continuous) return single(context, strokes);
        if (!boundaries.isEmpty()) return separated(context, strokes, boundaries);
        NeuralHandwritingModel.Prediction prediction = extended.recognize(context, strokes, false);
        if (Thread.currentThread().isInterrupted()) return new Result(Collections.emptyList(), "");
        List<String> values = convert(context, prediction.candidates);
        return new Result(values, !prediction.modelAvailable ? "连写模型暂不可用，可用「分字」逐字识别"
                : values.isEmpty() ? "未识别到文字，请横向书写或用「分字」标记字界"
                : prediction.confidence < .50f ? "可点「改字」核对识别结果"
                : "点候选上屏，或逐字修改");
    }

    private Result single(Context context, List<List<OfflineHandwritingRecognizer.Point>> strokes) {
        NeuralHandwritingModel.Prediction wide = extended.recognize(context, strokes, true);
        if (Thread.currentThread().isInterrupted()) return new Result(Collections.emptyList(), "");
        ArrayList<String> modelCandidates = new ArrayList<>(wide.candidates.subList(0, Math.min(8, wide.candidates.size())));
        if (wide.confidence >= SINGLE_FAST_PATH_CONFIDENCE && modelCandidates.size() >= 4) {
            List<String> primary = convert(context, modelCandidates);
            if (primary.size() >= 4) return new Result(primary, "点选候选上屏");
        }
        NeuralHandwritingModel.Prediction narrow = common.recognize(context, strokes);
        if (Thread.currentThread().isInterrupted()) return new Result(Collections.emptyList(), "");
        for (int i = 0; i < Math.min(4, narrow.candidates.size()); i++) {
            String value = narrow.candidates.get(i);
            if (!modelCandidates.contains(value)) modelCandidates.add(value);
        }
        List<String> candidates = OfflineHandwritingRecognizer.recognize(context, strokes, modelCandidates);
        return new Result(candidates, candidates.isEmpty() ? "未识别到文字，请补笔或重写" : "点选候选上屏");
    }

    private Result separated(Context context, List<List<OfflineHandwritingRecognizer.Point>> strokes,
                             List<Integer> boundaries) {
        ArrayList<Segment> parts = new ArrayList<>();
        int start = 0;
        int missing = 0;
        ArrayList<Integer> ends = new ArrayList<>(boundaries);
        if (ends.isEmpty() || ends.get(ends.size() - 1) < strokes.size()) ends.add(strokes.size());
        for (int end : ends) {
            if (end <= start || end > strokes.size()) continue;
            if (parts.size() >= CtcHandwritingDecoder.MAX_CHARACTERS || Thread.currentThread().isInterrupted())
                return new Result(Collections.emptyList(), "最多连续书写 12 个字");
            List<String> choices = new ArrayList<>(single(context, strokes.subList(start, end)).candidates);
            choices.removeIf(value -> value.codePointCount(0, value.length()) != 1);
            if (choices.isEmpty() && missing == 0) missing = parts.size() + 1;
            parts.add(new Segment(end, choices));
            start = end;
        }
        return new Result(Collections.emptyList(), parts, missing == 0 ? "点「改字」可逐字修改"
                : "第 " + missing + " 个字未识别，请补笔或撤销");
    }

    static List<String> combine(List<Segment> parts, Map<Integer, String> edits) {
        ArrayList<Combination> combinations = new ArrayList<>();
        combinations.add(new Combination("", 0));
        for (Segment segment : parts) {
            String replacement = edits.get(segment.end);
            List<String> part = replacement == null ? segment.candidates : Collections.singletonList(replacement);
            if (part.isEmpty()) return Collections.emptyList();
            ArrayList<Combination> next = new ArrayList<>();
            for (int rank = 0; rank < Math.min(4, part.size()); rank++) {
                for (Combination prefix : combinations)
                    next.add(new Combination(prefix.text + part.get(rank), prefix.rank + rank));
            }
            next.sort((a, b) -> Integer.compare(a.rank, b.rank));
            combinations = new ArrayList<>(next.subList(0, Math.min(12, next.size())));
        }
        ArrayList<String> values = new ArrayList<>();
        for (Combination candidate : combinations)
            if (!candidate.text.isEmpty() && !values.contains(candidate.text)) values.add(candidate.text);
        return values;
    }

    private static final class Combination {
        final String text; final int rank;
        Combination(String text, int rank) { this.text = text; this.rank = rank; }
    }

    private static List<String> convert(Context context, List<String> values) {
        boolean traditional = "traditional".equals(context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("output_script", "simplified"));
        ArrayList<String> result = new ArrayList<>();
        for (String value : values) {
            String text = traditional ? HanConverter.get(context).toTraditional(value) : HanConverter.get(context).toSimplified(value);
            if (!text.isEmpty() && !result.contains(text)) result.add(text);
        }
        return result;
    }

    @Override public void close() { extended.close(); common.close(); }
}
