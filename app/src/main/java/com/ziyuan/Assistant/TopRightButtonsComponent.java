package com.ziyuan.Assistant;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;

/** 右上角浮动按钮（倍速/小窗横排一行）：作为控制组件加入控制器，随控制条一起显隐 */
public class TopRightButtonsComponent implements IControlComponent {

    private final Context ctx;
    private LinearLayout box;
    public TextView speedBtn, pipBtn;
    public View.OnClickListener onSpeed, onPip;

    public TopRightButtonsComponent(Context c) { ctx = c; }

    @Override
    public void attach(ControlWrapper cw) { }

    @Override
    public View getView() {
        if (box == null) {
            box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.END);

            // 横排：小窗在左，倍速在右
            if (onPip != null) {
                pipBtn = mkBtn("小窗");
                box.addView(pipBtn);
                pipBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { onPip.onClick(v); }
                });
            }

            speedBtn = mkBtn("1x");
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.leftMargin = dp(8);   // 两个按钮间距
            box.addView(speedBtn, sp);
            speedBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (onSpeed != null) onSpeed.onClick(v); }
            });

            // 整体定位：右上角，避开标题栏
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            lp.topMargin = dp(44);
            lp.rightMargin = dp(12);
            box.setLayoutParams(lp);
            box.setVisibility(View.GONE);   // 初始与控制条一致：隐藏，只在控制条出现时显示
        }
        return box;
    }

    private TextView mkBtn(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackgroundColor(0x66000000);
        return t;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, android.view.animation.Animation anim) {
        if (box != null) box.setVisibility(isVisible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onPlayStateChanged(int state) { }

    @Override
    public void onPlayerStateChanged(int state) { }

    @Override
    public void setProgress(int p, int dur) { }

    @Override
    public void onLockStateChanged(boolean lock) { }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
