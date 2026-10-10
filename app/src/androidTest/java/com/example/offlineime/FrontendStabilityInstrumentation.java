package com.example.offlineime;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** On-device regression suite. Injects real touch events into the installed IME. */
public final class FrontendStabilityInstrumentation extends Instrumentation {
    private Activity host;
    private EditText field;
    private UiAutomation ui;
    private File out;
    private String phase;
    private String onlyCase;
    private String requestedGroup;
    private final JSONArray results = new JSONArray();
    private int failures;
    private int actions;
    private int rotationReactivations;
    private SharedPreferences preferences;
    private SharedPreferences keyboardPreferences;
    private Map<String, ?> saved;
    private Map<String, ?> savedKeyboardState;
    private Map<String, ?> baseline;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args == null ? "run" : args.getString("phase", "run");
        onlyCase = args == null ? null : args.getString("only");
        requestedGroup = args == null ? "all" : args.getString("group", "all");
        start();
    }

    @Override public void onStart() {
        Bundle summary = new Bundle();
        try {
            ui = getUiAutomation();
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            ui.setServiceInfo(info);
            // Starting instrumentation force-stops the target; Android may select
            // its fallback IME. Explicitly bind the tested IME before any touch.
            shell("ime enable com.example.offlineime/.OfflineImeService");
            shell("ime set com.example.offlineime/.OfflineImeService");
            pause(500);
            out = new File(getTargetContext().getExternalFilesDir(null), "frontend-stability/" + phase);
            out.mkdirs();
            preferences = getTargetContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
            keyboardPreferences = getTargetContext().getSharedPreferences("keyboard_state", Context.MODE_PRIVATE);
            saved = new LinkedHashMap<>(preferences.getAll());
            savedKeyboardState = new LinkedHashMap<>(keyboardPreferences.getAll());
            preferences.edit().putString("default_layout", "qwerty").putBoolean("dark", false)
                    .putBoolean("one_hand", false).putBoolean("floating", false)
                    .putBoolean("privacy_mode", false).putBoolean("clipboard_enabled", false)
                    .putBoolean("learn_words", false).putInt("key_height", 360).commit();
            baseline = new LinkedHashMap<>(preferences.getAll());

            test("28_keyboard_region_stays_fixed", () -> {
                preferences.edit().putString("default_layout", "qwerty").putBoolean("toolbar", true)
                        .putBoolean("full_pinyin", true).putBoolean("short_pinyin", true)
                        .putBoolean("learn_words", false).putBoolean("one_hand", false).putBoolean("floating", false).commit();
                fresh(InputType.TYPE_CLASS_TEXT);
                Rect idle = bounds("q"), bottom = bounds("空格");
                screenshot("geometry-idle");
                for (char key : "nihao".toCharArray()) {
                    tap(String.valueOf(key)); Rect now = bounds("q");
                    require(Math.abs(now.top - idle.top) <= 2 && Math.abs(now.height() - idle.height()) <= 2,
                            "Key row moved during input: " + idle + " -> " + now);
                }
                await("你好"); Rect ready = bounds("q");
                require(Math.abs(ready.top - idle.top) <= 2 && Math.abs(ready.height() - idle.height()) <= 2, "Candidate completion resized key row");
                require(bounds("空格").equals(bottom), "Bottom key row moved"); screenshot("geometry-candidates-ready");
                click("展开候选词"); click("返回"); await("q");
                require(bounds("q").equals(idle), "Candidate page return changed key geometry");
                for (int i = 0; i < 5; i++) tap("退格删除");
                require(bounds("q").equals(idle), "Empty composition changed key geometry");
                click("剪贴板"); await("返回键盘"); click("返回键盘"); await("q");
                require(bounds("q").equals(idle), "Clipboard return changed key geometry");
                preferences.edit().putBoolean("toolbar", false).commit(); fresh(InputType.TYPE_CLASS_TEXT);
                Rect hiddenToolbar = bounds("q");
                for (char key : "nihao".toCharArray()) tap(String.valueOf(key));
                await("你好"); require(bounds("q").equals(hiddenToolbar), "Hidden toolbar resized keys on input");
                screenshot("geometry-toolbar-disabled"); preferences.edit().putBoolean("toolbar", true).commit();
            });
            test("27_pinyin_edit_partial_and_user_words", () -> {
                String originalWords = SecureLocalStore.read(getTargetContext(), "user_words", "offline_ime_words");
                try {
                    preferences.edit().putString("default_layout", "qwerty").putBoolean("full_pinyin", true)
                            .putBoolean("short_pinyin", true).putBoolean("mixed_pinyin", true)
                            .putBoolean("double_initials", true).putBoolean("sentence_prediction", true)
                            .putBoolean("spelling_correction", true).putBoolean("fuzzy_pinyin", false).commit();
                    PinyinDictionary dictionary = new PinyinDictionary(getTargetContext().getAssets(), getTargetContext()); dictionary.load();
                    PinyinDictionary.saveCustomWord(getTargetContext(), "kai'xin'ce'ci", "开心测词");
                    dictionary.reloadManagedWords();
                    for (String query : new String[]{"kaixinceci", "kxcc", "kaixcc", "kxincci"})
                        require(dictionary.suggest(query).stream().anyMatch(entry -> "开心测词".equals(entry.word)), "Custom pronunciation missed " + query);
                    dictionary.adjustPreference("kaixinceci", "开心测词", "lower");
                    require(dictionary.suggest("kxcc").stream().filter(entry -> "开心测词".equals(entry.word)).noneMatch(entry -> entry.pinned), "Lowering retained pin");
                    PinyinDictionary.deleteManagedWord(getTargetContext(), "开心测词"); dictionary.reloadManagedWords();
                    require(dictionary.suggest("kxcc").stream().noneMatch(entry -> "开心测词".equals(entry.word)), "Deleted custom word survived");
                    preferences.edit().putBoolean("short_pinyin", false).commit();
                    pause(180);
                    require(dictionary.suggest("nh").stream().noneMatch(entry -> "你好".equals(entry.word)), "Short input flag ignored");
                    require(dictionary.suggestFast("nh").stream().noneMatch(entry -> "你好".equals(entry.word)), "Bootstrap flag ignored");
                    preferences.edit().putBoolean("full_pinyin", false).commit();
                    pause(180);
                    require(dictionary.suggest("nihao").isEmpty() && dictionary.suggestT9("64426").isEmpty()
                            && dictionary.suggestT9Fast("64426").isEmpty(), "Both modes disabled still returned Chinese");
                    preferences.edit().putBoolean("full_pinyin", true).putBoolean("short_pinyin", true).commit();
                    // Preference listeners are delivered on the app main
                    // looper while instrumentation runs on its own thread.
                    pause(180);
                    java.util.List<PinyinDictionary.Entry> partials = dictionary.suggest("nihaopengyou");
                    require(partials.stream().anyMatch(entry -> "你好朋友".equals(entry.word)),
                            "Whole phrase missing: " + partials.stream().limit(12).map(entry -> entry.word)
                                    .collect(java.util.stream.Collectors.toList()));
                    require(partials.stream().anyMatch(entry -> "你好".equals(entry.word) && entry.consumedLength == 5), "Partial span was incorrect");
                    fresh(InputType.TYPE_CLASS_TEXT);
                    for (char key : "nihaopengyou".toCharArray()) tap(String.valueOf(key));
                    awaitPrefix("首选候选词 "); click("展开候选词");
                    for (int scroll = 0; scroll < 8 && !has("你好"); scroll++) {
                        for (AccessibilityWindowInfo window : ui.getWindows())
                            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) scrollContent(window.getRoot());
                        pause(200);
                    }
                    await("你好"); click("你好");
                    pause(500); require(fieldText().startsWith("你好"), "Partial word did not commit");
                    awaitPrefix("首选候选词 "); tap("空格"); pause(600);
                    require(fieldText().equals("你好朋友"), "Suffix was lost after partial selection: " + fieldText());
                    fresh(InputType.TYPE_CLASS_TEXT);
                    for (char key : "nihao".toCharArray()) tap(String.valueOf(key));
                    await("选择拼音路径 ni·hao"); click("选择拼音路径 ni·hao");
                    await("当前拼音路径 ni·hao"); longClickPrefix("当前拼音路径 ni·hao");
                    clickDialog("hao"); clickDialog("hao");
                    tap("退格删除"); tap("i"); await("你还");
                    screenshot("pinyin-edit-cursor");
                    fresh(InputType.TYPE_CLASS_TEXT);
                    for (char key : "nihao".toCharArray()) tap(String.valueOf(key));
                    await("你好");
                    preferences.edit().putBoolean("learn_words", true).commit();
                    Rect candidate = bounds("你好"); gesture(candidate, 0, 650, false); clickDialog("固定候选");
                    await("你好"); screenshot("pinyin-word-preference");
                } finally {
                    SecureLocalStore.write(getTargetContext(), "user_words", "offline_ime_words", originalWords);
                    preferences.edit().putLong("user_words_revision", System.currentTimeMillis()).commit();
                }
            });
            test("26_pinyin_corpus_evaluation", () -> {
                preferences.edit().putBoolean("full_pinyin", true).putBoolean("short_pinyin", true)
                        .putBoolean("mixed_pinyin", true).putBoolean("double_initials", true)
                        .putBoolean("sentence_prediction", true).putBoolean("fuzzy_pinyin", false)
                        .putBoolean("spelling_correction", true).commit();
                PinyinDictionary dictionary = new PinyinDictionary(getTargetContext().getAssets(), getTargetContext());
                dictionary.load();
                java.util.Map<String, int[]> stats = new java.util.LinkedHashMap<>();
                java.util.List<Long> timings = new java.util.ArrayList<>();
                JSONArray misses = new JSONArray(); int count = 0;
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        getContext().getAssets().open("pinyin-evaluation.tsv"), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] row = line.split("\\t"); long began = SystemClock.elapsedRealtime();
                        java.util.List<PinyinDictionary.Entry> candidates = dictionary.suggest(row[1]);
                        timings.add(SystemClock.elapsedRealtime() - began);
                        int[] group = stats.computeIfAbsent(row[0], key -> new int[3]); group[0]++;
                        if (!candidates.isEmpty() && row[2].equals(candidates.get(0).word)) group[1]++;
                        boolean hit = false;
                        for (int i = 0; i < Math.min(5, candidates.size()); i++) hit |= row[2].equals(candidates.get(i).word);
                        if (hit) group[2]++;
                        else if (misses.length() < 100) { JSONObject miss = new JSONObject(); miss.put("category", row[0]); miss.put("input", row[1]); miss.put("expected", row[2]); misses.put(miss); }
                        if (++count % 100 == 0) { Bundle progress = new Bundle(); progress.putString("stream", "Pinyin evaluation " + count + "/2500\n"); sendStatus(0, progress); }
                    }
                }
                java.util.Collections.sort(timings); JSONObject report = new JSONObject(); report.put("seed", 20261003); report.put("cases", count);
                report.put("p95Ms", timings.get((int)Math.ceil(timings.size() * .95) - 1));
                report.put("p99Ms", timings.get((int)Math.ceil(timings.size() * .99) - 1));
                JSONObject categories = new JSONObject();
                for (java.util.Map.Entry<String, int[]> group : stats.entrySet()) {
                    JSONObject metric = new JSONObject(); int[] numbers = group.getValue(); metric.put("cases", numbers[0]);
                    metric.put("top1Rate", numbers[1] / (double)numbers[0]); metric.put("top5Rate", numbers[2] / (double)numbers[0]); categories.put(group.getKey(), metric);
                }
                report.put("categories", categories); report.put("misses", misses); write("pinyin-evaluation.json", report.toString(2));
                require(count == 2500, "Evaluation corpus was incomplete");
            });
            test("25_pinyin_decoder_matrix", () -> {
                preferences.edit().putBoolean("full_pinyin", true).putBoolean("short_pinyin", true)
                        .putBoolean("mixed_pinyin", true).putBoolean("double_initials", true)
                        .putBoolean("sentence_prediction", true).putBoolean("fuzzy_pinyin", false)
                        .putBoolean("spelling_correction", true).commit();
                PinyinDictionary dictionary = new PinyinDictionary(getTargetContext().getAssets(), getTargetContext());
                dictionary.load();
                JSONArray matrix = new JSONArray();
                for (String[] sample : new String[][]{{"nihao", "你好"}, {"nh", "你好"}, {"nhao", "你好"},
                        {"nih", "你好"}, {"zhongguo", "中国"}, {"zg", "中国"}, {"zhg", "中国"},
                        {"zhongg", "中国"}, {"zguo", "中国"}, {"shijie", "世界"}, {"shj", "世界"},
                        {"sjie", "世界"}, {"gongzry", "工作人员"}, {"gzuory", "工作人员"},
                        {"zjinzenmy", "最近怎么样"}, {"xi'an", "西安"}, {"lü", "绿"}, {"nihaoo", "你好"},
                        {"nihoa", "你好"}, {"wojintianxiangchihuoguoranhouhuijia", "我今天想吃火锅然后回家"}}) {
                    long start = SystemClock.elapsedRealtime();
                    java.util.List<PinyinDictionary.Entry> entries = dictionary.suggest(sample[0]);
                    JSONArray top = new JSONArray(); boolean found = false;
                    for (int i = 0; i < Math.min(8, entries.size()); i++) { top.put(entries.get(i).word); found |= sample[1].equals(entries.get(i).word); }
                    JSONObject item = new JSONObject(); item.put("input", sample[0]); item.put("expected", sample[1]);
                    item.put("top8", top); item.put("durationMs", SystemClock.elapsedRealtime() - start); item.put("hit", found); matrix.put(item);
                }
                write("pinyin-matrix.json", matrix.toString(2));
                for (int i = 0; i < matrix.length(); i++) require(matrix.getJSONObject(i).getBoolean("hit"), matrix.getJSONObject(i).toString());
                java.util.List<PinyinDictionary.Entry> forced = dictionary.suggest("xi'an");
                for (PinyinDictionary.Entry entry : forced) require(!"先".equals(entry.word), "Explicit separator admitted xian");
                for (String input : new String[]{"64426", "64", "6426", "644"})
                    require(dictionary.suggestT9(input).stream().anyMatch(entry -> "你好".equals(entry.word)), "Nine-key spelling missed " + input);
                String thirtyTwo = String.join("'", java.util.Collections.nCopies(32, "ni"));
                require(dictionary.suggest(thirtyTwo).stream().anyMatch(entry -> entry.word.length() == 32), "32-syllable sentence was not decoded");
                require(dictionary.suggest(thirtyTwo + "'ni").isEmpty(), "33-syllable limit was ignored");
            });
            test("24_clipboard_cards_and_toolbar_duplicates", () -> {
                ClipboardHistoryStore store = new ClipboardHistoryStore(getTargetContext());
                java.util.List<ClipboardHistoryStore.Entry> original = store.snapshot();
                try {
                    SharedPreferences.Editor editor = preferences.edit().putBoolean("clipboard_enabled", true)
                            .putBoolean("toolbar", true).putBoolean("clipboard_capture_while_typing", false)
                            .remove("key_height");
                    String[] duplicates = {"😊", "剪贴板", "键盘布局", "剪贴板", "手写", "😊", ""};
                    for (int i = 0; i < duplicates.length; i++) editor.putString("toolbar_item_" + i, duplicates[i]);
                    editor.commit();
                    fresh(InputType.TYPE_CLASS_TEXT);
                    runOnMainSync(() -> ((android.content.ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE)).clearPrimaryClip());
                    store.clear();
                    store.add("会议记录\n第一行：确认需求\n第二行：完成布局\n第三行：检查交互");
                    store.add("常用地址：上海市示例路一号");
                    require(countNodes("😊") == 1 && countNodes("剪贴板") == 1, "Legacy toolbar duplicates still visible");
                    Rect layoutBefore = bounds("键盘布局");
                    click("剪贴板"); await("返回键盘");
                    require(bounds("键盘布局").equals(layoutBefore), "Layout entry moved when opening clipboard");
                    require(!has("字") && !has("ABC"), "Old keyboard switch footer remains");
                    screenshot("clipboard-cards");
                    click("粘贴剪贴板内容：常用地址：上海市示例路一号");
                    require(text().equals("常用地址：上海市示例路一号"), "Card did not paste intact");
                    click("剪贴板"); await("返回键盘");
                    click("剪贴板内容操作：常用地址：上海市示例路一号"); clickDialog("固定");
                    click("固定"); await("粘贴剪贴板内容：常用地址：上海市示例路一号，已固定");
                    require(!hasPrefix("粘贴剪贴板内容：会议记录"), "Pinned filter shows recent content");
                    screenshot("clipboard-pinned");
                    click("全部"); click("清空"); await("确定清空"); click("确定清空");
                    await("暂无剪贴板内容"); screenshot("clipboard-empty");
                    click("撤销清空"); await("粘贴剪贴板内容：常用地址：上海市示例路一号，已固定");
                    click("剪贴板内容操作：常用地址：上海市示例路一号"); clickDialog("删除");
                    require(!hasPrefix("粘贴剪贴板内容：常用地址"), "Delete left the card visible");
                    click("清空"); click("确定清空"); click("返回键盘"); await("q"); pause(10500);
                    click("剪贴板"); await("暂无剪贴板内容");
                    require(!waitNode("清空", false).isEnabled(), "Empty history clear action is enabled");
                    click("搜索"); clickDialog("取消"); await("返回键盘");
                    click("返回键盘"); await("q");
                    screenshot("toolbar-deduplicated");
                } finally {
                    store.clear(); store.restore(original);
                }
            });
            test("23_custom_toolbar_and_dismiss", () -> {
                String[] entries = {"😊", "剪贴板", "键盘布局", "手写", "工具", "设置", "符号"};
                SharedPreferences.Editor editor = preferences.edit().remove("key_height")
                        .remove("key_radius").putBoolean("toolbar", true);
                for (int i = 0; i < entries.length; i++) editor.putString("toolbar_item_" + i, entries[i]);
                editor.commit();
                fresh(InputType.TYPE_CLASS_TEXT);
                Rect last = null;
                for (String entry : entries) {
                    Rect current = bounds(entry);
                    require(current.width() > 0, "Toolbar control collapsed: " + entry);
                    if (last != null) require(current.left >= last.right, "Toolbar controls overlap: " + entry);
                    last = current;
                }
                require(bounds("收起键盘").left >= last.right, "Dismiss is not rightmost");
                screenshot("toolbar-seven");
                choose("中文 9键"); await("2 ABC"); screenshot("beautified-nine");
                click("工具"); await("复制"); click("工具"); await("2 ABC");
                choose("中文 26键"); awaitKey("q");
                click("收起键盘"); pause(500);
                require(!hasKey("q") && !has("收起键盘"), "Dismiss did not hide keyboard");
                editor = preferences.edit();
                for (int i = 0; i < entries.length; i++) editor.putString("toolbar_item_" + i, "");
                editor.commit();
                fresh(InputType.TYPE_CLASS_TEXT);
                require(has("收起键盘") && !has("键盘布局") && !has("😊"), "Empty slots were restored unexpectedly");
                screenshot("toolbar-empty");
                editor = preferences.edit();
                for (int i = 0; i < entries.length; i++) editor.remove("toolbar_item_" + i);
                editor.commit();
            });
            test("00_emoji_and_kaomoji", () -> {
                fresh(InputType.TYPE_CLASS_TEXT); click("😊"); await("笑脸"); click("颜文字");
                tap("(^_^)"); require("(^_^)".equals(text()), "Kaomoji not committed intact");
                await("(^_^)"); screenshot("kaomoji");
                android.content.SharedPreferences emojiPrefs = getTargetContext().getSharedPreferences("emoji", Context.MODE_PRIVATE);
                boolean favorite = emojiPrefs.getBoolean("favorite_(^_^)", false);
                emojiPrefs.edit().putBoolean("favorite_(^_^)", false).commit();
                touch(bounds("(^_^)"), 700, 350);
                await("(^_^)"); require(has("收藏"), "Favorites inaccessible");
                tap("(^_^)"); require("(^_^)(^_^)".equals(text()), "Favorite kaomoji not committed");
                emojiPrefs.edit().putBoolean("favorite_(^_^)", favorite).commit();
                click("最近"); await("(^_^)"); screenshot("emoji-recent");
                click("笑脸"); await("😀"); screenshot("emoji-faces");
                for (int i = 0; i < 32 && !has("😾"); i++) {
                    for (AccessibilityWindowInfo window : ui.getWindows()) {
                        if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) scrollContent(window.getRoot());
                    }
                    pause(350);
                }
                await("😾"); tap("😾");
                require(text().endsWith("😾"), "Scrolled emoji not committed");
                click("返回"); await("q");
            });
            test("01_qwerty_rapid_input", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                Map<Character, Rect> keys = letterBounds("nihaoshijie");
                for (int round = 0; round < 8; round++) {
                    clearField();
                    for (char c : "nihaoshijie".toCharArray()) touch(keys.get(c), 14, 25);
                    pause(400);
                    require("nihaoshijie".equals(text()), "Dropped/reordered letters: " + text());
                }
            });
            test("02_nine_candidates_and_geometry", () -> {
                fresh(InputType.TYPE_CLASS_TEXT); choose("中文 9键");
                Rect before = bounds("2 ABC");
                Rect delete = bounds("退格删除"), retry = bounds("重输"), split = bounds("分词");
                require(delete.top < retry.top && retry.top < split.top, "Side controls are not in Sogou order");
                require(Math.abs(delete.height() - split.height()) <= 2, "Side control heights differ");
                Rect symbols = bounds("符号"), digits = bounds("123"), space = bounds("空格"), language = bounds("中/英"), enter = bounds("回车");
                require(symbols.left < digits.left && digits.left < space.left
                        && space.left < language.left && language.left < enter.left, "Bottom keys are not in Sogou order");
                require(Math.abs(enter.top - space.top) <= 2 && enter.height() == space.height(), "Enter still spans upper rows");
                screenshot("sogou-nine");
                for (String key : new String[]{"6 MNO","4 GHI","4 GHI","2 ABC","6 MNO"}) tap(key);
                awaitPrefix("首选候选词 ");
                pause(900);
                require(has("候选词 你好") || has("首选候选词 你好"), "64426 did not expose 你好");
                Rect after = bounds("2 ABC");
                require(Math.abs(after.top - before.top) <= 2 && after.height() == before.height(), "Key geometry changed after candidates");
                tap("分词"); pause(400); screenshot("expanded");
                tap("返回"); require(has("2 ABC"), "Expanded candidates did not return to T9");
            });
            test("03_cross_panel_navigation", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                click("😊"); require(has("笑脸"), "Emoji did not open");
                click("返回"); await("键盘布局");
                click("键盘布局"); clickPrefix("数字与符号，");
                pause(500); require(has("1") && has("9") && !has("笑脸"), "Emoji state overrode numeric selection");
            });
            test("04_clipboard_opt_in_prompt_and_back", () -> {
                preferences.edit().putBoolean("clipboard_enabled", false).commit();
                fresh(InputType.TYPE_CLASS_TEXT);
                require(waitNode("剪贴板", false).performAction(AccessibilityNodeInfo.ACTION_CLICK),
                        "Clipboard toolbar action was not clickable");
                awaitPrefix("剪贴板历史默认关闭");
                require(waitNode("返回键盘", false).performAction(AccessibilityNodeInfo.ACTION_CLICK),
                        "Clipboard prompt back action was not clickable");
                await("q");
                require(!hasPrefix("剪贴板历史默认关闭"), "Back remained on clipboard opt-in prompt");
            });
            test("05_repeat_delete_release", () -> {
                fresh(InputType.TYPE_CLASS_TEXT); choose("中文 9键");
                runOnMainSync(() -> { field.setText("abcdefghijklmnopqrstuvwxyz0123456789"); field.setSelection(field.length()); });
                pause(300);
                touch(bounds("退格删除"), 1200, 250);
                int remaining = text().length();
                require(remaining <= 28, "Long hold did not repeat deletion: " + remaining);
                pause(800);
                require(text().length() == remaining, "Deletion continued after finger release");
                // Repeat while composing; asynchronous candidate refresh must not detach the held key.
                clearField(); choose("中文 9键");
                for (int i = 0; i < 8; i++) tap("2 ABC");
                touch(bounds("退格删除"), 1100, 250);
                tap("重输"); await("键盘布局");
            });
            test("06_editor_switches", () -> {
                fresh(InputType.TYPE_CLASS_TEXT); tap("n"); tap("i");
                EditText[] password = new EditText[1];
                runOnMainSync(() -> { password[0] = host.getWindow().getDecorView().findViewWithTag("number_password"); password[0].requestFocus(); });
                pause(600); require(has("1") && !has("q"), "Numeric password kept alphabet keyboard");
                require(!hasPrefix("首选候选词 "), "Password displayed previous candidates");
                runOnMainSync(() -> field.requestFocus()); pause(600); require(hasKey("q"), "Text keyboard did not recover");
                for (int type : new int[]{InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI}) {
                    fresh(type);
                    if (type == InputType.TYPE_CLASS_PHONE) require(has("1"), "Phone field did not use numeric keys");
                    else require(hasKey("q"), "Text variation has no keyboard");
                }
            });
            test("07_rotation", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                for (int i = 0; i < 3; i++) {
                    ui.setRotation(UiAutomation.ROTATION_FREEZE_90); pause(1100);
                    reopenEditorIfHidden();
                    Rect landscape = bounds("q");
                    require(landscape.width() > 0 && landscape.height() > 25, "Landscape keys collapsed");
                    Bitmap display = ui.takeScreenshot();
                    require(display != null, "No landscape screenshot");
                    int keyArea = bounds("回车").bottom - landscape.top;
                    int displayHeight = display.getHeight(); display.recycle();
                    require(keyArea < displayHeight * 0.65f, "Landscape keyboard obscures host editor: " + keyArea + "/" + displayHeight);
                    screenshot("landscape-" + i);
                    ui.setRotation(UiAutomation.ROTATION_FREEZE_0); pause(1100);
                    reopenEditorIfHidden();
                    require(has("q"), "Keyboard missing after rotation");
                }
            });
            test("08_panel_switch_stress", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                Rect initial = bounds("q");
                for (int i = 0; i < 25; i++) {
                    click("😊"); click("返回");
                    click("键盘布局"); clickPrefix("中文 9键，");
                    tap("123"); tap("返回");
                    await("2 ABC");
                    click("键盘布局"); clickPrefix("中文 26键，");
                    await("q"); pause(220);
                    Rect current = bounds("q");
                    require(current.equals(initial), "Keyboard bounds drift at cycle " + i
                            + ": initial=" + initial + ", current=" + current);
                }
            });
            test("09_background_restore", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                for (int i = 0; i < 5; i++) {
                    ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                    pause(350);
                    Intent intent = new Intent().setClassName(getTargetContext(), "com.example.offlineime.ImeTestActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    getTargetContext().startActivity(intent); pause(500);
                    runOnMainSync(() -> { field.requestFocus(); ((InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT); });
                    await("q");
                }
            });
            test("10_dark_and_height", () -> {
                preferences.edit().putBoolean("dark", true).putInt("key_height", 280).commit();
                fresh(InputType.TYPE_CLASS_TEXT); require(bounds("q").height() > 25, "Small keyboard collapsed"); screenshot("dark-small");
                preferences.edit().putBoolean("dark", false).putInt("key_height", 440).commit();
                fresh(InputType.TYPE_CLASS_TEXT); require(bounds("q").height() > 25, "Tall keyboard collapsed"); screenshot("light-tall");
            });
            test("11_service_unbind_rebind", () -> {
                preferences.edit().putBoolean("dark", false).putInt("key_height", 360).commit();
                fresh(InputType.TYPE_CLASS_TEXT);
                for (int i = 0; i < 5; i++) {
                    tap("n"); tap("i");
                    shell("ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME");
                    pause(450);
                    shell("ime set com.example.offlineime/.OfflineImeService");
                    pause(450);
                    runOnMainSync(() -> ((InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
                    await("q"); clearField();
                }
            });
            test("12_nine_gestures_and_default_geometry", () -> {
                preferences.edit().remove("key_height").commit();
                require(KeyboardSizing.height(preferences) == 300, "Default height differs from settings");
                fresh(InputType.TYPE_CLASS_TEXT); screenshot("default-qwerty");
                choose("中文 9键"); await("2 ABC"); screenshot("default-nine");
                Rect key = bounds("2 ABC"), bottom = bounds("空格");
                float density = getTargetContext().getResources().getDisplayMetrics().density;
                require(bottom.height() / density <= 54 && bottom.height() / density >= 40, "Bottom row stretched");
                require(Math.abs(key.height() - bounds("5 JKL").height()) <= 2, "T9 rows uneven");
                gesture(key, -36 * density, 80, false);
                require("2".equals(text()), "Up-swipe should insert one digit: " + text());
                gesture(key, -36 * density, 650, false);
                require("22".equals(text()), "Long-press then swipe duplicated digit: " + text());
                gesture(key, 0, 30, true);
                require("22".equals(text()), "Cancelled gesture committed input");
                // Tap the visual gap immediately outside the key, within its row.
                Rect gap = new Rect(key.right + 1, key.centerY(), key.right + 3, key.centerY() + 2);
                touch(gap, 650, 250);
                require("222".equals(text()), "Key gap swallowed or misrouted a press: " + text());
                tap("重输");
            });
            test("13_key_feedback_lifecycle", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                final KeyButton[] button = new KeyButton[1]; final int[] clicks = {0};
                runOnMainSync(() -> {
                    ((InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(), 0);
                    button[0] = new KeyButton(host, "2\nABC", false, KeyboardTheme.from(host, false));
                    button[0].setOnClickListener(v -> clicks[0]++);
                    host.setContentView(button[0]);
                });
                pause(250);
                keyEvent(button[0], MotionEvent.ACTION_DOWN); pause(55);
                boolean[] downFeedback = {false};
                runOnMainSync(() -> {
                    downFeedback[0] = button[0].isPressed()
                            && button[0].getScaleX() >= 0.97f && button[0].getScaleX() < 1f;
                });
                require(downFeedback[0], "DOWN did not set pressed feedback");
                keyEvent(button[0], MotionEvent.ACTION_CANCEL); pause(260);
                boolean[] cancelReset = {false};
                runOnMainSync(() -> cancelReset[0] = !button[0].isPressed()
                        && Math.abs(button[0].getScaleX() - 1) < .001f && clicks[0] == 0);
                require(cancelReset[0], "Cancel did not reset");
                for (int i = 0; i < 15; i++) {
                    keyEvent(button[0], MotionEvent.ACTION_DOWN); pause(12);
                    keyEvent(button[0], MotionEvent.ACTION_UP); pause(12);
                }
                pause(260);
                boolean[] tapsReset = {false};
                runOnMainSync(() -> tapsReset[0] = clicks[0] == 15
                        && Math.abs(button[0].getScaleX() - 1) < .001f);
                require(tapsReset[0], "Fast taps left animation or wrong count");
                runOnMainSync(() -> button[0].setRepeatDelete());
                keyEvent(button[0], MotionEvent.ACTION_DOWN);
                runOnMainSync(() -> require(clicks[0] == 16, "Delete did not respond on DOWN"));
                runOnMainSync(() -> host.setContentView(new android.widget.TextView(host)));
                pause(550);
                runOnMainSync(() -> require(clicks[0] == 16 && !button[0].isPressed() && button[0].getScaleX() == 1f, "Detached key repeated or stayed pressed"));
            });
            test("14_overlapping_fingers", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                Rect first = bounds("n"), second = bounds("i");
                long down = SystemClock.uptimeMillis();
                multiEvent(down, MotionEvent.ACTION_DOWN, 1, first, second);
                pause(30);
                multiEvent(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, first, second);
                pause(30);
                multiEvent(down, MotionEvent.ACTION_POINTER_UP, 2, first, second);
                pause(30);
                multiEvent(down, MotionEvent.ACTION_UP, 1, first, second);
                actions += 2; pause(400);
                require("ni".equals(text()), "Overlapping fingers dropped or duplicated text: " + text());
            });
            test("15_offline_handwriting_candidate", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                click("键盘布局"); click("手写输入");
                Rect area = bounds("手写输入区域");
                drawStroke(area, 0.15f, 0.50f, 0.85f, 0.50f);
                drawStroke(area, 0.50f, 0.15f, 0.50f, 0.85f);
                await("十");
                tap("十");
                require("十".equals(text()), "Handwriting candidate did not commit: " + text());
            });
            test("16_webview_composition_and_commit", () -> {
                freshWebView();
                tap("n"); tap("i"); await("你"); tap("你");
                require("你".equals(webText()), "WebView did not receive selected text: " + webText());
            });
            test("17_external_cursor_move_clears_composition", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                tap("n"); tap("i"); pause(250);
                require("ni".equals(text()), "Pinyin composing text was not present before cursor move: " + text());
                runOnMainSync(() -> field.setSelection(0));
                pause(300);
                tap("退格删除");
                require("ni".equals(text()), "Backspace edited stale composing text after cursor move: " + text());
            });
            test("18_landscape_picker_text_only_four_columns", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                ui.setRotation(UiAutomation.ROTATION_FREEZE_90); pause(1100);
                reopenEditorIfHidden();
                click("键盘布局"); pause(350);
                require(!has("键盘预览"), "Layout chooser should use text labels, not keyboard drawings");
                Rect qwertyZh = boundsPrefix("中文 26键，");
                Rect t9Zh = boundsPrefix("中文 9键，");
                Rect stroke = boundsPrefix("笔画输入，");
                Rect qwertyEn = boundsPrefix("English，");
                require(Math.abs(qwertyZh.top - t9Zh.top) <= 2
                                && Math.abs(t9Zh.top - stroke.top) <= 2
                                && Math.abs(stroke.top - qwertyEn.top) <= 2,
                        "Landscape first four layouts did not share one row");
                require(qwertyZh.left < t9Zh.left && t9Zh.left < stroke.left
                                && stroke.left < qwertyEn.left,
                        "Landscape layout order or columns are incorrect");
                screenshot("landscape-layout-text-only");
            });
            test("19_high_frequency_candidate_paging", () -> {
                preferences.edit().putString("candidate_count", "6").commit();
                fresh(InputType.TYPE_CLASS_TEXT); choose("中文 9键");
                for (String key : new String[]{"6 MNO", "4 GHI", "4 GHI", "2 ABC", "6 MNO"}) tap(key);
                awaitPrefix("首选候选词 ");
                require(has("你好"), "High-frequency first T9 candidate was missing");
                require(!has("1 你好"), "Candidate row should not show numeric labels");
                AccessibilityNodeInfo first = waitNode("首选候选词 ", true);
                CharSequence firstText = first.getText();
                require(firstText == null || !firstText.toString().matches(".*[a-zA-Z].*"),
                        "Candidate words should not carry per-word pinyin annotations: " + firstText);
                require(hasPrefix("选择读音 "),
                        "T9 pinyin paths were missing from the left vertical list");
                tap("展开候选词"); await("中文候选词");
                require(visibleCandidateWords().size() >= 6,
                        "Expanded candidate page did not use a dense candidate grid");
                for (AccessibilityWindowInfo window : ui.getWindows())
                    if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD)
                        scrollContent(window.getRoot());
                pause(250);
                require(has("返回"), "Expanded candidate page disappeared while scrolling");
            });
            test("20_landscape_floating_keyboard", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                ui.setRotation(UiAutomation.ROTATION_FREEZE_90); pause(1100);
                reopenEditorIfHidden();
                Rect firstKey = bounds("q"), lastKey = bounds("p");
                int screenWidth = getTargetContext().getResources().getDisplayMetrics().widthPixels;
                float density = getTargetContext().getResources().getDisplayMetrics().density;
                int sideMargin = Math.round(24 * density);
                require(firstKey.left >= sideMargin && lastKey.right <= screenWidth - sideMargin,
                        "Landscape keyboard should float inside the display edges: " + firstKey + " / " + lastKey);
                int centerOffset = Math.abs((firstKey.left + lastKey.right) / 2 - screenWidth / 2);
                require(centerOffset <= Math.round(32 * density),
                        "Landscape floating keyboard is not centered in the display's safe area: screen=" + screenWidth
                                + ", first=" + firstKey + ", last=" + lastKey + ", offset=" + centerOffset);
                screenshot("landscape-floating-keyboard");
            });
            test("21_pinyin_path_selects_phrase_without_candidate_readings", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                for (char key : "xian".toCharArray()) tap(String.valueOf(key));
                awaitPrefix("首选候选词 ");
                require(hasPrefix("选择拼音路径 xi·an"), "Ambiguous xian path was not offered separately");
                AccessibilityNodeInfo first = waitNode("首选候选词 ", true);
                CharSequence firstText = first.getText();
                require(firstText == null || !firstText.toString().matches(".*[a-zA-Z].*"),
                        "Candidate words should be plain Chinese text: " + firstText);
                click("选择拼音路径 xi·an");
                await("西安");
                require(has("西安"), "Selecting the xi·an path did not expose the phrase 西安");
            });
            test("22_hot_word_candidate_matrix", () -> {
                preferences.edit().putString("default_layout", "qwerty")
                        .putBoolean("full_pinyin", true).putBoolean("short_pinyin", true)
                        .putBoolean("learn_words", false).commit();
                String[][] cases = {
                        {"2", "简拼", "sb", "社保"},
                        {"2", "全拼", "shebao", "社保"},
                        {"3", "简拼", "sjb", "世界杯"},
                        {"3", "全拼", "shijiebei", "世界杯"},
                        {"4", "简拼", "rgzn", "人工智能"},
                        {"4", "全拼", "rengongzhineng", "人工智能"},
                        {"5", "简拼", "wxgzh", "微信公众号"},
                        {"5", "全拼", "weixingongzhonghao", "微信公众号"},
                };
                JSONArray matrix = new JSONArray();
                for (String[] sample : cases) {
                    fresh(InputType.TYPE_CLASS_TEXT);
                    for (char key : sample[2].toCharArray()) tap(String.valueOf(key));
                    awaitPrefix("首选候选词 ");
                    pause(900);
                    java.util.List<String> visible = visibleCandidateWords();
                    boolean present = visible.contains(sample[3]);
                    require(!visible.isEmpty(), sample[0] + "字" + sample[1]
                            + "输入 " + sample[2] + " 后候选栏没有候选词");
                    JSONObject item = new JSONObject();
                    item.put("length", sample[0]); item.put("inputMode", sample[1]);
                    item.put("input", sample[2]); item.put("expected", sample[3]);
                    item.put("presentInCandidateBar", present);
                    item.put("visibleCandidates", new JSONArray(visible)); matrix.put(item);
                    screenshot("hot-" + sample[0] + "-" + sample[1]);
                }
                write("hot-word-matrix.json", matrix.toString(2));
                for (int i = 0; i < matrix.length(); i++) {
                    JSONObject sample = matrix.getJSONObject(i);
                    require(sample.getBoolean("presentInCandidateBar"), sample.getString("input")
                            + " missed hot word " + sample.getString("expected")
                            + ": " + sample.getJSONArray("visibleCandidates"));
                }
            });
            test("29_simplified_traditional_stroke_and_mixed_english", () -> {
                HanConverter converter = HanConverter.get(getTargetContext());
                require("後臺頭髮😀ABC".equals(converter.toTraditional("后台头发😀ABC")),
                        "Bundled longest-match Simplified/Traditional conversion failed");
                StrokeInputDictionary strokes = StrokeInputDictionary.get(getTargetContext());
                java.util.List<PinyinDictionary.Entry> strokeValues = strokes.suggest(getTargetContext(), "1", 24);
                require(!strokeValues.isEmpty(), "Bundled stroke dictionary returned no candidates");
                boolean prefixStarted = false;
                for (PinyinDictionary.Entry entry : strokeValues) {
                    if (entry.matchTier == PinyinDictionary.TIER_PREFIX) prefixStarted = true;
                    else require(!prefixStarted, "Exact stroke candidate appeared after prefix candidates");
                }
                preferences.edit().putString("output_script", "traditional")
                        .putBoolean("mixed_english", true).commit();
                fresh(InputType.TYPE_CLASS_TEXT);
                for (char key : "hou".toCharArray()) tap(String.valueOf(key));
                await("後");
                clearField();
                for (char key : "hello".toCharArray()) tap(String.valueOf(key));
                await("hello");
                preferences.edit().putBoolean("mixed_english", false).commit();
                fresh(InputType.TYPE_CLASS_TEXT);
                for (char key : "hello".toCharArray()) tap(String.valueOf(key));
                pause(700);
                require(!has("hello"), "English candidate remained after mixed input was disabled");
                preferences.edit().putString("output_script", "simplified").commit();
                fresh(InputType.TYPE_CLASS_TEXT); choose("笔画输入"); await("一 横");
                tap("一 横"); awaitPrefix("首选候选词 ");
                require(!visibleCandidateWords().isEmpty(), "Stroke keyboard did not publish candidates");
                screenshot("new-stroke-and-script");
            });
            test("30_editor_specific_layout_matrix", () -> {
                fresh(InputType.TYPE_CLASS_PHONE); require(has("+") && has("#"), "Phone layout keys missing");
                fresh(InputType.TYPE_CLASS_NUMBER); require(has("1") && has("."), "Number layout keys missing");
                fresh(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                require(has("."), "Decimal layout key missing");
                fresh(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                require(has("-"), "Signed-number layout key missing");
                fresh(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
                require(has("/") && has("-"), "Date layout keys missing");
                fresh(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
                require(has(":"), "Time layout key missing");
                fresh(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                require(has("@") && has("."), "Email layout shortcuts missing");
                fresh(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                require(has("/") && has(".com"), "URI layout shortcuts missing");
                screenshot("editor-layout-uri");
            });
            test("31_clipboard_policy_and_batch_delete", () -> {
                ClipboardHistoryStore store = new ClipboardHistoryStore(getTargetContext());
                java.util.List<ClipboardHistoryStore.Entry> original = store.snapshot();
                try {
                    long now = System.currentTimeMillis();
                    preferences.edit().putBoolean("clipboard_enabled", true)
                            .putString("clipboard_limit", "10")
                            .putString("clipboard_retention_days", "1").commit();
                    store.clear();
                    java.util.ArrayList<ClipboardHistoryStore.Entry> seeded = new java.util.ArrayList<>();
                    seeded.add(new ClipboardHistoryStore.Entry("过期固定", now - 40L * 86400000L, true));
                    seeded.add(new ClipboardHistoryStore.Entry("已经过期", now - 2L * 86400000L, false));
                    for (int i = 0; i < 12; i++) seeded.add(new ClipboardHistoryStore.Entry("记录" + i, now - i * 1000L, false));
                    store.restore(seeded);
                    java.util.List<ClipboardHistoryStore.Entry> kept = store.listEntries();
                    require(kept.size() == 11, "Clipboard limit or pinned retention failed: " + kept.size());
                    require(kept.stream().anyMatch(entry -> entry.pinned && "过期固定".equals(entry.text)),
                            "Expired pinned clipboard entry was removed");
                    require(kept.stream().noneMatch(entry -> "已经过期".equals(entry.text)),
                            "Expired ordinary clipboard entry survived");
                    runOnMainSync(() -> ((android.content.ClipboardManager) getTargetContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE)).clearPrimaryClip());
                    store.clear(); store.add("批量甲"); store.add("批量乙"); store.add("批量丙");
                    fresh(InputType.TYPE_CLASS_TEXT); click("剪贴板"); await("多选"); click("多选");
                    tap("批量乙"); tap("批量丙"); await("删除(2)"); click("删除(2)");
                    java.util.List<ClipboardHistoryStore.Entry> after = store.listEntries();
                    require(after.stream().anyMatch(entry -> "批量甲".equals(entry.text))
                                    && after.stream().noneMatch(entry -> "批量乙".equals(entry.text)
                                    || "批量丙".equals(entry.text)),
                            "Clipboard batch deletion removed the wrong entries");
                    screenshot("clipboard-batch-delete");
                } finally {
                    store.clear(); store.restore(original);
                }
            });
            test("32_space_trackpad_and_selection", () -> {
                fresh(InputType.TYPE_CLASS_TEXT);
                runOnMainSync(() -> { field.setText("abcdef"); field.setSelection(6); });
                drag(bounds("空格"), -180, 0, 220);
                int moved = selectionStart();
                require(moved < 6, "Space trackpad did not move the caret: " + moved);
                runOnMainSync(() -> field.setSelection(6));
                pause(180);
                drag(bounds("空格"), 0, -140, 180);
                drag(bounds("空格"), -150, 0, 180);
                int start = selectionStart(), end = selectionEnd();
                require(start < end && end == 6, "Space selection mode did not extend selection: " + start + ".." + end);
                screenshot("space-trackpad-selection");
                fresh(InputType.TYPE_CLASS_TEXT);
                verifyHeldSpaceCursor("qwerty");
                choose("中文 9键");
                verifyHeldSpaceCursor("nine");
                tap("中/英"); awaitKey("q");
                verifyHeldSpaceCursor("english");
                runOnMainSync(() -> { field.setText("abc"); field.setSelection(1); });
                pause(180); tap("空格");
                require("a bc".equals(fieldText()), "Normal Space tap no longer inserts a space: " + fieldText());
            });
            test("36_editor_direction_pad_and_actions", () -> {
                fresh(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                runOnMainSync(() -> {
                    field.setSingleLine(false); field.setMaxLines(3);
                    field.setText("abc\ndef\nghi"); field.setSelection(5);
                });
                pause(200); click("工具"); await("全选");
                Rect left = bounds("←"), right = bounds("→"), up = bounds("↑"), down = bounds("↓");
                require(up.centerX() > left.centerX() && up.centerX() < right.centerX()
                                && Math.abs(up.centerX() - down.centerX()) <= 2
                                && up.bottom <= left.top && down.top >= left.bottom
                                && Math.abs(left.centerY() - right.centerY()) <= 2,
                        "Direction controls do not form a cross");
                AccessibilityNodeInfo actionArea = waitNode("文本编辑操作区", false);
                for (String action : new String[]{"全选", "复制", "剪切", "粘贴", "删除", "⌫词",
                        "行首", "行尾", "选择", "撤销", "重做", "剪贴板", "返回"}) {
                    AccessibilityNodeInfo control = walk(actionArea, action, false);
                    require(control != null, "Missing editing action: " + action);
                    Rect actionBounds = new Rect(); control.getBoundsInScreen(actionBounds);
                    require(actionBounds.left > right.right, "Editing action is not to the right: " + action);
                }
                screenshot("editor-direction-pad");
                tap("←"); require(selectionStart() == 4, "Left arrow did not move one character");
                tap("→"); require(selectionStart() == 5, "Right arrow did not move one character");
                tap("↑"); require(selectionStart() < 4, "Up arrow did not move to previous line");
                tap("↓"); require(selectionStart() >= 4 && selectionStart() < 8, "Down arrow did not move to next line");
                tap("全选");
                require(selectionStart() == 0 && selectionEnd() == fieldText().length(), "Select all lost its selection");
                tap("复制"); tap("删除"); require(fieldText().isEmpty(), "Delete did not remove the selected text");
                tap("粘贴"); require("abc\ndef\nghi".equals(fieldText()), "Paste did not restore copied text");
                tap("全选"); tap("剪切"); require(fieldText().isEmpty(), "Cut did not remove selected text");
                tap("粘贴"); tap("全选"); tap("⌫词");
                require(fieldText().isEmpty(), "Word deletion did not remove selected text");
                screenshot("editor-selection-delete");
                tap("返回"); awaitKey("q");
            });
            test("33_emoji_toolbar_and_candidate_sizing", () -> {
                preferences.edit().putString("candidate_font_size", "15")
                        .putString("candidate_bar_height", "40")
                        .putString("expanded_candidate_density", "compact").commit();
                fresh(InputType.TYPE_CLASS_TEXT);
                Rect toolbar = bounds("😊"); click("😊"); await("最近");
                Rect emojiNavigation = bounds("最近");
                require(Math.abs(toolbar.top - emojiNavigation.top) <= Math.round(8 * density()),
                        "Emoji navigation did not occupy the toolbar row");
                click("返回"); await("q"); for (char key : "ni".toCharArray()) tap(String.valueOf(key));
                Rect compact = boundsPrefix("首选候选词 ");
                preferences.edit().putString("candidate_font_size", "21")
                        .putString("candidate_bar_height", "52")
                        .putString("expanded_candidate_density", "comfortable").commit();
                fresh(InputType.TYPE_CLASS_TEXT); for (char key : "ni".toCharArray()) tap(String.valueOf(key));
                Rect tall = boundsPrefix("首选候选词 ");
                require(tall.height() >= compact.height(), "Candidate height preference was ignored");
                require(bounds("q").top > tall.bottom, "Candidate bar overlaps the keyboard rows");
                for (String haptic : new String[]{"off", "light", "standard", "strong"}) {
                    preferences.edit().putString("haptic_strength", haptic).commit(); tap("q"); tap("退格删除");
                }
                for (String sound : new String[]{"off", "low", "medium", "high"}) {
                    preferences.edit().putString("sound_volume", sound).commit(); tap("q"); tap("退格删除");
                }
                screenshot("candidate-size-comfortable");
            });
            test("34_settings_secondary_page_lifecycle", () -> {
                if (host != null) runOnMainSync(() -> host.finish());
                host = startActivitySync(new Intent().setClassName(getTargetContext(),
                        "com.example.offlineime.SettingsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                clickAppPrefix("输入体验"); awaitApp("中文输出");
                waitAppNode("简体中文", true); clickAppPrefix("简体中文");
                awaitApp("繁体中文"); clickApp("繁体中文");
                ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); pause(250);
                require(appHasPrefix("输入体验"), "Settings secondary page did not return to the main page");
                clickAppPrefix("剪贴板"); awaitApp("普通历史数量");
                ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); pause(250);
                require(appHasPrefix("剪贴板"), "Settings clipboard page crashed or failed to return");
                screenshot("settings-secondary-pages");
            });
            test("35_latency_budget_and_package_privacy", () -> {
                long loadStarted = SystemClock.elapsedRealtime();
                PinyinDictionary dictionary = new PinyinDictionary(getTargetContext().getAssets(), getTargetContext());
                dictionary.load();
                long loadMs = SystemClock.elapsedRealtime() - loadStarted;
                java.util.ArrayList<Long> timings = new java.util.ArrayList<>();
                String[] queries = {"nihao", "zhongguo", "gongzuorenyuan", "weixingongzhonghao", "xi'an"};
                for (int i = 0; i < 100; i++) {
                    long started = SystemClock.elapsedRealtime();
                    dictionary.suggest(queries[i % queries.length]);
                    timings.add(SystemClock.elapsedRealtime() - started);
                }
                java.util.Collections.sort(timings);
                long p95 = timings.get(94);
                fresh(InputType.TYPE_CLASS_TEXT); long uiStarted = SystemClock.elapsedRealtime();
                for (char key : "nihao".toCharArray()) tap(String.valueOf(key));
                await("你好"); long uiMs = SystemClock.elapsedRealtime() - uiStarted;
                JSONObject metrics = new JSONObject(); metrics.put("dictionaryLoadMs", loadMs);
                metrics.put("queryP95Ms", p95); metrics.put("uiCandidateMs", uiMs);
                write("latency-budget.json", metrics.toString(2));
                require(loadMs <= 5000, "Dictionary cold load exceeded 5 seconds: " + loadMs);
                require(p95 <= 300, "Candidate query P95 exceeded 300 ms: " + p95);
                require(uiMs <= 2500, "UI candidate path exceeded 2.5 seconds: " + uiMs);
                android.content.pm.PackageInfo packageInfo = getTargetContext().getPackageManager()
                        .getPackageInfo(getTargetContext().getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
                java.util.List<String> permissions = packageInfo.requestedPermissions == null
                        ? java.util.Collections.emptyList() : java.util.Arrays.asList(packageInfo.requestedPermissions);
                require(!permissions.contains(android.Manifest.permission.INTERNET), "APK unexpectedly requests INTERNET");
                require(!permissions.contains(android.Manifest.permission.RECORD_AUDIO), "APK unexpectedly requests microphone");
                require(!permissions.contains(android.Manifest.permission.READ_CONTACTS), "APK unexpectedly requests contacts");
            });
        } catch (Throwable error) {
            failures++;
            summary.putString("fatal", android.util.Log.getStackTraceString(error));
        } finally {
            if (ui != null) ui.setRotation(UiAutomation.ROTATION_UNFREEZE);
            if (saved != null) {
                restorePreferences(preferences, saved);
            }
            if (savedKeyboardState != null && keyboardPreferences != null)
                restorePreferences(keyboardPreferences, savedKeyboardState);
            try {
                JSONObject report = new JSONObject(); report.put("phase", phase); report.put("group", requestedGroup);
                report.put("failures", failures);
                report.put("touchActions", actions); report.put("cases", results); report.put("fatal", summary.getString("fatal", ""));
                report.put("rotationReactivations", rotationReactivations);
                write("results.json", report.toString(2));
            } catch (Exception ignored) {}
            summary.putInt("failures", failures); summary.putInt("touchActions", actions);
            summary.putString("stream", "Frontend stability: " + results.length() + " cases, " + failures + " failures, " + actions + " touch actions.\n");
            finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, summary);
        }
    }

    private void multiEvent(long down, int action, int count, Rect first, Rect second) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            int id = count == 1 && action == MotionEvent.ACTION_UP ? 1 : i;
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = id;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            Rect rect = id == 0 ? first : second;
            coords[i].x = rect.centerX(); coords[i].y = rect.centerY(); coords[i].pressure = 1; coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count,
                properties, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        ui.injectInputEvent(event, true); event.recycle();
    }
    private void keyEvent(KeyButton button, int action) {
        runOnMainSync(() -> {
            long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(now, now, action, button.getWidth() / 2f, button.getHeight() / 2f, 0);
            button.dispatchTouchEvent(event); event.recycle();
        });
    }
    private void gesture(Rect rect, float deltaY, long hold, boolean cancel) {
        long down = SystemClock.uptimeMillis();
        inject(down, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY()); pause(hold);
        if (deltaY != 0) inject(down, MotionEvent.ACTION_MOVE, rect.centerX(), rect.centerY() + deltaY);
        inject(down, cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, rect.centerX(), rect.centerY() + deltaY);
        actions++; pause(300);
    }
    private void drag(Rect rect, float deltaX, float deltaY, long settle) {
        long down = SystemClock.uptimeMillis();
        inject(down, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY());
        for (int step = 1; step <= 6; step++) {
            float ratio = step / 6f;
            inject(down, MotionEvent.ACTION_MOVE, rect.centerX() + deltaX * ratio,
                    rect.centerY() + deltaY * ratio);
            pause(18);
        }
        inject(down, MotionEvent.ACTION_UP, rect.centerX() + deltaX, rect.centerY() + deltaY);
        actions++; pause(settle);
    }
    private void inject(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN); ui.injectInputEvent(event, true); event.recycle();
    }
    private void verifyHeldSpaceCursor(String layout) {
        runOnMainSync(() -> { field.setText("abcdefghij"); field.setSelection(5); });
        pause(220);
        Rect space = bounds("空格");
        long down = SystemClock.uptimeMillis();
        inject(down, MotionEvent.ACTION_DOWN, space.centerX(), space.centerY());
        pause(android.view.ViewConfiguration.getLongPressTimeout() + 150L);
        require("abcdefghij".equals(fieldText()), "Holding Space inserted text in " + layout);
        float offset = 25 * density();
        inject(down, MotionEvent.ACTION_MOVE, space.centerX() - offset, space.centerY());
        pause(180);
        require(selectionStart() == 3 && selectionEnd() == 3, "Held Space left drag failed in " + layout);
        inject(down, MotionEvent.ACTION_MOVE, space.centerX() + offset, space.centerY());
        pause(180);
        require(selectionStart() == 7 && selectionEnd() == 7, "Held Space reversed/right drag failed in " + layout);
        float outsideX = Math.max(8, space.left - 20 * density());
        inject(down, MotionEvent.ACTION_MOVE, outsideX, space.centerY());
        pause(180);
        require(selectionStart() < 7, "Cursor drag stopped outside Space in " + layout);
        inject(down, MotionEvent.ACTION_UP, outsideX, space.centerY());
        actions++; pause(200);
        require("abcdefghij".equals(fieldText()), "Cursor gesture committed a space in " + layout);
        gesture(space, 0, android.view.ViewConfiguration.getLongPressTimeout() + 100L, true);
        require("abcdefghij".equals(fieldText()), "Cancelled Space hold inserted text in " + layout);
        screenshot("space-hold-" + layout);
    }
    private boolean scrollContent(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if ("android.widget.ScrollView".contentEquals(node.getClassName()))
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        for (int i = 0; i < node.getChildCount(); i++) if (scrollContent(node.getChild(i))) return true;
        return false;
    }
    private interface Checked { void run() throws Exception; }
    private String fieldText() {
        java.util.concurrent.atomic.AtomicReference<String> value = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> value.set(field.getText().toString())); return value.get();
    }
    private void test(String name, Checked body) throws Exception {
        if (onlyCase != null && !onlyCase.equals(name)) return;
        String group = testGroup(name);
        if (!"all".equals(requestedGroup) && !group.equals(requestedGroup)) return;
        restorePreferences(baseline);
        keyboardPreferences.edit().clear().commit();
        JSONObject item = new JSONObject(); item.put("name", name); item.put("group", group);
        long started = SystemClock.uptimeMillis();
        try { body.run(); item.put("status", "PASS"); }
        catch (Throwable error) { failures++; item.put("status", "FAIL"); item.put("error", error.toString()); screenshot(name + "-failure"); }
        finally {
            // Dismiss a path/menu popup or the current input view so one failed
            // interaction cannot obscure every later case. Each case relaunches
            // the editor it needs.
            ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            pause(180);
        }
        item.put("durationMs", SystemClock.uptimeMillis() - started); results.put(item);
        Bundle progress = new Bundle(); progress.putString("stream", item.toString() + "\n"); sendStatus(0, progress);
    }
    private String testGroup(String name) {
        if (name.startsWith("26_") || name.startsWith("25_") || name.startsWith("27_")
                || name.startsWith("19_") || name.startsWith("21_") || name.startsWith("22_")) return "input";
        if (name.startsWith("05_") || name.startsWith("07_") || name.startsWith("08_")
                || name.startsWith("09_") || name.startsWith("11_") || name.startsWith("13_")
                || name.startsWith("14_") || name.startsWith("28_")) return "stability";
        if (name.startsWith("00_") || name.startsWith("03_") || name.startsWith("04_")
                || name.startsWith("15_") || name.startsWith("18_") || name.startsWith("20_")
                || name.startsWith("23_") || name.startsWith("24_")) return "panels";
        if (name.startsWith("35_")) return "performance";
        if (name.matches("(29|30|31|32|33|34|36)_.*")) return "features";
        return "smoke";
    }
    private void restorePreferences(Map<String, ?> values) {
        restorePreferences(preferences, values);
    }
    private void restorePreferences(SharedPreferences target, Map<String, ?> values) {
        if (values == null || target == null) return;
        SharedPreferences.Editor editor = target.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof java.util.Set) editor.putStringSet(key, (java.util.Set<String>) value);
        }
        editor.commit();
    }
    private void fresh(int type) {
        ui.setRotation(UiAutomation.ROTATION_FREEZE_0);
        if (host != null) runOnMainSync(() -> host.finish());
        Intent intent = new Intent().setClassName(getTargetContext(), "com.example.offlineime.ImeTestActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        host = startActivitySync(intent);
        runOnMainSync(() -> {
            field = host.getWindow().getDecorView().findViewWithTag("normal_text");
            field.setInputType(type); field.setText(""); field.requestFocus();
            ((InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
        int inputClass = type & InputType.TYPE_MASK_CLASS;
        String readyKey = inputClass == InputType.TYPE_CLASS_PHONE
                || inputClass == InputType.TYPE_CLASS_NUMBER
                || inputClass == InputType.TYPE_CLASS_DATETIME ? "1" : "q";
        pause(850);
        try {
            waitKey(readyKey);
        } catch (AssertionError notReady) {
            runOnMainSync(() -> {
                field.requestFocus();
                InputMethodManager manager = (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
                manager.restartInput(field);
                manager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            });
            pause(900);
            waitKey(readyKey);
        }
        pause(350);
    }
    private void freshWebView() {
        ui.setRotation(UiAutomation.ROTATION_FREEZE_0);
        if (host != null) runOnMainSync(() -> host.finish());
        Intent intent = new Intent().setClassName(getTargetContext(), "com.example.offlineime.ImeTestWebViewActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        host = startActivitySync(intent);
        runOnMainSync(() -> {
            WebView editor = host.getWindow().getDecorView().findViewWithTag("web_editor");
            require(editor != null, "WebView host was not created");
            editor.requestFocus();
            editor.evaluateJavascript("document.getElementById('web_text').focus()", null);
            ((InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        });
        pause(1200);
        await("q");
    }
    private String webText() throws Exception {
        AtomicReference<String> result = new AtomicReference<>("");
        CountDownLatch complete = new CountDownLatch(1);
        runOnMainSync(() -> {
            WebView editor = host.getWindow().getDecorView().findViewWithTag("web_editor");
            editor.evaluateJavascript("document.getElementById('web_text').value", value -> {
                try { result.set(new org.json.JSONArray("[" + value + "]").getString(0)); }
                catch (Exception error) { result.set("<decode error: " + error.getClass().getSimpleName() + ">"); }
                complete.countDown();
            });
        });
        if (!complete.await(3, TimeUnit.SECONDS)) throw new AssertionError("WebView value read timed out");
        return result.get();
    }
    private int selectionStart() {
        int[] value = {-1}; runOnMainSync(() -> value[0] = field.getSelectionStart()); return value[0];
    }
    private int selectionEnd() {
        int[] value = {-1}; runOnMainSync(() -> value[0] = field.getSelectionEnd()); return value[0];
    }
    private float density() { return getTargetContext().getResources().getDisplayMetrics().density; }
    private void drawStroke(Rect area, float startX, float startY, float endX, float endY) {
        long down = SystemClock.uptimeMillis();
        float x1 = area.left + area.width() * startX, y1 = area.top + area.height() * startY;
        float x2 = area.left + area.width() * endX, y2 = area.top + area.height() * endY;
        inject(down, MotionEvent.ACTION_DOWN, x1, y1); pause(25);
        for (int step = 1; step < 8; step++) {
            float ratio = step / 8f;
            inject(down, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * ratio, y1 + (y2 - y1) * ratio);
            pause(8);
        }
        inject(down, MotionEvent.ACTION_UP, x2, y2);
        actions++; pause(420);
    }
    private void clearField() { runOnMainSync(() -> { field.setText(""); ((InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(field); }); pause(350); }
    private String text() { String[] value = {""}; runOnMainSync(() -> value[0] = field.getText().toString()); return value[0]; }
    private void choose(String name) { click("键盘布局"); clickPrefix(name + "，"); }
    private void reopenEditorIfHidden() {
        long until = SystemClock.uptimeMillis() + 4500;
        do {
            if (has("q")) return;
            // On this API 37 emulator, activity recreation can temporarily hide
            // the editor before the input view is restored. Retry across frames.
            for (AccessibilityWindowInfo window : ui.getWindows()) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo node = walk(window.getRoot(), "normal_text", false);
                if (node != null) {
                    Rect r = new Rect(); node.getBoundsInScreen(r); touch(r, 25, 550);
                    rotationReactivations++;
                    if (has("q")) return;
                }
            }
            pause(180);
        } while (SystemClock.uptimeMillis() < until);
        // Some API 37 emulator rotations remove the test host window instead of
        // recreating it. Relaunch a fresh editor in the current orientation so
        // the keyboard itself can still be verified after rotation.
        if (host != null) runOnMainSync(() -> host.finish());
        Intent intent = new Intent().setClassName(getTargetContext(), "com.example.offlineime.ImeTestActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        host = startActivitySync(intent);
        runOnMainSync(() -> {
            field = host.getWindow().getDecorView().findViewWithTag("normal_text");
            field.setInputType(InputType.TYPE_CLASS_TEXT); field.setText(""); field.requestFocus();
            ((InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
        pause(850); await("q"); rotationReactivations++;
    }
    private Map<Character, Rect> letterBounds(String letters) { Map<Character, Rect> map = new LinkedHashMap<>(); for (char c : letters.toCharArray()) map.put(c, bounds(String.valueOf(c))); return map; }
    private java.util.List<String> visibleCandidateWords() {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && "com.example.offlineime".contentEquals(root.getPackageName()))
                collectCandidateWords(root, values);
        }
        return values;
    }
    private void collectCandidateWords(AccessibilityNodeInfo node, java.util.List<String> values) {
        if (node == null) return;
        CharSequence description = node.getContentDescription();
        if (node.isVisibleToUser() && description != null) {
            String label = description.toString();
            if (label.startsWith("首选候选词 ")) values.add(label.substring("首选候选词 ".length()));
            else if (label.startsWith("候选词 ")) values.add(label.substring("候选词 ".length()));
        }
        for (int i = 0; i < node.getChildCount(); i++) collectCandidateWords(node.getChild(i), values);
    }
    private boolean has(String label) { return find(label, false) != null; }
    private int countNodes(String label) {
        int total = 0;
        for (AccessibilityWindowInfo window : ui.getWindows())
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) total += countNodes(window.getRoot(), label);
        return total;
    }
    private int countNodes(AccessibilityNodeInfo node, String label) {
        if (node == null) return 0;
        int count = label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()) ? 1 : 0;
        for (int i = 0; i < node.getChildCount(); i++) count += countNodes(node.getChild(i), label);
        return count;
    }
    private boolean hasPrefix(String label) { return find(label, true) != null; }
    private void await(String label) { waitNode(label, false); }
    private void awaitPrefix(String label) { waitNode(label, true); }
    private AccessibilityNodeInfo waitNode(String label, boolean prefix) {
        long until = SystemClock.uptimeMillis() + 7000;
        do { AccessibilityNodeInfo node = find(label, prefix); if (node != null) return node; pause(70); } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Missing visible IME control: " + label);
    }
    private AccessibilityNodeInfo find(String label, boolean prefix) {
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !"com.example.offlineime".contentEquals(root.getPackageName())) continue;
            AccessibilityNodeInfo node = walkDescription(root, label, prefix);
            if (node == null) node = walk(root, label, prefix);
            if (node != null) return node;
        }
        return null;
    }
    private AccessibilityNodeInfo walk(AccessibilityNodeInfo node, String label, boolean prefix) {
        if (node == null) return null;
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        String text = node.getText() == null ? "" : node.getText().toString();
        if (node.isVisibleToUser() && (prefix ? desc.startsWith(label) || text.startsWith(label) : desc.equals(label) || text.equals(label))) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = walk(node.getChild(i), label, prefix); if (found != null) return found; }
        return null;
    }
    private AccessibilityNodeInfo walkDescription(AccessibilityNodeInfo node, String label, boolean prefix) {
        if (node == null) return null;
        String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        if (node.isVisibleToUser() && (prefix ? description.startsWith(label) : description.equals(label))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = walkDescription(node.getChild(i), label, prefix);
            if (found != null) return found;
        }
        return null;
    }
    private AccessibilityNodeInfo findDescription(String label, boolean prefix) {
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !"com.example.offlineime".contentEquals(root.getPackageName())) continue;
            AccessibilityNodeInfo node = walkDescription(root, label, prefix);
            if (node != null) return node;
        }
        return null;
    }
    private AccessibilityNodeInfo waitKey(String label) {
        long until = SystemClock.uptimeMillis() + 7000;
        do {
            AccessibilityNodeInfo node = findDescription(label, false);
            if (node == null) node = findDescription(label + "，", true);
            if (node != null) return node;
            pause(70);
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Missing visible keyboard key: " + label);
    }
    private void awaitKey(String label) { waitKey(label); }
    private boolean hasKey(String label) {
        return findDescription(label, false) != null || findDescription(label + "，", true) != null;
    }
    private Rect bounds(String label) {
        Rect result = new Rect();
        AccessibilityNodeInfo node = findDescription(label, false);
        if (node == null) node = findDescription(label + "，", true);
        if (node == null) node = waitNode(label, false);
        node.getBoundsInScreen(result);
        return result;
    }
    private Rect boundsPrefix(String label) { Rect r = new Rect(); waitNode(label, true).getBoundsInScreen(r); return r; }
    private void click(String label) { clickNode(waitNode(label, false)); }
    private void clickDialog(String label) {
        long until = SystemClock.uptimeMillis() + 7000;
        do {
            for (AccessibilityWindowInfo window : ui.getWindows()) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null || !"com.example.offlineime".contentEquals(root.getPackageName())) continue;
                AccessibilityNodeInfo node = walk(root, label, false);
                if (node != null) { Rect target = new Rect(); node.getBoundsInScreen(target); touch(target, 25, 260); return; }
            }
            pause(70);
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Missing application menu/dialog item: " + label);
    }
    private boolean appHasPrefix(String label) { return findApp(label, true) != null; }
    private void awaitApp(String label) { waitAppNode(label, false); }
    private void clickApp(String label) { clickAppNode(waitAppNode(label, false)); }
    private void clickAppPrefix(String label) { clickAppNode(waitAppNode(label, true)); }
    private AccessibilityNodeInfo waitAppNode(String label, boolean prefix) {
        long until = SystemClock.elapsedRealtime() + 7000;
        do {
            AccessibilityNodeInfo node = findApp(label, prefix);
            if (node != null) return node;
            pause(70);
        } while (SystemClock.elapsedRealtime() < until);
        throw new AssertionError("Missing application control: " + label);
    }
    private AccessibilityNodeInfo findApp(String label, boolean prefix) {
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !"com.example.offlineime".contentEquals(root.getPackageName())) continue;
            AccessibilityNodeInfo node = walkDescription(root, label, prefix);
            if (node == null) node = walk(root, label, prefix);
            if (node != null) return node;
        }
        return null;
    }
    private void clickAppNode(AccessibilityNodeInfo node) {
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Rect target = new Rect(); node.getBoundsInScreen(target); touch(target, 25, 250);
        } else { actions++; pause(250); }
    }
    private void clickPrefix(String label) { clickNode(waitNode(label, true)); }
    private void longClickPrefix(String label) {
        AccessibilityNodeInfo node = waitNode(label, true);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            throw new AssertionError("IME control did not accept accessibility long click: " + label);
        actions++; pause(300);
    }
    private void clickNode(AccessibilityNodeInfo node) {
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            throw new AssertionError("IME control did not accept accessibility click: " + node.getContentDescription());
        actions++; pause(260);
    }
    private void tap(String label) { touch(bounds(label), 25, 160); }
    private void tapPrefix(String label) { Rect r = new Rect(); waitNode(label, true).getBoundsInScreen(r); touch(r, 25, 200); }
    private void touch(Rect r, long hold, long settle) {
        long down = SystemClock.uptimeMillis();
        MotionEvent start = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0);
        start.setSource(InputDevice.SOURCE_TOUCHSCREEN); ui.injectInputEvent(start, true); start.recycle();
        pause(hold);
        MotionEvent end = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0);
        end.setSource(InputDevice.SOURCE_TOUCHSCREEN); ui.injectInputEvent(end, true); end.recycle(); actions++; pause(settle);
    }
    private void screenshot(String name) {
        try { Bitmap bitmap = ui.takeScreenshot(); if (bitmap == null) return; try (FileOutputStream stream = new FileOutputStream(new File(out, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); } bitmap.recycle(); } catch (Exception ignored) {}
    }
    private void write(String name, String value) throws Exception { try (FileOutputStream stream = new FileOutputStream(new File(out, name))) { stream.write(value.getBytes(StandardCharsets.UTF_8)); } }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void pause(long ms) { SystemClock.sleep(ms); }
    private void shell(String command) {
        try (android.os.ParcelFileDescriptor.AutoCloseInputStream stream =
                     new android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))) {
            byte[] buffer = new byte[1024]; while (stream.read(buffer) != -1) { }
        } catch (Exception error) { throw new AssertionError(command, error); }
    }
}
