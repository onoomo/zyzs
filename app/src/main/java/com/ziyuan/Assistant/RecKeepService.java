package com.ziyuan.Assistant;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;

/** 录制保活前台服务：通知栏/状态栏显示第一条录制任务，点击跳播放器；空闲自停 */
public class RecKeepService extends Service {
    private static final String CH = "rec_keep";
    private static final String CH_P = "rec_progress";
    private final Handler h = new Handler();

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            android.app.NotificationChannel nc = new android.app.NotificationChannel(CH, "录制保活", android.app.NotificationManager.IMPORTANCE_MIN);
            nc.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(nc);
            // 进度渠道: DEFAULT 才会在状态栏出图标 (与下载进度渠道同经验)
            android.app.NotificationChannel pc = new android.app.NotificationChannel(CH_P, "录制进度", android.app.NotificationManager.IMPORTANCE_DEFAULT);
            pc.setShowBadge(false);
            pc.setSound(null, null);
            pc.enableVibration(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(pc);
        } catch (Throwable ignored) {}
        startForeground(21001, noteBuilder("录制进行中", null, null).build());
        h.postDelayed(tick, 1000);
    }

    private final Runnable tick = new Runnable() { public void run() {
        boolean busy = false;
        try {
            // 只取第一条任务（id 最小的那一路）展示
            RecManager.RecJob first = null;
            for (RecManager.RecJob j : RecManager.recJobs.values()) {
                if (j == null) continue;
                busy = true;
                if (first == null || j.id < first.id) first = j;
            }
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (busy && first != null) {
                String st = first.state != null ? first.state : "录制中";
                String name = first.name != null ? first.name : "录制任务";
                // 第一条任务的实时数据：时长 · 大小 · 状态
                String line = fmtDur(curSecs(first)) + "  " + sizeStr(first.bytes) + "  " + st;
                nm.notify(21001, noteBuilder(name, line, playUrl(first)).build());
            }
        } catch (Throwable ignored) {}
        if (busy) {
            h.postDelayed(tick, 1000);
        } else {
            stopSelf();   // 无录制任务: 自动停服务、撤通知
        }
    } };

    /** 点击通知播放用的地址：优先取同一路最新的(未过期)地址 */
    private static String playUrl(RecManager.RecJob j) {
        try {
            String f = Or4Helper.freshFor(j.url);
            if (f != null && f.length() > 8) return f;
        } catch (Throwable ignored) {}
        return j.url;
    }

    private static long curSecs(RecManager.RecJob j) {
        long s = j.secs;
        if (j.startTs > 0) s += (System.currentTimeMillis() - j.startTs) / 1000;
        return s;
    }

    private static String fmtDur(long s) {
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }

    private static String sizeStr(long b) {
        if (b >= 1L << 30) return String.format("%.2fGB", b / 1073741824.0);
        if (b >= 1L << 20) return String.format("%.2fM", b / 1048576.0);
        if (b >= 1024) return String.format("%.0fK", b / 1024.0);
        return b + "B";
    }

    private Notification.Builder noteBuilder(String title, String text, String playUrl) {
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_P)
                : new Notification.Builder(this);
        b.setContentTitle(title)
         .setSmallIcon(android.R.drawable.stat_sys_download)
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        if (text != null && text.length() > 0) b.setContentText(text);

        Intent it;
        if (playUrl != null && playUrl.length() > 8) {
            // 点击通知 → 打开内置播放器播放这一路
            it = new Intent(this, NativePlayerActivity.class);
            it.putExtra("url", playUrl);
            it.putExtra("title", title);
            if (playUrl.contains("douyin")) it.putExtra("referer", "https://live.douyin.com/");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        } else {
            it = new Intent(this, com.example.immersive.MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        b.setContentIntent(android.app.PendingIntent.getActivity(this, 0, it,
             android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
        return b;
    }

    @Override
    public void onDestroy() {
        try { h.removeCallbacks(tick); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
