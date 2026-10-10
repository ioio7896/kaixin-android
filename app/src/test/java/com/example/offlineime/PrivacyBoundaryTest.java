package com.example.offlineime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import org.junit.Test;

public class PrivacyBoundaryTest {
    @Test public void detectsAllPasswordVariations() {
        assertTrue(OfflineImeService.isSensitiveInput(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 0));
        assertTrue(OfflineImeService.isSensitiveInput(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0));
        assertTrue(OfflineImeService.isSensitiveInput(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0));
        assertTrue(OfflineImeService.isSensitiveInput(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0));
    }

    @Test public void honorsNoPersonalizedLearningFlag() {
        assertTrue(OfflineImeService.isSensitiveInput(InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING));
        assertFalse(OfflineImeService.isSensitiveInput(InputType.TYPE_CLASS_TEXT, 0));
    }

    @Test public void clipboardIdsDoNotUseJavaHashCode() {
        // These strings deliberately collide under String.hashCode().
        assertNotEquals(ClipboardHistoryStore.id("Aa"), ClipboardHistoryStore.id("BB"));
    }
}
