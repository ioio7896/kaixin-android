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
    static boolean landscape(Context c) {
        return c.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
    static String axis(Context c) { return landscape(c) ? "landscape" : "portrait"; }
    static int height(Context c, SharedPreferences p) {
        int screen = c.getResources().getConfiguration().screenHeightDp;
        int value = landscape(c) ? p.getInt("key_height_landscape", 240) : KeyboardSizing.height(p);
        if (p.getBoolean("floating", false)) value = p.getInt("floating_height", value);
        return Math.min(Math.max(180, value), Math.max(180, Math.round(screen * (landscape(c) ? 0.65f : 0.72f))));
    }
    static int width(Context c, SharedPreferences p) {
        int screen = c.getResources().getConfiguration().screenWidthDp;
        if (p.getBoolean("floating", false)) return Math.min(screen, Math.max(Math.min(280, screen), p.getInt("floating_width", 340)));
        if (!"off".equals(side(p))) return Math.min(screen, Math.max(Math.min(280, screen), screen * p.getInt("one_hand_width", 82) / 100));
        return landscape(c) ? Math.min(780, screen) : screen;
    }
    static boolean split(Context c, SharedPreferences p) {
        return p.getBoolean("split_keyboard", false) && width(c, p) >= 600;
    }
}
