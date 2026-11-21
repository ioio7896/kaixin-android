package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small encrypted, versioned store backed by AtomicFile binary payloads.
 * Seamlessly reads and migrates legacy Base64 SharedPreferences entries.
 * Logs only error types, never private content.
 */
final class SecureLocalStore {
    private static final String TAG = "OfflineImeStorage";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String STORE_DIR = "secure_store";
    private static final byte FORMAT_VERSION = 1;
    private static final ConcurrentHashMap<File, Object> fileLocks = new ConcurrentHashMap<>();
    private static final java.util.Set<String> unreadable = java.util.concurrent.ConcurrentHashMap.newKeySet();

    enum ReadStatus { EMPTY, READY, ERROR }
    static final class ReadResult {
        final ReadStatus status;
        final String value;
        ReadResult(ReadStatus status, String value) { this.status = status; this.value = value; }
        boolean succeeded() { return status != ReadStatus.ERROR; }
    }

    static void markUnreadable(String preferencesName) { unreadable.add(preferencesName); }
    static boolean isUnreadable(String preferencesName) { return unreadable.contains(preferencesName); }
    static boolean hasReadErrors() { return !unreadable.isEmpty(); }

    private SecureLocalStore() { }

    static String read(Context context, String preferencesName, String keyAlias) {
        ReadResult result = readResult(context, preferencesName, keyAlias);
        if (!result.succeeded()) throw new IllegalStateException("本地数据读取失败，原数据已保留；请重新打开页面重试");
        return result.value;
    }

    static ReadResult readResult(Context context, String preferencesName, String keyAlias) {
        File target = storeFile(context, preferencesName);
        synchronized (lockFor(target)) {
            return readLocked(context, preferencesName, keyAlias, target);
        }
    }

    private static ReadResult readLocked(Context context, String preferencesName, String keyAlias, File target) {
        try {
            if (target.exists() || new File(target.getPath() + ".bak").exists()) {
                byte[] raw = new AtomicFile(target).readFully();
                return new ReadResult(ReadStatus.READY, decryptPayload(raw, keyAlias));
            }
            SharedPreferences preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
            // A pending-only legacy write is retained for explicit recovery, never treated as empty.
            if (!preferences.contains("blob")) {
                if (preferences.contains("blob_pending")) throw new IllegalStateException("incomplete write");
                return new ReadResult(ReadStatus.EMPTY, "");
            }
            String encoded = preferences.getString("blob", "");
            if (encoded.isEmpty()) throw new IllegalStateException("empty encrypted payload");
            byte[] raw = Base64.decode(encoded, Base64.NO_WRAP);
            return new ReadResult(ReadStatus.READY, decryptPayload(raw, keyAlias));
        } catch (Exception error) {
            markUnreadable(preferencesName);
            Log.e(TAG, "encrypted read failed: " + error.getClass().getSimpleName());
            return new ReadResult(ReadStatus.ERROR, "");
        }
    }

    private static String decryptPayload(byte[] raw, String keyAlias) throws Exception {
        if (raw == null || raw.length < 2) throw new IllegalStateException("bad format");
        boolean legacy = raw[0] != FORMAT_VERSION;
        int headerLength = legacy ? 1 : 2;
        int ivLength = raw[legacy ? 0 : 1] & 0xff;
        if (ivLength < 12 || raw.length <= headerLength + ivLength) throw new IllegalStateException("bad iv");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, existingKey(keyAlias),
                new GCMParameterSpec(128, raw, headerLength, ivLength));
        byte[] clear = cipher.doFinal(raw, headerLength + ivLength,
                raw.length - headerLength - ivLength);
        return new String(clear, StandardCharsets.UTF_8);
    }

    /** Called only after both decryption and the caller's format validation succeed. */
    static void markReadable(String preferencesName) { unreadable.remove(preferencesName); }

    static boolean write(Context context, String preferencesName, String keyAlias, String value) {
        File target = storeFile(context, preferencesName);
        synchronized (lockFor(target)) {
            return writeLocked(context, preferencesName, keyAlias, value, target);
        }
    }

    private static boolean writeLocked(Context context, String preferencesName, String keyAlias,
                                       String value, File target) {
        if (isUnreadable(preferencesName)) return false;
        FileOutputStream stream = null;
        AtomicFile atomicFile = null;
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

            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("store directory unavailable");
            }
            atomicFile = new AtomicFile(target);
            stream = atomicFile.startWrite();
            stream.write(payload);
            stream.getFD().sync();
            atomicFile.finishWrite(stream);
            stream = null;
            verifyCommittedPayload(atomicFile, payload);

            SharedPreferences legacyPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
            if (legacyPreferences.contains("blob") || legacyPreferences.contains("blob_pending")) {
                if (!legacyPreferences.edit().remove("blob").remove("blob_pending").commit()) {
                    Log.w(TAG, "legacy store cleanup failed");
                }
            }
            return true;
        } catch (Exception error) {
            if (atomicFile != null && stream != null) atomicFile.failWrite(stream);
            Log.e(TAG, "encrypted write failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    // AtomicFile may only log a commit error. Confirm that its read path sees these bytes.
    private static void verifyCommittedPayload(AtomicFile file, byte[] payload) throws IOException {
        try (FileInputStream input = file.openRead()) {
            byte[] buffer = new byte[Math.min(8192, payload.length)];
            int offset = 0;
            while (offset < payload.length) {
                int count = input.read(buffer, 0, Math.min(buffer.length, payload.length - offset));
                if (count <= 0) throw new IOException("incomplete store commit");
                for (int i = 0; i < count; i++) {
                    if (buffer[i] != payload[offset + i]) throw new IOException("store commit differs");
                }
                offset += count;
            }
            if (input.read() != -1) throw new IOException("unexpected data after store commit");
        }
    }

    static boolean clear(Context context, String preferencesName, String keyAlias) {
        File target = storeFile(context, preferencesName);
        synchronized (lockFor(target)) {
            return clearLocked(context, preferencesName, keyAlias, target);
        }
    }

    private static boolean clearLocked(Context context, String preferencesName, String keyAlias, File target) {
        // Remove the fallback first so a partial clear cannot expose stale preferences.
        if (!context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()) return false;
        new AtomicFile(target).delete();
        if (target.exists() || new File(target.getPath() + ".bak").exists()
                || new File(target.getPath() + ".new").exists()) return false;
        markReadable(preferencesName);
        try {
            KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
            store.load(null);
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias);
        } catch (Exception error) {
            Log.e(TAG, "encrypted key removal failed: " + error.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    private static Object lockFor(File target) {
        return fileLocks.computeIfAbsent(target, ignored -> new Object());
    }

    private static File storeFile(Context context, String preferencesName) {
        StringBuilder safe = new StringBuilder(preferencesName.length());
        for (int i = 0; i < preferencesName.length(); i++) {
            char ch = preferencesName.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' || ch == '.') {
                safe.append(ch);
            } else {
                safe.append('_');
            }
        }
        return new File(new File(context.getNoBackupFilesDir(), STORE_DIR), safe + ".bin");
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

    private static SecretKey existingKey(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(alias, null);
        if (!(entry instanceof KeyStore.SecretKeyEntry)) throw new IllegalStateException("missing encryption key");
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }
}
