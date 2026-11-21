package com.example.offlineime;

import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Manages composing buffer, cursor position, pinyin path locks, shuangpin, and touch correction state. */
final class ComposingSessionController {
    final StringBuilder buffer = new StringBuilder();
    final PinyinPathEditor pathEditor = new PinyinPathEditor();
    final ShuangpinInput shuangpin = new ShuangpinInput();
    final TouchCorrectionModel touchCorrection = new TouchCorrectionModel();

    int cursor;
    boolean hostHasComposingText;
    String lockedPinyinPath = "";
    TouchCorrectionModel.Sample pendingTouch;

    private String segmentsCacheKey = "";
    private List<PinyinPathEditor.Segment> segmentsCache = Collections.emptyList();

    String raw() {
        return buffer.toString();
    }

    int length() {
        return buffer.length();
    }

    boolean isEmpty() {
        return buffer.length() == 0;
    }

    int clampedCursor() {
        return Math.min(buffer.length(), cursor);
    }

    void clearSegmentsCache() {
        segmentsCacheKey = "";
    }

    void clearInputOnly() {
        buffer.setLength(0);
        touchCorrection.clear();
        shuangpin.clear();
        lockedPinyinPath = "";
    }

    void clearAll() {
        buffer.setLength(0);
        touchCorrection.clear();
        shuangpin.clear();
        pathEditor.clear();
        cursor = 0;
        lockedPinyinPath = "";
    }

    void resetForSchemeChange(InputConnection connection) {
        if (connection != null && hostHasComposingText) {
            connection.setComposingText("", 1);
            connection.finishComposingText();
        }
        hostHasComposingText = false;
        clearAll();
    }

    void syncHostComposing(InputConnection connection, boolean suppressHostComposing) {
        String text = buffer.toString();
        if (connection == null) return;
        connection.beginBatchEdit();
        try {
            if (suppressHostComposing || text.isEmpty()) {
                if (hostHasComposingText) connection.commitText("", 1);
                connection.finishComposingText();
                hostHasComposingText = false;
            } else {
                connection.setComposingText(text, 1);
                hostHasComposingText = true;
            }
        } finally {
            connection.endBatchEdit();
        }
    }

    List<List<String>> pinyinPaths(String input, boolean shuangpinMode, boolean nineKey,
                                   PinyinDictionary dictionary) {
        if (shuangpinMode) {
            List<List<String>> paths = new ArrayList<>();
            for (ShuangpinInput.Path path : shuangpin.paths(input)) paths.add(path.readings);
            return PinyinDisplayOrder.paths(paths);
        }
        if (dictionary == null) return Collections.emptyList();
        return PinyinDisplayOrder.paths(dictionary.paths(input, nineKey));
    }

    PinyinPathEditor.Query pathQuery(OfflineImeService.Mode mode, boolean shuangpinMode) {
        if (shuangpinMode) {
            shuangpin.sync(buffer.toString());
            return null;
        }
        if (mode != OfflineImeService.Mode.PINYIN && mode != OfflineImeService.Mode.NINE) {
            pathEditor.clear();
            return null;
        }
        boolean nineKey = mode == OfflineImeService.Mode.NINE;
        pathEditor.sync(buffer.toString(), nineKey);
        return pathEditor.hasLocks() || buffer.indexOf("'") >= 0
                ? pathEditor.query(buffer.toString(), nineKey) : null;
    }

    List<PinyinPathEditor.Segment> pinyinSegments(OfflineImeService.Mode mode, boolean shuangpinMode,
                                                  boolean sensitive, CandidateSession candidateSession,
                                                  String currentCandidateKey, PinyinDictionary dictionary) {
        if (shuangpinMode && !sensitive) {
            return shuangpin.segments(buffer.toString(),
                    candidateSession.candidateResults.isEmpty() ? "" : candidateSession.candidateResults.get(0).pronunciation);
        }
        if (sensitive || mode != OfflineImeService.Mode.NINE && mode != OfflineImeService.Mode.PINYIN) {
            return Collections.emptyList();
        }
        PinyinPathEditor.Query constraint = pathQuery(mode, false);
        String reading = candidateSession.candidateResults.isEmpty() ? "" : candidateSession.candidateResults.get(0).pronunciation;
        String key = mode + ":" + buffer + ':' + (constraint == null ? "" : constraint.key)
                + ':' + candidateSession.candidateResultKey + ':' + candidateSession.candidateCompleteKey + ':' + reading;
        if (key.equals(segmentsCacheKey)) return segmentsCache;
        boolean nineKey = mode == OfflineImeService.Mode.NINE;
        List<PinyinPathEditor.Segment> valuesForInput = pathEditor.segments(buffer.toString(), nineKey, fragment -> {
            List<List<String>> values = pinyinPaths(fragment, false, nineKey, dictionary);
            if (!fragment.equals(buffer.toString()) || !candidateSession.candidateResultKey.equals(currentCandidateKey)) {
                return values;
            }
            if (!candidateSession.candidateResults.isEmpty()) {
                PinyinDictionary.Entry entry = candidateSession.candidateResults.get(0);
                if (!entry.pronunciation.matches("[a-z]+(?:[ ']+[a-z]+)*")) return values;
                List<String> candidatePath = Arrays.asList(entry.pronunciation.split("[ ']+"));
                for (String syllable : candidatePath) {
                    if (!PinyinPathEditor.isCompleteReading(syllable)) return values;
                }
                List<List<String>> preferred = new ArrayList<>();
                preferred.add(candidatePath);
                preferred.addAll(values);
                return preferred;
            }
            return values;
        });
        segmentsCache = valuesForInput;
        segmentsCacheKey = key;
        return valuesForInput;
    }

    PinyinPathEditor.Segment focusedPinyinSegment(List<PinyinPathEditor.Segment> segments) {
        for (PinyinPathEditor.Segment segment : segments) {
            if (segment.start == pathEditor.focus()) return segment;
        }
        // Continue from the first unconfirmed syllable when input or its
        // segmentation changes. Explicit editing still takes precedence.
        for (PinyinPathEditor.Segment segment : segments) {
            if (!segment.locked) return segment;
        }
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    void advancePinyinSelection(int selectedStart, int selectedEnd) {
        int nextStart = selectedEnd;
        while (nextStart < buffer.length() && buffer.charAt(nextStart) == '\'') nextStart++;
        // Store the raw boundary so asynchronous candidate updates can resolve
        // the remaining syllables without reusing the selected one's focus.
        pathEditor.focus(nextStart < buffer.length() ? nextStart : selectedStart);
    }

    String activePinyinPath(boolean shuangpinMode, List<PinyinPathEditor.Segment> segments) {
        if (!shuangpinMode && !pathEditor.hasLocks()) return lockedPinyinPath.replace("'", "·");
        List<String> readings = new ArrayList<>();
        for (PinyinPathEditor.Segment segment : segments) readings.add(segment.reading);
        return String.join("·", readings);
    }

    String selectedPinyinPath(OfflineImeService.Mode mode, boolean shuangpinMode, boolean sensitive,
                              List<PinyinPathEditor.Segment> segments) {
        if (sensitive || buffer.length() == 0
                || mode != OfflineImeService.Mode.PINYIN && mode != OfflineImeService.Mode.NINE) {
            return "";
        }
        List<String> readings = new ArrayList<>();
        if (shuangpinMode) {
            for (PinyinPathEditor.Segment segment : segments) {
                if (segment.locked) readings.add(segment.reading);
            }
        } else {
            PinyinPathEditor.Query query = pathQuery(mode, false);
            if (query != null) {
                for (PinyinPathEditor.Part part : query.parts) {
                    if (part.locked) readings.add(part.text);
                }
            }
        }
        return String.join("·", readings);
    }

    String currentPinyinPath(OfflineImeService.Mode mode, boolean shuangpinMode, boolean sensitive,
                             List<PinyinPathEditor.Segment> segments) {
        if (sensitive || buffer.length() == 0
                || mode != OfflineImeService.Mode.PINYIN && mode != OfflineImeService.Mode.NINE) {
            return "";
        }
        boolean nineKey = mode == OfflineImeService.Mode.NINE;
        List<String> readings = new ArrayList<>();
        for (PinyinPathEditor.Segment segment : segments) {
            String reading = displayReading(segment, shuangpinMode, nineKey);
            if (!reading.isEmpty() && reading.matches("[a-z/]+")) readings.add(reading);
        }
        if (!readings.isEmpty()) return String.join("·", readings);
        // In 26-key mode the typed letters remain useful even before a valid
        // syllable has been resolved. Never present T9 digits as a reading.
        return mode == OfflineImeService.Mode.PINYIN ? buffer.toString().replace('\'', '·') : "";
    }

    String displayReading(PinyinPathEditor.Segment segment, boolean shuangpinMode, boolean nineKey) {
        if (shuangpinMode) return segment.reading;
        if (segment.prefix) {
            List<String> entered = PinyinPathEditor.enteredReadings(
                    buffer.substring(segment.start, segment.end), nineKey, segment.reading);
            if (!entered.isEmpty()) return String.join("/", entered);
        }
        return segment.reading.substring(0, Math.min(segment.typedLetters, segment.reading.length()));
    }

    boolean readingSelected(PinyinPathEditor.Segment segment, String reading, boolean shuangpinMode) {
        if (shuangpinMode) return segment != null && shuangpin.selected(segment.start, reading);
        return segment != null && segment.locked && (segment.prefix
                ? reading.startsWith(segment.reading) : reading.equals(segment.reading));
    }

    List<String> syllableChoices(PinyinPathEditor.Segment segment, boolean shuangpinMode,
                                 boolean nineKey, boolean sensitive, PinyinDictionary dictionary) {
        if (dictionary == null || segment == null || sensitive) return Collections.emptyList();
        String raw = buffer.substring(segment.start, segment.end);
        if (shuangpinMode) {
            List<String> choices = new ArrayList<>(ShuangpinInput.choices(raw));
            choices.sort(PinyinDisplayOrder.READINGS);
            return choices;
        }
        List<String> readings = dictionary.syllableChoices(raw, nineKey);
        String currentSpelling = nineKey ? PinyinDictionary.toT9(segment.reading) : segment.reading;
        if (segment.locked && currentSpelling.equals(raw) && !readings.contains(segment.reading)) {
            readings.add(segment.reading);
        }
        // Stable longest-first alphabetical display; selection is conveyed by a checkmark.
        readings.sort(PinyinDisplayOrder.READINGS);
        return readings;
    }

    List<PinyinPathEditor.Segment> ninePinyinChoiceSegments(PinyinPathEditor.Segment focus,
                                                            boolean shuangpinMode,
                                                            PinyinDictionary dictionary) {
        return pathEditor.alternativeSegments(buffer.toString(), true, focus,
                input -> pinyinPaths(input, shuangpinMode, true, dictionary));
    }

    boolean isPathLocked(OfflineImeService.Mode mode, boolean shuangpinMode) {
        if (shuangpinMode) return !shuangpin.signature(buffer.toString()).isEmpty();
        pathQuery(mode, false);
        return pathEditor.hasLocks();
    }

    boolean insertPinyin(String key) {
        String value = normalizePinyinKey(key);
        if ("'".equals(key)) value = "'";
        if (buffer.length() >= 192 || value.isEmpty()) return false;
        cursor = Math.max(0, Math.min(buffer.length(), cursor));
        if (buffer.length() == 0) cursor = 0;
        if ("'".equals(value) && (cursor == 0 || buffer.charAt(cursor - 1) == '\'')) return false;
        touchCorrection.insert(buffer.toString(), cursor, value, pendingTouch);
        pathEditor.replace(buffer.toString(), cursor, cursor, value, false);
        buffer.insert(cursor, value);
        cursor += value.length();
        lockedPinyinPath = "";
        return true;
    }

    boolean insertPinyinSeparator(OfflineImeService.Mode mode, boolean shuangpinMode) {
        if (buffer.length() == 0 || buffer.length() >= 192 || shuangpinMode) return false;
        int at = Math.max(0, Math.min(buffer.length(), cursor));
        if (at == 0 || buffer.charAt(at - 1) == '\''
                || at < buffer.length() && buffer.charAt(at) == '\'') {
            return false;
        }
        if (mode == OfflineImeService.Mode.PINYIN) return insertPinyin("'");
        pathEditor.replace(buffer.toString(), at, at, "'", true);
        buffer.insert(at, '\'');
        cursor = at + 1;
        lockedPinyinPath = "";
        return true;
    }

    boolean insertNineDigit(String key) {
        if (buffer.length() >= 192) return false;
        lockedPinyinPath = "";
        cursor = Math.max(0, Math.min(buffer.length(), cursor));
        pathEditor.replace(buffer.toString(), cursor, cursor, key, true);
        buffer.insert(cursor, key);
        cursor += key.length();
        return true;
    }

    boolean deleteBackward(OfflineImeService.Mode mode) {
        int at = mode == OfflineImeService.Mode.PINYIN || mode == OfflineImeService.Mode.NINE
                ? Math.min(buffer.length(), cursor) : buffer.length();
        if (at <= 0) return false;
        int start = GraphemeUtils.previousClusterStart(buffer, at);
        pathEditor.replace(buffer.toString(), start, at, "", mode == OfflineImeService.Mode.NINE);
        touchCorrection.delete(buffer.toString(), start, at);
        buffer.delete(start, at);
        cursor = start;
        lockedPinyinPath = "";
        return true;
    }

    void deleteSegment(PinyinPathEditor.Segment segment, boolean nineKey) {
        pathEditor.replace(buffer.toString(), segment.start, segment.end, "", nineKey);
        touchCorrection.delete(buffer.toString(), segment.start, segment.end);
        buffer.delete(segment.start, segment.end);
        cursor = segment.start;
        lockedPinyinPath = "";
    }

    boolean deleteSyllable(OfflineImeService.Mode mode, boolean shuangpinMode,
                           List<PinyinPathEditor.Segment> segments, PinyinDictionary dictionary) {
        if (buffer.length() == 0) return false;
        int end = Math.min(buffer.length(), cursor);
        if (end == 0) return false;
        int start = -1;
        for (PinyinPathEditor.Segment segment : segments) {
            if (segment.start < end && segment.end >= end) {
                start = segment.start;
                break;
            }
        }
        if (start < 0) start = buffer.lastIndexOf("'", Math.max(0, end - 2));
        boolean nineKey = mode == OfflineImeService.Mode.NINE;
        if (start < 0) {
            List<List<String>> paths = pinyinPaths(buffer.substring(0, end), shuangpinMode, nineKey, dictionary);
            start = paths.isEmpty() ? end - 1 : end - paths.get(0).get(paths.get(0).size() - 1).length();
        }
        start = Math.max(0, start);
        pathEditor.replace(buffer.toString(), start, end, "", nineKey);
        touchCorrection.delete(buffer.toString(), start, end);
        buffer.delete(start, end);
        cursor = start;
        lockedPinyinPath = "";
        return true;
    }

    void consumePrefix(int consumed, OfflineImeService.Mode mode, boolean shuangpinMode) {
        int end = consumed;
        while (end < buffer.length() && buffer.charAt(end) == '\'') end++;
        pathEditor.replace(buffer.toString(), 0, end, "", mode == OfflineImeService.Mode.NINE);
        touchCorrection.delete(buffer.toString(), 0, end);
        if (shuangpinMode) shuangpin.consumePrefix(buffer.toString(), end);
        buffer.delete(0, end);
        if (!shuangpinMode) shuangpin.clear();
        cursor = buffer.length();
        lockedPinyinPath = "";
    }

    static String normalizePinyinKey(String input) {
        if (input == null) return "";
        return input.toLowerCase(Locale.ROOT).replace("u:", "v").replace('ü', 'v')
                .replace('’', '\'').replaceAll("[^a-z']", "").replaceAll("'{2,}", "'")
                .replaceAll("^'", "");
    }
}
