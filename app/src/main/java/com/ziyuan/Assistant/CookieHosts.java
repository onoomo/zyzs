package com.ziyuan.Assistant;

/** 记录 WebView 登录/访问过的站点域名, 供视频下载导出对应 Cookie */
public class CookieHosts {
    private static final String SP = "cookie_hosts";

    public static void add(android.content.Context c, String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String h = u.getHost();
            if (h == null || h.length() < 3) return;
            if (h.equals("bilibili.com") || h.endsWith(".bilibili.com")) return; // b站已固定导出
            if (h.equals("douyin.com") || h.endsWith(".douyin.com")) return;
            android.content.SharedPreferences sp = c.getSharedPreferences(SP, 0);
            java.util.Set<String> set = new java.util.HashSet<String>(sp.getStringSet("hosts", new java.util.HashSet<String>()));
            if (set.add(h)) sp.edit().putStringSet("hosts", set).apply();
        } catch (Throwable ignored) {}
    }

    public static java.util.Set<String> hosts(android.content.Context c) {
        try {
            return c.getSharedPreferences(SP, 0).getStringSet("hosts", new java.util.HashSet<String>());
        } catch (Throwable e) { return new java.util.HashSet<String>(); }
    }
}
