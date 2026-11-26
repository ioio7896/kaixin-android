package com.example.offlineime;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shows bundled documents and stores acceptance of the current terms on this device. */
final class SettingsLegalDocuments {
    private SettingsLegalDocuments() {}

    static void addLinks(SettingsActivity host, LinearLayout parent) {
        addLink(host, parent, R.string.legal_terms_title, () -> showTerms(host));
        addLink(host, parent, R.string.legal_privacy_title, () -> showPrivacy(host));
    }

    private static void addLink(SettingsActivity host, LinearLayout parent, int title, Runnable open) {
        Button button = new Button(host);
        button.setAllCaps(false);
        button.setMinimumHeight(host.dp(UiTokens.MIN_TOUCH));
        button.setText(title);
        button.setOnClickListener(v -> open.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void showTerms(SettingsActivity host) {
        SharedPreferences preferences = host.getSharedPreferences("legal_documents", Context.MODE_PRIVATE);
        String version = host.getString(R.string.legal_terms_version);
        boolean accepted = version.equals(preferences.getString("accepted_terms_version", ""));
        AlertDialog.Builder builder = new AlertDialog.Builder(host)
                .setTitle(R.string.legal_terms_title)
                .setMessage(TextUtils.concat(host.getString(R.string.legal_version_label, version),
                        accepted ? "\n" + host.getString(R.string.legal_terms_accepted) : "",
                        "\n\n", host.getText(R.string.legal_terms_body)));
        if (accepted) {
            builder.setPositiveButton(R.string.legal_close, null);
        } else {
            builder.setNegativeButton(R.string.legal_close, null)
                    .setPositiveButton(R.string.legal_terms_accept, (dialog, which) ->
                            preferences.edit().putString("accepted_terms_version", version)
                                    .putLong("accepted_terms_time", System.currentTimeMillis()).apply());
        }
        showDocument(builder);
    }

    private static void showPrivacy(SettingsActivity host) {
        showDocument(new AlertDialog.Builder(host)
                .setTitle(R.string.legal_privacy_title)
                .setMessage(TextUtils.concat(host.getString(R.string.legal_version_label,
                        host.getString(R.string.legal_privacy_version)), "\n\n",
                        host.getText(R.string.legal_privacy_body)))
                .setPositiveButton(R.string.legal_close, null));
    }

    private static void showDocument(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        RoundedCardPopup.showDialog(dialog);
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextIsSelectable(true);
    }
}
