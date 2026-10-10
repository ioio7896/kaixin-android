package com.example.offlineime;

import android.os.Build;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

final class AccessibilitySupport {
    private AccessibilitySupport() { }
    static void longClick(View view, String label) {
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (host.isLongClickable()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, label));
                info.setSelected(host.isSelected());
                if (Build.VERSION.SDK_INT >= 30 && host.isSelected()) info.setStateDescription("已选择");
            }
        });
    }
    static void selected(View view, boolean selected) {
        view.setSelected(selected);
        if (Build.VERSION.SDK_INT >= 30) view.setStateDescription(selected ? "已选择" : null);
    }
}
