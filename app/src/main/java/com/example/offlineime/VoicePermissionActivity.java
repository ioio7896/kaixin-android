package com.example.offlineime;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Runtime permissions require an activity; guides permanent denials directly to system settings. */
public final class VoicePermissionActivity extends Activity {
    private static final int REQUEST_MICROPHONE = 41;
    private static final String PREF_REQUESTED = "voice_permission_requested";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }
        if (state != null) return;
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean previouslyRequested = prefs.getBoolean(PREF_REQUESTED, false);
        if (previouslyRequested && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showSettingsPrompt();
        } else {
            prefs.edit().putBoolean(PREF_REQUESTED, true).apply();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_MICROPHONE) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            Toast.makeText(this, "已允许录音，请返回输入框开始语音输入", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showSettingsPrompt();
            return;
        }
        Toast.makeText(this, "未获得录音权限，可在系统设置中随时允许", Toast.LENGTH_LONG).show();
        finish();
    }

    private void showSettingsPrompt() {
        new AlertDialog.Builder(this)
                .setTitle("需要麦克风权限")
                .setMessage("离线语音输入仅在本机实时识别文字，不保存录音，也不联网上传。请在系统应用设置中开启「麦克风」权限。")
                .setPositiveButton("去系统设置", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (RuntimeException ignored) {
                        Toast.makeText(this, "无法打开系统设置，请手动在应用权限中开启麦克风", Toast.LENGTH_LONG).show();
                    }
                    finish();
                })
                .setNegativeButton("取消", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }
}
