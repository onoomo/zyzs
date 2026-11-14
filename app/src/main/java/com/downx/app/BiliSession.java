package com.downx.app;

import android.content.Context;
import android.content.SharedPreferences;

/** B 站登录态（SESSDATA），本机保存，只用于向 bilibili.com 发请求 */
public class BiliSession {

    private static final String PREF = "downx_bili";
    private static final String KEY = "cookie";
    private static String sCookie;

    public static void init(Context c) {
        sCookie = prefs(c).getString(KEY, null);
    }

    public static boolean has(Context c) {
        return cookie(c) != null;
    }

    public static String cookie(Context c) {
        if (c != null) {
            String s = prefs(c).getString(KEY, null);
            if (s != null) return s;
        }
        return sCookie;
    }

    public static void save(Context c, String cookie) {
        sCookie = cookie;
        prefs(c).edit().putString(KEY, cookie).apply();
    }

    public static void clear(Context c) {
        sCookie = null;
        prefs(c).edit().remove(KEY).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
