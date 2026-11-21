package com.example.offlineime;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

/** Session-specific editor identity, privacy policy, layout and selection coordinates. */
final class EditorSession {
    EditorInfo info;
    boolean sensitive, numeric, forcedLayout;
    String layout = "text";
    int selectionStart = -1, selectionEnd = -1;
    volatile long generation;
    static boolean sameEditor(EditorInfo left, EditorInfo right) {
        return left != null && right != null && java.util.Objects.equals(left.packageName, right.packageName)
                && left.fieldId == right.fieldId && left.inputType == right.inputType;
    }
    static String classifyEditorLayout(EditorInfo info) {
        return classifyEditorLayout(info == null ? InputType.TYPE_CLASS_TEXT : info.inputType);
    }

    static String classifyEditorLayout(int type) {
        int cls = type & InputType.TYPE_MASK_CLASS;
        int variation = type & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_PHONE) return "phone";
        if (cls == InputType.TYPE_CLASS_DATETIME) {
            if (variation == InputType.TYPE_DATETIME_VARIATION_DATE) return "date";
            if (variation == InputType.TYPE_DATETIME_VARIATION_TIME) return "time";
            return "datetime";
        }
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            if ((type & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0 && (type & InputType.TYPE_NUMBER_FLAG_SIGNED) != 0) return "signed_decimal";
            if ((type & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0) return "decimal";
            if ((type & InputType.TYPE_NUMBER_FLAG_SIGNED) != 0) return "signed";
            return "number";
        }
        if (cls == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) return "email";
            if (variation == InputType.TYPE_TEXT_VARIATION_URI) return "uri";
        }
        return "text";
    }

    static boolean isSensitiveEditor(EditorInfo info) {
        if (info == null) return false;
        return isSensitiveInput(info.inputType, info.imeOptions);
    }

    static boolean isSensitiveInput(int type, int imeOptions) {
        if ((imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true;
        int cls = type & InputType.TYPE_MASK_CLASS;
        int variation = type & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        return cls == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
    }

}
