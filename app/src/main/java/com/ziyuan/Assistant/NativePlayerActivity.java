package com.ziyuan.Assistant;

import com.example.immersive.R;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import xyz.doikki.videoplayer.ijk.IjkPlayer;
import xyz.doikki.videoplayer.ijk.IjkPlayerFactory;
import xyz.doikki.videoplayer.player.BaseVideoView;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videocontroller.StandardVideoController;

/** 双内核播放器：默认走系统 AndroidMediaPlayer（硬解省电），仅 RTMP/RTSP 与冷门老格式按 URL 自动切 IjkPlayer（FFmpeg 软解兜底）；播放前一次性选定，运行时不切换。 */
public class NativePlayerActivity extends Activity {

    /** 双指缩放/拖动：在 Activity 层拦截，避免被控制器自身的触摸处理吃掉 */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        try { if (ZoomHelper.handle(this, ev)) return true; } catch (Throwable ignored) { }
        // 双击暂停 / 长按倍速：同样在 Activity 层监听（子 View 会消费触摸，root 的 OnTouchListener 收不到）
        try {
            if (gd != null) {
                gd.onTouchEvent(ev);
                if ((ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL)
                        && longPressSpeed) {
                    longPressSpeed = false;
                    try { videoView.setSpeed(playSpeed); } catch (Throwable ignored) {}  // 松开恢复设定倍速
                }
            }
        } catch (Throwable ignored) { }
        return super.dispatchTouchEvent(ev);
    }

    private VideoView videoView;
    private StandardVideoController controller;
    private String url;
    private String title;
    private boolean backgroundPlay = false;
    private GestureDetector gd;

    private float playSpeed = 1.0f;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f};
    private boolean longPressSpeed = false;   // 长按临时倍速中
    private TopRightButtonsComponent floatBtns;
    /** 直播态：关闭进度条拖动、左右滑动 seek、长按 2x 快进、倍速（直播无法快进） */
    private boolean isLive = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            initPlayerPage(savedInstanceState);
        } catch (Throwable e) {
            showCrash(e);
        }
    }

    void showCrash(final Throwable e) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            java.io.File f = new java.io.File(dir, "crash.txt");
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.append("\n==== " + new java.util.Date().toString() + " (player) ====\n");
            fw.append(android.util.Log.getStackTraceString(e));
            fw.close();
        } catch (Throwable e2) { }
        new GlassDialog.Builder(this)
                .setTitle("播放页闪退原因")
                .setCancelable(false)
                .setMessage(android.util.Log.getStackTraceString(e))
                .setPositiveButton("好", null)
                .show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    void initPlayerPage(Bundle savedInstanceState) {
        url = getIntent().getStringExtra("url");
        title = getIntent().getStringExtra("title");
        // 直播判定：URL 特征 + 显式 live 标记。直播关闭一切快进通道。
        isLive = getIntent().getBooleanExtra("live", isLiveUrl(url));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        videoView = new VideoView(this);
        // 双内核自动选：不设工厂时 dkplayer 默认用 AndroidMediaPlayer（系统硬解省电）；
        // 仅 RTMP/RTSP 拉流与冷门老格式(MPEG-2/WMV/RMVB 等)才 setPlayerFactory 切 Ijk（FFmpeg 软解兜底）
        if (needsIjkKernel(url)) {
            // IjkPlayer 内核（FFmpeg）；子类 setOptions 里开启 MediaCodec 硬件解码，省电不发热
            videoView.setPlayerFactory(new IjkPlayerFactory() {
                @Override
                public IjkPlayer createPlayer(android.content.Context context) {
                    return new IjkPlayer(context) {
                        @Override
                        public void setOptions() {
                            super.setOptions();
                            try {
                                if (mMediaPlayer != null) {
                                    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1);
                                    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
                                    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
                                }
                            } catch (Throwable ignored) {}
                        }
                    };
                }
            });
        }
        controller = new StandardVideoController(this);
        // 直播 → LiveControlView（无进度条、不可拖动）+ setCanChangePosition(false)（禁左右滑动 seek）
        controller.addDefaultControlComponent(title != null ? title : "资源嗅探", isLive);
        videoView.setVideoController(controller);
        // 双保险：无论 URL 判定结果，直播一律显式关闭滑动 seek
        if (isLive) { try { controller.setCanChangePosition(false); } catch (Throwable ignored) {} }
        if (isLive) disableSeekUi();   // 直播隐藏进度条/时间轴，快进在 UI 上彻底不可用
        // 双击播放/暂停统一由 Activity 层手势处理（先于子 View 触发，最可靠）。
        // 必须禁用 GestureVideoController 默认双击，否则 Activity 先 pause、控制器随后 togglePlay
        // 立即 resume，两次切换相互抵消，表现为“双击失效”。
        try { controller.setDoubleTapTogglePlayEnabled(false); } catch (Throwable ignored) { }
        controller.setOnKernelSwitchListener(new StandardVideoController.OnKernelSwitchListener() {
            @Override
            public void onKernelSwitch() { toggleBackgroundPlay(); }
        });
        // 控制层左上角：width: height:（随控制条显隐）
        controller.addControlComponent(new SizeComponent(videoView));
        // 控制层右上角：小窗 + 倍速 横排一行（随控制条显隐）
        floatBtns = new TopRightButtonsComponent(this);
        floatBtns.onSpeed = new View.OnClickListener() {
            @Override public void onClick(View v) { showSpeedMenu(); }
        };
        if (Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            floatBtns.onPip = new View.OnClickListener() {
                @Override public void onClick(View v) { enterPip(); }
            };
        }
        controller.addControlComponent(floatBtns);

        root.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupGestures();       // 双击暂停 / 长按倍速

        setContentView(root);
        Immersive.hide(this);
        if (url == null || url.length() == 0) {
            Toast.makeText(this, "无播放地址", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // 旧地址过期(403)时热替换为同一路直播的最新地址
        try { String f = Or4Helper.freshFor(url); if (f != null) url = f; } catch (Throwable ignored) {}
        // 录制进行中且是同一路流：改走本地代理，播放列表只由录制已抓取的内存分片组成
        // （秒进；播放器零上游请求，与录制完全不抢带宽。静默切换，不弹提示）
        // 匹配两级：① 文件名 key 精确匹配；② key 失配(录制中刷新变名/换CDN)时比对 stream-<id> 数字段
        try {
            if (!url.contains("127.0.0.1")) {
                java.util.ArrayList<String> cands = new java.util.ArrayList<>();
                if (LiveProxy.isRefresherActive() && LiveProxy.mediaUrl != null) cands.add(LiveProxy.mediaUrl);
                for (RecManager.RecJob j : RecManager.recJobs.values()) {
                    if (j.active && j.url != null && j.url.contains(".m3u8")) cands.add(j.url);
                }
                String curKey = Or4Helper.streamKey(url);
                String curId = streamId(url);
                for (String ru : cands) {
                    boolean match = curKey != null && curKey.equals(Or4Helper.streamKey(ru));
                    if (!match && curId != null) match = curId.equals(streamId(ru));
                    // 门槛：缓存有首片即切代理（录制预热已提速，首片1~2s内落地）；
                    // 缓存全空/录制断供时保持直连，绝不空等转圈
                    if (match && LiveProxy.cacheSegmentCount() >= 1) {
                        url = "http://127.0.0.1:" + LiveProxy.PORT + "/playlist.m3u8";
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        videoView.setUrl(url, douyinHeaders());
        // 连接预热：后台线程先向 CDN 发起一次请求，完成 DNS/TCP/TLS 握手。
        // Android 平台 HttpURLConnection 的 keep-alive 连接池是进程级共享的，
        // 播放器(默认 HttpDataSource / Ijk) 发起首个请求时可直接复用该连接，省 300-700ms。
        prewarmConnection(url);
        videoView.start();
        // 倍速在准备完成后才真正生效（BaseVideoView.setSpeed 仅在播放态生效）
        // 同时做运行时直播兜底：拿不到时长(duration<=0)的流按直播处理，动态关闭快进
        videoView.addOnStateChangeListener(new BaseVideoView.OnStateChangeListener() {
            @Override public void onPlayerStateChanged(int playerState) { }
            @Override public void onPlayStateChanged(int playState) {
                if (playState == VideoView.STATE_PREPARED || playState == VideoView.STATE_PLAYING) {
                    try {
                        // 兜底只针对网络流；本地成品文件即使时长异常也不关快进
                        boolean local = url != null &&
                                (url.startsWith("file://") || url.startsWith("content://"));
                        if (!isLive && !local && videoView.getDuration() <= 0) {
                            isLive = true;
                            try { controller.setCanChangePosition(false); } catch (Throwable ignored) {}
                            disableSeekUi();
                            playSpeed = 1.0f;
                            if (floatBtns != null && floatBtns.speedBtn != null)
                                floatBtns.speedBtn.setText(speedLabel(1.0f));
                        }
                    } catch (Throwable ignored) {}
                    try { videoView.setSpeed(isLive ? 1.0f : playSpeed); } catch (Throwable ignored) { }
                }
            }
        });
        showPlayNote();

    }

    // ---------- 播放通知 ----------
    private void showPlayNote() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel("xgj_player", "播放通知", android.app.NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(this, "xgj_player")
                    : new android.app.Notification.Builder(this);
            Intent back = new Intent(this, NativePlayerActivity.class)
                    .putExtra("url", url).putExtra("title", title);
            b.setSmallIcon(android.R.drawable.ic_media_play)
             .setContentTitle(title != null && !title.isEmpty() ? title : "正在播放")
             .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
             .setContentIntent(android.app.PendingIntent.getActivity(this, 5201, back,
                     android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
            nm.notify(5200, b.build());
        } catch (Throwable ignored) { }
    }

    private void cancelPlayNote() {
        try { ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(5200); } catch (Throwable ignored) { }
    }

    /** 耳机按钮：切后台播放模式。开启后退到桌面/切应用不停播 */
    void toggleBackgroundPlay() {
        backgroundPlay = !backgroundPlay;
        if (controller != null) controller.setBackgroundPlaySelected(backgroundPlay);
    }

    /**
     * 是否需要 Ijk 内核（FFmpeg 全能软解）：
     * 1) RTMP/RTSP 拉流 —— 系统 MediaPlayer 不支持，只有 Ijk 支持；
     * 2) 冷门老格式（MPEG-2/WMV/RMVB/AVI/ASF/VOB 等）—— 系统硬解不支持，Ijk 软解兜底。
     * 其余一律使用系统 AndroidMediaPlayer（默认硬解，省电低发热）。
     */
    static boolean needsIjkKernel(String url) {
        if (url == null || url.length() == 0) return false;
        String u = url.toLowerCase();
        if (u.startsWith("rtmp://") || u.startsWith("rtmps://") || u.startsWith("rtsp://")) return true;
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        String[] coldExts = {".wmv", ".rmvb", ".rm", ".mpg", ".mpeg", ".mpe", ".vob", ".asf", ".avi", ".swf", ".3gp", ".m2ts"};
        for (String ext : coldExts) {
            if (path.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * 是否直播流。直播无固定时长，快进（拖进度 / 左右滑动 seek / 长按倍速）都无意义，
     * 一律关闭。本地已下载/已录制的成品（file://、content://）与点播 mp4 保持可快进。
     * 覆盖：HLS(m3u8)、FLV、抖音直播 CDN 裸地址(/stage/)、流文件名(stream-)、
     * 录制中本地代理(playlist.m3u8)、RTMP/RTSP、直播域名。
     */
    static boolean isLiveUrl(String u) {
        if (u == null || u.length() == 0) return false;
        String s = u.toLowerCase();
        if (s.startsWith("file://") || s.startsWith("content://")) return false;  // 本地成品 = 点播
        if (s.startsWith("rtmp://") || s.startsWith("rtmps://") || s.startsWith("rtsp://")) return true;
        if (s.contains(".m3u8") || s.contains(".flv")) return true;
        if (s.contains("live.douyin.com") || s.contains("webcast") || s.contains("live-bvc")
                || s.contains("pull-flv") || s.contains("pull-hls") || s.contains("stream-")) return true;
        if ((s.contains("douyincdn") || s.contains("amemv")) && s.contains("/stage/")) return true;
        if (s.contains("127.0.0.1") && s.contains("playlist")) return true;      // 录制中本地代理
        return false;
    }

    /** 从 URL 提取 stream-<数字> 标识（跨 CDN/变名匹配同一路直播） */
    private static String streamId(String u) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("stream-[0-9]+").matcher(u);
            return m.find() ? m.group() : null;
        } catch (Throwable t) { return null; }
    }

    /** 连接预热：提前完成 DNS/TCP/TLS 握手，连接存入平台进程级 keep-alive 池供播放器复用 */
    private void prewarmConnection(String u) {
        if (u == null || !u.toLowerCase().startsWith("http")) return;
        if (u.contains("127.0.0.1")) return;   // 本地代理无需预热
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.net.URL uu = new java.net.URL(u);
                    javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection) uu.openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(3000);
                    c.setRequestProperty("Connection", "keep-alive");
                    java.io.InputStream is = c.getInputStream();
                    byte[] buf = new byte[256];
                    is.read(buf);   // 只读一小段即断开，目的仅是建立连接
                    is.close();
                    c.disconnect();
                } catch (Throwable ignored) { }
            }
        }, "conn-prewarm").start();
    }

    Map<String, String> douyinHeaders() {
        Map<String, String> hdrs = null;
        String extRef = getIntent() == null ? null : getIntent().getStringExtra("referer");
        if (extRef != null && !extRef.isEmpty()) {
            hdrs = new HashMap<String, String>();
            hdrs.put("Referer", extRef);
            hdrs.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        } else if (url != null && (url.contains("douyincdn") || url.contains("douyin"))) {
            hdrs = new HashMap<String, String>();
            hdrs.put("Referer", "https://live.douyin.com/");
            hdrs.put("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        }
        return hdrs;
    }

    /**
     * 直播态隐藏 VOD 控制栏里的进度条与时间轴（含运行时才识别出直播的情况），
     * 并把 SeekBar 设为不可拖动。id 取自 dkplayer 的 VOD 控制栏布局，找不到时安全跳过。
     */
    private void disableSeekUi() {
        if (controller == null) return;
        int[] ids = {R.id.seekBar, R.id.bottom_progress, R.id.curr_time, R.id.total_time};
        for (int id : ids) {
            try {
                View v = controller.findViewById(id);
                if (v == null) continue;
                v.setVisibility(View.GONE);
                v.setEnabled(false);
                if (v instanceof android.widget.SeekBar) {
                    v.setOnTouchListener(new View.OnTouchListener() {
                        @Override public boolean onTouch(View v, android.view.MotionEvent e) { return true; }
                    });
                }
            } catch (Throwable ignored) {}
        }
    }

    // ---------- 倍速 ----------
    private void showSpeedMenu() {
        if (isLive) {
            Toast.makeText(this, "直播不支持倍速/快进", Toast.LENGTH_SHORT).show();
            return;
        }
        PopupMenu pm = new PopupMenu(this, floatBtns != null ? floatBtns.speedBtn : null);
        for (int i = 0; i < SPEEDS.length; i++) {
            pm.getMenu().add(0, i, i, speedLabel(SPEEDS[i]));
        }
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                setPlaySpeed(SPEEDS[item.getItemId()]);
                return true;
            }
        });
        pm.show();
    }

    private void setPlaySpeed(float s) {
        if (isLive) s = 1.0f;   // 直播不允许任何倍速/快进
        playSpeed = s;
        if (floatBtns != null && floatBtns.speedBtn != null) floatBtns.speedBtn.setText(speedLabel(s));
        try { videoView.setSpeed(s); } catch (Throwable ignored) { }
    }

    // ---------- 手势：双击暂停 / 长按倍速 ----------
    private void setupGestures() {
        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                try {
                    if (videoView.isPlaying()) videoView.pause();
                    else videoView.resume();
                } catch (Throwable ignored) {}
                return true;
            }
            @Override
            public void onLongPress(MotionEvent e) {
                if (isLive) return;   // 直播禁止长按 2x 快进
                try { longPressSpeed = true; videoView.setSpeed(2.0f);
                      Toast.makeText(NativePlayerActivity.this, "2x 快进中，松开恢复", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            }
        });
    }

    private static String speedLabel(float s) {
        if (s == (int) s) return ((int) s) + "x";
        return s + "x";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- 画中画 ----------
    private void enterPip() {
        if (Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
            } catch (Throwable e) {
                Toast.makeText(this, "画中画不可用（可能在系统设置中被关闭）", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "此设备不支持画中画", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // 切到别的 App / 按 Home：自动进入画中画小窗，边看边干别的
        if (isFinishing()) return;
        enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPiP) {
        super.onPictureInPictureModeChanged(isInPiP);
        // 小窗内隐藏浮动按钮，避免 UI 错位
        if (floatBtns != null && floatBtns.getView() != null) {
            int vis = isInPiP ? View.INVISIBLE : View.VISIBLE;
            floatBtns.getView().setVisibility(vis);
            floatBtns.speedBtn.setVisibility(vis);
            if (floatBtns.pipBtn != null) floatBtns.pipBtn.setVisibility(vis);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 画中画模式下不暂停（小窗继续播）
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode()) return;
        if (!backgroundPlay) { videoView.pause(); cancelPlayNote(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!backgroundPlay) { videoView.resume(); showPlayNote(); }
    }

    @Override
    protected void onDestroy() {
        cancelPlayNote();
        ZoomHelper.clear();
        try { videoView.release(); } catch (Throwable e) { }
        super.onDestroy();
    }
}
