package com.downx.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * Instagram 匿名下载主路：加载官方 embed 页（公开帖子无需登录）。
 *
 * 2026-11-09 实测：小米系统浏览器未登录打开 embed 页能完整渲染视频，说明 IG 并没有
 * 封匿名 embed。之前误判"IG 必须登录"是对照实验用了夸克浏览器、而夸克没走 VPN。
 *
 * 这版不再赌页面 DOM 结构（IG 改版就失效），改用双保险：
 * 1) shouldInterceptRequest 监听 WebView 发出的每个请求，只要视频开始加载，
 *    CDN 的 mp4 直链必然从这里过——与 DOM 无关，最稳；
 * 2) 原有 JS 探测兜底（video.src / og:video / 海报图）。
 * 页面放在 1dp 不可见 WebView 里加载，视频未必自动播放，但 mp4 的预加载请求
 * （preload）依然会发出，监听照样能命中；JS 兜底负责图片帖与封面。
 */
public class IgScraper {

    public interface Callback {
        void onResult(TweetFetcher.Result r);

        void onFail(String msg);
    }

    private static final String TAG = "DownX-IG";
    /** 手机版页面匿名看 reel 会被"在应用中观看"拦住；桌面版网页匿名能直接播 */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String JS =
            "(function(){try{var r={};"
            + "var v=document.querySelector('video');"
            + "if(v){r.video=v.currentSrc||v.src||'';if(!r.video){var s=v.querySelector('source');if(s)r.video=s.src;}"
            + "r.poster=v.poster||'';}"
            + "var mv=document.querySelector('meta[property=\"og:video\"]');"
            + "if(mv&&!r.video)r.video=mv.content;"
            // 作者
            + "var u=document.querySelector('.UsernameText');"
            + "r.user=u?u.textContent.replace(/\\s+/g,' ').trim():'';"
            + "if(!r.user){var t=document.querySelector('meta[property=\"og:title\"]');"
            + "if(t){var m=t.content.match(/^(.*?)\\s*(?:•|\\|)?\\s*on Instagram/i);"
            + "r.user=m?m[1].trim():t.content;}}"
            // 正文：去掉第一行用户名与 “View all N comments”
            + "var c=document.querySelector('.Caption');"
            + "if(c){var t2=(c.innerText||c.textContent||'');"
            + "t2=t2.replace(/View all [\\d,]+ comments/gi,'').replace(/View [\\d,]+ comments/gi,'');"
            + "var ls=t2.split('\\n').map(function(x){return x.trim();}).filter(function(x){return x;});"
            + "if(ls.length&&r.user&&ls[0]===r.user)ls.shift();"
            + "r.caption=ls.join(' ').slice(0,300);}"
            // 封面：优先 video.poster，其次页面里的第一张内容图
            + "if(!r.poster){var im=document.querySelector('img.EmbeddedMediaImage')||document.querySelector('img');"
            + "r.poster=im&&im.src?im.src:'';}"
            + "r.image=r.poster;"
            // 主页面兜底用：帖子 HTML 的 JSON 里有 video_versions，第一条就是最高清直链
            + "var hm=document.documentElement.innerHTML;"
            + "var mv2=hm.match(/\"video_versions\":\\[\\{\"url\":\"([^\"]+)\"/);"
            + "if(mv2)r.versions=mv2[1];"
            // embed 页被 IG 判"链接损坏/已移除"时的标记：换主页面兜底
            + "var bx=document.body?document.body.innerText:'';"
            + "r.broken=(bx.indexOf('可能已被移除')>=0)||(bx.indexOf('been removed')>=0)"
            + "||(bx.indexOf('可能已损坏')>=0)||(bx.indexOf('may have been broken')>=0)"
            + "||(bx.indexOf('继续使用网页版')>=0)||(bx.indexOf('在应用中观看')>=0)"
            + "||(bx.indexOf('Watch this Reel')>=0)||(bx.indexOf('Open Instagram')>=0);"
            // IG 的"年龄限制"闸门：匿名永远拿不到，必须登录（不是参数/网络问题）
            + "r.gate=(bx.indexOf('有年龄限制')>=0)||(bx.indexOf('登录并继续')>=0)"
            + "||(/age.?restricted/i.test(bx));"
            + "var t3=document.querySelector('time');"
            + "if(t3)r.date=t3.getAttribute('datetime')||'';"
            + "return JSON.stringify(r);}catch(e){return JSON.stringify({err:String(e)});}})();";

    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int attempt;
    private String code;
    /** 帖子路径类型：p / reel / reels / tv，主页面兜底时要用原路径 */
    private String path;
    private Callback callback;
    private boolean done;
    /** 正在去首页种匿名 cookie，回来后再开 embed */
    private boolean warming;
    private int warms;              // 最多种 2 次，防止"种不出 cookie"时死循环
    /** embed 页被 IG 判"链接损坏"后，改去帖子主页面（匿名可看）——只切一次 */
    private boolean mainTried;
    /** 当前要打开的目标页（embed 或主页面）。被踢去登录墙种完 cookie 回这里 */
    private String targetUrl;
    /** WebView 里捕获到的 IG CDN mp4 直链（shouldInterceptRequest 是后台线程，用 volatile） */
    private volatile String hitVideo;
    private volatile String hitPoster;

    public IgScraper(WebView web) {
        this.web = web;
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(Net.UA);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBlockNetworkImage(false);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
    }

    private String embedUrl() {
        return "https://www.instagram.com/p/" + code + "/embed/captioned/";
    }

    /** 帖子主页面（匿名可看的那个）：保持原链接的路径类型（reel/p/reels/tv） */
    private String mainUrl() {
        return "https://www.instagram.com/" + (path == null ? "p" : path)
                + "/" + code + "/";
    }

    public void scrape(String link, Callback cb) {
        this.code = null;
        this.path = null;
        java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("instagram\\.com/(?:[^/]+/)?(p|reel|reels|tv)/([A-Za-z0-9_-]+)")
                .matcher(link);
        if (pm.find()) {
            this.path = pm.group(1);
            this.code = pm.group(2);
        }
        this.callback = cb;
        this.attempt = 0;
        this.done = false;
        this.mainTried = false;
        this.hitVideo = null;
        this.hitPoster = null;
        if (code == null) {
            cb.onFail("没有识别到 Instagram 帖子链接");
            return;
        }
        this.targetUrl = embedUrl();
        // 每次解析都从手机 UA 开始（上一次可能切过桌面 UA）
        web.getSettings().setUserAgentString(Net.UA);
        web.stopLoading();
        // 假线必须真匿名：WebView 里可能残留上次登录的 sessionid（退出登录时刻意
        // 保留了匿名 cookie），半登录状态会让 IG 把页面导去别的页，截到无关图片。
        // 这里彻底清干净，随后靠"访问首页领匿名 cookie"重新建立干净身份。
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            Log.i(TAG, "已清空 WebView 的 IG Cookie（假线保持纯匿名）");
        } catch (Throwable ignored) { }
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                return blockScheme(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                    android.webkit.WebResourceRequest req) {
                return blockScheme(req.getUrl() == null ? null : req.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "scraper page finished " + url);
                if (done) return;
                if (warming) {
                    // 首页种完匿名 cookie，带着它回目标页（可能是 embed 也可能是主页面）
                    warming = false;
                    view.loadUrl(targetUrl);
                    return;
                }
                if (url != null && url.contains("/accounts/login/")) {
                    // 被 IG 踢到登录墙 = WebView 里连匿名 cookie 都没有。
                    // 去首页让 IG 发一套匿名 cookie（mid/csrftoken/ig_did），再回来。
                    if (warms < 2) {
                        Log.i(TAG, "被重定向到登录墙，先种匿名 cookie");
                        warms++;
                        warming = true;
                        view.loadUrl("https://www.instagram.com/");
                    }
                    return;
                }
                // JS 探测之前先看几眼：IG 有时把匿名访客导去别的页面（而不是报错），
                // 那种页面上的图/视频和帖子毫无关系（曾截到用户自己的头像），必须挡掉
                String cur = url == null ? "" : url;
                if (cur.startsWith("https://www.instagram.com")
                        && !cur.equals("https://www.instagram.com/")
                        && !cur.contains(code)) {
                    Log.i(TAG, "页面被 IG 导去别处 " + brief(cur) + "，判定匿名不给，停");
                    done = true;
                    callback.onFail("这条帖子匿名打不开（Instagram 跳到了别的页面）。"
                            + "登录后一定能下；或过一会儿再试，IG 对匿名时松时紧");
                    return;
                }
                main.postDelayed(IgScraper.this::probe, 1500);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest req) {
                // 关键：视频直链不猜 DOM，直接从网络层截。embed 页只要开始加载视频，
                // 请求必经此处。注意 IG 的视频 URL 很多不带 .mp4：
                // 新版是 scontent-xxx.cdninstagram.com/o1/v/t16|t2/...，老版是
                // .../t50.2886-16/...——按这些特征认，别只认扩展名。
                String u = req.getUrl() == null ? "" : req.getUrl().toString();
                String low = u.toLowerCase();
                boolean cdn = low.contains("cdninstagram") || low.contains("fbcdn.net");
                // m3u8 / mpd / manifest 是流媒体清单，不是可下载的完整文件，跳过
                boolean manifest = low.contains("m3u8") || low.contains(".mpd")
                        || low.contains("manifest") || low.contains("/hls/") || low.contains("/dash/");
                if (cdn) {
                    boolean isImg = low.contains(".jpg") || low.contains(".jpeg")
                            || low.contains(".webp") || low.contains(".png")
                            || low.contains("/t51.2885");
                    boolean isVideo = !isImg && !manifest && (low.contains(".mp4")
                            || low.contains("/o1/v/") || low.contains("t50.2886"));
                    if (isVideo && hitVideo == null) {
                        hitVideo = u;
                        // 记完整 URL：截到的可能是清单（m3u8/mpd）而不是 mp4，排查要用
                        Log.i(TAG, "scraper 截到视频直链 " + u);
                    } else if (!isVideo && isImg && hitPoster == null
                            && (u.matches(".*[se](640|750|1080|x1080|1600)[x_].*") || u.contains("e15"))) {
                        hitPoster = u;
                    }
                }
                return null;   // 全部放行，只是旁听
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String desc, String failingUrl) {
                if (done) return;
                // 主文档加载失败基本就是网络不通（代理没生效），直接给结论，别让用户干等
                if (failingUrl != null && failingUrl.contains("instagram.com")
                        && failingUrl.contains("embed") && desc != null && desc.startsWith("net::")) {
                    done = true;
                    callback.onFail("内置浏览器打不开 Instagram（" + desc + "）。"
                            + "请确认代理/VPN 已连接，并把 DownX 加入 VPN 的代理应用列表");
                }
            }
        });
        // WebView 里连匿名 cookie 都没有时，embed 会被直接踢到登录墙；
        // 先去首页领一套匿名 cookie 再回来，一步到位
        String ck = "";
        try {
            ck = android.webkit.CookieManager.getInstance().getCookie("https://www.instagram.com");
        } catch (Throwable ignored) { }
        if (ck == null || !ck.contains("csrftoken=")) {
            Log.i(TAG, "无匿名 cookie，先访问首页领取");
            warming = true;
            web.loadUrl("https://www.instagram.com/");
        } else {
            web.loadUrl(embedUrl());
        }
    }

    private void probe() {
        if (done) return;
        web.evaluateJavascript(JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                handle(value);
            }
        });
    }

    private void handle(String raw) {
        if (done) return;
        String json = decode(raw);
        String video = "", image = "", user = "", caption = "", date = "", versions = "";
        boolean broken = false;
        boolean gated = false;
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            video = o.optString("video", "");
            image = o.optString("image", "");
            user = o.optString("user", "");
            caption = o.optString("caption", "");
            date = o.optString("date", "");
            versions = o.optString("versions", "");
            broken = "true".equals(o.optString("broken"));
            gated = "true".equals(o.optString("gate"));
        } catch (Exception e) { /* 走兜底 */ }

        // embed 页被 IG 判"链接损坏"（匿名不给 embed，但主页面能看）：
        // 改去帖子主页面，网络层旁听不变。2026-11-09 真机验证：
        // 同一帖子 embed 两个路径都"损坏"，主页面匿名照常播放。
        // 年龄限制：IG 服务端闸门，匿名无解，直接给准确结论（别耗 40 秒）
        if (gated) {
            done = true;
            Log.i(TAG, "判定为 IG 年龄限制内容，匿名不可达");
            callback.onFail("这条被 Instagram 标为「年龄限制内容」，未登录一律拿不到。"
                    + "到「设置 → Instagram 登录」登一次，回来就能下");
            return;
        }
        if (broken && !mainTried) {
            mainTried = true;
            attempt = 0;
            hitVideo = null;
            hitPoster = null;
            // 手机版网页匿名看 reel 会被"在应用中观看这条 Reels"拦住；
            // 桌面版网页匿名能直接播，换 UA 再开主页面
            web.getSettings().setUserAgentString(DESKTOP_UA);
            targetUrl = mainUrl();
            Log.i(TAG, "匿名门槛页 → 桌面 UA 开主页面 " + targetUrl);
            web.loadUrl(targetUrl);
            return;
        }

        // 优先级：页面 JSON 的 video_versions（真 mp4，音画合一）> 网络层截获 > DOM
        if ((video == null || video.isEmpty() || video.startsWith("blob:"))
                && versions != null && !versions.isEmpty()) {
            video = unesc(versions);
            Log.i(TAG, "从页面 JSON 取到 video_versions 直链 " + brief(video));
        }
        if ((video == null || video.isEmpty() || video.startsWith("blob:")) && hitVideo != null) {
            video = hitVideo;
        }
        if ((image == null || image.isEmpty()) && hitPoster != null) image = hitPoster;
        // 头像（t51.2885-19）和帖子内容图（t51.2885-15）不是一回事：
        // 主页面兜底时只认帖子内容图，免得把用户自己的头像当帖子下下来
        if (mainTried && image != null && image.contains("/t51.2885-19")) image = "";

        // 原链接是 reel（一定是视频）：主页面兜底仍拿不到视频时，宁可不给，
        // 也不能把页面里的某张图当"下载结果"交出去
        boolean reelLink = "reel".equals(path) || "reels".equals(path);
        if (reelLink && mainTried && (video == null || video.isEmpty())) {
            retry();
            return;
        }

        boolean ok = video.startsWith("http") || image.startsWith("http");
        if (!ok) {
            retry();
            return;
        }
        TweetFetcher.Result r = new TweetFetcher.Result();
        r.platform = "instagram";
        r.tweetUrl = "https://www.instagram.com/p/" + code + "/";
        r.handle = user == null ? "" : user;
        r.author = r.handle;
        r.text = caption == null ? "" : caption;
        if (date != null && date.length() >= 16) {
            r.date = date.substring(0, 10) + " " + date.substring(11, 16);
        }

        if (video != null && video.startsWith("http") && !video.startsWith("blob:")) {
            TweetFetcher.Media m = new TweetFetcher.Media();
            m.url = video;
            m.thumb = image != null && image.startsWith("http") ? image : "";
            m.label = "视频 · 最佳画质";
            m.kind = "video";
            r.items.add(m);
        } else if (image != null && image.startsWith("http")) {
            TweetFetcher.Media m = new TweetFetcher.Media();
            m.url = image;
            m.thumb = image;
            m.label = "图片 · 原图";
            m.kind = "photo";
            m.ext = "jpg";
            r.items.add(m);
        }
        if (r.items.isEmpty()) {
            retry();
            return;
        }
        done = true;
        Log.i(TAG, "scraper 命中 video=" + brief(video) + " via="
                + (hitVideo != null ? "intercept" : "js"));
        callback.onResult(r);
    }

    private void retry() {
        attempt++;
        if (attempt == 10) {
            // 一直拿不到东西时看一眼页面里到底有什么：是登录墙还是海报，别瞎猜
            web.evaluateJavascript("(function(){try{return JSON.stringify({"
                    + "url:location.href,"
                    + "videos:document.querySelectorAll('video').length,"
                    + "imgs:document.querySelectorAll('img').length,"
                    + "hasLogin:document.body.innerText.indexOf('Log in')>=0,"
                    + "text:document.body.innerText.replace(/\\s+/g,' ').slice(0,120)"
                    + "});}catch(e){return JSON.stringify({err:String(e)});}})();",
                    v -> Log.i(TAG, "scraper 诊断 " + decode(v)));
        }
        if (attempt % 5 == 0) {
            Log.i(TAG, "scraper 第 " + attempt + " 次探测未命中 video="
                    + (hitVideo == null ? "-" : "intercept-ok"));
        }
        // embed 页在代理下可能十几秒才渲染，给足 30 次（约 40 秒）
        if (attempt > 30) {
            done = true;
            callback.onFail("Instagram 页面没有返回可下载的内容。"
                    + "可能是私密账号、帖子已删除、代理未生效，"
                    + "或 DownX 没被加进 VPN 的代理应用列表");
            return;
        }
        main.postDelayed(this::probe, 1200);
    }

    /**
     * IG 网页会朝 instagram:// 这类自定义协议跳转（想唤起 App）。WebView 打不开就变
     * "网页无法打开"错误页，我们就啥也截不到。和浏览器一样：非 http(s) 一律拦下，
     * 页面留在原帖子上，视频照常加载。
     */
    private boolean blockScheme(String u) {
        if (u == null) return false;
        String low = u.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")) return false;
        Log.i(TAG, "拦掉非 http 跳转 " + brief(u));
        return true;
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 70 ? s.substring(0, 70) + "…" : s;
    }

    /** 页面 JSON 里的直链带 \/ 和 \u0026 转义，还原成真 URL */
    private static String unesc(String s) {
        if (s == null) return "";
        return s.replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("&amp;", "&");
    }

    /** evaluateJavascript 返回的是 JSON 字符串字面量，需要先解一层 */
    private static String decode(String v) {
        if (v == null || "null".equals(v)) return null;
        try {
            Object o = new org.json.JSONTokener(v).nextValue();
            return o instanceof String ? (String) o : String.valueOf(o);
        } catch (Exception e) {
            return v;
        }
    }
}
