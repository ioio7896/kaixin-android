package com.example.offlineime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.Switch;

/** Shared layout and control styling for the settings pages. */
final class SettingsUi {
    static final int PAGE_INSET = 16;
    static final int CARD_INSET = 16;
    static final int CARD_RADIUS = 20;
    static final int CONTROL_RADIUS = 14;
    static final int ROW_MIN_HEIGHT = 64;
    // Switch measures its track against twice the thumb width; leave room for both ends.
    static final int SWITCH_WIDTH = 60;

    private SettingsUi() { }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context context, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    static StateListDrawable pressable(Context context, int normal, int pressed, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(context, pressed, radius));
        states.addState(new int[]{}, rounded(context, normal, radius));
        return states;
    }

    static void styleCapsuleSwitch(Switch control, Context context, UiTokens.Palette palette) {
        int[][] states = {
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        StateListDrawable track = new StateListDrawable();
        int[] trackColors = {palette.accent, palette.divider};
        for (int i = 0; i < states.length; i++) {
            GradientDrawable shape = rounded(context, trackColors[i], 16);
            shape.setSize(dp(context, SWITCH_WIDTH), dp(context, 32));
            track.addState(states[i], shape);
        }
        GradientDrawable thumbShape = rounded(context, Color.WHITE, 14);
        thumbShape.setSize(dp(context, 28), dp(context, 28));
        thumbShape.setStroke(dp(context, 1), palette.divider);
        control.setShowText(false);
        control.setSplitTrack(false);
        control.setThumbTextPadding(0);
        control.setSwitchPadding(0);
        control.setSwitchMinWidth(dp(context, SWITCH_WIDTH));
        control.setMinWidth(dp(context, SWITCH_WIDTH));
        control.setMinHeight(dp(context, 32));
        control.setPadding(0, 0, 0, 0);
        control.setBackground(null);
        control.setThumbTintList(null);
        control.setTrackTintList(null);
        control.setThumbDrawable(thumbShape);
        control.setTrackDrawable(track);
    }

    static void styleActionButton(Button button, Context context, UiTokens.Palette palette,
                                  boolean primary, boolean danger) {
        int foreground = primary ? Color.WHITE : danger ? palette.danger : palette.accent;
        int fill = primary ? palette.accent : danger ? palette.dangerSoft : palette.accentSoft;
        int pressed = primary ? darker(palette.accent) : palette.surfaceMuted;
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(foreground);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setMinHeight(dp(context, 48));
        button.setBackground(pressable(context, fill, pressed, CONTROL_RADIUS));
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        button.setElevation(0f);
    }

    private static int darker(int color) {
        return Color.rgb((int) (Color.red(color) * 0.82f),
                (int) (Color.green(color) * 0.82f),
                (int) (Color.blue(color) * 0.82f));
    }
}
