package com.downx.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 下载服务：前台通知 + 断点式进度。
 * 文件落在哪儿由 Store 决定（相册目录 / 应用私有目录）。
 */
public class DlService extends Service {

    public static final String A_DONE = "com.downx.app.DL_DONE";
    public static final String A_FAIL = "com.downx.app.DL_FAIL";
    public static final String A_PROG = "com.downx.app.DL_PROG";
    /** 用户主动取消：残余文件已清理 */
    public static final String A_CANCEL = "com.downx.app.DL_CANCEL";
    /** 暂停状态变化：msg = "1" 已暂停 / "0" 已继续 */
    public static final String A_PAUSE = "com.downx.app.DL_PAUSE";

    private static final String CH = "dl";
    private static final int NID = 1001;

    /** 通知栏两个动作（同一个 Service 收到带 action 的 Intent） */
    private static final String ACT_TOGGLE = "com.downx.app.DL_ACT_TOGGLE";
    private static final String ACT_CANCEL = "com.downx.app.DL_ACT_CANCEL";

    /**
     * 一条下载的运行态。用对象而不是静态布尔，是因为取消/暂停必须只作用于
     * 当前这条 —— 之前用全局开关，撞上两条并发时会把别人的取消状态清掉，
     * 结果临时文件没人删、线程还在偷偷写。
     */
    private static final class Task {
        volatile boolean paused;
        volatile boolean canceled;
        final String name;
        /** 断点续传任务 id，取消时要把它的残留状态一起删掉 */
        String pid = "";

        Task(String name) {
            this.name = name;
        }
    }

    /**
     * 最近启动的那一条（通知栏的暂停/取消按钮只作用于它）。
     * 真正的「当前线程在跑哪一条」看 [CUR] —— 并行时两者不是一回事。
     */
    private static volatile Task task;

    /** 当前线程的任务：并行下载时每个线程各一条，gate() 靠它判断暂停/取消 */
    private static final ThreadLocal<Task> CUR = new ThreadLocal<>();
    private static final List<Task> tasks = Collections.synchronizedList(new ArrayList<Task>());

    /**
     * 在跑的任务数 / 其中图片数。
     *
     * 为什么要并行：同一帖 30 张图串行下 = 30 次完整往返，实测 3 路 2.7x、
     * 6 路 4.8x（tests/speed）。但视频仍然是独占的 —— 它一条就吃 8 条 Range 连接，
     * 再并行只会互相抢带宽、撞 CDN 同 IP 连接数上限。
     */
    /** 图片最多几张同时下：3 路已拿到大部分收益，再多容易触发风控 */
    private static final int MAX_PARALLEL_PHOTO = 3;
    /** 并发名额（视频独占 / 图片 ≤3）：纯逻辑类，主机上可直接压测 */
    private static final Slots SLOTS = new Slots(MAX_PARALLEL_PHOTO);

    private static final Set<HttpURLConnection> conns =
            Collections.synchronizedSet(new LinkedHashSet<HttpURLConnection>());
    /** 正在跑的分片并发下载器：取消时要把它的全部连接一起掐断 */
    private static final Set<ChunkedFetch> chunkeds =
            Collections.synchronizedSet(new LinkedHashSet<ChunkedFetch>());

    private static volatile String lastTitle = "DownX";
    private static volatile String lastText = "";
    private static volatile int lastPct;

    /**
     * 同一帖的多图批量：把 N 张合进同一条通知。
     * 并行之后「第几张」不可靠了（3 路同时在飞、完成顺序是乱的），
     * 所以进度按「已完成几张」算，条只往前走。
     */
    private static volatile int batchIndex;
    private static volatile int batchTotal = 1;
    private static volatile String batchLabel = "";
    private static final AtomicInteger batchDoneN = new AtomicInteger();
    /** 这一批的通知是否已经挂上：整批只在第一张 startForeground 一次，后面全是更新 */
    private static volatile boolean fgUp;

    private static boolean inBatch() {
        return batchTotal > 1;
    }

    /**
     * 批量时的通知：标题固定成整批的名字，正文前面标「第 x/N 张」，
     * 百分比按整批折算 —— 这样进度条只往前走，不会每张都 0%→100% 乱跳。
     */
    private Notification bn(String name, String text, int localPct, boolean ongoing) {
        if (!inBatch()) return build(name, text, localPct, ongoing);
        String t = text == null ? "" : text;
        String label = (batchLabel == null || batchLabel.isEmpty()) ? name : batchLabel;
        // 暂停/取消这两句里带的百分比本来就是整批的，别再折算第二次
        if (t.startsWith("已暂停") || t.startsWith("正在取消")) {
            return build(label, t, localPct, ongoing);
        }
        int local = Math.max(0, Math.min(100, localPct));
        int doneN = Math.min(batchTotal, batchDoneN.get());
        int all = Math.min(100, (doneN * 100 + local) / batchTotal);
        return build(label, "已完成 " + doneN + "/" + batchTotal + " 张 · " + t, all, ongoing);
    }

    /** 已取消：一路往上抛，走到 finally 里把临时文件全删掉 */
    private static final class Canceled extends RuntimeException {
        Canceled() { super("downloading canceled by user"); }
    }

    /** 暂停/取消的检查点：所有下载循环里都要调一次 */
    private void gate() {
        // 并行时不能看静态的 task（那是"最近一条"，可能是别人的），
        // 必须看当前线程自己的那一条
        Task t = CUR.get();
        if (t == null) t = task;
        if (t == null) return;
        if (t.canceled) throw new Canceled();
        if (!t.paused) return;
        while (t.paused && !t.canceled) {
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
                // 打断就继续转一圈，由 canceled 决定走不走
            }
        }
        if (t.canceled) throw new Canceled();
    }

    public static boolean isPaused() {
        synchronized (tasks) {
            for (Task t : tasks) {
                if (t.paused) return true;
            }
        }
        Task t = task;
        return t != null && t.paused;
    }

    /** 有没有正在下的（用来决定回 App 时要不要把浮层重新亮出来） */
    public static boolean isRunning() {
        return SLOTS.active() > 0;
    }

    public static int progress() {
        return lastPct;
    }

    /** 通知栏/浮层上的「暂停-继续」开关 */
    public static void toggle(Context c) {
        Intent i = new Intent(c, DlService.class).setAction(ACT_TOGGLE);
        try {
            c.startService(i);
        } catch (Throwable ignored) { }
    }

    /** 取消下载并删掉下到一半的文件（并行时等于取消整批） */
    public static void cancel(Context c) {
        synchronized (tasks) {
            for (Task t : tasks) {
                t.canceled = true;
                t.paused = false;
            }
        }
        Task t0 = task;
        if (t0 != null) {
            t0.canceled = true;
            t0.paused = false;
        }
        Intent i = new Intent(c, DlService.class).setAction(ACT_CANCEL);
        try {
            c.startService(i);
        } catch (Throwable ignored) { }
        // 连接卡在读上时，先掐掉它，工作线程才会立刻醒来收尾
        killConns();
    }

    /** 掐断当前所有在途连接：每条单连接 + 每个分片并发下载器的 N 条 */
    private static void killConns() {
        synchronized (chunkeds) {
            for (ChunkedFetch cf : chunkeds) {
                try { cf.abort(); } catch (Throwable ignored) { }
            }
        }
        synchronized (conns) {
            for (HttpURLConnection c2 : conns) {
                try { c2.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    public static void start(Context c, String url, String audio, String name, String kind,
                             String referer, boolean visible, String thumb, String page,
                             String sub, String key) {
        start(c, url, audio, name, kind, referer, visible, thumb, page, sub, key,
                null, null, null);
    }

    /**
     * alt：同一条作品的备用直链；altName：用备用流时改用的文件名；
     * pick：HLS 要哪一档画质（如 "720"），不传就挑最高码率。
     */
    public static void start(Context c, String url, String audio, String name, String kind,
                             String referer, boolean visible, String thumb, String page,
                             String sub, String key, String alt, String altName,
                             String pick) {
        start(c, url, audio, name, kind, referer, visible, thumb, page, sub, key,
                alt, altName, pick, null, 0, 1, null);
    }

    /**
     * 带批量信息的启动：同一帖的 N 张图共用一条通知，
     * bIdx 从 0 开始，bTotal<=1 表示不是批量（单条下载就传这个）。
     * fallback：跨协议旧式直链（B 站 dash 被拒时退回 html5 单文件），可为 null。
     */
    public static void start(Context c, String url, String audio, String name, String kind,
                             String referer, boolean visible, String thumb, String page,
                             String sub, String key, String alt, String altName,
                             String pick, String fallback, int bIdx, int bTotal, String bLabel) {
        // 准入不在这里做：名额一旦在「发起时」占住，服务万一没起来就永远收不回来，
        // 之后所有下载都会被「还有一条在下载」挡死。放到 onStartCommand 里，
        // 名额和线程同生共死（finally 必放）。
        Intent i = new Intent(c, DlService.class);
        i.putExtra("url", url);
        i.putExtra("audio", audio);
        i.putExtra("name", name);
        i.putExtra("kind", kind);
        i.putExtra("referer", referer);
        i.putExtra("visible", visible);
        i.putExtra("thumb", thumb);
        i.putExtra("page", page);
        i.putExtra("sub", sub);
        i.putExtra("key", key);
        i.putExtra("alt", alt);
        i.putExtra("altName", altName);
        i.putExtra("pick", pick);
        i.putExtra("fallback", fallback);
        i.putExtra("bIdx", bIdx);
        i.putExtra("bTotal", bTotal);
        i.putExtra("bLabel", bLabel);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            broadcast(c, A_FAIL, "无法启动下载服务：" + t.getMessage());
        }
    }

    /** 放开一个并发名额（提前返回、下载结束都要放，漏一个就再也起不来） */
    private static void releaseSlot(boolean photo) {
        SLOTS.release(photo);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();
        if (ACT_TOGGLE.equals(act) || ACT_CANCEL.equals(act)) {
            Task t = task;
            if (SLOTS.active() == 0) {
                // 已经没有在下的了，清掉残留通知就行
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    nm.cancel(NID);
                } catch (Throwable ignored) { }
                stopSelf();
                return START_NOT_STICKY;
            }
            if (t == null) {
                // 并行时「最近一条」可能刚好下完被清掉，随便取一条在飞的
                synchronized (tasks) {
                    if (!tasks.isEmpty()) t = tasks.get(0);
                }
                if (t == null) return START_NOT_STICKY;
            }
            if (ACT_CANCEL.equals(act)) {
                // 并行时「取消」是取消整批：只标最新那条会把另外两路留在后台继续写
                synchronized (tasks) {
                    for (Task x : tasks) {
                        x.canceled = true;
                        x.paused = false;
                    }
                }
                t.canceled = true;
                t.paused = false;
                killConns();
                broadcast(this, A_PAUSE, "0");
                notify(bn(lastTitle, "正在取消，清理临时文件…", lastPct, true));
            } else {
                final boolean np = !t.paused;
                synchronized (tasks) {
                    for (Task x : tasks) x.paused = np;
                }
                t.paused = np;
                broadcast(this, A_PAUSE, np ? "1" : "0");
                notify(bn(lastTitle,
                        np ? "已暂停 " + lastPct + "% · 点「继续」接着下"
                                : lastText, lastPct, true));
            }
            return START_NOT_STICKY;
        }
        if (intent == null || intent.getStringExtra("url") == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String url = intent.getStringExtra("url");
        final String audio = intent.getStringExtra("audio");
        final String name = intent.getStringExtra("name");
        final String kind = intent.getStringExtra("kind");
        final String referer = intent.getStringExtra("referer");
        final boolean visible = intent.getBooleanExtra("visible", true);
        final String thumb = intent.getStringExtra("thumb");
        final String page = intent.getStringExtra("page");
        final String sub = intent.getStringExtra("sub");
        final String key = intent.getStringExtra("key");
        final String alt = intent.getStringExtra("alt");
        final String altName = intent.getStringExtra("altName");
        final String pick = intent.getStringExtra("pick");
        // 跨协议旧式直链（双保险第三层）：dash 整条链（主/备用节点）都被拒时换它从头下
        final String fallback = intent.getStringExtra("fallback");
        // 批量信息（同一帖的多图）：没带就是单条下载
        batchIndex = Math.max(0, intent.getIntExtra("bIdx", 0));
        batchTotal = Math.max(1, intent.getIntExtra("bTotal", 1));
        batchLabel = intent.getStringExtra("bLabel");
        // 主直链被硬拒（403/404）时才会降级用备用流，文件名也要跟着改，
        // 免得把带水印的网页版存成「原片」
        final String[] nm = {name};

        // 准入：视频独占一条；图片最多 3 条并行（实测 2.7~4.8x）。
        // 放在服务里而不是 start() 里，是为了让名额和线程同生共死 —— finally 必放，
        // 不会出现「服务没起来但名额被占住、之后永远提示还有一条在下载」。
        final boolean isPhoto = "photo".equals(kind);
        // SLOTS.admit 返回「申请前的在跑数」，-1 = 拒。一次调用同时做判断与占位，
        // 不会出现两条同时读到 0 一起过线。
        int before = SLOTS.admit(isPhoto);
        if (before < 0) {
            broadcast(this, A_FAIL, "还有一条正在下载，等它下完或先取消它");
            // startForegroundService 之后必须尽快 startForeground，否则系统按 ANR 处理；
            // 已有其它任务的前台通知时（fgUp）不能去覆盖它，直接退出这一条即可。
            if (!fgUp) {
                Notification n0 = build("DownX", "还有一条正在下载…", 0, false);
                if (Build.VERSION.SDK_INT >= 29)
                    startForeground(NID, n0, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                else startForeground(NID, n0);
                stopForeground(true);
            }
            if (SLOTS.active() == 0) stopSelf();
            return START_NOT_STICKY;
        }
        // 整批（或单条）的开始：之前没人在跑才算新的一批。不能用 bIdx==0 ——
        // 并行时前 3 张都带 bIdx=0，会互相把「已完成几张」清掉。
        if (before == 0) batchDoneN.set(0);
        // 整批只有第一张才 startForeground（那时通知才真正"出现"），
        // 后面每张都只是更新同一条 —— 否则通知会消失再弹，看着像一张一条。
        // 批量中间也不推"准备下载…"，等真实进度上来再更新，少一次文案闪动。
        if (!fgUp) {
            Notification n1 = bn(name, "准备下载…", 0, true);
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NID, n1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NID, n1);
            fgUp = true;
        } else if (!inBatch()) {
            notify(bn(name, "准备下载…", 0, true));
        }

        // 去重：同一个原帖 + 同一清晰度已经下过就跳过，避免重复占空间
        if (Store.isDuplicate(this, key, name)) {
            broadcast(this, A_FAIL, "重复下载：这张之前已经存过了");
            releaseSlot("photo".equals(kind));
            // 并行时还有别的张在跑：不能停服务、不能撤通知，否则它们失去前台保护
            if (SLOTS.active() == 0) {
                stopForeground(false);
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        final Task tk = new Task(name);
        // 续传 id：作品标识 + 这一档画质。同一作品换域名（cn/www）也要认成同一条
        final String pid = Parts.idOf(stableKey(key), pick);
        tk.pid = pid;
        task = tk;
        tasks.add(tk);
        new Thread(() -> {
            // 并行时 gate() 认的是「本线程的任务」，不是静态的 task
            CUR.set(tk);
            HttpURLConnection conn = null;
            try {
                // 不再做断点续传：每次下载都从头来。
                // 先把同任务可能残留的半成品清掉（老版本留下的、
                // 上次下载失败后没清干净的），免得两种字节拼在一个文件里
                Parts.clear(this, pid);
                Parts.State st = new Parts.State();
                st.id = pid;
                st.key = key == null ? "" : key;
                st.pick = pick == null ? "" : pick;
                st.page = page == null ? "" : page;
                st.sub = sub == null ? "" : sub;
                st.name = nm[0];
                st.kind = kind == null ? "video" : kind;
                st.thumb = thumb == null ? "" : thumb;
                st.referer = referer == null ? "" : referer;
                st.alt = alt == null ? "" : alt;
                st.altName = altName == null ? "" : altName;
                st.visible = visible;
                st.ts = System.currentTimeMillis();
                Parts.save(this, st);

                String uri;
                if (audio != null && !audio.isEmpty()) {
                    // YouTube 1080P / B 站 dash：视频轨和音轨分开下，再合成一个 mp4。
                    // 双保险：dash 整条链（主链 + 同签名备用节点）都被拒时，
                    // 退回「旧协议单文件直链」（fallback，html5 自带声音）从头下 ——
                    // 宁可降清晰度，也不让整条链被平台风控锁死。
                    try {
                        uri = twoPassAndMerge(url, audio, nm[0], kind, referer, visible, st);
                    } catch (Throwable first) {
                        if (tk.canceled) throw first;
                        if (!rejected(first) || fallback == null || fallback.isEmpty()
                                || fallback.equals(url)) {
                            throw first;
                        }
                        android.util.Log.w("DownX", "dash 被拒，退回旧协议单文件：" + msg(first));
                        resetParts(st);
                        HttpURLConnection[] holder = new HttpURLConnection[1];
                        uri = one(fallback, referer, nm[0], kind, visible, holder, st);
                        conn = holder[0];
                    }
                } else {
                    HttpURLConnection[] holder = new HttpURLConnection[1];
                    try {
                        uri = isHls(url)
                                ? hls(url, referer, nm[0], kind, visible, pick, holder, st)
                                : one(url, referer, nm[0], kind, visible, holder, st);
                    } catch (Throwable first) {
                        if (tk.canceled) throw first;   // 用户取消的不算「被拒」，别去换备用流
                        // 只有「服务器明确拒绝」才换备用流；断流/超时这类是网络抖动，
                        // 已经在 one() 里续传过了，换流只会拿到带水印的低清版
                        if (!rejected(first)) throw first;
                        // 兜底链：① 同签名备用节点（文件名不变）
                        //          ② 跨协议旧式直链 fallback（音轨丢弃，单文件自带声音）
                        //          ③ 都没有就抛出去，上层自愈会重新解析
                        String next = null;
                        boolean crossProto = false;
                        if (alt != null && !alt.isEmpty() && !alt.equals(url)) {
                            next = alt;
                        } else if (fallback != null && !fallback.isEmpty()
                                && !fallback.equals(url)) {
                            next = fallback;
                            crossProto = true;
                        } else {
                            throw first;
                        }
                        android.util.Log.w("DownX", "主直链被拒，改用"
                                + (crossProto ? "旧协议直链" : "备用流") + "：" + msg(first));
                        nm[0] = (crossProto || altName == null || altName.isEmpty())
                                ? nm[0] : altName;
                        st.name = nm[0];
                        // 换了流就不能拿上一股流的位图续传，否则会把两边的字节拼在一起
                        st.cs = 0;
                        st.ranges = "";
                        st.lenV = 0;
                        st.totalV = -1;
                        Parts.save(this, st);
                        uri = isHls(next)
                                ? hls(next, referer, nm[0], kind, visible, pick, holder, st)
                                : one(next, referer, nm[0], kind, visible, holder, st);
                    }
                    conn = holder[0];
                }

                final String fname = nm[0];
                HistoryStore.Entry e = new HistoryStore.Entry();
                e.name = fname;
                e.sub = sub == null ? "" : sub;
                e.pageUrl = page == null ? "" : page;
                e.thumb = thumb == null ? "" : thumb;
                e.kind = kind;
                e.uri = uri == null ? "" : uri;
                e.key = key == null ? "" : key;
                e.visible = visible;
                e.ts = System.currentTimeMillis();
                HistoryStore.add(this, e);

                // 后台补一个「竖屏/横屏」标记，主页的「竖横分区」排列要用
                if (!"photo".equals(kind)) {
                    new Thread(() -> {
                        try {
                            String o = Store.probeOrientation(this, e);
                            if (o != null) {
                                HistoryStore.updateOrientation(this, e.name, e.ts, o);
                            }
                        } catch (Throwable ignored) { }
                    }, "orient").start();
                }

                // 成了：断点状态和中间数据都没用了，一次清干净
                Parts.clear(this, pid);

                // 批量里中间那几张**不换文案、不弹新通知**（那是"一张一跳"的直接原因），
                // 只有最后一张才收尾成"全部已保存"；单条下载照旧提示已保存。
                // 并行时按「已完成几张」判断收尾，不能再看 batchIndex（完成顺序是乱的）
                if (!inBatch()) {
                    notify(build(fname, "已保存", 100, false));
                } else if (batchDoneN.incrementAndGet() >= batchTotal) {
                    notify(bn(fname, "全部 " + batchTotal + " 张已保存", 100, false));
                } else {
                    notify(bn(fname, "已完成", 100, true));
                }
                broadcast(this, A_DONE, fname);
            } catch (Throwable t) {
                if (tk.canceled) {
                    android.util.Log.i("DownX", "用户取消下载：" + tk.name);
                    cleanupParts();
                    Parts.clear(this, pid);      // 用户反悔了：连断点状态一起删
                    broadcast(this, A_CANCEL, "已取消下载，下到一半的文件也删掉了");
                } else {
                    android.util.Log.w("DownX", "下载失败 " + tk.name, t);
                    // 不做断点续传：失败就把半成品和状态一起清掉，
                    // 不留在缓存里白占空间（用户也找不到是哪个链接的）
                    cleanupParts();
                    Parts.clear(this, pid);
                    broadcast(this, A_FAIL, msg(t) + "（已清理半成品，重新点下载即可）");
                    // 批量中间某张失败：通知还是「进行中」，下一张会紧接着来
                    notify(bn(tk.name, "下载失败，接着下一张…", 0,
                            inBatch() && batchIndex < batchTotal - 1));
                }
            } finally {
                CUR.remove();
                tasks.remove(tk);
                if (tk == task) task = null;     // 只清自己，别把并行的另一条当成自己清掉
                if (conn != null) {
                    conns.remove(conn);
                    try { conn.disconnect(); } catch (Throwable ignored) { }
                }
                releaseSlot("photo".equals(kind));

                // 还有并行的在跑：服务不退、通知不撤，交给最后一条收尾
                if (SLOTS.active() > 0) {
                    // 取消整批时其余线程会自己抛 Canceled 走同一个 finally
                } else if (tk.canceled) {
                    try {
                        NotificationManager nm2 =
                                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        nm2.cancel(NID);
                    } catch (Throwable ignored) { }
                    stopForeground(true);
                    fgUp = false;
                    stopSelf();
                } else if (inBatch() && batchDoneN.get() < batchTotal) {
                    // 整批还有下一张：服务不退出、通知不撤下，
                    // 等 MainActivity 排来的下一张直接在同一实例上接着跑
                    keepAliveForNext();
                } else {
                    stopForeground(false);
                    fgUp = false;
                    stopSelf();
                }
            }
        }, "dl").start();

        return START_NOT_STICKY;
    }

    /**
     * 整批中间的一张贴完了，但服务先不退：MainActivity 会紧接着排下一张，
     * 同一个服务实例接住它，通知就始终是那一条（不消失、不重新提醒）。
     * 兜底：20 秒还没等到下一张（用户取消了整批 / 后面的都被去重跳过），自己收摊。
     */
    private void keepAliveForNext() {
        final int myIndex = batchIndex;
        new Thread(() -> {
            try { Thread.sleep(20000L); } catch (InterruptedException ignored) { }
            if (SLOTS.active() > 0) return;       // 下一张已经在跑了
            if (batchIndex != myIndex) return;  // 下一条启动已经进来过
            fgUp = false;
            stopForeground(false);
            stopSelf();
        }, "dl-idle").start();
    }

    /** 单条直链下载到相册，返回 content uri */
    private String one(String u, String referer, String name, String kind, boolean visible,
                       HttpURLConnection[] holder, Parts.State st) throws Exception {
        // 数据直接写进 parts 目录：失败时它就是下次续传的起点，成功后由外层统一清
        File data = Parts.dataFile(this, st.id, "v");

        // 先试分片并发：8 条 Range 连接通常比单连接快 3~6 倍。
        // 服务器不支持 Range / 文件太小，它会自己放弃（used=false），下面退回单连接，
        // 行为跟以前完全一样 —— 这也让这条新路径出问题时不至于整单失败。
        final int[] shown = {-1};
        ChunkedFetch cf = new ChunkedFetch(data, u, referer, st, new ChunkedFetch.Ctl() {
            @Override
            public void gate() {
                DlService.this.gate();
            }

            @Override
            public void progress(long done, long total) {
                int p = (int) (done * 92 / Math.max(1, total));
                if (p == shown[0]) return;
                shown[0] = p;
                // 匿名类里裸写 notify 会命中 Object.notify()，必须显式指明是 Service 的那个
                DlService.this.notify(bn(name, "下载中 " + p + "%", p, true));
                broadcast(DlService.this, A_PROG, String.valueOf(p));
            }

            @Override
            public void persist() {
                Parts.save(DlService.this, st);
            }

            @Override
            public void log(String line) {
                android.util.Log.i("DownX", line);
            }
        });

        long len;
        String ct;
        chunkeds.add(cf);
        try {
            ChunkedFetch.Result cr = cf.run();
            if (cr.used) {
                len = cr.total;
                ct = cr.contentType;
            } else {
                len = fetch(u, referer, data, name, holder, st);
                ct = holder[0] == null ? "" : String.valueOf(holder[0].getContentType());
            }
        } finally {
            chunkeds.remove(cf);
        }

        // 有的站会把广告图/错误页当成 mp4 返回，这里按内容头挡一道
        if (!"photo".equals(kind)) {
            String low = ct == null ? "" : ct.toLowerCase();
            if (low.startsWith("image/") || low.startsWith("text/")) {
                // 个别 CDN 会胡乱报 Content-Type：文件头第 4~7 字节是 "ftyp"
                // 的就是真 mp4，别因为一个报错的头把好视频扔了
                if (!isMp4Magic(data)) {
                    throw new Exception("服务器返回的不是视频（" + ct + "），链接可能被换了");
                }
            }
        }
        try (InputStream in = new java.io.FileInputStream(data)) {
            return Store.write(this, in, len, kind, name, visible, p -> {
                gate();      // 保存阶段也能暂停/取消，取消时 Store 会把半成品删掉
                notify(bn(name, "保存中 " + p + "%", 96 + p / 25, true));
            });
        }
    }

    /**
     * 单流下载，两层断点：
     *   ① 同一次运行里断流 —— 记着已写字节用 Range 接着要，最多 6 次；
     *   ② 进程被杀 / App 崩溃 —— 数据留在 parts 目录，下次进来按文件长度接着下。
     * 只有服务器拒绝 Range（返回 200 而不是 206）时才不得不从头开始。
     */
    private long fetch(String u, String referer, File tmp, String name,
                       HttpURLConnection[] holder, Parts.State st) throws Exception {
        long have = tmp.exists() ? tmp.length() : 0;
        Parts.truncate(tmp, have);            // 截掉崩溃时可能写坏的尾部半截
        long total = st != null && st.totalV > 0 ? st.totalV : -1;
        int lastPct = -1;
        // 重试要够耐心：切后台/灭屏后 MIUI 会掐一会儿网络，几十秒后就恢复，
        // 以前 6 次盲冲几十秒就放弃，留下 99% 半成品让用户手动续
        for (int tries = 1; tries <= 12; tries++) {
            if (tries > 1) {
                long backoff = Math.min(800L * (tries - 1), 6000L);
                try { Thread.sleep(backoff); } catch (InterruptedException ignored) { }
                gate();
            }
            HttpURLConnection c = open(u, referer);
            holder[0] = c;
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code == 416) {                // 本地已比远端长：要么下完了，要么文件坏了
                if (total <= 0 || have >= total) return have;
                have = 0;
                Parts.truncate(tmp, 0);
                continue;
            }
            if (code >= 400) throw new Exception("服务器拒绝（HTTP " + code + "），链接可能已过期");
            boolean resumed = code == 206;
            if (have > 0 && !resumed) {
                // 服务器不理 Range：只能从 0 来（少数 CDN 会这样）
                have = 0;
                Parts.truncate(tmp, 0);
            }
            long len = c.getContentLength();
            if (resumed) {
                if (total <= 0) total = have + len;
            } else if (total <= 0) {
                total = len;
            }
            if (st != null && total > 0) st.totalV = total;
            InputStream in = c.getInputStream();
            long savedAt = have;
            try (java.io.OutputStream os = new java.io.FileOutputStream(tmp, true)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    gate();                       // 这里暂停会原地停住，继续时接着往下读
                    os.write(buf, 0, n);
                    have += n;
                    if (total > 0) {
                        int p = (int) (have * 92 / total);
                        if (p > lastPct) {
                            lastPct = p;
                            progress(name, p);
                        }
                    }
                    // 每 4MB 落一次盘：崩溃最多丢 4MB，不用每写一块就刷状态
                    if (st != null && have - savedAt >= 4L * 1024 * 1024) {
                        savedAt = have;
                        st.lenV = have;
                        Parts.save(this, st);
                    }
                }
            } catch (java.io.IOException e) {
                android.util.Log.w("DownX", "断流 " + have + "/" + total + "，第 "
                        + tries + " 次续传");
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
            if (st != null) {
                st.lenV = have;
                Parts.save(this, st);
            }
            if (total <= 0) return have;          // 长度未知，只能信这一遍
            if (have >= total) return total;      // 下完了
        }
        throw new Exception("网络反复中断，重试多次仍没下完，请稍后重新下载");
    }

    private static boolean isHls(String u) {
        if (u == null) return false;
        String low = u.toLowerCase();
        return low.contains(".m3u8");
    }

    /** 文件头是不是 mp4 的 ftyp 签名（isom/msix 等品牌都在这个位置） */
    private static boolean isMp4Magic(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[12];
            int n = in.read(b);
            return n >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
        } catch (Throwable t) {
            return false;
        }
    }
    /**
     * HLS / m3u8 下载：不少站点现在只给分片流。
     * 流程：拉主清单 → 按用户选的画质（没选就挑最高码率）→ 逐个下分片拼成一个文件
     *      → TS 分片用 Mux 重封装成 mp4（不解码，几秒钟）
     *      → 若音频是独立 rendition（EXT-X-MEDIA），再单独下一遍并合回去。
     *
     * 断点续传按「分片」粒度：每下完一片就把序号写进 state，进程被杀后再进来
     * 从下一片接着下；已经下完的流连清单都不重拉一遍（vDone 直接进封装）。
     */
    private String hls(String masterUrl, String referer, String name, String kind,
                       boolean visible, String pick, HttpURLConnection[] holder,
                       Parts.State st) throws Exception {
        String base = masterUrl;
        String txt = text(base, referer, holder);
        String audioPl = null;

        if (txt.contains("#EXT-X-STREAM-INF")) {
            String[] lines = txt.split("\r?\n");
            long bestBw = -1;
            String best = null;
            String want = null;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                // 独立音轨：master 里会写 EXT-X-MEDIA:TYPE=AUDIO,URI="xxx.m3u8"
                if (l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO")
                        && audioPl == null) {
                    String u = attr(l, "URI=");
                    if (!u.isEmpty()) audioPl = abs(base, u);
                }
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
                long bw = attrNum(l, "BANDWIDTH=");
                String h = "";
                java.util.regex.Matcher rm = java.util.regex.Pattern
                        .compile("RESOLUTION=\\d+x(\\d{3,4})").matcher(l);
                if (rm.find()) h = rm.group(1);
                String next = null;
                for (int j = i + 1; j < lines.length; j++) {
                    String c = lines[j].trim();
                    if (c.isEmpty() || c.startsWith("#")) continue;
                    next = c;
                    break;
                }
                if (next == null) continue;
                if (pick != null && pick.equals(h)) want = abs(base, next);
                if (bw > bestBw) {
                    bestBw = bw;
                    best = abs(base, next);
                }
            }
            String chosen = want != null ? want : best;
            if (chosen == null) throw new Exception("主清单里没找到可用的视频流");
            base = chosen;
            txt = text(base, referer, holder);
        }

        if (txt.contains("#EXT-X-KEY") && txt.contains("AES-128")) {
            throw new Exception("这条视频的 HLS 分片是加密的（AES-128），暂时下不了");
        }

        java.util.List<String> segs = new java.util.ArrayList<>();
        String initSeg = null;
        for (String raw : txt.split("\r?\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-MAP")) {
                initSeg = abs(base, attr(l, "URI="));
            } else if (!l.startsWith("#")) {
                segs.add(abs(base, l));
            }
        }
        if (segs.isEmpty()) throw new Exception("分片清单是空的，链接可能已过期");

        // 清单指纹：同一条视频同一档画质，重解析后分片路径不会变（签名参数会过期，
        // 所以只比路径尾巴）。对不上说明换了流，旧数据作废重下
        String sig = sigOf(segs);
        if (!sig.equals(st.sig)) {
            st.sig = sig;
            st.offs = "";
            st.segV = 0;
            st.lenV = 0;
            st.vDone = false;
            st.totalV = -1;
            st.segA = 0;
            st.lenA = 0;
            st.aDone = false;
            st.totalA = -1;
            st.segCountV = segs.size();
            //noinspection ResultOfMethodCallIgnored
            Parts.dataFile(this, st.id, "v").delete();
            //noinspection ResultOfMethodCallIgnored
            Parts.dataFile(this, st.id, "a").delete();
            Parts.save(this, st);
        } else {
            st.segCountV = segs.size();
        }

        // fMP4 分片（有 EXT-X-MAP）本身就是 mp4 片段，拼起来直接能用；
        // TS 分片必须重封装，否则相册识别不了
        boolean fmp4 = initSeg != null;
        File dataV = Parts.dataFile(this, st.id, "v");
        File dataA = Parts.dataFile(this, st.id, "a");
        File mp4 = null;
        File merged = null;
        int videoEnd = audioPl == null ? 90 : 70;
        try {
            if (!st.vDone) {
                writeSegments(base, initSeg, segs, referer, dataV, name, holder,
                        st, false, 0, videoEnd);
            } else {
                notify(bn(name, "画面已下完，接着处理…", videoEnd, true));
                broadcast(this, A_PROG, String.valueOf(videoEnd));
            }

            File src = dataV;
            if (!fmp4) {
                mp4 = new File(workDir(), "r" + System.nanoTime() + ".mp4");
                notify(bn(name, "重新封装成 MP4…", videoEnd + 2, true));
                long[] segLens = parseSegLens(st.offs, segs.size());
                Mux.remux(dataV, segLens, mp4, p -> {
                    gate();
                    // 映射到 videoEnd~videoEnd+6，绝不能越过后面「保存」的 96%
                    int q = videoEnd + p * 6 / 100;
                    notify(bn(name, "封装中 " + p + "%", q, true));
                    broadcast(this, A_PROG, String.valueOf(q));
                });
                src = mp4;
            }

            // 音轨跟画面分开时，单独下一遍再合回去，否则成片没声音
            if (audioPl != null) {
                try {
                    String at = text(audioPl, referer, holder);
                    if (!at.contains("#EXT-X-KEY") || !at.contains("AES-128")) {
                        java.util.List<String> aSegs = new java.util.ArrayList<>();
                        String aInit = null;
                        for (String raw : at.split("\r?\n")) {
                            String l = raw.trim();
                            if (l.isEmpty()) continue;
                            if (l.startsWith("#EXT-X-MAP")) {
                                aInit = abs(audioPl, attr(l, "URI="));
                            } else if (!l.startsWith("#")) {
                                aSegs.add(abs(audioPl, l));
                            }
                        }
                        if (!aSegs.isEmpty()) {
                            if (!st.aDone) {
                                writeSegments(audioPl, aInit, aSegs, referer, dataA, name,
                                        holder, st, true, videoEnd, 88);
                            }
                            merged = new File(workDir(), "mux" + System.nanoTime() + ".mp4");
                            notify(bn(name, "合并声音…", 90, true));
                            final File msrc = src;
                            Mux.merge(msrc, dataA, merged, p -> gate());
                            src = merged;
                        }
                    }
                } catch (Canceled cc) {
                    throw cc;
                } catch (Throwable t) {
                    android.util.Log.w("DownX", "音轨合并失败，保留无声音版本", t);
                }
            }

            try (InputStream in = new java.io.FileInputStream(src)) {
                return Store.write(this, in, src.length(), kind, name, visible, p -> {
                    gate();
                    notify(bn(name, "保存中 " + p + "%", 96 + p / 25, true));
                });
            }
        } finally {
            // 只清中间产物；dataV / dataA 留着，失败时它们是续传的起点
            if (mp4 != null) {
                //noinspection ResultOfMethodCallIgnored
                mp4.delete();
            }
            if (merged != null) {
                //noinspection ResultOfMethodCallIgnored
                merged.delete();
            }
        }
    }

    /**
     * 把一个清单里的分片顺序追加进同一个文件，顺带报进度。
     * 每下完一片就把「已完成片数 + 已写字节」写进 state：
     * 断网/崩溃后从下一片续上，已经下过的一片都不会重下。
     */
    private void writeSegments(String plUrl, String initSeg, java.util.List<String> segs,
                               String referer, File out, String name,
                               HttpURLConnection[] holder, Parts.State st,
                               boolean audio, int pctFrom, int pctTo)
            throws Exception {
        int done = audio ? st.segA : st.segV;
        long len = audio ? st.lenA : st.lenV;
        if (done > segs.size()) {           // 清单变短了，只能重来
            done = 0;
            len = 0;
        }
        if (out.exists() && out.length() != len) Parts.truncate(out, len);
        boolean append = out.exists() && out.length() == len && len > 0;
        if (!append) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            done = 0;
            len = 0;
        }
        java.io.FileOutputStream os = new java.io.FileOutputStream(out, append);
        int lastPct = -1;
        int span = Math.max(1, segs.size());
        // 记下每一片的字节数：重封装时按分片边界逐段抽，
        // 否则 MediaExtractor 会在分片接缝的时间戳跳变处提前收工
        StringBuilder lens = new StringBuilder();
        if (!audio && done > 0 && st.offs != null && !st.offs.isEmpty()) {
            String[] old = st.offs.split(",");
            int keep = Math.min(done, old.length);
            for (int i = 0; i < keep; i++) lens.append(old[i]).append(',');
        }
        try {
            if (done == 0 && initSeg != null) {
                len += copyTo(initSeg, referer, os, holder);
                os.flush();
                if (audio) st.lenA = len;
                else st.lenV = len;
                Parts.save(this, st);
            }
            // 分片并行：HLS 天然是「很多个小文件」，串行下等于单连接。
            // 实测 30 个小文件串行 1.41s、4 路并行 0.5s（tests/speed）。
            // 用滑动窗口而不是「全下完再合并」：后者磁盘峰值是成片的 2 倍，
            // 一条 1.5GB 的片子要 3GB 空闲，手机上经常直接存不下。
            // 合并仍在主线程按清单顺序做 —— Mux.remux 靠 segLens 切段，错一片整条花掉。
            final StringBuilder lensBox = lens;
            final long[] lenBox = {len};
            final int[] pctBox = {lastPct};
            new ParallelSegs(HLS_WORKERS).runWindow(segs, done, out.getParentFile(),
                    audio ? "a_" : "v_", HLS_AHEAD,
                    (u, dest) -> downloadSeg(u, referer, dest),
                    (i, part) -> {
                        gate();              // 分片之间是最自然的暂停点
                        long got = appendFile(part, os);
                        os.flush();
                        lenBox[0] += got;
                        if (!audio) lensBox.append(got).append(',');
                        // 合走立刻删：磁盘上只留「还没合的那几片」+ 已经长成的部分
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        if (audio) {
                            st.segA = i + 1;
                            st.lenA = lenBox[0];
                        } else {
                            st.segV = i + 1;
                            st.lenV = lenBox[0];
                        }
                        st.ts = System.currentTimeMillis();
                        Parts.save(this, st);
                        int p = pctFrom + (i + 1) * (pctTo - pctFrom) / span;
                        if (p != pctBox[0]) {
                            pctBox[0] = p;
                            progress(name, p);
                        }
                    });
        } finally {
            try { os.close(); } catch (Throwable ignored) { }
        }
        if (audio) {
            st.aDone = true;
        } else {
            st.vDone = true;
            st.offs = lens.toString();
        }
        Parts.save(this, st);
    }

    /** 同时抓几片：4 路在手机网络上已经能吃满 CDN，再多容易被同 IP 限速 */
    private static final int HLS_WORKERS = 4;

    /**
     * 滑动窗口深度：最多几片「下好了但还没合走」。
     * 8 片按常见的 2~6MB/片算，临时占用约 16~48MB，相对成片大小可以忽略；
     * 再深就白白多吃内存和磁盘，也更容易被 CDN 判定为并发过高。
     */
    private static final int HLS_AHEAD = 8;

    /** 下一个分片（带 4 次重试）；已存在且非空就直接复用，续传靠它 */
    private long downloadSeg(String u, String referer, File dest) throws Exception {
        if (dest.exists() && dest.length() > 0) return dest.length();
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        int tries = 0;
        while (true) {
            HttpURLConnection c = null;
            try {
                c = open(u, referer);
                int code = c.getResponseCode();
                if (code >= 400) throw new Exception("分片下载失败（HTTP " + code + "）");
                long got = 0;
                try (InputStream in = c.getInputStream();
                     java.io.OutputStream os = new java.io.FileOutputStream(tmp, false)) {
                    byte[] buf = new byte[64 * 1024];
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        gate();
                        os.write(buf, 0, k);
                        got += k;
                    }
                }
                if (got <= 0) throw new Exception("分片是空的");
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(dest);
                return got;
            } catch (Canceled cc) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw cc;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                if (++tries >= 4) throw e;
                android.util.Log.w("DownX", "分片重试 " + tries + "：" + e.getMessage());
                try {
                    Thread.sleep(Math.min(800L * tries, 3000L));
                } catch (InterruptedException ignored) { }
                gate();
            } finally {
                try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 把一片临时文件按序追加进输出流，返回字节数 */
    private long appendFile(File part, java.io.OutputStream os) throws Exception {
        long n = 0;
        try (InputStream in = new java.io.FileInputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) {
                gate();
                os.write(buf, 0, k);
                n += k;
            }
        }
        return n;
    }

    /** 抓一段文本（m3u8 清单） */
    private String text(String u, String referer, HttpURLConnection[] holder) throws Exception {
        HttpURLConnection c = open(u, referer);
        holder[0] = c;
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("清单拉不下来（HTTP " + code + "），链接可能已过期");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                gate();
                bos.write(buf, 0, n);
            }
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 把一个分片写进流，返回写入的字节数（续传时要靠它把文件长度对齐） */
    private long copyTo(String u, String referer, java.io.OutputStream os,
                        HttpURLConnection[] holder) throws Exception {
        HttpURLConnection c = open(u, referer);
        holder[0] = c;
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("分片下载失败（HTTP " + code + "）");
        long got = 0;
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                gate();
                os.write(buf, 0, n);
                got += n;
            }
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
        return got;
    }

    /** m3u8 里的相对地址要按清单所在目录补全 */
    private static String abs(String base, String ref) {
        try {
            return new java.net.URI(base).resolve(ref).toString();
        } catch (Throwable t) {
            int slash = base.lastIndexOf('/');
            return slash > 0 ? base.substring(0, slash + 1) + ref : ref;
        }
    }

    private static String attr(String line, String key) {
        int i = line.indexOf(key + "\"");
        if (i < 0) return "";
        int s = i + key.length() + 1;
        int e = line.indexOf('"', s);
        return e > s ? line.substring(s, e) : "";
    }

    private static long attrNum(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return 0;
        int s = i + key.length();
        int e = s;
        while (e < line.length() && Character.isDigit(line.charAt(e))) e++;
        try {
            return Long.parseLong(line.substring(s, e));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 是不是「服务器明确拒绝」这类错误（这类换备用流才有意义） */
    private static boolean rejected(Throwable t) {
        String m = msg(t);
        return m.contains("HTTP 4") || m.contains("HTTP 5");
    }

    /**
     * 下载时额外带的 Cookie（B 站需要：登录态才让 CDN 给高码率流）。
     * 同一时刻只跑一条下载（见 busy），所以静态字段不会串到别的任务上。
     */
    static volatile String cookie = "";

    public static void setCookie(String c) {
        cookie = c == null ? "" : c;
    }

    /**
     * 本线程最近一条连接。HLS 是「一片一个连接」，若不换掉登记的旧连接，
     * 集合里会攒下几百条早已读完的 HttpURLConnection（既漏内存，取消时也白掐一堆）。
     */
    private static final ThreadLocal<HttpURLConnection> LAST_CONN = new ThreadLocal<>();

    private static HttpURLConnection open(String url, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        // 取消时靠这个集合把卡在读上的连接全部掐断
        HttpURLConnection prev = LAST_CONN.get();
        if (prev != null) conns.remove(prev);
        conns.add(conn);
        LAST_CONN.set(conn);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        // B 站 CDN 实测只认桌面 UA（tests/bili：移动 UA 在标准节点一律 403），
        // 而且直链签名绑定了「解析时的 UA」，所以解析和下载必须同一个桌面 UA
        conn.setRequestProperty("User-Agent",
                Net.isBiliCdn(url) ? Net.UA_DESKTOP : Net.UA);
        conn.setRequestProperty("Accept", "*/*");
        if (referer != null && !referer.isEmpty()) {
            conn.setRequestProperty("Referer", referer);
        }
        String ck = cookie;
        if (ck != null && !ck.isEmpty()) {
            conn.setRequestProperty("Cookie", ck);
        }
        return conn;
    }

    /** 换协议从头下：清掉断点与已下的数据文件，防止两股流的字节拼在一起 */
    private void resetParts(Parts.State st) {
        st.cs = 0;
        st.ranges = "";
        st.lenV = 0;
        st.lenA = 0;
        st.totalV = -1;
        st.segV = 0;
        st.segA = 0;
        st.offs = "";
        try { Parts.dataFile(this, st.id, "v").delete(); } catch (Throwable ignored) { }
        try { Parts.dataFile(this, st.id, "a").delete(); } catch (Throwable ignored) { }
        Parts.save(this, st);
    }

    /** 取消后兜底：把临时目录里下到一半的东西全清掉，别留残余占空间 */
    private void cleanupParts() {
        // 并行时这个目录里放的可能是**别人**正在用的中间文件（YouTube 双流的音视频轨），
        // 一个任务失败就把整个目录端掉会连累另一路
        if (SLOTS.active() > 1) return;
        try {
            File[] fs = workDir().listFiles();
            if (fs == null) return;
            for (File f : fs) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) { }
    }

    /** 临时目录：放分离的视频轨/音轨，合完就删 */
    private File workDir() {
        File base = getExternalCacheDir();
        if (base == null) base = getCacheDir();
        File d = new File(base, "mux");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * 两段下载 + 本地合成：视频轨（0~62%）→ 音轨（62~80%）→ 合并（81~94%）→ 落盘。
     * YouTube 的 1080P 只有 DASH 分离流，没有音画一体的文件，必须分开下再合。
     * 两条轨各自可续传：崩溃时谁的 vDone/aDone 是 true 就不用再下一遍。
     */
    private String twoPassAndMerge(String vUrl, String aUrl, String name, String kind,
                                   String referer, boolean visible, Parts.State st) throws Exception {
        File vf = Parts.dataFile(this, st.id, "v");
        File af = Parts.dataFile(this, st.id, "a");
        File mf = new File(workDir(), "m" + System.nanoTime() + ".p");
        try {
            if (!st.vDone) {
                int b = pctOf(st.lenV, st.totalV, 0, 62);
                notify(bn(name, "下载画面 " + b + "%", b, true));
                downloadTo(vf, vUrl, referer, 0, 62, name, "下载画面", st, false);
            } else {
                notify(bn(name, "画面已下完", 62, true));
            }

            if (!st.aDone) {
                int b = pctOf(st.lenA, st.totalA, 62, 80);
                notify(bn(name, "下载声音 " + b + "%", b, true));
                downloadTo(af, aUrl, referer, 62, 80, name, "下载声音", st, true);
            } else {
                notify(bn(name, "声音已下完", 80, true));
            }

            notify(bn(name, "合成中…", 81, true));
            broadcast(this, A_PROG, "81");
            Mux.merge(vf, af, mf, p -> {
                gate();
                // 关键：Mux 的 p 是 0~100，得压进 81~94 这一段，
                // 否则进度会从 80 猛掉到 0 再爬回去，看着像来回跳
                int q = 81 + p * 13 / 100;
                notify(bn(name, "合成中 " + p + "%", q, true));
                broadcast(this, A_PROG, String.valueOf(q));
            });

            gate();
            notify(bn(name, "写入文件…", 99, true));
            if (!visible) {
                // 隐藏模式：都在同一块存储上，直接改名，不用再拷一遍几百兆
                // 用 privateWriteTarget 保证落到 Android/data/<包名>/files/... ，
                // 与 Store.privateDir 保持一致，隐藏后去私有目录就能找到
                java.io.File out = Store.privateWriteTarget(this, kind, name);
                if (out.exists()) out.delete();
                if (!mf.renameTo(out)) {
                    try (InputStream in = new java.io.FileInputStream(mf)) {
                        Store.write(this, in, mf.length(), kind, name, false, p -> gate());
                    }
                }
                return "";
            }
            try (InputStream in = new java.io.FileInputStream(mf)) {
                return Store.write(this, in, mf.length(), kind, name, true, p -> gate());
            }
        } finally {
            // 中间合成文件每次都删；vf / af 由外层在成功或取消时一并清掉
            //noinspection ResultOfMethodCallIgnored
            mf.delete();
        }
    }

    /** 把百分比映射进 [from,to] 区间，算不出总长时退回区间起点 */
    private static int pctOf(long done, long total, int from, int to) {
        if (total <= 0) return from;
        int p = from + (int) ((to - from) * done / total);
        return Math.max(from, Math.min(to, p));
    }

    /**
     * 单条轨下载（断点续传版）：从已写长度往后接，断流自动重试；
     * 服务器不支持 Range 时才清空重来。
     */
    private void downloadTo(File out, String url, String referer,
                            int fromPct, int toPct, String name, String prefix,
                            Parts.State st, boolean audio) throws Exception {
        long have = out.exists() ? out.length() : 0;
        Parts.truncate(out, have);
        long total = audio ? st.totalA : st.totalV;
        int last = -1;
        int tries = 0;
        while (true) {
            HttpURLConnection conn = null;
            try {
                conn = open(url, referer);
                if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
                int code = conn.getResponseCode();
                if (code >= 400) {
                    throw new Exception(prefix + "失败（HTTP " + code + "），链接可能已过期");
                }
                if (have > 0 && code != 206) {
                    have = 0;
                    Parts.truncate(out, 0);
                }
                long len = conn.getContentLength();
                if (total <= 0) total = have > 0 ? have + len : len;
                if (audio) st.totalA = total;
                else st.totalV = total;
                try (InputStream in = conn.getInputStream();
                     java.io.OutputStream os = new java.io.FileOutputStream(out, true)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        gate();
                        os.write(buf, 0, n);
                        have += n;
                        if (total > 0) {
                            int p = fromPct + (int) ((toPct - fromPct) * have / total);
                            if (p != last) {
                                last = p;
                                notify(bn(name, prefix + " " + p + "%", p, true));
                                broadcast(this, A_PROG, String.valueOf(p));
                            }
                        }
                    }
                    os.flush();
                }
                if (total <= 0 || have >= total) break;
            } catch (Canceled cc) {
                throw cc;
            } catch (Exception e) {
                tries++;
                if (tries >= 4) throw e;
                android.util.Log.w("DownX", prefix + "中断，第 " + tries + " 次续传：" + e.getMessage());
                try {
                    Thread.sleep(700L * tries);
                } catch (InterruptedException ignored) { }
                gate();
            } finally {
                try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
        if (audio) {
            st.aDone = true;
            st.lenA = have;
        } else {
            st.vDone = true;
            st.lenV = have;
        }
        Parts.save(this, st);
        if (out.length() <= 0) throw new Exception(prefix + "失败：文件是空的");
    }

    /** "123,456,..." → long[]；数量对不上就当没有（退回整文件模式） */
    private static long[] parseSegLens(String s, int expect) {
        if (s == null || s.trim().isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length != expect) return null;
        long[] r = new long[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                r[i] = Long.parseLong(p[i].trim());
            } catch (Throwable t) {
                return null;
            }
            if (r[i] <= 0) return null;
        }
        return r;
    }

    /** 清单指纹：分片数 + 首尾分片路径。签名参数会过期变化，所以只比路径 */
    private static String sigOf(java.util.List<String> segs) {
        if (segs.isEmpty()) return "";
        return segs.size() + ":" + tail(segs.get(0)) + ":" + tail(segs.get(segs.size() - 1));
    }

    private static String tail(String u) {
        int q = u.indexOf('?');
        String s = q > 0 ? u.substring(0, q) : u;
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /**
     * 续传用的稳定 key：去掉域名（同一视频的不同镜像域名视为同一条），
     * 只留路径 + 画质标签，这样重新解析后还能对上上次的任务。
     */
    private static String stableKey(String key) {
        if (key == null) return "";
        int bar = key.indexOf('|');
        if (bar <= 0) return key;
        String url = key.substring(0, bar);
        String label = key.substring(bar);
        try {
            java.net.URI u = new java.net.URI(url);
            String p = u.getPath() == null ? "" : u.getPath();
            String q = u.getQuery();
            return p + (q == null ? "" : "?" + q) + label;
        } catch (Throwable t) {
            return key;
        }
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        if (m.contains("Unable to resolve host") || m.contains("Failed to connect")
                || m.contains("timeout") || m.contains("timed out")) {
            return "网络连不上，X / Instagram 需要开代理";
        }
        return "下载失败：" + m;
    }

    private static void broadcast(Context c, String action, String text) {
        Intent i = new Intent(action);
        i.setPackage(c.getPackageName());
        i.putExtra("msg", text);
        c.sendBroadcast(i);
    }

    // ---------------- 通知 ----------------

    private Notification build(String title, String text, int pct, boolean ongoing) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "下载", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);

        // 通知点击回到宿主工程的首页（DownX 原工程的 MainActivity 未移植，改指本工程入口）
        Intent open = new Intent(this, com.example.immersive.MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        // 记下「真正的下载状态文案」，暂停/继续时用它还原，别把“已暂停”记成进度文案
        String t2 = text == null ? "" : text;
        if (!t2.startsWith("已暂停") && !t2.startsWith("正在取消")) {
            lastTitle = title == null ? "DownX" : title;
            lastText = t2;
            lastPct = pct;
        }

        b.setContentTitle(title == null ? "DownX" : title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setShowWhen(false);
        if (ongoing) {
            b.setProgress(100, Math.max(0, pct), pct <= 0);

            // 反悔了随时能停：暂停（数据留着，继续接着下）+ 取消（连临时文件一起删）
            Intent ti = new Intent(this, DlService.class).setAction(ACT_TOGGLE);
            PendingIntent tpi = PendingIntent.getService(this, 2, ti, piFlags);
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this,
                            isPaused() ? android.R.drawable.ic_media_play
                                    : android.R.drawable.ic_media_pause),
                    isPaused() ? "继续" : "暂停", tpi).build());

            Intent ci = new Intent(this, DlService.class).setAction(ACT_CANCEL);
            PendingIntent cpi = PendingIntent.getService(this, 3, ci, piFlags);
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this,
                            android.R.drawable.ic_menu_close_clear_cancel),
                    "取消", cpi).build());
        } else {
            b.setProgress(0, 0, false);
        }
        return b.build();
    }

    private void notify(Notification n) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NID, n);
        } catch (Throwable ignored) { }
    }

    private long lastProgAt;
    /**
     * 进度刷新统一走这里并限频（约 0.7 秒一条）：
     * 以前每涨 1% 就 notify + 广播，下载快时一秒几十条，
     * 系统会按频率把通知丢掉（日志里 Package enqueue rate shedding），
     * 界面广播也让主线程一直忙（MIUI Slow main thread 告警）。
     */
    private void progress(String name, int p) {
        long now = System.currentTimeMillis();
        if (now - lastProgAt < 700) return;
        lastProgAt = now;
        notify(bn(name, "下载中 " + p + "%", p, true));
        broadcast(this, A_PROG, String.valueOf(p));
    }
}
