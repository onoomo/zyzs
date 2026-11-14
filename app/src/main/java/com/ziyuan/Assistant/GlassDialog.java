package com.ziyuan.Assistant;

import com.example.immersive.R;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.TextView;

/**
 * 全局玻璃弹窗。
 *
 * 用 {@link Builder} 替代 android.app.AlertDialog.Builder，弹窗自动变成：
 * 透明窗底 + 圆角玻璃卡片（顶亮底沉渐变 + 1dp 高光描边 + 投影）
 * + 缩放淡入淡出 + 明暗跟随（纯黑 / 浅蓝）+ Android 12 以上真实背景模糊。
 *
 * 例：new GlassDialog.Builder(this).setTitle("播放").setItems(...).show();
 */
public final class GlassDialog {

    private GlassDialog() {}

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 圆角玻璃卡片背景 */
    public static GradientDrawable glass(Context c, boolean dk) {
        int[] cols = dk
                ? new int[]{0xE62C3446, 0xDE171C27}
                : new int[]{0xF2FFFFFF, 0xEFEAF2FF};
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, cols);
        g.setCornerRadius(dp(c, 24));
        g.setStroke(Math.max(1, dp(c, 1)), dk ? 0x40FFFFFF : 0xFFFFFFFF);
        return g;
    }

    /** 窗口级设置：透明窗底、遮罩、动画、背景模糊（必须在 show 之前调用） */
    public static void pre(android.app.Dialog d) {
        if (d == null) return;
        Window w = d.getWindow();
        if (w == null) return;
        try {
            Context c = d.getContext();
            boolean dk = Theme.dark(c);
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = dk ? 0.60f : 0.30f;
            w.setAttributes(lp);
            w.setWindowAnimations(R.style.DialogPopAnim);
            if (Build.VERSION.SDK_INT >= 31) {
                // Android 12+ 的跨窗口真模糊；不支持的机型自动退化为半透明玻璃
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                w.setBackgroundBlurRadius(dp(c, dk ? 44 : 30));
            }
        } catch (Throwable ignored) {}
    }

    /** 面板级设置：玻璃底 + 投影 + 文字跟随明暗（必须在 show 之后调用） */
    public static void post(android.app.Dialog d) {
        if (d == null) return;
        try {
            Context c = d.getContext();
            boolean dk = Theme.dark(c);
            View panel = panel(d);
            if (panel == null) return;
            panel.setBackground(glass(c, dk));
            panel.setClipToOutline(true);
            panel.setElevation(dp(c, 18));
            tint(panel, dk);
        } catch (Throwable ignored) {}
    }

    /** 对 create() 出来的弹窗：先 pre，等真正布局出来再补玻璃 */
    public static android.app.AlertDialog wrap(final android.app.AlertDialog d) {
        if (d == null) return null;
        pre(d);
        try {
            Window w = d.getWindow();
            final View decor = w == null ? null : w.getDecorView();
            if (decor != null) {
                final View.OnLayoutChangeListener l = new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                               int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        if (panel(d) == null) return;
                        decor.removeOnLayoutChangeListener(this);
                        post(d);
                    }
                };
                decor.addOnLayoutChangeListener(l);
            }
        } catch (Throwable ignored) {}
        return d;
    }

    /** 取 AlertDialog 的卡片面板（android.R.id.content 的唯一子 View） */
    static View panel(android.app.Dialog d) {
        Window w = d.getWindow();
        if (w == null) return null;
        View content = w.getDecorView().findViewById(android.R.id.content);
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
            return ((ViewGroup) content).getChildAt(0);
        }
        return content;
    }

    /** 文字/按钮配色跟随明暗 */
    static void tint(View v, boolean dk) {
        if (v == null) return;
        if (v instanceof Button) {
            ((Button) v).setTextColor(dk ? 0xFF7FA8FF : 0xFF1C63E8);
            return;
        }
        if (v instanceof EditText) {
            ((EditText) v).setTextColor(dk ? 0xFFF2F5FA : 0xFF1F2329);
            ((EditText) v).setHintTextColor(dk ? 0xFF6F7889 : 0xFF8A93A6);
            return;
        }
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(dk ? 0xFFF2F5FA : 0xFF1F2329);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tint(g.getChildAt(i), dk);
        }
    }

    /** 替代 android.app.AlertDialog.Builder —— 每个 return this 的重写都保证链式调用不丢玻璃 */
    public static class Builder extends android.app.AlertDialog.Builder {

        public Builder(Context c) { super(c); }

        public Builder(Context c, int themeResId) { super(c, themeResId); }

        @Override public Builder setTitle(CharSequence title) { super.setTitle(title); return this; }

        @Override public Builder setTitle(int titleId) { super.setTitle(titleId); return this; }

        @Override public Builder setMessage(CharSequence message) { super.setMessage(message); return this; }

        @Override public Builder setMessage(int messageId) { super.setMessage(messageId); return this; }

        @Override public Builder setView(View view) { super.setView(view); return this; }

        @Override public Builder setView(int layoutResId) { super.setView(layoutResId); return this; }

        @Override public Builder setPositiveButton(CharSequence text, android.content.DialogInterface.OnClickListener listener) {
            super.setPositiveButton(text, listener); return this;
        }

        @Override public Builder setPositiveButton(int textId, android.content.DialogInterface.OnClickListener listener) {
            super.setPositiveButton(textId, listener); return this;
        }

        @Override public Builder setNegativeButton(CharSequence text, android.content.DialogInterface.OnClickListener listener) {
            super.setNegativeButton(text, listener); return this;
        }

        @Override public Builder setNegativeButton(int textId, android.content.DialogInterface.OnClickListener listener) {
            super.setNegativeButton(textId, listener); return this;
        }

        @Override public Builder setNeutralButton(CharSequence text, android.content.DialogInterface.OnClickListener listener) {
            super.setNeutralButton(text, listener); return this;
        }

        @Override public Builder setNeutralButton(int textId, android.content.DialogInterface.OnClickListener listener) {
            super.setNeutralButton(textId, listener); return this;
        }

        @Override public Builder setItems(CharSequence[] items, android.content.DialogInterface.OnClickListener listener) {
            super.setItems(items, listener); return this;
        }

        @Override public Builder setItems(int itemsId, android.content.DialogInterface.OnClickListener listener) {
            super.setItems(itemsId, listener); return this;
        }

        @Override public Builder setSingleChoiceItems(CharSequence[] items, int checkedItem,
                                                      android.content.DialogInterface.OnClickListener listener) {
            super.setSingleChoiceItems(items, checkedItem, listener); return this;
        }

        @Override public Builder setSingleChoiceItems(int itemsId, int checkedItem,
                                                     android.content.DialogInterface.OnClickListener listener) {
            super.setSingleChoiceItems(itemsId, checkedItem, listener); return this;
        }

        @Override public Builder setSingleChoiceItems(ListAdapter adapter, int checkedItem,
                                                      android.content.DialogInterface.OnClickListener listener) {
            super.setSingleChoiceItems(adapter, checkedItem, listener); return this;
        }

        @Override public Builder setMultiChoiceItems(CharSequence[] items, boolean[] checkedItems,
                                                     android.content.DialogInterface.OnMultiChoiceClickListener listener) {
            super.setMultiChoiceItems(items, checkedItems, listener); return this;
        }

        @Override public Builder setCancelable(boolean cancelable) { super.setCancelable(cancelable); return this; }

        @Override public Builder setOnDismissListener(android.content.DialogInterface.OnDismissListener l) {
            super.setOnDismissListener(l); return this;
        }

        @Override public Builder setOnCancelListener(android.content.DialogInterface.OnCancelListener l) {
            super.setOnCancelListener(l); return this;
        }

        @Override public android.app.AlertDialog create() {
            return wrap(super.create());
        }

        @Override public android.app.AlertDialog show() {
            android.app.AlertDialog d = create();
            d.show();
            post(d);
            return d;
        }
    }
}
