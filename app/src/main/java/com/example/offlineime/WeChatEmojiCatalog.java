package com.example.offlineime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thumbnails are local; only the bracketed text marker is committed to an editor. */
final class WeChatEmojiCatalog {
    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    private static final int MAX_TOKEN_LENGTH;
    private static final LruCache<String, Bitmap> ICONS = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    static {
        // Familiar faces first. The remaining faces retain a predictable catalog order.
        String[] names = {"微笑", "呲牙", "捂脸", "偷笑", "大哭", "流泪", "害羞", "得意",
                "调皮", "憨笑", "坏笑", "奸笑", "亲亲", "可怜", "愉快", "耶",
                "撇嘴", "色", "发呆", "闭嘴", "睡", "尴尬", "发怒", "惊讶",
                "难过", "囧", "抓狂", "吐", "白眼", "傲慢", "困", "惊恐",
                "悠闲", "咒骂", "疑问", "嘘", "晕", "衰", "骷髅", "敲打",
                "再见", "擦汗", "抠鼻", "鼓掌", "右哼哼", "鄙视", "委屈", "快哭了",
                "阴险", "嘿哈", "机智", "皱眉", "吃瓜", "加油", "汗", "天啊",
                "Emm", "社会社会", "旺柴", "好的", "打脸", "哇", "翻白眼", "666",
                "让我看看", "叹气", "苦涩", "裂开", "笑脸", "生病", "脸红", "恐惧",
                "失望", "无语", "破涕为笑"};
        int maxTokenLength = 0;
        for (String name : names) {
            String token = "[" + name + "]";
            NAMES.put(token, name);
            maxTokenLength = Math.max(maxTokenLength, token.length());
        }
        MAX_TOKEN_LENGTH = maxTokenLength;
    }

    private WeChatEmojiCatalog() { }

    static String[] values() { return NAMES.keySet().toArray(new String[0]); }
    static boolean contains(String token) { return NAMES.containsKey(token); }
    static String name(String token) { return NAMES.get(token); }

    static int maxTokenLength() { return MAX_TOKEN_LENGTH; }

    static int trailingTokenLength(CharSequence text) {
        if (text == null || text.length() == 0 || text.charAt(text.length() - 1) != ']') return 0;
        int end = text.length();
        for (int start = end - 2; start >= Math.max(0, end - MAX_TOKEN_LENGTH); start--) {
            if (text.charAt(start) == '[') {
                return contains(text.subSequence(start, end).toString()) ? end - start : 0;
            }
        }
        return 0;
    }

    static Drawable icon(Context context, String token, int size) {
        String name = name(token);
        if (name == null) return null;
        String key = token + ":" + size;
        Bitmap bitmap = ICONS.get(key);
        if (bitmap == null) {
            String path = "emoji/wechat/" + name + ".png";
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream input = context.getAssets().open(path)) {
                BitmapFactory.decodeStream(input, null, options);
            } catch (IOException missing) { return null; }
            options.inJustDecodeBounds = false;
            options.inScaled = false;
            options.inSampleSize = 1;
            while (options.outWidth / (options.inSampleSize * 2) >= size
                    && options.outHeight / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2;
            try (InputStream input = context.getAssets().open(path)) {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            } catch (IOException missing) { return null; }
            if (bitmap == null) return null;
            ICONS.put(key, bitmap);
        }
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
