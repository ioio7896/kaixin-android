package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.view.WindowManager;

/** Shared, unscaled keyboard geometry for the window and its content. */
final class KeyboardPlacement {
    private KeyboardPlacement() { }
    static String side(SharedPreferences p) {
        String value = p.getString("one_hand_side", p.getBoolean("one_hand", false) ? "right" : "off");
        return "left".equals(value) || "right".equals(value) ? value : "off";
    }
    static int availableWidthDp(Context c) {
        Configuration config = c.getResources().getConfiguration();
        int configWidth = Math.max(1, config.screenWidthDp);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowManager wm = c.getSystemService(WindowManager.class);
            if (wm != null) {
                Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                float density = Math.max(0.5f, c.getResources().getDisplayMetrics().density);
                int metricWidth = Math.round(bounds.width() / density);
                if (metricWidth > 0) return Math.max(1, Math.min(configWidth, metricWidth));
            }
        }
        return configWidth;
    }
    static int availableHeightDp(Context c) {
        Configuration config = c.getResources().getConfiguration();
        int configHeight = Math.max(1, config.screenHeightDp);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowManager wm = c.getSystemService(WindowManager.class);
            if (wm != null) {
                Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                float density = Math.max(0.5f, c.getResources().getDisplayMetrics().density);
                int metricHeight = Math.round(bounds.height() / density);
                if (metricHeight > 0) return Math.max(1, Math.min(configHeight, metricHeight));
            }
        }
        return configHeight;
    }
    static boolean isTabletWidth(Context c) { return availableWidthDp(c) >= 600; }
    static boolean hardwareKeyboardConnected(Context c) {
        return c.getResources().getConfiguration().hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }
    static boolean hardwareCompactBar(Context c, SharedPreferences p) {
        return hardwareKeyboardConnected(c)
                && p.getBoolean("hw_keyboard_enabled", true)
                && p.getBoolean("hw_keyboard_hide_soft", true);
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
                && isTabletWidth(c);
    }
    static boolean landscape(Context c) {
        return c.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
    static String axis(Context c) { return landscape(c) ? "landscape" : "portrait"; }
    static int defaultHeight(Context c) {
        Configuration config = c.getResources().getConfiguration();
        boolean tablet = config.smallestScreenWidthDp >= 600 && isTabletWidth(c);
        return tablet ? (landscape(c) ? 300 : 320) : (landscape(c) ? 220 : KeyboardSizing.DEFAULT_HEIGHT);
    }
    static int height(Context c, SharedPreferences p) {
        int screen = availableHeightDp(c);
        int value = landscape(c) ? SettingsRepository.integer(p, "key_height_landscape", defaultHeight(c))
                : (p.contains("key_height") ? KeyboardSizing.height(p) : defaultHeight(c));
        // Typing layouts and regular panels share this height. Handwriting
        // measures its half- and full-screen surfaces separately.
        if (floating(c, p)) value = SettingsRepository.integer(p, "floating_height_" + axis(c), SettingsRepository.integer(p, "floating_height", value));
        int limit = Math.max(1, Math.round(screen * (landscape(c) ? 0.60f : 0.72f)));
        return Math.min(Math.max(landscape(c) ? 120 : 180, value), limit);
    }
    static String tabletWidthMode(SharedPreferences p) {
        String value = p.getString("tablet_width_mode", "standard");
        return "compact".equals(value) || "full".equals(value) ? value : "standard";
    }
    static int tabletEdgeMargin(Context c, SharedPreferences p) {
        if (!isTabletWidth(c) || floating(c, p) || !"off".equals(side(c, p))) return 0;
        return Math.max(0, Math.min(32, SettingsRepository.integer(p, "tablet_edge_margin", 0)));
    }
    static int width(Context c, SharedPreferences p) {
        int screen = availableWidthDp(c);
        if (floating(c, p)) return Math.min(screen, Math.max(Math.min(280, screen),
                SettingsRepository.integer(p, "floating_width_" + axis(c), SettingsRepository.integer(p, "floating_width", 340))));
        String preset = preset(c, p);
        if (!"off".equals(side(c, p))) {
            int width = screen * SettingsRepository.integer(p, "one_hand_width", 80) / 100;
            if (screen >= 600) width = Math.min(width, compactNine(c) ? 440 : 560);
            return Math.min(screen, Math.max(Math.min(280, screen), width));
        }
        if (compactNine(c)) return Math.min(440, screen);
        if ("split".equals(preset) && screen >= 600) return screen;
        if (screen >= 600) {
            String mode = tabletWidthMode(p);
            if ("full".equals(mode)) return screen;
            return Math.min("compact".equals(mode) ? 720 : 860, screen);
        }
        return landscape(c) ? Math.min(680, screen) : screen;
    }
    static boolean split(Context c, SharedPreferences p) {
        String preset = preset(c, p);
        boolean requested = "legacy".equals(preset) ? p.getBoolean("split_keyboard", false) : "split".equals(preset);
        return requested && !compactNine(c) && width(c, p) >= 600;
    }
    static float splitGap(SharedPreferences p) {
        return Math.max(1f, Math.min(4f, SettingsRepository.integer(p, "split_gap", 2)));
    }
    static String splitCenterPanel(Context c, SharedPreferences p) {
        if (!split(c, p) || width(c, p) < 680) return "empty";
        String value = p.getString("split_center_panel", "empty");
        return "numpad".equals(value) || "edit".equals(value) ? value : "empty";
    }
    static boolean tabletPcLayout(Context c, SharedPreferences p) {
        return isTabletWidth(c) && !floating(c, p) && "off".equals(side(c, p))
                && !split(c, p) && p.getBoolean("tablet_pc_layout", true);
    }
}
