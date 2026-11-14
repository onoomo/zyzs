package com.downx.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.example.immersive.R;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 资源嗅探 —— 对齐 github.com/likesugar/Assistant 的 SniffActivity（main 分支）界面与抖音直播解析。
 *
 * 界面（对齐原版 activity_sniff.xml）：全屏 WebView + 浮动顶栏（地址 / 前往 / 刷新 / 切电脑UA / 记录）
 * + 右下角 FAB（≡，点屏幕出现、再点隐藏，点击切换顶栏）+ 底部记录面板（默认隐藏）。
 *
 * 抖音直播解析（对齐原版三层机制）：
 *   1) 默认进 live.douyin.com，并在首次进入时强制「桌面 UA」（手机 UA 下直播间不下发可嗅探流）；
 *   2) dygrab.js 在页面 DOM 里扫直播流（onDyUrl 回传）；
 *   3) douyinLiveExtract 直接拉直播间 HTML 按清晰度正则兜底提取（绕过验证页）；
 *   4) shouldInterceptRequest 截 douyincdn /stage/ 裸流与 aweme/v1/play 直链；
 *   5) douyin.user.js（评论过滤）+ vue 一并注入页面上下文。
 *
 * 播放：统一交给 PlayerActivity 的 IjkPlayer（FFmpeg 内核，原版同款 .so）；
 * MP4 / m3u8 / flv / 抖音直播通吃，不使用 WebView / WASM 播放页。
 *
 * 下载：接本工程真实下载引擎 DlService；广告屏蔽沿用 AdBlocker。
 */
public class SniffActivity extends Activity {

    // 媒体直链后缀 + 分片 / 流特征（原版）
    private static final Pattern MEDIA = Pattern.compile(
            "\\.(m3u8|mp4|flv|mkv|avi|ts|webm|mp3|m4a|aac|flac|mov)(\\?|$)|\\.ts\\?|/stream/|media-worker",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSET_EXT = Pattern.compile(
            "\\.(js|css|png|jpe?g|gif|webp|ico|woff2?|svg|ttf|eot|html?|xml|json)(\\?|$)",
            Pattern.CASE_INSENSITIVE);
    // 从任意文本抠第一个 http(s) 链接（含平台分享文案：中文描述 + 真实短链）
    private static final Pattern URL_IN_TEXT = Pattern.compile(
            "(https?://|www\\.)[\\w\\-./?:#=&%+~@!$'*;,\\[\\]]+", Pattern.CASE_INSENSITIVE);

    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    /** 默认进直播间（原版默认就是 live.douyin.com，不是 bilibili）。 */
    private static final String HOME = "https://live.douyin.com/";
    /** 抖音直播提取兜底 Cookie（无登录态时也能拉到房间 HTML）。 */
    private static final String DY_LIVE_COOKIE =
            "enter_pc_once=1; hevc_supported=true; ttwid=1%7COnZEYGAxHABx6WRfArV8V0vfh1qUfP8AU2WYpG2ybdU%7C1754493043%7C867b28541b24aca9aec6379357aa2bff731e159fa7a804a767f575c8ff886639; __ac_nonce=06893707d00e64c4488d5; __ac_signature=_02B4Z6wo00f01m0zFcQAAIDDRDeLuhFSmo5tExFAAPPu88; odin_tt=e0bcb4ad345d3ed6915b71cab9469cb459681b743f65d870cb52329adfa8792b80633cf01c31edd9cf4874b743daacc7b789efd1727202b7ed3f6e7059ce43a72f3358995fc8367000f0b42103a78d1b; passport_csrf_token=4d713363889176dba46a4d28394acf2f";

    private WebView webView;
    private EditText etUrl;
    private LinearLayout topBar;
    private LinearLayout recList;
    private ScrollView recScroll;
    private View fab;

    private boolean isMobileUa = true;
    private boolean isRecordsVisible = false;
    private boolean isTopBarVisible = true;
    private boolean dyDesktopSet = false;

    // 抖音解析状态
    private final List<String> douyinCands = new ArrayList<String>();
    private boolean douyinPickScheduled = false;
    private boolean douyinParsing = false;
    private String douyinLiveUrl = null;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> recordKeys = new LinkedHashSet<String>();
    private final List<Rec> recs = new ArrayList<Rec>();
    private String referer = "";

    private static final class Rec {
        String url, title, ref;
        Rec(String u, String t, String r) {
            url = u;
            ref = (r == null || r.isEmpty()) ? "" : r;
            title = t;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Immersive.apply(this);
        AdBlocker.load(this);
        buildUi();
        setupWeb();

        // 入口：分享进入 / 显式 url / 抖音直播开关，否则默认进直播间
        String pre = (getIntent() == null) ? null : getIntent().getStringExtra("input");
        if (pre == null || pre.isEmpty()) {
            String act = (getIntent() == null) ? null : getIntent().getAction();
            if ("android.intent.action.SEND".equals(act) || "android.intent.action.SEND_MULTIPLE".equals(act)) {
                pre = getIntent().getStringExtra("android.intent.extra.TEXT");
                if (pre == null) pre = "";
                String ex = extractUrl(pre);
                pre = (ex != null) ? ex : "";
            }
        }
        boolean live = (getIntent() != null) && getIntent().getBooleanExtra("live", false);
        String url = (getIntent() == null) ? null : getIntent().getStringExtra("url");
        if (pre != null && !pre.isEmpty()) {
            etUrl.setText(pre);
            openInputUrl();
        } else if (url != null && url.startsWith("http")) {
            etUrl.setText(url);
            openInputUrl();
        } else if (live) {
            webView.loadUrl(HOME);
            dyAutoPcClick();
        } else {
            webView.loadUrl(HOME);
            dyAutoPcClick();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 对齐原版 activity_sniff.xml：全屏 WebView + 浮动顶栏 + FAB + 底部记录面板。 */
    @SuppressLint("ClickableViewAccessibility")
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0f0f23);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右下角 FAB（≡）：默认隐藏，点屏幕出现，再点隐藏；点击切换顶栏
        FrameLayout fabWrap = new FrameLayout(this);
        fabWrap.setElevation(24f);
        fabWrap.setLayoutParams(new FrameLayout.LayoutParams(dp(36), dp(36),
                Gravity.BOTTOM | Gravity.END));
        ((FrameLayout.LayoutParams) fabWrap.getLayoutParams()).setMargins(dp(16), 0, dp(16), dp(216));
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setColor(0xCC1e1e3f);
        fabBg.setCornerRadius(dp(18));
        fabWrap.setBackground(fabBg);
        fabWrap.setVisibility(View.GONE);
        fabWrap.setClickable(true);
        fabWrap.setFocusable(true);
        TextView fabIcon = new TextView(this);
        fabIcon.setText("≡");
        fabIcon.setTextColor(Color.WHITE);
        fabIcon.setTextSize(18f);
        fabIcon.setTypeface(null, Typeface.BOLD);
        fabIcon.setGravity(Gravity.CENTER);
        fabWrap.addView(fabIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fabWrap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleTopBar(); }
        });
        fab = fabWrap;
        root.addView(fab);

        // 顶部浮动操作栏（半透明）
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(0x190f0f23);
        topBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        etUrl = new EditText(this);
        etUrl.setHint("输入网址 / 直播间链接 / 分享文案");
        etUrl.setHintTextColor(0xFF8a8ab0);
        etUrl.setTextColor(Color.WHITE);
        etUrl.setTextSize(13f);
        etUrl.setSingleLine(true);
        etUrl.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        etUrl.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        // 圆角胶囊底，确保在深色网页上一眼可见（对齐原版图1的地址输入框）
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xE6212140);
        pill.setCornerRadius(dp(19));
        etUrl.setBackground(pill);
        etUrl.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.addView(etUrl, new LinearLayout.LayoutParams(0, dp(36), 1f));

        Button btnGo = btn("↵", 0x4D1e90ff);
        btnGo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openInputUrl(); }
        });
        row.addView(btnGo);
        Button btnRefresh = btn("↻", 0x4D1677FF);
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { webView.reload(); }
        });
        row.addView(btnRefresh);
        final Button btnUa = btn("切电脑UA", 0x4D2ed573);
        btnUa.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchUa(btnUa); }
        });
        row.addView(btnUa);
        Button btnRec = btn("记录", 0x4Dff4757);
        btnRec.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleRecords(); }
        });
        row.addView(btnRec);

        // 底部记录面板（默认隐藏）
        FrameLayout bottomWrap = new FrameLayout(this);
        bottomWrap.setBackgroundColor(0x191a1a3e);
        recScroll = new ScrollView(this);
        recList = new LinearLayout(this);
        recList.setOrientation(LinearLayout.VERTICAL);
        recList.setPadding(dp(8), dp(8), dp(8), dp(8));
        recScroll.addView(recList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottomWrap.addView(recScroll, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        bottomWrap.setVisibility(View.GONE);
        bottomPanel = bottomWrap;
        root.addView(bottomPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);

        // 点屏幕（非可点击控件）→ FAB 显隐
        webView.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { x0 = e.getX(); y0 = e.getY(); }
                else if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (Math.abs(e.getX() - x0) < 12 && Math.abs(e.getY() - y0) < 12) {
                        fab.setVisibility(fab.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    }
                }
                return false;
            }
        });
        renderRecords();
    }

    private View bottomPanel;

    private Button btn(String text, int bg) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13f);
        b.setBackgroundColor(bg);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.setMargins(dp(8), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= 17) s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(UA_MOBILE);
        CookieManager.getInstance().setAcceptCookie(true);
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true); } catch (Throwable ignored) { }

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public String getUrl() { return ""; }
            @JavascriptInterface
            public String getScript() {
                StringBuilder sb = new StringBuilder();
                String vue = readAsset("vue.global.min.js");
                if (vue != null) sb.append(vue).append("\n;\n");
                String js = readAsset("douyin.user.js");
                if (js != null) sb.append(js);
                return sb.toString();
            }
            @JavascriptInterface
            public void onDyUrl(String u) {
                if (u == null || u.length() < 12) return;
                String url = u.replace("\\u002F", "/").replace("\\/", "/")
                        .replace("\\u0026", "&").replace("\\u003D", "=").replace("\\u003d", "=");
                final String fu = url;
                main.post(new Runnable() { public void run() { dyAccept(fu); } });
            }
            @JavascriptInterface
            public void onHlsJson(String data) { /* 预留：FC2 走不到这里 */ }
            @JavascriptInterface
            public void openNative(String u, String t) { /* 播放器内处理 */ }
        }, "AndroidPlayer");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView v, String title) {
                if (title != null && !title.isEmpty()) etUrl.setHint(title);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // 站内导航交给 WebView
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // 抖音直播：手机 UA 渲染后强制切桌面 UA 并刷新一次（仅一次），桌面 UA 才下发可嗅探流
                if (url != null && url.contains("live.douyin.com") && !dyDesktopSet) {
                    dyDesktopSet = true;
                    view.getSettings().setUserAgentString(UA_DESKTOP);
                    isMobileUa = false;
                    view.stopLoading();
                    view.loadUrl(url);
                    return;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && (url.contains("douyin.com") || url.contains("iesdouyin"))) {
                    injectDouyinScript();
                }
                if (url != null && url.contains("live.douyin.com")) {
                    final String fu = url;
                    boolean newRoom = (douyinLiveUrl == null || !douyinLiveUrl.equals(fu));
                    if (newRoom || !douyinParsing) {
                        if (newRoom) { synchronized (douyinCands) { douyinCands.clear(); } }
                        douyinLiveUrl = fu;
                        douyinParsing = true;
                        // 主路径：页面 DOM 扫描（800ms 后注入 dygrab.js）
                        main.postDelayed(new Runnable() { public void run() { injectDyGrabber(); } }, 800);
                        // 兜底：直接拉 HTML 解析（3s 后）
                        main.postDelayed(new Runnable() { public void run() {
                            douyinLiveExtract(fu, CookieManager.getInstance().getCookie("https://live.douyin.com"));
                        }}, 3000);
                        startDouyinWatchdog();
                    }
                } else {
                    douyinLiveUrl = null;
                    douyinParsing = false;
                }
                etUrl.setText(url);
            }

            /** 嗅探心脏：拦截每个请求，广告返回空响应，媒体直链 / 抖音流进记录。 */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (AdBlocker.shouldBlock(u)) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                if (!"GET".equalsIgnoreCase(req.getMethod())) return null;
                String scheme = req.getUrl().getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
                if (u.contains("/log/")) return null;
                try { maybeRecordDouyin(u); } catch (Throwable ignored) { }
                if (MEDIA.matcher(u).find() && !ASSET_EXT.matcher(u).find()) {
                    final String fu = u;
                    main.post(new Runnable() { public void run() { addRecord(fu, "", refererOf(fu)); } });
                }
                return null;
            }
        });
    }

    // ---------------- 入口 / 输入 ----------------

    void openInputUrl() {
        String raw = etUrl.getText().toString().trim();
        if (raw.length() == 0) return;
        String url = extractUrl(raw);
        if (url == null) {
            if (raw.matches("\\d+")) { url = "https://live.douyin.com/" + raw; }
            else { toast("没找到可用链接，请粘贴完整网址（如 https://v.douyin.com/…）", false); return; }
        }
        if (!url.startsWith("http")) url = "https://" + url;
        if (url.contains("v.douyin.com")) { resolveDouyinShort(url); return; }
        if (isDyLiveUrl(url)) dyAutoPcClick();
        webView.loadUrl(url);
    }

    /** 抖音短链（v.douyin.com）原生跟随 302，直达直播间地址。 */
    void resolveDouyinShort(final String shortUrl) {
        new Thread(new Runnable() { public void run() {
            String cur = shortUrl;
            try {
                for (int i = 0; i < 5; i++) {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(cur).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", UA_MOBILE);
                    int code = c.getResponseCode();
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (code >= 300 && code < 400 && loc != null && loc.length() > 0) {
                        cur = loc.startsWith("http") ? loc : new java.net.URL(new java.net.URL(cur), loc).toString();
                    } else break;
                }
            } catch (Throwable ignored) { }
            final String fu = cur;
            main.post(new Runnable() { public void run() {
                try { if (isMobileUa) dyAutoPcClick(); webView.loadUrl(fu); } catch (Throwable ignored) { }
            }});
        }}).start();
    }

    /** 从任意文本抠第一个真实 http(s) 链接（分享文案 / 裸域名都支持）。 */
    public static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_IN_TEXT.matcher(text);
        if (m.find()) {
            String u = m.group();
            if (u.startsWith("www.")) u = "https://" + u;
            return u;
        }
        return null;
    }

    static boolean isDyLiveUrl(String u) {
        return u != null && (u.contains("live.douyin.com") || u.contains("webcast.amemv.com"));
    }

    /** 抖音直播：手机 UA 渲染后自动点「切电脑UA」（含刷新），只切一次。 */
    void dyAutoPcClick() {
        main.postDelayed(new Runnable() { public void run() {
            try {
                if (isFinishing() || isDestroyed() || webView == null) return;
                String cur = webView.getUrl();
                if (!isDyLiveUrl(cur)) return;
                if (!isMobileUa) return;
                switchUa(null);
            } catch (Throwable ignored) { }
        }}, 300);
    }

    void switchUa(Button btnUa) {
        isMobileUa = !isMobileUa;
        webView.getSettings().setUserAgentString(isMobileUa ? UA_MOBILE : UA_DESKTOP);
        if (btnUa != null) btnUa.setText(isMobileUa ? "切电脑UA" : "切手机UA");
        webView.reload();
    }

    // ---------------- 抖音直播提取 ----------------

    /** shouldInterceptRequest 的抖音判定段：拦 douyincdn /stage/ 裸流与 aweme/v1/play 直链。 */
    void maybeRecordDouyin(final String url) {
        String l = url.toLowerCase();
        if (l.contains("doppiocdn.net") || l.contains("/ping.m3u8")) return;
        String base = url;
        int qi = base.indexOf('?');
        if (qi > 0) base = base.substring(0, qi);
        String lb = base.toLowerCase();
        boolean anyM3u8 = l.contains(".m3u8");
        boolean bareLive = lb.contains("douyincdn") && lb.contains("/stage/")
                && !lb.substring(lb.lastIndexOf('/') + 1).contains(".");
        boolean dyHost = l.contains("douyin") || l.contains("amemv") || l.contains("douyincdn");
        if (dyHost && (bareLive || anyM3u8)) {
            if (anyM3u8 && l.contains("_sd.")) return; // 标清不进记录
            final String f = base;
            boolean added = false;
            synchronized (douyinCands) {
                if (!douyinCands.contains(f)) { douyinCands.add(f); added = true; }
            }
            if (added && !douyinPickScheduled) {
                douyinPickScheduled = true;
                main.postDelayed(new Runnable() { public void run() { finishDouyinPick(); } }, 6000);
            }
            return;
        }
        boolean hit = (l.contains(".flv") || l.contains(".m3u8") || l.contains(".mp4") || l.contains(".m4s"))
                && (l.contains("douyinvod") || l.contains("/aweme/v1/play") || l.contains("playwm"));
        if (hit) {
            String hi = url
                    .replaceAll("ratio=[a-zA-Z0-9_]+", "ratio=1080p")
                    .replaceAll("biz_resolution(=|%3D|%3d)[a-zA-Z0-9_x]+", "biz_resolution$11088x1920")
                    .replaceAll("resolution(=|%3D|%3d)[a-zA-Z0-9_x]+", "resolution$11088x1920");
            synchronized (douyinCands) { if (!douyinCands.contains(hi)) douyinCands.add(hi); }
            if (!douyinPickScheduled) {
                douyinPickScheduled = true;
                main.postDelayed(new Runnable() { public void run() { finishDouyinPick(); } }, 6000);
            }
        }
    }

    /** 看门狗：8 秒内还没拿到画质流就重新提取，直到抓到为止。 */
    private final Runnable douyinWatchdog = new Runnable() {
        public void run() {
            if (!douyinParsing) return;
            boolean hasQuality = false;
            synchronized (douyinCands) {
                for (String u : douyinCands) if (u.contains("_or4") || u.contains("_uhd") || u.contains("_hd")) { hasQuality = true; break; }
            }
            if (!hasQuality && douyinLiveUrl != null) {
                injectDyGrabber();
                douyinLiveExtract(douyinLiveUrl, CookieManager.getInstance().getCookie("https://live.douyin.com"));
            }
            if (hasQuality && douyinPickScheduled) return;
            main.postDelayed(this, 1000);
        }
    };

    void startDouyinWatchdog() {
        main.removeCallbacks(douyinWatchdog);
        main.postDelayed(douyinWatchdog, 1000);
    }

    /** 候选全部入记录（带画质标签），原画优先。 */
    void finishDouyinPick() {
        List<String> snapshot;
        synchronized (douyinCands) { snapshot = new ArrayList<String>(douyinCands); }
        douyinPickScheduled = false;
        douyinParsing = false;
        if (snapshot.isEmpty()) return;
        java.util.Collections.sort(snapshot, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                boolean ao = a.contains("_or4"), bo = b.contains("_or4");
                if (ao != bo) return ao ? -1 : 1;
                boolean as = a.contains("_sd") || a.contains("_ld"), bs = b.contains("_sd") || b.contains("_ld");
                if (as != bs) return as ? 1 : -1;
                return 0;
            }
        });
        String pgTitle = "";
        try { pgTitle = webView.getTitle(); } catch (Throwable e) { }
        for (final String u : snapshot) {
            if (recordKeys.add("dy#" + u)) {
                final String q = douyinQuality(u);
                final String t = (pgTitle == null ? "" : pgTitle);
                main.post(new Runnable() { public void run() {
                    addRecord(u, "抖音·" + q, "");
                }});
            }
        }
        if (!isRecordsVisible) toggleRecords();
    }

    /** 页面内注入 dygrab.js：直接扫抖音真实 DOM 拿直播流地址。 */
    void injectDyGrabber() {
        try {
            String js = readAsset("dygrab.js");
            if (js == null || js.length() < 50) return;
            try { webView.evaluateJavascript("(function(){window.__dyGrab=0;})();", null); } catch (Throwable ignored) { }
            webView.evaluateJavascript(js, null);
        } catch (Throwable ignored) { }
    }

    /** 页面内注入 douyin.user.js（评论过滤）+ vue。 */
    void injectDouyinScript() {
        try {
            String script = getScriptBridge();
            if (script != null && script.length() > 200) webView.evaluateJavascript(script, null);
        } catch (Throwable ignored) { }
    }

    private String getScriptBridge() {
        StringBuilder sb = new StringBuilder();
        String vue = readAsset("vue.global.min.js");
        if (vue != null) sb.append(vue).append("\n;\n");
        String js = readAsset("douyin.user.js");
        if (js != null) sb.append(js);
        return sb.toString();
    }

    /** 收下 dygrab.js 上报的流地址（带画质，谁先到谁进记录）。 */
    void dyAccept(String u) {
        if (u == null || u.length() < 12) return;
        if (recordKeys.add("dy#" + u)) {
            addRecord(u, "抖音·" + douyinQuality(u), "");
            if (!isRecordsVisible) toggleRecords();
        }
        douyinParsing = false;
    }

    /** DK douyinLiveExtract：直接拉直播间页面按清晰度正则提取流地址（兜底提取方案）。 */
    private void douyinLiveExtract(final String liveUrl, final String cookie) {
        new Thread(new Runnable() { public void run() {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(liveUrl).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(10000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36");
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
                c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                String ck = (cookie != null && cookie.length() > 10) ? cookie : DY_LIVE_COOKIE;
                c.setRequestProperty("Cookie", ck);
                if (c.getResponseCode() != 200) { c.disconnect(); return; }
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close(); c.disconnect();
                String html = sb.toString();

                String[][] qualities = {
                        {"原画", "flv\",?\\s*\"?streamUrl\"?:?\\s*\"?(https?:[^\"']+flv[^\"']*)"},
                        {"蓝光", "\"(?:flv|full)\"\\s*:\\s*\"(https?:[^\"]+)\""},
                        {"超清", "\"uhd\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"(https?:[^\"]+)\""},
                        {"高清", "\"hd\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"(https?:[^\"]+)\""},
                        {"标清", "\"sd\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"(https?:[^\"]+)\""}
                };
                for (String[] q : qualities) {
                    Matcher pm = Pattern.compile(q[1]).matcher(html);
                    if (pm.find()) {
                        String u = pm.group(1).replace("\\u002F", "/").replace("\\/", "/");
                        final String fu = u;
                        main.post(new Runnable() { public void run() {
                            if (recordKeys.add("dy#" + fu)) {
                                addRecord(fu, "抖音·" + douyinQuality(fu), "");
                                if (!isRecordsVisible) toggleRecords();
                            }
                            douyinParsing = false;
                        }});
                        return;
                    }
                }
                html = html.replace("\\u0026", "&").replace("\\u002F", "/").replace("\\/", "/").replace("\\u003D", "=");
                String[] gens = {
                        "https?:[^\\s\"'<>]*?\\.m3u8[^\\s\"'<>]*",
                        "https?:[^\\s\"'<>]*?\\.flv[^\\s\"'<>]*"
                };
                for (String gp : gens) {
                    Matcher gm = Pattern.compile(gp).matcher(html);
                    if (gm.find()) {
                        String u = gm.group(0).replace("\\u002F", "/").replace("\\/", "/");
                        u = u.replaceAll("^[\"'\\[<]+", "").replaceAll("[\"'\\]>.,;)]+$", "");
                        final String fu = u;
                        main.post(new Runnable() { public void run() {
                            if (recordKeys.add("dy#" + fu)) {
                                addRecord(fu, "抖音·直播(兜底)", "");
                                if (!isRecordsVisible) toggleRecords();
                            }
                            douyinParsing = false;
                        }});
                        return;
                    }
                }
            } catch (Throwable ignored) { }
        }}).start();
    }

    /** 抖音清晰度后缀识别：_or4原画 _uhd蓝光 _hd高清 _sd标清 _ld流畅 _md极速。 */
    static String douyinQuality(String url) {
        String l = url.toLowerCase();
        if (l.contains("_or4.") || l.contains("/origin")) return "原画";
        if (l.contains("_uhd.")) return "蓝光";
        if (l.contains("_hd.")) return "高清";
        if (l.contains("_sd.")) return "标清";
        if (l.contains("_ld.")) return "流畅";
        if (l.contains("_md.")) return "极速";
        return "直播";
    }

    // ---------------- 记录 / 播放 / 下载 ----------------

    /** 抖音直播只显示原画/蓝光/高清（过滤 标清/流畅/极速），其余站按媒体类型前缀显示。 */
    void addRecord(final String url, final String title, final String ref) {
        if (url == null || url.isEmpty()) return;
        String l = url.toLowerCase();
        if (l.contains("douyin") || l.contains("amemv") || l.contains("douyincdn")) {
            if (l.contains("_ld.") || l.contains("_md.") || l.contains("_sd.")) return; // 低画质不进记录
        }
        if (!recordKeys.add(url)) return;
        final Rec rec = new Rec(url, title, ref);
        recs.add(0, rec);
        renderRecords();
        if (l.contains(".m3u8") || l.contains("playlist") || l.contains("/hls/")) {
            new Thread(new Runnable() { public void run() {
                try {
                    final M3u8Util.M3U8 m = M3u8Util.parseIndex(rec.url);
                    final String info = " · " + m.tsList.size() + "分片 · 约"
                            + M3u8Util.fmtDur((long) m.totalSeconds)
                            + ("live".equals(m.type) ? " · 直播" : "");
                    main.post(new Runnable() { public void run() {
                        rec.title = (title == null || title.isEmpty()) ? label(url) : title;
                        rec.title = rec.title + info;
                        renderRecords();
                    }});
                } catch (Throwable ignored) { }
            }}, "m3u8-probe").start();
        }
    }

    /** 媒体类型前缀 + 文件名，让 m3u8/mp4/flv 一眼可见。 */
    private static String label(String u) {
        String low = u.toLowerCase();
        String kind;
        if (low.contains(".m3u8") || low.contains("playlist") || low.contains("/hls/")) kind = "HLS";
        else if (low.contains(".mp3") || low.contains(".m4a") || low.contains(".aac") || low.contains(".flac")) kind = "音频";
        else if (low.contains(".flv")) kind = "FLV";
        else if (low.contains(".ts")) kind = "分片";
        else if (low.contains(".mp4") || low.contains(".mkv") || low.contains(".mov") || low.contains(".webm") || low.contains(".avi")) kind = "视频";
        else kind = "资源";
        String name = u;
        int q = name.indexOf('?');
        if (q > 0) name = name.substring(0, q);
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        if (name.length() > 44) name = name.substring(0, 44) + "…";
        return "[" + kind + "] " + name;
    }

    /** 对齐原版记录行：标题（点行=复制）+ 右侧 ⋮ 菜单按钮；⋮ 菜单提供 下载/播放/直播录制/删除。 */
    private void renderRecords() {
        if (recList == null) return;
        recList.removeAllViews();
        for (final Rec r : recs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.setBackgroundColor(0x33222244);

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView tv = new TextView(this);
            tv.setText(r.title == null || r.title.isEmpty() ? label(r.url) : r.title);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(13f);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setPadding(dp(2), dp(2), dp(2), dp(2));
            head.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 右侧 ⋮ 菜单按钮（对齐原版）
            TextView menuBtn = new TextView(this);
            menuBtn.setText("⋮");
            menuBtn.setTextColor(Color.WHITE);
            menuBtn.setTextSize(18f);
            menuBtn.setTypeface(null, Typeface.BOLD);
            menuBtn.setBackgroundColor(0x338899AA);
            menuBtn.setPadding(dp(12), dp(2), dp(12), dp(2));
            menuBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showRecMenu(v, r); }
            });
            head.addView(menuBtn);
            row.addView(head);

            TextView tvUrl = new TextView(this);
            tvUrl.setTextColor(0xFF8AB4F8);
            tvUrl.setTextSize(11f);
            tvUrl.setText(r.url);
            tvUrl.setMaxLines(1);
            tvUrl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(tvUrl);

            // 点行 = 复制（对齐原版：点条目复制，不删除）
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { copy(r.url); }
            });
            recList.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View div = new View(this);
            div.setBackgroundColor(0x55FFFFFF);
            div.setAlpha(0.15f);
            recList.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    /** 对齐原版 ⋮ 菜单：下载 / 播放 / 直播录制(或停止录制) / 删除。 */
    private void showRecMenu(View anchor, final Rec r) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        pm.getMenu().add("下载");
        pm.getMenu().add("播放");
        pm.getMenu().add(RecManager.isRecording(r.url) ? "停止录制" : "直播录制");
        pm.getMenu().add("删除");
        pm.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                String t = item.getTitle().toString();
                if (t.equals("下载")) download(r);
                else if (t.equals("播放")) play(r);
                else if (t.equals("直播录制")) {
                    RecManager.init(getApplicationContext());
                    RecManager.startRecJob(getApplicationContext(), r.url, r.title == null ? label(r.url) : r.title);
                    toast("已开始录制，存到 DownX/录制 目录", true);
                } else if (t.equals("停止录制")) {
                    RecManager.recStop(r.url);
                    toast("录制已结束", false);
                } else if (t.equals("删除")) {
                    recs.remove(r);
                    recordKeys.remove(r.url);
                    renderRecords();
                    toast("已删除", false);
                }
                return true;
            }
        });
        pm.show();
    }

    /** 播放：统一交给 PlayerActivity 的 IjkPlayer（FFmpeg 内核），不使用 WebView / WASM 播放页。 */
    private void play(Rec r) {
        try {
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra("url", r.url);
            i.putExtra("title", r.title);
            i.putExtra("referer", r.ref.isEmpty() ? refererOf(r.url) : r.ref);
            startActivity(i);
        } catch (Throwable t) {
            toast("无法播放：" + t.getMessage(), false);
        }
    }

    private void download(Rec r) {
        String name = fileNameOf(r.url);
        try {
            DlService.start(this, r.url, "", name, "video",
                    r.ref.isEmpty() ? refererOf(r.url) : r.ref,
                    Prefs.galleryVisible(this), "", refererOf(r.url), "资源嗅探",
                    String.valueOf(r.url.hashCode()));
            toast("已开始下载：" + name, false);
        } catch (Throwable t) {
            toast("无法启动下载：" + t.getMessage(), false);
        }
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("资源地址", s));
        toast("已复制下载地址", false);
    }

    private void openBrowser(String u) {
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(u)), "打开"));
        } catch (Throwable t) {
            toast("没有可用的浏览器", false);
        }
    }

    private void showFullUrl(final Rec r) {
        new AlertDialog.Builder(this)
                .setTitle("完整地址")
                .setMessage(r.url)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { copy(r.url); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void toggleRecords() {
        isRecordsVisible = !isRecordsVisible;
        if (bottomPanel != null) bottomPanel.setVisibility(isRecordsVisible ? View.VISIBLE : View.GONE);
    }

    private void toggleTopBar() {
        isTopBarVisible = !isTopBarVisible;
        if (topBar != null) topBar.setVisibility(isTopBarVisible ? View.VISIBLE : View.GONE);
    }

    private void showAdblockDialog() {
        final EditText input = new EditText(this);
        input.setHint("广告域名或 URL 关键词");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("广告屏蔽 · 已拦 " + AdBlocker.getBlockedCount() + " 次")
                .setView(input)
                .setPositiveButton("添加规则", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String k = input.getText().toString();
                        if (!k.trim().isEmpty()) {
                            AdBlocker.addCustomRule(SniffActivity.this, k);
                            toast("已添加屏蔽规则", false);
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private String readAsset(String name) {
        try {
            java.io.InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String refererOf(String url) {
        try {
            Uri u = Uri.parse(url);
            return u.getScheme() + "://" + u.getHost() + "/";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String extOf(String url) {
        String p = url.toLowerCase();
        String[] exts = {".m3u8", ".mp4", ".flv", ".mkv", ".webm", ".mov", ".avi",
                ".mp3", ".m4a", ".aac", ".flac", ".ts"};
        for (String e : exts) if (p.contains(e)) return e;
        return ".mp4";
    }

    private static String fileNameOf(String url) {
        String p = url;
        int q = p.indexOf('?');
        if (q > 0) p = p.substring(0, q);
        int s = p.lastIndexOf('/');
        String n = (s >= 0 && s < p.length() - 1) ? p.substring(s + 1) : "";
        if (n.isEmpty() || !n.contains(".")) {
            n = "sniff_" + System.currentTimeMillis() + extOf(url);
        }
        return n;
    }

    private void toast(String s, boolean longLen) {
        Toast.makeText(this, s, longLen ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(douyinWatchdog);
        super.onDestroy();
    }
}
