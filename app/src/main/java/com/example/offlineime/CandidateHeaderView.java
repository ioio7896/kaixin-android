package com.example.offlineime;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Fixed-height keyboard header shared by the horizontal candidate strip, icon toolbar, and expanded title. */
final class CandidateHeaderView extends FrameLayout {
    interface Host {
        KeyboardView.Callback callback();
        KeyboardTheme theme();
        SharedPreferences settings();
        CandidateTextStyle candidateStyle();
        int candidateHeightDp();
        int candidateGapDp();
        int candidatePageSize();
        boolean isCandidateListShown();
        void requestPendingRefresh();
    }

    private static final class CandidateSlot {
        final TextView view;
        PinyinDictionary.Entry entry, pressedEntry;
        int candidateIndex = -1;
        boolean selectable;
        String word = "";
        String description = "";
        String styledWord = "";
        int styledForeground;
        int styledText;
        int styledKey;
        int styledAccent;
        int styledFunction;
        int styledRadius;
        int styledFont;
        int styledHeight;
        int styledNumber;
        long styledRevision = -1;
        int gap = -1;
        boolean styledFirst;
        boolean hasStyle;

        CandidateSlot(TextView view) {
            this.view = view;
        }
    }

    private final Host host;
    private final LinearLayout toolbarRow;
    private final LinearLayout candidateBar;
    private final HorizontalScrollView candidateScroll;
    private final LinearLayout candidates;
    private final TextView expandCandidates;
    private final TextView composingReading;
    private View expandedHeaderTitle;

    private final ArrayList<Integer> visibleCandidateIndexes = new ArrayList<>(15);
    private final ArrayList<CandidateSlot> candidatePool = new ArrayList<>();
    private int nextCandidateStripIndex;
    private boolean bindingCandidateStrip;
    private boolean candidateStripAppendPosted;
    private int candidateTouches;
    private boolean candidateRefreshPending;

    private String renderedCandidateInput = "";
    private List<PinyinDictionary.Entry> renderedCandidateWords = Collections.emptyList();
    private OfflineImeService.Mode renderedCandidateMode;
    private boolean renderedCandidateReady;
    private long renderedCandidateStyleRevision = -1;
    private int renderedCandidatePage = -1;
    private String renderedCandidateSurfaceKey = "";
    private long activeStyleRevision;
    private int candidatePageStart;

    public CandidateHeaderView(Context context) {
        this(context, null);
    }

    CandidateHeaderView(Context context, Host host) {
        super(context);
        this.host = host;
        toolbarRow = new LinearLayout(context);
        toolbarRow.setOrientation(LinearLayout.HORIZONTAL);
        toolbarRow.setGravity(Gravity.CENTER_VERTICAL);
        toolbarRow.setPadding(dp(8), 0, dp(8), 0);

        candidateBar = new LinearLayout(context);
        candidateBar.setOrientation(LinearLayout.HORIZONTAL);
        candidateBar.setGravity(Gravity.CENTER_VERTICAL);
        candidateBar.setBaselineAligned(false);

        composingReading = new TextView(context);
        composingReading.setGravity(Gravity.CENTER);
        composingReading.setTextSize(UiTokens.CAPTION_SP);
        composingReading.setSingleLine(true);
        composingReading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        composingReading.setPadding(dp(8), 0, dp(8), 0);
        composingReading.setMaxWidth(dp(92));
        composingReading.setVisibility(GONE);
        candidateBar.addView(composingReading, new LinearLayout.LayoutParams(-2, -1));

        candidateScroll = new HorizontalScrollView(context) {
            private boolean tracking;

            @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
                super.onScrollChanged(x, y, oldX, oldY);
                scheduleCandidateStripAppend();
            }

            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    tracking = true;
                    candidateTouches++;
                }
                try {
                    return super.dispatchTouchEvent(event);
                } finally {
                    if (tracking && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                        tracking = false;
                        candidateTouches = Math.max(0, candidateTouches - 1);
                        if (candidateRefreshPending) {
                            candidateRefreshPending = false;
                            if (CandidateHeaderView.this.host != null) {
                                post(CandidateHeaderView.this.host::requestPendingRefresh);
                            }
                        }
                    }
                }
            }
        };
        candidateScroll.setHorizontalScrollBarEnabled(false);
        candidateScroll.setFillViewport(false);
        candidateScroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> scheduleCandidateStripAppend());

        candidates = new LinearLayout(context);
        candidates.setOrientation(LinearLayout.HORIZONTAL);
        candidates.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> scheduleCandidateStripAppend());
        candidateScroll.addView(candidates, new HorizontalScrollView.LayoutParams(-2, -1));
        candidateBar.addView(candidateScroll, new LinearLayout.LayoutParams(0, -1, 1f));

        expandCandidates = new TextView(context);
        expandCandidates.setText("⌄");
        expandCandidates.setTextSize(24);
        expandCandidates.setGravity(Gravity.CENTER);
        expandCandidates.setMinWidth(dp(UiTokens.MIN_TOUCH));
        expandCandidates.setContentDescription("展开候选词");
        GradientDrawable expandBackground = new GradientDrawable();
        expandBackground.setColor(Color.TRANSPARENT);
        expandBackground.setCornerRadius(dp(UiTokens.RADIUS_CONTROL));
        expandCandidates.setBackground(expandBackground);
        expandCandidates.setOnClickListener(view -> {
            if (this.host == null) return;
            KeyboardView.Callback cb = this.host.callback();
            if (renderedCandidateInput != null && renderedCandidateInput.isEmpty()
                    && renderedCandidateWords != null && !renderedCandidateWords.isEmpty()
                    && (renderedCandidateMode == OfflineImeService.Mode.PINYIN
                    || renderedCandidateMode == OfflineImeService.Mode.NINE)) {
                cb.onDismissNextWordPrediction();
            } else {
                cb.onCandidateExpand();
            }
        });
        candidateBar.addView(expandCandidates, new LinearLayout.LayoutParams(dp(48), -1));

        int initialHeight = dp(host == null ? 48 : host.candidateHeightDp());
        addView(candidateBar, new LayoutParams(-1, initialHeight, Gravity.TOP));
        addView(toolbarRow, new LayoutParams(-1, initialHeight, Gravity.TOP));
    }

    LinearLayout toolbarRow() {
        return toolbarRow;
    }

    boolean isCandidateTouched() {
        return candidateTouches > 0;
    }

    void markCandidateRefreshPending() {
        candidateRefreshPending = true;
    }

    List<PinyinDictionary.Entry> renderedCandidateWords() {
        return renderedCandidateWords;
    }

    void setToolbarVisibility(int visibility) {
        toolbarRow.setVisibility(visibility);
    }

    void hideCandidateBar() {
        candidateBar.setVisibility(GONE);
    }

    void applyHeight(int heightPx) {
        LayoutParams candidateParams = (LayoutParams) candidateBar.getLayoutParams();
        if (candidateParams != null && candidateParams.height != heightPx) {
            candidateParams.height = heightPx;
            candidateBar.setLayoutParams(candidateParams);
        }
        ViewGroup.LayoutParams toolbarParams = toolbarRow.getLayoutParams();
        if (toolbarParams != null && toolbarParams.height != heightPx) {
            toolbarParams.height = heightPx;
            toolbarRow.setLayoutParams(toolbarParams);
        }
        ViewGroup.LayoutParams headerParams = getLayoutParams();
        if (headerParams != null && headerParams.height != heightPx) {
            headerParams.height = heightPx;
            setLayoutParams(headerParams);
        }
        if (expandedHeaderTitle != null) {
            LayoutParams titleParams = (LayoutParams) expandedHeaderTitle.getLayoutParams();
            if (titleParams != null && titleParams.height != heightPx) {
                titleParams.height = heightPx;
                expandedHeaderTitle.setLayoutParams(titleParams);
            }
        }
    }

    void showExpandedTitle(OfflineImeService.Mode mode, String input, String reading, int count,
                           KeyboardTheme theme, int heightPx) {
        clearExpandedTitle();
        LinearLayout titleRow = new KeyTouchRow(getContext());
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(getContext());
        String title = mode == OfflineImeService.Mode.ENGLISH ? "English suggestions"
                : mode == OfflineImeService.Mode.STROKE ? "笔画候选"
                : input.isEmpty() && count > 0 ? "下一词建议" : "中文候选词";
        heading.setText(reading.isEmpty() ? title
                : getContext().getString(R.string.candidate_title_with_reading, title, reading));
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setTextSize(13);
        heading.setTextColor(theme.text);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(12), 0, dp(12), 0);
        heading.setContentDescription(heading.getText());
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView countLabel = new TextView(getContext());
        countLabel.setText(getContext().getString(R.string.candidate_count, count));
        countLabel.setTextSize(12);
        countLabel.setTextColor(theme.text);
        countLabel.setGravity(Gravity.CENTER);
        countLabel.setContentDescription("共" + count + "个候选词");
        titleRow.addView(countLabel, new LinearLayout.LayoutParams(dp(64), -1));

        addView(titleRow, new LayoutParams(-1, heightPx, Gravity.TOP));
        expandedHeaderTitle = titleRow;
    }

    void clearExpandedTitle() {
        if (expandedHeaderTitle != null) {
            removeView(expandedHeaderTitle);
            expandedHeaderTitle = null;
        }
    }

    void resetForRowsRemoved() {
        candidateTouches = 0;
        candidateRefreshPending = false;
        clearExpandedTitle();
        renderedCandidateWords = null;
        renderedCandidateMode = null;
        renderedCandidateSurfaceKey = "";
    }

    void onDetachedCleanup() {
        candidateTouches = 0;
        candidateRefreshPending = false;
    }

    void bindCandidates(OfflineImeService.Mode mode, String input, String reading, List<PinyinDictionary.Entry> words,
                        int candidatePage, boolean candidateSurfaceReady,
                        boolean candidateFirstFramePending, long styleRevision, String surfaceKey) {
        if (host == null) return;
        KeyboardTheme theme = host.theme();
        int fg = theme.text;
        if (!android.text.TextUtils.equals(composingReading.getText(), reading)) composingReading.setText(reading);
        if (composingReading.getCurrentTextColor() != fg) composingReading.setTextColor(fg);
        int readingVisibility = reading.isEmpty() ? GONE : VISIBLE;
        if (composingReading.getVisibility() != readingVisibility) composingReading.setVisibility(readingVisibility);
        boolean candidateInputChanged = !input.equals(renderedCandidateInput);
        // Retain the disabled previous strip through every repaint while the current
        // input awaits its first selectable frame.
        List<PinyinDictionary.Entry> displayWords = !candidateSurfaceReady && words.isEmpty()
                && candidateFirstFramePending && !input.isEmpty()
                && renderedCandidateInput != null && !renderedCandidateInput.isEmpty()
                && mode == renderedCandidateMode && renderedCandidateWords != null
                && !renderedCandidateWords.isEmpty()
                ? renderedCandidateWords : words;
        boolean candidateEntriesChanged = displayWords != renderedCandidateWords;
        boolean candidateSurfaceChanged = !sameCandidateWords(displayWords, renderedCandidateWords)
                || mode != renderedCandidateMode
                || candidateInputChanged
                || candidateSurfaceReady != renderedCandidateReady
                || styleRevision != renderedCandidateStyleRevision
                || candidatePage != renderedCandidatePage
                || !surfaceKey.equals(renderedCandidateSurfaceKey);
        boolean settledCandidateChange = candidateSurfaceReady && !displayWords.isEmpty()
                && (renderedCandidateWords == null || renderedCandidateWords.isEmpty()
                || !displayWords.get(0).word.equals(renderedCandidateWords.get(0).word))
                && candidatePage == renderedCandidatePage
                && KeyFeedback.get(getContext()).quietFor(120)
                && android.animation.ValueAnimator.areAnimatorsEnabled();
        renderedCandidateInput = input;
        boolean typing = !input.isEmpty() || !words.isEmpty();

        if (candidateSurfaceChanged) {
            bindingCandidateStrip = true;
            int previousCount = visibleCandidateIndexes.size();
            visibleCandidateIndexes.clear();
            int pageSize = host.candidatePageSize();
            int page = Math.min(candidatePage, CandidatePaging.pageCount(displayWords.size(), pageSize) - 1);
            nextCandidateStripIndex = page * pageSize;
            candidatePageStart = nextCandidateStripIndex;
            activeStyleRevision = styleRevision;
            int visibleEstimate = Math.max(8, Math.min(15,
                    KeyboardPlacement.width(getContext(), host.settings()) / 42));
            int initialCount = candidateInputChanged || candidatePage != renderedCandidatePage
                    ? visibleEstimate : Math.max(visibleEstimate, previousCount);
            appendCandidateStripBatch(displayWords, mode, candidateSurfaceReady, initialCount);
            for (int i = visibleCandidateIndexes.size(); i < candidatePool.size(); i++) {
                CandidateSlot slot = candidatePool.get(i);
                slot.entry = slot.pressedEntry = null;
                slot.candidateIndex = -1;
                slot.selectable = false;
                if (slot.view.getVisibility() != GONE) slot.view.setVisibility(GONE);
            }
            if (expandCandidates.getCurrentTextColor() != fg) expandCandidates.setTextColor(fg);
            // The expanded page is useful even when a reading constraint leaves
            // fewer candidates than the horizontal strip can hold.
            expandCandidates.setVisibility(typing ? VISIBLE : GONE);
            expandCandidates.setEnabled(candidateSurfaceReady);
            boolean showingNextWordPrediction = input.isEmpty() && !displayWords.isEmpty()
                    && (mode == OfflineImeService.Mode.PINYIN || mode == OfflineImeService.Mode.NINE);
            expandCandidates.setText(showingNextWordPrediction ? "×" : "⌄");
            expandCandidates.setContentDescription(showingNextWordPrediction ? "关闭下一词联想" : "展开候选词");
            candidateBar.setVisibility(typing ? VISIBLE : GONE);
            if ((candidateInputChanged || candidatePage != renderedCandidatePage)
                    && candidateScroll.getScrollX() != 0) {
                candidateScroll.scrollTo(0, 0);
            }
            candidateScroll.setVisibility(visibleCandidateIndexes.isEmpty() ? INVISIBLE : VISIBLE);
            candidateScroll.animate().cancel();
            candidateScroll.setAlpha(settledCandidateChange ? 0.88f : 1f);
            if (settledCandidateChange) {
                candidateScroll.animate().alpha(1f).setDuration(70).start();
            }
            renderedCandidateWords = displayWords;
            renderedCandidateMode = mode;
            renderedCandidateReady = candidateSurfaceReady;
            renderedCandidateStyleRevision = styleRevision;
            renderedCandidatePage = candidatePage;
            renderedCandidateSurfaceKey = surfaceKey;
            bindingCandidateStrip = false;
            scheduleCandidateStripAppend();
        } else if (candidateEntriesChanged) {
            // Identical labels need no view/layout work, but selection must use
            // the newest entries (including their input version and reading).
            for (int i = 0; i < visibleCandidateIndexes.size(); i++) {
                int index = visibleCandidateIndexes.get(i);
                candidatePool.get(i).entry = displayWords.get(index);
            }
            renderedCandidateWords = displayWords;
            scheduleCandidateStripAppend();
        }
    }

    private CandidateSlot createCandidateSlot() {
        TouchTrackingTextView item = new TouchTrackingTextView(getContext());
        CandidateSlot slot = new CandidateSlot(item);
        item.setOnClickListener(view -> {
            if (host != null && slot.selectable && slot.candidateIndex >= 0) {
                host.callback().onCandidateEntry(slot.pressedEntry == null ? slot.entry : slot.pressedEntry);
            }
            slot.pressedEntry = null;
        });
        item.setTouchObserver(event -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) slot.pressedEntry = slot.entry;
            else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) slot.pressedEntry = null;
        });
        // Candidate touches only select words; holding must never open an editing menu.
        item.setLongClickable(false);
        return slot;
    }

    private void appendCandidateStripBatch(List<PinyinDictionary.Entry> words,
                                           OfflineImeService.Mode mode, boolean ready, int count) {
        if (host == null) return;
        KeyboardTheme theme = host.theme();
        boolean nextWordPrediction = renderedCandidateInput.isEmpty() && !words.isEmpty()
                && (mode == OfflineImeService.Mode.PINYIN || mode == OfflineImeService.Mode.NINE);
        int gap = host.candidateGapDp();
        boolean numbers = host.candidateStyle().barNumbers;
        int end = Math.min(words.size(), nextCandidateStripIndex + count);
        while (nextCandidateStripIndex < end) {
            int index = nextCandidateStripIndex++;
            int slotIndex = visibleCandidateIndexes.size();
            visibleCandidateIndexes.add(index);
            CandidateSlot slot;
            if (slotIndex < candidatePool.size()) {
                slot = candidatePool.get(slotIndex);
            } else {
                slot = createCandidateSlot();
                candidatePool.add(slot);
            }
            PinyinDictionary.Entry entry = words.get(index);
            String word = entry.word;
            boolean first = index == 0;
            String candidateType = mode == OfflineImeService.Mode.ENGLISH ? "英文联想词 "
                    : nextWordPrediction ? "下一词建议 " : "候选词 ";
            slot.entry = entry;
            slot.selectable = ready;
            bindCandidateSlot(slot, index, word,
                    entry.emojiSuggestion ? "表情 " + EmojiCatalog.get(getContext()).description(word)
                            : (first ? "首选" : "") + candidateType + word, first, theme, gap,
                    numbers ? index - candidatePageStart + 1 : 0);
            slot.view.setEnabled(ready);
        }
    }

    private void scheduleCandidateStripAppend() {
        if (host == null || bindingCandidateStrip || candidateStripAppendPosted || host.isCandidateListShown()
                || renderedCandidateWords == null
                || nextCandidateStripIndex >= renderedCandidateWords.size()) return;
        candidateStripAppendPosted = true;
        candidateScroll.post(() -> {
            candidateStripAppendPosted = false;
            if (bindingCandidateStrip || host.isCandidateListShown() || renderedCandidateWords == null
                    || candidateScroll.getVisibility() != VISIBLE
                    || candidateScroll.getWidth() == 0
                    || nextCandidateStripIndex >= renderedCandidateWords.size()) return;
            View content = candidateScroll.getChildAt(0);
            if (content == null || content.getWidth() - candidateScroll.getScrollX()
                    - candidateScroll.getWidth() > dp(96)) return;
            appendCandidateStripBatch(renderedCandidateWords, renderedCandidateMode,
                    renderedCandidateReady, 8);
        });
    }

    private void bindCandidateSlot(CandidateSlot slot, int candidateIndex, String word,
                                   String description, boolean first, KeyboardTheme theme, int gap,
                                   int number) {
        slot.candidateIndex = candidateIndex;
        // Keep the existing node and let accessibility services respond to content
        // changes; never clear and asynchronously steal their current focus.
        slot.word = word;
        if (number > 0) description = number + "，" + description;
        if (!description.equals(slot.description)) {
            slot.view.setContentDescription(description);
            slot.description = description;
        }
        CandidateTextStyle style = host.candidateStyle();
        int fontSp = style.barFontSp;
        int heightDp = host.candidateHeightDp();
        int foreground = theme.text;
        if (!slot.hasStyle || !word.equals(slot.styledWord) || first != slot.styledFirst
                || foreground != slot.styledForeground || theme.text != slot.styledText
                || theme.key != slot.styledKey || theme.accent != slot.styledAccent
                || theme.function != slot.styledFunction
                || Math.round(theme.radius) != slot.styledRadius
                || fontSp != slot.styledFont || heightDp != slot.styledHeight
                || number != slot.styledNumber || activeStyleRevision != slot.styledRevision) {
            applyCandidateStyle(slot.view, word, first, theme, style, number, false);
            slot.styledWord = word;
            slot.styledFirst = first;
            slot.styledForeground = foreground;
            slot.styledText = theme.text;
            slot.styledKey = theme.key;
            slot.styledAccent = theme.accent;
            slot.styledFunction = theme.function;
            slot.styledRadius = Math.round(theme.radius);
            slot.styledFont = fontSp;
            slot.styledHeight = heightDp;
            slot.styledNumber = number;
            slot.styledRevision = activeStyleRevision;
            slot.hasStyle = true;
        }
        if (slot.view.getVisibility() != VISIBLE) slot.view.setVisibility(VISIBLE);
        if (slot.gap != gap) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -1);
            params.setMargins(0, 0, 0, 0);
            slot.view.setLayoutParams(params);
            slot.gap = gap;
        }
        if (slot.view.getParent() == null) candidates.addView(slot.view);
    }

    /** Shared cell styling for both live candidate surfaces and their settings previews. */
    static void applyCandidateStyle(TextView item, String word, boolean first, KeyboardTheme theme,
                                    CandidateTextStyle style, int number, boolean expanded) {
        float density = item.getResources().getDisplayMetrics().density;
        int padding = Math.round((expanded ? style.pagePaddingDp : style.barPaddingDp) * density);
        String marker = number > 0 ? number + " " : "";
        SpannableString label = new SpannableString(marker + word);
        if (!marker.isEmpty()) {
            label.setSpan(new RelativeSizeSpan(CandidateTextStyle.NUMBER_SCALE), 0, marker.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        item.setSingleLine(true);
        item.setText(label);
        style.applyTextSize(item, expanded);
        item.setTypeface(style.typeface(first, expanded));
        item.setIncludeFontPadding(false);
        boolean accent = first && style.accentFirst(expanded);
        item.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
                new int[]{theme.candidateForeground(accent, true), theme.candidateForeground(accent, false)}));
        item.setGravity(Gravity.CENTER);
        int vertical = expanded ? Math.round(2 * density) : 0;
        item.setPadding(padding, vertical, padding, vertical);
        item.setEllipsize(expanded ? style.pageEllipsize : android.text.TextUtils.TruncateAt.END);
        item.setMinWidth(expanded ? 0 : Math.round(30 * density));
        item.setMinHeight(expanded ? 0 : Math.round(style.barHeight(item.getContext()) * density));
        int divider = blendColor(theme.background, theme.text, theme.isDark ? 0.25f : 0.13f);
        int pressed = blendColor(theme.background, theme.accent, theme.isDark ? 0.22f : 0.09f);
        item.setBackground(new CandidateCellDrawable(divider, pressed, expanded, density));
    }

    private static boolean sameCandidateWords(List<PinyinDictionary.Entry> left,
                                              List<PinyinDictionary.Entry> right) {
        if (left == right) return true;
        if (left == null || right == null || left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!Objects.equals(left.get(i).word, right.get(i).word)) return false;
        }
        return true;
    }

    static int blendColor(int base, int overlay, float amount) {
        int alpha = Math.round(255 * amount);
        int red = ((base >> 16) & 0xff) * (255 - alpha) / 255
                + ((overlay >> 16) & 0xff) * alpha / 255;
        int green = ((base >> 8) & 0xff) * (255 - alpha) / 255
                + ((overlay >> 8) & 0xff) * alpha / 255;
        int blue = (base & 0xff) * (255 - alpha) / 255
                + (overlay & 0xff) * alpha / 255;
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
