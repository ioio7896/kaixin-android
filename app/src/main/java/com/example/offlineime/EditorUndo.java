package com.example.offlineime;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

/** One bounded edit in memory, validated against editor text and session before use. */
final class EditorUndo {
    static final class Snapshot {
        final String text;
        final int offset, start, end;
        Snapshot(ExtractedText value) {
            text = value.text.toString(); offset = value.startOffset;
            start = value.selectionStart; end = value.selectionEnd;
        }
    }
    private Snapshot before, after;
    private long session;
    private boolean undone;
    void clear() { before = after = null; undone = false; }
    static Snapshot read(InputConnection connection) {
        if (connection == null) return null;
        ExtractedTextRequest request = new ExtractedTextRequest();
        request.hintMaxChars = 512; request.hintMaxLines = 8;
        ExtractedText value = connection.getExtractedText(request, 0);
        if (value == null || value.text == null || value.text.length() > 4096
                || value.partialStartOffset >= 0 || value.selectionStart < 0
                || value.selectionEnd < 0 || value.selectionStart > value.text.length()
                || value.selectionEnd > value.text.length()) return null;
        return new Snapshot(value);
    }
    void record(Snapshot previous, InputConnection connection, long currentSession) {
        Snapshot next = read(connection);
        clear();
        if (previous == null || next == null || previous.offset != next.offset
                || previous.text.equals(next.text)) return;
        before = previous; after = next; session = currentSession;
    }
    boolean apply(InputConnection connection, long currentSession, boolean redo) {
        if (before == null || after == null || session != currentSession || redo != undone) return false;
        Snapshot expected = undone ? before : after, target = undone ? after : before;
        Snapshot actual = read(connection);
        if (actual == null || actual.offset != expected.offset || !actual.text.equals(expected.text)
                || actual.start != expected.start || actual.end != expected.end) { clear(); return false; }
        int prefix = 0;
        while (prefix < expected.text.length() && prefix < target.text.length()
                && expected.text.charAt(prefix) == target.text.charAt(prefix)) prefix++;
        if (prefix > 0) prefix = GraphemeUtils.previousClusterStart(expected.text, prefix);
        connection.beginBatchEdit();
        try {
            connection.finishComposingText();
            if (!connection.setSelection(expected.offset + prefix, expected.offset + expected.text.length())) return false;
            if (!connection.commitText(target.text.substring(prefix), 1)) return false;
            connection.setSelection(target.offset + target.start, target.offset + target.end);
            undone = !undone;
            return true;
        } finally { connection.endBatchEdit(); }
    }
}
