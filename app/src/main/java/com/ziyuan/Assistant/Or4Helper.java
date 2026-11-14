package com.ziyuan.Assistant;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 抖音直播「新鲜地址」登记处：
 * 嗅探阶段(shouldInterceptRequest/dygrab.js)每拿到一条流地址就 handle() 登记；
 * 签名/expire 只有几分钟时效，录制断流重试、后台播放重启时用 freshFor()
 * 把过期旧地址热替换为同一路直播的最新地址，否则永远 403
 * （症状：录制大小 0 字节、后台播放卡"转码中"）。
 *
 * 按「文件名(画质+格式)」分键存储：or4.m3u8 的记录只换 or4.m3u8 的新地址，
 * 避免被 dygrab 持续登记的 flv/其他画质变体顶掉导致 ffmpeg 封装失败。
 *
 * 只负责地址登记与替换，不产生记录行——列表抓取由 SniffActivity 管线负责。
 */
public final class Or4Helper {

    // key = 流文件名(不含query, 如 stream-408...63_or4.m3u8) → 最新完整地址
    private static final Map<String, String> FRESH = new LinkedHashMap<String, String>();
    private static final Map<String, Long> FRESH_MS = new LinkedHashMap<String, Long>();

    private Or4Helper() {
    }

    /**
     * 返回同一文件(同画质+同格式)的最新新鲜地址；拿不到(过期>2min/不同格式/相同)返回 null。
     * 供录制重试与播放启动在旧地址 403 时热替换。
     */
    public static String freshFor(String old) {
        try {
            if (old == null || old.length() < 20) return null;
            String k = streamKey(old);
            if (k == null) return null;
            String f;
            Long ms;
            synchronized (FRESH) {
                f = FRESH.get(k);
                ms = FRESH_MS.get(k);
            }
            if (f == null || ms == null || f.length() < 20) return null;
            long age = System.currentTimeMillis() - ms;
            if (age < 0 || age > 120000L) return null;  // 2分钟内登记的地址都可信(sign 几分钟时效)
            if (f.equals(old)) return null;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 嗅探到抖音直播流地址即登记（主线程/拦截线程均可，Map 加锁）。 */
    public static void handle(String url) {
        if (url == null || url.length() < 20) return;
        String l = url.toLowerCase();
        if (!l.startsWith("http")) return;
        String k = streamKey(url);
        if (k == null) return;
        synchronized (FRESH) {
            FRESH.put(k, url);
            FRESH_MS.put(k, System.currentTimeMillis());
            while (FRESH.size() > 50) {  // 防膨胀：淘汰最早的键
                String first = FRESH.keySet().iterator().next();
                FRESH.remove(first);
                FRESH_MS.remove(first);
            }
        }
        log("FRESH " + url);
    }

    /** 流文件名作为 key：取路径最后一段并去掉 query，如 stream-408...63_or4.m3u8 */
    public static String streamKey(String u) {
        try {
            int q = u.indexOf('?');
            String p = q > 0 ? u.substring(0, q) : u;
            int s = Math.max(p.lastIndexOf('/'), 0);
            String seg = p.substring(s + 1);
            if (seg.length() < 8 || !seg.contains("stream-")) return null;
            return seg;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String s) {
        try {
            Method m = SniffActivity.class.getDeclaredMethod("dumpFc2Debug", String.class);
            m.setAccessible(true);
            m.invoke(null, s);
        } catch (Throwable ignored) {
        }
    }
}
