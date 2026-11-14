package com.ziyuan.Assistant;

import android.content.Context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 轻量下载管理：嗅探"下载"入口 → 下载页卡片。HLS 走 ffmpeg 分段下载，支持 开始/暂停/结束(合并MP4) */
public class DlManager {

    public static class DlJob {
        public int id;
        public String url;
        public String title;
        public volatile boolean active = true;
        public volatile boolean paused = false;
        public volatile boolean done = false;
        public volatile boolean failed = false;
        public volatile boolean hls = false;   // HLS 分段下载模式
        public volatile boolean m3u8 = false;  // M3U8Manger 纯 HTTP 下载方式(解析+并行ts+合并)
        public volatile long total = -1;
        public volatile long doneBytes = 0;
        public volatile String state = "连接中…";
        public volatile boolean notified = false;   // 通知栏已发结果
        public java.io.File file;   // 直连模式=目标文件；HLS模式=分段目录
    }

    private static final ConcurrentHashMap<Integer, DlJob> JOBS = new ConcurrentHashMap<Integer, DlJob>();
    private static final ConcurrentHashMap<Integer, com.arthenica.ffmpegkit.FFmpegSession> HLS_SESS =
        new java.util.concurrent.ConcurrentHashMap<Integer, com.arthenica.ffmpegkit.FFmpegSession>();
    private static final ConcurrentHashMap<Integer, M3u8Util.CancelToken> M3U8_TOKENS =
        new java.util.concurrent.ConcurrentHashMap<Integer, M3u8Util.CancelToken>();
    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static Context sCtx;

    public static void init(Context c) { sCtx = c.getApplicationContext(); }

    public static java.util.Collection<DlJob> jobs() { return JOBS.values(); }

    public static void start(String url) {
        String lu = url.toLowerCase();
        if (lu.contains("127.0.0.1:8123/relay")) startHls(url);
        else if (lu.contains(".m3u8") || lu.contains("playlist")) startM3u8(url);  // M3U8Manger 方式: 解析+并行ts+合并
        else startDirect(url);
    }

    /** 直连单文件下载 */
    public static void startDirect(String url) {
        final DlJob j = new DlJob();
        j.id = SEQ.incrementAndGet();
        j.url = url;
        String t = url.substring(url.lastIndexOf('/') + 1);
        if (t.contains("?")) t = t.substring(0, t.indexOf('?'));
        if (t.length() == 0) t = "资源嗅探_" + System.currentTimeMillis() / 1000;
        j.title = t;
        j.file = new java.io.File(sCtx.getExternalFilesDir(null), "下载/dl_" + Integer.toHexString(url.hashCode()) + ".ts");
        try { j.file.getParentFile().mkdirs(); } catch (Throwable ignored) {}
        JOBS.put(j.id, j);
        try { DownloadService.start(sCtx); } catch (Throwable ignored) {}
        DlKeepService.ensure(sCtx);
        new Thread(new Runnable() { public void run() { download(j); } }).start();
    }

    /** HLS 分段下载：开始即启动，支持暂停/继续/结束合并 */
    public static void startHls(String url) {
        final DlJob j = new DlJob();
        j.id = SEQ.incrementAndGet();
        j.url = url;
        j.title = "HLS_" + System.currentTimeMillis() / 1000;
        j.hls = true;
        j.file = new java.io.File(sCtx.getExternalFilesDir(null), "下载/hls" + j.id);
        j.file.mkdirs();
        JOBS.put(j.id, j);
        try { DownloadService.start(sCtx); } catch (Throwable ignored) {}
        DlKeepService.ensure(sCtx);
        launchSegmentFfmpeg(j);
    }

    /** M3U8Manger 下载方式: 纯 HTTP 解析 m3u8(识别分片) + 并行下载全部 ts + 合并, 无需 ffmpeg */
    public static void startM3u8(String url) {
        final DlJob j = new DlJob();
        j.id = SEQ.incrementAndGet();
        j.url = url;
        j.title = "M3U8_" + System.currentTimeMillis() / 1000;
        j.hls = true;
        j.m3u8 = true;
        j.file = new java.io.File(sCtx.getExternalFilesDir(null), "下载/m3u8_" + j.id + ".ts");
        try { j.file.getParentFile().mkdirs(); } catch (Throwable ignored) {}
        final M3u8Util.CancelToken token = new M3u8Util.CancelToken();
        M3U8_TOKENS.put(j.id, token);
        JOBS.put(j.id, j);
        try { DownloadService.start(sCtx); } catch (Throwable ignored) {}
        DlKeepService.ensure(sCtx);
        j.active = true;
        j.state = "解析中…";
        new Thread(new Runnable() {
            public void run() {
                try {
                    M3u8Util.M3U8 m = M3u8Util.parseIndex(url);  // 识别
                    if (token.cancelled) { cleanupM3u8(j); return; }
                    j.total = m.tsList.size();
                    M3u8Util.download(m, j.file, new M3u8Util.Listener() {
                        public void onStart() { j.active = true; j.state = "下载中"; }
                        public void onProgress(int done, int total, long db, long tb) {
                            if (token.cancelled) return;
                            j.doneBytes = db; j.total = total; j.active = true;
                            j.state = "下载中 " + done + "/" + total;
                        }
                        public void onCompleted(java.io.File o) {
                            boolean ok = insertGallery(j);
                            HistoryStore.add(sCtx, "视频", j.title, "file://" + o.getAbsolutePath());
                            j.doneBytes = o.length();
                            j.done = true; j.active = false; j.paused = false;
                            j.state = ok ? "已完成(视频栏)" : "完成(未入相册)";
                            M3U8_TOKENS.remove(j.id);
                        }
                        public void onError(Throwable e) {
                            if (token.cancelled) { cleanupM3u8(j); return; }
                            j.failed = true; j.active = false;
                            j.state = "下载失败: " + e.getClass().getSimpleName();
                            M3U8_TOKENS.remove(j.id);
                        }
                    }, token);
                } catch (Throwable e) {
                    if (token.cancelled) { cleanupM3u8(j); return; }
                    j.failed = true; j.active = false;
                    j.state = "解析失败: " + e.getClass().getSimpleName();
                    M3U8_TOKENS.remove(j.id);
                }
            }
        }).start();
    }

    private static void cleanupM3u8(DlJob j) {
        try {
            java.io.File tmp = new java.io.File(j.file.getAbsolutePath() + ".parts");
            if (tmp.isDirectory()) { java.io.File[] fs = tmp.listFiles(); if (fs != null) for (java.io.File f : fs) f.delete(); }
            tmp.delete();
        } catch (Throwable ignored) {}
        j.active = false;
        M3U8_TOKENS.remove(j.id);
    }

    private static void launchSegmentFfmpeg(final DlJob j) {
        j.active = true;
        j.paused = false;
        j.failed = false;
        j.state = "下载中";
        int segN = 0;
        try {
            java.io.File[] fs = j.file.listFiles();
            if (fs != null) for (java.io.File f : fs) if (f.getName().endsWith(".ts")) segN++;
        } catch (Throwable ignored) {}
        String[] args = { "-y", "-i", j.url, "-c", "copy",
            "-f", "segment", "-segment_time", "10", "-reset_timestamps", "1",
            "-segment_start_number", String.valueOf(segN),
            new java.io.File(j.file, "seg%05d.ts").getAbsolutePath() };
        com.arthenica.ffmpegkit.FFmpegSession st = com.arthenica.ffmpegkit.FFmpegKit.executeWithArgumentsAsync(args,
            new com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback() {
                public void apply(com.arthenica.ffmpegkit.FFmpegSession st2) {
                    HLS_SESS.remove(j.id);
                    if (j.active && !j.paused) {
                        // 自己断流：标失败
                        if (!j.done) { j.failed = true; j.active = false; j.state = "下载中断(点开始续传)"; }
                    }
                    refreshBytes(j);
                }
            });
        HLS_SESS.put(j.id, st);
    }

    public static void pauseHls(int id) {
        DlJob j = JOBS.get(id);
        if (j == null || !j.hls) return;
        com.arthenica.ffmpegkit.FFmpegSession st = HLS_SESS.remove(id);
        if (st != null) { try { com.arthenica.ffmpegkit.FFmpegKit.cancel(st.getSessionId()); } catch (Throwable ignored) {} }
        j.paused = true;
        j.active = false;
        j.state = "已暂停";
        refreshBytes(j);
    }

    public static void resumeHls(int id) {
        DlJob j = JOBS.get(id);
        if (j == null || !j.hls) return;
        DlKeepService.ensure(sCtx);
        launchSegmentFfmpeg(j);
    }

    /** 结束：停采集 + 合并成 MP4 入相册/历史，卡片转入"视频" */
    public static void finishHls(final int id) {
        final DlJob j = JOBS.get(id);
        if (j == null || !j.hls) return;
        com.arthenica.ffmpegkit.FFmpegSession st = HLS_SESS.remove(id);
        if (st != null) { try { com.arthenica.ffmpegkit.FFmpegKit.cancel(st.getSessionId()); } catch (Throwable ignored) {} }
        j.state = "合并MP4中…";
        new Thread(new Runnable() { public void run() {
            try {
                java.io.File[] segs = j.file.listFiles();
                java.util.Arrays.sort(segs);
                java.util.ArrayList<java.io.File> list = new java.util.ArrayList<java.io.File>();
                for (java.io.File f : segs) if (f.getName().endsWith(".ts") && f.length() > 0) list.add(f);
                if (list.isEmpty()) { j.failed = true; j.active = false; j.state = "无数据"; return; }
                java.io.File listFile = new java.io.File(j.file, "list.txt");
                java.io.PrintWriter pw = new java.io.PrintWriter(listFile, "UTF-8");
                for (java.io.File f : list) pw.println("file '" + f.getAbsolutePath().replace("'", "'\\''") + "'");
                pw.close();
                java.io.File mp4 = new java.io.File(sCtx.getExternalFilesDir(null), "下载/hls" + j.id + ".mp4");
                com.arthenica.ffmpegkit.FFmpegSession cs = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                    new String[]{"-y", "-f", "concat", "-safe", "0", "-i", listFile.getAbsolutePath(),
                        "-c", "copy", "-fflags", "+genpts", "-movflags", "+faststart", mp4.getAbsolutePath()});
                if (cs.getState().equals(com.arthenica.ffmpegkit.SessionState.COMPLETED) && mp4.length() > 0) {
                    // mp4 入相册
                    try {
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, j.title + ".mp4");
                        cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                        if (android.os.Build.VERSION.SDK_INT >= 29)
                            cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/资源嗅探");
                        android.net.Uri uri = sCtx.getContentResolver().insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                        if (uri != null) {
                            java.io.InputStream in = new java.io.FileInputStream(mp4);
                            java.io.OutputStream os = sCtx.getContentResolver().openOutputStream(uri);
                            byte[] b = new byte[64 * 1024]; int n;
                            while ((n = in.read(b)) > 0) os.write(b, 0, n);
                            os.close(); in.close();
                        }
                    } catch (Throwable ignored) {}
                    HistoryStore.add(sCtx, "视频", j.title, "file://" + mp4.getAbsolutePath());
                    for (java.io.File f : j.file.listFiles()) f.delete();
                    j.file.delete();
                    mp4.delete();  // 相册已有副本
                    j.done = true; j.paused = false; j.active = false;
                    j.doneBytes = mp4.length();
                    j.state = "已完成(视频栏)";
                } else {
                    j.failed = true; j.active = false;
                    j.state = "合并失败(分段保留)";
                }
            } catch (Throwable t) {
                j.failed = true; j.active = false;
                j.state = "合并失败: " + t.getClass().getSimpleName();
            }
        } }).start();
    }

    private static void refreshBytes(DlJob j) {
        try {
            long t = 0;
            java.io.File[] fs = j.file.listFiles();
            if (fs != null) for (java.io.File f : fs) t += f.length();
            j.doneBytes = t;
        } catch (Throwable ignored) {}
    }

    private static void download(final DlJob j) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            java.net.URL u = new java.net.URL(j.url);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (j.url.contains("bilibili") || j.url.contains("bilivideo"))
                c.setRequestProperty("Referer", "https://www.bilibili.com/");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");
            // 断点续传：.part 记录已下字节，Range 续传；服务器不支持206则重下
            final java.io.File part = new java.io.File(j.file.getAbsolutePath() + ".part");
            long have = 0;
            if (part.exists() && part.length() > 0) {
                have = part.length();
                c.setRequestProperty("Range", "bytes=" + have + "-");
            }
            int code = c.getResponseCode();
            boolean resumed = have > 0 && code == 206;
            if (have > 0 && !resumed) { part.delete(); have = 0; }
            j.doneBytes = have;
            j.total = resumed ? have + Math.max(0, c.getContentLength()) : c.getContentLength();
            in = c.getInputStream();
            out = new java.io.FileOutputStream(part, resumed);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (!j.active) return;   // 暂停/中断保留 .part，下次续传
                out.write(buf, 0, n);
                j.doneBytes += n;
                j.state = "下载中" + (resumed ? "（续传）" : "");
            }
            out.close(); out = null;
            if (!part.renameTo(j.file)) {
                java.io.InputStream pin = new java.io.FileInputStream(part);
                java.io.OutputStream po = new java.io.FileOutputStream(j.file);
                byte[] pb = new byte[64 * 1024]; int pn;
                while ((pn = pin.read(pb)) > 0) po.write(pb, 0, pn);
                po.close(); pin.close(); part.delete();
            }
            j.state = insertGallery(j) ? "已完成" : "完成(未入相册)";
            HistoryStore.add(sCtx, "视频", j.title, "file://" + j.file.getAbsolutePath());
            j.done = true;
            j.active = false;
        } catch (Throwable t) {
            if (j.active) { j.failed = true; j.active = false; j.state = "下载失败: " + t.getClass().getSimpleName(); }
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean insertGallery(DlJob j) {
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, j.title.endsWith(".ts") ? j.title : j.title + ".ts");
            cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp2t");
            if (android.os.Build.VERSION.SDK_INT >= 29)
                cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/资源嗅探");
            android.net.Uri uri = sCtx.getContentResolver().insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return false;
            java.io.InputStream in = new java.io.FileInputStream(j.file);
            java.io.OutputStream os = sCtx.getContentResolver().openOutputStream(uri);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close(); in.close();
            return true;
        } catch (Throwable t) { return false; }
    }

    public static void cancel(int id) {
        DlJob j = JOBS.get(id);
        if (j == null) return;
        j.active = false;
        JOBS.remove(id);
        com.arthenica.ffmpegkit.FFmpegSession hs = HLS_SESS.remove(id);
        if (hs != null) { try { com.arthenica.ffmpegkit.FFmpegKit.cancel(hs.getSessionId()); } catch (Throwable ignored) {} }
        M3u8Util.CancelToken mt = M3U8_TOKENS.remove(id);
        if (mt != null) mt.cancelled = true;   // 中止 M3U8Manger 方式的并行下载
        try { if (j.file != null) {
            if (j.file.isDirectory()) { for (java.io.File f : j.file.listFiles()) f.delete(); }
            j.file.delete();
            new java.io.File(j.file.getAbsolutePath() + ".part").delete();
        } } catch (Throwable ignored) {}
    }
}
