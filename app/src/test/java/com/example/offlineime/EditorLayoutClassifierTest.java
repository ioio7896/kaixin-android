package com.example.offlineime;

import static org.junit.Assert.assertEquals;

import android.text.InputType;
import org.junit.Test;

public class EditorLayoutClassifierTest {
    @Test public void recognizesNumericAndDateVariations() {
        assertEquals("phone", OfflineImeService.classifyEditorLayout(InputType.TYPE_CLASS_PHONE));
        assertEquals("number", OfflineImeService.classifyEditorLayout(InputType.TYPE_CLASS_NUMBER));
        assertEquals("decimal", OfflineImeService.classifyEditorLayout(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        assertEquals("signed", OfflineImeService.classifyEditorLayout(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
        assertEquals("date", OfflineImeService.classifyEditorLayout(
                InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE));
        assertEquals("time", OfflineImeService.classifyEditorLayout(
                InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME));
    }

    @Test public void recognizesEmailUriAndPlainText() {
        assertEquals("email", OfflineImeService.classifyEditorLayout(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
        assertEquals("email", OfflineImeService.classifyEditorLayout(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS));
        assertEquals("uri", OfflineImeService.classifyEditorLayout(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
        assertEquals("text", OfflineImeService.classifyEditorLayout(InputType.TYPE_CLASS_TEXT));
    }
}
