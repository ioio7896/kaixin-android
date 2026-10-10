package com.example.offlineime;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Debug-only host for repeatable IME smoke tests. */
public final class ImeTestActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(48), dp(20), dp(20));

        content.addView(label("普通文本"));
        EditText normal = field("normal_text");
        normal.setInputType(InputType.TYPE_CLASS_TEXT);
        content.addView(normal);

        content.addView(label("数字密码"));
        EditText password = field("number_password");
        password.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        content.addView(password);

        content.addView(label("禁止个性化学习"));
        EditText privateText = field("private_text");
        privateText.setInputType(InputType.TYPE_CLASS_TEXT);
        privateText.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        content.addView(privateText);
        setContentView(content);
    }

    private TextView label(String text) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextSize(18);
        value.setPadding(0, dp(18), 0, dp(4));
        return value;
    }

    private EditText field(String tag) {
        EditText value = new EditText(this);
        // Stable IDs let Android restore focus and editor state after rotation.
        value.setId("normal_text".equals(tag) ? android.R.id.edit
                : "number_password".equals(tag) ? android.R.id.text1 : android.R.id.text2);
        value.setTag(tag);
        value.setHint(tag);
        value.setSingleLine(true);
        value.setMinHeight(dp(56));
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
