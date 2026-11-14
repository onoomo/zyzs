package com.ziyuan.Assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** 直播录制/下载前台服务: 持续通知, 每秒刷新进度/状态, 无活动任务自停 */
public class DownloadService extends Service {

    private static volatile boolean running = false;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() {
        if (!update()) { stopSelf(); running = false; return; }
        h.postDelayed(this, 1000);
    }};

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(47001, emptyNotif());
        h.postDelayed(tick, 1000);
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        h.removeCallbacks(tick);
        h.post(tick);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacks(tick);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(47001);
        super.onDestroy();
    }

    private Notification emptyNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("dl_chan", "下载", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, "dl_chan") : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
         .setContentTitle("下载服务已启动").setOngoing(true);
        return b.build();
    }

    /** @return 是否仍有活动任务 */
    private boolean update() {
        int n = 0;
        long done = 0, total = 0;
        String first = null;
        try {
            for (DlManager.DlJob j : DlManager.jobs()) {
                if (j.done || j.failed || !j.active) continue;
                n++;
                if (first == null) first = j.title != null ? j.title : ("下载#" + j.id);
                if (j.total > 0) { total += j.total; done += Math.min(j.doneBytes, j.total); }
            }
            // 录制通知统一由 RecKeepService 负责，这里只统计下载任务，避免两条重复通知
        } catch (Throwable ignored) {}
        if (n == 0) return false;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, "dl_chan")
            : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
         .setOnlyAlertOnce(true)
         .setOngoing(true);
        if (n == 1 && total > 0) {
            int pc = (int) (100L * done / total);
            b.setContentTitle(first)
             .setContentText(fmt(done) + " / " + fmt(total) + "  " + pc + "%")
             .setProgress(100, pc, false);
        } else if (n == 1) {
            b.setContentTitle(first)
             .setContentText("进行中…")
             .setProgress(0, 0, true);
        } else {
            b.setContentTitle("下载 " + n + " 个任务")
             .setContentText("进行中…")
             .setProgress(0, 0, true);
        }
        try { nm.notify(47001, b.build()); } catch (Throwable ignored) {}
        return true;
    }

    private static String fmt(long b) {
        if (b >= 1L << 30) return String.format(java.util.Locale.US, "%.1fG", b / 1073741824.0);
        if (b >= 1L << 20) return String.format(java.util.Locale.US, "%.1fM", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.0fK", b / 1024.0);
    }

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, DownloadService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable ignored) {}
    }
}
