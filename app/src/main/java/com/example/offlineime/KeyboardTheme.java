package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 集中的主题令牌，所有键盘控件使用同一套颜色和尺寸。 */
public final class KeyboardTheme {
    /** Palette order: canvas, letter keys, function keys, accent, text. */
    static final class Skin {
        final String id, label;
        private final int[] light, dark;
        Skin(String id, String label, int[] light, int[] dark) {
            this.id = id;
            this.label = label;
            this.light = light;
            this.dark = dark;
        }
    }

    static final List<Skin> SKINS = Collections.unmodifiableList(Arrays.asList(
            original("system", "默认蓝", 0xff3478f6),
            original("ocean", "海洋蓝", 0xff237bc4),
            original("mint", "薄荷绿", 0xff159570),
            original("violet", "优雅紫", 0xff7657d9),
            new Skin("classic_bw", "黑白经典",
                    new int[]{0xffe9e9e9, 0xffffffff, 0xffd6d6d6, 0xff303030, 0xff202020},
                    new int[]{0xff121212, 0xff292929, 0xff414141, 0xfff0f0f0, 0xfff5f5f5}),
            new Skin("cream", "奶油米白",
                    new int[]{0xffeee7d8, 0xfffffcf4, 0xffe0d4bc, 0xff80602f, 0xff453b2e},
                    new int[]{0xff242119, 0xff373126, 0xff4b4131, 0xffe9c98b, 0xfffff4df}),
            new Skin("coffee", "复古咖啡",
                    new int[]{0xffe6d8cc, 0xfffaf3eb, 0xffd3b9a3, 0xff7c4930, 0xff49342b},
                    new int[]{0xff261d19, 0xff3b2d26, 0xff543e32, 0xffe5b48e, 0xfffaf0e6}),
            new Skin("sakura", "樱花粉",
                    new int[]{0xfff7e6eb, 0xfffff9fb, 0xffefcbd7, 0xffa83f68, 0xff52313e},
                    new int[]{0xff2a1d24, 0xff402c36, 0xff593b49, 0xffffa8c7, 0xffffedf3}),
            new Skin("burgundy", "经典酒红",
                    new int[]{0xffeadde0, 0xfffff7f8, 0xffd7b5bd, 0xff8a2840, 0xff4c2631},
                    new int[]{0xff29171d, 0xff43232d, 0xff603340, 0xffffa3b7, 0xffffedf1}),
            new Skin("forest", "森林绿",
                    new int[]{0xffdfe9df, 0xfff7fcf5, 0xffbdd1bd, 0xff2d6542, 0xff293f30},
                    new int[]{0xff18241c, 0xff26392c, 0xff38523e, 0xffa0d6aa, 0xffedf7ec}),
            new Skin("navy", "经典藏蓝",
                    new int[]{0xffdfe5ef, 0xfff8faff, 0xffbfccdf, 0xff284d80, 0xff26364e},
                    new int[]{0xff152032, 0xff253550, 0xff364d70, 0xffa9c8ff, 0xffedf3ff}),
            new Skin("graphite", "石墨灰",
                    new int[]{0xffdfe3e6, 0xfff4f6f7, 0xffbcc4cb, 0xff45576a, 0xff28333d},
                    new int[]{0xff1c2228, 0xff303a43, 0xff46535f, 0xffb8d2e8, 0xffeef3f7}),
            new Skin("amber", "琥珀橙",
                    new int[]{0xfff6e5d3, 0xfffff9f0, 0xffefc69c, 0xff99501b, 0xff513921},
                    new int[]{0xff2b2017, 0xff433123, 0xff5e452f, 0xffffc084, 0xfffff2e0}),
            new Skin("lavender", "薰衣草",
                    new int[]{0xffeae4f2, 0xfffdfaff, 0xffd3c4e3, 0xff725092, 0xff40334c},
                    new int[]{0xff241c2d, 0xff382b46, 0xff50405f, 0xffd4b6f3, 0xfff6edff}),
            new Skin("peach", "蜜桃珊瑚",
                    new int[]{0xfff9e4dd, 0xfffffaf7, 0xfff0c4b5, 0xffad4d3d, 0xff53352e},
                    new int[]{0xff2d1e1a, 0xff452e28, 0xff62443a, 0xffffb5a1, 0xfffff0e9}),
            new Skin("lemon", "柠檬黄",
                    new int[]{0xfff2edcf, 0xfffffef2, 0xffe2d99e, 0xff78620b, 0xff453e21},
                    new int[]{0xff292616, 0xff403b22, 0xff5a5330, 0xffe8d671, 0xfffaf5db}),
            new Skin("sky", "晴空蓝",
                    new int[]{0xffdceef7, 0xfff7fcff, 0xffb7d9ea, 0xff226887, 0xff29424e},
                    new int[]{0xff17262e, 0xff253d49, 0xff365564, 0xff9cd8f2, 0xffeaf8ff}),
            new Skin("teal", "孔雀青",
                    new int[]{0xffd9ece9, 0xfff5fffc, 0xffafd7d0, 0xff126e65, 0xff26433f},
                    new int[]{0xff162927, 0xff233f3b, 0xff335953, 0xff8adbd0, 0xffe8faf5}),
            new Skin("olive", "橄榄绿",
                    new int[]{0xffe9ebd9, 0xfffbfdf3, 0xffd1d8b2, 0xff596d2d, 0xff3a422b},
                    new int[]{0xff222719, 0xff363e29, 0xff4d573b, 0xffc2da91, 0xfff1f8e4}),
            new Skin("rose", "玫瑰豆沙",
                    new int[]{0xfff0e2e2, 0xfffff9f8, 0xffddc0c1, 0xff98545d, 0xff4d3437},
                    new int[]{0xff2b1e21, 0xff422e32, 0xff5c4247, 0xffedb0b9, 0xffffeff0}),
            new Skin("plum", "暮色梅紫",
                    new int[]{0xffece0eb, 0xfffff8fe, 0xffd7bfd4, 0xff824d7b, 0xff483349},
                    new int[]{0xff291b29, 0xff402b40, 0xff5a3e59, 0xffedb4e2, 0xffffeeff}),
            new Skin("sand", "沙漠暖棕",
                    new int[]{0xffeee2cf, 0xfffffaf1, 0xffdac5a5, 0xff86603a, 0xff493b2c},
                    new int[]{0xff292219, 0xff403428, 0xff594936, 0xffe7c397, 0xfffff2e1}),
            new Skin("slate", "雾霭青灰",
                    new int[]{0xffdfe9e9, 0xfff7fbfb, 0xffbfd0d1, 0xff46696c, 0xff304143},
                    new int[]{0xff1b2627, 0xff2c3c3d, 0xff405556, 0xffafd4d4, 0xffedf8f7})
    ));

    private static Skin original(String id, String label, int accent) {
        return new Skin(id, label,
                new int[]{0xffeef0f5, 0xffffffff, 0xffdfe4ed, accent, 0xff30343b},
                new int[]{0xff17191d, 0xff343941, 0xff4a515c, accent, 0xffffffff});
    }

    static Skin skin(String id) {
        for (Skin skin : SKINS) if (skin.id.equals(id)) return skin;
        return SKINS.get(0);
    }

    public final int background, key, function, accent, text;
    public final boolean isDark;
    public final float radius, font, gap;
    private final int candidateHighlight, candidatePrimaryText, candidateRegularText;
    private final int candidatePressedPrimaryText, candidatePressedText;
    private KeyboardTheme(int b, int k, int f, int a, int t, float r, float fs, float g, boolean dark) {
        background=b; key=k; function=f; accent=a; text=t; radius=r; font=fs; gap=g; isDark=dark;
        candidateHighlight = mix(key, accent, dark ? 0.18 : 0.12);
        candidatePrimaryText = readableText(accent, candidateHighlight);
        candidateRegularText = readableText(text, background);
        candidatePressedPrimaryText = readableText(accent, function);
        candidatePressedText = readableText(text, function);
    }
    public static KeyboardTheme from(Context c, boolean dark) {
        SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        dark = resolveDark(c);
        return forSkin(c, p.getString("theme", "system"), dark, p.getBoolean("high_contrast", false));
    }

    static boolean resolveDark(Context c) {
        SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String mode = p.getString("appearance_mode", p.contains("dark") ? (p.getBoolean("dark", false) ? "dark" : "light") : "system");
        return "dark".equals(mode) || "system".equals(mode)
                && (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    static KeyboardTheme forSkin(Context c, String id, boolean dark, boolean highContrast) {
        SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        int[] colors = dark ? skin(id).dark : skin(id).light;
        if (highContrast) {
            colors = dark ? new int[]{0xff000000, 0xff202020, 0xff555555, 0xff8dbbff, 0xffffffff}
                    : new int[]{0xffffffff, 0xffffffff, 0xffdddddd, 0xff005fcc, 0xff000000};
        }
        return new KeyboardTheme(colors[0], colors[1], colors[2], colors[3], colors[4],
                p.getInt("key_radius", 12), p.getInt("key_font", 20), p.getInt("key_gap", 2), dark);
    }

    int emphasizedBackground() {
        int amount = (int) (255 * (isDark ? 0.24f : 0.12f));
        int red = ((key >> 16) & 255) * (255 - amount) / 255 + ((accent >> 16) & 255) * amount / 255;
        int green = ((key >> 8) & 255) * (255 - amount) / 255 + ((accent >> 8) & 255) * amount / 255;
        int blue = (key & 255) * (255 - amount) / 255 + (accent & 255) * amount / 255;
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    int candidateBackground(boolean first) { return first ? candidateHighlight : background; }

    int candidateForeground(boolean first, boolean pressed) {
        return pressed ? (first ? candidatePressedPrimaryText : candidatePressedText)
                : (first ? candidatePrimaryText : candidateRegularText);
    }

    /** Retain the skin hue while bringing candidate text to at least 4.5:1. */
    static int readableText(int foreground, int surface) {
        if (contrast(foreground, surface) >= 4.5) return foreground;
        int target = contrast(0xff000000, surface) >= contrast(0xffffffff, surface)
                ? 0xff000000 : 0xffffffff;
        double low = 0, high = 1;
        for (int i = 0; i < 12; i++) {
            double middle = (low + high) / 2;
            if (contrast(mix(foreground, target, middle), surface) >= 4.5) high = middle;
            else low = middle;
        }
        return mix(foreground, target, high);
    }

    private static int mix(int base, int overlay, double amount) {
        int red = (int) Math.round(((base >> 16) & 255) * (1 - amount) + ((overlay >> 16) & 255) * amount);
        int green = (int) Math.round(((base >> 8) & 255) * (1 - amount) + ((overlay >> 8) & 255) * amount);
        int blue = (int) Math.round((base & 255) * (1 - amount) + (overlay & 255) * amount);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * linear((color >> 16) & 255) + 0.7152 * linear((color >> 8) & 255)
                + 0.0722 * linear(color & 255);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
