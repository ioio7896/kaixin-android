package com.example.offlineime;

import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Candidate-strip and expanded-page controls with samples drawn like the live IME. */
final class SettingsCandidatePage {
    private final SettingsActivity host;
    private FrameLayout barPreview, pagePreview;
    private LinearLayout pageSizeControls, pageStyleControls;

    SettingsCandidatePage(SettingsActivity host) {
        this.host = host;
    }

    void build(LinearLayout content) {
        host.addSubheading(content, host.getString(R.string.candidate_bar_heading));
        barPreview = addPreview(content, R.string.candidate_bar_preview);
        fontSize(content, CandidateTextStyle.KEY_BAR_FONT);
        host.rows.addNumber(content, host.getString(R.string.candidate_bar_height),
                CandidateTextStyle.KEY_BAR_HEIGHT, CandidateTextStyle.MIN_BAR_HEIGHT_DP,
                CandidateTextStyle.MAX_BAR_HEIGHT_DP, CandidateTextStyle.DEFAULT_BAR_HEIGHT_DP, 2, " dp");
        fontControls(content, false);
        choice(content, R.string.candidate_spacing, CandidateTextStyle.KEY_SPACING,
                R.array.candidate_spacing_options, CandidateTextStyle.SPACING_VALUES, "standard");
        host.rows.addSwitch(content, host.getString(R.string.candidate_numbers),
                CandidateTextStyle.KEY_BAR_NUMBERS, false);

        host.addSubheading(content, host.getString(R.string.candidate_page_heading));
        pagePreview = addPreview(content, R.string.candidate_page_preview);
        host.rows.addSwitch(content, host.getString(R.string.candidate_page_size_follow),
                CandidateTextStyle.KEY_PAGE_FOLLOW, true);
        pageSizeControls = addConditionalGroup(content);
        fontSize(pageSizeControls, CandidateTextStyle.KEY_PAGE_FONT);
        host.rows.addSwitch(content, host.getString(R.string.candidate_page_style_follow),
                host.getString(R.string.candidate_page_style_follow_summary),
                CandidateTextStyle.KEY_PAGE_STYLE_FOLLOW, true);
        pageStyleControls = addConditionalGroup(content);
        fontControls(pageStyleControls, true);
        host.rows.addSwitch(content, host.getString(R.string.candidate_numbers),
                CandidateTextStyle.KEY_PAGE_NUMBERS, false);
        choice(content, R.string.candidate_density, CandidateTextStyle.KEY_PAGE_DENSITY,
                R.array.candidate_spacing_options, CandidateTextStyle.DENSITY_VALUES, "standard");
        choice(content, R.string.candidate_ellipsize, CandidateTextStyle.KEY_PAGE_ELLIPSIZE,
                R.array.candidate_ellipsize_options, CandidateTextStyle.ELLIPSIZE_VALUES, "end");
        host.rows.addExpandableHelp(content, host.getString(R.string.candidate_font_help_title),
                host.getString(R.string.candidate_font_help_detail));
        refreshPreview(null, 0);
    }

    private void fontSize(LinearLayout content, String key) {
        host.rows.addNumber(content, host.getString(R.string.candidate_font_size), key,
                CandidateTextStyle.MIN_FONT_SP, CandidateTextStyle.MAX_FONT_SP,
                CandidateTextStyle.DEFAULT_FONT_SP, 1, " sp");
    }

    private void fontControls(LinearLayout content, boolean expanded) {
        choice(content, R.string.candidate_font_family,
                expanded ? CandidateTextStyle.KEY_PAGE_FAMILY : CandidateTextStyle.KEY_FAMILY,
                R.array.candidate_font_families, CandidateTextStyle.FAMILY_VALUES, "system");
        choice(content, R.string.candidate_font_weight,
                expanded ? CandidateTextStyle.KEY_PAGE_WEIGHT : CandidateTextStyle.KEY_WEIGHT,
                R.array.candidate_font_weights, CandidateTextStyle.WEIGHT_VALUES, "400");
        choice(content, R.string.candidate_first_style,
                expanded ? CandidateTextStyle.KEY_PAGE_FIRST_STYLE : CandidateTextStyle.KEY_FIRST_STYLE,
                R.array.candidate_first_styles, CandidateTextStyle.FIRST_VALUES, "accent_bold");
        host.rows.addSwitch(content, host.getString(R.string.candidate_follow_font_scale),
                expanded ? CandidateTextStyle.KEY_PAGE_FOLLOW_SCALE : CandidateTextStyle.KEY_FOLLOW_SCALE, true);
    }

    private void choice(LinearLayout content, int label, String key, int labels, String[] values, String fallback) {
        host.rows.addChoice(content, host.getString(label), key,
                host.getResources().getStringArray(labels), values, fallback);
    }

    private FrameLayout addPreview(LinearLayout content, int label) {
        FrameLayout preview = new FrameLayout(host);
        preview.setContentDescription(host.getString(label));
        preview.setImportantForAccessibility(FrameLayout.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(host.dp(SettingsUi.CARD_INSET), host.dp(8), host.dp(SettingsUi.CARD_INSET), host.dp(8));
        content.addView(preview, params);
        return preview;
    }

    private LinearLayout addConditionalGroup(LinearLayout content) {
        LinearLayout group = new LinearLayout(host);
        group.setOrientation(LinearLayout.VERTICAL);
        content.addView(group, new LinearLayout.LayoutParams(-1, -2));
        return group;
    }

    void refreshPreview(String key, int value) {
        if (key != null && !CandidateTextStyle.affects(key)) return;
        Map<String, Object> values = new HashMap<>(host.preferences().getAll());
        if (key != null) values.put(key, value);
        pageSizeControls.setVisibility(SettingsRepository.bool(values, CandidateTextStyle.KEY_PAGE_FOLLOW, true)
                ? LinearLayout.GONE : LinearLayout.VISIBLE);
        pageStyleControls.setVisibility(SettingsRepository.bool(values, CandidateTextStyle.KEY_PAGE_STYLE_FOLLOW, true)
                ? LinearLayout.GONE : LinearLayout.VISIBLE);
        CandidateTextStyle style = CandidateTextStyle.fromValues(values);
        KeyboardTheme theme = KeyboardTheme.from(host, false);
        List<String> samples = Arrays.asList(host.getResources().getStringArray(R.array.candidate_preview_words));

        HorizontalScrollView strip = new HorizontalScrollView(host);
        strip.setFillViewport(true);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(host);
        int barHeight = host.dp(style.barHeight(host));
        for (int i = 0; i < samples.size() - 1; i++) {
            TextView cell = new TextView(host);
            CandidateHeaderView.applyCandidateStyle(cell, samples.get(i), i == 0, theme, style,
                    style.barNumbers ? i + 1 : 0, false);
            row.addView(cell, new LinearLayout.LayoutParams(-2, barHeight));
        }
        strip.addView(row, new HorizontalScrollView.LayoutParams(-2, barHeight));
        updatePreview(barPreview, strip, theme.background);

        RecycledCandidateLayout grid = CandidatePanel.createGrid(host, samples, style, (cell, position) ->
                CandidateHeaderView.applyCandidateStyle(cell, samples.get(position), position == 0, theme, style,
                        style.pageNumbers ? position + 1 : 0, true));
        updatePreview(pagePreview, grid, theme.background);
    }

    private void updatePreview(FrameLayout preview, android.view.View sample, int background) {
        preview.setBackground(host.roundRect(background, SettingsUi.CONTROL_RADIUS));
        preview.setClipToOutline(true);
        preview.removeAllViews();
        sample.setImportantForAccessibility(FrameLayout.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        preview.addView(sample, new FrameLayout.LayoutParams(-1, -2));
    }
}
