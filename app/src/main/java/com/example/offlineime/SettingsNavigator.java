package com.example.offlineime;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** Owns the visible settings page and restores its scroll position after recreation. */
final class SettingsNavigator {
    private static final String SECTION = "settings.section";
    private static final String HOME_SCROLL = "settings.home_scroll";
    private static final String DETAIL_SCROLL = "settings.detail_scroll";

    private final SettingsActivity activity;
    private final ScrollView scroll;
    private final FrameLayout pageHost;
    private final LinearLayout home;
    private final TextView title;
    private final View offlineBadge;
    private final boolean twoPane;
    private LinearLayout details;
    private SettingsSections section;
    private int homeScroll;
    private Runnable unregisterBack;
    private ViewTreeObserver.OnGlobalLayoutListener restoreScroll;
    private int pendingScroll;

    SettingsNavigator(SettingsActivity activity, ScrollView scroll, FrameLayout pageHost,
                      LinearLayout home, TextView title, View offlineBadge, boolean twoPane) {
        this.activity = activity;
        this.scroll = scroll;
        this.pageHost = pageHost;
        this.home = home;
        this.title = title;
        this.offlineBadge = offlineBadge;
        this.twoPane = twoPane;
    }

    void open(SettingsSections next) {
        if (twoPane) {
            if (section == next) return;
            if (section != null) {
                activity.closeSection();
                pageHost.removeView(details);
                details = null;
            }
            show(next, 0);
            return;
        }
        if (section != null) return;
        homeScroll = visibleScroll();
        show(next, 0);
    }

    private void show(SettingsSections next, int y) {
        section = next;
        details = activity.buildSection(next);
        if (!twoPane) home.setVisibility(View.GONE);
        pageHost.addView(details, new FrameLayout.LayoutParams(-1, -2));
        title.setText(next.title);
        if (!twoPane) offlineBadge.setVisibility(View.GONE);
        scrollToAfterLayout(y);
        if (!twoPane && Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            OnBackInvokedCallback callback = this::back;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            unregisterBack = () -> dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }

    boolean back() {
        if (activity.dismissChoicePopup()) return true;
        if (twoPane || section == null) return false;
        activity.closeSection();
        removeBackCallback();
        pageHost.removeView(details);
        details = null;
        section = null;
        home.setVisibility(View.VISIBLE);
        title.setText(R.string.settings_title);
        offlineBadge.setVisibility(View.VISIBLE);
        scrollToAfterLayout(homeScroll);
        return true;
    }

    void restore(Bundle state) {
        if (state == null) {
            if (twoPane) show(SettingsSections.APPEARANCE, 0);
            return;
        }
        homeScroll = state.getInt(HOME_SCROLL, state.getInt("main_scroll_y", 0));
        SettingsSections saved = SettingsSections.restore(activity, state.getString(SECTION),
                state.getString("open_section"));
        if (saved == null) {
            if (twoPane) show(SettingsSections.APPEARANCE, 0);
            else scrollToAfterLayout(homeScroll);
        } else {
            show(saved, state.getInt(DETAIL_SCROLL, state.getInt("detail_scroll_y", 0)));
        }
    }

    void save(Bundle state) {
        state.putInt(HOME_SCROLL, section == null ? visibleScroll() : homeScroll);
        if (section != null) {
            state.putString(SECTION, section.name());
            state.putInt(DETAIL_SCROLL, visibleScroll());
        }
    }

    private void scrollToAfterLayout(int y) {
        cancelScrollRestore();
        pendingScroll = y;
        restoreScroll = () -> {
            cancelScrollRestore();
            scroll.scrollTo(0, y);
        };
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(restoreScroll);
        scroll.requestLayout();
    }

    private void cancelScrollRestore() {
        if (restoreScroll == null) return;
        ViewTreeObserver observer = scroll.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(restoreScroll);
        restoreScroll = null;
    }

    private int visibleScroll() {
        return restoreScroll == null ? scroll.getScrollY() : pendingScroll;
    }

    private void removeBackCallback() {
        if (unregisterBack != null) {
            unregisterBack.run();
            unregisterBack = null;
        }
    }

    void close() {
        removeBackCallback();
        cancelScrollRestore();
    }
}
