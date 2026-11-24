package com.example.offlineime;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Guides the user through enabling and selecting OfflineImeService in Android system settings. */
final class SettingsSetupPage {
    private final SettingsActivity host;
    private View homeSetupCard, homeSetupLink;
    private int state = -1;
    private boolean micGranted;
    private TextView homeSetupTitle, homeSetupDescription, homeSetupAction;
    private TextView setupStatus, setupStatusDescription, enableStepNumber, enableStepState;
    private TextView pickerStepNumber, pickerStepState, enableSetupAction, pickerSetupAction;
    private TextView micStepNumber, micStepState, micSetupAction;

    SettingsSetupPage(SettingsActivity host) {
        this.host = host;
    }

    void addHomeSetupCard(LinearLayout parent) {
        UiTokens.Palette palette = host.palette();
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(host.dp(18), host.dp(18), host.dp(18), host.dp(18));
        homeSetupCard = card;
        TextView eyebrow = host.text("开始使用", 12, palette.accent);
        eyebrow.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        card.addView(eyebrow);
        homeSetupTitle = host.text("启用开心输入法", 20, palette.textPrimary);
        homeSetupTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = host.dp(8);
        card.addView(homeSetupTitle, titleParams);
        homeSetupDescription = host.text("完成系统设置后即可开始使用", 13, palette.textSecondary);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = host.dp(5);
        card.addView(homeSetupDescription, descriptionParams);
        homeSetupAction = host.text("去设置  ›", 15, Color.WHITE);
        homeSetupAction.setGravity(Gravity.CENTER);
        homeSetupAction.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        homeSetupAction.setOnClickListener(v -> host.openSection(SettingsSections.SETUP));
        homeSetupAction.setMinimumHeight(host.dp(UiTokens.MIN_TOUCH));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = host.dp(16);
        card.addView(homeSetupAction, actionParams);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = host.dp(16);
        parent.addView(card, cardParams);
        host.bindPalette(card, () -> {
            UiTokens.Palette current = host.palette();
            GradientDrawable background = host.roundRect(current.accentSoft, SettingsUi.CARD_RADIUS);
            background.setStroke(host.dp(1), current.accent);
            card.setBackground(background);
            eyebrow.setTextColor(current.accent);
            homeSetupTitle.setTextColor(current.textPrimary);
            homeSetupDescription.setTextColor(current.textSecondary);
            homeSetupAction.setBackground(host.pressableRoundRect(
                    current.accent, host.darken(current.accent), SettingsUi.CONTROL_RADIUS));
        });
    }

    void setHomeSetupLink(View link) {
        homeSetupLink = link;
        link.setVisibility(View.GONE);
    }

    void releaseDetails() {
        setupStatus = setupStatusDescription = enableStepNumber = enableStepState = null;
        pickerStepNumber = pickerStepState = enableSetupAction = pickerSetupAction = null;
        micStepNumber = micStepState = micSetupAction = null;
    }

    LinearLayout createSetupDetails() {
        UiTokens.Palette palette = host.palette();
        LinearLayout page = new LinearLayout(host);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hero = new LinearLayout(host);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(host.dp(18), host.dp(20), host.dp(18), host.dp(20));
        TextView eyebrow = host.text("两步完成设置", 12, palette.accent);
        eyebrow.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        hero.addView(eyebrow);
        setupStatus = host.text("检查输入法状态", 21, palette.textPrimary);
        setupStatus.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = host.dp(8);
        hero.addView(setupStatus, statusParams);
        setupStatusDescription = host.text("按顺序完成下面两步，即可开始打字。", 14, palette.textSecondary);
        setupStatusDescription.setLineSpacing(host.dp(3), 1f);
        LinearLayout.LayoutParams statusDescriptionParams = new LinearLayout.LayoutParams(-1, -2);
        statusDescriptionParams.topMargin = host.dp(6);
        hero.addView(setupStatusDescription, statusDescriptionParams);
        page.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        host.bindPalette(hero, () -> {
            UiTokens.Palette current = host.palette();
            hero.setBackground(host.roundRect(current.accentSoft, SettingsUi.CARD_RADIUS));
            eyebrow.setTextColor(current.accent);
            setupStatus.setTextColor(current.textPrimary);
            setupStatusDescription.setTextColor(current.textSecondary);
        });

        addSetupStep(page, true, "在系统中启用", "打开系统输入法列表，开启「开心离线输入法」。", v ->
                host.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        addSetupStep(page, false, "设为当前输入法", "从系统输入法选择器中选择「开心离线输入法」。", v -> {
            InputMethodManager manager = (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) manager.showInputMethodPicker();
        });
        addOptionalMicStep(page);

        TextView tip = host.text("完成后返回此页，状态会自动更新。以后也可以在输入框的键盘切换按钮中更换输入法。",
                13, palette.textSecondary);
        tip.setLineSpacing(host.dp(3), 1f);
        tip.setPadding(host.dp(4), host.dp(16), host.dp(4), 0);
        page.addView(tip, new LinearLayout.LayoutParams(-1, -2));
        host.bindPalette(tip, () -> tip.setTextColor(host.palette().textSecondary));
        if (state >= 0) renderState();
        return page;
    }

    private void addSetupStep(LinearLayout page, boolean first, String title, String description,
                              View.OnClickListener listener) {
        UiTokens.Palette palette = host.palette();
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(host.dp(16), host.dp(17), host.dp(16), host.dp(16));
        LinearLayout heading = new LinearLayout(host);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView number = host.text(first ? "1" : "2", 15, palette.accent);
        number.setGravity(Gravity.CENTER);
        number.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        heading.addView(number, new LinearLayout.LayoutParams(host.dp(32), host.dp(32)));
        TextView label = host.text(title, 17, palette.textPrimary);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = host.dp(10);
        heading.addView(label, labelParams);
        TextView state = host.text("待完成", 12, palette.textSecondary);
        heading.addView(state);
        card.addView(heading);
        TextView detail = host.text(description, 14, palette.textSecondary);
        detail.setLineSpacing(host.dp(3), 1f);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = host.dp(10);
        card.addView(detail, detailParams);
        TextView action = host.text(first ? "打开系统设置  ›" : "选择输入法  ›", 15, Color.WHITE);
        action.setGravity(Gravity.CENTER);
        action.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        action.setOnClickListener(listener);
        action.setMinimumHeight(host.dp(UiTokens.MIN_TOUCH));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = host.dp(16);
        card.addView(action, actionParams);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = host.dp(12);
        page.addView(card, cardParams);
        host.bindPalette(card, () -> {
            UiTokens.Palette current = host.palette();
            card.setBackground(host.roundRect(current.surface, SettingsUi.CARD_RADIUS));
            label.setTextColor(current.textPrimary);
            detail.setTextColor(current.textSecondary);
        });
        if (first) {
            enableStepNumber = number;
            enableStepState = state;
            enableSetupAction = action;
        } else {
            pickerStepNumber = number;
            pickerStepState = state;
            pickerSetupAction = action;
        }
    }

    private int inputMethodSetupState() {
        InputMethodManager manager = (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager == null) return 0;
        boolean enabled = false;
        for (InputMethodInfo info : manager.getEnabledInputMethodList()) {
            if (host.getPackageName().equals(info.getPackageName())
                    && OfflineImeService.class.getName().equals(info.getServiceName())) {
                enabled = true;
                break;
            }
        }
        if (!enabled) return 0;
        String selectedId = Settings.Secure.getString(host.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName selected = selectedId == null ? null : ComponentName.unflattenFromString(selectedId);
        return selected != null && host.getPackageName().equals(selected.getPackageName())
                && OfflineImeService.class.getName().equals(selected.getClassName()) ? 2 : 1;
    }

    private void addOptionalMicStep(LinearLayout page) {
        UiTokens.Palette palette = host.palette();
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(host.dp(16), host.dp(17), host.dp(16), host.dp(16));
        LinearLayout heading = new LinearLayout(host);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView number = host.text("3", 15, palette.accent);
        number.setGravity(Gravity.CENTER);
        number.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        heading.addView(number, new LinearLayout.LayoutParams(host.dp(32), host.dp(32)));
        TextView label = host.text("离线语音权限（可选）", 17, palette.textPrimary);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = host.dp(10);
        heading.addView(label, labelParams);
        TextView permState = host.text("未开启", 12, palette.textSecondary);
        heading.addView(permState);
        card.addView(heading);
        TextView detail = host.text("允许录音权限即可使用离线语音输入；所有语音均在本机识别，不保存录音、不联网。", 14, palette.textSecondary);
        detail.setLineSpacing(host.dp(3), 1f);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = host.dp(10);
        card.addView(detail, detailParams);
        TextView action = host.text("允许录音权限  ›", 15, Color.WHITE);
        action.setGravity(Gravity.CENTER);
        action.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        action.setOnClickListener(v -> host.startActivity(new Intent(host, VoicePermissionActivity.class)));
        action.setMinimumHeight(host.dp(UiTokens.MIN_TOUCH));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = host.dp(16);
        card.addView(action, actionParams);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = host.dp(12);
        page.addView(card, cardParams);
        host.bindPalette(card, () -> {
            UiTokens.Palette current = host.palette();
            card.setBackground(host.roundRect(current.surface, SettingsUi.CARD_RADIUS));
            label.setTextColor(current.textPrimary);
            detail.setTextColor(current.textSecondary);
        });
        micStepNumber = number;
        micStepState = permState;
        micSetupAction = action;
    }

    private boolean isMicGranted() {
        return host.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    void refresh() {
        int current = inputMethodSetupState();
        boolean currentMic = isMicGranted();
        if (state == current && micGranted == currentMic) return;
        state = current;
        micGranted = currentMic;
        renderState();
    }

    private void renderState() {
        UiTokens.Palette palette = host.palette();
        homeSetupCard.setVisibility(state == 2 ? View.GONE : View.VISIBLE);
        homeSetupLink.setVisibility(state == 2 ? View.VISIBLE : View.GONE);
        homeSetupTitle.setText(state == 0 ? "尚未启用输入法" : "还需选择默认输入法");
        homeSetupDescription.setText(state == 0 ? "先在系统设置中启用，再选择为当前输入法。"
                : "已完成启用，再进行最后一步即可开始打字。");
        homeSetupAction.setText(state == 0 ? "去启用输入法  ›" : "去选择输入法  ›");
        homeSetupCard.setContentDescription(homeSetupTitle.getText() + "，" + homeSetupDescription.getText()
                + "，点击查看设置步骤");
        if (setupStatus == null) return;
        setupStatus.setText(state == 0 ? "先启用输入法" : state == 1 ? "再选择输入法" : "已准备就绪");
        setupStatusDescription.setText(state == 0 ? "在系统设置中开启后，返回完成第二步。"
                : state == 1 ? "输入法已启用，选择它作为当前输入法。" : "开心离线输入法已经启用并选为当前输入法。");
        enableStepNumber.setText(state > 0 ? "✓" : "1");
        enableStepState.setText(state > 0 ? "已完成" : "待完成");
        pickerStepNumber.setText(state == 2 ? "✓" : "2");
        pickerStepState.setText(state == 2 ? "已完成" : state == 1 ? "进行中" : "待完成");
        enableSetupAction.setText(state > 0 ? "查看系统设置  ›" : "打开系统设置  ›");
        pickerSetupAction.setText(state == 2 ? "更换输入法  ›" : "选择输入法  ›");
        styleSetupAction(enableSetupAction, state == 0, true);
        styleSetupAction(pickerSetupAction, state == 1, state > 0);
        enableStepNumber.setBackground(host.roundRect(palette.accentSoft, 10));
        pickerStepNumber.setBackground(host.roundRect(state > 0 ? palette.accentSoft : palette.surfaceMuted, 10));
        enableStepNumber.setTextColor(palette.accent);
        pickerStepNumber.setTextColor(state > 0 ? palette.accent : palette.textSecondary);
        enableStepState.setTextColor(state > 0 ? palette.accent : palette.textSecondary);
        pickerStepState.setTextColor(state == 2 ? palette.accent : palette.textSecondary);
        if (micStepNumber != null) {
            micStepNumber.setText(micGranted ? "✓" : "3");
            micStepNumber.setBackground(host.roundRect(micGranted ? palette.accentSoft : palette.surfaceMuted, 10));
            micStepNumber.setTextColor(micGranted ? palette.accent : palette.textSecondary);
            micStepState.setText(micGranted ? "已允许" : "可选");
            micStepState.setTextColor(micGranted ? palette.accent : palette.textSecondary);
            micSetupAction.setText(micGranted ? "已获得录音权限" : "允许录音权限  ›");
            styleSetupAction(micSetupAction, false, !micGranted);
        }
    }

    private void styleSetupAction(TextView action, boolean primary, boolean enabled) {
        UiTokens.Palette palette = host.palette();
        action.setEnabled(enabled);
        action.setTextColor(primary ? Color.WHITE : enabled ? palette.accent : palette.textSecondary);
        action.setBackground(host.pressableRoundRect(primary ? palette.accent : palette.accentSoft,
                primary ? host.darken(palette.accent) : palette.surfaceMuted, 12));
        action.setAlpha(enabled ? 1f : 0.65f);
    }
}
