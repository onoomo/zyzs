package com.downx.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Instagram 登录态（sessionid 等 Cookie），本机保存，只用于向 instagram.com 发请求。
 *
 * 为什么必须有：IG 从 2024 年起对匿名 API 一律返回
 * {@code 401 {"message":"Please wait a few minutes...","require_login":true}}，
 * 连公开帖的 embed 页都只给一个不含数据的空壳。没有 sessionid 就解析不出任何东西。
 */
public class IgSession {

    private static final String PREF = "downx_ig";
    private static final String KEY = "cookie";
    private static final String KEY_BAD = "expired";
    private static String sCookie;
    private static Context sCtx;

    public static void init(Context c) {
        sCtx = c.getApplicationContext();
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

    /** 给不持有 Context 的抓取器用（IgFetcher 是纯 Java，跑在桌面验证台里时这里是 null） */
    public static String cached() {
        return sCookie;
    }

    public static void save(Context c, String cookie) {
        sCookie = cookie;
        prefs(c).edit().putString(KEY, cookie).putBoolean(KEY_BAD, false).apply();
    }

    public static void clear(Context c) {
        sCookie = null;
        prefs(c).edit().remove(KEY).putBoolean(KEY_BAD, false).apply();
        // 只清"登录凭证"，绝不能 removeAllCookies：
        // WebView 里的匿名 cookie（mid/csrftoken/ig_did）是 embed 匿名下载的门票，
        // 2026-11-09 实测：全清之后 embed 页直接被 IG 302 到登录墙，匿名解析全挂
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            for (String host : new String[]{"https://www.instagram.com", "https://i.instagram.com"}) {
                cm.setCookie(host, "sessionid=; Max-Age=0; Path=/");
                cm.setCookie(host, "sessionid_sig=; Max-Age=0; Path=/");
                cm.setCookie(host, "ds_user_id=; Max-Age=0; Path=/");
            }
            cm.flush();
        } catch (Throwable ignored) { }
    }

    /**
     * 会话自动续期：Instagram 每次请求都可能下发新的 sessionid / csrftoken，
     * 不写回本地就会出现「服务端已经换了票据、本地还拿着旧的」——表现为过一阵子突然 401。
     * 所以凡是 instagram.com 的响应，都把里面的 Set-Cookie 合并回本地。
     * 返回值：是否真的更新了。
     */
    public static synchronized boolean mergeFromResponse(String setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return false;
        if (!setCookies.contains("sessionid=") && !setCookies.contains("csrftoken=")) return false;
        String cur = sCookie;
        if (cur == null) return false;                 // 没登录过就别凭空造一个
        java.util.Map<String, String> merged = parse(cur);
        boolean changed = false;
        for (String part : setCookies.split(";")) {
            String kv = part.trim();
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq), v = kv.substring(eq + 1);
            if (v.isEmpty()) continue;
            if (!v.equals(merged.get(k))) { merged.put(k, v); changed = true; }
        }
        if (!changed) return false;
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : merged.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sCookie = sb.toString();
        if (sCtx != null) {
            prefs(sCtx).edit().putString(KEY, sCookie).putBoolean(KEY_BAD, false).apply();
        }
        return true;
    }

    /** 服务器明确说登录态无效（401 / require_login）时打标记，设置页会提示重新导入 */
    public static void markExpired() {
        if (sCtx == null) return;
        prefs(sCtx).edit().putBoolean(KEY_BAD, true).apply();
    }

    public static boolean expired(Context c) {
        return has(c) && prefs(c).getBoolean(KEY_BAD, false);
    }

    private static java.util.Map<String, String> parse(String cookies) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        if (cookies == null) return m;
        for (String part : cookies.split(";")) {
            String kv = part.trim();
            int eq = kv.indexOf('=');
            if (eq > 0) m.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return m;
    }

    /** 判断这份 Cookie 里有没有真正可用的登录票据 */
    public static boolean usable(String cookie) {
        return cookie != null && cookie.contains("sessionid=");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
