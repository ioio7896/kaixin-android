package com.example.offlineime;

import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Manages cursor movement, selection mode, batched delete runs, and editor undo/redo state. */
final class SelectionEditController {
    private static final int CARET_CONTEXT_LENGTH = 128;

    private static final class SelectionTargets {
        private final ArrayDeque<Long> pending = new ArrayDeque<>();
        private long confirmed;

        SelectionTargets(int start, int end) {
            confirmed = pair(start, end);
        }

        void expect(int start, int end) {
            if (pending.size() == 32) pending.removeFirst();
            pending.addLast(pair(start, end));
        }

        boolean accept(int start, int end) {
            long target = pair(start, end);
            if (!pending.contains(target)) return target == confirmed;
            while (pending.removeFirst() != target) {
                // Drain superseded intermediate selection targets.
            }
            confirmed = target;
            return true;
        }

        private static long pair(int start, int end) {
            return ((long) start << 32) | (end & 0xffffffffL);
        }
    }

    private static final class DeleteRun {
        boolean editorStarted;
        boolean selectionDeleted;
        EditorUndo.Snapshot before;
        EditorUndo.Snapshot latest;
        SelectionTargets selection;
    }

    private static final class CaretWindow {
        final String text;
        final int offset;
        final boolean reachesEnd;
        final SelectionTargets selection;
        int start;
        int end;

        CaretWindow(String text, int offset, int start, int end, boolean reachesEnd) {
            this.text = text;
            this.offset = offset;
            this.start = start;
            this.end = end;
            this.reachesEnd = reachesEnd;
            this.selection = new SelectionTargets(start, end);
        }

        boolean covers(int caret, int delta) {
            int margin = Math.abs(delta) * 4;
            return (offset == 0 || caret - offset > margin)
                    && (reachesEnd || offset + text.length() - caret > margin);
        }
    }

    private final EditorUndo editorUndo = new EditorUndo();
    private boolean selectionMode;
    private DeleteRun lastDeleteRun;
    private DeleteRun dispatchingDeleteRun;
    private CaretWindow caretWindow;
    private boolean undoCandidateCommit;
    private int selectionAnchor = -1;
    private int selectionExtent = -1;

    boolean isSelectionMode() {
        return selectionMode;
    }

    boolean isUndoCandidateCommit() {
        return undoCandidateCommit;
    }

    void setUndoCandidateCommit(boolean value) {
        undoCandidateCommit = value;
    }

    void clearTransientState() {
        lastDeleteRun = null;
        caretWindow = null;
    }

    void clearAll() {
        editorUndo.clear();
        lastDeleteRun = null;
        caretWindow = null;
    }

    void clearUndo() {
        editorUndo.clear();
    }

    void resetSelectionMode() {
        selectionMode = false;
        selectionAnchor = -1;
        selectionExtent = -1;
    }

    void onKeyBegin(String key) {
        if (dispatchingDeleteRun == null) lastDeleteRun = null;
        if (!key.startsWith("移动光标:") && !"←".equals(key) && !"→".equals(key) && !"选择".equals(key)) {
            caretWindow = null;
        }
    }

    void onUpdateSelection(int newSelStart, int newSelEnd) {
        if (lastDeleteRun != null && lastDeleteRun.latest != null
                && !lastDeleteRun.selection.accept(newSelStart, newSelEnd)) {
            lastDeleteRun = null;
        }
        if (caretWindow != null && !caretWindow.selection.accept(newSelStart, newSelEnd)) {
            caretWindow = null;
            if (selectionMode) {
                selectionAnchor = -1;
                selectionExtent = -1;
            }
        }
    }

    KeyButton.DeleteAction beginDeleteRun(LongSupplier generationProvider, Runnable onBackspaceKey) {
        DeleteRun run = new DeleteRun();
        long generation = generationProvider.getAsLong();
        return first -> {
            if (generationProvider.getAsLong() != generation || (!first && lastDeleteRun != run)) return;
            lastDeleteRun = run;
            dispatchingDeleteRun = run;
            try {
                onBackspaceKey.run();
            } finally {
                dispatchingDeleteRun = null;
            }
        };
    }

    void deleteFromEditor(InputConnection connection, boolean mayRememberEdit,
                          boolean hasEditorSelection, long generation,
                          Runnable onHostComposingCleared, Runnable onAfterEditorDelete) {
        if (connection == null) return;
        DeleteRun run = dispatchingDeleteRun == null ? new DeleteRun() : dispatchingDeleteRun;
        boolean first = !run.editorStarted;
        if (first) connection.beginBatchEdit();
        try {
            if (first) {
                run.editorStarted = true;
                // Some hosts keep a composing span after the local buffer is cleared.
                connection.finishComposingText();
                onHostComposingCleared.run();
                run.before = mayRememberEdit ? EditorUndo.read(connection) : null;
                run.latest = run.before;
                if (run.before != null) {
                    run.selection = new SelectionTargets(
                            run.before.offset + run.before.start, run.before.offset + run.before.end);
                }
            }
            EditorUndo.Snapshot current = mayRememberEdit ? run.latest : null;
            if (current != null && current.start != current.end) {
                if (connection.commitText("", 1)) {
                    recordDelete(run, current.replace(
                            Math.min(current.start, current.end), Math.max(current.start, current.end), ""),
                            generation);
                }
                resetSelectionMode();
                return;
            }
            if (current == null && (first || (!run.selectionDeleted && hasEditorSelection))) {
                CharSequence selected = connection.getSelectedText(0);
                if (selected != null && selected.length() > 0 && connection.commitText("", 1)) {
                    run.selectionDeleted = true;
                    resetSelectionMode();
                    return;
                }
            }
            if (current != null && current.start > 0) {
                CharSequence prefix = current.text.subSequence(0, current.start);
                int start = previousDeleteStart(prefix, false);
                if (connection.deleteSurroundingText(current.start - start, 0)) {
                    recordDelete(run, current.replace(start, current.start, ""), generation);
                } else {
                    run.latest = null;
                    deleteBeforeCursor(connection, false);
                }
            } else if (current == null || current.offset != 0) {
                run.latest = null;
                deleteBeforeCursor(connection, false);
            }
        } finally {
            if (first) connection.endBatchEdit();
        }
        onAfterEditorDelete.run();
    }

    void deleteWordFromEditor(InputConnection connection, boolean mayRememberEdit, long generation,
                              Runnable onHostComposingCleared, Runnable onAfterEditorDelete) {
        if (connection == null) return;
        connection.beginBatchEdit();
        try {
            connection.finishComposingText();
            onHostComposingCleared.run();
            CharSequence selected = connection.getSelectedText(0);
            if (selected != null && selected.length() > 0) {
                // Surrounding text excludes the selection. Replace it instead.
                EditorUndo.Snapshot snapshot = mayRememberEdit ? EditorUndo.read(connection) : null;
                connection.commitText("", 1);
                if (snapshot != null) editorUndo.record(snapshot, connection, generation);
                resetSelectionMode();
                return;
            }
            EditorUndo.Snapshot snapshot = mayRememberEdit ? EditorUndo.read(connection) : null;
            deleteBeforeCursor(connection, true);
            if (snapshot != null) editorUndo.record(snapshot, connection, generation);
            onAfterEditorDelete.run();
        } finally {
            connection.endBatchEdit();
        }
    }

    void recordCandidateCommitUndo(EditorUndo.Snapshot before, InputConnection connection,
                                   String committedWord, String replacedComposition, long generation) {
        EditorUndo.Snapshot after = before.afterCommit(committedWord, replacedComposition);
        if (after != null) editorUndo.record(before, after, generation);
        else editorUndo.record(before, connection, generation);
    }

    void recordCommitUndo(EditorUndo.Snapshot before, InputConnection connection, long generation) {
        editorUndo.record(before, connection, generation);
    }

    boolean applyUndo(InputConnection connection, long generation, boolean redo) {
        return editorUndo.apply(connection, generation, redo);
    }

    void moveCaretBy(InputConnection connection, int delta, boolean mayRememberEdit,
                     int sessionSelStart, int sessionSelEnd) {
        if (connection == null || delta == 0) return;
        int clamped = Math.max(-20, Math.min(20, delta));
        int start = caretWindow == null ? sessionSelStart : caretWindow.start;
        int end = caretWindow == null ? sessionSelEnd : caretWindow.end;
        int caret = selectionMode && selectionExtent >= 0 ? selectionExtent
                : clamped < 0 ? Math.min(start, end) : Math.max(start, end);
        if (caretWindow == null || !caretWindow.covers(caret, clamped)) {
            caretWindow = readCaretWindow(connection, start, end, mayRememberEdit);
        }
        if (caretWindow == null) {
            int code = clamped < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            for (int i = 0; i < Math.abs(clamped); i++) {
                long now = SystemClock.uptimeMillis();
                int meta = selectionMode ? KeyEvent.META_SHIFT_ON : 0;
                connection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta));
                connection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta));
            }
            return;
        }
        int position = GraphemeUtils.move(caretWindow.text, caret - caretWindow.offset, clamped) + caretWindow.offset;
        if (selectionMode) {
            if (selectionAnchor < 0) selectionAnchor = caret == start ? end : start;
            if (setCaretSelection(connection, Math.min(selectionAnchor, position), Math.max(selectionAnchor, position))) {
                selectionExtent = position;
            }
        } else {
            setCaretSelection(connection, position, position);
        }
    }

    Boolean toggleSelectionMode(InputConnection connection, int sessionSelStart, int sessionSelEnd) {
        if (connection == null) return null;
        int start = caretWindow == null ? sessionSelStart : caretWindow.start;
        int end = caretWindow == null ? sessionSelEnd : caretWindow.end;
        if (start < 0 || end < 0) return null;
        if (selectionMode) {
            selectionMode = false;
            int caret = Math.max(start, end);
            setCaretSelection(connection, caret, caret);
            selectionAnchor = -1;
            selectionExtent = -1;
        } else {
            selectionMode = true;
            selectionAnchor = start;
            selectionExtent = end;
        }
        return selectionMode;
    }

    void sendKey(InputConnection connection, int code) {
        if (connection != null) {
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }
    }

    void modifiedKey(InputConnection connection, int code) {
        if (connection != null) {
            long now = System.currentTimeMillis();
            connection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON));
            connection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, KeyEvent.META_CTRL_ON));
        }
    }

    void contextAction(InputConnection connection, int actionId, int fallbackKey,
                       Runnable onHostComposingCleared) {
        if (connection == null) return;
        connection.finishComposingText();
        onHostComposingCleared.run();
        if (!connection.performContextMenuAction(actionId)) modifiedKey(connection, fallbackKey);
        if (actionId == android.R.id.cut || actionId == android.R.id.paste
                || actionId == android.R.id.selectAll) {
            resetSelectionMode();
        }
    }

    private void recordDelete(DeleteRun run, EditorUndo.Snapshot result, long generation) {
        run.latest = result;
        run.selection.expect(result.offset + result.start, result.offset + result.end);
        editorUndo.record(run.before, result, generation);
    }

    private void deleteBeforeCursor(InputConnection connection, boolean word) {
        CharSequence before = connection.getTextBeforeCursor(256, 0);
        if (before != null && before.length() > 0) {
            connection.deleteSurroundingText(before.length() - previousDeleteStart(before, word), 0);
        } else if (!connection.deleteSurroundingTextInCodePoints(1, 0)) {
            sendKey(connection, KeyEvent.KEYCODE_DEL);
        }
    }

    private static int previousDeleteStart(CharSequence before, boolean word) {
        int tokenLength = WeChatEmojiCatalog.trailingTokenLength(before);
        return tokenLength > 0 ? before.length() - tokenLength
                : word ? GraphemeUtils.previousWordStart(before)
                : GraphemeUtils.previousClusterStart(before, before.length());
    }

    private CaretWindow readCaretWindow(InputConnection connection, int start, int end,
                                        boolean mayRememberEdit) {
        if (!mayRememberEdit || start < 0 || end < 0 || Math.abs(end - start) > CARET_CONTEXT_LENGTH) {
            return null;
        }
        int from = Math.min(start, end);
        int to = Math.max(start, end);
        if (Build.VERSION.SDK_INT >= 31) {
            SurroundingText context = connection.getSurroundingText(
                    CARET_CONTEXT_LENGTH, CARET_CONTEXT_LENGTH, 0);
            if (context == null) return null;
            String text = context.getText().toString();
            int left = Math.min(context.getSelectionStart(), context.getSelectionEnd());
            int right = Math.max(context.getSelectionStart(), context.getSelectionEnd());
            int offset = context.getOffset() < 0 ? from - left : context.getOffset();
            if (left < 0 || right > text.length() || offset < 0
                    || offset + left != from || offset + right != to) {
                return null;
            }
            return new CaretWindow(text, offset, start, end, text.length() - right < CARET_CONTEXT_LENGTH);
        }
        CharSequence before = connection.getTextBeforeCursor(CARET_CONTEXT_LENGTH, 0);
        CharSequence after = connection.getTextAfterCursor(CARET_CONTEXT_LENGTH, 0);
        CharSequence selected = from == to ? "" : connection.getSelectedText(0);
        if (before == null || after == null || selected == null || selected.length() != to - from
                || before.length() > from) {
            return null;
        }
        return new CaretWindow(before.toString() + selected + after, from - before.length(), start, end,
                after.length() < CARET_CONTEXT_LENGTH);
    }

    private boolean setCaretSelection(InputConnection connection, int start, int end) {
        if (caretWindow != null && caretWindow.start == start && caretWindow.end == end) return true;
        if (!connection.setSelection(start, end)) {
            caretWindow = null;
            return false;
        }
        if (caretWindow != null) {
            caretWindow.start = start;
            caretWindow.end = end;
            caretWindow.selection.expect(start, end);
        }
        return true;
    }
}
