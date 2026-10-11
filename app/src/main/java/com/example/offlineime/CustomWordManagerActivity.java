package com.example.offlineime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Searchable local dictionary with independent reading, alias, pin and backup controls. */
public final class CustomWordManagerActivity extends Activity {
    private static final int EXPORT = 31, IMPORT = 32;
    private UiTokens.Palette palette;
    private EditText search;
    private LinearLayout rows;
    private TextView summary, undo;
    private Spinner filter;
    private Map<String, CustomWordStore.Word> custom = Collections.emptyMap();
    private Map<String, List<String>> learned = Collections.emptyMap();
    private Set<String> legacyPinned = Collections.emptySet();
    private final List<String> visible = new ArrayList<>();
    private int pageSize = 50, requestVersion;
    private boolean busy, initialAction;
    private CustomWordStore.Change lastChange;
    private String exportData;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable expireUndo = () -> { lastChange = null; if (undo != null) undo.setVisibility(View.GONE); };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); palette = UiTokens.palette(this); setTitle("自定义词与学习词");
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(palette.background); page.setPadding(dp(16), dp(12), dp(16), dp(8));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom()); return insets;
        });
        TextView title = label("自定义词与学习词", 21); page.addView(title);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        button(actions, "添加词", () -> editWord("", "")); button(actions, "备份", this::backupMenu);
        button(actions, "清理", this::clearMenu); button(actions, "返回", this::finish); page.addView(actions);
        search = input("搜索汉字、拼音或别名", ""); page.addView(search);
        filter = new Spinner(this);
        filter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"全部", "主动保存", "学习记录", "已固定", "人名", "地名", "专业术语", "昵称", "短语"}));
        page.addView(filter);
        summary = label("正在读取本机词库…", 13); page.addView(summary);
        undo = label("撤销上次词库修改", 14); undo.setPadding(dp(8), dp(10), dp(8), dp(10));
        undo.setTextColor(palette.accent); undo.setVisibility(View.GONE); undo.setOnClickListener(v -> undoChange()); page.addView(undo);
        ScrollView scroll = new ScrollView(this); rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(page);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { pageSize = 50; render(); }
            public void afterTextChanged(Editable value) {}
        });
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int position, long id) { pageSize = 50; render(); }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        reload();
    }
    private void reload() {
        int version = ++requestVersion;
        CustomWordStore.IO.execute(() -> {
            try {
                Map<String, CustomWordStore.Word> saved = CustomWordStore.load(getApplicationContext());
                Map<String, List<String>> records = new LinkedHashMap<>(); Set<String> pins = new HashSet<>();
                for (String[] row : PinyinDictionary.managedWords(getApplicationContext())) {
                    records.computeIfAbsent(row[0], k -> new ArrayList<>()).add(row[1]);
                    if ("固定".equals(row[2])) pins.add(row[0]);
                }
                postUi(() -> {
                    if (isFinishing() || isDestroyed() || version != requestVersion) return;
                    custom = saved; learned = records; legacyPinned = pins; render();
                    if (!initialAction) {
                        initialAction = true;
                        String word = getIntent().getStringExtra("word");
                        if (word != null) editWord(word, getIntent().getStringExtra("reading"));
                        else if (getIntent().getBooleanExtra("add", false)) editWord("", "");
                        else if (getIntent().getBooleanExtra("backup", false)) backupMenu();
                    }
                });
            } catch (RuntimeException error) { postUi(() -> { summary.setText(error.getMessage()); toast(error.getMessage()); }); }
        });
    }
    private void render() {
        if (rows == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT).replace('ü', 'v');
        int selected = filter.getSelectedItemPosition(); visible.clear();
        TreeSet<String> words = new TreeSet<>(learned.keySet()); words.addAll(custom.keySet());
        for (String word : words) {
            CustomWordStore.Word record = custom.get(word);
            if (selected == 1 && record == null || selected == 2 && record != null || selected == 3 && (record == null ? !legacyPinned.contains(word) : !record.pinned)) continue;
            if (selected >= 4 && (record == null || !filter.getSelectedItem().toString().equals(record.category))) continue;
            String codes = record == null ? String.join(" ", learned.get(word)) : String.join(" ", record.readings) + " " + String.join(" ", record.aliases);
            String haystack = (word + " " + codes + " " + codes.replace("'", "")).toLowerCase(Locale.ROOT);
            if (query.isEmpty() || haystack.contains(query) || haystack.contains(query.replace(" ", "'"))) visible.add(word);
        }
        rows.removeAllViews(); summary.setText("主动保存 " + custom.size() + " 个词 · 当前找到 " + visible.size() + " 个词");
        for (String word : visible.subList(0, Math.min(pageSize, visible.size()))) {
            CustomWordStore.Word record = custom.get(word);
            String detail = record == null ? (legacyPinned.contains(word) ? "既有固定记录 · " : "学习记录 · ") + String.join("、", learned.get(word))
                    : record.category + (record.pinned ? " · 已固定" : " · 未固定") + "\n读音："
                    + (record.readings.isEmpty() ? "仅快捷别名" : String.join(" / ", record.readings).replace('\'', ' '))
                    + (record.aliases.isEmpty() ? "" : "\n别名：" + String.join("、", record.aliases));
            TextView row = label(word + "\n" + detail, 15); row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(palette.surface); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6);
            rows.addView(row, lp); row.setOnClickListener(v -> wordMenu(word));
        }
        if (pageSize < visible.size()) { Button more = new Button(this); more.setText("再显示 50 个词"); more.setOnClickListener(v -> { pageSize += 50; render(); }); rows.addView(more); }
        if (visible.isEmpty()) rows.addView(label("没有匹配的词语，可以添加新词或更换筛选条件。", 14));
    }
    private void wordMenu(String word) {
        if (busy) return;
        CustomWordStore.Word record = custom.get(word);
        RoundedCardPopup menu = RoundedCardPopup.settings(this, word).width(360);
        menu.item("编辑词语、读音和别名", null, null, false, false, () -> editWord(word, ""));
        if (record != null) {
            menu.item(record.pinned ? "取消固定" : "固定匹配候选", "仅匹配读音或别名时生效", null, false, false, () -> saveWord(
                    new CustomWordStore.Word(word, record.readings, record.aliases, record.category, !record.pinned, record.createdAt), null));
            menu.item("删除自定义词", "同时删除这个词的旧学习记录，可撤销自定义词删除", null, false, true, () -> removeWord(word));
        }
        if (learned.containsKey(word)) menu.item("重置这个词的学习记录", "保留主动保存的读音和别名", null, false, true, () -> runOperation(() -> {
            if (!PinyinDictionary.deleteManagedWord(getApplicationContext(), word)) throw new IllegalStateException("清除失败，请重试");
            return "学习记录已重置";
        }));
        menu.item("屏蔽候选", "可在设置中的屏蔽词管理里恢复", null, false, true, () -> {
            getSharedPreferences("blocked_words", MODE_PRIVATE).edit().putBoolean(word, true).apply();
            getSharedPreferences("settings", MODE_PRIVATE).edit().putLong("user_words_revision", System.currentTimeMillis()).apply(); toast("已屏蔽：" + word);
        });
        menu.show(search);
    }
    private void editWord(String existing, String suggested) {
        if (busy) return;
        CustomWordStore.Word old = custom.get(existing);
        String readings = old == null ? suggested == null ? "" : suggested : String.join(",", old.readings);
        String aliases = old == null ? "" : String.join(",", old.aliases);
        if (old == null && readings.isEmpty() && learned.containsKey(existing)) {
            LinkedHashSet<String> canonical = new LinkedHashSet<>(), codes = new LinkedHashSet<>();
            for (String code : learned.get(existing)) {
                try { canonical.add(CustomWordStore.reading(code, existing)); } catch (IllegalArgumentException incomplete) { codes.add(code); }
            }
            readings = String.join(",", canonical); aliases = String.join(",", codes);
        }
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText word = input("词语，最多 64 个字", existing);
        EditText reading = input("完整读音，如 shan yu an；多个读音用逗号分隔", readings);
        EditText alias = input("快捷别名，如 sya（可不填）", aliases);
        EditText category = input("分类：普通词、人名、地名、专业术语等", old == null ? "普通词" : old.category);
        reading.setPrivateImeOptions("offlineime-word-code"); alias.setPrivateImeOptions("offlineime-word-code");
        Switch pinned = new Switch(this); pinned.setText("固定匹配候选"); pinned.setChecked(old != null ? old.pinned : legacyPinned.contains(existing));
        fields.addView(word); fields.addView(reading); fields.addView(alias); fields.addView(category); fields.addView(pinned);
        fields.addView(label("读音保留音节边界；保存不会自动固定。仅别名词可留空读音。", 12));
        ScrollView scroll = new ScrollView(this); scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing.isEmpty() ? "添加自定义词" : "编辑词条")
                .setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        RoundedCardPopup.showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) return;
            if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("privacy_mode", false)) { toast("隐私模式下不保存词语"); return; }
            try {
                CustomWordStore.Word value = CustomWordStore.validate(word.getText().toString(), reading.getText().toString(), alias.getText().toString(),
                        category.getText().toString(), pinned.isChecked(), old == null ? 0 : old.createdAt);
                saveWord(value, dialog, old == null ? "" : existing);
            } catch (IllegalArgumentException error) { reading.setError(error.getMessage()); toast(error.getMessage()); }
        });
    }
    private void saveWord(CustomWordStore.Word word, AlertDialog dialog) { saveWord(word, dialog, ""); }
    private void saveWord(CustomWordStore.Word word, AlertDialog dialog, String original) {
        if (busy) return; busy = true;
        CustomWordStore.IO.execute(() -> {
            CustomWordStore.Change change = original.isEmpty() || original.equals(word.text)
                    ? CustomWordStore.save(getApplicationContext(), word) : CustomWordStore.rename(getApplicationContext(), original, word);
            if (change.succeeded() && !change.renamedFrom.isEmpty()) PinyinDictionary.renameManagedWord(getApplicationContext(), change.renamedFrom, change.word);
            postUi(() -> {
                busy = false;
                if (!change.succeeded()) { toast(change.error); return; }
                if (dialog != null) dialog.dismiss(); rememberChange(change); toast("已保存：" + word.text); reload();
            });
        });
    }
    private void removeWord(String word) {
        if (busy) return; busy = true;
        CustomWordStore.IO.execute(() -> {
            CustomWordStore.Change change;
            if (!PinyinDictionary.deleteManagedWord(getApplicationContext(), word))
                change = new CustomWordStore.Change(word, null, null, "删除旧学习记录失败，词条已保留");
            else change = CustomWordStore.remove(getApplicationContext(), word);
            CustomWordStore.Change result = change;
            postUi(() -> { busy = false; if (result.succeeded()) { rememberChange(result); reload(); toast("已删除自定义词"); } else toast(result.error); });
        });
    }
    private void rememberChange(CustomWordStore.Change change) {
        lastChange = change; undo.setVisibility(View.VISIBLE); handler.removeCallbacks(expireUndo); handler.postDelayed(expireUndo, 10000);
    }
    private void undoChange() {
        CustomWordStore.Change change = lastChange; if (change == null || busy) return;
        runOperation(() -> { if (!CustomWordStore.undo(getApplicationContext(), change)) throw new IllegalStateException("词条已改变，未撤销"); if (!change.renamedFrom.isEmpty()) PinyinDictionary.renameManagedWord(getApplicationContext(), change.word, change.renamedFrom); return "已撤销词库修改"; });
        lastChange = null; undo.setVisibility(View.GONE);
    }
    private interface Work { String run() throws Exception; }
    private void runOperation(Work action) {
        if (busy) return; busy = true;
        CustomWordStore.IO.execute(() -> {
            String message;
            try { message = action.run(); } catch (Exception error) { message = error.getMessage() == null ? "操作失败，请重试" : error.getMessage(); }
            String result = message; postUi(() -> { busy = false; toast(result); reload(); });
        });
    }
    private void clearMenu() {
        new AlertDialog.Builder(this).setTitle("选择清理内容").setItems(new String[]{"清除自动学习记录，保留主动保存的词", "清除主动保存的词，保留其他学习记录"}, (d, which) ->
                new AlertDialog.Builder(this).setTitle("确认清除")
                        .setMessage(which == 0 ? "主动保存的词、读音和别名会保留。" : "主动保存的词及这些词的旧学习记录会删除。")
                        .setPositiveButton("清除", (confirm, button) -> runOperation(() -> {
                            if (which == 0) {
                                if (!SecureLocalStore.clear(getApplicationContext(), "user_words", "offline_ime_words")) throw new IllegalStateException("清除失败");
                            } else {
                                Map<String, CustomWordStore.Word> words = CustomWordStore.load(getApplicationContext());
                                if (!CustomWordStore.clear(getApplicationContext())) throw new IllegalStateException("清除失败");
                                for (String word : words.keySet()) PinyinDictionary.deleteManagedWord(getApplicationContext(), word);
                            }
                            getSharedPreferences("settings", MODE_PRIVATE).edit().putLong("user_words_revision", System.currentTimeMillis()).apply();
                            return "已清除所选词库记录";
                        })).setNegativeButton("取消", null).show()).show();
    }
    private void backupMenu() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("词库导入与备份")
                .setItems(new String[]{"导出自定义词库 JSON", "创建口令加密备份", "导入词库或加密备份"}, (d, which) -> {
                    if (which == 2) { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), IMPORT); }
                    else if (which == 1) passwordDialog("设置备份口令", password -> prepareExport(password));
                    else prepareExport(null);
                }).setNegativeButton("取消", null).show();
    }
    private interface PasswordAction { void run(char[] password); }
    private void passwordDialog(String title, PasswordAction action) {
        EditText field = input("口令，至少 8 个字符", ""); field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(field).setPositiveButton("确定", null).setNegativeButton("取消", null).create();
        RoundedCardPopup.showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (field.length() < 8) { field.setError("口令至少 8 个字符"); return; }
            char[] password = field.getText().toString().toCharArray(); field.setText(""); dialog.dismiss(); action.run(password);
        });
    }
    private void prepareExport(char[] password) {
        if (busy) { if (password != null) Arrays.fill(password, '\0'); return; } busy = true;
        CustomWordStore.IO.execute(() -> {
            try {
                String json = CustomWordStore.exportJson(getApplicationContext());
                exportData = password == null ? json : WordBackup.encrypt(json, password);
                postUi(() -> { busy = false; startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, password == null ? "开心输入法-自定义词库.json" : "开心输入法-加密词库.json"), EXPORT); });
            } catch (Exception error) { postUi(() -> { busy = false; toast("备份失败，词库原数据已保留"); }); }
            finally { if (password != null) Arrays.fill(password, '\0'); }
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) { exportData = null; return; }
        android.net.Uri uri = data.getData();
        if (request == EXPORT) {
            String payload = exportData; exportData = null; if (payload == null) { toast("备份会话已结束，请重新导出"); return; }
            runOperation(() -> { try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) throw new IllegalStateException("无法打开保存位置"); stream.write(payload.getBytes(StandardCharsets.UTF_8));
            } return "词库备份已保存"; });
        } else if (request == IMPORT) {
            if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("privacy_mode", false)) { toast("隐私模式下不导入词语"); return; }
            if (busy) return; busy = true;
            CustomWordStore.IO.execute(() -> {
                try (InputStream stream = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (stream == null) throw new IllegalStateException("无法读取文件");
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = stream.read(buffer)) != -1) { if (output.size() + count > 8 * 1024 * 1024) throw new IllegalArgumentException("词库文件不能超过 8 MB"); output.write(buffer, 0, count); }
                    String json = output.toString(StandardCharsets.UTF_8.name());
                    postUi(() -> { busy = false; if (WordBackup.encrypted(json)) passwordDialog("输入备份口令", password -> previewImport(json, password)); else previewImport(json, null); });
                } catch (Exception error) { postUi(() -> { busy = false; toast("无法读取词库文件"); }); }
            });
        }
    }
    private void previewImport(String json, char[] password) {
        if (busy) { if (password != null) Arrays.fill(password, '\0'); return; } busy = true;
        CustomWordStore.IO.execute(() -> {
            try {
                List<CustomWordStore.Word> words = CustomWordStore.previewImport(password == null ? json : WordBackup.decrypt(json, password));
                LinkedHashSet<String> names = new LinkedHashSet<>(); for (CustomWordStore.Word word : words) names.add(word.text);
                int duplicates = 0; for (String name : names) if (custom.containsKey(name)) duplicates++;
                int overlap = duplicates;
                List<String> preview = new ArrayList<>(names);
                String details = "包含 " + names.size() + " 个词，其中 " + overlap + " 个词已存在；新增 " + (names.size() - overlap) + " 个。可合并读音和别名，或覆盖同名词；其他词保持不变。\n\n"
                        + String.join("、", preview.subList(0, Math.min(12, preview.size())));
                postUi(() -> { busy = false; new AlertDialog.Builder(this).setTitle("导入预览").setMessage(details)
                        .setPositiveButton("合并导入", (d, which) -> runOperation(() -> {
                            if (!CustomWordStore.importWords(getApplicationContext(), words)) throw new IllegalStateException("导入失败，原词库已保留"); return "已导入 " + names.size() + " 个词";
                        })).setNeutralButton("覆盖同名词", (d, which) -> runOperation(() -> {
                            if (!CustomWordStore.importWords(getApplicationContext(), words, true)) throw new IllegalStateException("导入失败，原词库已保留");
                            return "已导入 " + names.size() + " 个词（覆盖同名词）";
                        })).setNegativeButton("取消", null).show(); });
            } catch (Exception error) { postUi(() -> { busy = false; toast(password == null ? "词库格式不正确，未导入" : "口令错误或备份文件损坏，未导入"); }); }
            finally { if (password != null) Arrays.fill(password, '\0'); }
        });
    }
    private TextView label(String text, int size) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(palette.textPrimary); return view; }
    private EditText input(String hint, String value) {
        EditText view = new EditText(this); view.setSingleLine(); view.setHint(hint); view.setText(value);
        view.setTextColor(palette.textPrimary); view.setHintTextColor(palette.textSecondary);
        view.setPrivateImeOptions("offlineime-word-editor"); return view;
    }
    private void button(LinearLayout parent, String text, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setTextColor(palette.accent); view.setOnClickListener(v -> action.run());
        parent.addView(view, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    private void postUi(Runnable action) { super.runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); }); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { if (!isFinishing()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); exportData = null; requestVersion++; super.onDestroy(); }
}
