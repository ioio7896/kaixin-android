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
                0xfff2f2f7, 0xffffffff, 0xfff7f7fa, 0xff1c1c1e, 0xff636366,
                0xffd1d1d6, 0xff0066cc, 0xffe8f2ff, 0xffd92d20, 0xffffebe9);
        public static final Palette DARK = new Palette(
                0xff000000, 0xff1c1c1e, 0xff2c2c2e, 0xfff5f5f7, 0xffaeaeb2,
                0xff38383a, 0xff0a84ff, 0xff173a5e, 0xffff453a, 0xff4a211e);

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
