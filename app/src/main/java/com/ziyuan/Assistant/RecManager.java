package com.ziyuan.Assistant;

import android.content.Context;
import android.content.Intent;

public class RecManager {

    // ---------- 直播录制（纯 Java 拉流写文件，支持多路并行） ----------
    static class RecJob {
        int id;
        volatile boolean active = true;
        volatile boolean paused = false;
        volatile java.net.HttpURLConnection conn;
        Thread thread;
        String name;
        String url;
        java.io.File file;
        android.net.Uri storeUri;
        volatile long bytes = 0;
        volatile long bps = 0;          // 实时写入速率(字节/秒), 看门狗每次采样更新
        volatile long baseBytes = 0;    // 本次 ffmpeg 会话开始前已落盘量(用于 statistics 累加基准)
        volatile long mergeTotal = 0;   // 合并: 输入总大小(仅作 getTime 不可用时的兜底分母)
        volatile long mergeDurMs = 0;   // 合并: 录制总时长(ms), 进度主分母
        volatile int mergePct = 0;      // 合并进度 0-100, 由 ffmpeg 输出时间推进
        volatile long statTs = 0;       // 速率窗口起始时间戳
        volatile long rateBytes = 0;    // 速率窗口起始字节数
        volatile boolean finishNow = false;
        volatile String state = null;   // null=暂停录制 / 转换MP4中 / 转换失败
        volatile boolean savedToHistory = false;  // 已写入历史记录（下载页去重用）
        int notifId;
        volatile long secs = 0;
        volatile long startTs = 0;
        tv.danmaku.ijk.media.player.IjkMediaPlayer bgPlayer;
        com.arthenica.ffmpegkit.FFmpegSession bgSession;
        int bgGen = 0;
        volatile boolean bgCancel = false;
    }
    public static final java.util.concurrent.ConcurrentHashMap<Integer, RecJob> recJobs =
        new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<Integer, RecJob> stoppedJobs =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int recSeq = 0;
    private static volatile com.arthenica.ffmpegkit.FFmpegSession ffkSession = null;
    private static android.os.PowerManager.WakeLock recWake = null;
    private static Context sCtx;
    public static void init(Context appCtx) {
        if (sCtx == null) sCtx = appCtx;
        // ffmpeg-kit 默认把 statistics 回调节流到约 1s 一次, 这里压到 250ms, 大小才会跟手
        // (反射调用: 不同 ffmpeg-kit 版本该方法可能不存在, 缺失时保持默认)
        try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig")
                .getMethod("setStatisticsCallbackInterval", int.class).invoke(null, 250);
        } catch (Throwable ignored) {}
        // 进程被杀(手动/系统)后重启: 遗留录制任务自动转为暂停, 文件保留可续录
        try {
            android.content.SharedPreferences sp = sCtx.getSharedPreferences("rec_live", 0);
            java.util.Set<String> paths = sp.getStringSet("jobs_set", null);
            if (paths != null && !paths.isEmpty()) {
                for (String path : paths) {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(sp.getString("job:" + path, "{}"));
                        if (!o.has("file")) continue;
                        RecJob j = new RecJob();
                        j.id = ++recSeq;
                        j.notifId = 9000 + j.id;
                        j.url = o.optString("url");
                        j.name = o.optString("name");
                        j.file = new java.io.File(o.optString("file"));
                        j.active = false;
                        j.paused = true;
                        j.state = null;   // 显示"暂停录制", 可继续/结束
                        // 补算已落盘大小, 避免显示为空
                        try {
                            long b = 0;
                            if (j.file.isDirectory()) for (java.io.File f : j.file.listFiles()) if (f.getName().startsWith("seg")) b += f.length();
                            j.bytes = b;
                        } catch (Throwable ignored) {}
                        stoppedJobs.put(j.id, j);
                    } catch (Throwable ignored) {}
                }
                sp.edit().remove("jobs").apply();
            }
        } catch (Throwable ignored) {}
    }

    /** 登记进行中的录制(用于进程被杀后自动转暂停) */
    static void saveLiveRec(final RecJob job) {
        try {
            if (job == null || job.file == null || !job.file.isDirectory()) return;
            android.content.SharedPreferences sp = sCtx.getSharedPreferences("rec_live", 0);
            java.util.Set<String> set = new java.util.HashSet<String>(sp.getStringSet("jobs_set", new java.util.HashSet<String>()));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("url", job.url == null ? "" : job.url);
            o.put("name", job.name == null ? "" : job.name);
            o.put("file", job.file.getAbsolutePath());
            set.add(job.file.getAbsolutePath());
            java.util.Map<String, String> map = new java.util.HashMap<String, String>();
            map.put(job.file.getAbsolutePath(), o.toString());
            android.content.SharedPreferences.Editor ed = sp.edit();
            for (String k : sp.getAll().keySet()) if (k.startsWith("job:")) ed.remove(k);
            ed.putString("job:" + job.file.getAbsolutePath(), o.toString());
            ed.putStringSet("jobs_set", set);
            ed.apply();
        } catch (Throwable ignored) {}
    }

    static void removeLiveRec(final RecJob job) {
        try {
            if (job == null || job.file == null) return;
            android.content.SharedPreferences sp = sCtx.getSharedPreferences("rec_live", 0);
            sp.edit().remove("job:" + job.file.getAbsolutePath()).apply();
        } catch (Throwable ignored) {}
    }
    public static volatile String lastStreamUrl = null;

    private static void acquireWake() {
        // 仅在有活跃下载任务时启动下载前台服务，避免录制时多出一条重复通知
        try {
            for (DlManager.DlJob dj : DlManager.jobs()) {
                if (dj != null && !dj.done && !dj.failed && dj.active) { DownloadService.start(sCtx); break; }
            }
        } catch (Throwable ignored) {}
        // 录制通知栏/状态栏前台服务（服务内空闲自停）
        try {
            android.content.Intent ni = new android.content.Intent(sCtx, RecKeepService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) sCtx.startForegroundService(ni); else sCtx.startService(ni);
        } catch (Throwable ignored) {}
        try {
            if (recWake == null && sCtx != null) {
                android.os.PowerManager pm = (android.os.PowerManager) sCtx.getSystemService(Context.POWER_SERVICE);
                recWake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pillmate:rec");
                recWake.acquire(4 * 3600 * 1000L);
            }
        } catch (Throwable ignored) {}
    }

    private static void releaseWakeIfIdle() {
        try {
            if (recJobs.isEmpty() && recWake != null) { recWake.release(); recWake = null; }
        } catch (Throwable ignored) {}
    }

    static void startRecJob(final String url) { startRecJob(url, null); }

    /** title: 页面标题/分享文案，作为录制名 */
    static void startRecJob(final String url, final String title) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("\\") || u.endsWith("\"") || u.endsWith("'") || u.endsWith(",")) u = u.substring(0, u.length() - 1).trim();
        if (u.isEmpty() || !u.startsWith("http")) return;
        String t = cleanTitle(title);
        if (t == null) t = nameFromUrl(u);
        if (t == null) t = "直播·原画_" + System.currentTimeMillis() / 1000;
        // 同一路流去重(忽略画质后缀 _sd/_hd/_or4 与 URL 签名参数)：
        // 抖音录制时会把 _hd 探测改写成 _or4 原画地址，若按精确 URL 比较，
        // 第二次点「直播录制」会失效再建一条 → 列表出现「录制中 + 暂停录制」两条。
        String key = normKey(u);
        for (RecJob j : recJobs.values()) if (j.url != null && normKey(j.url).equals(key)) return;
        for (RecJob j : stoppedJobs.values()) if (j.url != null && normKey(j.url).equals(key) && j.file != null) { recContinue(j.id); return; }
        startBgRec(u, t);
    }

    /** 归一化流地址作为去重键：去掉 ? 后的签名参数，把画质档位标记统一成 _q */
    private static String normKey(String u) {
        if (u == null) return "";
        String k = u.split("\\?")[0];
        k = k.replaceAll("_(sd|ld|hd|uhd|ocs|or4)(?=[/._])", "_q");
        return k;
    }


    private static void startPull(final RecJob job, final boolean append) {
        job.active = true;
        job.paused = false;
        job.startTs = System.currentTimeMillis();
        recJobs.put(job.id, job);
        stoppedJobs.remove(job.id);
        acquireWake();
        job.thread = new Thread(new Runnable() {
            public void run() {
                java.io.FileOutputStream fo = null;
                boolean ended = false;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(job.url).openConnection();
                    job.conn = c;
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    String lu = job.url.toLowerCase();
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                    c.setRequestProperty("Referer", lu.contains("bilibili") ? "https://www.bilibili.com/" : "https://live.douyin.com/");
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    java.io.InputStream in = c.getInputStream();
                    java.io.OutputStream os = sCtx.getContentResolver().openOutputStream(job.storeUri, append ? "wa" : "w");
                    fo = (os instanceof java.io.FileOutputStream) ? (java.io.FileOutputStream) os : null;
                    java.io.OutputStream out = fo != null ? fo : os;
                    byte[] b = new byte[32768]; int n;
                    while ((n = in.read(b)) > 0 && job.active && !job.paused) { out.write(b, 0, n); job.bytes += n; }
                    try { out.close(); } catch (Exception ignored) {} fo = null;
                    try { in.close(); } catch (Exception ignored) {}
                    try { c.disconnect(); } catch (Throwable ignored) {}
                    ended = !job.paused;
                } catch (Throwable t) {
                } finally {
                    try { if (fo != null) fo.close(); } catch (Exception ignored) {}
                    try { if (job.conn != null) job.conn.disconnect(); } catch (Throwable ignored) {}
                    job.conn = null;
                    if (job.startTs > 0) job.secs += (System.currentTimeMillis() - job.startTs) / 1000;
                    job.startTs = 0;
                    job.active = false;
                    recJobs.remove(job.id);
                    releaseWakeIfIdle();
                    try {   // 收尾：解除 pending，让系统文件管理器可见
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                        sCtx.getContentResolver().update(job.storeUri, cv, null, null);
                    } catch (Throwable ignored) {}
                    if (job.finishNow) {
                        stoppedJobs.put(job.id, job);
                        convertToMp4(job);
                    } else {
                        job.state = null;
                        stoppedJobs.put(job.id, job);   // 暂停/断流：可继续
                    }
                }
            }
        });
        job.thread.start();
    }

    /** 停止（暂停）录制：断流、通知取消，文件保留可继续 */
    /** m3u8：ffmpeg-kit 直接拉 HLS 录成 MP4（-c copy 边下边封装） */

    /** m3u8：纯 Java 解析播放清单，循环抓 .ts 分片合并（小工具 KB 同款请求头，容错重试） */
    static void startHlsRec(final String url) {
        try {
            final RecJob job = new RecJob();
            job.id = ++recSeq;
            job.notifId = 9000 + job.id;
            job.url = url;
            job.name = "直播·原画_" + System.currentTimeMillis() / 1000 + ".ts";
            job.startTs = System.currentTimeMillis();
            java.io.File dir = new java.io.File(sCtx.getExternalFilesDir(null), "录制");
            dir.mkdirs();
            job.file = new java.io.File(dir, job.name);
            recJobs.put(job.id, job);
            acquireWake();
            job.thread = new Thread(new Runnable() {
                public void run() {
                    java.io.FileOutputStream fo = null;
                    try {
                        // 通知本地代理：录制开始喂数据，播放同一路流时可走内存缓存秒进
                        LiveProxy.beginCacheFeed(job.url);
                        fo = new java.io.FileOutputStream(job.file, true);
                        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                        byte[] buf = new byte[65536];
                        String curUrl = job.url;
                        int failStreak = 0;
                        while (job.active && failStreak < 30) {
                            // ---- 拉清单（独立容错）----
                            java.util.List<String> segs = new java.util.ArrayList<String>();
                            java.util.List<String> variants = new java.util.ArrayList<String>();
                            boolean ok = false;
                            java.net.HttpURLConnection pc = null;
                            try {
                                pc = (java.net.HttpURLConnection) new java.net.URL(curUrl).openConnection();
                                pc.setConnectTimeout(8000); pc.setReadTimeout(8000);
                                pc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                                pc.setRequestProperty("Referer", "https://live.douyin.com/");
                                int rc = pc.getResponseCode();
                                if (rc == 200) {
                                    ok = true;
                                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pc.getInputStream()));
                                    String ln, lastInf = null;
                                    boolean inVariant = false;
                                    while ((ln = br.readLine()) != null) {
                                        ln = ln.trim();
                                        if (ln.startsWith("#EXT-X-STREAM-INF")) { inVariant = true; continue; }
                                        if (inVariant && !ln.isEmpty() && !ln.startsWith("#")) {
                                            String v = ln;
                                            if (!v.startsWith("http")) v = new java.net.URL(new java.net.URL(curUrl), v).toString();
                                            variants.add(v); inVariant = false; continue;
                                        }
                                        if (ln.startsWith("#EXTINF")) lastInf = ln;
                                        else if (!ln.isEmpty() && !ln.startsWith("#") && lastInf != null) {
                                            String seg = ln;
                                            if (!seg.startsWith("http")) seg = new java.net.URL(new java.net.URL(curUrl), seg).toString();
                                            segs.add(seg); lastInf = null;
                                        }
                                    }
                                    br.close();
                                }
                            } catch (Throwable pe) {
                            } finally {
                                if (pc != null) { try { pc.disconnect(); } catch (Throwable ignored) {} }
                            }
                            // master 清单：只认 _or4 原画; 没有则快速刷新清单等待原画出现
                            if (!variants.isEmpty() && segs.isEmpty()) {
                                String pick = null;
                                for (String v : variants) if (v.contains("_or4")) { pick = v; break; }
                                if (pick == null) {
                                    job.state = "兜底画质, 等待原画…";
                                    if (job.finishNow) break;
                                    // 500ms 快速刷新：原画变体一出现立即抓片，缩短缓存预热期
                                    Thread.sleep(500);
                                    continue;
                                }
                                curUrl = pick; failStreak = 0; job.state = null; continue;
                            }
                            if (!ok) { failStreak++; job.state = "清单失败x" + failStreak + " (HTTP" + pc.getResponseCode() + ")"; Thread.sleep(1000); continue; }
                            // ---- 逐分片下载（独立容错）----
                            for (String seg : segs) {
                                if (!job.active) break;
                                if (!seen.add(seg)) continue;
                                java.net.HttpURLConnection sc = null;
                                try {
                                    sc = (java.net.HttpURLConnection) new java.net.URL(seg).openConnection();
                                    sc.setConnectTimeout(8000); sc.setReadTimeout(15000);
                                    sc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                                    sc.setRequestProperty("Referer", "https://live.douyin.com/");
                                    if (sc.getResponseCode() == 200 && fo != null) {
                                        java.io.InputStream in = sc.getInputStream();
                                        java.io.ByteArrayOutputStream cab = new java.io.ByteArrayOutputStream();
                                        int n;
                                        while (job.active && (n = in.read(buf)) > 0) { fo.write(buf, 0, n); job.bytes += n; cab.write(buf, 0, n); }
                                        fo.flush();
                                        in.close();
                                        failStreak = 0;
                                        // 完整分片进内存缓存：录制中播放同一路流直接命中（秒进）
                                        LiveProxy.noteSegment(seg, cab.toByteArray());
                                    } else failStreak++;
                                } catch (Throwable se) {
                                    failStreak++;
                                    job.state = "分片失败x" + failStreak + " (" + se.getClass().getSimpleName() + ")";
                                } finally {
                                    if (sc != null) { try { sc.disconnect(); } catch (Throwable ignored) {} }
                                }
                            }
                            if (job.finishNow) break;
                            // 500ms 轮询：抓片速度必须快于直播出片速度(2s/片)，否则录制断口、播放缓存断供
                            Thread.sleep(500);
                        }
                    } catch (Throwable t) {
                        job.state = "中断: " + t.getClass().getSimpleName();
                    } finally {
                        LiveProxy.endCacheFeed();   // 录制结束，释放分片缓存
                        try { if (fo != null) fo.close(); } catch (Exception ignored) {}
                        if (job.startTs > 0) job.secs += (System.currentTimeMillis() - job.startTs) / 1000;
                        job.startTs = 0;
                        job.active = false;
                        recJobs.remove(job.id);
                        releaseWakeIfIdle();
                        if (job.finishNow && job.bytes > 0) convertFileToMp4(job);
                        else stoppedJobs.put(job.id, job);
                    }
                }
            });
            job.thread.start();
        } catch (Throwable e) {
            try {
                RecJob jb = new RecJob();
                jb.id = ++recSeq;
                jb.name = "直播·原画（失败）";
                jb.state = "启动失败: " + e.getClass().getSimpleName();
                stoppedJobs.put(jb.id, jb);
            } catch (Throwable ignored) {}
        }
    }

    static void startFfmpegRec(final String url) {
        try {
            RecJob job = new RecJob();
            job.id = ++recSeq;
            job.notifId = 9000 + job.id;
            job.url = url;
            job.name = "直播·原画";
            job.startTs = System.currentTimeMillis();
            java.io.File out = new java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
                "资源嗅探_" + System.currentTimeMillis() + ".mp4");
            job.file = out;
            // MediaStore 登记（IS_PENDING 隐藏，完成转正）
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, out.getName());
            cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/录制");
            cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 1);
            android.net.Uri storeUri = sCtx.getContentResolver()
                .insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            job.storeUri = storeUri;
            recJobs.put(job.id, job);
            acquireWake();
            String args = "-y -user_agent \"" + "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" + "\" -headers \"Referer: https://live.douyin.com/\\r\\n\" -i \"" + url + "\" -c copy -movflags +faststart -f mp4 \"" + out.getAbsolutePath() + "\"";
            ffkSession = com.arthenica.ffmpegkit.FFmpegKit.executeWithArgumentsAsync(
                com.arthenica.ffmpegkit.FFmpegKitConfig.parseArguments(args),
                new com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback() {
                    public void apply(com.arthenica.ffmpegkit.FFmpegSession st) {
                        try {
                            android.content.ContentValues cv2 = new android.content.ContentValues();
                            cv2.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                            sCtx.getContentResolver().update(storeUri, cv2, null, null);
                        } catch (Throwable e) { }
                        recJobs.remove(job.id);
                        job.state = null;
                        stoppedJobs.put(job.id, job);
                        releaseWakeIfIdle();
                        ffkSession = null;
                    }
                });
            acquireWake();
        } catch (Throwable e) {
            ffkSession = null;
        }
    }

    public static void recStop(int jid) {
        RecJob bg = recJobs.get(jid);
        if (bg != null && bg.state != null && bg.state.contains("合并视频")) return; // 合并中不可暂停
        if (bg != null && bg.file != null && bg.file.isDirectory()) { pauseBg(bg, "暂停录制"); return; }
        if (ffkSession != null) {
            com.arthenica.ffmpegkit.FFmpegKit.cancel(ffkSession.getSessionId());
            return;
        }
        RecJob job = recJobs.get(jid);
        if (job == null) return;
        job.paused = true;
        job.active = false;
        try { if (job.conn != null) job.conn.disconnect(); } catch (Throwable ignored) {}
        try { if (job.thread != null) job.thread.interrupt(); } catch (Throwable ignored) {}
    }

    /** 继续：同一路重新拉流，追加写入同一文件 */
    public static void recContinue(int jid) {
        try {
            RecJob job = stoppedJobs.get(jid);
            if (job == null) return;
        if (job.file != null && job.file.isDirectory()) {
            stoppedJobs.remove(job.id);
            job.active = true;
            job.paused = false;
            job.state = null;
            job.startTs = System.currentTimeMillis();  // 续录时长继续走
            recJobs.put(job.id, job);
            saveLiveRec(job);
            acquireWake();
            startBgPlayer(job);
            startBgSession(job);
            startWatchdog(job);  // 续录重启看门狗，大小才会刷新
            return;
        }
            startPull(job, true);
        } catch (Throwable e) {
            try {
                java.io.File d = sCtx.getExternalFilesDir(null);
                java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(d, "网页诊断.txt"), true);
                fw.write("\n==== REC continue err " + new java.util.Date() + " jid=" + jid + " ====" + android.util.Log.getStackTraceString(e) + "\n");
                fw.close();
            } catch (Throwable ignored) {}
        }
    }

    /** 结束录制：合并转封装成 mp4（后台执行），完成后从列表移除 */
    public static void recFinish(int jid) {
        RecJob live = recJobs.get(jid);
        if (live != null && "合并视频".equals(live.state)) return;  // 合并中: 防 apply/看门狗重复触发
        if (live != null && live.file != null && live.file.isDirectory()) {
            stopBgSession(live);
            stopBgPlayer(live);
            live.active = false;
            if (live.startTs > 0) {  // 结束时固化已录时长
                live.secs += (System.currentTimeMillis() - live.startTs) / 1000;
                live.startTs = 0;
            }
            // 留在列表显示"合并视频", 合并完成才移出(对齐参考App)
            live.state = "合并视频";
            mergeSegs(live);
            return;
        }
        if (live != null && ffkSession != null) {
            com.arthenica.ffmpegkit.FFmpegKit.cancel(ffkSession.getSessionId());
            return;
        }
        if (live != null) {
            live.finishNow = true;
            recStop(jid);
            return;
        }
        final RecJob job = stoppedJobs.get(jid);
        if (job == null) return;
        stoppedJobs.remove(job.id);
        if (job.file != null && job.file.isDirectory()) { mergeSegs(job); return; }
        new Thread(new Runnable() { public void run() { convertToMp4(job); } }).start();
    }

    /** ts 文件 → MP4 入相册 Movies/录制 */
    static void convertFileToMp4(final RecJob job) {
        job.state = "合并视频";
        new Thread(new Runnable() { public void run() {
            try {
                String mp4Name = job.name.endsWith(".ts") ? job.name.substring(0, job.name.length() - 3) + ".mp4" : job.name + ".mp4";
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, mp4Name);
                cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/录制");
                android.net.Uri out = sCtx.getContentResolver().insert(
                    android.provider.MediaStore.Video.Media.getContentUri("external_primary"), cv);
                java.io.File tmp = new java.io.File(sCtx.getCacheDir(), "conv_" + System.currentTimeMillis() + ".mp4");
                com.arthenica.ffmpegkit.FFmpegSession st = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                    new String[]{"-y", "-fflags", "+genpts", "-i", job.file.getAbsolutePath(), "-c", "copy", "-movflags", "+faststart", tmp.getAbsolutePath()});
                if (st.getState().equals(com.arthenica.ffmpegkit.SessionState.COMPLETED) && tmp.length() > 0) {
                    java.io.InputStream in = new java.io.FileInputStream(tmp);
                    java.io.OutputStream os = sCtx.getContentResolver().openOutputStream(out);
                    byte[] b = new byte[32768]; int n;
                    while ((n = in.read(b)) > 0) os.write(b, 0, n);
                    in.close(); os.close();
                    job.file.delete();
                    job.state = null;
                    HistoryStore.add(sCtx, "视频", job.name, out.toString());
                    job.savedToHistory = true;
                } else job.state = "转换失败";
                tmp.delete();
            } catch (Throwable e) {
                job.state = "转换失败: " + e.getClass().getSimpleName();
            }
            stoppedJobs.put(job.id, job);
        } }).start();
    }


    // ---------- 后台静音播放器拉流录制：分段落盘 + 无流量自动暂停 ----------
    private static final String BG_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    static String cleanTitle(String t) {
        if (t == null) return null;
        t = t.replace("\r", " ").replace("\n", " ").trim();
        // 整串像 URL / 流地址(剪贴板直接开录)时不当标题, 交由 nameFromUrl 推断
        if (t.startsWith("http://") || t.startsWith("https://") || t.contains("://")
                || t.contains(".com") || t.contains("pull-hls") || t.contains(".tv") || t.contains(".cn")) return null;
        // 分享文案：取第二组 #...# 之后、正在直播之前的主体
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#[^#]+#(.+?)正在直播").matcher(t);
        if (m.find()) t = m.group(1).trim();
        t = t.replace("[", "").replace("]", "").replace("/", "-").replace("\\", "-").replace(":", "-")
             .replace("*", "").replace("?", "").replace("\"", "").replace("<", "").replace(">", "").replace("|", "-");
        return t.length() == 0 ? null : (t.length() > 40 ? t.substring(0, 40) : t);
    }

    /** 剪贴板直接开录没标题时, 从流地址推断可读名: 抖音 pull-hls 取房间标识, 否则取路径段 */
    static String nameFromUrl(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("pull-hls-([a-zA-Z0-9_-]+)").matcher(url);
        if (m.find()) return "直播·" + m.group(1);
        try {
            java.net.URL u = new java.net.URL(url);
            String p = u.getPath();
            if (p != null && p.length() > 1) {
                String seg = p.substring(1).split("/")[0];
                if (seg.length() > 0) return "直播·" + seg;
            }
            String host = u.getHost();
            if (host != null && host.length() > 0) return "直播·" + host;
        } catch (Throwable ignored) {}
        return null;
    }

    static void startBgRec(final String url) { startBgRec(url, null); }

    static void startBgRec(final String url, final String title) {
        final RecJob job = new RecJob();
            job.id = ++recSeq;
            job.notifId = 9000 + job.id;
            job.url = url;
            String ct = cleanTitle(title);
            job.name = (ct != null ? ct : "直播·原画_" + System.currentTimeMillis() / 1000) + ".ts";
            try { SniffActivity.registerLanLink(url, (url != null && url.contains("douyin") ? "抖音·原画" : "直播") + " " + job.name.replace(".ts", "")); } catch (Throwable ignored) {}
            job.startTs = System.currentTimeMillis();
            String title2 = job.name.endsWith(".ts") ? job.name.substring(0, job.name.length() - 3) : job.name;
            final java.io.File dir = new java.io.File(sCtx.getExternalFilesDir(null), "录制合并/" + title);
            dir.mkdirs();
            job.file = dir;
            recJobs.put(job.id, job);
            // 全部后台化: 抖音兜底探测含网络+sleep, 主线程跑会冻死UI(2026-10-07 实测卡住根因)
            new Thread(new Runnable() { public void run() {
            try {
            saveLiveRec(job);
            acquireWake();
            // 抖音: 只录原画——兜底变体(_sd/_hd/_uhd)时, 构造_or4原画地址每2秒探测直到可用
            if (job.url != null && job.url.contains("douyin")) {
                boolean isLow = job.url.matches(".*_(sd|ld|hd|uhd|ocs)(/|\\.).*");
                String or4 = isLow ? job.url.replaceFirst("_(sd|ld|hd|uhd)/", "_or4/") : job.url;
                int waits = 0;
                while (job.active && isLow) {
                    try {
                        java.net.HttpURLConnection pc = (java.net.HttpURLConnection) new java.net.URL(or4).openConnection();
                        pc.setRequestMethod("HEAD");
                        pc.setConnectTimeout(8000); pc.setReadTimeout(8000);
                        pc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                        pc.setRequestProperty("Referer", "https://live.douyin.com/");
                        int rc = pc.getResponseCode();
                        pc.disconnect();
                        if (rc == 200) {
                            job.url = or4;
                            job.state = null;
                            break;
                        }
                        job.state = "兜底画质, 等待原画(2s刷新)…";
                        if (job.finishNow) break;
                        Thread.sleep(2000);
                        waits++;
                        if (waits > 900) break;
                    } catch (Throwable e2) {
                        if (!job.active || job.finishNow) break;
                        try { Thread.sleep(2000); } catch (Throwable ignored) {}
                        continue;
                    }
                }
            }
            startBgPlayer(job);
            startBgSession(job);
            startWatchdog(job);
            } catch (Throwable e) {
                // 启动失败不再往 stoppedJobs 塞空壳任务（否则列表会出现一条
                // 无 URL、无数据的「假录制」，与真正那条并列让人分不清）。
                // 仅把失败原因写到诊断文件，真正的 job 仍在 recJobs 由看门狗处理。
                try {
                    java.io.File d = sCtx.getExternalFilesDir(null);
                    java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(d, "网页诊断.txt"), true);
                    fw.write("\n[录制启动失败] " + e + "\n"); fw.close();
                } catch (Throwable ignored) {}
            }
            } }).start();
    }

    static void startWatchdog(final RecJob job) {
        new Thread(new Runnable() { public void run() {  // 流量看门狗：1s刷大小(与列表刷新同频)，无增长自动重试
            long last = -1;
            int idle = 0, retry = 0;
            long grace = System.currentTimeMillis() + 10000;  // 启动前10s宽限(ffmpeg缓冲/等原画), 不计无流量
            while (job.active) {
                try { Thread.sleep(1000); } catch (Throwable e) { break; }
                if (!job.active) break;
                // 录制大小: 目录求和(分段 .ts 文件). 分段封装下 ffmpeg getSize 恒为0/逐段重置, 目录求和是稳定真实的实时大小
                long d = dirTotal(job);
                if (d >= 0) job.bytes = d;
                long now = System.currentTimeMillis();
                // 速率: 固定 ~1s 窗口算, 避开 getSize 0.5s 上报周期错位导致的翻倍, dv>0 才记
                if (job.statTs == 0) { job.statTs = now; job.rateBytes = job.bytes; }
                else if (now - job.statTs >= 900) {
                    long dv = job.bytes - job.rateBytes;
                    long dt = now - job.statTs;
                    job.bps = dv > 0 ? dv * 1000 / dt : 0;
                    job.statTs = now;
                    job.rateBytes = job.bytes;
                }
                long total = job.bytes;
                if (total == last) {
                    if (now < grace) { last = total; continue; }
                    idle++;
                    if (idle >= 5) {            // 连续5s无增长 → 自动重试抓流
                        idle = 0;
                        retry++;
                        if (retry > 2) {        // 两轮重试都拉不到 → 自动结束并合并MP4
                            job.state = "拉取不到，自动合并";
                            recFinish(job.id);
                            break;
                        }
                        job.state = "无流量，重试抓流 " + retry + "/2…";
                        stopBgSession(job);
                        stopBgPlayer(job);
                        try { Thread.sleep(3000); } catch (Throwable e) { break; }
                        if (!job.active) break;
                        grace = System.currentTimeMillis() + 10000;   // 重启后重新宽限
                        job.state = null;
                        startBgPlayer(job);
                        startBgSession(job);
                    }
                } else { idle = 0; retry = 0; }
                last = total;
            }
        } }).start();
    }

    static long dirTotal(RecJob job) {
        try {
            long t = 0;
            java.io.File[] fs = job.file.listFiles();
            if (fs == null) return -1;
            for (java.io.File f : fs)           // 只统计分段 .ts, 避免把残留的 out.mp4/list.txt 也算进大小
                if (f.getName().startsWith("seg") && f.length() > 0) t += f.length();
            return t;
        } catch (Throwable e) { return -1; }
    }


    static void dump(String st) {
        try {
            java.io.File dir = sCtx.getExternalFilesDir(null).getParentFile();
            java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(dir, "fc2_debug.txt"), true);
            fw.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                    + " REC: " + st + "\n----------------\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    static void startBgSession(final RecJob job) {
        // 旧地址过期(403)时热替换为同一路直播的最新地址
        try { String f = Or4Helper.freshFor(job.url); if (f != null) job.url = f; } catch (Throwable ignored) {}
        final int gen = ++job.bgGen;
        job.bgCancel = false;
        // 续录：分段号接着已有 seg 继续编，避免覆盖旧分段
        int segN = 0;
        try {
            java.io.File[] fs = job.file.listFiles();
            if (fs != null) for (java.io.File f : fs) if (f.getName().startsWith("seg")) segN++;
        } catch (Throwable ignored) {}
        job.statTs = 0;          // 速率窗口由看门狗接管; 大小走目录求和, 不依赖 ffmpeg getSize
        job.rateBytes = 0;
        String[] args = { "-y", "-stats_period", "0.5",   // 显式指定统计周期, 保证大小约每 0.5s 上报一次
            "-user_agent", BG_UA,
            "-headers", "Referer: https://live.douyin.com/\r\n",
            "-i", job.url, "-c", "copy",
            "-flush_packets", "1",   // 每包立即写盘: 文件大小实时增长, 而不是攒够缓冲才落
            "-f", "segment", "-segment_time", "30", "-reset_timestamps", "1",
            "-segment_start_number", String.valueOf(segN),
            new java.io.File(job.file, "seg%03d.ts").getAbsolutePath() };
        job.bgSession = com.arthenica.ffmpegkit.FFmpegKit.executeWithArgumentsAsync(args,
            new com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback() {
                public void apply(com.arthenica.ffmpegkit.FFmpegSession st) {
                    try {
                        String logs = st.getAllLogsAsString();
                        dump("BG sess rc=" + st.getReturnCode() + " state=" + st.getState()
                            + " tail=" + (logs.length() > 600 ? logs.substring(logs.length() - 600) : logs));
                        if (st.getReturnCode() != null && st.getReturnCode().getValue() != 0) {
                            // 失败原因直接亮到任务状态上：优先挑含关键错误信息的日志行，
                            // 否则被 cancel 提示("received cancel request")盖住，看不到真因
                            String[] ls = logs.trim().split("\n");
                            String err = "";
                            for (String line : ls) {
                                String l = line.trim();
                                if (l.matches("(?i).*(error|failed|invalid|forbidden|not found|403|404|429|timed out|connection|refused|protocol|unable to open).*")
                                    && !l.contains("cancel request")) { err = l; break; }
                            }
                            if (err.length() == 0 && ls.length > 0) err = ls[ls.length - 1].trim();
                            if (err.length() > 110) err = err.substring(0, 110);
                            job.state = "拉流失败自动重连 · " + err;
                        }
                    } catch (Throwable ignored) {}
                    if (gen != job.bgGen || job.bgCancel) return;  // 旧会话/已暂停：直接作废
                    if (job.active) {
                        // 流已断开：用嗅探页面登记的新鲜地址续录，最多尝试 2 次。
                        // 每次最多等约 6s 查新鲜地址；2 次都抓不到 → 把已录片段合并成视频并结束。
                        boolean resumed = false;
                        for (int attempt = 1; attempt <= 2 && job.active && gen == job.bgGen && !job.bgCancel; attempt++) {
                            String nu = null;
                            for (int i = 0; i < 3 && job.active && gen == job.bgGen && !job.bgCancel; i++) {
                                nu = Or4Helper.freshFor(job.url);
                                if (nu != null) break;
                                try { job.state = "流已断开，重连 " + attempt + "/2…"; } catch (Throwable ignored) {}
                                try { Thread.sleep(2000L); } catch (Throwable e) { return; }
                            }
                            if (nu != null) {
                                job.url = nu;
                                job.state = null;
                                startBgSession(job);   // 用新鲜地址续录
                                resumed = true;
                                break;
                            }
                        }
                        if (resumed || !job.active || gen != job.bgGen || job.bgCancel) return;
                        // 2 次重连都抓不到新流：已录片段合并成视频（out.mp4），不再无限等待
                        if (dirTotal(job) > 0) {
                            job.state = "重试2次未抓到，合并已录片段";
                            recFinish(job.id);
                        } else {
                            pauseBg(job, "重试2次未抓到新流地址，且无已录数据（可回直播间后点继续）");
                        }
                    }
                }
            },
            null,   // log 回调
            null);  // 录制阶段大小走目录求和(看门狗1s采样): ffmpeg getSize 对分段封装返回0, 不可靠
    }

    static void startBgPlayer(final RecJob job) {
        // 旧地址过期(403)时热替换为同一路直播的最新地址
        try { String f = Or4Helper.freshFor(job.url); if (f != null) job.url = f; } catch (Throwable ignored) {}
        stopBgPlayer(job);
        try {
            tv.danmaku.ijk.media.player.IjkMediaPlayer m = new tv.danmaku.ijk.media.player.IjkMediaPlayer();
            java.util.Map<String, String> h = new java.util.HashMap<String, String>();
            h.put("Referer", "https://live.douyin.com/");
            h.put("User-Agent", BG_UA);
            m.setDataSource(job.url, h);
            m.setVolume(0f, 0f);
            m.prepareAsync();
            m.start();
            job.bgPlayer = m;
        } catch (Throwable e) { stopBgPlayer(job); }
    }

    static void stopBgPlayer(RecJob job) {
        try { if (job.bgPlayer != null) job.bgPlayer.stop(); } catch (Throwable ignored) {}
        try { if (job.bgPlayer != null) job.bgPlayer.release(); } catch (Throwable ignored) {}
        job.bgPlayer = null;
    }

    static void stopBgSession(RecJob job) {
        job.bgCancel = true;
        try { if (job.bgSession != null) com.arthenica.ffmpegkit.FFmpegKit.cancel(job.bgSession.getSessionId()); } catch (Throwable ignored) {}
        job.bgSession = null;
    }

    static void pauseBg(RecJob job, String why) {
        stopBgSession(job);
        stopBgPlayer(job);
        job.active = false;
        job.paused = true;
        if (job.startTs > 0) {
            job.secs += (System.currentTimeMillis() - job.startTs) / 1000;
            job.startTs = 0;
        }
        job.state = why;
        recJobs.remove(job.id);
        stoppedJobs.put(job.id, job);
        releaseWakeIfIdle();
    }

    static void mergeSegs(final RecJob job) {
        job.state = "合并视频";
        new Thread(new Runnable() { public void run() {
            try {
                java.io.File[] segs = job.file.listFiles();
                java.util.Arrays.sort(segs);
                java.util.List<java.io.File> list = new java.util.ArrayList<java.io.File>();
                for (java.io.File f : segs) if (f.getName().startsWith("seg") && f.length() > 0) list.add(f);
                if (list.isEmpty()) { job.state = "无数据"; stoppedJobs.put(job.id, job); return; }
                java.io.File listFile = new java.io.File(job.file, "list.txt");
                java.io.FileWriter fw = new java.io.FileWriter(listFile);
                for (java.io.File f : list) fw.write("file '" + f.getAbsolutePath() + "'\n");
                fw.close();
                final java.io.File mp4 = new java.io.File(job.file, "out.mp4");
                long totalIn = 1;
                for (java.io.File f : list) totalIn += f.length();
                job.mergeTotal = totalIn;                       // 兜底分母(输入总大小)
                job.mergeDurMs = Math.max(1, job.secs) * 1000;  // 主分母: 录制总时长(ms), 输出时间向它推进
                job.mergePct = 0;
                job.bytes = 0;                                  // 合并阶段: 已生成 mp4 字节数, 从 0 涨到最终
                com.arthenica.ffmpegkit.FFmpegSession st = com.arthenica.ffmpegkit.FFmpegKit.executeWithArgumentsAsync(
                    new String[]{"-y", "-stats_period", "0.5", "-f", "concat", "-safe", "0", "-i", listFile.getAbsolutePath(),
                        "-c", "copy", "-fflags", "+genpts", mp4.getAbsolutePath()},
                    new com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback() {
                        public void apply(com.arthenica.ffmpegkit.FFmpegSession st2) { }
                    }, new com.arthenica.ffmpegkit.LogCallback() {
                        public void apply(com.arthenica.ffmpegkit.Log m) { }
                    }, new com.arthenica.ffmpegkit.StatisticsCallback() {
                        public void apply(com.arthenica.ffmpegkit.Statistics stat) {
                            try {
                                long s = stat.getSize();
                                if (s > 0) job.bytes = s;               // 已生成字节, 从 0 增长
                                long t = (long) stat.getTime();         // 输出时间(ms, double→long): 流拷贝也连续递增 0→总时长
                                int p;
                                if (t > 0 && job.mergeDurMs > 0) p = (int) (t * 100 / job.mergeDurMs);
                                else if (job.mergeTotal > 0) p = (int) (s * 100 / job.mergeTotal);
                                else p = 0;
                                if (p > 99) p = 99;                     // 100 留给真正完成那一刻
                                if (p > job.mergePct) job.mergePct = p;
                            } catch (Throwable ignored) {}
                        }
                    });
                long mergeStart = System.currentTimeMillis();
                while (!st.getState().equals(com.arthenica.ffmpegkit.SessionState.COMPLETED)
                    && !st.getState().equals(com.arthenica.ffmpegkit.SessionState.FAILED)
                    ) {
                    try { job.state = "合并视频"; } catch (Throwable ignored) {}
                    // 时间驱动兜底进度: concat 流拷贝的 ffmpeg 进度上报很稀疏(甚至为0), 靠已用时间从0爬到95%,
                    // 保证用户能看到 0→100 的推进; 若 ffmpeg 真有上报, 统计回调的 mergePct 会更高, 取两者最大
                    long el = System.currentTimeMillis() - mergeStart;
                    int timeP = (int) Math.min(95, el * 95L / 4000);
                    if (timeP > job.mergePct) job.mergePct = timeP;
                    try { Thread.sleep(500); } catch (Throwable e) { }
                }
                if (st.getState().equals(com.arthenica.ffmpegkit.SessionState.COMPLETED) && mp4.length() > 0) {
                    job.mergePct = 100;              // 完成: 定格 100%
                    job.bytes = mp4.length();
                    String mp4Name = job.name.endsWith(".ts") ? job.name.substring(0, job.name.length() - 3) + ".mp4" : job.name + ".mp4";
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, mp4Name);
                    cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/录制");
                    android.net.Uri out = sCtx.getContentResolver().insert(
                        android.provider.MediaStore.Video.Media.getContentUri("external_primary"), cv);
                    java.io.InputStream in = new java.io.FileInputStream(mp4);
                    java.io.OutputStream os = sCtx.getContentResolver().openOutputStream(out);
                    byte[] b = new byte[32768]; int n;
                    while ((n = in.read(b)) > 0) os.write(b, 0, n);
                    in.close(); os.close();
                    // 保留 MP4 在 /Android/data/com.ziyuan.Assistant/files/录制/<文件夹>/
                    for (java.io.File f : job.file.listFiles()) {
                        if (!f.equals(mp4) && !f.equals(listFile)) f.delete();
                    }
                    listFile.delete();
                    mp4.renameTo(new java.io.File(job.file, mp4Name));
                    job.state = "已保存: files/录制合并/" + mp4Name.replace(".mp4", "") + "/" + mp4Name;
                    HistoryStore.add(sCtx, "录制", mp4Name, "file://" + new java.io.File(job.file, mp4Name).getAbsolutePath());
                    job.savedToHistory = true;
                } else job.state = "合并失败";
                recJobs.remove(job.id); removeLiveRec(job); releaseWakeIfIdle(); // 合并完成, 卡片此时才移出
                stoppedJobs.put(job.id, job);
            } catch (Throwable e) {
                job.state = "转换失败: " + e.getClass().getSimpleName();
                stoppedJobs.put(job.id, job);
            }
        } }).start();
    }

    static void convertToMp4(final RecJob job) {
        job.state = "合并视频";
        try {
            String src = com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(sCtx, job.storeUri);
            java.io.File tmp = new java.io.File(sCtx.getCacheDir(), "conv_" + System.currentTimeMillis() + ".mp4");
            com.arthenica.ffmpegkit.FFmpegSession st = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                new String[]{"-y", "-i", src, "-c", "copy", "-movflags", "+faststart", tmp.getAbsolutePath()});
            if (st.getState().equals(com.arthenica.ffmpegkit.SessionState.COMPLETED) && tmp.length() > 0) {
                String name = job.name != null ? job.name : "rec.mp4";
                String mp4Name = (name.endsWith(".flv") ? name.substring(0, name.length() - 4) : name) + ".mp4";
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, mp4Name);
                cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/录制");
                android.net.Uri out = sCtx.getContentResolver().insert(
                    android.provider.MediaStore.Video.Media.getContentUri("external_primary"), cv);
                java.io.InputStream in = new java.io.FileInputStream(tmp);
                java.io.OutputStream os = sCtx.getContentResolver().openOutputStream(out);
                byte[] b = new byte[32768]; int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
                os.close(); in.close();
                try { sCtx.getContentResolver().delete(job.storeUri, null, null); } catch (Throwable ignored) {}
            }
            tmp.delete();
            stoppedJobs.remove(job.id);   // 成功：从列表移除
        } catch (Throwable t) {
            job.state = "转换失败(保留flv)";
        }
    }

    /** 彻底取消：删文件、从列表移除 */
    public static void recCancel(int jid) {
        RecJob j = recJobs.get(jid);
        if (j != null) recStop(jid);
        RecJob job = (j != null) ? j : stoppedJobs.get(jid);
        if (job == null) return;
        stoppedJobs.remove(job.id);
        try { if (job.storeUri != null) sCtx.getContentResolver().delete(job.storeUri, null, null); } catch (Throwable ignored) {}
    }

    public static void recStopAll() {
        for (Integer id : recJobs.keySet().toArray(new Integer[0])) recStop(id);
    }

    // ---------- 供外部媒体管理页(首页下载 tab)实时读取录制任务 ----------

    /** 一条录制任务的快照（供跨包 UI 渲染，避免暴露 RecJob 内部字段） */
    public static final class RecInfo {
        public final int id;
        public final String title;   // 标题（带抖音·原画 前缀）
        public final String secs;    // 录制时长 mm:ss
        public final String size;    // 录制大小（紧凑单位 + 实时速率）
        public final String state;   // 录制中 / 暂停录制 / 合并视频 x% / ...
        public final boolean live;
        public RecInfo(int id, String title, String secs, String size, String state, boolean live) {
            this.id = id; this.title = title; this.secs = secs; this.size = size; this.state = state; this.live = live;
        }
    }

    private static String fmtDur(long s) {
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private static String fmtCompact(long b) {
        if (b >= 1048576) return String.format(java.util.Locale.US, "%.2fM", b / 1048576.0);
        if (b >= 1024) return String.format(java.util.Locale.US, "%.0fK", b / 1024.0);
        return String.valueOf(b);
    }

    private static String qualifyName(RecJob j) {
        String u = (j.url != null ? j.url : "") + " " + (j.name != null ? j.name : "");
        String q = null;
        if (u.contains("_or4")) q = "原画";
        else if (u.contains("_uhd")) q = "蓝光";
        else if (u.contains("_hd")) q = "高清";
        return q == null ? (j.name == null ? "" : j.name) : (u.contains("douyin") ? "抖音·" : "") + q + " " + (j.name == null ? "" : j.name);
    }

    /**
     * 全部录制任务快照：同一路流只保留「一条真任务」。
     * 录制中(recJobs) 优先于 暂停/合并(stoppedJobs)，避免一次点击后出现
     * 「录制中 + 暂停录制」两条并列、分不清哪条在真正录制。
     * 启动失败生成的无 URL 空壳行不显示。
     */
    public static java.util.List<RecInfo> listRecords() {
        java.util.LinkedHashMap<String, RecInfo> byKey = new java.util.LinkedHashMap<String, RecInfo>();
        try {
            // 1) 先放暂停/合并行
            for (final Integer id : stoppedJobs.keySet().toArray(new Integer[0])) {
                final RecJob j = stoppedJobs.get(id);
                if (j == null || recJobs.containsKey(id)) continue;
                if (j.url == null || j.url.isEmpty()) continue;   // 启动失败空壳
                String state = j.state != null ? j.state : "暂停录制";
                if (j.state != null && j.state.startsWith("合并视频")) state = "合并视频 " + j.mergePct + "%";
                String key = keyOf(j.url, id);
                byKey.put(key, new RecInfo(j.id, qualifyName(j), fmtDur(j.secs), fmtCompact(j.bytes), state, false));
            }
            // 2) 录制中覆盖同键的暂停行（真任务优先）
            for (final Integer id : recJobs.keySet().toArray(new Integer[0])) {
                final RecJob j = recJobs.get(id);
                if (j == null) continue;
                long secs = j.secs + (j.startTs > 0 ? (System.currentTimeMillis() - j.startTs) / 1000 : 0);
                // 只显示大小，不显示下载速率/码率（否则「录制大小」折行会挤掉第三行「录制状态」）
                String size = fmtCompact(j.bytes);
                byKey.put(keyOf(j.url, id), new RecInfo(j.id, qualifyName(j), fmtDur(secs), size, "录制中", true));
            }
        } catch (Throwable ignored) { }
        return new java.util.ArrayList<RecInfo>(byKey.values());
    }

    private static String keyOf(String url, int id) {
        if (url == null || url.isEmpty()) return "id:" + id;
        String k = normKey(url);
        return k.isEmpty() ? "id:" + id : "u:" + k;
    }

    /** 该路流是否已有录制任务（供「直播录制/停止录制」菜单判断，避免重复开录） */
    public static boolean isRecording(String url) {
        if (url == null || url.isEmpty()) return false;
        String k = "u:" + normKey(url);
        try {
            for (RecJob j : recJobs.values()) if (j.url != null && keyOf(j.url, j.id).equals(k)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** 已合并完成的录制 → 可播放的本地文件路径(file://)；录制中/未生成返回 null */
    public static String recPlayPath(int jid) {
        try {
            RecJob j = stoppedJobs.get(jid);
            if (j == null || j.file == null || !j.file.isDirectory()) return null;
            String mp4 = (j.name != null && j.name.endsWith(".ts")) ? j.name.substring(0, j.name.length() - 3) + ".mp4" : j.name;
            java.io.File f = new java.io.File(j.file, mp4);
            if (f.exists() && f.length() > 0) return "file://" + f.getAbsolutePath();
            java.io.File out = new java.io.File(j.file, "out.mp4");
            if (out.exists() && out.length() > 0) return "file://" + out.getAbsolutePath();
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * 录制中/已完成的录制 → 可用于抽首帧做缩略图的本地文件(file://)。
     * 优先已合并的 mp4；否则用正在落盘的分段 seg*.ts（-flush_packets 1 边写边刷，
     * 开头就有 PAT/PMT + 关键帧，MediaMetadataRetriever 能直接抽帧）。
     * 固定取编号最小的达标分段，保证 uri 稳定、缩略图缓存只算一次。
     */
    public static String recThumbPath(int jid) {
        try {
            RecJob j = recJobs.get(jid);
            if (j == null) j = stoppedJobs.get(jid);
            if (j == null || j.file == null) return null;
            String mp4 = recPlayPath(jid);
            if (mp4 != null) return mp4;
            java.io.File f = j.file;
            if (f.isFile()) return f.length() > 32768 ? "file://" + f.getAbsolutePath() : null;
            if (!f.isDirectory()) return null;
            java.io.File[] fs = f.listFiles();
            if (fs == null) return null;
            java.io.File best = null;
            for (java.io.File x : fs) {
                String n = x.getName();
                if (!n.startsWith("seg") || !n.endsWith(".ts")) continue;
                if (x.length() <= 32768) continue;   // 太小多半还没写到关键帧
                if (best == null || n.compareTo(best.getName()) < 0) best = x;
            }
            return best == null ? null : "file://" + best.getAbsolutePath();
        } catch (Throwable ignored) { return null; }
    }

    /** 暂停/继续（录制中→暂停，暂停→继续），返回操作后的新状态描述 */
    public static String recToggle(int jid) {
        if (recJobs.containsKey(jid)) { recStop(jid); return "已暂停"; }
        recContinue(jid); return "已继续";
    }

    // ---------- 供 1:1 下载页「读取剪贴板录制 / 复制下载地址 / 打开所在目录」调用 ----------

    /** 从剪贴板链接直接开录（title 由引擎自动生成） */
    public static void startRecFromClip(String url) { startRecJob(url, null); }

    /** 该录制任务的流地址（供「复制下载地址」「播放视频」） */
    public static String recUrl(int jid) {
        try {
            RecJob j = recJobs.get(jid);
            if (j == null) j = stoppedJobs.get(jid);
            return j == null || j.url == null ? "" : j.url;
        } catch (Throwable ignored) { return ""; }
    }

    /** 该录制任务落盘目录（供「打开所在目录」） */
    public static String recDir(int jid) {
        try {
            RecJob j = recJobs.get(jid);
            if (j == null) j = stoppedJobs.get(jid);
            if (j == null || j.file == null) return "";
            return j.file.isDirectory() ? j.file.getAbsolutePath() : j.file.getParent();
        } catch (Throwable ignored) { return ""; }
    }

    /** 当前录制是否进行中（供下载页菜单判断显示「暂停」还是「打开/继续录制」） */
    public static boolean recLive(int jid) {
        try { return recJobs.containsKey(jid) && recJobs.get(jid).active; }
        catch (Throwable ignored) { return false; }
    }
}
