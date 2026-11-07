package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** Shared, unscaled keyboard geometry for the window and its content. */
final class KeyboardPlacement {
    private KeyboardPlacement() { }
    static String side(SharedPreferences p) {
        String value = p.getString("one_hand_side", p.getBoolean("one_hand", false) ? "right" : "off");
        return "left".equals(value) || "right".equals(value) ? value : "off";
    }
    static String preset(Context c, SharedPreferences p) {
        return p.getString("tablet_preset_" + axis(c), "legacy");
    }
    static boolean floating(Context c, SharedPreferences p) {
        String preset = preset(c, p);
        return "legacy".equals(preset) ? p.getBoolean("floating", false) : "floating".equals(preset);
    }
    static String side(Context c, SharedPreferences p) {
        String preset = preset(c, p);
        if ("legacy".equals(preset)) {
            String side = side(p);
            if ("off".equals(side) && !p.getBoolean("floating", false)
                    && !p.getBoolean("split_keyboard", false) && compactNine(c)) return "right";
            return side;
        }
        if ("split".equals(preset) && compactNine(c)) return "right";
        return "left".equals(preset) || "right".equals(preset) ? preset : "off";
    }
    private static boolean compactNine(Context c) {
        return c instanceof OfflineImeService && ((OfflineImeService) c).compactNineLayout()
                && c.getResources().getConfiguration().screenWidthDp >= 600;
    }
    static boolean landscape(Context c) {
        return c.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
    static String axis(Context c) { return landscape(c) ? "landscape" : "portrait"; }
    static int defaultHeight(Context c) {
        Configuration config = c.getResources().getConfiguration();
        boolean tablet = config.smallestScreenWidthDp >= 600;
        return tablet ? (landscape(c) ? 300 : 320) : (landscape(c) ? 220 : KeyboardSizing.DEFAULT_HEIGHT);
    }
    static int height(Context c, SharedPreferences p) {
        int screen = c.getResources().getConfiguration().screenHeightDp;
        int value = landscape(c) ? p.getInt("key_height_landscape", defaultHeight(c))
                : (p.contains("key_height") ? KeyboardSizing.height(p) : defaultHeight(c));
        // All keyboard modes and panels share this height. Never branch on the
        // input mode here: switching 9-key/26-key/numbers must not move the host.
        if (floating(c, p)) value = p.getInt("floating_height_" + axis(c), p.getInt("floating_height", value));
        int limit = Math.max(1, Math.round(screen * (landscape(c) ? 0.60f : 0.72f)));
        return Math.min(Math.max(landscape(c) ? 120 : 180, value), limit);
    }
    static int width(Context c, SharedPreferences p) {
        int screen = Math.max(1, c.getResources().getConfiguration().screenWidthDp);
        if (floating(c, p)) return Math.min(screen, Math.max(Math.min(280, screen),
                p.getInt("floating_width_" + axis(c), p.getInt("floating_width", 340))));
        String preset = preset(c, p);
        if (!"off".equals(side(c, p))) {
            int width = screen * p.getInt("one_hand_width", 80) / 100;
            if (screen >= 600) width = Math.min(width, compactNine(c) ? 420 : 560);
            return Math.min(screen, Math.max(Math.min(280, screen), width));
        }
        if (compactNine(c)) return Math.min(420, screen);
        if ("split".equals(preset) && screen >= 600) return screen;
        if (screen >= 600) return Math.min(780, screen);
        return landscape(c) ? Math.min(680, screen) : screen;
    }
    static boolean split(Context c, SharedPreferences p) {
        String preset = preset(c, p);
        boolean requested = "legacy".equals(preset) ? p.getBoolean("split_keyboard", false) : "split".equals(preset);
        return requested && !compactNine(c) && width(c, p) >= 600;
    }
    static float splitGap(SharedPreferences p) {
        return Math.max(1f, Math.min(4f, p.getInt("split_gap", 2)));
    }
}
