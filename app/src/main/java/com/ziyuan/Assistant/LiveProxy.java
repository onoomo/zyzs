package com.ziyuan.Assistant;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.util.LinkedHashSet;

/** 本地代理+独立刷新器：页面点播放后拿到媒体列表地址(c/d)，
 *  之后服务自己每 800ms 轮询该地址（页面可退），分片由刷新器抓取并写入录制文件，
 *  VLC 播 http://127.0.0.1:8123/playlist.m3u8（ts 经 /ts?u= 中转，绕过 ffmpeg-min 无 https） */
public class LiveProxy {

    public static final int PORT = 8123;
    public static volatile byte[] latestBody = null;
    public static volatile long latestAt = 0;
    public static volatile String mediaUrl = null;
    public static volatile String tsPipe = null;
    public static volatile long liveTsBytes = 0;
    public static volatile FileOutputStream kbOut = null;
    public static volatile String kbOutName = "";
    public static volatile int pollCount = 0;
    public static volatile int failCount = 0;
    private static final java.util.LinkedHashMap<String, byte[]> segCache = new java.util.LinkedHashMap<>();
    public static void putSeg(String url, byte[] body) {
        synchronized (segCache) {
            segCache.put(url, body);
            while (segCache.size() > 24) {  // 24 片×~1MB：播放窗口10片+充足追赶余量
                String k = segCache.keySet().iterator().next();
                segCache.remove(k);
            }
        }
    }
    public static byte[] getSeg(String url) {
        synchronized (segCache) { return segCache.get(url); }
    }
    /** 当前缓存分片数（播放页路由判断用：缓存不足不切代理，避免空等） */
    public static int cacheSegmentCount() {
        synchronized (segCache) { return segCache.size(); }
    }
    private static volatile boolean running = false;
    private static volatile boolean refreshing = false;
    /** 真实录制器(RecManager)正在喂数据时为 true——缓存数据源与刷新器解耦 */
    public static volatile boolean cacheLive = false;
    private static final LinkedHashSet<String> fetched = new LinkedHashSet<>();
    private static long totalFetched = 0;   // 成功抓取的分片总数（用于 MEDIA-SEQUENCE 单调递增）

    /** 录制开始时由 RecManager 调用：登记媒体列表地址并清空旧缓存 */
    public static void beginCacheFeed(String mediaListUrl) {
        mediaUrl = mediaListUrl;
        cacheLive = true;
        synchronized (LiveProxy.class) { fetched.clear(); totalFetched = 0; }
        synchronized (segCache) { segCache.clear(); }
    }

    /** 录制结束时由 RecManager 调用：释放缓存 */
    public static void endCacheFeed() {
        cacheLive = false;
        mediaUrl = null;
        synchronized (LiveProxy.class) { fetched.clear(); totalFetched = 0; }
        synchronized (segCache) { segCache.clear(); }
    }

    /** 录制器每完整抓取一个分片调用：进内存缓存，供播放 /ts?u= 秒读 */
    public static void noteSegment(String url, byte[] body) {
        if (url == null || body == null || body.length == 0) return;
        synchronized (LiveProxy.class) {
            if (!fetched.contains(url)) { fetched.add(url); totalFetched++; }
        }
        putSeg(url, body);
    }

    public static void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    ServerSocket ss = new ServerSocket(PORT);
                    while (running) {
                        final Socket s = ss.accept();
                        new Thread(new Runnable() { public void run() {
                            try { handle(s); } catch (Throwable ignored) {}
                            finally { try { s.close(); } catch (Throwable ignored) {} }
                        } }).start();
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    /** 独立刷新器：服务里启动；每 800ms 轮询媒体列表 + 抓新分片写录制文件 */
    public static void startRefresher() {
        if (refreshing) return;
        refreshing = true;
        new Thread(new Runnable() {
            public void run() {
                // 与播放分开：录制降为后台优先级，带宽/CPU 调度让位给前台播放，
                // 避免录制持续拉流拖慢播放起播与卡顿
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) {}
                int fails = 0;
                while (refreshing) {
                    try {
                        String mu = mediaUrl;
                        if (mu == null) { Thread.sleep(800); continue; }
                        byte[] body = httpGet(mu);
                        if (body == null) {
                            Thread.sleep(1500);
                            continue;
                        }
                        fails = 0;
                        latestBody = body;
                        latestAt = System.currentTimeMillis();
                        // 解析分片，抓新的写录制
                        for (String ln : new String(body, "UTF-8").split("\n")) {
                            String t = ln.trim();
                            boolean isNew;
                            synchronized (LiveProxy.class) { isNew = !fetched.contains(t); }
                            if (t.startsWith("http") && t.contains(".ts") && isNew) {
                                byte[] seg = httpGet(t);
                                if (seg != null) {
                                    synchronized (LiveProxy.class) { fetched.add(t); totalFetched++; }
                                    // 同步放入分片缓存：录制中播放 /ts?u= 直接命中，零网络等待
                                    putSeg(t, seg);
                                    FileOutputStream fo = kbOut;
                                    if (fo != null) {
                                        fo.write(seg);
                                        fo.flush();
                                    }
                                } // 失败不标记，下一轮重试（减少时间戳断口）
                            }
                        }
                        Thread.sleep(800);
                    } catch (Throwable e) {
                        try { Thread.sleep(800); } catch (Exception ignored) {}
                    }
                }
                refreshing = false;
            }
        }).start();
    }

    public static void stopRefresher() {
        refreshing = false;
        mediaUrl = null;
        synchronized (LiveProxy.class) { fetched.clear(); totalFetched = 0; }
        synchronized (segCache) { segCache.clear(); }
    }

    /** 缓存有数据可用（录制器喂数据中，或独立刷新器在跑）——同一路流播放可改走本代理秒进 */
    public static boolean isRefresherActive() {
        return cacheLive || (refreshing && mediaUrl != null);
    }

    private static byte[] httpGet(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
            c.setRequestProperty("Referer", "https://guangdongvideo.com/");
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            // 不调用 disconnect：让连接回到平台 keep-alive 池复用，后续请求省去 TLS 握手
            return bo.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    public static void fetchLatest(String url) {
        final String fUrl = url;
        new Thread(new Runnable() { public void run() {
            byte[] body = httpGet(fUrl);
            if (body != null) { latestBody = body; latestAt = System.currentTimeMillis(); }
        } }).start();
    }

    private static void handle(Socket s) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String reqLine = br.readLine();
            if (reqLine == null) return;
            String path = reqLine.split(" ")[1];

            if (path.startsWith("/relay")) {
                // 通用中转：u=原始地址（m3u8 内容递归改写；分片流式转发）
                try {
                    String raw = URLDecoder.decode(queryParam(path, "u"), "UTF-8");
                    // 本地录制文件：直接流式回放（支持 Range），不走上游
                    if (raw.startsWith("file://")) { serveLocal(s, raw.substring(7), reqLine); return; }
                    HttpURLConnection oc = (HttpURLConnection) new URL(raw).openConnection();
                    oc.setConnectTimeout(8000);
                    oc.setReadTimeout(8000);
                    oc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                    oc.setRequestProperty("Referer", "https://guangdongvideo.com/");
                    int code = oc.getResponseCode();
                    if (code == 200) {
                        InputStream in = oc.getInputStream();
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] rb = new byte[8192];
                        int rn;
                        while ((rn = in.read(rb)) > 0) bo.write(rb, 0, rn);
                        in.close();
                        oc.disconnect();
                        byte[] body = bo.toByteArray();
                        String head = new String(body, 0, Math.min(body.length, 64), "UTF-8");
                        if (head.startsWith("#EXTM3U")) {
                            StringBuilder sb = new StringBuilder();
                            for (String ln : new String(body, "UTF-8").split("\n")) {
                                String t = ln.trim();
                                if (!t.isEmpty() && !t.startsWith("#")) {
                                    String abs = new URL(new URL(raw), t).toString();
                                    ln = "http://127.0.0.1:" + PORT + "/relay?u="
                                        + java.net.URLEncoder.encode(abs, "UTF-8");
                                }
                                sb.append(ln).append("\n");
                            }
                            writeResp(s, "200 OK", "application/vnd.apple.mpegurl", sb.toString().getBytes());
                        } else {
                            writeResp(s, "200 OK", "video/mp2t", body);
                        }
                        return;
                    }
                    oc.disconnect();
                    writeResp(s, "404 Not Found", "text/plain", "upstream err".getBytes());
                } catch (Throwable e) {
                    writeResp(s, "404 Not Found", "text/plain", "relay err".getBytes());
                }
                return;
            }

            if (path.startsWith("/live.ts")) {
                // 桥接 TS 流：outPipe 是 FIFO，阻塞式直读即可（不能轮询 length）
                try {
                    String f = tsPipe;
                    if (f == null) { writeResp(s, "404 Not Found", "text/plain", "no bridge".getBytes()); return; }
                    OutputStream os = s.getOutputStream();
                    os.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".getBytes());
                    os.flush();
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    byte[] rb = new byte[65536];
                    int rn;
                    while ((rn = in.read(rb)) > 0) {
                        os.write(rb, 0, rn);
                        os.flush();
                    }
                    in.close();
                } catch (Throwable ignored) {}
                return;
            }

            if (path.startsWith("/bili")) {
                // B站中转：u=原始媒体地址，带 B站 Referer/UA；流式转发 + Range 支持
                try {
                    String raw = URLDecoder.decode(queryParam(path, "u"), "UTF-8");
                    // 读请求头里的 Range
                    String range = null;
                    String hl;
                    while ((hl = br.readLine()) != null && !hl.isEmpty()) {
                        if (hl.toLowerCase().startsWith("range:")) {
                            range = hl.substring(6).trim();
                        }
                    }
                    HttpURLConnection oc = (HttpURLConnection) new URL(raw).openConnection();
                    oc.setConnectTimeout(8000);
                    oc.setReadTimeout(30000);
                    oc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                    oc.setRequestProperty("Referer", "https://www.bilibili.com/");
                    if (range != null) oc.setRequestProperty("Range", range);
                    int code = oc.getResponseCode();
                    if (code == 200 || code == 206) {
                        OutputStream os = s.getOutputStream();
                        StringBuilder hh = new StringBuilder("HTTP/1.1 " + code + (code == 206 ? " Partial Content" : " OK") + "\r\n");
                        String cr = oc.getHeaderField("Content-Range");
                        String cl = oc.getHeaderField("Content-Length");
                        String ct = oc.getHeaderField("Content-Type");
                        hh.append("Content-Type: ").append(ct != null ? ct : "video/mp4").append("\r\n");
                        if (cr != null) hh.append("Content-Range: ").append(cr).append("\r\n");
                        if (cl != null) hh.append("Content-Length: ").append(cl).append("\r\n");
                        hh.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
                        os.write(hh.toString().getBytes());
                        InputStream in = oc.getInputStream();
                        byte[] rb = new byte[65536];
                        int rn;
                        while ((rn = in.read(rb)) > 0) os.write(rb, 0, rn);
                        os.flush();
                        in.close(); oc.disconnect();
                        return;
                    }
                    oc.disconnect();
                    writeResp(s, "404 Not Found", "text/plain", "bili upstream err".getBytes());
                } catch (Throwable e) {
                    writeResp(s, "404 Not Found", "text/plain", "bili relay err".getBytes());
                }
                return;
            }

            if (path.startsWith("/dy")) {
                // 抖音中转：u=原始媒体地址，带抖音 Referer/UA
                try {
                    String raw = URLDecoder.decode(queryParam(path, "u"), "UTF-8");
                    String range = null;
                    String hl;
                    while ((hl = br.readLine()) != null && !hl.isEmpty()) {
                        if (hl.toLowerCase().startsWith("range:")) range = hl.substring(6).trim();
                    }
                    HttpURLConnection oc = (HttpURLConnection) new URL(raw).openConnection();
                    oc.setConnectTimeout(8000);
                    oc.setReadTimeout(30000);
                    oc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                    oc.setRequestProperty("Referer", "https://www.douyin.com/");
                    if (range != null) oc.setRequestProperty("Range", range);
                    int code = oc.getResponseCode();
                    if (code == 200 || code == 206) {
                        OutputStream os = s.getOutputStream();
                        StringBuilder hh = new StringBuilder("HTTP/1.1 " + code + (code == 206 ? " Partial Content" : " OK") + "\r\n");
                        String cr = oc.getHeaderField("Content-Range");
                        String cl = oc.getHeaderField("Content-Length");
                        String ct = oc.getHeaderField("Content-Type");
                        hh.append("Content-Type: ").append(ct != null ? ct : "video/mp4").append("\r\n");
                        if (cr != null) hh.append("Content-Range: ").append(cr).append("\r\n");
                        if (cl != null) hh.append("Content-Length: ").append(cl).append("\r\n");
                        hh.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
                        os.write(hh.toString().getBytes());
                        InputStream in = oc.getInputStream();
                        byte[] rb = new byte[65536];
                        int rn;
                        while ((rn = in.read(rb)) > 0) os.write(rb, 0, rn);
                        os.flush();
                        in.close(); oc.disconnect();
                        return;
                    }
                    oc.disconnect();
                    writeResp(s, "404 Not Found", "text/plain", "dy upstream err".getBytes());
                } catch (Throwable e) {
                    writeResp(s, "404 Not Found", "text/plain", "dy relay err".getBytes());
                }
                return;
            }

            if (path.startsWith("/playlist.m3u8")) {
                // 秒进关键：播放列表只由「录制已完整抓取且仍在内存缓存」的分片组成，
                // 播放器全程只读本地内存、零上游请求，不与录制抢带宽。
                // 之前直接转发上游原始列表，最新分片往往还没抓完，播放器被迫自己去上游下载（实测反而慢 3.5s）。
                java.util.ArrayList<String> window = new java.util.ArrayList<>();
                long seq = 0;
                // 录制刚启动/录制器还在等原画时缓存可能是空的：等待首片落地（最多 8 秒），避免播放器拿到空列表报错
                for (int i = 0; i < 16; i++) {
                    synchronized (LiveProxy.class) {
                        int n = fetched.size();
                        window.clear();
                    if (n > 0) {
                        int win = Math.min(10, n);  // 滑动窗口 10 片 ≈ 20s 积压：吸收录制节奏抖动，播放不饿死
                            int skip = n - win;
                            int k = 0;
                            for (String u : fetched) { if (k++ >= skip) window.add(u); }
                            seq = totalFetched - win;
                        }
                    }
                    if (!window.isEmpty()) break;
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                }
                if (window.isEmpty()) { writeResp(s, "404 Not Found", "text/plain", "no stream".getBytes()); return; }
                StringBuilder sb = new StringBuilder();
                sb.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n")
                  .append("#EXT-X-MEDIA-SEQUENCE:").append(seq).append("\n");
                for (String u : window) {
                    sb.append("#EXTINF:2.0,\n")
                      .append("http://127.0.0.1:").append(PORT).append("/ts?u=")
                      .append(java.net.URLEncoder.encode(u, "UTF-8")).append("\n");
                }
                writeResp(s, "200 OK", "application/vnd.apple.mpegurl", sb.toString().getBytes());
                return;
            }

            if (path.startsWith("/ts?u=")) {
                String raw = URLDecoder.decode(queryParam(path, "u"), "UTF-8");
                // 1) 命中录制刷新器已抓取的分片缓存：直接回内存数据，播放器零等待
                byte[] cached = getSeg(raw);
                if (cached != null) { writeResp(s, "200 OK", "video/mp2t", cached); return; }
                // 2) 未命中：流式转发（边下边发），播放器不必等整个分片下载完
                OutputStream os = s.getOutputStream();
                try {
                    HttpURLConnection oc = (HttpURLConnection) new URL(raw).openConnection();
                    oc.setConnectTimeout(6000);
                    oc.setReadTimeout(6000);
                    oc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                    oc.setRequestProperty("Referer", "https://guangdongvideo.com/");
                    if (oc.getResponseCode() != 200) { oc.disconnect(); return; }
                    os.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".getBytes());
                    os.flush();
                    InputStream in = oc.getInputStream();
                    byte[] rb = new byte[16384];
                    int rn;
                    while ((rn = in.read(rb)) > 0) { os.write(rb, 0, rn); os.flush(); }
                    in.close();
                    // 不 disconnect，保留 keep-alive 连接复用
                } catch (Throwable ignored) {}
                return;
            }

            writeResp(s, "404 Not Found", "text/plain", "no".getBytes());
        } catch (Throwable ignored) {}
    }

    private static String queryParam(String path, String key) {
        try {
            int qi = path.indexOf('?');
            if (qi < 0) return "";
            for (String kv : path.substring(qi + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
            }
        } catch (Exception ignored) {}
        return "";
    }


    /** 本地文件流式服务（支持单段Range，供局域网回放录制文件） */
    private static void serveLocal(Socket s, String path, String reqLine) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists() || !f.isFile()) { writeResp(s, "404 Not Found", "text/plain", "file not found".getBytes()); return; }
            long len = f.length(), start = 0, end = len - 1;
            String range = null;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null && !ln.isEmpty()) if (ln.toLowerCase().startsWith("range:")) range = ln;
            boolean partial = false;
            if (range != null && range.contains("bytes=")) {
                try {
                    String spec = range.substring(range.indexOf('=') + 1).trim();
                    int dash = spec.indexOf('-');
                    start = Long.parseLong(spec.substring(0, dash).trim());
                    if (dash < spec.length() - 1) end = Long.parseLong(spec.substring(dash + 1).trim());
                    if (end >= len) end = len - 1;
                    partial = start <= end;
                } catch (Throwable ignored) { }
            }
            java.io.InputStream in = new java.io.FileInputStream(f);
            if (partial) { in.skip(start); }
            else { start = 0; }
            String head = (partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                + "Content-Type: video/mp2t\r\n"
                + "Accept-Ranges: bytes\r\n"
                + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + len + "\r\n" : "")
                + "Content-Length: " + (end - start + 1) + "\r\nConnection: close\r\n\r\n";
            OutputStream os = s.getOutputStream();
            os.write(head.getBytes());
            byte[] buf = new byte[64 * 1024];
            long remain = end - start + 1;
            int n;
            while (remain > 0 && (n = in.read(buf, 0, (int)Math.min(buf.length, remain))) > 0) {
                os.write(buf, 0, n); remain -= n;
            }
            in.close(); os.flush();
        } catch (Throwable e) { try { writeResp(s, "404 Not Found", "text/plain", "file err".getBytes()); } catch (Throwable ignored) {} }
    }

    private static void writeResp(Socket s, String status, String type, byte[] body) {
        try {
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes());
            os.write(body);
            os.flush();
        } catch (Throwable ignored) {}
    }
}
