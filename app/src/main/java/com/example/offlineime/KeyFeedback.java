package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.media.AudioManager;

/** One application-scoped settings listener; no preference reads on touch DOWN. */
final class KeyFeedback {
    private static KeyFeedback instance;
    private volatile int hapticLevel;
    private volatile float soundVolume;
    private final SharedPreferences preferences;
    private final AudioManager audioManager;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
        if (key == null || "haptic".equals(key) || "sound".equals(key)
                || "haptic_strength".equals(key) || "sound_volume".equals(key)) reload();
    };

    static synchronized KeyFeedback get(Context context) {
        if (instance == null) instance = new KeyFeedback(context.getApplicationContext());
        return instance;
    }

    private KeyFeedback(Context context) {
        preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        reload();
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    private void reload() {
        String haptic = preferences.getString("haptic_strength",
                preferences.getBoolean("haptic", true) ? "standard" : "off");
        hapticLevel = "off".equals(haptic) ? 0 : "light".equals(haptic) ? 1
                : "strong".equals(haptic) ? 3 : 2;
        String sound = preferences.getString("sound_volume",
                preferences.getBoolean("sound", false) ? "medium" : "off");
        soundVolume = "low".equals(sound) ? 0.25f : "medium".equals(sound) ? 0.55f
                : "high".equals(sound) ? 1f : 0f;
    }

    void press(View view) {
        if (hapticLevel > 0) {
            // Keep every key pulse short so successive presses stay distinct.
            // LONG_PRESS lingers and feels smeared during rapid typing.
            int feedback = hapticLevel == 1 ? HapticFeedbackConstants.CLOCK_TICK
                    : hapticLevel == 3 ? HapticFeedbackConstants.VIRTUAL_KEY
                    : HapticFeedbackConstants.KEYBOARD_TAP;
            view.performHapticFeedback(feedback);
        }
        view.setSoundEffectsEnabled(false);
        if (soundVolume > 0 && audioManager != null)
            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, soundVolume);
    }
}
