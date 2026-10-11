package com.example.offlineime;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Both bundled models live and close on the same worker; all results stay in memory. */
final class HandwritingEngine implements AutoCloseable {
    private final ExtendedHandwritingModel extended = new ExtendedHandwritingModel();
    private final NeuralHandwritingModel common = new NeuralHandwritingModel();

    static final class Result {
        final List<String> candidates;
        final String status;
        Result(List<String> candidates, String status) { this.candidates = candidates; this.status = status; }
    }

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
                : prediction.confidence < .50f ? "连写结果不够确定 · 点预览中的字可改选"
                : "连写识别完成 · 点预览改字，点候选整段上屏");
    }

    private Result single(Context context, List<List<OfflineHandwritingRecognizer.Point>> strokes) {
        NeuralHandwritingModel.Prediction wide = extended.recognize(context, strokes, true);
        if (Thread.currentThread().isInterrupted()) return new Result(Collections.emptyList(), "");
        NeuralHandwritingModel.Prediction narrow = common.recognize(context, strokes);
        if (Thread.currentThread().isInterrupted()) return new Result(Collections.emptyList(), "");
        ArrayList<String> modelCandidates = new ArrayList<>(wide.candidates.subList(0, Math.min(8, wide.candidates.size())));
        for (int i = 0; i < Math.min(4, narrow.candidates.size()); i++) {
            String value = narrow.candidates.get(i);
            if (!modelCandidates.contains(value)) modelCandidates.add(value);
        }
        List<String> candidates = OfflineHandwritingRecognizer.recognize(context, strokes, modelCandidates);
        return new Result(candidates, !wide.modelAvailable
                ? "扩展模型暂不可用 · 常用字模型和笔画后备识别"
                : "扩展离线模型 · 繁体和生僻字候选可左右滑动");
    }

    private Result separated(Context context, List<List<OfflineHandwritingRecognizer.Point>> strokes,
                             List<Integer> boundaries) {
        ArrayList<List<String>> parts = new ArrayList<>();
        int start = 0;
        ArrayList<Integer> ends = new ArrayList<>(boundaries);
        if (ends.isEmpty() || ends.get(ends.size() - 1) < strokes.size()) ends.add(strokes.size());
        for (int end : ends) {
            if (end <= start || end > strokes.size()) continue;
            if (parts.size() >= CtcHandwritingDecoder.MAX_CHARACTERS || Thread.currentThread().isInterrupted())
                return new Result(Collections.emptyList(), "最多连续书写 12 个字");
            Result result = single(context, new ArrayList<>(strokes.subList(start, end)));
            if (result.candidates.isEmpty()) return new Result(Collections.emptyList(), "第 " + (parts.size() + 1) + " 个字尚未识别，请补笔或撤销");
            parts.add(result.candidates);
            start = end;
        }
        ArrayList<Combination> combinations = new ArrayList<>(); combinations.add(new Combination("", 0));
        for (List<String> part : parts) {
            ArrayList<Combination> next = new ArrayList<>();
            for (int rank = 0; rank < Math.min(4, part.size()); rank++) {
                for (Combination prefix : combinations)
                    next.add(new Combination(prefix.text + part.get(rank), prefix.rank + rank));
            }
            next.sort((a, b) -> Integer.compare(a.rank, b.rank));
            combinations = new ArrayList<>(next.subList(0, Math.min(12, next.size())));
        }
        ArrayList<String> values = new ArrayList<>();
        for (Combination candidate : combinations) if (!values.contains(candidate.text)) values.add(candidate.text);
        return new Result(values, "已按字界识别 " + parts.size() + " 个字 · 点预览可改字");
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
