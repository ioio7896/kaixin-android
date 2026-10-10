package com.example.offlineime;

import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable password backup; no AndroidKeyStore key is exported. */
final class WordBackup {
    static boolean encrypted(String data) {
        try { return "offlineime-password-backup".equals(new JSONObject(data).optString("format")); }
        catch (Exception invalid) { return false; }
    }
    static String encrypt(String plain, char[] password) throws Exception {
        if (password.length < 8) throw new IllegalArgumentException("备份口令至少 8 个字符");
        byte[] salt = new byte[16], iv = new byte[12];
        SecureRandom random = new SecureRandom(); random.nextBytes(salt); random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt, 150000), new GCMParameterSpec(128, iv));
        cipher.updateAAD("offlineime-backup-v1".getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("format", "offlineime-password-backup").put("version", 1)
                .put("iterations", 150000).put("salt", encode(salt)).put("iv", encode(iv)).put("data", encode(ciphertext)).toString();
    }
    static String decrypt(String backup, char[] password) throws Exception {
        JSONObject root = new JSONObject(backup);
        int iterations = root.getInt("iterations");
        if (root.getInt("version") != 1 || iterations < 100000 || iterations > 500000) throw new IllegalArgumentException("不支持的备份格式");
        byte[] salt = Base64.decode(root.getString("salt"), Base64.NO_WRAP);
        byte[] iv = Base64.decode(root.getString("iv"), Base64.NO_WRAP);
        if (salt.length != 16 || iv.length != 12) throw new IllegalArgumentException("备份文件格式不正确");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt, iterations), new GCMParameterSpec(128, iv));
        cipher.updateAAD("offlineime-backup-v1".getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.decode(root.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    private static SecretKeySpec derive(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES"); }
        finally { spec.clearPassword(); }
    }
    private static String encode(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP); }
    private WordBackup() {}
}
