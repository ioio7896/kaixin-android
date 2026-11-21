package com.example.offlineime;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Storage for emoji history, ordered favorites and preferred skin-tone variants. */
final class EmojiPreferences {
    private static final String FAVORITES = "favorites_v2";

    private EmojiPreferences() { }

    static List<String> recent(SharedPreferences prefs) {
        List<String> values = read(prefs, "recent_v2");
        if (values != null) return values;
        String legacy = prefs.getString("recent", "");
        return legacy.isEmpty() ? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(legacy.split("\\|")));
    }

    static void remember(SharedPreferences prefs, String glyph) {
        List<String> values = recent(prefs);
        values.remove(glyph);
        values.add(0, glyph);
        prefs.edit().putString("recent_v2", new JSONArray(values.subList(0, Math.min(32, values.size()))).toString()).apply();
    }

    static List<String> favorites(SharedPreferences prefs) {
        List<String> values = read(prefs, FAVORITES);
        if (values != null) return values;
        values = new ArrayList<>();
        for (var entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("favorite_") && Boolean.TRUE.equals(entry.getValue()))
                values.add(entry.getKey().substring(9));
        }
        Collections.sort(values);
        return values;
    }

    static void toggleFavorite(SharedPreferences prefs, String glyph) {
        List<String> values = favorites(prefs);
        if (!values.remove(glyph)) values.add(glyph);
        writeFavorites(prefs, values);
    }

    static void moveFavorite(SharedPreferences prefs, String glyph, int direction) {
        List<String> values = favorites(prefs);
        int old = values.indexOf(glyph);
        int next = old + direction;
        if (old < 0 || next < 0 || next >= values.size()) return;
        Collections.swap(values, old, next);
        writeFavorites(prefs, values);
    }

    private static void writeFavorites(SharedPreferences prefs, List<String> values) {
        SharedPreferences.Editor editor = prefs.edit().putString(FAVORITES, new JSONArray(values).toString());
        if (!prefs.contains(FAVORITES)) {
            for (String key : prefs.getAll().keySet()) if (key.startsWith("favorite_")) editor.remove(key);
        }
        editor.apply();
    }

    static String preferred(SharedPreferences prefs, EmojiCatalog catalog, String glyph) {
        if (!catalog.hasToneChoices(glyph)) return glyph;
        String variant = prefs.getString(toneKey(glyph), glyph);
        return catalog.base(variant).equals(glyph) && EmojiCatalog.visible(variant) ? variant : glyph;
    }

    static void saveTone(SharedPreferences prefs, EmojiCatalog catalog, String variant) {
        String base = catalog.base(variant);
        SharedPreferences.Editor editor = prefs.edit();
        if (base.equals(variant)) editor.remove(toneKey(base));
        else editor.putString(toneKey(base), variant);
        editor.apply();
    }

    private static String toneKey(String glyph) { return "tone_" + EmojiCatalog.key(glyph); }

    private static List<String> read(SharedPreferences prefs, String key) {
        String json = prefs.getString(key, null);
        if (json == null) return null;
        try {
            JSONArray array = new JSONArray(json);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String value = array.getString(i);
                if (!value.isEmpty()) values.add(value);
            }
            return values;
        } catch (JSONException ignored) {
            return null;
        }
    }
}
