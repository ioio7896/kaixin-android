package com.example.offlineime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Offline artwork for the emoji picker. The committed text is always Unicode. */
final class EmojiStyleAssets {
    static final String SYSTEM = "system";
    static final String TWEMOJI = "twemoji";
    static final String FLUENT = "fluent";
    static final String OPENMOJI = "openmoji";
    static final String[] STYLES = {SYSTEM, TWEMOJI, FLUENT, OPENMOJI};

    private final Context context;
    private final Map<String, Set<String>> available = new HashMap<>();
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    EmojiStyleAssets(Context context) { this.context = context.getApplicationContext(); }

    static String label(String style) {
        switch (style) {
            case TWEMOJI: return "Twemoji";
            case FLUENT: return "Fluent";
            case OPENMOJI: return "OpenMoji";
            default: return "系统";
        }
    }

    static boolean known(String style) {
        for (String value : STYLES) if (value.equals(style)) return true;
        return false;
    }

    Bitmap get(String style, String emoji) {
        if (SYSTEM.equals(style)) return null;
        String name = key(emoji) + (FLUENT.equals(style) ? ".webp" : ".png");
        if (!files(style).contains(name)) return null;
        String cacheKey = style + '/' + name;
        Bitmap cached = bitmaps.get(cacheKey);
        if (cached != null) return cached;
        try (InputStream stream = context.getAssets().open("emoji_styles/" + cacheKey)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            if (FLUENT.equals(style)) options.inSampleSize = 2;
            Bitmap decoded = BitmapFactory.decodeStream(stream, null, options);
            if (decoded != null) bitmaps.put(cacheKey, decoded);
            return decoded;
        } catch (IOException ignored) {
            return null;
        }
    }

    private Set<String> files(String style) {
        Set<String> names = available.get(style);
        if (names != null) return names;
        try {
            String[] listed = context.getAssets().list("emoji_styles/" + style);
            names = listed == null ? Collections.emptySet() : new HashSet<>(java.util.Arrays.asList(listed));
        } catch (IOException ignored) {
            names = Collections.emptySet();
        }
        available.put(style, names);
        return names;
    }

    private static String key(String value) {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            if (result.length() > 0) result.append('-');
            result.append(Integer.toHexString(point).toLowerCase(Locale.ROOT));
            offset += Character.charCount(point);
        }
        return result.toString();
    }
}
