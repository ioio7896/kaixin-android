package com.example.offlineime;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Offline candidate fusion: neural top-k first, templates and stroke index as supplements. */
final class OfflineHandwritingRecognizer {
    static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final int SAMPLE_COUNT = 16;
    static final int MAX_STROKES = 64;
    private static final double MAX_SCORE = 0.48;
    private static final double ALTERNATIVE_MARGIN = 0.12;
    private static final List<Template> TEMPLATES = createTemplates();

    private OfflineHandwritingRecognizer() { }

    static List<String> recognize(Context context, List<? extends List<Point>> strokes) {
        return recognize(context, strokes, Collections.emptyList());
    }

    static List<String> recognize(Context context, List<? extends List<Point>> strokes,
                                  List<String> neuralCandidates) {
        if (strokes == null || strokes.isEmpty() || strokes.size() > MAX_STROKES)
            return Collections.emptyList();
        ArrayList<String> result = new ArrayList<>(20);
        boolean traditional = "traditional".equals(context.getSharedPreferences(
                "settings", Context.MODE_PRIVATE).getString("output_script", "simplified"));
        for (String value : neuralCandidates) addCandidate(context, result, value, traditional);
        for (String value : recognize(strokes)) addCandidate(context, result, value, traditional);
        StringBuilder sequence = new StringBuilder(strokes.size());
        for (List<Point> stroke : strokes) {
            if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
            sequence.append(classifyStroke(resample(stroke, SAMPLE_COUNT)));
        }
        for (String value : StrokeInputDictionary.get(context)
                .suggestCharacters(context, sequence.toString(), 16)) {
            if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
            addCandidate(context, result, value, traditional);
            if (result.size() >= 20) break;
        }
        return result;
    }

    private static void addCandidate(Context context, List<String> result, String value, boolean traditional) {
        if (result.size() >= 20) return;
        String displayed = traditional ? HanConverter.get(context).toTraditional(value)
                : HanConverter.get(context).toSimplified(value);
        if (!result.contains(displayed)) result.add(displayed);
    }

    static List<String> recognize(List<? extends List<Point>> strokes) {
        if (strokes == null || strokes.isEmpty() || strokes.size() > MAX_STROKES) {
            return Collections.emptyList();
        }
        List<List<Point>> normalized = normalize(strokes);
        if (normalized == null) return Collections.emptyList();

        ArrayList<ScoredTemplate> matches = new ArrayList<>();
        for (Template template : TEMPLATES) {
            if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
            if (template.strokes.size() != normalized.size()) continue;
            matches.add(new ScoredTemplate(template.value, score(normalized, template.strokes)));
        }
        if (matches.isEmpty()) return Collections.emptyList();
        matches.sort(Comparator.comparingDouble(match -> match.score));

        double best = matches.get(0).score;
        if (best > MAX_SCORE) return Collections.emptyList();
        ArrayList<String> result = new ArrayList<>();
        for (ScoredTemplate match : matches) {
            if (match.score > MAX_SCORE || match.score > best + ALTERNATIVE_MARGIN || result.size() == 5) break;
            if (!result.contains(match.value)) result.add(match.value);
        }
        return result;
    }

    /** Classify one trajectory as horizontal, vertical, left-fall, dot/right-fall, or turn. */
    private static char classifyStroke(List<Point> stroke) {
        if (stroke == null || stroke.size() < 2) return '4';
        Point first = stroke.get(0), last = stroke.get(stroke.size() - 1);
        float dx = last.x - first.x, dy = last.y - first.y;
        double pathLength = 0;
        double strongestTurn = 0;
        double previousAngle = Double.NaN;
        for (int i = 1; i < stroke.size(); i++) {
            Point before = stroke.get(i - 1), after = stroke.get(i);
            double segment = distance(before, after);
            if (segment < 0.5) continue;
            pathLength += segment;
            double angle = Math.atan2(after.y - before.y, after.x - before.x);
            if (!Double.isNaN(previousAngle)) {
                double change = Math.abs(angle - previousAngle);
                change = Math.min(change, Math.PI * 2 - change);
                strongestTurn = Math.max(strongestTurn, change);
            }
            previousAngle = angle;
        }
        double direct = Math.hypot(dx, dy);
        if (strongestTurn > 0.85 || pathLength > Math.max(8, direct * 1.35)) return '5';
        if (Math.abs(dx) > Math.abs(dy) * 1.45) return '1';
        if (Math.abs(dy) > Math.abs(dx) * 1.65) return '2';
        if (dx < 0 && dy > 0) return '3';
        if (dy >= 0) return '4';
        return '5';
    }

    private static List<List<Point>> normalize(List<? extends List<Point>> strokes) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        int pointCount = 0;
        for (List<Point> stroke : strokes) {
            if (stroke == null || stroke.isEmpty()) return null;
            for (Point point : stroke) {
                if (point == null || !Float.isFinite(point.x) || !Float.isFinite(point.y)) return null;
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
                pointCount++;
            }
        }
        if (pointCount == 0) return null;
        float width = maxX - minX, height = maxY - minY;
        float side = Math.max(Math.max(width, height), 1f);
        float xOffset = (100f - width * 100f / side) / 2f;
        float yOffset = (100f - height * 100f / side) / 2f;

        ArrayList<List<Point>> result = new ArrayList<>(strokes.size());
        for (List<Point> stroke : strokes) {
            ArrayList<Point> scaled = new ArrayList<>(stroke.size());
            for (Point point : stroke) {
                scaled.add(new Point(
                        xOffset + (point.x - minX) * 100f / side,
                        yOffset + (point.y - minY) * 100f / side));
            }
            result.add(resample(scaled, SAMPLE_COUNT));
        }
        return result;
    }

    private static List<Point> resample(List<Point> points, int count) {
        if (points.size() == 1) {
            ArrayList<Point> repeated = new ArrayList<>(count);
            for (int i = 0; i < count; i++) repeated.add(points.get(0));
            return repeated;
        }
        double total = 0;
        for (int i = 1; i < points.size(); i++) total += distance(points.get(i - 1), points.get(i));
        if (total < 0.001) {
            ArrayList<Point> repeated = new ArrayList<>(count);
            for (int i = 0; i < count; i++) repeated.add(points.get(0));
            return repeated;
        }

        ArrayList<Point> result = new ArrayList<>(count);
        int segment = 1;
        double traversed = 0;
        for (int sample = 0; sample < count; sample++) {
            double target = total * sample / (count - 1d);
            while (segment < points.size() - 1
                    && traversed + distance(points.get(segment - 1), points.get(segment)) < target) {
                traversed += distance(points.get(segment - 1), points.get(segment));
                segment++;
            }
            Point start = points.get(segment - 1), end = points.get(segment);
            double segmentLength = distance(start, end);
            float fraction = segmentLength < 0.001 ? 0f
                    : (float) Math.max(0d, Math.min(1d, (target - traversed) / segmentLength));
            result.add(new Point(start.x + (end.x - start.x) * fraction,
                    start.y + (end.y - start.y) * fraction));
        }
        return result;
    }

    private static double score(List<List<Point>> input, List<List<Point>> template) {
        double sum = 0;
        int count = 0;
        for (int stroke = 0; stroke < input.size(); stroke++) {
            List<Point> actual = input.get(stroke), expected = template.get(stroke);
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                sum += squaredDistance(actual.get(i), expected.get(i));
                count++;
            }
        }
        return Math.sqrt(sum / Math.max(1, count)) / 100d;
    }

    private static double distance(Point first, Point second) {
        return Math.sqrt(squaredDistance(first, second));
    }

    private static double squaredDistance(Point first, Point second) {
        double dx = first.x - second.x, dy = first.y - second.y;
        return dx * dx + dy * dy;
    }

    private static List<Template> createTemplates() {
        ArrayList<Template> values = new ArrayList<>();
        add(values, "一", line(10, 50, 90, 50));
        add(values, "丨", line(50, 10, 50, 90));
        add(values, "丿", line(82, 12, 18, 88));
        add(values, "丶", line(62, 16, 38, 72));
        add(values, "乙", path(75, 16, 28, 16, 25, 52, 73, 52, 72, 83));
        add(values, "十", line(12, 50, 88, 50), line(50, 12, 50, 88));
        add(values, "二", line(12, 35, 88, 35), line(12, 68, 88, 68));
        add(values, "人", line(50, 14, 18, 88), line(50, 14, 84, 88));
        add(values, "八", line(45, 38, 16, 82), line(55, 38, 84, 82));
        add(values, "大", line(12, 30, 88, 30), line(50, 30, 16, 88), line(50, 30, 84, 88));
        add(values, "小", line(50, 10, 50, 90), line(39, 48, 18, 78), line(61, 48, 82, 78));
        add(values, "工", line(12, 25, 88, 25), line(50, 25, 50, 80), line(12, 80, 88, 80));
        add(values, "土", line(12, 28, 88, 28), line(50, 28, 50, 72), line(12, 80, 88, 80));
        add(values, "干", line(12, 25, 88, 25), line(12, 50, 88, 50), line(50, 50, 50, 90));
        add(values, "口", line(20, 12, 20, 82), path(20, 12, 82, 12, 82, 82), line(20, 82, 82, 82));
        add(values, "日", line(20, 12, 20, 84), path(20, 12, 80, 12, 80, 84), line(20, 48, 80, 48), line(20, 84, 80, 84));
        add(values, "中", line(18, 10, 18, 85), path(18, 28, 82, 28, 82, 72), line(18, 72, 82, 72), line(50, 10, 50, 90));
        add(values, "木", line(12, 35, 88, 35), line(50, 12, 50, 88), line(50, 45, 18, 88), line(50, 45, 84, 88));
        return Collections.unmodifiableList(values);
    }

    @SafeVarargs
    private static void add(List<Template> target, String value, List<Point>... strokes) {
        List<List<Point>> normalized = normalize(java.util.Arrays.asList(strokes));
        if (normalized != null) target.add(new Template(value, Collections.unmodifiableList(normalized)));
    }

    private static List<Point> line(float x1, float y1, float x2, float y2) {
        ArrayList<Point> points = new ArrayList<>(2);
        points.add(new Point(x1, y1));
        points.add(new Point(x2, y2));
        return points;
    }

    private static List<Point> path(float... coordinates) {
        ArrayList<Point> points = new ArrayList<>(coordinates.length / 2);
        for (int i = 0; i + 1 < coordinates.length; i += 2) {
            points.add(new Point(coordinates[i], coordinates[i + 1]));
        }
        return points;
    }

    private static final class Template {
        final String value;
        final List<List<Point>> strokes;

        Template(String value, List<List<Point>> strokes) {
            this.value = value;
            this.strokes = strokes;
        }
    }

    private static final class ScoredTemplate {
        final String value;
        final double score;

        ScoredTemplate(String value, double score) {
            this.value = value;
            this.score = score;
        }
    }
}
