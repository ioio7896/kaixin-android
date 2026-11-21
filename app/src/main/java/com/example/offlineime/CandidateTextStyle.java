package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.TextView;
import java.util.Arrays;
import java.util.Map;

/** 候选栏与展开候选页的字体显示设置，键盘与设置预览共用同一套解析逻辑。 */
final class CandidateTextStyle {
    static final int MIN_FONT_SP = 12;
    static final int MAX_FONT_SP = 28;
    static final int DEFAULT_FONT_SP = 17;
    static final int MIN_BAR_HEIGHT_DP = 40;
    static final int MAX_BAR_HEIGHT_DP = 72;
    static final int DEFAULT_BAR_HEIGHT_DP = 48;

    static final String KEY_BAR_FONT = "candidate_font_size";
    static final String KEY_BAR_HEIGHT = "candidate_bar_height";
    static final String KEY_PAGE_FOLLOW = "candidate_page_font_follow";
    static final String KEY_PAGE_FONT = "candidate_page_font_size";
    static final String KEY_PAGE_STYLE_FOLLOW = "candidate_page_style_follow";
    static final String KEY_PAGE_FAMILY = "candidate_page_font_family";
    static final String KEY_PAGE_WEIGHT = "candidate_page_font_weight";
    static final String KEY_PAGE_FIRST_STYLE = "candidate_page_first_style";
    static final String KEY_PAGE_FOLLOW_SCALE = "candidate_page_follow_font_scale";
    static final String KEY_FAMILY = "candidate_font_family";
    static final String KEY_WEIGHT = "candidate_font_weight";
    static final String KEY_FIRST_STYLE = "candidate_first_style";
    static final String KEY_SPACING = "candidate_spacing";
    static final String KEY_BAR_NUMBERS = "candidate_bar_numbers";
    static final String KEY_PAGE_NUMBERS = "candidate_page_numbers";
    static final String KEY_FOLLOW_SCALE = "candidate_follow_font_scale";
    static final String KEY_PAGE_ELLIPSIZE = "candidate_page_ellipsize";
    static final String KEY_PAGE_DENSITY = "expanded_candidate_density";

    /** 恢复“候选显示”默认值时需要清除的全部键。 */
    static final String[] KEYS = {
            KEY_BAR_FONT, KEY_BAR_HEIGHT, KEY_PAGE_FOLLOW, KEY_PAGE_FONT, KEY_FAMILY, KEY_WEIGHT,
            KEY_FIRST_STYLE, KEY_SPACING, KEY_BAR_NUMBERS, KEY_PAGE_NUMBERS, KEY_FOLLOW_SCALE,
            KEY_PAGE_ELLIPSIZE, KEY_PAGE_DENSITY, KEY_PAGE_STYLE_FOLLOW, KEY_PAGE_FAMILY,
            KEY_PAGE_WEIGHT, KEY_PAGE_FIRST_STYLE, KEY_PAGE_FOLLOW_SCALE
    };

    static final String[] FAMILY_VALUES = {"system", "serif", "mono", "condensed"};
    static final String[] WEIGHT_VALUES = {"300", "400", "500", "700"};
    static final String[] FIRST_VALUES = {"accent_bold", "accent", "bold", "plain"};
    static final String[] SPACING_VALUES = {"compact", "standard", "loose"};
    static final String[] ELLIPSIZE_VALUES = {"end", "middle"};
    static final String[] DENSITY_VALUES = {"compact", "standard", "comfortable"};
    static final float NUMBER_SCALE = 0.62f;

    final int barFontSp;
    final int pageFontSp;
    final int barHeightDp;
    final int barPaddingDp;
    final boolean barNumbers;
    final boolean pageNumbers;
    final TextUtils.TruncateAt pageEllipsize;
    final int pageMinCellDp;
    final int pagePaddingDp;
    private final Font barFont;
    private final Font pageFont;

    private static final class Font {
        final boolean followScale;
        final boolean accentFirst;
        final Typeface regular;
        final Typeface first;

        Font(Map<String, ?> values, String familyKey, String weightKey, String firstKey, String scaleKey) {
            String family = SettingsRepository.string(values, familyKey, "system");
            int weight = clamp(SettingsRepository.integer(values, weightKey, 400), 100, 900);
            String emphasis = SettingsRepository.string(values, firstKey, "accent_bold");
            followScale = SettingsRepository.bool(values, scaleKey, true);
            accentFirst = "accent_bold".equals(emphasis) || "accent".equals(emphasis);
            boolean boldFirst = "accent_bold".equals(emphasis) || "bold".equals(emphasis);
            regular = face(family, weight);
            first = boldFirst ? face(family, clamp(weight + 200, 700, 900)) : regular;
        }

        int unit() {
            return followScale ? TypedValue.COMPLEX_UNIT_SP : TypedValue.COMPLEX_UNIT_DIP;
        }
    }

    private CandidateTextStyle(Map<String, ?> values) {
        barFontSp = clamp(SettingsRepository.integer(values, KEY_BAR_FONT, DEFAULT_FONT_SP), MIN_FONT_SP, MAX_FONT_SP);
        pageFontSp = SettingsRepository.bool(values, KEY_PAGE_FOLLOW, true) ? barFontSp
                : clamp(SettingsRepository.integer(values, KEY_PAGE_FONT, DEFAULT_FONT_SP), MIN_FONT_SP, MAX_FONT_SP);
        barHeightDp = clamp(SettingsRepository.integer(values, KEY_BAR_HEIGHT, DEFAULT_BAR_HEIGHT_DP),
                MIN_BAR_HEIGHT_DP, MAX_BAR_HEIGHT_DP);
        String spacing = SettingsRepository.string(values, KEY_SPACING, "standard");
        barPaddingDp = "compact".equals(spacing) ? 8 : "loose".equals(spacing) ? 18 : 12;
        barNumbers = SettingsRepository.bool(values, KEY_BAR_NUMBERS, false);
        pageNumbers = SettingsRepository.bool(values, KEY_PAGE_NUMBERS, false);
        pageEllipsize = "middle".equals(SettingsRepository.string(values, KEY_PAGE_ELLIPSIZE, "end"))
                ? TextUtils.TruncateAt.MIDDLE : TextUtils.TruncateAt.END;
        String pageDensity = SettingsRepository.string(values, KEY_PAGE_DENSITY, "standard");
        pageMinCellDp = "comfortable".equals(pageDensity) ? 56 : 48;
        pagePaddingDp = "compact".equals(pageDensity) ? 5 : "comfortable".equals(pageDensity) ? 10 : 8;
        barFont = new Font(values, KEY_FAMILY, KEY_WEIGHT, KEY_FIRST_STYLE, KEY_FOLLOW_SCALE);
        pageFont = SettingsRepository.bool(values, KEY_PAGE_STYLE_FOLLOW, true) ? barFont
                : new Font(values, KEY_PAGE_FAMILY, KEY_PAGE_WEIGHT, KEY_PAGE_FIRST_STYLE, KEY_PAGE_FOLLOW_SCALE);
    }

    static boolean affects(String key) {
        return Arrays.asList(KEYS).contains(key);
    }

    static CandidateTextStyle read(SharedPreferences preferences) {
        return fromValues(preferences.getAll());
    }

    static CandidateTextStyle fromValues(Map<String, ?> values) {
        return new CandidateTextStyle(values);
    }

    Typeface typeface(boolean first, boolean expanded) {
        Font font = expanded ? pageFont : barFont;
        return first ? font.first : font.regular;
    }

    boolean accentFirst(boolean expanded) {
        return (expanded ? pageFont : barFont).accentFirst;
    }

    void applyTextSize(TextView view, boolean expanded) {
        Font font = expanded ? pageFont : barFont;
        view.setTextSize(font.unit(), expanded ? pageFontSp : barFontSp);
    }

    float textPx(Context context, boolean expanded) {
        Font font = expanded ? pageFont : barFont;
        return TypedValue.applyDimension(font.unit(), expanded ? pageFontSp : barFontSp,
                context.getResources().getDisplayMetrics());
    }

    int barHeight(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int textDp = (int) Math.ceil(textPx(context, false) * 1.4f / density) + 12;
        return Math.max(barHeightDp, textDp);
    }

    int pageCellHeightPx(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.max(Math.round(pageMinCellDp * density),
                (int) Math.ceil(textPx(context, true) * 1.4f + 8 * density));
    }

    private static Typeface face(String family, int weight) {
        String name = familyName(family);
        Typeface base = Typeface.create(name, Typeface.NORMAL);
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(base, weight, false);
        if (weight >= 700) return Typeface.create(base, Typeface.BOLD);
        if ("sans-serif".equals(name)) {
            if (weight >= 500) return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            if (weight <= 300) return Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
        return base;
    }

    private static String familyName(String family) {
        switch (family) {
            case "serif": return "serif";
            case "mono": return "monospace";
            case "condensed": return "sans-serif-condensed";
            default: return "sans-serif";
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

}
