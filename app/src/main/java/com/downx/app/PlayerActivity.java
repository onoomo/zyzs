package com.downx.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.example.immersive.R;

import com.ziyuan.Assistant.GlassDialog;
import com.ziyuan.Assistant.SizeComponent;
import com.ziyuan.Assistant.TopRightButtonsComponent;
import com.ziyuan.Assistant.ZoomHelper;

import java.util.HashMap;
import java.util.Map;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import xyz.doikki.videoplayer.ijk.IjkPlayer;
import xyz.doikki.videoplayer.ijk.IjkPlayerFactory;
import xyz.doikki.videoplayer.player.BaseVideoView;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videocontroller.StandardVideoController;

/**
 * 播放器 —— 1:1 移植自 github.com/likesugar/Assistant 的 NativePlayerActivity：
 * 同款 dkplayer(VideoView) + StandardVideoController + SizeComponent + TopRightButtonsComponent，
 * 同款 ijkplayer(FFmpeg) 内核 .so，同款手势（双击暂停 / 长按倍速）、倍速菜单、
 * 画中画小窗、后台播放、播放通知。仅选源界面（首页「播放器」框入口）为本工程自有。
 */
public class PlayerActivity extends Activity {

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

    /** 是否由首页「播放器」框（无地址）进入：决定返回时回到选源界面还是直接退出 */
    private boolean enteredFromPicker = false;
    private boolean pickerShown = false;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(ThemeMode.apply(base));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (getIntent() != null) {
                url = getIntent().getStringExtra("url");
                title = getIntent().getStringExtra("title");
            }
            if (url == null || url.length() == 0) {
                enteredFromPicker = true;
                showPicker();   // 本工程自有入口：选播放源
            } else {
                initPlayerPage(null);
            }
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
        pickerShown = false;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        videoView = new VideoView(this);
        // 播放内核：统一使用 IjkPlayer（FFmpeg 内核，原版同款 .so），
        // 本工程未集成 Media3/ExoPlayer（离线环境无 AAR），MP4 / m3u8 / flv / 抖音直播均走 Ijk 软解
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
        controller = new StandardVideoController(this);
        controller.addDefaultControlComponent(title != null ? title : "资源嗅探", url != null && url.contains(".m3u8"));
        videoView.setVideoController(controller);
        // 双击播放/暂停统一由 Activity 层手势处理（先于子 View 触发，最可靠）。
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
        Immersive.apply(this);
        if (url == null || url.length() == 0) {
            Toast.makeText(this, "无播放地址", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        videoView.setUrl(url, douyinHeaders());
        videoView.start();
        // 倍速在准备完成后才真正生效（BaseVideoView.setSpeed 仅在播放态生效）
        videoView.addOnStateChangeListener(new BaseVideoView.OnStateChangeListener() {
            @Override public void onPlayerStateChanged(int playerState) { }
            @Override public void onPlayStateChanged(int playState) {
                if (playState == VideoView.STATE_PREPARED || playState == VideoView.STATE_PLAYING) {
                    try { videoView.setSpeed(playSpeed); } catch (Throwable ignored) { }
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
            Intent back = new Intent(this, PlayerActivity.class)
                    .putExtra("url", url).putExtra("title", title);
            b.setSmallIcon(android.R.drawable.ic_media_play)
             .setContentTitle(title != null && !title.isEmpty() ? title : "正在播放")
             .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
             .setContentIntent(PendingIntent.getActivity(this, 5201, back,
                     PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
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

    // ---------- 倍速 ----------
    private void showSpeedMenu() {
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
        playSpeed = s;
        if (floatBtns != null && floatBtns.speedBtn != null) floatBtns.speedBtn.setText(speedLabel(s));
        try { videoView.setSpeed(s); } catch (Throwable ignored) { }
    }

    private static String speedLabel(float s) {
        if (s == (int) s) return ((int) s) + "x";
        return s + "x";
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
                try { longPressSpeed = true; videoView.setSpeed(2.0f);
                      Toast.makeText(PlayerActivity.this, "2x 快进中，松开恢复", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            }
        });
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
        if (videoView == null) return;
        if (!backgroundPlay) { videoView.pause(); cancelPlayNote(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoView == null) return;
        if (!backgroundPlay) { videoView.resume(); showPlayNote(); }
    }

    @Override
    public void onBackPressed() {
        if (videoView != null) {   // 播放态：返回退出（原版行为）
            cancelPlayNote();
            ZoomHelper.clear();
            try { videoView.release(); } catch (Throwable e) { }
            videoView = null;
            super.onBackPressed();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        cancelPlayNote();
        ZoomHelper.clear();
        if (videoView != null) { try { videoView.release(); } catch (Throwable e) { } }
        super.onDestroy();
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ---------------- 选播放源界面（首页「播放器」框入口，本工程自有） ----------------

    /** 无地址时显示的入口：粘贴视频 / 直播流地址，或点选已下载文件。跟随明暗主题。 */
    private void showPicker() {
        pickerShown = true;
        final int bg = ContextCompat.getColor(this, R.color.set_bg);
        final int card = ContextCompat.getColor(this, R.color.set_card);
        final int text = ContextCompat.getColor(this, R.color.set_text);
        final int dim = ContextCompat.getColor(this, R.color.tab_normal);
        final int accent = ContextCompat.getColor(this, R.color.tab_selected);
        final int stroke = ContextCompat.getColor(this, R.color.set_stroke);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶栏：返回 + 标题
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextColor(text);
        back.setTextSize(26f);
        back.setPadding(0, 0, dp(14), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back);
        TextView h = new TextView(this);
        h.setText("播放器");
        h.setTextColor(text);
        h.setTextSize(19f);
        h.setTypeface(null, Typeface.BOLD);
        bar.addView(h);
        root.addView(bar, lpWrap());

        // 网络地址 / 直播流
        TextView sec1 = smallLabel("粘贴视频 / 直播流地址", dim);
        sec1.setPadding(0, dp(22), 0, dp(10));
        root.addView(sec1, lpWrap());

        final EditText et = new EditText(this);
        et.setHint("https://… 支持 mp4 / m3u8 / 直播流");
        et.setHintTextColor(dim);
        et.setTextColor(text);
        et.setTextSize(14f);
        et.setSingleLine(true);
        et.setBackground(roundRect(card, stroke));
        et.setPadding(dp(14), dp(13), dp(14), dp(13));
        root.addView(et, lpFull());

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);
        TextView paste = pill("粘贴", accent);
        paste.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String s = readClip();
                if (s.isEmpty()) { toast("剪贴板里没有链接"); return; }
                et.setText(s);
                et.setSelection(s.length());
            }
        });
        btnRow.addView(paste, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = pill("播放", accent);
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        goLp.setMargins(dp(10), 0, 0, 0);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String s = et.getText().toString().trim();
                if (s.isEmpty()) { toast("请先粘贴或输入地址"); return; }
                String u = SniffActivity.extractUrl(s);
                if (u.isEmpty()) {
                    toast("没找到可用链接，请粘贴完整网址（如 https://v.douyin.com/…）");
                    return;
                }
                play(u, "网络播放", "");
            }
        });
        btnRow.addView(go, goLp);
        root.addView(btnRow, lpFull());

        // 本地已下载文件
        TextView sec2 = smallLabel("已从媒体管理下载的文件", dim);
        sec2.setPadding(0, dp(26), 0, dp(10));
        root.addView(sec2, lpWrap());

        int count = 0;
        try {
            java.util.List<HistoryStore.Entry> all = HistoryStore.list(this);
            for (final HistoryStore.Entry e : all) {
                if (e == null || !isPlayable(e.name)) continue;
                if (!Store.exists(this, e)) continue;
                final String u = Store.playUri(this, e).toString();
                final String nm = e.name;
                TextView item = new TextView(this);
                item.setText("\u25b6  " + nm);
                item.setTextColor(text);
                item.setTextSize(14f);
                item.setSingleLine(true);
                item.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                item.setBackground(roundRect(card, stroke));
                item.setPadding(dp(14), dp(14), dp(14), dp(14));
                item.setClickable(true);
                item.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { play(u, nm, ""); }
                });
                LinearLayout.LayoutParams ilp = lpFull();
                ilp.setMargins(0, 0, 0, dp(8));
                root.addView(item, ilp);
                count++;
            }
        } catch (Throwable ignored) { }
        if (count == 0) {
            TextView empty = smallLabel("还没有已下载的文件，可先到「视频下载」保存", dim);
            empty.setPadding(dp(2), dp(6), dp(2), dp(6));
            root.addView(empty, lpWrap());
        }

        setContentView(scroll);
    }

    /** 选好播放源（网络 / 直播地址或本地文件）后切入播放。 */
    private void play(String u, String t, String r) {
        url = nz(u);
        title = nz(t);
        if (getIntent() == null) setIntent(new Intent());
        getIntent().putExtra("referer", r);
        initPlayerPage(null);
    }

    private static boolean isPlayable(String n) {
        if (n == null) return false;
        String s = n.toLowerCase(java.util.Locale.ROOT);
        return s.endsWith(".mp4") || s.endsWith(".mkv") || s.endsWith(".webm")
                || s.endsWith(".mov") || s.endsWith(".m3u8") || s.endsWith(".flv")
                || s.endsWith(".ts") || s.endsWith(".mp3") || s.endsWith(".m4a")
                || s.endsWith(".aac") || s.endsWith(".flac") || s.endsWith(".wav");
    }

    private TextView smallLabel(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(13f);
        return t;
    }

    private GradientDrawable roundRect(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private TextView pill(String s, int accent) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14f);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(accent);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private LinearLayout.LayoutParams lpWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpFull() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 只读剪贴板文本；不自动识别、不自动播放，仅由「粘贴」按钮触发。 */
    private String readClip() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null) return "";
            ClipData d = cm.getPrimaryClip();
            if (d.getItemCount() == 0) return "";
            CharSequence c = d.getItemAt(0).getText();
            return c == null ? "" : c.toString().trim();
        } catch (Throwable t) { return ""; }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
