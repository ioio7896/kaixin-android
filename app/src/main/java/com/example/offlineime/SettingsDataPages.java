package com.example.offlineime;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Builds the "词库管理", "隐私安全", "剪贴板", and "关于" settings sections. */
final class SettingsDataPages {
    private final SettingsActivity host;

    SettingsDataPages(SettingsActivity host) {
        this.host = host;
    }

    void buildDictionarySection(LinearLayout content) {
        host.addSubheading(content, host.getString(R.string.settings_dictionary_learning));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_learn_words), "learn_words", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_learn_long_press), "learn_long_press", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_blocked_words), "blocked_words", true);
        // 自定义词编辑器属于词库管理，紧跟在词库开关之后。
        host.addSubheading(content, host.getString(R.string.settings_dictionary_custom_words));
        addCustomWordControls(content);
    }

    void buildPrivacySection(LinearLayout content) {
        SharedPreferences preferences = host.preferences();
        UiTokens.Palette palette = host.palette();
        host.addSubheading(content, host.getString(R.string.settings_privacy_protection));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_privacy_mode), host.getString(R.string.settings_pref_privacy_mode_detail), "privacy_mode", false);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_sensitive_filter), host.getString(R.string.settings_pref_sensitive_filter_detail), "sensitive_filter", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_performance_logging), host.getString(R.string.settings_pref_performance_logging_detail), "performance_logging", false);
        host.addSubheading(content, host.getString(R.string.settings_privacy_reset));
        Button clearAll = new Button(host);
        clearAll.setTag("action-danger");
        clearAll.setText("清除全部本地数据");
        clearAll.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(host)
                    .setTitle("确认清除？")
                    .setMessage("主动保存的词、用户词库、上下文学习、拼音路径偏好、屏蔽词、表情记录、快捷短语和剪贴板历史将被删除。键盘外观设置会保留。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清除", (popup, which) -> {
                        clearAll.setEnabled(false);
                        PersonalDataIO.EXECUTOR.execute(() -> {
                            boolean cleared = clearLocalPersonalData();
                            host.runOnUiThread(() -> {
                                if (host.isFinishing() || host.isDestroyed()) return;
                                clearAll.setEnabled(true);
                                Toast.makeText(host, cleared ? "本地学习数据和历史已清除" : "部分数据未能清除，请重试",
                                        Toast.LENGTH_LONG).show();
                            });
                        });
                    }).create();
            RoundedCardPopup.showDialog(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(palette.danger);
        });
        content.addView(clearAll, new LinearLayout.LayoutParams(-1, host.dp(48)));
        Button reset = new Button(host);
        reset.setTag("action-secondary");
        reset.setText(R.string.settings_reset_all);
        reset.setOnClickListener(v -> RoundedCardPopup.showDialog(
                new AlertDialog.Builder(host)
                        .setTitle(R.string.settings_reset_all_title)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("恢复", (d, w) -> {
                            preferences.edit().clear().apply();
                            host.getSharedPreferences("keyboard_state", Context.MODE_PRIVATE).edit().clear().apply();
                            host.scheduleRecreate();
                        }).create()));
        content.addView(reset, new LinearLayout.LayoutParams(-1, host.dp(48)));
    }

    void buildClipboardSection(LinearLayout content) {
        host.addSubheading(content, host.getString(R.string.settings_clipboard_history));
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_clipboard_enabled), "clipboard_enabled", true);
        host.rows.addSwitch(content, host.getString(R.string.settings_pref_clipboard_capture_while_typing), "clipboard_capture_while_typing", true);
        host.rows.addChoice(content, host.getString(R.string.settings_pref_clipboard_limit), "clipboard_limit",
                host.getResources().getStringArray(R.array.settings_pref_clipboard_limit_options),
                new String[]{"10", "20", "50", "100"});
        host.rows.addChoice(content, host.getString(R.string.settings_pref_clipboard_retention_days), "clipboard_retention_days",
                host.getResources().getStringArray(R.array.settings_pref_clipboard_retention_days_options),
                new String[]{"1", "7", "30", "0"}, "7");
        host.addSubheading(content, host.getString(R.string.settings_clipboard_description));
        host.rows.addExpandableHelp(content, host.getString(R.string.settings_help_clipboard_history),
                host.getString(R.string.settings_help_clipboard_history_detail));
    }

    void buildAboutSection(LinearLayout content) {
        UiTokens.Palette palette = host.palette();
        host.addSubheading(content, host.getString(R.string.settings_about_version));
        TextView about = host.text(host.getString(R.string.about_version_text, BuildConfig.VERSION_NAME),
                14, palette.textSecondary);
        content.addView(about, new LinearLayout.LayoutParams(-1, -2));
        Button licenses = new Button(host);
        licenses.setText("词库与表情来源");
        licenses.setOnClickListener(v -> showLexiconLicenses());
        content.addView(licenses, new LinearLayout.LayoutParams(-1, host.dp(48)));
        host.addSubheading(content, host.getString(R.string.settings_about_privacy));
        TextView privacy = host.text("隐私说明\n\n本应用只在当前输入框中完成本地组合和候选。密码、支付和验证码输入不会写入用户词库。语音输入需要录音权限，识别在本机完成，不保存录音。应用没有网络权限、联系人权限、存储权限和无障碍服务。",
                14, palette.textSecondary);
        privacy.setPadding(0, host.dp(18), 0, 0);
        content.addView(privacy, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addCustomWordControls(LinearLayout parent) {
        SharedPreferences preferences = host.preferences();
        TextView hint = host.text("可在这里添加和管理自定义词，保存后供输入时使用。", 14, host.palette().textSecondary);
        parent.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        for (String title : new String[]{"添加自定义词", "管理自定义词与学习词", "导入、导出与备份词库"}) {
            Button button = new Button(host);
            button.setText(title);
            button.setOnClickListener(v -> host.startActivity(new Intent(host, CustomWordManagerActivity.class)
                    .putExtra("add", "添加自定义词".equals(title)).putExtra("backup", title.startsWith("导入"))));
            parent.addView(button, new LinearLayout.LayoutParams(-1, host.dp(48)));
        }
        Button clearContext = new Button(host);
        clearContext.setText("清除联想记录");
        clearContext.setOnClickListener(v -> CustomWordStore.IO.execute(() -> {
            long revision = Math.max(System.currentTimeMillis(), preferences.getLong("context_revision", 0) + 1);
            boolean cleared = SecureLocalStore.clear(host, "context_model", "offline_ime_context");
            if (cleared) preferences.edit().putLong("context_revision", revision).apply();
            host.runOnUiThread(() -> {
                if (!host.isFinishing() && !host.isDestroyed()) {
                    Toast.makeText(host, cleared ? "联想记录已清除" : "清除失败，请重试", Toast.LENGTH_SHORT).show();
                }
            });
        }));
        parent.addView(clearContext, new LinearLayout.LayoutParams(-1, host.dp(48)));
    }

    private boolean clearLocalPersonalData() {
        PersonalDataIO.beginClear();
        ClipboardHistoryStore.flushBeforeGlobalClear();
        boolean cleared = PinyinDictionary.clearStoredUserWords(host);
        cleared &= CustomWordStore.clear(host);
        cleared &= SecureLocalStore.clear(host, "context_model", "offline_ime_context");
        cleared &= SecureLocalStore.clear(host, "clipboard_history", "offline_ime_clipboard");
        cleared &= host.getSharedPreferences("clipboard_capture_state", Context.MODE_PRIVATE).edit().clear().commit();
        cleared &= SecureLocalStore.clear(host, "quick_phrases", "offline_ime_quick_phrases");
        for (String preferencesName : new String[]{"blocked_words", "pinyin_path_usage", "emoji", "symbol_recent", "gesture_tips"}) {
            cleared &= host.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit();
        }
        QuickPhraseRepository.onDataCleared();
        host.sendBroadcast(new Intent(UserDataEvents.LOCAL_DATA_CLEARED).setPackage(host.getPackageName()),
                UserDataEvents.INTERNAL_PERMISSION);
        return cleared;
    }

    private void showLexiconLicenses() {
        String notice = "内置词库来源\n"
                + "中文基础候选、生活词和扩展词来自项目 lexicon 中各文件头部所列来源；部分词条为项目整理。\n"
                + "英文词频词库基于 wordfreq 3.1.1（Apache License 2.0），由项目筛选并加入少量常用技术词。\n\n"
                + "Apache 许可摘要\n" + readBundledText("en/LICENSE.wordfreq.md")
                + "\n\n来源说明\n" + readBundledText("en/README.md")
                + "\n\n" + readBundledText("windows-category-import-notice.md")
                + "\n\n" + readBundledText("lexicon-corrections/NOTICE.md")
                + "\n\n分类词库语料许可\n" + readBundledText("category-LICENSE.jieba.md")
                + "\n\n拼音生成许可\n" + readBundledText("category-LICENSE.pypinyin.md")
                + "\n\n单字读音数据许可\n" + readBundledText("lexicon-notices/LICENSE.pinyin-data.md")
                + "\n中文词库各数据源的再分发条件须以对应来源和许可为准。公开发布前请核实全部词库的授权，并随应用提供适用的完整许可文本。"
                + "\n\nEmoji 目录及注释\n" + readBundledText("emoji/NOTICE.txt")
                + "\nUnicode 数据许可\n" + readBundledText("emoji/LICENSE.txt")
                + "\n\n简繁转换数据\n" + readBundledText("opencc/NOTICE.txt")
                + "\n许可文本\n" + readBundledText("opencc/LICENSE.txt")
                + "\n\n笔画输入数据\n" + readBundledText("stroke/NOTICE.txt");
        TextView content = host.text(notice, 14, host.palette().textPrimary);
        content.setTextIsSelectable(true);
        content.setGravity(Gravity.TOP);
        content.setPadding(host.dp(18), host.dp(14), host.dp(18), host.dp(14));
        ScrollView scroll = new ScrollView(host);
        scroll.setFillViewport(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        RoundedCardPopup.showDialog(new AlertDialog.Builder(host).setTitle("词库与表情来源").setView(scroll)
                .setPositiveButton("关闭", null).create());
    }

    private String readBundledText(String path) {
        try (InputStream stream = host.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception error) {
            return "无法读取随包文件：" + path;
        }
    }
}
