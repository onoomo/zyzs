package com.ziyuan.Assistant;

import com.example.immersive.R;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ClipData;
import android.content.Intent;
import android.content.ClipboardManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 嗅探页：复刻抖音直播解析主页面（WebView + 顶栏 + 右下角半透明FAB + 记录面板） */
public class SniffActivity extends Activity {

    private static final Pattern MEDIA = Pattern.compile(
        "\\.(m3u8|mp4|flv|mkv|avi|ts|webm|mp3|m4a|aac|flac|mov)(\\?|$)|\\.ts\\?|/stream/|media-worker", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_IN_TEXT = Pattern.compile(
        "(https?://|www\\.)[\\w\\-./?:#=&%+~@!$'*;,\\[\\]]+", Pattern.CASE_INSENSITIVE);

    private WebView webView;
    private boolean dyDesktopSet = false;
    private EditText etUrl;
    private LinearLayout topBar;
    private LinearLayout bottomPanel;
    private LinearLayout layoutRecords;
    private View fab;
    private Button btnSwitchUa;
    private Button btnRefresh;

    private boolean isMobileUa = false;
    private boolean isRecordsVisible = false;
    private boolean isTopBarVisible = true;

    private final ArrayList<String> foundUrls = new ArrayList<>();
    private volatile boolean diagActive = false;
    private static void dumpDiag(String st) {
        try {
            java.io.File dir = sDumpCtx.getExternalFilesDir(null).getParentFile();
            java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(dir, "网页诊断.txt"), true);
            fw.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                + " " + st + "\n----------------\n");
            fw.close();
        } catch (Throwable ignored) {}
    }
    private final java.util.Set<String> recordKeys = java.util.Collections.synchronizedSet(new LinkedHashSet<String>());
    // 同一路流按画质去重：baseKey -> [rank, url, tv, tvUrl]
    private final java.util.HashMap<String, Object[]> recByBase = new java.util.HashMap<>();
    static volatile int biliSeq = 100;  // B站直播刷新序号：每次master/url递增，新地址原地替换旧地址

    static int qualRank(String u) {
        if (u.contains("_or4")) return 3;
        if (u.contains("_uhd")) return 2;
        if (u.contains("_hd")) return 1;
        return 0;
    }

    static String qualName(int r) {
        if (r >= 3) return "原画";
        if (r == 2) return "蓝光";
        if (r == 1) return "高清";
        return null;
    }

    static String baseKey(String url) {
        return url.replace("_or4", "\u0001").replace("_uhd", "\u0001").replace("_hd", "\u0001");
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Immersive.hide(this);
        sDumpCtx = this;
        try { LiveProxy.start(); } catch (Throwable ignored) {}   // 本地中转必须常驻，FC2/B站记录才能播/录/下
        DlManager.init(this);
        setContentView(R.layout.activity_sniff);
        dumpFc2Debug("ACT CREATE act=" + hashCode());
        AdBlocker.load(this);


        webView = findViewById(R.id.webview);
        etUrl = findViewById(R.id.et_url);
        topBar = findViewById(R.id.top_bar);
        bottomPanel = findViewById(R.id.bottom_panel);
        layoutRecords = findViewById(R.id.layout_records);
        fab = findViewById(R.id.btn_toggle_top);
        fab.setElevation(100f);
        btnSwitchUa = findViewById(R.id.btn_switch_ua);
        Button btnGo = findViewById(R.id.btn_go);
        Button btnRecords = findViewById(R.id.btn_records);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAutoRefresh();
    }
        });

        btnRefresh.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) { showAdblockMgr(); return true; }
        });

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setUserAgentString(UA_MOBILE); // 手机UA：抖音直播流按移动端下发
        isMobileUa = true;
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setDatabaseEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public String getUrl() { return playerUrl; }
            @android.webkit.JavascriptInterface
            public String getScript() {
                StringBuilder sb = new StringBuilder();
                String vue = readAsset("vue.global.min.js");
                if (vue != null) sb.append(vue).append("\n;\n");
                String js = null;
                try {
                    java.io.File f = new java.io.File(getFilesDir(), "douyin.user.js");
                    if (f.exists() && f.length() > 1000) js = readTextFile(f);
                } catch (Throwable e) { }
                if (js == null) js = readAsset("douyin.user.js");
                if (js != null) sb.append(js);
                return sb.toString();
            }
            @android.webkit.JavascriptInterface
            public void onWsUrl(String u) {
                // 已改为窃听页面自身连接的回包（onHlsJson），不再自建连接避免多连接踢线
            }

            @android.webkit.JavascriptInterface
            public void onDyUrl(String u) {
                // dygrab.js 在页面上下文扫到的抖音直播流地址（可能是转义过的）
                if (u == null || u.length() < 12) return;
                String url = u.replace("\\u002F", "/").replace("\\/", "/")
                        .replace("\\u0026", "&").replace("\\u003D", "=").replace("\\u003d", "=");
                final String fu = url;
                main.post(new Runnable() { public void run() { dyAccept(fu); } });
            }

            @android.webkit.JavascriptInterface
            public void onHlsJson(String data) {
                if (data == null || !data.contains("url")) return;
                String best = null; int bestMode = -1;
                try {
                    java.util.regex.Matcher mu = java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(data);
                    java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\"mode\"\\s*:\\s*(\\d+)").matcher(data);
                    java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
                    java.util.ArrayList<Integer> modes = new java.util.ArrayList<Integer>();
                    while (mu.find()) urls.add(mu.group(1).replace("\\/", "/"));
                    while (mm.find()) modes.add(Integer.parseInt(mm.group(1)));
                    for (int i = 0; i < urls.size() && i < modes.size(); i++) {
                        int m = modes.get(i);
                        int cmp = m >= 90 ? m - 90 : m;
                        if (cmp > bestMode) { bestMode = cmp; best = urls.get(i); }
                    }
                } catch (Throwable ignored) {}
                if (best == null) return;
                final String hls = best;
                main.post(new Runnable() { public void run() {
                    try {
                        if (fc2HlsSeen.add(hls)) {
                            addRecord(hls, "FC2·直播");
                            if (!isRecordsVisible) toggleRecords();
                            dumpFc2Debug("记录已添加: " + hls);
                        }
                    } catch (Throwable e) { dumpFc2Debug("记录添加失败: " + e.getClass().getSimpleName()); }
                }});
            }
        }, "AndroidPlayer");

        webView.getSettings().setSupportMultipleWindows(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // 吞掉所有新窗口/弹窗广告(点播放触发的 popunder)
                android.util.Log.w("Sniff", "POP-SWALLOW gesture=" + isUserGesture);
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // 直链媒体: 不在 WebView 里打开(会黑页/跳走), 直接进播放器
                    String mediaUrl = u.toString();
                    // 命中广告特征的跳转链(如 wheelroot /api/v1/direct): 后台跟随302甄别
                    // 真媒体→带Referer进播放器; 真广告→丢弃不跳
                    if (AdBlocker.shouldBlock(mediaUrl)) {
                        android.util.Log.w("Sniff", "NAV-PROBE " + mediaUrl);
                        final String mu = mediaUrl;
                        WebView wv = webView;
                        final String ref = (wv == null || wv.getUrl() == null) ? "" : wv.getUrl();
                        new Thread(new Runnable() { public void run() {
                            try {
                                String cu = mu;
                                for (int i = 0; i < 5; i++) {
                                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(cu).openConnection();
                                    c.setInstanceFollowRedirects(false);
                                    c.setRequestMethod("GET");
                                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                                    if (!ref.isEmpty()) c.setRequestProperty("Referer", ref);
                                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                                    int code = c.getResponseCode();
                                    String loc = c.getHeaderField("Location");
                                    String ct = c.getHeaderField("Content-Type");
                                    c.disconnect();
                                    if (ct != null && (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("mpegurl") || ct.contains("octet-stream"))) {
                                        final String fu = cu; runOnUiThread(new Runnable() { public void run() { pendingRef = ref; playUrl(fu); } });
                                        return;
                                    }
                                    if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && loc != null) {
                                        cu = new java.net.URL(new java.net.URL(cu), loc).toString();
                                        if (MEDIA.matcher(cu).find()) {
                                            final String fu2 = cu; runOnUiThread(new Runnable() { public void run() { pendingRef = ref; playUrl(fu2); } });
                                            return;
                                        }
                                        continue;
                                    }
                                    break;
                                }
                                // 最终不是媒体 → 广告, 丢弃
                            } catch (Throwable ignored) { }
                        } }).start();
                        return true;
                    }
                    if (MEDIA.matcher(mediaUrl).find()) {
                        playUrl(mediaUrl);
                        return true;
                    }
                    // host 无点号说明是相对路径被浏览器补全的假链接（如 webcast_room/），忽略
                    String h = u.getHost() == null ? "" : u.getHost();
                    return !h.contains(".");
                }
                // 非 http(s) 协议：不拉起外部、不询问；剩余部分带域名才转 https，否则忽略
                String s2 = u.toString();
                String rest = s2.replaceAll("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
                int slash = rest.indexOf('/');
                String host = slash >= 0 ? rest.substring(0, slash) : rest;
                if (host.indexOf('.') >= 0) {
                    view.loadUrl("https://" + rest);
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (AdBlocker.shouldBlock(request.getUrl().toString()))
                        return new WebResourceResponse("text/plain", "utf-8",
                                new java.io.ByteArrayInputStream(new byte[0]));
                } catch (Throwable ignored) {}
                try { maybeRecordGeneric(request.getUrl().toString(), request.getMethod()); } catch (Throwable ignored) {}
                try {
                    if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
                    Uri u = request.getUrl();
                    if (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme())) return null;
                    String url = u.toString();
                    if (url.contains("/log/")) return null;
                    if (url.contains("guangdongvideo.com") || url.contains("live.fc2.com")) {
                        String lu = url.toLowerCase();
                        boolean isMaster = lu.contains("master_playlist");
                        if (lu.contains(".m3u8") || lu.contains("playlist") || lu.contains("hls") || lu.contains(".ts")) {
                            dumpFc2Debug("REQ: " + url);
                        }
                        if (isMaster) {
                            // 画质优先 90 其次 40：从 master 推导对应子列表，探测可用后进记录
                            final String fUrl = url;
                            final String chKey = url.substring(0, url.indexOf('?') > 0 ? url.indexOf('?') : url.length());
                            new Thread(new Runnable() { public void run() {
                                String pick = pickFc2Quality(fUrl);
                                String qLabel = bestFc2Name != null && bestFc2Name.length() > 0 ? bestFc2Name : "直播";
                                if (pick != null && pick.contains("/90/")) qLabel = "原画";
                                else if (pick != null && pick.contains("/40/")) qLabel = "高清";
                                final String titleQ = "FC2·" + qLabel;
                                if (pick == null) pick = fUrl;
                                final String pickUrl = pick;
                                String proxied;
                                try { proxied = "http://127.0.0.1:8123/relay?u=" + java.net.URLEncoder.encode(pickUrl, "UTF-8"); }
                                catch (Throwable e2) { proxied = pickUrl; }
                                final String proxiedF = proxied;
                                main.post(new Runnable() { public void run() {
                                    try {
                                        if (fc2HlsSeen.add(chKey)) {
                                            addRecord(proxiedF, titleQ);
                                            if (!isRecordsVisible) toggleRecords();
                                            dumpFc2Debug("记录已添加: " + proxiedF);
                                        }
                                    } catch (Throwable e) { dumpFc2Debug("记录添加失败: " + e.getClass().getSimpleName()); }
                                }});
                            } }).start();
                        }
                    }
                    boolean fc2Doc = (url.contains("live.fc2.com") || url.contains("guangdongvideo.com"))
                        && !url.contains(".js") && !url.contains(".css") && !url.contains(".png")
                        && !url.contains(".jpg") && !url.contains(".gif") && !url.contains(".ico");
                    if (fc2Doc && (request.isForMainFrame() || url.contains(".php") || url.endsWith("/")
                        || url.toLowerCase().contains("embed") || url.toLowerCase().contains("player"))) {
                        // FC2 系主文档：提前装 WebSocket 钩子
                        try {
                            java.net.HttpURLConnection hc = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                            hc.setConnectTimeout(10000); hc.setReadTimeout(15000);
                            hc.setRequestProperty("User-Agent", view.getSettings().getUserAgentString());
                            if (hc.getResponseCode() == 200) {
                                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                                java.io.InputStream in = hc.getInputStream();
                                byte[] b2 = new byte[8192]; int n2;
                                while ((n2 = in.read(b2)) > 0) bo.write(b2, 0, n2);
                                in.close(); hc.disconnect();
                                String html = bo.toString("UTF-8");
                                String hook = "<script>(function(){if(window.__fc2Hooked)return;window.__fc2Hooked=1;"
                                    + "var OW=window.WebSocket;"
                                    + "function NW(u,p){var ws=(p===undefined)?new OW(u):new OW(u,p);"
                                    + "try{ws.addEventListener('message',function(ev){"
                                    + "try{if(typeof ev.data==='string'&&ev.data.indexOf('playlists')>=0){AndroidPlayer.onHlsJson(ev.data);}}catch(e){}"
                                    + "});}catch(e){}"
                                    + "return ws;}"
                                    + "NW.prototype=OW.prototype;NW.CONNECTING=OW.CONNECTING;NW.OPEN=OW.OPEN;NW.CLOSING=OW.CLOSING;NW.CLOSED=OW.CLOSED;"
                                    + "window.WebSocket=NW;})();</script>";
                                int hp = html.indexOf("<head>");
                                html = hp >= 0 ? html.substring(0, hp + 6) + hook + html.substring(hp + 6) : hook + html;
                                return new android.webkit.WebResourceResponse("text/html", "UTF-8",
                                    new java.io.ByteArrayInputStream(html.getBytes("UTF-8")));
                            }
                            hc.disconnect();
                        } catch (Throwable ignored) {}
                    }
                    if (kbState[0] == 0 && kbArmed && url.toLowerCase().contains(".ts") && (url.contains("/stream/") || url.contains("douyincdn") || url.contains("amemv"))) {
                        kbArmed = false;
                        startKb(SniffActivity.this);
                    }
                    if (kbState[0] == 1 && kbSeen.add(url) && url.toLowerCase().contains(".ts")) {
                        try {
                            java.net.HttpURLConnection hc = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                            hc.setConnectTimeout(8000); hc.setReadTimeout(8000);
                            hc.setRequestProperty("User-Agent", view.getSettings().getUserAgentString());
                            hc.setRequestProperty("Referer", "https://live.douyin.com/");
                            if (hc.getResponseCode() == 200) {
                                java.io.InputStream hin = hc.getInputStream();
                                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                                byte[] bb = new byte[65536]; int nn;
                                while ((nn = hin.read(bb)) > 0) bo.write(bb, 0, nn);
                                hin.close();
                                byte[] body = bo.toByteArray();
                                if (kbOut != null) { kbOut.write(body); kbOut.flush(); }
                                return new android.webkit.WebResourceResponse("video/mp2t", null, new java.io.ByteArrayInputStream(body));
                            }
                        } catch (Throwable e) { }
                    }
                    // B站直播（PC 播放器）：截 play-gateway/master/url 响应，正则提取流地址
                    if (url.contains("api.live.bilibili.com") && url.contains("play-gateway/master/url")) {
                        try {
                            java.net.HttpURLConnection hc = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                            hc.setConnectTimeout(10000); hc.setReadTimeout(15000);
                            for (java.util.Map.Entry<String, String> h : request.getRequestHeaders().entrySet()) {
                                String k = h.getKey(), v = h.getValue();
                                if (k.equalsIgnoreCase("cookie")) hc.setRequestProperty("Cookie", v);
                                else if (!k.equalsIgnoreCase("accept-encoding") && !k.equalsIgnoreCase("host") && !k.equalsIgnoreCase("connection"))
                                    hc.setRequestProperty(k, v);
                            }
                            if (hc.getResponseCode() == 200) {
                                java.io.InputStream hin = hc.getInputStream();
                                java.io.ByteArrayOutputStream bo2 = new java.io.ByteArrayOutputStream();
                                byte[] bb = new byte[8192]; int nn;
                                while ((nn = hin.read(bb)) > 0) bo2.write(bb, 0, nn);
                                hin.close(); hc.disconnect();
                                byte[] body2 = bo2.toByteArray();
                                final String jstr = new String(body2, "UTF-8");
                                dumpFc2Debug("MASTERURL head: " + jstr.substring(0, Math.min(300, jstr.length())));
                                String qn = "直播";
                                java.util.regex.Matcher qm = java.util.regex.Pattern.compile("\"current_qn\"\\s*:\\s*(\\d+)").matcher(jstr);
                                if (!qm.find()) qm = java.util.regex.Pattern.compile("\"qn\"\\s*:\\s*(\\d+)").matcher(jstr);
                                if (qm.find()) {
                                    long q = Long.parseLong(qm.group(1));
                                    qn = q >= 30000 ? "杜比" : q >= 25000 ? "4K" : q >= 10000 ? "原画" : q >= 400 ? "蓝光" : q >= 250 ? "超清" : q >= 150 ? "高清" : "流畅";
                                }
                                biliSeq++;
                                final String qTitle = "B站直播·" + qn;
                                java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
                                java.util.regex.Matcher mm = java.util.regex.Pattern
                                        .compile("https?:\\/\\/[^\"\\\\\\s,]+")
                                        .matcher(jstr);
                                while (mm.find()) {
                                    String cand = mm.group().replace("\\/", "/");
                                    if (cand.contains("gotcha")) continue;  // 过滤P2P/PCDN线路
                                    if (cand.contains("live-bvc") || cand.contains(".m3u8") || cand.contains(".flv")) urls.add(cand);
                                }
                                for (final String su : urls) {
                                    runOnUiThread(new Runnable() { public void run() {
                                        try { if (recordKeys.add("bili#" + su)) addRecord(su, qTitle); } catch (Throwable ignored) {}
                                    }});
                                }
                                dumpFc2Debug("MASTERURL: " + urls.size() + " urls");
                                return new android.webkit.WebResourceResponse("application/json", "UTF-8",
                                        new java.io.ByteArrayInputStream(body2));
                            }
                        } catch (Throwable t) { dumpFc2Debug("MASTERURL fail " + t); }
                    }
                    // B站直播：截 getRoomPlayInfo 的 JSON 响应，直接解析流地址进记录
                    if (url.contains("api.live.bilibili.com") && url.contains("getRoomPlayInfo")) {
                        try {
                            java.net.HttpURLConnection hc = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                            hc.setConnectTimeout(10000); hc.setReadTimeout(15000);
                            for (java.util.Map.Entry<String, String> h : request.getRequestHeaders().entrySet()) {
                                String k = h.getKey(), v = h.getValue();
                                if (k.equalsIgnoreCase("cookie")) hc.setRequestProperty("Cookie", v);
                                else if (!k.equalsIgnoreCase("accept-encoding") && !k.equalsIgnoreCase("host") && !k.equalsIgnoreCase("connection"))
                                    hc.setRequestProperty(k, v);
                            }
                            int code = hc.getResponseCode();
                            if (code == 200) {
                                java.io.InputStream hin = hc.getInputStream();
                                java.io.ByteArrayOutputStream bo2 = new java.io.ByteArrayOutputStream();
                                byte[] bb = new byte[8192]; int nn;
                                while ((nn = hin.read(bb)) > 0) bo2.write(bb, 0, nn);
                                hin.close(); hc.disconnect();
                                byte[] body2 = bo2.toByteArray();
                                final String jstr = new String(body2, "UTF-8");
                                new Thread(new Runnable() { public void run() {
                                    try {
                                        org.json.JSONObject root = new org.json.JSONObject(jstr);
                                        org.json.JSONArray list = root.optJSONObject("data") == null ? null
                                                : root.optJSONObject("data").optJSONObject("play_url_info") == null ? null
                                                : root.optJSONObject("data").optJSONObject("play_url_info").optJSONArray("play_url_list");
                                        java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
                                        if (list != null) {
                                            for (int i = 0; i < list.length(); i++) {
                                                org.json.JSONObject item = list.optJSONObject(i);
                                                if (item == null) continue;
                                                String bu = item.optString("base_url", "");
                                                String ex = item.optString("extra", "");
                                                if (bu.startsWith("http")) urls.add(bu + (ex.startsWith("?") ? ex : ""));
                                            }
                                        }
                                        if (urls.isEmpty()) {  // 结构兜底：正则抓 live-bvc 地址
                                            java.util.regex.Matcher mm = java.util.regex.Pattern
                                                    .compile("https?:\\/\\/[^\"\\\\\\s]+(?:live-bvc|\\.m3u8|\\.flv)[^\"\\\\\\s]*")
                                                    .matcher(jstr);
                                            while (mm.find()) {
                                                String g = mm.group().replace("\\/", "/");
                                                if (g.contains("gotcha")) continue;  // 过滤P2P/PCDN线路
                                                urls.add(g);
                                            }
                                        }
                                        for (final String su : urls) {
                                            if (su.contains("live-bvc") || su.contains(".m3u8") || su.contains(".flv")) {
                                                runOnUiThread(new Runnable() { public void run() {
                                                    try { if (recordKeys.add("bili#" + su)) addRecord(su, "B站直播"); } catch (Throwable ignored) {}
                                                }});
                                            }
                                        }
                                        dumpFc2Debug("ROOMPLAY: " + urls.size() + " urls");
                                    } catch (Throwable t) { dumpFc2Debug("ROOMPLAY err " + t); }
                                }}).start();
                                return new android.webkit.WebResourceResponse("application/json", "UTF-8",
                                        new java.io.ByteArrayInputStream(body2));
                            }
                        } catch (Throwable t) { dumpFc2Debug("ROOMPLAY fail " + t); }
                    }
                    maybeRecordDouyin(url);
                    if (isMediaUrl(url) && recordKeys.add(url)) {
                        foundUrls.add(url);
                        final String page = webView.getTitle();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                addRecord(url, page == null ? "" : page);
                                if (!isRecordsVisible) toggleRecords();
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
                return null;
            }

            private boolean biliDesktopSet = false;

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // 保险丝: window.open 一律返回 null, 防止 JS 弹窗劫持
                view.evaluateJavascript("try{window.open=function(){return null};}catch(e){}", null);
                // 诊断模式：stripchat 等抓不到的站，全量请求落 网页诊断.txt
                diagActive = url != null && (url.contains("stripchat") || url.contains("zh.stripchat"));
                if (diagActive) dumpDiag("PAGE: " + url);
                if (url != null) dumpFc2Debug("PAGE: " + url);
                // B站手机UA会导致房间初始化失败，全程桌面UA
                if (url != null && url.contains("live.douyin.com") && !dyDesktopSet) {
                    dyDesktopSet = true;
                    view.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36");
                    view.stopLoading();
                    view.loadUrl(url);
                    return;
                }
                if (url != null && url.contains("bilibili.com") && !biliDesktopSet) {
                    biliDesktopSet = true;
                    view.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36");
                    view.stopLoading();
                    view.loadUrl(url);
                    return;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieHosts.add(getApplicationContext(), url);
                if (url != null && (url.contains("douyin.com") || url.contains("iesdouyin"))) {
                    injectDouyinScript();
                }
                if (url != null && (url.contains("live.fc2.com") || url.contains("guangdongvideo.com"))) {
                    injectFc2Hook();
                }
                // DK：直播房间页 → 持续解析（reload 由看门狗触发，回到这里重新武装提取）
                if (url != null && url.contains("live.douyin.com")) {
                    final String fu = url;
                    boolean newRoom = (douyinLiveUrl == null || !douyinLiveUrl.equals(fu));
                    if (newRoom || !douyinParsing) {
                        if (newRoom) {
                            synchronized (douyinCands) { douyinCands.clear(); }
                        }
                        douyinLiveUrl = fu;
                        douyinParsing = true;
                        // 主路径：页面内扫 DOM（抖音 PC 页走 WebSocket 传流，请求拦截抓不到）
                        main.postDelayed(new Runnable() { public void run() { injectDyGrabber(); } }, 800);
                        // 低频兜底：直接拉 HTML 解析 stream_url（可能被验证页拦，仅作补充）
                        main.postDelayed(new Runnable() { public void run() {
                            douyinLiveExtract(fu, CookieManager.getInstance().getCookie("https://live.douyin.com"));
                        }}, 3000);
                        startDouyinWatchdog();
                    }
                } else {
                    douyinLiveUrl = null;
                    douyinParsing = false;
                }
                BiliResolver.tryParse(SniffActivity.this, url);
                etUrl.setText(url);
            }
        });

        // 回车按钮（及键盘 Go）：打开网址（B站/抖音/其他网站都能进）
        btnGo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openInputUrl();
            }
        });
        etUrl.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                openInputUrl();
                return true;
            }
        });

        btnSwitchUa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchUa();
            }
        });
        btnRecords.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRecords();
            }
        });

        // FAB：切换顶部栏
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTopBar();
            }
        });

        // 点屏幕（非FAB区域）→ FAB 出现；再点一下 → 消失
        webView.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    x0 = event.getX(); y0 = event.getY();
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (Math.abs(event.getX() - x0) < 12 && Math.abs(event.getY() - y0) < 12) {
                        fab.setVisibility(fab.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    }
                }
                return false;
            }
        });

        // 弹窗带入的输入 / 系统分享进入
        String pre = getIntent().getStringExtra("input");
        if (pre == null || pre.length() == 0) {
            String act = getIntent().getAction();
            if ("android.intent.action.SEND".equals(act) || "android.intent.action.SEND_MULTIPLE".equals(act)) {
                pre = getIntent().getStringExtra("android.intent.extra.TEXT");
                if (pre == null) pre = "";
                if (pre.startsWith("http")) pre = pre.trim();
                else {
                    // 分享文本里抽第一个链接
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(pre);
                    if (m.find()) pre = m.group();
                    else pre = "";
                }
            }
        }
        if (pre != null && pre.length() > 0) {
            etUrl.setText(pre);
            openInputUrl();
        } else {
            webView.loadUrl("https://live.douyin.com/");
            dyAutoPcClick();
        }
    }

    void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    static boolean isDyLiveUrl(String u) {
        return u != null && (u.contains("live.douyin.com") || u.contains("webcast.amemv.com"));
    }

    /** 抖音直播：手机UA渲染后自动点"切电脑UA"（含刷新），3s兜底，只切一次 */
    void dyAutoPcClick() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() { public void run() {
            try {
                if (isFinishing() || isDestroyed() || webView == null || btnSwitchUa == null) return;
                if (!isMobileUa) return;
                String cur = webView.getUrl();
                if (!isDyLiveUrl(cur)) return;
                btnSwitchUa.performClick();   // 走手动按钮同一路径：切UA+刷新
            } catch (Throwable ignored) {}
        }}, 300);
    }

    void openInputUrl() {
        String raw = etUrl.getText().toString().trim();
        if (raw.length() == 0) return;

        String url = extractUrl(raw);
        if (url == null) {
            if (raw.matches("\\d+")) {
                url = "https://live.douyin.com/" + raw;
            } else {
                return;
            }
        }
        if (!url.startsWith("http")) url = "https://" + url;
        if (url.contains("v.douyin.com")) { resolveDouyinShort(url); return; }  // 抖音短链原生解析302直达房间
        if (isDyLiveUrl(url)) dyAutoPcClick();
        webView.loadUrl(url);
    }

    /** 抖音短链(v.douyin.com)原生跟随302，直达直播间地址 */
    void resolveDouyinShort(final String shortUrl) {
        dumpFc2Debug("DYRESOLVE: " + shortUrl);
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
            } catch (Throwable t) { dumpFc2Debug("DYRESOLVE err " + t); }
            dumpFc2Debug("DYRESOLVE final: " + cur);
            final String fu = cur;
            runOnUiThread(new Runnable() { public void run() {
                try { if (isMobileUa) dyAutoPcClick(); webView.loadUrl(fu); } catch (Throwable ignored) {}
            }});
        }}).start();
    }

    public static String extractUrl(String text) {
        Matcher m = URL_IN_TEXT.matcher(text);
        if (m.find()) {
            String u = m.group();
            if (u.startsWith("www.")) u = "https://" + u;
            return u;
        }
        if (text.matches("[\\w\\-./?:#=&%+~@!$'*;,\\[\\]]+")) return text;
        return null;
    }

    /** B站解析（cookie 版）：登录后 SESSDATA 走 view+playurl API，DASH 标画质，durl 合并流可直接播/下 */
    static class BiliResolver {
        static final java.util.Set<String> parsed = new java.util.HashSet<String>();

        static String storedCookies(android.content.Context c) {
            // 优先读设置页网页登录的全局 Cookie（CookieManager），无则回退老 prefs
            try {
                String ck = android.webkit.CookieManager.getInstance().getCookie("https://www.bilibili.com");
                if (ck != null && ck.contains("SESSDATA=")) return ck;
            } catch (Throwable ignored) {}
            android.content.SharedPreferences sp =
                    c.getSharedPreferences("bili", android.content.Context.MODE_PRIVATE);
            long t = sp.getLong("time", 0);
            // Cookie 有效期约 7 天：到期或无时间戳即删除，触发重新登录
            if (t > 0 && System.currentTimeMillis() - t > 7L * 24 * 3600 * 1000) {
                sp.edit().remove("cookies").remove("time").apply();
                return null;
            }
            return sp.getString("cookies", null);
        }

        static void tryParse(final SniffActivity act, String pageUrl) {
            try {
                if (pageUrl == null) return;
                Uri u = Uri.parse(pageUrl);
                String host = u.getHost() == null ? "" : u.getHost();
                if (!host.contains("bilibili.com")) return;
                if (!u.getPath().contains("/video/")) return;
                Matcher m = Pattern.compile("(BV[0-9A-Za-z]{10})").matcher(pageUrl);
                if (!m.find()) return;
                final String bvid = m.group(1);
                int p = 1;
                try { p = Integer.parseInt(u.getQueryParameter("p")); } catch (Exception e) { }
                final int pIdx = p;
                if (!parsed.add(bvid + "#p" + pIdx)) return;

                final String cookies = storedCookies(act);
                if (cookies == null || !cookies.contains("SESSDATA=")) {
                    // 首次：弹窗引导登录
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            new GlassDialog.Builder(act)
                                    .setTitle("需要登录B站")
                                    .setMessage("登录后才能解析并下载/播放最高画质，是否前往登录？")
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("去登录", new android.content.DialogInterface.OnClickListener() {
                                        public void onClick(android.content.DialogInterface d, int w) {
                                            act.startActivity(new android.content.Intent(act, com.downx.app.BiliLoginActivity.class));
                                        }
                                    })
                                    .show();
                        }
                    });
                    parsed.remove(bvid + "#p" + pIdx); // 登录后重试
                    return;
                }

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String vjson = httpGet("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid, cookies);
                            org.json.JSONObject vroot = new org.json.JSONObject(vjson);
                            if (vroot.optInt("code", -1) != 0) return;
                            org.json.JSONArray pages = vroot.getJSONObject("data").getJSONArray("pages");
                            long cid = pages.getJSONObject(Math.min(pIdx - 1, pages.length() - 1)).getLong("cid");
                            final String title = vroot.getJSONObject("data").optString("title", "B站视频");

                            // DASH：只用来取最高画质标签
                            String djson = httpGet("https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                                    + "&cid=" + cid + "&qn=127&fnval=16&fourk=1", cookies);
                            org.json.JSONObject dd = new org.json.JSONObject(djson).getJSONObject("data");
                            int qn = dd.optInt("quality", 0);
                            final String qname = qnName(qn);

                            // durl：合并了音轨的完整流，可直接播放/下载
                            String mjson = httpGet("https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                                    + "&cid=" + cid + "&qn=127&fnval=0&platform=html5", cookies);
                            org.json.JSONObject md = new org.json.JSONObject(mjson).getJSONObject("data");
                            String merged = null;
                            org.json.JSONArray durls = md.optJSONArray("durl");
                            if (durls != null && durls.length() > 0) {
                                merged = durls.getJSONObject(0).optString("url",
                                        durls.getJSONObject(0).optString("url"));
                            }
                            final String fmerged = merged;
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    String key = "bili#" + bvid + "#p" + pIdx;
                                    if (act.recordKeys.contains(key)) return;
                                    act.recordKeys.add(key);
                                    String label = "▶" + qname + "·" + title;
                                    act.addRecord(fmerged != null ? fmerged : ("RESOLVE:" + bvid + ":" + pIdx), label);
                                    if (!act.isRecordsVisible) act.toggleRecords();
                                }
                            });
                        } catch (Exception e) { }
                    }
                }).start();
            } catch (Exception e) { }
        }

        static String qnName(int qn) {
            switch (qn) {
                case 127: return "8K"; case 126: return "杜比"; case 125: return "HDR";
                case 120: return "4K"; case 116: return "1080P60"; case 112: return "1080P高码率";
                case 80: return "1080P"; case 74: return "720P60"; case 64: return "720P";
                case 32: return "480P"; case 16: return "360P";
                default: return "qn" + qn;
            }
        }

        static String httpGet(String url, String cookies) throws Exception {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            c.setRequestProperty("Referer", "https://www.bilibili.com/");
            if (cookies != null && cookies.length() > 0) c.setRequestProperty("Cookie", cookies);
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        }
    }

    // ==== DK 抖音解析（照 DKVideoPlayer MainActivity 逐段移植）====
    private final java.util.List<String> douyinCands = new java.util.ArrayList<>();
    private boolean douyinPickScheduled = false;
    private final java.util.Set<String> seenMedia = new java.util.HashSet<>();
    public static final int[] kbState = {0};
    private static final java.util.LinkedHashSet<String> kbSeen = new java.util.LinkedHashSet<>();
    private static java.io.FileOutputStream kbOut = null;
    private static String kbOutName = "";
    private static java.io.File kbDir;
    private boolean kbArmed = false;

    static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private boolean douyinParsing = false;      // 对应 DK 的 parsing（抖音解析进行中）
    private String douyinLiveUrl = null;        // 当前解析的直播间地址
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final String DY_LIVE_COOKIE = "enter_pc_once=1; hevc_supported=true; ttwid=1%7COnZEYGAxHABx6WRfArV8V0vfh1qUfP8AU2WYpG2ybdU%7C1754493043%7C867b28541b24aca9aec6379357aa2bff731e159fa7a804a767f575c8ff886639; __ac_nonce=06893707d00e64c4488d5; __ac_signature=_02B4Z6wo00f01m0zFcQAAIDDRDeLuhFSmo5tExFAAPPu88; odin_tt=e0bcb4ad345d3ed6915b71cab9469cb459681b743f65d870cb52329adfa8792b80633cf01c31edd9cf4874b743daacc7b789efd1727202b7ed3f6e7059ce43a72f3358995fc8367000f0b42103a78d1b; passport_csrf_token=4d713363889176dba46a4d28394acf2f";

    // ===== cat-catch webRequest 语义：全量请求统计识别媒体流 =====
    private static final java.util.HashMap<String, long[]> REQ_STATS = new java.util.HashMap<String, long[]>();
    private static final java.util.regex.Pattern ASSET_EXT = java.util.regex.Pattern.compile(
            "\\.(js|css|png|jpe?g|gif|webp|ico|woff2?|svg|ttf|eot|html?|xml|json)(\\?|$)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private final java.util.Set<String> reqTsLogged = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    void maybeRecordGeneric(final String url) { maybeRecordGeneric(url, "GET"); }
    void maybeRecordGeneric(final String url, final String method) {
        try {
            if (url == null || url.length() < 20) return;
            if (diagActive || url.contains("stripchat")) dumpDiag("WEBREQ: " + method + " " + url);
            if (url.contains("127.0.0.1:8123")) return;
            // 调试：B站全量请求落日志（用户回传 fc2_debug.txt 定位直播流）
            if (url.contains("bilibili") || url.contains("b23.tv")) {
                dumpFc2Debug("BREQ: " + method + " " + url);
            }
            String l = url.toLowerCase();
            int q = l.indexOf('?');
            String base = q > 0 ? l.substring(0, q) : l;
            if (l.endsWith(".ts") || l.contains(".ts?") || l.contains(".m4s")) {
                if (reqTsLogged.add(base)) dumpFc2Debug("WEBREQ-TS: " + method + " " + url);
            } else {
                dumpFc2Debug("WEBREQ: " + method + " " + url);
            }
            if (ASSET_EXT.matcher(base).find()) return;
            if (isMediaUrl(url)) { recordGeneric(url); return; }
            // 同一 base 反复请求(分段流/Range流) → 判定为媒体流，记录实际地址
            long now = System.currentTimeMillis();
            long[] st;
            synchronized (REQ_STATS) {
                if (REQ_STATS.size() > 800) REQ_STATS.clear();
                st = REQ_STATS.get(base);
                if (st == null) { REQ_STATS.put(base, new long[]{1, now}); return; }
                st[0]++;
            }
            if (st[0] == 4) recordGeneric(url);
        } catch (Throwable ignored) {}
    }

    void recordGeneric(final String url) { recordGenericT(url, null); }

    void recordGenericT(final String url, final String forceTitle) {
        boolean ok = recordKeys.add(url);
        dumpDiag("SC ADD: ok=" + ok + " url=" + url + " title=" + forceTitle);
        dumpFc2Debug("SC ADD: ok=" + ok + " url=" + url);
        if (!ok) return;
        foundUrls.add(url);
        final String page = webView.getTitle();
        runOnUiThread(new Runnable() { public void run() {
            try { addRecord(url, forceTitle != null ? forceTitle : (page == null ? "" : page)); } catch (Throwable t) { dumpDiag("SC ADD ERR: " + t); dumpFc2Debug("SC ADD ERR: " + t); }
            if (!isRecordsVisible) toggleRecords();
        }});
    }


    private android.app.AlertDialog adblockDlg;

    /** 自定义广告屏蔽管理: 添加域名/关键词 + 列表删除 */
    private void showAdblockMgr() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("输入域名(如 ad.example.com)或关键词");
        et.setSingleLine(true);
        box.addView(et);
        android.widget.Button add = new android.widget.Button(this);
        add.setText("＋ 添加屏蔽");
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String t = et.getText().toString().trim();
                if (t.isEmpty()) return;
                AdBlocker.addCustomRule(SniffActivity.this, t);
                Toast.makeText(SniffActivity.this, "已添加屏蔽: " + t + "，立即生效", Toast.LENGTH_SHORT).show();
                et.setText("");
            }
        });
        box.addView(add, new android.view.ViewGroup.LayoutParams(-1, -2));
        java.util.List<String> customs = AdBlocker.getCustomRules();
        if (!customs.isEmpty()) {
            TextView lbl = new TextView(this);
            lbl.setText("已添加 " + customs.size() + " 条（点按删除）:");
            lbl.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
            box.addView(lbl);
            for (final String k : customs) {
                TextView tv = new TextView(this);
                tv.setText("✕  " + k);
                tv.setTextColor(0xFFE5A50A);
                tv.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
                tv.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        AdBlocker.removeCustomRule(SniffActivity.this, k);
                        Toast.makeText(SniffActivity.this, "已删除: " + k, Toast.LENGTH_SHORT).show();
                        if (adblockDlg != null) adblockDlg.dismiss();
                        showAdblockMgr();
                    }
                });
                box.addView(tv);
            }
        }
        adblockDlg = new GlassDialog.Builder(this).setTitle("广告屏蔽管理").setView(box).create();
        adblockDlg.show();
    }

    /** 对应 DK shouldInterceptRequest 的抖音判定段 */
    void maybeRecordDouyin(final String url) {
        String l = url.toLowerCase();
        if (url.contains("/log/")) return;
        if (l.contains("doppiocdn.net") || l.contains("/ping.m3u8")) return;  // stripchat流/心跳，非抖音
        if (l.contains("bilivideo") || l.contains("upos-")) return;
        // 抖音直播：无参数裸地址 .../stage/xxxxx（不带 .flv?e= 签名参数，签名地址每次都变会死循环）
        String base = url;
        int qi = base.indexOf('?');
        if (qi > 0) base = base.substring(0, qi);
        String lb = base.toLowerCase();
        boolean anyM3u8 = l.contains(".m3u8");
        boolean bareLive = lb.contains("douyincdn") && lb.contains("/stage/")
            && !lb.substring(lb.lastIndexOf('/') + 1).contains(".");
        // 只有抖音系域名才走抖音候选；任意站的 m3u8 不再截胡（走通用嗅探，标签带 m3u8 前缀）
        boolean dyHost = l.contains("douyin") || l.contains("amemv") || l.contains("douyincdn");
        if (dyHost && (bareLive || anyM3u8)) {
            // 登记新鲜地址：录制重试/播放启动时 freshFor 热替换过期地址(防403)
            try { Or4Helper.handle(url); } catch (Throwable ignored) {}
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
            // 抖音强制最高画质：ratio→1080p；biz_resolution→1088x1920（兼容 %3D 编码）
            String hi = url
                .replaceAll("ratio=[a-zA-Z0-9_]+", "ratio=1080p")
                .replaceAll("biz_resolution(=|%3D|%3d)[a-zA-Z0-9_x]+", "biz_resolution$11088x1920")
                .replaceAll("resolution(=|%3D|%3d)[a-zA-Z0-9_x]+", "resolution$11088x1920");
            synchronized (douyinCands) {
                if (!douyinCands.contains(hi)) douyinCands.add(hi);
            }
            if (!douyinPickScheduled) {
                douyinPickScheduled = true;
                main.postDelayed(new Runnable() { public void run() { finishDouyinPick(); } }, 6000);
            }
            if (l.contains(".m3u8")) {
                new Thread(new Runnable() { public void run() { pickBestVariant(url); } }).start();
            }
        }
    }

    /** 看门狗：8秒还没抓到候选就重新走提取，直到抓到为止（照DK douyinWatchdog，reload换成重提取） */
    private final Runnable douyinWatchdog = new Runnable() {
        public void run() {
            if (!douyinParsing) return;
            boolean hasQuality = false;
            synchronized (douyinCands) {
                for (String u : douyinCands) if (u.contains("_or4") || u.contains("_uhd") || u.contains("_hd")) { hasQuality = true; break; }
            }
            if (!hasQuality && douyinLiveUrl != null) {
                injectDyGrabber();  // 主路径重注入：页面可能已换流/被验证页拦过
                douyinLiveExtract(douyinLiveUrl, CookieManager.getInstance().getCookie("https://live.douyin.com"));
            }
            if (hasQuality && douyinPickScheduled) return;  // 已拿到画质流，停止看门狗
            main.postDelayed(this, 1000);
        }
    };

    private final java.util.concurrent.atomic.AtomicBoolean fc2Added = new java.util.concurrent.atomic.AtomicBoolean(false);

    private final java.util.HashSet<String> fc2HlsSeen = new java.util.HashSet<>();

    private void pollFc2Hls(final String ws, final int round) {
        if (round > 45) return;  // ~90s 放弃
        String hls = Fc2Relay.getHls();
        if (hls != null && !hls.isEmpty() && fc2HlsSeen.add(hls)) {
            try {
                addRecord(hls, "FC2·直播");
                if (!isRecordsVisible) toggleRecords();
                dumpFc2Debug("记录已添加: " + hls);
            } catch (Throwable e) {
                dumpFc2Debug("记录添加失败: " + e.getClass().getSimpleName());
            }
            return;
        }
        main.postDelayed(new Runnable() { public void run() { pollFc2Hls(ws, round + 1); } }, 2000);
    }

    /** FC2 画质选择：解析 master_playlist，按 BANDWIDTH 从高到低挑子列表（90原画>40高清自然包含在内） */
    private static String pickFc2Quality(String masterUrl) {
        try {
            String proxied = "http://127.0.0.1:8123/relay?u=" + java.net.URLEncoder.encode(masterUrl, "UTF-8");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(proxied).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(6000);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = c.getInputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close(); c.disconnect();
            String body = bo.toString("UTF-8");
            if (!body.contains("#EXTM3U")) return null;
            // 解析 #EXT-X-STREAM-INF(BANDWIDTH) + 下一行 URL
            java.util.ArrayList<long[]> bws = new java.util.ArrayList<long[]>();
            java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
            java.util.ArrayList<String> names = new java.util.ArrayList<String>();
            String[] lines = body.split("\n");
            String base = masterUrl.substring(0, masterUrl.lastIndexOf('/') + 1);
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i].trim();
                if (ln.startsWith("#EXT-X-STREAM-INF")) {
                    java.util.regex.Matcher mb = java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)").matcher(ln);
                    long bw = mb.find() ? Long.parseLong(mb.group(1)) : 0;
                    java.util.regex.Matcher mn = java.util.regex.Pattern.compile("NAME=\"([^\"]*)\"").matcher(ln);
                    String nm = mn.find() ? mn.group(1) : "";
                    // 下一行是子列表地址
                    for (int j2 = i + 1; j2 < lines.length; j2++) {
                        String u2 = lines[j2].trim();
                        if (u2.isEmpty() || u2.startsWith("#")) continue;
                        if (!u2.startsWith("http")) u2 = base + u2.replaceFirst("^/", "");
                        bws.add(new long[]{bw});
                        urls.add(u2);
                        names.add(nm);
                        break;
                    }
                }
            }
            // 按带宽降序，逐个探测
            Integer[] idx = new Integer[urls.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
                public int compare(Integer a, Integer b2) { return Long.compare(bws.get(b2)[0], bws.get(a)[0]); }
            });
            for (int i = 0; i < idx.length; i++) {
                String cand = urls.get(idx[i]);
                dumpFc2Debug("候选#" + (i + 1) + " bw=" + bws.get(idx[i])[0] + " name=" + names.get(idx[i]) + " " + cand);
                if (probeHls(cand)) {
                    int rank = i + 1;  // 1=最高带宽
                    bestFc2Name = rank == 1 ? "原画" : (rank == 2 ? "高清" : (rank == 3 ? "极速" : "直播"));
                    dumpFc2Debug("选中: " + bestFc2Name);
                    return cand;
                }
            }
        } catch (Throwable ignored) {}
        bestFc2Name = null;
        return null;
    }

    private static volatile String bestFc2Name = null;

    private static boolean probeHls(String url) {
        try {
            String proxied = "http://127.0.0.1:8123/relay?u=" + java.net.URLEncoder.encode(url, "UTF-8");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(proxied).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(5000);
            java.io.InputStream in = c.getInputStream();
            byte[] b = new byte[256];
            int n = in.read(b);
            in.close(); c.disconnect();
            if (n <= 0) return false;
            String head = new String(b, 0, n, "UTF-8");
            return head.contains("#EXTM3U");
        } catch (Throwable e) { return false; }
    }

    public static android.content.Context sDumpCtx;

    static java.io.File dumpDir() {
        try { return sDumpCtx.getExternalFilesDir(null).getParentFile(); } catch (Throwable t) { return null; }
    }

    private static void dumpFc2Debug(String st) {
        try {
            java.io.File dir = sDumpCtx.getExternalFilesDir(null).getParentFile();
            java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(dir, "fc2_debug.txt"), true);
            fw.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                + " " + st + "\n----------------\n");
            fw.close();
        } catch (Throwable ignored) {}
    }


    /** FC2：钩住页面 WebSocket 拿 ws-flv 地址 */
    void injectFc2Hook() {
        fc2Added.set(false);
        String js = "(function(){if(window.__fc2Hooked)return;window.__fc2Hooked=1;"
            + "var OW=window.WebSocket;"
            + "function NW(u,p){try{AndroidPlayer.onWsUrl(String(u));}catch(e){}"
            + "if(p===undefined)return new OW(u);return new OW(u,p);}"
            + "NW.prototype=OW.prototype;NW.CONNECTING=OW.CONNECTING;NW.OPEN=OW.OPEN;NW.CLOSING=OW.CLOSING;NW.CLOSED=OW.CLOSED;"
            + "window.WebSocket=NW;})();";
        try { webView.evaluateJavascript(js, null); } catch (Throwable ignored) {}
        main.postDelayed(new Runnable() { public void run() {
            try { webView.evaluateJavascript("(function(){window.__fc2Hooked=0;})();", null); } catch (Throwable ignored) {}
        }}, 8000);
    }

    void startDouyinWatchdog() {
        main.removeCallbacks(douyinWatchdog);
        main.postDelayed(douyinWatchdog, 1000);
    }

    /** 候选全部入记录（带画质标签），不再只挑一条 */
    void finishDouyinPick() {
        java.util.List<String> snapshot;
        synchronized (douyinCands) { snapshot = new java.util.ArrayList<String>(douyinCands); }
        douyinPickScheduled = false;
        douyinParsing = false;
        if (snapshot.isEmpty()) return;
        // DK 方案：原画优先——含 _or4 的排最前，含 _sd 的排最后
        java.util.Collections.sort(snapshot, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                boolean ao = a.contains("_or4"), bo = b.contains("_or4");
                if (ao != bo) return ao ? -1 : 1;
                boolean as = a.contains("_sd"), bs = b.contains("_sd");
                if (as != bs) return as ? 1 : -1;
                return 0;
            }
        });
        boolean added = false;
        String pgTitle = "";
        try { pgTitle = webView.getTitle(); } catch (Throwable e) { }
        // 只保留原画一条（已按 _or4 原画优先、_sd 靠后排序，取第一条即可）
        for (final String u : snapshot) {
            if (recordKeys.add("dy#" + u)) {
                added = true;
                final String q = douyinQuality(u);
                final String t = pgTitle == null ? "" : pgTitle;
                main.post(new Runnable() { public void run() {
                    addRecord(u, "抖音[" + t + "]·" + q);
                }});
                break;   // 只入最高画质（原画）一条，其余画质档不进记录
            }
        }
        if (added && !isRecordsVisible) toggleRecords();
    }

    long remoteSizeDouyin(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", UA_MOBILE);
            c.setRequestProperty("Referer", "https://live.douyin.com/");
            long len = c.getContentLengthLong();
            return len < 0 ? 0 : len;
        } catch (Throwable e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** m3u8 主清单 → 挑最大 BANDWIDTH 子清单（照DK pickBestVariant） */
    void pickBestVariant(String masterUrl) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(masterUrl).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA_MOBILE);
            c.setRequestProperty("Referer", "https://live.douyin.com/");
            if (c.getResponseCode() != 200) return;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close(); c.disconnect();
            String body = bo.toString("UTF-8");
            if (!body.contains("#EXT-X-STREAM-INF")) return;
            long bestBw = -1; String best = null; long curBw = -1;
            java.net.URI base = java.net.URI.create(masterUrl);
            for (String ln : body.split("\n")) {
                String t = ln.trim();
                if (t.startsWith("#EXT-X-STREAM-INF")) {
                    java.util.regex.Matcher bm = java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)").matcher(t);
                    curBw = bm.find() ? Long.parseLong(bm.group(1)) : -1;
                } else if (!t.isEmpty() && !t.startsWith("#") && curBw > bestBw) {
                    bestBw = curBw;
                    best = base.resolve(t).toString();
                }
            }
            if (best != null && seenMedia.add(best)) {
                synchronized (douyinCands) {
                    if (!douyinCands.contains(best)) douyinCands.add(best);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 页面内注入 dygrab.js：直接扫抖音真实 DOM 拿直播流地址（不经 HttpURLConnection，避免验证页） */
    void injectDyGrabber() {
        try {
            String js = readAsset("dygrab.js");
            if (js == null || js.length() < 50) return;
            try { webView.evaluateJavascript("(function(){window.__dyGrab=0;})();", null); } catch (Throwable ignored) {}
            webView.evaluateJavascript(js, null);
        } catch (Throwable ignored) {}
    }

    /** 收下 dygrab.js 上报的流地址（带画质兜底，谁先到谁进记录） */
    void dyAccept(String u) {
        try {
            if (u == null || u.length() < 12) return;
            try { Or4Helper.handle(u); } catch (Throwable ignored) {}  // 登记新鲜地址供 403 热替换
            if (recordKeys.add("dy#" + u)) {
                addRecord(u, "抖音·" + douyinQuality(u));
                if (!isRecordsVisible) toggleRecords();
            }
            douyinParsing = false;
        } catch (Throwable ignored) {}
    }

    /** DK douyinLiveExtract：直接拉直播间页面按清晰度正则提取流地址（兜底提取方案） */
    private void douyinLiveExtract(final String liveUrl, final String cookie) {
        new Thread(new Runnable() { public void run() {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(liveUrl).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(10000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36");
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
                c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                c.setRequestProperty("Cookie", cookie != null && cookie.length() > 10 ? cookie : DY_LIVE_COOKIE);
                if (c.getResponseCode() != 200) { c.disconnect(); failDouyin("页面请求失败:" + c.getResponseCode()); return; }
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close(); c.disconnect();
                String html = sb.toString();

                // 各清晰度流地址正则提取（照DK）
                String[][] qualities = {
                    {"原画", "flv\",?\\s*\"?streamUrl\"?:?\\s*\"?(https?:[^\"']+flv[^\"']*)"},
                    {"蓝光", "\"(?:flv|full)\"\\s*:\\s*\"(https?:[^\"]+)\""},
                    {"超清", "\"uhd\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"(https?:[^\"]+)\""},
                    {"高清", "\"hd\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"(https?:[^\"]+)\""},
                    {"标清", "\"sd\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"(https?:[^\"]+)\""}
                };
                for (String[] q : qualities) {
                    java.util.regex.Matcher pm = java.util.regex.Pattern.compile(q[1]).matcher(html);
                    if (pm.find()) {
                        String u = pm.group(1).replace("\\u002F", "/").replace("\\/", "/");
                        final String fu = u;
                        main.post(new Runnable() { public void run() {
                            if (recordKeys.add("dy#" + fu)) {
                                addRecord(fu, "抖音·" + douyinQuality(fu));
                                if (!isRecordsVisible) toggleRecords();
                            }
                            douyinParsing = false;
                        }});
                        return;
                    }
                }
                // 统一解码转义后再匹配
                html = html.replace("\\u0026", "&").replace("\\u002F", "/").replace("\\/", "/").replace("\\u003D", "=");
                // 兜底：页面里任意 m3u8/flv 直链
                String[] gens = {
                    "https?:[^\\s\"'<>]*?\\.m3u8[^\\s\"'<>]*",
                    "https?:[^\\s\"'<>]*?\\.flv[^\\s\"'<>]*"
                };
                for (String gp : gens) {
                    java.util.regex.Matcher gm = java.util.regex.Pattern.compile(gp).matcher(html);
                    if (gm.find()) {
                        String u = gm.group(0).replace("\\u002F", "/").replace("\\/", "/");
                        u = u.replaceAll("^[\"'\\[<]+", "").replaceAll("[\"'\\]>.,;)]+$", "");
                        final String fu = u;
                        main.post(new Runnable() { public void run() {
                            if (recordKeys.add("dy#" + fu)) {
                                addRecord(fu, "抖音·直播(兜底)");
                                if (!isRecordsVisible) toggleRecords();
                            }
                            douyinParsing = false;
                        }});
                        return;
                    }
                }
                failDouyin("各清晰度都没取到流地址 html=" + html.length());
            } catch (Throwable e) {
                failDouyin("解析异常:" + e.getMessage());
            }
        }}).start();
    }

    private void failDouyin(final String msg) {
        main.post(new Runnable() { public void run() {
            if (recordKeys.add("dyfail#" + msg)) {
                addRecord(msg, "抖音直播");
            }
        }});
    }

    /** 抖音清晰度后缀识别：_or4原画 _uhd蓝光 _hd高清 _sd标清 _ld流畅 _md极速 */
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


    public static void startKb(Context c) {
        if (kbState[0] == 1) return;
        try {
            kbDir = new java.io.File(c.getExternalFilesDir(null), "录制/segments");
            if (kbDir == null) return;
            kbDir.mkdirs();
            kbOutName = "live_kb_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + ".ts";
            kbOut = new java.io.FileOutputStream(new java.io.File(kbDir.getParentFile(), kbOutName), true);
            Intent svc = new Intent(c, KbRecordService.class);
            c.startForegroundService(svc);
            kbState[0] = 1;
        } catch (Throwable e) { kbState[0] = 0; }
    }

    public static void stopKb() {
        try {
            kbState[0] = 0;
            java.io.FileOutputStream fo = kbOut;
            kbOut = null;
            if (fo != null) { fo.flush(); fo.close(); }
            if (kbOutName != null && kbDir != null) {
                final java.io.File src = new java.io.File(kbDir.getParentFile(), kbOutName);
                if (src.exists() && src.length() > 0) {
                    final java.io.File tmp = new java.io.File(src.getParentFile(), "fix_tmp.ts");
                    String[] args = { "-y", "-fflags", "+genpts", "-i", src.getAbsolutePath(),
                        "-c", "copy", "-map", "0", "-f", "mpegts", tmp.getAbsolutePath() };
                    com.arthenica.ffmpegkit.FFmpegKit.executeWithArgumentsAsync(args,
                        new com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback() {
                            public void apply(com.arthenica.ffmpegkit.FFmpegSession st) {
                                if (tmp.exists() && tmp.length() > 0) { src.delete(); tmp.renameTo(src); }
                                else tmp.delete();
                            }
                        });
                }
            }
        } catch (Throwable ignored) {}
    }
    static boolean isMediaUrl(String url) {
        return MEDIA.matcher(url.toLowerCase()).find();
    }

    /** 油猴脚本离线内置：抖音网页版全能优化（assets/douyin.user.js） */
    private String jsDouyin = null;

    void injectDouyinScript() {
        // 纯离线内置：Vue + 脚本全部走 assets，不再在线拉取
        String loader = "(function(){"
            + "function bad(m){var d=document.createElement('div');d.style.cssText='position:fixed;top:0;left:0;right:0;z-index:999999;background:#c0392b;color:#fff;font-size:12px;padding:2px';d.textContent='脚本注入失败:'+m;(document.body||document.documentElement).appendChild(d);}"
            + "if(typeof window.GM_addStyle=='undefined'){window.GM_addStyle=function(c){var s=document.createElement('style');s.textContent=c;document.head.appendChild(s);};}"
            + "if(typeof window.__GM_STORE=='undefined'){try{window.__GM_STORE=JSON.parse(localStorage.getItem('gm_store')||'{}');}catch(e){window.__GM_STORE={};}}"
            + "if(typeof window.GM_getValue=='undefined'||window.__GM_STORE_V2!==true){window.__GM_STORE_V2=true;"
            + "window.GM_getValue=function(k,d){return (k in window.__GM_STORE)?window.__GM_STORE[k]:d;};"
            + "window.GM_setValue=function(k,v){window.__GM_STORE[k]=v;try{localStorage.setItem('gm_store',JSON.stringify(window.__GM_STORE));}catch(e){}};"
            + "window.GM_deleteValue=function(k){delete window.__GM_STORE[k];try{localStorage.setItem('gm_store',JSON.stringify(window.__GM_STORE));}catch(e){}};"
            + "window.GM_listValues=function(){return Object.keys(window.__GM_STORE);};"
            + "window.GM_addValueChangeListener=function(){return 0;};"
            + "window.GM_removeValueChangeListener=function(){};}"
            + "if(typeof window.GM_xmlhttpRequest=='undefined'){window.GM_xmlhttpRequest=function(d){fetch(d.url).then(function(r){return r.text();}).then(function(t){if(d.onload)d.onload({responseText:t,status:200});});};}"
            + "if(typeof window.GM_info=='undefined'){window.GM_info={script:{version:'offline',name:'douyin'}};}"
            + "if(typeof window.GM_registerMenuCommand=='undefined'){window.GM_registerMenuCommand=function(name,fn){if(!window.__GM_MENUS)window.__GM_MENUS=[];window.__GM_MENUS.push({name:name,fn:fn});return window.__GM_MENUS.length;};}"
            + "if(typeof window.GM_unregisterMenuCommand=='undefined'){window.GM_unregisterMenuCommand=function(){};}"
            + "if(typeof window.GM_openInTab=='undefined'){window.GM_openInTab=function(u){window.open(u);};}"
            + "if(typeof window.GM_notification=='undefined'){window.GM_notification=function(){};}"
            + "if(typeof window.GM_download=='undefined'){window.GM_download=function(d){var a=document.createElement('a');a.href=(typeof d==='object')?d.url:d;a.download='';document.body.appendChild(a);a.click();};}"
            + "if(typeof window.GM_setClipboard=='undefined'){window.GM_setClipboard=function(t){var ta=document.createElement('textarea');ta.value=t;document.body.appendChild(ta);ta.select();document.execCommand('copy');ta.remove();};}"
            + "if(typeof window.GM_getResourceText=='undefined'){window.GM_getResourceText=function(){return '';};}"
            + "if(typeof window.GM_getResourceURL=='undefined'){window.GM_getResourceURL=function(){return '';};}"
            + "if(typeof window.GM_cookie=='undefined'){window.GM_cookie={list:function(d){if(d&&d.onload)d.onload([]);},set:function(){},delete:function(){}};}"
            + "if(typeof window.unsafeWindow=='undefined'){window.unsafeWindow=window;}"
            + "var n=0;"
            + "function dyTry(){"
            + "  if(document.getElementById('dyLoadTip')) return;"
            + "  var sc=window.AndroidPlayer?AndroidPlayer.getScript():null;"
            + "  if(!sc){ if(++n<10) setTimeout(dyTry,2000); return; }"
            + "  try{ (0,window.eval)(sc); }catch(e){ bad(e&&e.message?e.message:String(e)); return; }"
            + "  if(++n<10) setTimeout(dyTry,2000);"
            + "}"
            + "dyTry();"
            + "})();";
        webView.evaluateJavascript(loader, null);
    }

        static final String DOUYIN_SCRIPT_URL = "https://update.greasyfork.org/scripts/584735/%E6%8A%96%E9%9F%B3%E7%BD%91%E9%A1%B5%E7%89%88%E5%85%A8%E8%83%BD%E4%BC%98%E5%8C%96.user.js";

    /** 后台拉最新脚本缓存到 files/douyin.user.js，下次注入生效 */
    void fetchLatestDouyinScript() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(DOUYIN_SCRIPT_URL).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    if (c.getResponseCode() != 200) return;
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                    in.close();
                    if (bo.size() > 1000) {
                        java.io.File f = new java.io.File(getFilesDir(), "douyin.user.js");
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                        fo.write(bo.toByteArray()); fo.close();
                    }
                } catch (Throwable e) { }
            }
        }).start();
    }

    String readTextFile(java.io.File f) {
        try {
            java.io.FileInputStream is = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            is.close();
            return bo.toString("UTF-8");
        } catch (Throwable e) { return null; }
    }

    String readAsset(String name) {
        try {
            java.io.InputStream is = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            is.close();
            return bo.toString("UTF-8");
        } catch (Throwable e) { return null; }
    }

    void switchUa() {
        if (isMobileUa) {
            webView.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            btnSwitchUa.setText("切手机UA");
            btnSwitchUa.setBackgroundColor(0x4D3742fa);
            isMobileUa = false;
        } else {
            webView.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            btnSwitchUa.setText("切电脑UA");
            btnSwitchUa.setBackgroundColor(0x4D2ed573);
            isMobileUa = true;
        }
        webView.reload();
    }

    void toggleRecords() {
        dumpFc2Debug("PANEL TOGGLE vis=" + isRecordsVisible + " act=" + hashCode());
        if (isRecordsVisible) {
            bottomPanel.setVisibility(View.GONE);
            ((Button) findViewById(R.id.btn_records)).setBackgroundColor(0x4Dff4757);
            isRecordsVisible = false;
        } else {
            bottomPanel.setVisibility(View.VISIBLE);
            ((Button) findViewById(R.id.btn_records)).setBackgroundColor(0x4Dff4757);
            isRecordsVisible = true;
            bottomPanel.post(new Runnable() { public void run() {
                dumpFc2Debug("PANEL AFTER vis=" + bottomPanel.getVisibility() + " rows=" + layoutRecords.getChildCount());
            }});
        }
    }

    void toggleTopBar() {
        isTopBarVisible = !isTopBarVisible;
        topBar.setVisibility(isTopBarVisible ? View.VISIBLE : View.GONE);
    }

    private volatile String playerUrl = null;

    /** 探测甄别出的 referer, 随 playUrl 透传给播放器 */
    static volatile String pendingRef = "";

    /** 播放：统一唤起 NativePlayerActivity（系统硬解 / Ijk FFmpeg 双内核按 URL 自动选） */
    void playUrl(String url) {
        // FC2 中转流：按 http 链接直接唤起浏览器播放
        if (url != null && url.contains("127.0.0.1:8123/relay")) {
            try {
                android.content.Intent i = new android.content.Intent(Intent.ACTION_VIEW);
                i.setData(android.net.Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Throwable e) {
                Toast.makeText(this, "无法唤起浏览器: " + e.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
            }
        }
        try {
            finish(); // 播放前先退出资源嗅探
            playerUrl = url;
            android.content.Intent i = new android.content.Intent(this, NativePlayerActivity.class);
            i.putExtra("url", url);
            i.putExtra("title", "资源嗅探");
            if (pendingRef != null && !pendingRef.isEmpty()) i.putExtra("referer", pendingRef);
            pendingRef = "";
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打不开: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 局域网共享：记录的链接（url -> title）
    private static final java.util.LinkedHashMap<String, String> LAN_LINKS = new java.util.LinkedHashMap<String, String>();
    public static java.util.LinkedHashMap<String, String> lanLinks() {
        synchronized (LAN_LINKS) { return new java.util.LinkedHashMap<String, String>(LAN_LINKS); }
    }

    /** 供录制器注册任务链接（如 抖音·原画） */
    public static void registerLanLink(String url, String title) {
        try {
            String real = url;
            if (real.startsWith("http://127.0.0.1:8123/relay?u=")) {
                real = java.net.URLDecoder.decode(real.substring("http://127.0.0.1:8123/relay?u=".length()), "UTF-8");
            }
            synchronized (LAN_LINKS) {
                if (!LAN_LINKS.containsKey(real)) {
                    LAN_LINKS.put(real, title == null ? real : title);
                    if (LAN_LINKS.size() > 500) LAN_LINKS.remove(LAN_LINKS.keySet().iterator().next());
                }
            }
        } catch (Throwable ignored) {}
    }

    void addRecord(final String url, String title) {
        // 流畅/极速不显示
        if (title != null && title.contains("抖音") && (url.contains("_ld.") || url.contains("_md."))) return;
        // 抖音直播只显示原画 m3u8(_or4.m3u8)：其他画质/flv 一律不进记录（所有路径统一在此过滤）
        if (url != null && url.contains("douyincdn") && url.contains("/stage/")
                && !(url.contains("_or4") && url.contains(".m3u8"))) return;
        try {
            String real = url;
            if (real.startsWith("http://127.0.0.1:8123/relay?u=")) {
                real = java.net.URLDecoder.decode(real.substring("http://127.0.0.1:8123/relay?u=".length()), "UTF-8");
            }
            synchronized (LAN_LINKS) {
                if (!LAN_LINKS.containsKey(real)) {
                    LAN_LINKS.put(real, title == null ? real : title);
                    if (LAN_LINKS.size() > 500) LAN_LINKS.remove(LAN_LINKS.keySet().iterator().next());
                }
            }
        } catch (Throwable ignored) {}
        // 同一路流只留一条最高画质：原画(_or4) > 蓝光(_uhd) > 高清(_hd)
        boolean isBiliLive = title != null && title.startsWith("B站直播");
        final int rank = isBiliLive ? biliSeq : qualRank(url);
        final String bk = isBiliLive ? url.substring(0, url.indexOf('?') > 0 ? url.indexOf('?') : url.length())
                                     : baseKey(url);
        if (recByBase.containsKey(bk)) {
            Object[] prev = recByBase.get(bk);
            int pr = (Integer) prev[0];
            if (rank < pr || (rank == pr && !isBiliLive)) { dumpFc2Debug("ADD SKIP dup bk=" + bk); return; }  // 已有更高或同等画质，丢弃
            // B站直播：新地址(带新有效期)原地替换旧地址，记录永不过期
            prev[0] = rank; prev[1] = url;
            if (isBiliLive) {
                ((TextView) prev[2]).setText(title);
            } else {
                String qn = qualName(rank);
                ((TextView) prev[2]).setText(qn == null ? title : (title != null && (title.contains("抖音") || url.contains("douyin")) ? "抖音·" + qn : qn));
            }
            ((TextView) prev[3]).setText(url);
            return;
        }
        boolean isDy = (title != null && title.contains("抖音")) || url.contains("douyin");
        // 兜底直链（无画质后缀）与带后缀的流并存时，只留带后缀的
        if (isDy && rank == 0 && hasHigherRank()) return;
        if (isDy && rank == 0 && genericBase != null) return;  // 兜底只留一条(兜底/直播不重复)
        if (isDy && rank > 0 && genericBase != null && recByBase.containsKey(genericBase)) {
            dropRecord(genericBase);  // 高画质到了，删掉兜底那条
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(16, 12, 16, 12);
        row.setBackgroundColor(0x33222244);

        final TextView tv = new TextView(this);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13);
        String label = (title == null || title.length() == 0) ? "嗅探 " + (foundUrls.size()) : title;
        String qn = qualName(rank);
        if (qn != null && isDy) label = "抖音·" + qn;
        // 格式打头：非抖音/非B站一律前缀媒体类型，让 m3u8/mp4 一眼可见
        if (!isDy && !isBiliLive) {
            String ext = "";
            try {
                java.util.regex.Matcher em = java.util.regex.Pattern.compile(
                        "\\.(m3u8|mp4|flv|mkv|avi|ts|webm|mp3|m4a|aac|flac|mov)(\\?|$)",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
                if (em.find()) ext = em.group(1).toLowerCase();
            } catch (Throwable ignored) {}
            String t2 = (title == null || title.length() == 0) ? "" : title;
            label = (ext.isEmpty() ? "嗅探" : ext) + (t2.isEmpty() ? "" : "·" + t2);
        }
        tv.setText(label);
        // 单行但截断：setSingleLine 会开启 horizontallyScrolling, 在水平 LinearLayout(weight=1) 下
        // 会忽略宽度约束、按全文宽度测量, 长标题(尤其 m3u8 标注" · N分片 · 约MM:SS")会把右侧 ⋮ 按钮挤到屏外导致点不动。
        // 改用 maxLines+ellipsize, 在约束宽度内省略号截断, ⋮ 始终留在屏内可点击。
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView menuBtn = new TextView(this);
        menuBtn.setText("\u22EE");
        menuBtn.setBackgroundColor(0x338899AA);
        menuBtn.setTextColor(0xFFFFFFFF);
        menuBtn.setTextSize(16);
        menuBtn.setPadding(12, 4, 12, 4);
        menuBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRecordMenu(v, url, row);
            }
        });
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headRow.addView(tv, new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(menuBtn);
        row.addView(headRow);

        TextView tvUrl = new TextView(this);
        tvUrl.setTextColor(0xFF8AB4F8);
        tvUrl.setTextSize(11);
        tvUrl.setText(url);
        tvUrl.setMaxLines(1);
        tvUrl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(tvUrl);
        row.setTag(url);  // 画质升级后菜单/复制取最新地址

        // 点行复制（不删除）
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = row.getTag() instanceof String ? (String) row.getTag() : url;
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("url", u));
                Toast.makeText(SniffActivity.this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });

        recByBase.put(bk, new Object[]{rank, url, tv, tvUrl});
        if (isDy && rank == 0) genericBase = bk;  // 记住兜底那条，便于高画质到达时移除
        layoutRecords.addView(row, 0);
        dumpFc2Debug("ADD-ROW ok bk=" + bk + " rank=" + rank);
        // M3U8Manger 识别: 嗅探到 m3u8 时解析分片数与时长, 标注到记录(他的"识别记录")
        if (url.toLowerCase().contains(".m3u8") || url.toLowerCase().contains("playlist") || url.toLowerCase().contains("/hls/")) {
            final String rawU = url;
            new Thread(new Runnable() { public void run() {
                try {
                    M3u8Util.M3U8 m = M3u8Util.parseIndex(rawU);
                    final String info = " · " + m.tsList.size() + "分片 · 约" + M3u8Util.fmtDur((long) m.totalSeconds);
                    main.post(new Runnable() { public void run() {
                        try { tv.setText(tv.getText() + info); } catch (Throwable ignored) {}
                    }});
                } catch (Throwable ignored) {}
            }}).start();
        }
    }

    private String genericBase = null;

    // ↻ 连续刷新：1秒一次，直到刷出带画质后缀(原画/蓝光/高清)的流；按钮变"停"，再点停止
    private boolean autoRefreshing = false;
    private final Runnable autoRefreshRun = new Runnable() {
        public void run() {
            if (!autoRefreshing) return;
            if (hasHigherRank()) { toggleAutoRefresh(); return; }  // 刷到原画等，自动停
            try { webView.reload(); } catch (Throwable e) { }
            main.postDelayed(this, 2000);
        }
    };

    void toggleAutoRefresh() {
        autoRefreshing = !autoRefreshing;
        btnRefresh.setText(autoRefreshing ? "停" : "↻");
        if (autoRefreshing) autoRefreshRun.run();
        else main.removeCallbacks(autoRefreshRun);
    }

    private boolean hasHigherRank() {
        for (Object[] v : recByBase.values()) if ((Integer) v[0] > 0) return true;
        return false;
    }

    private void dropRecord(String bk) {
        Object[] v = recByBase.remove(bk);
        if (v != null && v[3] != null) {
            TextView tvU = (TextView) v[3];
            Object p = tvU.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(tvU); // tvUrl的父即整行
        }
        genericBase = null;
    }

    void showRecordMenu(final View anchor, final String url, final LinearLayout row) {
        final String curUrl = row.getTag() instanceof String ? (String) row.getTag() : url;
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        pm.getMenu().add("下载");
        pm.getMenu().add("播放");
        // 用 RecManager 实际录制状态判断（recThreads 是旧通道，与引擎不是一套）
        final boolean recording = recThreads.containsKey(url) || RecManager.isRecording(curUrl);
        final String label = recording ? "停止录制" : "直播录制";
        pm.getMenu().add(label);
        pm.getMenu().add("删除");
        pm.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                String t = item.getTitle().toString();
                if (t.equals("下载")) downloadUrl(curUrl);
                else if (t.equals("播放")) playUrl(curUrl);
                else if (t.equals("直播录制")) {
                    final String url = curUrl;
                    String pgTitle2 = "";
                    try { pgTitle2 = webView.getTitle(); } catch (Throwable ignored) {}  // 页面标题作为录制名
                    finish(); // 先退出资源嗅探
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("stream", curUrl));
                    } catch (Throwable e) { }
                    RecManager.init(getApplicationContext());
                    RecManager.startRecJob(url, pgTitle2); // 自动开始录制
                    // 跳回首页「媒体管理·任务」实时查看录制状态
                    try {
                        android.content.Intent mi = new android.content.Intent(SniffActivity.this, com.example.immersive.MainActivity.class);
                        mi.putExtra("goto_rec", true);
                        mi.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(mi);
                    } catch (Throwable ignored) {}
                    Toast.makeText(SniffActivity.this, "已开始录制，可在任务页管理", Toast.LENGTH_LONG).show();
                }
                else if (t.equals("停止录制")) {
                    RecManager.recStopAll();
                    Toast.makeText(SniffActivity.this, "录制已结束", Toast.LENGTH_SHORT).show();
                }
                else if (t.equals("删除")) {
                    ViewGroup p = (ViewGroup) row.getParent();
                    if (p != null) p.removeView(row);
                    for (java.util.Iterator<java.util.Map.Entry<String, Object[]>> it2 = recByBase.entrySet().iterator(); it2.hasNext(); ) {
                        if (it2.next().getValue()[3] == row.getChildAt(1)) it2.remove();
                    }
                    recordKeys.remove(url);
                    foundUrls.remove(url);
                    Toast.makeText(SniffActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        pm.show();
    }

    final java.util.Map<String, Thread> recThreads = new java.util.HashMap<>();

        String refererFor(String url) {
        return url.contains("bilibili.com") || url.contains("bilivideo") ? "https://www.bilibili.com/" : null;
    }

    void downloadUrl(String url) {
        try {
            String lu = url.toLowerCase();
            if (lu.contains("127.0.0.1:8123/relay")) {
                DlManager.startHls(url);  // 本地中转直播流: ffmpeg 后台下载
            } else if (lu.contains(".m3u8") || lu.contains("playlist")) {
                DlManager.startM3u8(url);  // M3U8Manger 方式: 纯 HTTP 解析分片+并行下载+合并
            } else {
                DlManager.start(url);
            }
        } catch (Throwable e) { }
        Toast.makeText(this, "已加入下载，可在下载页-下载 查看", Toast.LENGTH_SHORT).show();
    }

    void startRecord(final String url) {
        if (recThreads.containsKey(url)) return;
        Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                java.io.File out = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
                        "资源嗅探_直播_" + System.currentTimeMillis() + ".ts");
                java.io.InputStream in = null;
                java.io.FileOutputStream fos = null;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                    String rf = refererFor(url);
                    if (rf != null) c.setRequestProperty("Referer", rf);
                    in = c.getInputStream();
                    fos = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[65536]; int n; long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        total += n;
                    }
                    final String msg = "录制完成: " + (total / 1048576) + "MB " + out.getName();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { Toast.makeText(SniffActivity.this, msg, Toast.LENGTH_LONG).show(); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { Toast.makeText(SniffActivity.this, "录制中断: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                    });
                } finally {
                    try { if (in != null) in.close(); } catch (Exception e) { }
                    try { if (fos != null) fos.close(); } catch (Exception e) { }
                    recThreads.remove(url);
                }
            }
        });
        recThreads.put(url, t);
        t.start();
    }

    void stopRecord(String url) {
        Thread t = recThreads.remove(url);
        if (t != null) t.interrupt();
        Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
    }    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        try { webView.stopLoading(); } catch (Throwable e) { }
        try {
            ViewGroup p = (ViewGroup) webView.getParent();
            if (p != null) p.removeView(webView);
            webView.destroy();
        } catch (Throwable e) { }
        super.onDestroy();
    }
}
