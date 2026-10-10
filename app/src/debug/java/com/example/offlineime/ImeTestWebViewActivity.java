package com.example.offlineime;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Debug-only WebView editor used to exercise the IME's composing-text bridge. */
public final class ImeTestWebViewActivity extends Activity {
    @SuppressLint("SetJavaScriptEnabled") // The debug host loads a fixed local input fixture only.
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WebView editor = new WebView(this);
        editor.setTag("web_editor");
        editor.setBackgroundColor(Color.WHITE);
        editor.setFocusable(true);
        editor.setFocusableInTouchMode(true);
        editor.getSettings().setJavaScriptEnabled(true);
        editor.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("document.getElementById('web_text').focus()", null);
            }
        });
        setContentView(editor);
        editor.loadDataWithBaseURL("https://ime.test/",
                "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<input id='web_text' aria-label='web_text' autocomplete='off' "
                        + "style='font-size:24px;width:95%;height:56px' placeholder='WebView editor'>",
                "text/html", "UTF-8", null);
    }
}
