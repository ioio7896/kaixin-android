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
    private static final String REQUEST_IN_PROGRESS = "request_in_progress";
    private boolean requestInProgress;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }
        requestInProgress = state != null && state.getBoolean(REQUEST_IN_PROGRESS);
        if (requestInProgress) return;
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean previouslyRequested = prefs.getBoolean(PREF_REQUESTED, false);
        if (previouslyRequested && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showSettingsPrompt();
        } else {
            RoundedCardPopup.showDialog(new AlertDialog.Builder(this)
                    .setTitle("离线语音输入")
                    .setMessage(R.string.voice_permission_explanation)
                    .setNegativeButton(R.string.optional_feature_not_now, (dialog, which) -> finish())
                    .setPositiveButton(R.string.voice_permission_continue, (dialog, which) -> {
                        prefs.edit().putBoolean(PREF_REQUESTED, true).apply();
                        requestInProgress = true;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
                    })
                    .setOnCancelListener(dialog -> finish())
                    .create());
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(REQUEST_IN_PROGRESS, requestInProgress);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_MICROPHONE) return;
        requestInProgress = false;
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
