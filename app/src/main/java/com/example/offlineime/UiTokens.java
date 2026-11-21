package com.example.offlineime;

import android.content.Context;

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
    public static final int TITLE_SP = 20;
    public static final int BODY_SP = 15;
    public static final int CAPTION_SP = 13;

    private UiTokens() {}

    public static boolean isDark(Context context) {
        return KeyboardTheme.resolveDark(context);
    }

    public static Palette palette(Context context) {
        boolean dark = isDark(context);
        Palette base = dark ? Palette.DARK : Palette.LIGHT;
        return new Palette(base.background, base.surface, base.surfaceMuted,
                base.textPrimary, base.textSecondary, base.divider, base.accent,
                base.accentSoft, base.danger, base.dangerSoft);
    }

    public static final class Palette {
        public static final Palette LIGHT = new Palette(
                0xfff4f6fa, 0xffffffff, 0xffeaf0f8, 0xff1b2430, 0xff5b6675,
                0xffdce3ec, 0xff315fa8, 0xffe0eafb, 0xffb3261e, 0xfffce8e6);
        public static final Palette DARK = new Palette(
                0xff12151b, 0xff1d222b, 0xff2a313d, 0xfff0f3f9, 0xffb6c0cf,
                0xff485364, 0xffadc9ff, 0xff263b58, 0xffffb4ab, 0xff4a2926);

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
