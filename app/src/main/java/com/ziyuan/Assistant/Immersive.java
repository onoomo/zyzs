package com.ziyuan.Assistant;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** 全局隐藏底部导航栏(沉浸式, 上滑临时呼出自动回落) */
public class Immersive {
    public static void hide(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Window w = a.getWindow();
                w.setDecorFitsSystemWindows(false);
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            } else {
                View d = a.getWindow().getDecorView();
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignored) {}
    }
}
