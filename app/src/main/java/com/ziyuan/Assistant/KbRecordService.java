package com.ziyuan.Assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** KB 后台录制前台服务：保活+通知栏停止按钮 */
public class KbRecordService extends Service {

    private static final String CHANNEL = "kbrecord_v2";   // 旧kbrecord是LOW不出状态栏图标, 换id升级
    public static final String ACTION_STOP = "com.ziyuan.Assistant.KB_STOP";

    private android.os.PowerManager.WakeLock wl;
    private android.os.Handler uiH;
    private android.widget.TextView ovTxt;
    private android.view.View ovView;
    private long ovStart;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "xgjhome:kbrec");
            wl.acquire();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
        hideOverlay();
        super.onDestroy();
    }

    /** 屏幕顶部悬浮 REC 指示条（不依赖状态栏图标设置） */
    private void showOverlay() {
        try {
            if (!android.provider.Settings.canDrawOverlays(this)) return;
            if (ovView != null) return;
            uiH = new android.os.Handler(getMainLooper());
            ovStart = android.os.SystemClock.elapsedRealtime();
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            box.setGravity(android.view.Gravity.CENTER_VERTICAL);
            box.setPadding(18, 8, 18, 8);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xCC1B1B1B);
            bg.setCornerRadius(30);
            box.setBackgroundDrawable(bg);
            android.view.View dot = new android.view.View(this);
            android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
            dg.setColor(0xFFFF3B30);
            dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setBackgroundDrawable(dg);
            android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(16, 16);
            dlp.rightMargin = 10;
            box.addView(dot, dlp);
            ovTxt = new android.widget.TextView(this);
            ovTxt.setTextColor(0xFFFFFFFF);
            ovTxt.setTextSize(12);
            ovTxt.setText("REC 00:00");
            box.addView(ovTxt);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            lp.y = 60;
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            wm.addView(box, lp);
            ovView = box;
        } catch (Throwable ignored) {}
    }

    private void updateOverlayTime() {
        if (ovTxt == null || uiH == null) return;
        uiH.post(new Runnable() { public void run() {
            if (ovTxt == null) return;
            long s = (android.os.SystemClock.elapsedRealtime() - ovStart) / 1000;
            ovTxt.setText(String.format("REC %02d:%02d", s / 60, s % 60));
        } });
    }

    private void hideOverlay() {
        try {
            if (ovView != null) {
                android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
                wm.removeView(ovView);
            }
        } catch (Throwable ignored) {}
        ovView = null; ovTxt = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SniffActivity.stopKb();
            LiveProxy.stopRefresher();
            stopSelf();
            return START_NOT_STICKY;
        }
        LiveProxy.start();
        if (LiveProxy.mediaUrl == null) {
            LiveProxy.mediaUrl = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("mediaUrl", null);
        }
        LiveProxy.startRefresher();
        startForeground(2001, buildNotification("初始化…"));
        showOverlay();
        new Thread(new Runnable() { public void run() {
            while (true) {
                try {
                    Thread.sleep(2000);
                    long age = LiveProxy.latestAt == 0 ? -1 : (System.currentTimeMillis() - LiveProxy.latestAt) / 1000;
                    String t = "轮询 " + LiveProxy.pollCount + " 次｜失败 " + LiveProxy.failCount
                        + "｜最新列表 " + (age < 0 ? "无" : age + " 秒前");
                    updateNotification(t);
                    updateOverlayTime();
                    if (SniffActivity.kbState[0] != 1) break;
                } catch (Throwable e) { break; }
            }
        } }).start();
        return START_STICKY;
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(2001, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "直播录制", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(ch);
        // 点通知本体 = 直接进入播放器（本地代理地址，分片命中录制缓存，接近秒开）
        android.app.PendingIntent openPi = android.app.PendingIntent.getActivity(this, 2,
            new Intent(this, NativePlayerActivity.class)
                .putExtra("url", "http://127.0.0.1:" + LiveProxy.PORT + "/playlist.m3u8")
                .putExtra("title", "直播录制")
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("小工具 · 录制中")
            .setContentText(text + "｜点此进入查看")
            .setContentIntent(openPi)
            .setOngoing(true);
        android.app.PendingIntent pi = android.app.PendingIntent.getService(this, 0,
            new Intent(this, KbRecordService.class).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null, "停止", pi).build());
        return b.build();
    }
}
