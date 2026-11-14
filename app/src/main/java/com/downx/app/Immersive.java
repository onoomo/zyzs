package com.downx.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** 二级页面（使用说明 / 文件与相册）与主界面一致的沉浸式全屏：隐藏状态栏与导航栏。 */
public final class Immersive {
    private Immersive() { }

    public static void apply(Activity a) {
        WindowCompat.setDecorFitsSystemWindows(a.getWindow(), false);
        a.getWindow().setStatusBarColor(Color.TRANSPARENT);
        a.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            a.getWindow().setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat c = new WindowInsetsControllerCompat(
                a.getWindow(), a.getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        // 状态栏已隐藏，内容贴顶：只保留左右 inset 避让横屏挖孔，
        // 不再给顶部补整条状态栏高度（否则标题会被压下一大截）。
        View content = a.findViewById(android.R.id.content);
        // 挖孔屏：安全区 padding 会露出主题里浅蓝色的 windowBackground（真机截图顶部那条浅蓝带），
        // 这里把窗口与内容根统一成页面深色，浅色/深色模式都不再出现割裂色带。
        int pageBg = ContextCompat.getColor(a, com.example.immersive.R.color.set_bg);
        content.setBackgroundColor(pageBg);
        a.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(pageBg));
        if (Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            a.getWindow().setAttributes(lp);
        }
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, 0, bars.right, 0);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }
}
