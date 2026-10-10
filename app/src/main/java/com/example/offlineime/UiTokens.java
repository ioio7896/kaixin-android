package com.example.offlineime;

import android.content.Context;
import android.content.res.Configuration;

/** Shared visual tokens for settings surfaces and keyboard chrome. */
public final class UiTokens {
    public static final int SPACE_XS = 4;
    public static final int SPACE_SM = 8;
    public static final int SPACE_MD = 12;
    public static final int SPACE_LG = 16;
    public static final int SPACE_XL = 24;
    public static final int RADIUS_CONTROL = 12;
    public static final int RADIUS_CARD = 16;
    public static final int MIN_TOUCH = 48;

    private UiTokens() {}

    public static boolean isDark(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static Palette palette(Context context) {
        boolean dark = isDark(context);
        Palette base = dark ? Palette.DARK : Palette.LIGHT;
        String skin = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme", "system");
        int accent = KeyboardTheme.forSkin(context, skin, dark, false).accent;
        if (dark && ("system".equals(skin) || "ocean".equals(skin)
                || "mint".equals(skin) || "violet".equals(skin))) accent = blend(accent, 0xffffffff, 0.4f);
        return new Palette(base.background, base.surface, base.surfaceMuted,
                base.textPrimary, base.textSecondary, base.divider, accent,
                blend(base.surface, accent, dark ? 0.18f : 0.10f), base.danger, base.dangerSoft);
    }

    private static int blend(int base, int tint, float amount) {
        return android.graphics.Color.rgb(
                Math.round(android.graphics.Color.red(base) * (1 - amount) + android.graphics.Color.red(tint) * amount),
                Math.round(android.graphics.Color.green(base) * (1 - amount) + android.graphics.Color.green(tint) * amount),
                Math.round(android.graphics.Color.blue(base) * (1 - amount) + android.graphics.Color.blue(tint) * amount));
    }

    public static final class Palette {
        public static final Palette LIGHT = new Palette(
                0xfff5f7fa, 0xffffffff, 0xfff8fafc, 0xff1d2430, 0xff626b79,
                0xffdfe4ea, 0xff276ef1, 0xffedf3ff, 0xffba1a1a, 0xfffcebea);
        public static final Palette DARK = new Palette(
                0xff111419, 0xff1c2128, 0xff252b34, 0xfff2f5f8, 0xffaeb7c4,
                0xff353d48, 0xff8ab4ff, 0xff1e355d, 0xffffb4ab, 0xff5c1f1f);

        public final int background;
        public final int surface;
        public final int surfaceMuted;
        public final int textPrimary;
        public final int textSecondary;
        public final int divider;
        public final int accent;
        public final int accentSoft;
        public final int danger;
        public final int dangerSoft;

        private Palette(int background, int surface, int surfaceMuted, int textPrimary,
                        int textSecondary, int divider, int accent, int accentSoft,
                        int danger, int dangerSoft) {
            this.background = background;
            this.surface = surface;
            this.surfaceMuted = surfaceMuted;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.divider = divider;
            this.accent = accent;
            this.accentSoft = accentSoft;
            this.danger = danger;
            this.dangerSoft = dangerSoft;
        }
    }
}
