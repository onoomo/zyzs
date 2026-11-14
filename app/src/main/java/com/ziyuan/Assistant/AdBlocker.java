package com.ziyuan.Assistant;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;

/** 资源嗅探广告过滤: Adblock 规则轻量匹配(域名后缀哈希 + 白名单), 规则包 assets/adblock_lite.txt */
public class AdBlocker {

    private static final HashMap<String, ArrayList<String[]>> domainRules = new HashMap<>();
    private static final HashMap<String, ArrayList<String[]>> domainAllow = new HashMap<>();
    private static final ArrayList<String> genericRules = new ArrayList<>();
    private static final ArrayList<String> genericAllow = new ArrayList<>();
    private static final String[] BOOT_BLOCK = {
        "/api/v1/direct/", "go.marzaent.com", "smartpop",
        "propellerads", "propellerclick", "adcash", "zroiw2.com", "pu-wew" };
    private static volatile boolean loaded = false;
    private static int blocked = 0;

    private static final java.util.LinkedHashSet<String> customRules = new java.util.LinkedHashSet<>();

    /** 自定义屏蔽: 域名(含点)→整域拦截; 其它→URL关键词拦截。立即生效并持久化 */
    public static void addCustomRule(Context c, String input) {
        String k = input == null ? "" : input.trim().toLowerCase();
        if (k.isEmpty()) return;
        synchronized (customRules) {
            customRules.add(k);
            persist(c);
            applyCustom(k);
        }
    }

    public static void removeCustomRule(Context c, String k) {
        synchronized (customRules) {
            customRules.remove(k);
            persist(c);
        }
        loaded = false;
        load(c);
    }

    public static java.util.List<String> getCustomRules() {
        synchronized (customRules) { return new ArrayList<String>(customRules); }
    }

    private static void persist(Context c) {
        StringBuilder sb = new StringBuilder();
        for (String k : customRules) { if (sb.length() > 0) sb.append('\u0001'); sb.append(k); }
        c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .edit().putString("adblock_custom", sb.toString()).apply();
    }

    private static void applyCustom(String k) {
        if (k.indexOf('.') >= 0 && !k.contains(" ")) {
            ArrayList<String[]> l = domainRules.get(k);
            if (l == null) { l = new ArrayList<String[]>(); domainRules.put(k, l); }
            l.add(new String[]{k, ""});
        } else {
            genericRules.add(k);
        }
    }

    /** 首次调用异步加载规则包 */
    public static void load(Context c) {
        if (loaded) return;
        loaded = true;
        new Thread(new Runnable() { public void run() {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        c.getAssets().open("adblock_lite.txt"), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim().replace("\r", "");
                    if (line.isEmpty()) continue;
                    char c0 = line.charAt(0);
                    if (c0 == '!' || c0 == '[') continue;
                    if (line.startsWith("@@")) {
                        addRule(line.substring(2), domainAllow, genericAllow);
                        continue;
                    }
                    if (line.contains("##") || line.contains("#@#")) continue; // 元素隐藏暂不处理
                    addRule(line, domainRules, genericRules);
                }
                br.close();
            } catch (Throwable ignored) { }
            for (String b : BOOT_BLOCK) applyCustom(b);
            synchronized (customRules) {
                String saved = c.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                        .getString("adblock_custom", "");
                for (String k : saved.split("\u0001")) if (!k.isEmpty()) { customRules.add(k); applyCustom(k); }
            }
        }}, "adblock-load").start();
    }

    private static void addRule(String line, HashMap<String, ArrayList<String[]>> dom,
                                ArrayList<String> gen) {
        if (line.startsWith("||")) {
            String rest = line.substring(2);
            int dol = rest.indexOf('$');
            if (dol >= 0) {
                String opt = rest.substring(dol);
                if (opt.contains("document") || opt.contains("popup") || opt.contains("csp") || opt.contains("webrtc")) return;
                rest = rest.substring(0, dol);
            }
            int caret = rest.indexOf('^');
            String tail = "";
            if (caret >= 0) {
                tail = rest.substring(caret + 1).replace("^", "/");
                rest = rest.substring(0, caret);
            }
            rest = rest.toLowerCase();
            if (rest.isEmpty()) return;
            ArrayList<String[]> l = dom.get(rest);
            if (l == null) { l = new ArrayList<String[]>(); dom.put(rest, l); }
            l.add(new String[]{rest, tail.toLowerCase()});
        } else {
            if (line.length() < 4 || line.length() > 120) return;
            if (line.contains("$") || line.startsWith("/") || line.contains("~")) return;
            gen.add(line.toLowerCase());
        }
    }

    /** true = 命中广告规则, 应拦截 */
    public static boolean shouldBlock(String rawUrl) {
        if (!loaded) return false;
        try {
            String ul = rawUrl.toLowerCase();
            String host;
            try {
                Uri u = Uri.parse(rawUrl);
                host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            } catch (Throwable t) { host = ""; }
            if (host.isEmpty()) return false;
            // 白名单优先
            if (matchRules(domainAllow, genericAllow, host, ul)) return false;
            if (matchRules(domainRules, genericRules, host, ul)) {
                blocked++;
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean matchRules(HashMap<String, ArrayList<String[]>> dom,
                                      ArrayList<String> gen, String host, String ul) {
        String h = host;
        while (true) {
            ArrayList<String[]> list = dom.get(h);
            if (list != null) for (String[] r : list) {
                if (r[1].isEmpty() || ul.contains(r[1])) return true;
            }
            int dot = h.indexOf('.');
            if (dot < 0) break;
            h = h.substring(dot + 1);
        }
        for (String g : gen) if (ul.contains(g)) return true;
        return false;
    }

    public static int getBlockedCount() { return blocked; }
}
