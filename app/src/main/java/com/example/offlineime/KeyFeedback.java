package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.media.AudioManager;

/** One application-scoped settings listener; no preference reads on touch DOWN. */
final class KeyFeedback {
    private static final long[] PULSE_MS = {0, 8, 11, 15};
    private static final int[] AMPLITUDES = {0, 95, 150, 210};
    private static final float[] CLICK_SCALES = {0f, 0.35f, 0.60f, 0.90f};
    private static KeyFeedback instance;
    private volatile int hapticLevel;
    private volatile float soundVolume;
    private volatile long lastPressAt;
    private final SharedPreferences preferences;
    private final AudioManager audioManager;
    private final Vibrator vibrator;
    // All effect creation and vibrator IPC take place on this worker.
    private Handler worker;
    private VibrationEffect[] effects;
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
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
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
        lastPressAt = android.os.SystemClock.uptimeMillis();
        view.setSoundEffectsEnabled(false);
        if (hapticLevel == 0 && soundVolume == 0) return;
        if (worker == null) {
            HandlerThread thread = new HandlerThread("ime-key-feedback");
            thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(this::createEffects);
        }
        worker.post(this::playFeedback);
    }

    private void createEffects() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        effects = new VibrationEffect[PULSE_MS.length];
        boolean amplitude = vibrator.hasAmplitudeControl();
        boolean click = Build.VERSION.SDK_INT >= 30
                && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK);
        for (int level = 1; level < effects.length; level++) {
            if (Build.VERSION.SDK_INT >= 30 && click) {
                effects[level] = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, CLICK_SCALES[level])
                        .compose();
            } else {
                effects[level] = VibrationEffect.createOneShot(PULSE_MS[level],
                        amplitude ? AMPLITUDES[level] : VibrationEffect.DEFAULT_AMPLITUDE);
            }
        }
    }

    private void playFeedback() {
        int level = hapticLevel;
        if (level > 0 && effects != null) vibrator.vibrate(effects[level]);
        float volume = soundVolume;
        if (volume > 0 && audioManager != null)
            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, volume);
    }

    boolean quietFor(long milliseconds) {
        return android.os.SystemClock.uptimeMillis() - lastPressAt >= milliseconds;
    }
}
