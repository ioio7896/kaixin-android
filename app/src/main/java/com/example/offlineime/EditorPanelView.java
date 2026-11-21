package com.example.offlineime;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.BiConsumer;

/** Cursor navigation and text editing control panel. */
final class EditorPanelView extends LinearLayout {
    private final KeyboardTheme theme;
    private final KeyboardView.Callback callback;
    private final BiConsumer<View, RoundedCardPopup> menuPresenter;
    private final long styleRevision;
    private KeyButton selectButton;
    private KeyButton copyButton;
    private KeyButton cutButton;
    private Drawable selectBackground;

    public EditorPanelView(Context context) {
        this(context, KeyboardTheme.from(context, KeyboardTheme.resolveDark(context)),
                KeyboardView.requireService(context), (anchor, menu) -> menu.show(anchor), -1L);
    }

    EditorPanelView(Context context, KeyboardTheme theme, KeyboardView.Callback callback,
                    BiConsumer<View, RoundedCardPopup> menuPresenter, long styleRevision) {
        super(context);
        this.theme = theme;
        this.callback = callback;
        this.menuPresenter = menuPresenter;
        this.styleRevision = styleRevision;
        setOrientation(HORIZONTAL);

        LinearLayout directions = new LinearLayout(context);
        directions.setOrientation(VERTICAL);
        directions.setContentDescription("方向键控制区");
        addEditorRow(directions, new String[]{"", "↑", ""});
        addEditorRow(directions, new String[]{"←", "光标", "→"});
        addEditorRow(directions, new String[]{"", "↓", ""});
        int directionGap = 1;
        DirectionPad directionPad = new DirectionPad(context, directions,
                dp(3 * (54 + 2 * directionGap)));
        addView(directionPad, new LayoutParams(0, -1, 0.42f));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(VERTICAL);
        actions.setContentDescription("文本编辑操作区");
        addEditorRow(actions, new String[]{"选择", "全选", "粘贴"});
        addEditorRow(actions, new String[]{"复制", "剪切", "退格"});
        addEditorRow(actions, new String[]{"撤销", "重做", "更多"});
        LayoutParams actionsParams = new LayoutParams(0, -1, 0.58f);
        actionsParams.leftMargin = dp(UiTokens.SPACE_XS);
        addView(actions, actionsParams);
    }

    long styleRevision() {
        return styleRevision;
    }

    void updateActionStates(boolean selecting, boolean hasSelection) {
        if (selectButton == null) return;
        if (selectButton.isSelected() != selecting) {
            AccessibilitySupport.selected(selectButton, selecting);
            if (selecting) selectButton.setEmphasized(theme);
            else {
                selectButton.setBackground(selectBackground);
                selectButton.setTextColor(theme.text);
            }
        }
        selectButton.setContentDescription(selecting ? "选择模式已开启，点击退出" : "开启选择模式");
        if (copyButton != null) {
            copyButton.setEnabled(hasSelection);
            copyButton.setAlpha(hasSelection ? 1f : 0.45f);
        }
        if (cutButton != null) {
            cutButton.setEnabled(hasSelection);
            cutButton.setAlpha(hasSelection ? 1f : 0.45f);
        }
    }

    private void addEditorRow(LinearLayout parent, String[] labels) {
        LinearLayout row = new KeyTouchRow(getContext());
        row.setGravity(Gravity.CENTER);
        for (String label : labels) {
            if (label.isEmpty()) {
                row.addView(new View(getContext()), new LayoutParams(0, -1, 1f));
            } else if ("光标".equals(label)) {
                TextView title = new TextView(getContext());
                title.setText(label);
                title.setTextSize(11);
                title.setTextColor(CandidateHeaderView.blendColor(theme.text, theme.background, 0.4f));
                title.setGravity(Gravity.CENTER);
                title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(title, new LayoutParams(0, -1, 1f));
            } else {
                KeyButton button = new KeyButton(getContext(), label, false, theme);
                boolean direction = "↑".equals(label) || "↓".equals(label)
                        || "←".equals(label) || "→".equals(label);
                if (direction) button.setText("");
                button.setActionIcon(editorIcon(label), direction ? editorDirectionDescription(label) : label);
                String action = "退格".equals(label) ? "删除" : label;
                if ("更多".equals(label)) button.setOnClickListener(v -> showMoreMenu(button));
                else button.setOnClickListener(v -> callback.onKey(action));
                if ("←".equals(label) || "→".equals(label)) button.setRepeatAction();
                if ("选择".equals(label)) {
                    selectButton = button;
                    selectBackground = button.getBackground();
                } else if ("复制".equals(label)) {
                    copyButton = button;
                } else if ("剪切".equals(label)) {
                    cutButton = button;
                }
                LayoutParams params = new LayoutParams(0, -1, 1f);
                params.setMargins(dp(1), dp(1), dp(1), dp(1));
                row.addView(button, params);
            }
        }
        parent.addView(row, new LayoutParams(-1, 0, 1f));
    }

    private void showMoreMenu(View anchor) {
        RoundedCardPopup menu = RoundedCardPopup.keyboard(getContext(), "更多文本操作", theme);
        menu.item("行首", "移动到开头", null, false, false, () -> callback.onKey("行首"));
        menu.item("行尾", "移动到结尾", null, false, false, () -> callback.onKey("行尾"));
        menu.item("删词", "删除光标前的一个词", null, false, false, () -> callback.onKey("⌫词"));
        menu.item("剪贴板", null, null, false, false, () -> callback.onKey("剪贴板"));
        Context context = getContext();
        if (context instanceof OfflineImeService && ((OfflineImeService) context).hasWordSaveActions()) {
            menu.item("词语操作", null, null, false, false, () -> callback.onKey("造词菜单"));
        }
        menu.divider().item("返回键盘", null, null, false, false, () -> callback.onKey("返回"));
        menuPresenter.accept(anchor, menu);
    }

    private static int editorIcon(String label) {
        switch (label) {
            case "↑": return R.drawable.ic_edit_up;
            case "↓": return R.drawable.ic_edit_down;
            case "←": return R.drawable.ic_edit_left;
            case "→": return R.drawable.ic_edit_right;
            case "选择": return R.drawable.ic_edit_select;
            case "全选": return R.drawable.ic_edit_select_all;
            case "粘贴": return R.drawable.ic_edit_paste;
            case "复制": return R.drawable.ic_edit_copy;
            case "剪切": return R.drawable.ic_edit_cut;
            case "退格": return R.drawable.ic_edit_backspace;
            case "撤销": return R.drawable.ic_edit_undo;
            case "重做": return R.drawable.ic_edit_redo;
            default: return R.drawable.ic_edit_more;
        }
    }

    private static String editorDirectionDescription(String label) {
        switch (label) {
            case "↑": return "光标上移";
            case "↓": return "光标下移";
            case "←": return "光标左移，长按连续移动";
            default: return "光标右移，长按连续移动";
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Keep the cross square and centered when height, rotation or insets change. */
    private static final class DirectionPad extends FrameLayout {
        private final View cross;
        private final int maximumSide;

        DirectionPad(Context context, View cross, int maximumSide) {
            super(context);
            this.cross = cross;
            this.maximumSide = maximumSide;
            addView(cross, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? maximumSide : MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? maximumSide : MeasureSpec.getSize(heightMeasureSpec);
            int side = Math.max(0, Math.min(maximumSide, Math.min(
                    width - getPaddingLeft() - getPaddingRight(),
                    height - getPaddingTop() - getPaddingBottom())));
            // Update the existing params during measurement without scheduling another layout.
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cross.getLayoutParams();
            params.width = side;
            params.height = side;
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
