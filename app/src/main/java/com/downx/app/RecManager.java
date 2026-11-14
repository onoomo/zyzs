package com.downx.app;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播录制器 —— 逻辑对齐原版 Assistant 的 LiveRec / RecManager：
 * 1) 主清单(#EXT-X-STREAM-INF) 自动解析出码率最高的子清单（媒体分片清单）再录制；
 * 2) 以 #EXT-X-MEDIA-SEQUENCE + 行序号 作为分片唯一键去重（抖音分片 URL 带时效 token，
 *    每轮刷新 URL 都会变，按 URL 去重会反复下同一分片 / 漏分片）；
 * 3) 抖音系 CDN 强制 Referer: https://live.douyin.com/ 与桌面 UA，否则 403；
 * 4) HLS：循环拉索引，把新分片按序追加进 .ts 文件；
 *    FLV/MP4：长连接流式拷贝，断流即停（原版 FLV 录制同款行为）。
 */
public class RecManager {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    /** 进行中的录制任务：url -> 线程。 */
    private static final Map<String, RecThread> jobs = new ConcurrentHashMap<String, RecThread>();

    public static void init(Context c) { /* 资源目录按需创建 */ }

    public static boolean isRecording(String url) {
        return url != null && jobs.containsKey(url);
    }

    public static void startRecJob(Context c, String url, String title) {
        if (url == null || url.isEmpty() || jobs.containsKey(url)) return;
        final Context app = c.getApplicationContext();
        final String name = safeName(title) + "_" + System.currentTimeMillis() + extOf(url);
        // 录制文件直接落进 App 私有下载目录（Android/data/.../files/Movies/DownX），
        // 并登记进「媒体管理」的下载历史，录完后就能在下载列表里播放 / 删除。
        try {
            HistoryStore.Entry e = new HistoryStore.Entry();
            e.name = name;
            e.sub = "直播录制";
            e.pageUrl = url;
            e.kind = "video";
            e.key = "rec|" + url;
            e.visible = false;
            e.ts = System.currentTimeMillis();
            HistoryStore.add(app, e);
        } catch (Throwable ignored) { }
        RecThread t = new RecThread(app, url, title, name);
        jobs.put(url, t);
        t.start();
    }

    public static void recStop(String url) {
        if (url == null) return;
        RecThread t = jobs.remove(url);
        if (t != null) t.requestStop();
    }

    public static void recStopAll() {
        for (RecThread t : new ArrayList<RecThread>(jobs.values())) t.requestStop();
        jobs.clear();
    }

    private static File recDir(Context c) {
        // 与「媒体管理」下载同目录（Android/data/<包名>/files/Movies/DownX），列表里能直接管理
        return Store.privateDir(c, "video");
    }

    private static String safeName(String s) {
        if (s == null) s = "";
        s = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isEmpty() ? "直播" : s;
    }

    private static String extOf(String url) {
        String u = url.toLowerCase();
        if (u.contains(".m3u8")) return ".ts";
        if (u.contains(".flv")) return ".flv";
        if (u.contains(".mp4") || u.contains(".m4s")) return ".mp4";
        if (u.contains(".ts")) return ".ts";
        return ".ts";
    }

    /** 请求头：抖音系强制 live.douyin.com Referer；其余按域名根。 */
    private static void setHeaders(HttpURLConnection c, String url) {
        c.setRequestProperty("User-Agent", UA);
        String u = url.toLowerCase();
        if (u.contains("douyin") || u.contains("amemv") || u.contains("douyincdn")
                || u.contains("zijieapi") || u.contains("bytedance") || u.contains("bytecdn")) {
            c.setRequestProperty("Referer", "https://live.douyin.com/");
        } else {
            try {
                URL uu = new URL(url);
                c.setRequestProperty("Referer", uu.getProtocol() + "://" + uu.getHost() + "/");
            } catch (Throwable ignored) { }
        }
    }

    private static HttpURLConnection open(String u, int connMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(connMs);
        c.setReadTimeout(readMs);
        setHeaders(c, u);
        return c;
    }

    private static class RecThread extends Thread {
        private final Context ctx;
        private final String url;
        private final String title;
        private volatile boolean stop = false;
        private long written = 0;
        private volatile String status = "准备中…";

        RecThread(Context ctx, String url, String title, String fileName) {
            this.ctx = ctx;
            this.url = url;
            this.title = title;
            this.fileName = fileName;
            setName("rec-" + url.hashCode());
        }
        private final String fileName;

        void requestStop() {
            stop = true;
            this.interrupt();
        }

        @Override
        public void run() {
            File out = new File(recDir(ctx), fileName);
            try {
                OutputStream os = new FileOutputStream(out, true);
                boolean isHls = url.toLowerCase().contains(".m3u8");
                if (isHls) {
                    runHls(os);
                } else {
                    runStream(os);
                }
                os.close();
            } catch (Throwable t) {
                android.util.Log.w("RecManager", "录制异常 " + url + " -> " + t);
            } finally {
                jobs.remove(url);
            }
        }

        /** HLS 直播录制（对齐原版）：先解析主清单，再循环抓新分片追加。 */
        private void runHls(OutputStream os) throws Exception {
            // ① 主清单 → 子清单：#EXT-X-STREAM-INF 取最高码率的 variant
            String mediaUrl = url;
            try {
                Pl p = parsePlaylist(mediaUrl);
                if (p.variant != null) mediaUrl = p.variant;
            } catch (Throwable ignored) { }
            // ② 循环拉媒体清单，按「媒体序号+行序」去重，把新分片按序追加
            Set<String> done = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
            final long start = System.currentTimeMillis();
            long lastNew = start;
            while (!stop) {
                try {
                    Pl p = parsePlaylist(mediaUrl);
                    if (p.variant != null && !p.variant.equals(mediaUrl)) mediaUrl = p.variant;
                    if (!p.segments.isEmpty()) {
                        for (String seg : p.segments) {
                            if (stop) break;
                            if (!done.add(seg)) continue;
                            byte[] data = fetchBytes(seg);
                            if (data != null && data.length > 0) {
                                os.write(data);
                                os.flush();
                                written += data.length;
                                lastNew = System.currentTimeMillis();
                                status = "录制中 " + (written / 1024) + "KB";
                            }
                        }
                    } else {
                        status = "清单中暂无分片";
                    }
                } catch (Throwable t) {
                    status = "重试中…";
                }
                if (stop) break;
                // 5 分钟没抓到任何新分片 → 判定直播结束（对齐原版宽限窗口）
                if (System.currentTimeMillis() - lastNew > 300000) break;
                Thread.sleep(1500);
            }
        }

        /** 裸流（FLV/MP4）：流式拷贝直到断开。 */
        private void runStream(OutputStream os) throws Exception {
            HttpURLConnection c = open(url, 10000, 0); // 直播长连接，不超时
            try {
                InputStream in = c.getInputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while (!stop && (n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    written += n;
                    status = "录制中 " + (written / 1024) + "KB";
                    if (written > 8L * 1024 * 1024 * 1024) break; // 8GB 上限保护
                }
                in.close();
            } finally {
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
        }

        private byte[] fetchBytes(String u) {
            try {
                HttpURLConnection c = open(u, 10000, 20000);
                try {
                    InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    return bo.toByteArray();
                } finally {
                    try { c.disconnect(); } catch (Throwable ignored) { }
                }
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** 一次清单解析结果：variant = 子清单（主清单时非空），segments = 媒体分片。 */
    private static class Pl {
        String variant = null;
        List<String> segments = new ArrayList<String>();
    }

    /** 解析 m3u8：主清单取最高带宽子清单；媒体清单返回带绝对地址的分片列表（按媒体序号命名键）。 */
    private static Pl parsePlaylist(String m3u8) throws Exception {
        Pl p = new Pl();
        HttpURLConnection c = open(m3u8, 10000, 10000);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            long bestBw = -1;
            String bestVariant = null;
            long pendingBw = -1;
            long mediaSeq = 0;
            int idxInList = 0;
            String base = m3u8;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try { mediaSeq = Long.parseLong(line.substring(21).trim()); } catch (Throwable ignored) { }
                    idxInList = 0;
                    continue;
                }
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    pendingBw = parseBw(line);
                    continue;
                }
                if (line.startsWith("#")) continue;
                // 非注释行：主清单里是子清单，媒体清单里是分片
                String abs = absolutize(base, line);
                if (pendingBw >= 0) {
                    if (pendingBw > bestBw) { bestBw = pendingBw; bestVariant = abs; }
                    pendingBw = -1;
                    continue;
                }
                // 分片：键 = 媒体序号 + 行内序（URL 带时效 token 也会变，键不变）
                p.segments.add(mediaSeq + ":" + idxInList + "|" + abs);
                idxInList++;
            }
            r.close();
            if (bestVariant != null) p.variant = bestVariant;
            return p;
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static long parseBw(String streamInfLine) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("BANDWIDTH=([0-9]+)").matcher(streamInfLine);
            return m.find() ? Long.parseLong(m.group(1)) : 0;
        } catch (Throwable t) { return 0; }
    }

    private static String absolutize(String base, String line) {
        if (line.startsWith("http://") || line.startsWith("https://")) return line;
        try {
            return new URL(new URL(base), line).toString();
        } catch (Throwable t) {
            int slash = base.lastIndexOf('/');
            return base.substring(0, slash + 1) + line;
        }
    }
}
