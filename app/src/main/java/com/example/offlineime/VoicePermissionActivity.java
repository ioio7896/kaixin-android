package com.example.offlineime;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/** Runtime permissions require an activity; return to the editor before recording. */
public final class VoicePermissionActivity extends Activity {
    private static final int REQUEST_MICROPHONE = 41;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish();
        } else if (state == null) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_MICROPHONE) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, granted ? "已允许录音，请返回输入框再次点击语音"
                : "未获得录音权限，可在系统设置中允许", Toast.LENGTH_LONG).show();
        finish();
    }
}
