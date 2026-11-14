package com.downx.app;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 明暗主题切换（纯 framework，不依赖 AppCompatDelegate）。
 *
 * 原理：在 Activity.attachBaseContext() 里覆盖 Configuration.uiMode 的 NIGHT 位，
 * 让 Android 资源系统自动在 values/（浅色）与 values-night/（深色）之间选择同名颜色。
 * 所有 layout / drawable / 代码里的 @color/xxx 引用无需任何改动即随主题换值，
 * 且 createConfigurationContext 为 API 17+ 标准 API，向下兼容 minSdk 21。
 *
 * app 的明暗完全由设置页开关决定，不跟随系统夜间模式（避免系统切深色时界面被改）。
 */
public final class ThemeMode {
    private static final String PREF = "downx_demo";
    private static final String KEY_DARK = "dark_mode";

    private ThemeMode() { }

    public static boolean isDark(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_DARK, false);
    }

    public static void setDark(Context c, boolean dark) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DARK, dark).apply();
    }

    /** 在 Activity.attachBaseContext(newBase) 中调用，返回 uiMode 被覆盖的 context。 */
    public static Context apply(Context base) {
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int mask = Configuration.UI_MODE_NIGHT_MASK;
        int night = isDark(base)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~mask) | (night & mask);
        return base.createConfigurationContext(cfg);
    }
}
