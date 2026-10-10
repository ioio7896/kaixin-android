package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Small encrypted, versioned store. Logs only error types, never private content. */
final class SecureLocalStore {
    private static final String TAG = "OfflineImeStorage";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final byte FORMAT_VERSION = 1;

    private SecureLocalStore() { }

    static String read(Context context, String preferencesName, String keyAlias) {
        String encoded = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .getString("blob", "");
        if (encoded.isEmpty()) return "";
        try {
            byte[] raw = Base64.decode(encoded, Base64.NO_WRAP);
            if (raw.length < 2) throw new IllegalStateException("bad format");
            boolean legacy = raw[0] != FORMAT_VERSION;
            int headerLength = legacy ? 1 : 2;
            int ivLength = raw[legacy ? 0 : 1] & 0xff;
            if (ivLength < 12 || raw.length <= headerLength + ivLength) throw new IllegalStateException("bad iv");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(keyAlias),
                    new GCMParameterSpec(128, raw, headerLength, ivLength));
            byte[] clear = cipher.doFinal(raw, headerLength + ivLength,
                    raw.length - headerLength - ivLength);
            return new String(clear, StandardCharsets.UTF_8);
        } catch (Exception error) {
            Log.e(TAG, "encrypted read failed: " + error.getClass().getSimpleName());
            return "";
        }
    }

    static boolean write(Context context, String preferencesName, String keyAlias, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // AndroidKeyStore keys require a provider-generated randomized IV for encryption.
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(keyAlias));
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[2 + iv.length + encrypted.length];
            payload[0] = FORMAT_VERSION;
            payload[1] = (byte) iv.length;
            System.arraycopy(iv, 0, payload, 2, iv.length);
            System.arraycopy(encrypted, 0, payload, 2 + iv.length, encrypted.length);
            String encoded = Base64.encodeToString(payload, Base64.NO_WRAP);
            SharedPreferences preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
            // A synchronous staging commit prevents a process death from replacing good data
            // with a partial write. The final commit is one atomic preferences-file update.
            if (!preferences.edit().putString("blob_pending", encoded).commit()) return false;
            boolean saved = preferences.edit().putString("blob", encoded).remove("blob_pending").commit();
            if (!saved) Log.e(TAG, "encrypted write failed: commit");
            return saved;
        } catch (Exception error) {
            Log.e(TAG, "encrypted write failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    static boolean clear(Context context, String preferencesName, String keyAlias) {
        boolean preferencesCleared = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .edit().clear().commit();
        try {
            KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
            store.load(null);
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias);
        } catch (Exception error) {
            Log.e(TAG, "encrypted key removal failed: " + error.getClass().getSimpleName());
            return false;
        }
        return preferencesCleared;
    }

    private static SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
        store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(alias, null)).getSecretKey();
    }
}
