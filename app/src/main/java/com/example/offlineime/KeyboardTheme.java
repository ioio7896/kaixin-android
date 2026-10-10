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
                    new int[]{0xff2b2017, 0xff433123, 0xff5e452f, 0xffffc084, 0xfffff2e0})
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
    private KeyboardTheme(int b, int k, int f, int a, int t, float r, float fs, float g, boolean dark) { background=b; key=k; function=f; accent=a; text=t; radius=r; font=fs; gap=g; isDark=dark; }
    public static KeyboardTheme from(Context c, boolean dark) {
        SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        return forSkin(c, p.getString("theme", "system"), dark, p.getBoolean("high_contrast", false));
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
}
