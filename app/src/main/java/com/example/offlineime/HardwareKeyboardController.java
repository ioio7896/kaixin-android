package com.example.offlineime;

import android.view.KeyEvent;

/** Translates physical (Bluetooth/magnetic) keyboard events into IME actions. */
final class HardwareKeyboardController {
    interface Host {
        boolean isEnabled();
        boolean isChineseMode();
        boolean isComposing();
        boolean isPasswordOrUri();
        void onKey(String key);
        void toggleChineseEnglish();
        void clearComposing();
        void commitText(String text);
        void pageCandidates(int delta);
        boolean selectCandidateOnPage(int indexInPage);
        void ensureCandidateBarVisible();
    }

    private final Host host;
    private boolean shiftDownAlone;
    private int shiftKeyCode;

    HardwareKeyboardController(Host host) {
        this.host = host;
    }

    boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!host.isEnabled() || host.isPasswordOrUri()) {
            shiftDownAlone = false;
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (event.getRepeatCount() == 0 && !event.isCtrlPressed() && !event.isAltPressed() && !event.isMetaPressed()) {
                shiftDownAlone = true;
                shiftKeyCode = keyCode;
            }
            return false;
        }
        shiftDownAlone = false;

        // Ctrl + Space toggles Chinese/English mode cleanly.
        if (keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed()
                && !event.isAltPressed() && !event.isMetaPressed()) {
            if (event.getRepeatCount() == 0) {
                host.toggleChineseEnglish();
                host.ensureCandidateBarVisible();
            }
            return true;
        }

        // Let system/app handle Ctrl, Alt, Meta shortcuts.
        if (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) {
            return false;
        }

        if (!host.isChineseMode()) {
            return false;
        }

        boolean composing = host.isComposing();

        // Composing navigation & selection controls.
        if (composing) {
            if (keyCode == KeyEvent.KEYCODE_SPACE) {
                host.onKey("空格");
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                host.onKey(event.isShiftPressed() ? "原文上屏" : "回车");
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                host.onKey("⌫");
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                host.clearComposing();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MINUS || keyCode == KeyEvent.KEYCODE_LEFT_BRACKET
                    || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                host.pageCandidates(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET
                    || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                host.pageCandidates(1);
                return true;
            }
            if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9 && !event.isShiftPressed()) {
                int index = keyCode - KeyEvent.KEYCODE_1;
                if (host.selectCandidateOnPage(index)) return true;
            }
            if (keyCode == KeyEvent.KEYCODE_APOSTROPHE && !event.isShiftPressed()) {
                host.onKey("分隔");
                return true;
            }
        }

        // Letters A-Z -> feed into Pinyin / Shuangpin engine.
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            if (event.isShiftPressed() && !composing) {
                // Allow Shift+Letter to type uppercase English directly when not composing.
                return false;
            }
            char letter = (char) ('a' + (keyCode - KeyEvent.KEYCODE_A));
            host.ensureCandidateBarVisible();
            host.onKey(String.valueOf(letter));
            return true;
        }

        // Chinese punctuation mapping.
        String punctuation = chinesePunctuation(keyCode, event.isShiftPressed());
        if (punctuation != null) {
            if (composing) host.onKey("空格");
            host.commitText(punctuation);
            return true;
        }

        return false;
    }

    boolean onKeyUp(int keyCode, KeyEvent event) {
        if (shiftDownAlone && keyCode == shiftKeyCode) {
            shiftDownAlone = false;
            if (host.isEnabled() && !host.isPasswordOrUri()) {
                if (host.isComposing()) host.onKey("原文上屏");
                host.toggleChineseEnglish();
                host.ensureCandidateBarVisible();
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            shiftDownAlone = false;
        }
        return false;
    }

    void reset() {
        shiftDownAlone = false;
    }

    private static String chinesePunctuation(int keyCode, boolean shifted) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_COMMA: return shifted ? "《" : "，";
            case KeyEvent.KEYCODE_PERIOD: return shifted ? "》" : "。";
            case KeyEvent.KEYCODE_SLASH: return shifted ? "？" : null;
            case KeyEvent.KEYCODE_SEMICOLON: return shifted ? "：" : "；";
            case KeyEvent.KEYCODE_BACKSLASH: return shifted ? null : "、";
            case KeyEvent.KEYCODE_1: return shifted ? "！" : null;
            case KeyEvent.KEYCODE_9: return shifted ? "（" : null;
            case KeyEvent.KEYCODE_0: return shifted ? "）" : null;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return shifted ? null : "【";
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return shifted ? null : "】";
            default: return null;
        }
    }
}
