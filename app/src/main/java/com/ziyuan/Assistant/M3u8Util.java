package com.ziyuan.Assistant;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M3U8 下载工具 (移植自 huangdali/M3U8Manger, 协议 CC0/Apache-2.0 兼容思路)
 *   - 识别: parseIndex() 拉取并解析 m3u8 索引, 递归展开嵌套/主清单(取首个变体), 得到分片列表与总时长
 *   - 下载: download() 用固定线程池(10) 并行拉取全部 .ts, 再顺序合并为单个文件
 * 纯 Java 实现, 不依赖 ffmpeg / Apache Commons IO
 * 注: 不支持 EXT-X-KEY 加密流(与上游一致, 加密分片无法解密)
 */
public class M3u8Util {

    public static class M3U8Ts {
        public String name;   // 分片文件名(相对 basePath)
        public float seconds; // 该分片时长(EXTINF)
        public M3U8Ts(String n, float s) { name = n; seconds = s; }
    }

    public static class M3U8 {
        public String basePath = "";          // 分片前缀(解析后的真实地址目录)
        public List<M3U8Ts> tsList = new ArrayList<M3U8Ts>();
        public float totalSeconds = 0;
        public List<String> variants = new ArrayList<String>(); // 主清单里的子清单地址
        public String type = "vod";           // vod / live / master
    }

    public interface Listener {
        void onStart();
        void onProgress(int done, int total, long doneBytes, long totalBytes);
        void onCompleted(File out);
        void onError(Throwable e);
    }

    /** 取消令牌: download 过程每片前检查, 置 true 即中止 */
    public static class CancelToken {
        public volatile boolean cancelled = false;
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 识别: 拉取并解析 m3u8。非 m3u8 内容抛 IOException。 */
    public static M3U8 parseIndex(String url) throws IOException {
        return parseIndex(url, 0);
    }

    private static M3U8 parseIndex(String url, int depth) throws IOException {
        if (depth > 6) throw new IOException("m3u8 嵌套过深");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", UA);
        int code = conn.getResponseCode();
        if (code != 200) { conn.disconnect(); throw new IOException("HTTP " + code); }
        String realUrl = conn.getURL().toString();
        BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        String base = realUrl.substring(0, realUrl.lastIndexOf('/') + 1);
        M3U8 ret = new M3U8();
        ret.basePath = base;
        String line;
        float sec = 0;
        boolean isM3u = false;
        List<String> nested = new ArrayList<String>();
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTM3U")) isM3u = true;
                else if (line.startsWith("#EXTINF:")) {
                    String s = line.substring(8);
                    if (s.endsWith(",")) s = s.substring(0, s.length() - 1);
                    try { sec = Float.parseFloat(s); } catch (Throwable ignored) {}
                } else if (line.startsWith("#EXT-X-STREAM-INF")) {
                    ret.type = "master";
                } else if (line.startsWith("#EXT-X-ENDLIST")) {
                    // VOD 标记
                }
                continue;
            }
            if (line.toLowerCase().contains(".m3u8")) {  // 嵌套/主清单: 记录子清单, 稍后递归首个
                nested.add(resolve(base, line));
                continue;
            }
            ret.tsList.add(new M3U8Ts(line, sec));
            ret.totalSeconds += sec;
            sec = 0;
        }
        r.close();
        conn.disconnect();
        if (!isM3u && ret.tsList.isEmpty()) throw new IOException("不是合法的 m3u8");
        if (!nested.isEmpty()) {
            ret.variants = nested;
            M3U8 sub = parseIndex(nested.get(0), depth + 1); // 取首个变体(分片清单)
            sub.variants = nested;
            if (sub.type.equals("vod")) sub.type = "vod";
            return sub;
        }
        if (ret.type.equals("master")) ret.type = "vod";
        return ret;
    }

    /** 下载方式(移植 M3U8Manger): 并行拉取全部 ts -> 合并为单个文件 */
    public static void download(final M3U8 m3u8, final File out, final Listener l) {
        download(m3u8, out, l, new CancelToken());
    }

    public static void download(final M3U8 m3u8, final File out, final Listener l, final CancelToken token) {
        final int total = m3u8.tsList.size();
        if (total == 0) { l.onError(new IOException("m3u8 无分片")); return; }
        File tmp = new File(out.getParentFile(), out.getName() + ".parts");
        tmp.mkdirs();
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicLong doneBytes = new AtomicLong(0);
        final AtomicBoolean err = new AtomicBoolean(false);
        final ExecutorService ex = Executors.newFixedThreadPool(10);
        final CountDownLatch latch = new CountDownLatch(total);
        l.onStart();
        for (final M3U8Ts ts : m3u8.tsList) {
            ex.execute(new Runnable() {
                public void run() {
                    try {
                        if (token.cancelled) return;
                        File f = new File(tmp, safeName(ts.name));
                        if (f.exists() && f.length() > 0) {
                            doneBytes.addAndGet(f.length());           // 断点续传: 已存在直接复用
                        } else {
                            URL u = new URL(resolve(m3u8.basePath, ts.name));
                            HttpURLConnection c = (HttpURLConnection) u.openConnection();
                            c.setConnectTimeout(15000);
                            c.setReadTimeout(60000);
                            c.setRequestProperty("User-Agent", UA);
                            InputStream in = c.getInputStream();
                            FileOutputStream fo = new FileOutputStream(f);
                            byte[] b = new byte[32 * 1024];
                            int n;
                            while ((n = in.read(b)) > 0) {
                                if (token.cancelled) { fo.close(); in.close(); c.disconnect(); return; }
                                fo.write(b, 0, n);
                                doneBytes.addAndGet(n);
                            }
                            fo.close();
                            in.close();
                            c.disconnect();
                        }
                        int d = done.incrementAndGet();
                        l.onProgress(d, total, doneBytes.get(), 0);
                    } catch (Throwable e) {
                        err.set(true);
                        try { l.onError(e); } catch (Throwable ignored) {}
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try { latch.await(); } catch (InterruptedException ignored) {}
        ex.shutdown();
        if (err.get() || token.cancelled) { clearTmp(tmp); return; } // onError 已通知
        // 顺序合并
        try {
            FileOutputStream fos = new FileOutputStream(out);
            for (M3U8Ts ts : m3u8.tsList) {
                File f = new File(tmp, safeName(ts.name));
                if (f.exists() && f.length() > 0) {
                    FileInputStream fis = new FileInputStream(f);
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while ((n = fis.read(b)) > 0) fos.write(b, 0, n);
                    fis.close();
                }
            }
            fos.close();
            clearTmp(tmp);
            l.onCompleted(out);
        } catch (Throwable e) {
            clearTmp(tmp);
            try { l.onError(e); } catch (Throwable ignored) {}
        }
    }

    private static void clearTmp(File tmp) {
        try {
            if (tmp.isDirectory()) { File[] fs = tmp.listFiles(); if (fs != null) for (File f : fs) f.delete(); }
            tmp.delete();
        } catch (Throwable ignored) {}
    }

    /** 相对分片名做安全化(去掉路径分隔, 防止跨目录) */
    private static String safeName(String name) {
        String s = name;
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        s = s.replace('/', '_').replace('\\', '_').replace(':', '_');
        return s.isEmpty() ? "seg.ts" : s;
    }

    /** 解析分片真实地址: 绝对(http/https)直接用, 否则 base + 相对 */
    private static String resolve(String base, String line) {
        if (line.startsWith("http://") || line.startsWith("https://")) return line;
        return base + line;
    }

    /** 秒 -> mm:ss / hh:mm:ss */
    public static String fmtDur(long sec) {
        long s = sec;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        if (h > 0) return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s);
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }
}
