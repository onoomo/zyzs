package com.ziyuan.Assistant;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;

/** 下载保活前台服务：有下载任务时全进程存活；通知栏逐任务显示进度/结果 */
public class DlKeepService extends Service {
    private static final String CH = "dl_keep";
    private static final String CH_P = "dl_progress";
    private final Handler h = new Handler();
    private android.os.PowerManager.WakeLock wl;   // 后台保活: 锁 CPU 直到下载结束

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            android.app.NotificationChannel nc = new android.app.NotificationChannel(CH, "下载保活", android.app.NotificationManager.IMPORTANCE_MIN);
            nc.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(nc);
            // 进度渠道: DEFAULT 才会在状态栏出图标 (realme 实测 MIN 不出)
            android.app.NotificationChannel pc = new android.app.NotificationChannel(CH_P, "下载进度", android.app.NotificationManager.IMPORTANCE_DEFAULT);
            pc.setShowBadge(false);
            pc.setSound(null, null);
            pc.enableVibration(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(pc);
        } catch (Throwable ignored) {}
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Assistant:dlkeep");
            wl.setReferenceCounted(false);
            wl.acquire(6L * 60 * 60 * 1000);   // 最长 6 小时, 空闲自停时释放
        } catch (Throwable ignored) {}
        startForeground(20001, buildNote("下载进行中…"));
        h.postDelayed(tick, 1500);
    }

    private final Runnable tick = new Runnable() { public void run() {
        boolean busy = false;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            for (DlManager.DlJob j : DlManager.jobs()) {
                if (j.active || j.paused) busy = true;
                if (j.title == null) continue;
                if (j.done) {
                    if (!j.notified) {
                        j.notified = true;
                        nm.notify(30000 + j.id, progNote(j.title, "已完成 ✓" + sizeStr(j.doneBytes), 100, 100, false, true));
                    } else if (!j.active && !j.paused) nm.cancel(30000 + j.id);
                } else if (j.failed) {
                    if (!j.notified) {
                        j.notified = true;
                        nm.notify(30000 + j.id, progNote(j.title, j.state, 0, 0, false, true));
                    } else if (!j.active && !j.paused) nm.cancel(30000 + j.id);
                } else {
                    // 进行中: 进度通知
                    if (j.total > 0) {
                        int p = (int) Math.min(100, j.doneBytes * 100 / j.total);
                        nm.notify(30000 + j.id, progNote(j.title, sizeStr(j.doneBytes) + " / " + sizeStr(j.total), p, 100, true, false));
                    } else {
                        nm.notify(30000 + j.id, progNote(j.title, j.state + " " + sizeStr(j.doneBytes), 0, 0, true, false));
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (!busy) {
            stopForeground(true);
            stopSelf();
            return;
        }
        h.postDelayed(this, 1500);
    }};

    private static String sizeStr(long b) {
        if (b < 0) return "";
        if (b < 1024) return b + "B";
        if (b < 1048576) return String.format("%.1fMB", b / 1048576f);
        return String.format("%.2fGB", b / 1073741824f);
    }

    private Notification buildNote(String s) {
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle("资源嗅探").setContentText(s).setSmallIcon(android.R.drawable.stat_sys_download)
         .setOngoing(true);
        return b.build();
    }

    private Notification progNote(String title, String text, int p, int max, boolean ongoing, boolean fin) {
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_P)
                : new Notification.Builder(this);
        b.setContentTitle(title).setContentText(text)
         .setSmallIcon(fin ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_sys_download)
         .setOngoing(ongoing).setOnlyAlertOnce(true).setAutoCancel(fin);
        if (max > 0) b.setProgress(max, p, false);
        else if (ongoing) b.setProgress(0, 0, true);
        return b.build();
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    public static void ensure(Context c) {
        try {
            android.content.Intent i = new android.content.Intent(c, DlKeepService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {}
    }
}
