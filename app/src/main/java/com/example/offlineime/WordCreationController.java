package com.example.offlineime;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Manages explicit word creation drafts, ordinary word assembly offers, name mode, and custom word saves. */
final class WordCreationController {
    interface Host {
        Context context();
        Handler mainHandler();
        ComposingSessionController composing();
        PinyinDictionary dictionary();
        KeyboardView keyboardView();
        HandwritingView handwritingInput();
        InputConnection currentInputConnection();
        OfflineImeService.Mode mode();
        void setMode(OfflineImeService.Mode mode);
        OfflineImeService.Mode restoreLastChineseMode();
        void rememberKeyboardMode(OfflineImeService.Mode mode);
        boolean isShuangpin();
        boolean isDarkMode();
        boolean isDestroyed();
        boolean isSensitiveEditor();
        boolean canSaveExplicitWord();
        boolean learningAllowed();
        boolean canLearn();
        long editorGeneration();
        String currentCandidateKey();
        List<PinyinPathEditor.Segment> pinyinSegments();
        boolean clearComposing(boolean commitBest);
        boolean commitText(CharSequence text);
        void hidePanels();
        void closeHandwriting();
        void invalidateCandidates();
        void resetCandidatePaging();
        void updateComposing();
        void refresh();
        void expandCandidates();
        void dispatchKey(String key);
        void rememberWord(String word);
        void queueAssembledWordLearning(String code, String word);
        void toast(String message);
    }

    private final Host host;
    final WordCreationSession session = new WordCreationSession();
    final WordCreationSession ordinaryWordAssembly = new WordCreationSession();
    CustomWordStore.Word ordinaryWordOffer;
    CustomWordStore.Change lastCustomWordChange;
    String lastCustomWordNotice = "";
    private Runnable customWordNoticeExpiry;
    private String category = "普通词";
    boolean nameMode;

    WordCreationController(Host host) {
        this.host = host;
    }

    boolean isActive() {
        return session.active;
    }

    boolean isSaving() {
        return session.saving;
    }

    boolean isNameMode() {
        return nameMode;
    }

    void clearNameMode() {
        nameMode = false;
    }

    void toggleNameMode() {
        OfflineImeService.Mode mode = host.mode();
        if (host.isSensitiveEditor() || mode != OfflineImeService.Mode.PINYIN && mode != OfflineImeService.Mode.NINE) {
            return;
        }
        nameMode = !nameMode;
        host.hidePanels();
        host.invalidateCandidates();
        host.refresh();
        KeyboardView view = host.keyboardView();
        if (view != null) view.announceForAccessibility(nameMode ? "人名候选已开启" : "已恢复普通候选");
        Toast.makeText(host.context(),
                nameMode ? "人名候选已开启，本输入框有效" : "已恢复普通候选",
                Toast.LENGTH_SHORT).show();
    }

    void clearAssembly() {
        ordinaryWordAssembly.clear();
    }

    void clearAllUserData() {
        ordinaryWordAssembly.clear();
        ordinaryWordOffer = null;
        session.clear();
        lastCustomWordChange = null;
        lastCustomWordNotice = "";
    }

    void clearOnStartInput() {
        ordinaryWordAssembly.clear();
        ordinaryWordOffer = null;
        session.clear();
        lastCustomWordChange = null;
        lastCustomWordNotice = "";
        nameMode = false;
    }

    void clearOnFinishInput() {
        session.clear();
    }

    boolean isChoosingWordParts() {
        OfflineImeService.Mode mode = host.mode();
        // Ordinary partial selection still needs normal phrase candidates for
        // the remaining input; only explicit word creation selects single parts.
        return (mode == OfflineImeService.Mode.PINYIN || mode == OfflineImeService.Mode.NINE) && session.active;
    }

    String status(String rawComposing) {
        // Ordinary typing must never insert a save/undo row into the keyboard.
        return session.active ? session.label(rawComposing) : "";
    }

    boolean hasSaveActions() {
        return host.canSaveExplicitWord() && (ordinaryWordOffer != null || lastCustomWordChange != null);
    }

    List<PinyinDictionary.Entry> filterCreationStrokes(List<PinyinDictionary.Entry> source, String strokes) {
        if (strokes.isEmpty()) return source;
        Set<String> letters = new HashSet<>(
                StrokeInputDictionary.get(host.context()).suggestCharacters(host.context(), strokes, 10000));
        List<PinyinDictionary.Entry> filtered = new ArrayList<>();
        for (PinyinDictionary.Entry entry : source) {
            QueryWork.check();
            if (entry.codePointLength == 1 && letters.contains(entry.word)) filtered.add(entry);
        }
        return filtered;
    }

    void trackOrdinaryWordSelection(PinyinDictionary.Entry selected, int consumed) {
        OfflineImeService.Mode mode = host.mode();
        ComposingSessionController composing = host.composing();
        if ((mode != OfflineImeService.Mode.PINYIN && mode != OfflineImeService.Mode.NINE)
                || !host.canSaveExplicitWord()) {
            ordinaryWordAssembly.clear();
            return;
        }
        boolean complete = consumed >= composing.length()
                || composing.buffer.substring(consumed).replace("'", "").isEmpty();
        if (!complete || !ordinaryWordAssembly.parts.isEmpty()) {
            String reading = selected.pronunciation;
            if (reading.isEmpty() && mode == OfflineImeService.Mode.PINYIN && !host.isShuangpin()) {
                try {
                    reading = CustomWordStore.reading(composing.buffer.substring(0, consumed), selected.originalWord);
                } catch (IllegalArgumentException ignored) {
                    reading = "";
                }
            }
            if (!ordinaryWordAssembly.add(selected.originalWord, reading, "")) {
                ordinaryWordAssembly.clear();
                return;
            }
            if (complete && ordinaryWordAssembly.parts.size() > 1) {
                try {
                    ordinaryWordOffer = CustomWordStore.validate(
                            ordinaryWordAssembly.word(), ordinaryWordAssembly.reading(), "", "普通词", false, 0);
                    if (host.learningAllowed()) {
                        host.queueAssembledWordLearning(ordinaryWordAssembly.reading(), ordinaryWordOffer.text);
                        lastCustomWordNotice = "已学习组合：" + ordinaryWordOffer.text + " · 点击永久保存";
                    } else {
                        lastCustomWordNotice = "记住组合：" + ordinaryWordOffer.text + " · 点击保存";
                    }
                    if (customWordNoticeExpiry != null) host.mainHandler().removeCallbacks(customWordNoticeExpiry);
                    customWordNoticeExpiry = () -> {
                        ordinaryWordOffer = null;
                        lastCustomWordNotice = "";
                        host.refresh();
                    };
                    host.mainHandler().postDelayed(customWordNoticeExpiry, 10000);
                } catch (IllegalArgumentException ignored) {
                    ordinaryWordOffer = null;
                }
                ordinaryWordAssembly.clear();
            }
        }
    }

    boolean handleKey(String key) {
        ComposingSessionController composing = host.composing();
        if ("造词".equals(key)) {
            if (!host.canSaveExplicitWord()) {
                host.toast("当前输入框或隐私模式不保存词语");
                return true;
            }
            if (session.active) {
                showCreationMenu();
                return true;
            }
            OfflineImeService.Mode mode = host.mode();
            if (mode != OfflineImeService.Mode.PINYIN && mode != OfflineImeService.Mode.NINE) {
                if (!host.clearComposing(true)) return true;
                OfflineImeService.Mode restored = host.restoreLastChineseMode();
                if (restored == OfflineImeService.Mode.STROKE) restored = OfflineImeService.Mode.PINYIN;
                host.setMode(restored);
                composing.pathEditor.clear();
                composing.shuangpin.clear();
                composing.cursor = 0;
            }
            host.hidePanels();
            session.start();
            category = "普通词";
            nameMode = false;
            InputConnection connection = host.currentInputConnection();
            if (connection != null && composing.hostHasComposingText) {
                connection.setComposingText("", 1);
                connection.finishComposingText();
            }
            composing.hostHasComposingText = false;
            host.invalidateCandidates();
            host.updateComposing();
            return true;
        }
        if ("造词菜单".equals(key)) {
            showCreationMenu();
            return true;
        }
        if (!session.active) return false;
        if (!host.canSaveExplicitWord()) {
            session.clear();
            host.clearComposing(false);
            host.toast("造词已结束，当前模式不保存词语");
            return true;
        }
        if (session.saving) {
            host.toast("正在保存词语");
            return true;
        }
        if ("字".equals(key) || "26键".equals(key) || "9键".equals(key)) {
            switchMode("9键".equals(key) ? OfflineImeService.Mode.NINE : OfflineImeService.Mode.PINYIN);
            return true;
        }
        if (key.startsWith("手写字:")) {
            addHandwrittenWord(key.substring(4));
            return true;
        }
        if ("重输".equals(key)) {
            composing.buffer.setLength(0);
            composing.pathEditor.clear();
            composing.shuangpin.clear();
            composing.touchCorrection.clear();
            host.updateComposing();
            return true;
        }
        if ("设置".equals(key) || "工具".equals(key) || "剪贴板".equals(key) || "😊".equals(key)
                || "ABC".equals(key) || "中英".equals(key) || "123".equals(key) || "符".equals(key)
                || "符号".equals(key) || "笔画".equals(key) || key.startsWith("长按数字:")
                || key.startsWith("直接输入:") || "标点".equals(key)) {
            host.toast("请先完成或退出造词");
            return true;
        }
        // Punctuation must not leak an unfinished draft into the host field.
        if (key.length() == 1 && !key.matches("[a-zA-Z2-9']")) {
            host.toast("造词时请输入拼音；点击草稿可完成或退出");
            return true;
        }
        return false;
    }

    boolean selectCandidate(PinyinDictionary.Entry selected) {
        ComposingSessionController composing = host.composing();
        if (selected.pronunciation.isEmpty()) {
            host.toast("这个候选没有完整读音，请逐字选择");
            return false;
        }
        int consumed = Math.min(composing.length(),
                selected.consumedLength > 0 ? selected.consumedLength : composing.length());
        String raw = composing.buffer.substring(0, consumed);
        boolean editing = session.editing >= 0;
        if (editing && consumed < composing.length()) {
            host.toast("请完整选择正在修改的片段");
            return false;
        }
        if (!session.add(selected.word, selected.pronunciation, raw)) {
            host.toast("读音未完整，请先选择完整音节");
            return false;
        }
        boolean shuangpinMode = host.isShuangpin();
        composing.consumePrefix(consumed, host.mode(), shuangpinMode);
        if (editing) {
            composing.buffer.setLength(0);
            composing.buffer.append(session.remaining);
            session.remaining = "";
            composing.pathEditor.clear();
            composing.shuangpin.clear();
            restoreRemainingLocks();
        }
        if (!editing && !shuangpinMode) composing.shuangpin.clear();
        composing.cursor = composing.length();
        composing.lockedPinyinPath = "";
        host.resetCandidatePaging();
        host.invalidateCandidates();
        host.updateComposing();
        return true;
    }

    void undoSelection() {
        ComposingSessionController composing = host.composing();
        OfflineImeService.Mode mode = host.mode();
        boolean shuangpinMode = host.isShuangpin();
        if (session.editing >= 0) {
            session.editing = -1;
            composing.buffer.setLength(0);
            composing.buffer.append(session.remaining);
            session.remaining = "";
            composing.pathEditor.clear();
            composing.shuangpin.clear();
            restoreRemainingLocks();
        } else if (!session.parts.isEmpty()) {
            WordCreationSession.Part part = session.parts.remove(session.parts.size() - 1);
            String encoded = encodeReading(part.reading, mode);
            List<PinyinPathEditor.Segment> tailLocks = new ArrayList<>(host.pinyinSegments());
            String remaining = composing.raw();
            composing.buffer.setLength(0);
            composing.buffer.append(encoded);
            if (!remaining.isEmpty()) {
                if (mode == OfflineImeService.Mode.PINYIN && !shuangpinMode) composing.buffer.append('\'');
                composing.buffer.append(remaining);
            }
            composing.pathEditor.clear();
            composing.shuangpin.clear();
            restoreLocks(part.reading);
            int offset = encoded.length()
                    + (!remaining.isEmpty() && mode == OfflineImeService.Mode.PINYIN && !shuangpinMode ? 1 : 0);
            for (PinyinPathEditor.Segment segment : tailLocks) {
                if (segment.locked) {
                    if (shuangpinMode) {
                        composing.shuangpin.select(composing.raw(), offset + segment.start, offset + segment.end, segment.reading);
                    } else {
                        composing.pathEditor.select(composing.raw(), mode == OfflineImeService.Mode.NINE,
                                offset + segment.start, offset + segment.end, segment.reading);
                    }
                }
            }
        } else {
            host.toast("还没有已选的字");
            return;
        }
        session.revision++;
        composing.touchCorrection.clear();
        composing.cursor = composing.length();
        composing.lockedPinyinPath = "";
        host.hidePanels();
        host.invalidateCandidates();
        host.updateComposing();
    }

    void switchMode(OfflineImeService.Mode target) {
        if (target != OfflineImeService.Mode.PINYIN && target != OfflineImeService.Mode.NINE) {
            host.toast("造词支持中文 26 键与 9 键");
            return;
        }
        if (session.editing >= 0) {
            host.toast("请先完成当前片段的修改");
            return;
        }
        ComposingSessionController composing = host.composing();
        List<String> readings = new ArrayList<>();
        for (PinyinPathEditor.Segment segment : host.pinyinSegments()) {
            if (segment.prefix || !PinyinPathEditor.isCompleteReading(segment.reading)) {
                host.toast("请先补全当前音节，再切换布局");
                return;
            }
            readings.add(segment.reading);
        }
        if (composing.length() > 0 && readings.isEmpty()) {
            host.toast("请先明确当前拼音路径");
            return;
        }
        host.setMode(target);
        String joined = String.join("'", readings);
        composing.buffer.setLength(0);
        composing.buffer.append(encodeReading(joined, target));
        composing.pathEditor.clear();
        composing.shuangpin.clear();
        restoreLocks(joined);
        composing.cursor = composing.length();
        session.revision++;
        host.rememberKeyboardMode(target);
        host.hidePanels();
        host.invalidateCandidates();
        host.updateComposing();
    }

    void showCreationMenu() {
        KeyboardView keyboardView = host.keyboardView();
        if (keyboardView == null) return;
        if (session.active && session.saving) {
            host.toast("正在保存词语");
            return;
        }
        ComposingSessionController composing = host.composing();
        RoundedCardPopup menu = RoundedCardPopup.keyboard(host.context(),
                session.active ? "造词草稿" : "保存结果",
                KeyboardTheme.from(host.context(), host.isDarkMode()));
        if (!session.active) {
            CustomWordStore.Word offer = ordinaryWordOffer;
            if (offer != null) {
                menu.item("记住刚输入的整词", offer.text, null, false, false,
                        () -> showSaveCandidateMenu(offer.text, String.join("'", offer.readings)));
            }
            if (lastCustomWordChange != null) {
                menu.item("撤销词库修改", "保留已上屏文字", null, false, false, this::undoLastCustomSave);
            }
            keyboardView.showWordMenu(menu);
            return;
        }
        long revision = session.revision;
        for (int index = 0; index < session.parts.size(); index++) {
            int chosen = index;
            WordCreationSession.Part part = session.parts.get(index);
            menu.item(part.word, part.reading.replace('\'', ' '), null, session.editing == index, false, () -> {
                if (revision != session.revision) return;
                RoundedCardPopup edit = RoundedCardPopup.keyboard(host.context(), part.word,
                        KeyboardTheme.from(host.context(), host.isDarkMode()));
                edit.item("重新选字", "保留这段读音", null, false, false, () -> editPart(chosen, true));
                edit.item("修改读音", "可退格重输，仅修改这一段", null, false, false, () -> editPart(chosen, false));
                edit.item("删除这段", "保留其他已选片段", null, false, true, () -> {
                    if (revision != session.revision) return;
                    if (session.editing == chosen) {
                        composing.buffer.setLength(0);
                        composing.buffer.append(session.remaining);
                        session.remaining = "";
                        session.editing = -1;
                        composing.pathEditor.clear();
                        composing.shuangpin.clear();
                        restoreRemainingLocks();
                    } else if (session.editing > chosen) {
                        session.editing--;
                    }
                    session.parts.remove(chosen);
                    session.revision++;
                    composing.cursor = composing.length();
                    host.invalidateCandidates();
                    host.updateComposing();
                });
                keyboardView.showWordMenu(edit);
            });
        }
        boolean ready = composing.isEmpty() && session.editing < 0 && !session.parts.isEmpty();
        if (ready && !session.saving) {
            menu.divider().item("保存并上屏", session.reading().replace('\'', ' '), null, false, false, () -> saveWord(false));
            menu.item("保存并固定后上屏", "仅匹配读音或别名时优先", null, false, false, () -> saveWord(true));
            menu.item("只上屏", "不保存到词库", null, false, false, this::finishWithoutSaving);
        } else {
            menu.item("继续输入并选字", "全部选完后可保存整词", null, false, false, () -> {
                host.hidePanels();
                host.refresh();
            });
        }
        menu.item("词语分类：" + category, null, null, false, false, () -> {
            RoundedCardPopup categories = RoundedCardPopup.keyboard(host.context(), "词语分类",
                    KeyboardTheme.from(host.context(), host.isDarkMode()));
            for (String value : new String[]{"普通词", "人名", "地名", "专业术语", "昵称", "短语"}) {
                categories.item(value, null, null, value.equals(category), false, () -> {
                    category = value;
                    showCreationMenu();
                });
            }
            keyboardView.showWordMenu(categories);
        });
        menu.item(session.editing >= 0 ? "取消这段修改" : "撤销上次选字", null, null, false, false, this::undoSelection);
        menu.item("笔画筛选" + (session.strokePrefix.isEmpty() ? "" : "：" + session.strokePrefix),
                "在当前拼音候选中按起笔笔画找字", null, false, false, this::showStrokeMenu);
        menu.item("手写补字", "选择后确认读音，再回到草稿", null, false, false, () -> host.dispatchKey("手写"));
        menu.divider().item("清空草稿", null, null, false, true, () -> {
            session.start();
            composing.buffer.setLength(0);
            composing.pathEditor.clear();
            composing.shuangpin.clear();
            host.updateComposing();
        });
        menu.item("退出造词", "放弃本次草稿", null, false, true, () -> {
            session.clear();
            host.clearComposing(false);
            host.hidePanels();
            host.invalidateCandidates();
            host.refresh();
        });
        keyboardView.showWordMenu(menu);
    }

    void offerSaveCandidate(PinyinDictionary.Entry entry) {
        if (entry == null || !host.canSaveExplicitWord() || session.saving) return;
        if (!entry.inputVersion.isEmpty() && !entry.inputVersion.equals(host.currentCandidateKey())) return;
        showSaveCandidateMenu(entry.word, entry.pronunciation);
    }

    void editCandidateWord(PinyinDictionary.Entry entry) {
        if (entry == null || !host.canSaveExplicitWord() || session.active) return;
        if (!entry.inputVersion.isEmpty() && !entry.inputVersion.equals(host.currentCandidateKey())) return;
        host.context().startActivity(new Intent(host.context(), CustomWordManagerActivity.class)
                .putExtra("word", entry.originalWord)
                .putExtra("reading", entry.pronunciation)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void showStrokeMenu() {
        KeyboardView keyboardView = host.keyboardView();
        if (keyboardView == null) return;
        RoundedCardPopup menu = RoundedCardPopup.keyboard(host.context(), "按起笔笔画筛选单字",
                KeyboardTheme.from(host.context(), host.isDarkMode()));
        String[] strokes = {"横（一）", "竖（丨）", "撇（丿）", "点／捺（丶）", "折（乙）"};
        for (int index = 0; index < strokes.length; index++) {
            String code = String.valueOf(index + 1);
            menu.item(strokes[index], null, null, false, false, () -> {
                if (session.strokePrefix.length() < 16) session.strokePrefix += code;
                session.revision++;
                host.invalidateCandidates();
                host.refresh();
                showStrokeMenu();
            });
        }
        menu.item("撤销一笔", null, null, false, false, () -> {
            if (!session.strokePrefix.isEmpty()) {
                session.strokePrefix = session.strokePrefix.substring(0, session.strokePrefix.length() - 1);
            }
            session.revision++;
            host.invalidateCandidates();
            host.refresh();
            showStrokeMenu();
        });
        menu.item("清除筛选", null, null, false, false, () -> {
            session.strokePrefix = "";
            session.revision++;
            host.invalidateCandidates();
            host.refresh();
        });
        menu.item("查看筛选后的候选", "已输入笔画：" + session.strokePrefix, null, false, false, host::expandCandidates);
        keyboardView.showWordMenu(menu);
    }

    private void editPart(int index, boolean preserveReading) {
        if (index < 0 || index >= session.parts.size() || session.saving) return;
        ComposingSessionController composing = host.composing();
        if (session.editing < 0) {
            session.remaining = composing.raw();
            session.remainingLocks.clear();
            for (PinyinPathEditor.Segment segment : host.pinyinSegments()) {
                if (segment.locked) session.remainingLocks.add(segment);
            }
        }
        session.strokePrefix = "";
        session.editing = index;
        session.revision++;
        WordCreationSession.Part part = session.parts.get(index);
        composing.buffer.setLength(0);
        composing.buffer.append(encodeReading(part.reading, host.mode()));
        composing.pathEditor.clear();
        composing.shuangpin.clear();
        composing.touchCorrection.clear();
        composing.lockedPinyinPath = "";
        if (preserveReading) restoreLocks(part.reading);
        composing.cursor = composing.length();
        host.hidePanels();
        host.invalidateCandidates();
        host.updateComposing();
    }

    private String encodeReading(String reading, OfflineImeService.Mode target) {
        boolean shuangpinMode = host.isShuangpin();
        List<String> values = new ArrayList<>();
        for (String token : reading.split("[ ']+")) {
            String encoded = target == OfflineImeService.Mode.NINE ? PinyinDictionary.toT9(token)
                    : shuangpinMode ? ShuangpinInput.encode(token) : token;
            if (encoded.isEmpty()) return "";
            values.add(encoded);
        }
        return String.join(target == OfflineImeService.Mode.NINE || shuangpinMode ? "" : "'", values);
    }

    private void restoreLocks(String reading) {
        ComposingSessionController composing = host.composing();
        if (host.isShuangpin()) {
            int at = 0;
            for (String token : reading.split("[ ']+")) {
                composing.shuangpin.select(composing.raw(), at, at + 2, token);
                at += 2;
            }
        } else {
            boolean nineKey = host.mode() == OfflineImeService.Mode.NINE;
            int at = 0;
            for (String token : reading.split("[ ']+")) {
                while (at < composing.length() && composing.buffer.charAt(at) == '\'') at++;
                int end = at + (nineKey ? PinyinDictionary.toT9(token).length() : token.length());
                composing.pathEditor.select(composing.raw(), nineKey, at, end, token);
                at = end;
            }
        }
    }

    private void restoreRemainingLocks() {
        ComposingSessionController composing = host.composing();
        boolean shuangpinMode = host.isShuangpin();
        boolean nineKey = host.mode() == OfflineImeService.Mode.NINE;
        for (PinyinPathEditor.Segment segment : session.remainingLocks) {
            if (shuangpinMode) {
                composing.shuangpin.select(composing.raw(), segment.start, segment.end, segment.reading);
            } else {
                composing.pathEditor.select(composing.raw(), nineKey, segment.start, segment.end, segment.reading);
            }
        }
        session.remainingLocks.clear();
    }

    private void saveWord(boolean pinned) {
        ComposingSessionController composing = host.composing();
        if (!session.active || session.saving || !composing.isEmpty() || session.editing >= 0
                || !host.canSaveExplicitWord()) {
            return;
        }
        CustomWordStore.Word value;
        try {
            value = CustomWordStore.validate(session.word(), session.reading(), "", category, pinned, 0);
        } catch (IllegalArgumentException error) {
            host.toast(error.getMessage());
            return;
        }
        session.saving = true;
        long generation = host.editorGeneration();
        long revision = session.revision;
        host.refresh();
        Context appContext = host.context().getApplicationContext();
        PinyinDictionary dictionary = host.dictionary();
        CustomWordStore.IO.execute(() -> {
            CustomWordStore.Change change = CustomWordStore.merge(appContext, value);
            if (change.succeeded() && dictionary != null) dictionary.reloadManagedWords();
            host.mainHandler().post(() -> {
                if (host.isDestroyed()) return;
                if (generation != host.editorGeneration() || revision != session.revision || !session.active) return;
                session.saving = false;
                if (!change.succeeded()) {
                    host.toast(change.error);
                    host.refresh();
                    return;
                }
                if (host.commitText(value.text)) {
                    if (host.canLearn() && !value.readings.isEmpty() && session.parts.size() > 1) {
                        host.queueAssembledWordLearning(value.readings.get(0), value.text);
                    }
                    host.rememberWord(value.text);
                }
                session.clear();
                composing.buffer.setLength(0);
                composing.pathEditor.clear();
                composing.shuangpin.clear();
                host.hidePanels();
                host.invalidateCandidates();
                showCustomWordSaved(change, "已保存：" + value.text + " · " + value.readings.get(0).replace('\'', ' '));
                host.updateComposing();
            });
        });
    }

    private void finishWithoutSaving() {
        ComposingSessionController composing = host.composing();
        if (session.saving || !composing.isEmpty() || session.editing >= 0) return;
        host.commitText(session.word());
        session.clear();
        composing.pathEditor.clear();
        composing.shuangpin.clear();
        host.hidePanels();
        host.updateComposing();
    }

    private void showCustomWordSaved(CustomWordStore.Change change, String notice) {
        ordinaryWordOffer = null;
        lastCustomWordChange = change;
        lastCustomWordNotice = notice + " · 点击撤销";
        host.toast(notice);
        if (customWordNoticeExpiry != null) host.mainHandler().removeCallbacks(customWordNoticeExpiry);
        customWordNoticeExpiry = () -> {
            lastCustomWordChange = null;
            lastCustomWordNotice = "";
            host.refresh();
        };
        host.mainHandler().postDelayed(customWordNoticeExpiry, 10000);
        host.refresh();
    }

    private void undoLastCustomSave() {
        CustomWordStore.Change change = lastCustomWordChange;
        if (change == null) return;
        lastCustomWordChange = null;
        lastCustomWordNotice = "";
        Context appContext = host.context().getApplicationContext();
        CustomWordStore.IO.execute(() -> {
            boolean undone;
            try {
                undone = CustomWordStore.undo(appContext, change);
            } catch (RuntimeException failed) {
                undone = false;
            }
            boolean done = undone;
            host.mainHandler().post(() -> {
                host.toast(done ? "已撤销词库修改" : "词条已改变，未撤销");
                host.invalidateCandidates();
                host.refresh();
            });
        });
    }

    private void showSaveCandidateMenu(String word, String reading) {
        if (!host.canSaveExplicitWord()) {
            host.toast("当前模式不保存词语");
            return;
        }
        if (reading.isEmpty()) {
            host.toast("请在词库管理中补充完整读音");
            return;
        }
        KeyboardView keyboardView = host.keyboardView();
        if (keyboardView == null) return;
        RoundedCardPopup menu = RoundedCardPopup.keyboard(host.context(), "保存「" + word + "」",
                KeyboardTheme.from(host.context(), host.isDarkMode()));
        Context appContext = host.context().getApplicationContext();
        for (boolean pinned : new boolean[]{false, true}) {
            menu.item(pinned ? "保存并固定" : "保存到自定义词库",
                    reading.replace('\'', ' '), null, false, false, () -> {
                        CustomWordStore.Word value;
                        try {
                            value = CustomWordStore.validate(word, reading, "", "普通词", pinned, 0);
                        } catch (IllegalArgumentException error) {
                            host.toast(error.getMessage());
                            return;
                        }
                        CustomWordStore.IO.execute(() -> {
                            CustomWordStore.Change change = CustomWordStore.merge(appContext, value);
                            host.mainHandler().post(() -> {
                                if (change.succeeded()) {
                                    host.invalidateCandidates();
                                    showCustomWordSaved(change, "已保存：" + word);
                                } else {
                                    host.toast(change.error);
                                }
                            });
                        });
                    });
        }
        keyboardView.showWordMenu(menu);
    }

    private void addHandwrittenWord(String word) {
        HandwritingView pad = host.handwritingInput();
        PinyinDictionary dictionary = host.dictionary();
        if (dictionary == null || pad == null) return;
        long revision = session.revision;
        long generation = host.editorGeneration();
        int inkRevision = pad.revision();
        CustomWordStore.IO.execute(() -> {
            List<String> readings = dictionary.wordReadings(word);
            host.mainHandler().post(() -> {
                if (!session.active || revision != session.revision || generation != host.editorGeneration()
                        || host.handwritingInput() != pad || inkRevision != pad.revision()
                        || pad.getWindowVisibility() != View.VISIBLE) {
                    return;
                }
                if (readings.isEmpty()) {
                    host.toast("未收录这个字的读音，请用拼音选字或在词库管理中添加");
                    return;
                }
                List<PinyinPathEditor.Segment> segments = host.pinyinSegments();
                PinyinPathEditor.Segment first = segments.isEmpty() ? null : segments.get(0);
                RoundedCardPopup menu = RoundedCardPopup.keyboard(host.context(), "确认「" + word + "」读音",
                        KeyboardTheme.from(host.context(), host.isDarkMode()));
                int choices = 0;
                ComposingSessionController composing = host.composing();
                for (String reading : readings) {
                    if (reading.indexOf('\'') >= 0 || !PinyinPathEditor.isCompleteReading(reading)) continue;
                    if (first != null && first.locked
                            && !(first.prefix ? reading.startsWith(first.reading) : reading.equals(first.reading))) {
                        continue;
                    }
                    choices++;
                    menu.item(reading, null, null, false, false, () -> {
                        if (revision != session.revision || generation != host.editorGeneration()
                                || host.handwritingInput() != pad || inkRevision != pad.revision()) {
                            return;
                        }
                        int consumed = first == null ? 0 : first.end;
                        if (consumed > 0) {
                            String raw = composing.buffer.substring(0, consumed);
                            String encoded = host.mode() == OfflineImeService.Mode.NINE
                                    ? PinyinDictionary.toT9(reading)
                                    : host.isShuangpin() ? ShuangpinInput.encode(reading) : reading;
                            if (!encoded.startsWith(raw.replace("'", ""))) {
                                host.toast("这个读音与当前编码不匹配，请先修改拼音");
                                return;
                            }
                        }
                        PinyinDictionary.Entry selected = new PinyinDictionary.Entry(word, 0).withInput(consumed, reading);
                        if (first == null) {
                            if (session.add(word, reading, "")) {
                                host.hidePanels();
                                host.invalidateCandidates();
                                host.refresh();
                            }
                        } else if (selectCandidate(selected)) {
                            host.closeHandwriting();
                        }
                    });
                }
                KeyboardView keyboardView = host.keyboardView();
                if (choices == 0) host.toast("这个字与当前限定读音不匹配，请先修改拼音");
                else if (keyboardView != null) keyboardView.showWordMenu(menu);
            });
        });
    }
}
