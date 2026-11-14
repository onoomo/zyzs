package com.downx.app;

import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 抖音 / 快手。两条路：
 *
 * 快手走「快路径」（参照 CYQawa/YunX 的 KuaishouParser）：不开 WebView，一次 HTTP 请求
 * 作品页，从 SSR 数据 window.INIT_STATE 里直接拿 标题/作者/封面/视频直链/图集，
 * 约 1 秒出结果。以前「INIT_STATE 只剩空壳」的结论是错的 —— 空壳是因为请求头不对：
 * 必须 iOS UA + Referer https://v.kuaishou.com/，用桌面/安卓 UA 拿到的才真是空壳。
 *
 * 快路径失败（改版/验证/网络）时退回老路：用内置浏览器打开官方页面，
 * 让平台自己的 JS 去算签名、去拉流，我们只把它实际请求的流地址截下来。
 * 这条路不依赖任何私有接口，页面改版顶多补条 URL 特征，不会再「整体失效」。
 *
 * 抖音官方页面已经是前端渲染 + 请求签名，服务端 HTML 里没有任何视频地址，
 * 所以抖音只走 WebView 嗅探（2026-11 实测 iesdouyin/m.douyin/www.douyin 的
 * SSR 与分享页都拿不到 play_addr）。
 *
 * 要点：
 *   1. 候选页面按顺序试（iesdouyin → m.douyin → www.douyin），一个不行换下一个；
 *   2. 播放器可能等着用户点，得主动 muted + play()，否则永远嗅不到流；
 *   3. 抖音的流地址不带 .mp4 后缀（.../video/tos/...?mime_type=video_mp4），
 *      不能只看后缀判断，否则全是漏的。
 */
public class ShortVideoScraper {

    public interface Callback {
        void onResult(TweetFetcher.Result r);

        void onFail(String msg);
    }

    /** 每轮问页面要一次状态：视频地址、封面、标题、作者、图片 */
    private static final String JS =
            "(function(){try{var r={};"
            + "var vs=document.querySelectorAll('video');"
            + "var v=vs.length?vs[0]:null;"
            + "if(v){r.video=v.currentSrc||v.src||'';"
            + "if(!r.video){var s=v.querySelector('source');if(s)r.video=s.src;}"
            + "r.poster=v.poster||'';"
            + "try{v.muted=true;if(v.paused)v.play();}catch(e){}"
            + "}"
            // 有的播放器把流藏在 <video> 之前的网络请求里，从性能条目里捞一遍
            + "if(!r.video||/^blob:/.test(r.video)){try{"
            + "var es=performance.getEntriesByType('resource');"
            + "for(var i=es.length-1;i>=0;i--){var n=es[i].name||'';"
            + "if(/\\.m3u8/.test(n)||/\\.mp4/.test(n)||/mime_type=video/.test(n)){r.video=n;break;}"
            + "}}catch(e2){}}"
            + "if(!r.video){var mv=document.querySelector('meta[property=\"og:video\"]')"
            + "||document.querySelector('meta[property=\"og:video:url\"]');"
            + "if(mv)r.video=mv.content;}"
            + "var im=document.querySelector('meta[property=\"og:image\"]');"
            + "if(!r.poster&&im)r.poster=im.content;"
            // 快手 H5 的封面是 JS 塞进去的背景图，<img>/og:image 都没有，
            // 这里扫一遍 background-image，专挑图床域名
            + "if(!r.poster){try{var all=document.querySelectorAll('*');"
            + "for(var bi=0;bi<all.length&&bi<1500;bi++){"
            + "var bg=(getComputedStyle(all[bi]).backgroundImage)||'';"
            + "var mm=bg.match(/url\\([\\\"']?([^\\\"')]+)[\\\"']?\\)/);"
            + "if(mm&&/yximgs|kwimgs|kwaicdn|wsukwai|wskwai/.test(mm[1])){r.poster=mm[1];break;}"
            + "}}catch(e6){}}"
            + "var ia=[];var ns=document.querySelectorAll('img');"
            + "for(var i=0;i<ns.length&&ia.length<20;i++){"
            + "var src=ns[i].currentSrc||ns[i].src||'';"
            + "if(!/^https?:/.test(src))continue;"
            + "if(/avatar|icon|logo|emoji|sns-avatar/i.test(src))continue;"
            + "if(ia.indexOf(src)<0)ia.push(src);}"
            + "r.imgs=ia;"
            + "r.title=(document.title||'').replace(/\\\\s+/g,' ').trim();"
            + "var a=document.querySelector('meta[property=\"og:title\"]');"
            + "if(a&&!r.title)r.title=a.content;"
            + "if(!r.title||r.title.length<4){var h1=document.querySelector('h1');"
            + "if(h1){var ht=(h1.textContent||'').replace(/\\\\s+/g,' ').trim();"
            + "if(ht.length>3)r.title=ht;}}"
            + "var sel=['[data-e2e=\"video-author\"]','.author-wrapper .username',"
            + "'.author-wrapper .name','[class*=\"authorName\"]','[class*=\"userName\"]',"
            + "'.user-name','.nickname','[class*=\"nickname\"]','.profile-user-name',"
            + "'.author-name','a[href*=\"/user/\"] .name','h1'];"
            + "for(var k=0;k<sel.length&&!r.author;k++){var ae=null;"
            + "try{ae=document.querySelector(sel[k]);}catch(e3){ae=null;}"
            + "if(ae){var tx=(ae.textContent||'').replace(/\\\\s+/g,' ').trim();"
            + "if(tx&&tx.length<40)r.author=tx;}}"
            // 被风控拦下来时页面只剩验证码，「正在解析…」会白等到超时，
            // 提前认出来直接换下一条候选页，用户就不用干等
            + "try{var bt=(document.body?document.body.innerText:'').slice(0,400);"
            + "if(/安全验证|请完成验证|拖动滑块|完成拼图|访问异常|verify-center/i.test(bt)"
            + "||location.href.indexOf('verify')>0)r.verify=true;}catch(e4){}"
            // 抖音图文作品：页面上根本没有 <video>，图在 SSR 里或 DOM 的作品图里。
            // 判据照 CYQawa/YunX 的 DouyinParser：只认 biz_tag=aweme_images / tplv-dy-aweme-images，
            // 封面（pcweb_cover）和「相关推荐」（WEBPC_RELATED）都不算，否则会收一堆封面图。
            + "var wi=[];try{"
            + "var rd=document.querySelector('#RENDER_DATA');var app=null;"
            + "if(rd&&rd.textContent){try{app=JSON.parse(decodeURIComponent(rd.textContent)).app;}catch(e8){}}"
            + "if(!app&&window.SSR_RENDER_DATA)app=window.SSR_RENDER_DATA.app;"
            + "var vd=app&&app.videoDetail;"
            + "if(vd&&vd.images&&vd.images.length){"
            + "for(var qi=0;qi<vd.images.length&&wi.length<30;qi++){var qim=vd.images[qi];"
            + "var qu=qim?((qim.url_list&&qim.url_list[0])||(qim.urlList&&qim.urlList[0])||''):'';"
            + "if(qu)wi.push(qu);}"
            + "if(vd.desc&&vd.desc.length>3)r.title=vd.desc;"
            + "if(vd.author&&vd.author.nickname)r.author=vd.author.nickname;"
            + "}}catch(e9){}"
            + "if(!wi.length){try{var ai=document.querySelectorAll('img');"
            + "for(var ni=0;ni<ai.length&&wi.length<30;ni++){var su=ai[ni].src||'';"
            + "if(su.indexOf('douyinpic')<0)continue;"
            + "if(su.indexOf('WEBPC_RELATED')>=0)continue;"
            + "if(su.indexOf('biz_tag=aweme_images')>=0||su.indexOf('tplv-dy-aweme-images')>=0){"
            + "if(wi.indexOf(su)<0)wi.push(su);}}}catch(e10){}}"
            + "r.workImgs=wi;"
            + "return JSON.stringify(r);}catch(e){return JSON.stringify({err:String(e)});}})();";

    /** 每轮顺手戳一下：挡住「点击播放」的蒙层、让静音播放先跑起来 */
    private static final String NUDGE =
            "(function(){try{"
            + "var b=document.querySelector('[class*=\"play\"][class*=\"btn\"],"
            + "[class*=\"Play\"],[data-e2e=\"video-play\"],.xgplayer-play,.prism-play-btn');"
            + "if(b){b.click();}"
            + "var vs=document.querySelectorAll('video');"
            + "for(var i=0;i<vs.length;i++){var v=vs[i];"
            + "try{v.muted=true;v.setAttribute('playsinline','');"
            + "if(v.paused&&v.play)v.play();}catch(e){}}"
            + "return 'ok';}catch(e){return 'err';}})();";

    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<String> pages = new ArrayList<>();
    private int pageIdx;
    private int attempt;
    private String platform;
    private String pageUrl;
    private Callback callback;
    private boolean done;
    /** 第几轮页面。切换候选页后旧一轮的定时探测必须作废，否则会两条链并行狂探 */
    private int gen;
    /** 播放器自己发出去的流地址（JS 播放器常常是 blob，只有抓请求才拿得到） */
    private volatile String sniffed = "";
    private volatile boolean sniffedHls;
    private volatile int mp4Score;
    /** 封面：快手 H5 的封面图是 <作品id>.jpg，和视频流共用 clientCacheKey */
    private volatile String sniffedCover = "";
    /** 快手弹了人机验证：验证码页背后全是推荐流的别人的视频，谁都不能信 */
    private volatile boolean captchaHit;

    /** 设备自带 UA（移动版）。快手桌面 UA 会掉进「浏览器版本过低」页，见 scrape() */
    private final String mobileUa;

    public ShortVideoScraper(WebView web) {
        this(web, null);
    }

    public ShortVideoScraper(WebView web, String mobileUa) {
        this.web = web;
        String ua = mobileUa == null ? "" : mobileUa.trim();
        this.mobileUa = ua.isEmpty() ? web.getSettings().getUserAgentString() : ua;
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBlockNetworkImage(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    }

    /**
     * @param pageUrl 首选作品页；内部会按平台特征再补几条候选页轮流试
     */
    public void scrape(String platform, String pageUrl, Callback cb) {
        this.platform = platform;
        this.pageUrl = pageUrl;
        this.callback = cb;
        this.attempt = 0;
        this.pageIdx = 0;
        this.done = false;
        this.sniffed = "";
        this.sniffedHls = false;
        this.mp4Score = 0;
        this.sniffedCover = "";
        this.captchaHit = false;

        boolean dy = "douyin".equals(platform);
        pages.clear();
        String id = ShortVideo.idOf(dy, pageUrl);
        List<String> all = id == null ? new ArrayList<>() : ShortVideo.pagesOf(dy, id);
        if (all.isEmpty() && pageUrl != null) all.add(pageUrl);
        // 自学习：上一次哪条候选页出过流，这次从它开始，省掉前面几条的等待
        int best = Prefs.svPage(web.getContext(), platform);
        if (best > 0 && best < all.size()) {
            pages.add(all.get(best));
            for (int i = 0; i < all.size(); i++) {
                if (i != best) pages.add(all.get(i));
            }
        } else {
            pages.addAll(all);
        }

        // 1dp 的隐藏 WebView 里播放器不会真正起流，嗅探期间临时铺开展示，
        // 靠透明度藏起来，结束后恢复
        expandForSniff();

        // 快手先跑快路径（HTTP 直取 INIT_STATE，约 1 秒）；WebView 嗅探同时启动，
        // 谁先出结果算谁的 —— 快路径赢了用户只等 1 秒，输了老路照常兜底
        if ("kuaishou".equals(platform)) ksFastParse();

        // 抖音 H5 对 iPhone UA 更愿意直接放视频；
        // 快手反过来：桌面 UA 会被判成「目前浏览器版本过低」，页面只剩一张提示图，
        // 既没有 <video> 也没有封面 —— 这就是「能解析出东西但预览黑屏、还下不动」的原因。
        // 快手必须用 WebView 自带的移动 UA，才会给真正的 H5 播放器。
        web.getSettings().setUserAgentString(dy ? ShortVideo.UA_M : mobileUa);
        loadCurrent();
    }

    private void loadCurrent() {
        if (pageIdx >= pages.size()) {
            fail();
            return;
        }
        final int g = ++gen;
        main.removeCallbacksAndMessages(null);
        String url = pages.get(pageIdx);
        attempt = 0;
        sniffed = "";
        sniffedHls = false;
        mp4Score = 0;
        sniffedCover = "";
        captchaHit = false;
        web.stopLoading();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                if (done || g != gen) return;
                view.evaluateJavascript(NUDGE, null);
                main.postDelayed(() -> {
                    if (g == gen) probe();
                }, 900);
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest req) {
                try {
                    if (req != null && req.getUrl() != null) note(req.getUrl().toString());
                } catch (Throwable ignored) { }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                // 抖音 H5 会拼命唤起 App（snssdk:// / intent://），一律拦掉，
                // 让它继续留在网页里播视频
                if (u == null) return false;
                return u.startsWith("intent:") || u.startsWith("snssdk")
                        || u.startsWith("aweme") || u.startsWith("market:")
                        || u.startsWith("douyin") || u.startsWith("ksnebula")
                        || u.startsWith("kwai");
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (done || req == null || !req.isForMainFrame()) return;
                // 子资源（唤起 App 的深链）报错不影响主页面，只有主文档挂了才换下一条
                nextPage("打不开这个页面");
            }
        });
        web.loadUrl(url);
    }

    private void probe() {
        if (done) return;
        final int g = gen;
        web.evaluateJavascript(NUDGE, null);
        web.evaluateJavascript(JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (g == gen) handle(value);   // 换页了就丢掉这一轮的结果
            }
        });
    }

    private void handle(String raw) {
        if (done) return;
        String json = decode(raw);
        android.util.Log.i(TAG, "probe#" + attempt + " p" + pageIdx + " " + (json == null ? "null" : json.substring(0, Math.min(220, json.length()))));
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            String video = o.optString("video", "");
            String poster = o.optString("poster", "");
            String title = o.optString("title", "");
            String author = o.optString("author", "").trim();
            org.json.JSONArray imgs = o.optJSONArray("imgs");

            // 撞上人机验证：这条页面白等也没用，立刻换下一条
            if (o.optBoolean("verify")) {
                nextPage("页面要求验证");
                return;
            }

            // 快手弹了滑动验证（网络层截到的）：验证码页上放的是推荐流的
            // 别人的视频，等下去也不会有本作品的流 —— 趁早换候选页
            if (captchaHit && sniffed.isEmpty()) {
                nextPage("快手弹了人机验证");
                return;
            }

            // 通用落地页（作品已删/私密/链接不对）同样没必要等满预算。
            // 但只有在「一条流都没截到」时才算死：快手把标题固定成
            // 「短视频-快手」的页面有时照样在播，截到流了就别乱换页
            if (attempt >= 2 && sniffed.isEmpty() && isDeadPage(title)) {
                nextPage("这条作品打不开");
                return;
            }

            if (video.startsWith("blob:")) video = "";
            // 播放器用 MSE 播放时 <video>.src 是 blob:，真正能下的是它背后的清单，
            // 这一步从拦截到的网络请求里捞出来
            boolean hls = false;
            if (!video.startsWith("http") && !sniffed.isEmpty()) {
                video = sniffed;
                hls = sniffedHls;
            } else if (video.startsWith("http") && isHlsUrl(video)) {
                hls = true;
            }
            if (video.startsWith("http")) {
                // 抖音去水印：以前是把 playwm 直接替换成 play —— 2026 年 11 月实测这条路已经死了，
                // m.douyin.com/aweme/v1/play/ 现在返回 404（用户看到的「解析成功、点下载 404」就是它）。
                // 现在改成从地址里抠出 video_id，用官方 play 端点现算：
                //   https://aweme.snssdk.com/aweme/v1/play/?video_id=<id>&ratio=1080p
                // 这个端点每次请求都会 302 到一枚**新鲜签名**的 CDN 地址，所以不存在"放久了失效"，
                // 也顺手拿到无水印（playwm 那条是带水印的分发版）。
                // 参考 CYQawa/YunX 的 DouyinParser（存 video_id、下载时现算），实测 1080p 3.1MB / 720p 2.4MB 均 206。
                String vid = "douyin".equals(platform) ? videoIdOf(video) : "";

                TweetFetcher.Result r = new TweetFetcher.Result();
                r.platform = platform;
                r.tweetUrl = pageUrl;
                r.handle = platform;
                r.author = authorName(author, platform);
                r.text = clean(title);
                if (r.text.isEmpty()) {
                    r.text = "来自" + r.author + "的视频";
                    r.svGeneric = true;   // 自己编的通用句，下载时要补作品 ID
                }

                String poster0 = poster.startsWith("http") ? poster : "";
                // 封面兜底：分享页 og:image → 页面第一张内容图 → 网络层截到的封面
                if (poster0.isEmpty() && imgs != null && imgs.length() > 0) {
                    String f = imgs.optString(0, "");
                    if (f.startsWith("http")) poster0 = f;
                }
                if (poster0.isEmpty() && !sniffedCover.isEmpty()) poster0 = sniffedCover;

                if (!vid.isEmpty()) {
                    // 清楚度按体积从小到大排：第一条最清晰，后面的当备用直链
                    // （DlService 主链被拒时会自动换成 alt，用户不用重来）
                    String[][] qs = {{"1080p", "1080P · 无水印"}, {"720p", "720P · 无水印"}};
                    for (String[] q : qs) {
                        TweetFetcher.Media m = new TweetFetcher.Media();
                        m.url = "https://aweme.snssdk.com/aweme/v1/play/?video_id=" + vid + "&ratio=" + q[0];
                        m.kind = "video";
                        m.ext = "mp4";
                        m.thumb = poster0;
                        m.label = q[1];
                        r.items.add(m);
                    }
                } else {
                    if (!"douyin".equals(platform)) {
                        // 快手等：playwm → play 仍是有效写法
                        video = video.replace("/playwm/", "/play/").replace("playwm", "play");
                    }
                    TweetFetcher.Media m = new TweetFetcher.Media();
                    m.url = video;
                    m.kind = "video";
                    m.ext = "mp4";
                    m.thumb = poster0;
                    m.label = hls ? "HLS · 最高画质" : "视频 · 无水印";
                    r.items.add(m);
                }

                done = true;
                // 记住了：下次同类平台直接从这条候选页开始。
                // 注意记的是「当前真正成功的那条」在原始候选里的序号 ——
                // 以前存的是 pages.indexOf(pageUrl)，而 pageUrl 是第一条候选，
                // 候选被重排过就变成 -1，等于永远学不到东西。
                String okPage = pages.get(pageIdx);
                Prefs.setSvPage(web.getContext(), platform,
                        ShortVideo.pagesOf("douyin".equals(platform),
                                ShortVideo.idOf("douyin".equals(platform), pageUrl))
                                .indexOf(okPage));
                shrink();
                callback.onResult(r);
                return;
            }

            // 抖音图文作品：没有 <video>，只有一组作品图（SSR 的 videoDetail.images
            // 或 DOM 里 biz_tag=aweme_images 的图）。以前这类链接会一直等到超时然后报失败。
            if ("douyin".equals(platform) && !video.startsWith("http")) {
                org.json.JSONArray wi = o.optJSONArray("workImgs");
                if (wi != null && wi.length() > 0) {
                    TweetFetcher.Result r = new TweetFetcher.Result();
                    r.platform = platform;
                    r.tweetUrl = pageUrl;
                    r.handle = platform;
                    r.svId = ShortVideo.idOf(true, pageUrl);
                    r.author = authorName(author, platform);
                    r.text = clean(title);
                    if (r.text.isEmpty()) {
                        r.text = "来自" + r.author + "的图文";
                        r.svGeneric = true;
                    }

                    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < wi.length() && r.items.size() < 30; i++) {
                        String u = wi.optString(i, "");
                        if (!u.startsWith("http")) continue;
                        if (!seen.add(u)) continue;
                        TweetFetcher.Media m = new TweetFetcher.Media();
                        m.url = u;
                        m.kind = "photo";
                        // 抖音图集是 ~tplv-dy-aweme-images:q75.webp 这类，不能一律当 jpg
                        m.ext = u.contains(".webp") ? "webp"
                                : (u.contains(".png") ? "png" : "jpg");
                        m.thumb = u;
                        m.label = "图片 " + (r.items.size() + 1) + " · 原图";
                        r.items.add(m);
                    }
                    if (!r.items.isEmpty()) {
                        android.util.Log.i(TAG, "douyin 图文 " + r.items.size() + " 张");
                        done = true;
                        shrink();
                        callback.onResult(r);
                        return;
                    }
                }
            }

            // 小红书图文笔记：页面里没有 video，但有图片
            if ("xiaohongshu".equals(platform) && imgs != null && imgs.length() > 0) {
                TweetFetcher.Result r = new TweetFetcher.Result();
                r.platform = platform;
                r.tweetUrl = pageUrl;
                r.handle = platform;
                r.author = authorName(author, platform);
                r.text = clean(title);
                if (r.text.isEmpty()) r.text = "来自" + r.author + "的图文笔记";
                r.avatar = poster.startsWith("http") ? poster : "";

                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                for (int i = 0; i < imgs.length() && r.items.size() < 20; i++) {
                    String u = imgs.optString(i, "");
                    if (!u.startsWith("http")) continue;
                    // 只收小红书图床的图，别把图标/广告位收进来
                    if (!u.contains("xhscdn.com") && !u.contains("xiaohongshu.com")) continue;
                    u = XhsFetcher.originOf(u);
                    if (!seen.add(u)) continue;
                    TweetFetcher.Media m = new TweetFetcher.Media();
                    m.url = u;
                    m.kind = "photo";
                    m.ext = "jpg";
                    m.thumb = u;
                    m.label = "图片 " + (r.items.size() + 1) + " · 原图";
                    r.items.add(m);
                }
                if (!r.items.isEmpty()) {
                    done = true;
                    shrink();
                    callback.onResult(r);
                    return;
                }
            }
            retry();
        } catch (Exception e) {
            retry();
        }
    }

    /**
     * 快手快路径（参照 CYQawa/YunX 的 KuaishouParser，主机验证台 tests/douyin 已验证）。
     *
     * 一次 HTTP 拿作品页 SSR 数据 window.INIT_STATE，里面有：
     *   photo.caption / userName / coverUrls / mainMvUrls（视频，两条 CDN 互为镜像）
     *   / ext_params.atlas（图集：cdnList + list 路由）
     * 关键是请求头必须 iOS UA + Referer https://v.kuaishou.com/ ——
     * 换成安卓/桌面 UA 拿到的 INIT_STATE 就是空壳（这正是以前误判"只剩空壳"的原因）。
     * 快路径在任何一步失败时静默返回，WebView 嗅探继续兜底。
     */
    private static final String UA_IOS =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1";

    private void ksFastParse() {
        new Thread(() -> {
            try {
                Net.Resp resp = Net.getResp(pageUrl, new String[][]{
                        {"User-Agent", UA_IOS},
                        {"Referer", "https://v.kuaishou.com/"},
                        {"Accept-Language", "zh-CN,zh;q=0.9"},
                }, 15000);
                if (resp.code != 200) {
                    android.util.Log.i(TAG, "ks fast: HTTP " + resp.code);
                    return;
                }
                String js = extractInitState(resp.body);
                if (js == null) {
                    android.util.Log.i(TAG, "ks fast: 无 INIT_STATE（可能改版）");
                    return;
                }
                JSONObject initState = new JSONObject(js);
                // key 是「作品 id → 作品数据」映射，但 value 的 key 不总是链接里那个 id，
                // 所以遍历取第一个 photo 非空的 value（org.json 保持插入顺序，和 YunX 一致）
                JSONObject photo = null;
                java.util.Iterator<String> it = initState.keys();
                while (it.hasNext()) {
                    JSONObject v = initState.optJSONObject(it.next());
                    JSONObject p = v == null ? null : v.optJSONObject("photo");
                    if (p != null && p.length() > 0) { photo = p; break; }
                }
                if (photo == null) {
                    android.util.Log.i(TAG, "ks fast: INIT_STATE 空壳（风控或改版）");
                    return;
                }

                org.json.JSONArray mv = photo.optJSONArray("mainMvUrls");
                String video = firstCdnUrl(mv);
                if (video.isEmpty()) return;

                TweetFetcher.Result r = new TweetFetcher.Result();
                r.platform = "kuaishou";
                r.tweetUrl = pageUrl;
                r.handle = "kuaishou";
                r.svId = ShortVideo.idOf(false, pageUrl);
                // 昵称里常混 U+3164 占位空白字符，剥掉（YunX 同款处理）
                r.author = authorName(jsonStr(photo, "userName").replace("\u3164", ""), "kuaishou");
                r.text = clean(jsonStr(photo, "caption"));
                if (r.text.isEmpty()) {
                    r.text = "来自" + r.author + "的视频";
                    r.svGeneric = true;
                }
                String cover = firstCdnUrl(photo.optJSONArray("coverUrls"));

                TweetFetcher.Media m = new TweetFetcher.Media();
                m.url = video;
                m.kind = "video";
                m.ext = "mp4";
                m.thumb = cover;
                m.label = "视频 · 无水印";
                r.items.add(m);

                // 同一文件常在两条 CDN 各放一份（tymov2/txmov2…）：主链被拒时自动换备用
                for (int i = 1; i < mv.length() && i < 3; i++) {
                    String alt = jsonStr(mv.optJSONObject(i), "url");
                    if (alt.startsWith("http") && !alt.equals(video)) {
                        TweetFetcher.Media m2 = new TweetFetcher.Media();
                        m2.url = alt;
                        m2.kind = "video";
                        m2.ext = "mp4";
                        m2.thumb = cover;
                        m2.label = "备用线路 · 同画质";
                        r.items.add(m2);
                    }
                }

                // 图集（相册作品）：cdnList 第一条 CDN + list 路由拼出原图直链
                JSONObject extp = photo.optJSONObject("ext_params");
                JSONObject atlas = extp == null ? null : extp.optJSONObject("atlas");
                java.util.List<String> album = atlasUrls(atlas);
                for (int i = 0; i < album.size(); i++) {
                    TweetFetcher.Media mi = new TweetFetcher.Media();
                    mi.url = album.get(i);
                    mi.kind = "photo";
                    mi.ext = "jpg";
                    mi.thumb = mi.url;
                    mi.label = "图片 " + (i + 1) + " · 原图";
                    r.items.add(mi);
                }

                android.util.Log.i(TAG, "ks fast: OK 作者=" + r.author
                        + " 直链=" + video.substring(0, Math.min(80, video.length())));
                final TweetFetcher.Result fr = r;
                main.post(() -> {
                    if (done) return;
                    done = true;
                    shrink();
                    callback.onResult(fr);
                });
            } catch (Throwable t) {
                android.util.Log.i(TAG, "ks fast: 失败 " + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ":" + t.getMessage()));
                // 快路径失败就静默放弃，WebView 嗅探照常兜底
            }
        }, "ks-fast").start();
    }

    /**
     * 从 HTML 里完整抠出 window.INIT_STATE = {...}。
     * 不能用「找到就截到行尾」这类取巧：JSON 里有字符串和转义，
     * 只有括号配平（区分字符串内外）才截得准。
     */
    private static String extractInitState(String html) {
        if (html == null) return null;
        int i = html.indexOf("window.INIT_STATE");
        if (i < 0) return null;
        int j = html.indexOf('{', i);
        if (j < 0) return null;
        boolean inStr = false, esc = false;
        int depth = 0;
        for (int k = j; k < html.length(); k++) {
            char c = html.charAt(k);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return html.substring(j, k + 1);
            }
        }
        return null;
    }

    /** CdnUrl 数组里取第一条有 url 的 */
    private static String firstCdnUrl(org.json.JSONArray arr) {
        if (arr == null) return "";
        for (int i = 0; i < arr.length(); i++) {
            String u = jsonStr(arr.optJSONObject(i), "url");
            if (u.startsWith("http")) return u;
        }
        return "";
    }

    /** 图集直链：https://<cdn>/<route>（YunX 的 Atlas.img_urls 同款拼法） */
    private static java.util.List<String> atlasUrls(JSONObject atlas) {
        java.util.List<String> out = new ArrayList<>();
        if (atlas == null) return out;
        org.json.JSONArray cdnList = atlas.optJSONArray("cdnList");
        JSONObject cdn0 = cdnList == null ? null : cdnList.optJSONObject(0);
        String cdn = cdn0 == null ? "" : jsonStr(cdn0, "cdn");
        org.json.JSONArray list = atlas.optJSONArray("list");
        if (cdn.isEmpty() || list == null) return out;
        for (int i = 0; i < list.length() && out.size() < 30; i++) {
            String route = list.optString(i, "");
            if (!route.isEmpty()) out.add("https://" + cdn + "/" + route);
        }
        return out;
    }

    /** JSON 字段缺失或 null 一律当空串（对齐 YunX 的 jsonString） */
    private static String jsonStr(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return "";
        String s = o.optString(k, "");
        return s == null ? "" : s;
    }

    private void retry() {
        attempt++;
        // 前 12 轮密一点（页面刚加载时最容易出流），之后放慢；
        // 一条页面最多约 25 秒，超了就换下一条候选页
        int budget = 22;
        if (attempt > budget) {
            nextPage("这条页面没放出视频地址");
            return;
        }
        // 头 1 秒最容易出流，密探；后面放慢，别一直烧电
        main.postDelayed(this::probe, attempt <= 3 ? 300 : (attempt <= 12 ? 550 : 1100));
    }

    private void nextPage(String why) {
        pageIdx++;
        if (pageIdx >= pages.size()) {
            fail();
            return;
        }
        loadCurrent();
    }

    private void fail() {
        done = true;
        shrink();
        if ("douyin".equals(platform)) {
            callback.onFail("抖音网页版没放出视频地址（可能触发了验证），"
                    + "换个链接或稍后再试");
        } else if ("xiaohongshu".equals(platform)) {
            callback.onFail("小红书网页版没放出内容（可能要登录才能看），"
                    + "换个链接再试，或者在 App 里重新复制一次分享链接");
        } else {
            callback.onFail(captchaHit
                    ? "快手弹了人机验证，暂时解析不了。去快手 App 里随便刷一两个视频再回来试"
                    : "快手网页版没放出视频地址（可能要登录才能看），换个链接再试");
        }
    }

    /** 嗅探期间把隐藏 WebView 铺开（几乎透明），结束后恢复 1dp */
    private void expandForSniff() {
        try {
            final android.view.ViewGroup.LayoutParams lp = web.getLayoutParams();
            if (lp == null || lp.width > 16) return;   // 1dp ≈ 3~4px，别误放过正常尺寸
            final int w = lp.width, h = lp.height;
            float density = web.getResources().getDisplayMetrics().density;
            // 只要够播放器起流就行，不用铺满整屏——大页面整屏合成会拖慢手机
            lp.width = (int) (340 * density);
            lp.height = (int) (600 * density);
            web.setLayoutParams(lp);
            web.setAlpha(0.01f);
            web.setTranslationY(100000f);   // 挪到屏幕外，别占着可见区域
            web.bringToFront();
            main.postDelayed(this::shrink, 60000);
        } catch (Throwable ignored) { }
    }

    private void shrink() {
        try {
            web.setTranslationY(0f);
            android.view.ViewGroup.LayoutParams lp = web.getLayoutParams();
            float density = web.getResources().getDisplayMetrics().density;
            if (lp == null || lp.width <= 16) return;
            lp.width = (int) density;
            lp.height = (int) density;
            web.setLayoutParams(lp);
            web.setAlpha(0f);
        } catch (Throwable ignored) { }
    }

    /** 抖音/快手打不开作品时会落到通用页，标题长这样 */
    private static boolean isDeadPage(String title) {
        if (title == null) return false;
        String t = title.trim();
        // 注意：快手 H5 分享页的 document.title 本来就是空的（标题在 JS 里渲染），
        // 以前把「空标题」也算成死页面 —— 于是移动 UA 下刚加载两轮就被判死、
        // 直接换页，最后报「没放出视频地址」。空标题不算死。
        return t.equals("在抖音记录美好生活") || t.startsWith("在抖音记录美好生活")
                || t.equals("抖音-记录美好生活") || t.equals("快手")
                || t.equals("短视频-快手") || t.equals("抖音");
    }

    /**
     * 从抖音播放地址里抠出 video_id。
     *
     * 例：https://m.douyin.com/aweme/v1/playwm/?line=0&logo_name=aweme_diversion_search
     *     &ratio=720p&video_id=v0d00fg10000d72g6vnog65joudlshs0
     * 有了它就能用官方 play 端点现算一条「新鲜签名 + 无水印」的直链，
     * 而不是把几分钟就会失效的 CDN 地址存下来（这正是以前「下载 404」的根因）。
     */
    private static String videoIdOf(String u) {
        if (u == null || u.isEmpty()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[?&]video_id=([A-Za-z0-9_\\-]+)").matcher(u);
        return m.find() ? m.group(1) : "";
    }

    private static boolean isHlsUrl(String u) {        String low = u.toLowerCase();
        int q = low.indexOf('?');
        String path = q > 0 ? low.substring(0, q) : low;
        return path.endsWith(".m3u8") || low.contains(".m3u8?") || low.contains("/playlist/");
    }

    /**
     * 记录播放器真正去拉的流地址。
     *
     * 注意：抖音的流地址长这样，路径里根本没有 .mp4：
     *   https://v26-web.douyinvod.com/xxxx/video/tos/cn/tos-cn-ve-15/xxx/?a=6383&br=...&mime_type=video_mp4
     * 所以不能只看后缀，得认 CDN 域名和 mime_type 参数，否则一条都截不到。
     */
    private static final String TAG = "DownX-SVS";

    private void note(String u) {
        String low = u.toLowerCase();
        if (low.contains("trafficjunky") || low.contains("/ads/") || low.contains("doubleclick")) {
            return;
        }
        // 快手人机验证：一旦弹验证，页面背后全是推荐流的别人的视频，
        // 这时候旁听到的任何"视频"都不是用户要的那条 —— 记下来，探测时直接换页
        if (low.contains("captcha.zt.kuaishou.com")) captchaHit = true;
        // 封面先记一笔：快手 H5 的封面是 <作品id>.jpg&clientCacheKey=<id>.jpg，
        // 和视频流一起发出来。页面 DOM 里经常拿不到 og:image（H5 是 JS 渲染的），
        // 这里从网络层捞是最稳的 —— 预览黑屏就是缺这张图。
        if (sniffedCover.isEmpty() && low.contains("clientcachekey=")
                && (low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp")
                || low.contains(".jpg?") || low.contains(".jpeg?") || low.contains(".webp?"))) {
            sniffedCover = u;
        }
        if (low.contains(".mp3") || low.contains(".m4a") || low.contains("/audio/")) return;

        // 快手：只认本作品的流。作品页 feed 会预加载「下一集」、验证码页背后还有
        // 别人的视频 —— 旁听到一个不相干的地址，下回来要么是空文件要么是错误图。
        // 本作品的流一定带作品 ID（clientCacheKey=<id>_b.mp4 或路径里有）。
        if ("kuaishou".equals(platform)) {
            String wid = ShortVideo.idOf(false, pageUrl);
            if (wid != null && !wid.isEmpty() && !low.contains(wid)) return;
        }

        if (isHlsUrl(low)) {
            boolean haveMaster = sniffed.toLowerCase().contains("master");
            if (sniffed.isEmpty() || !sniffedHls
                    || (low.contains("master") && !haveMaster)) {
                sniffed = u;
                sniffedHls = true;
            }
            return;
        }

        if (!looksLikeVideo(low)) return;
        android.util.Log.i(TAG, "sniff " + u.substring(0, Math.min(160, u.length())));
        int score = qualityScore(low);
        if (sniffed.isEmpty() || (!sniffedHls && score > mp4Score)) {
            sniffed = u;
            sniffedHls = false;
            mp4Score = score;
        }
    }

    /** 视频流判定：后缀 / 平台 CDN 域名 / mime 参数，三者命中其一即可 */
    private static boolean looksLikeVideo(String low) {
        int q = low.indexOf('?');
        String path = q > 0 ? low.substring(0, q) : low;
        if (path.endsWith(".mp4") || path.endsWith(".flv")) return true;
        // 快手 CDN 域名上什么都跑：脚本、样式、图片、日志全在 kwimgs/yximgs 上，
        // 只按域名匹配会把 .js 当成视频记进来 —— 先把非媒体资源挡掉
        if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".html")
                || path.endsWith(".htm") || path.endsWith(".json")
                || path.endsWith(".jpg") || path.endsWith(".jpeg")
                || path.endsWith(".png") || path.endsWith(".webp")
                || path.endsWith(".gif") || path.endsWith(".svg")
                || path.endsWith(".woff") || path.endsWith(".woff2")) {
            return false;
        }
        if (low.contains("mime_type=video") || low.contains("mime_type%3dvideo")
                || low.contains("mime_type=video_mp4")) {
            return true;
        }
        if (path.contains("/video/tos/")) return true;
        // 平台自己的视频 CDN
        String[] hosts = {"douyinvod.com", "byteoversea.com", "douyinpic.com/video",
                "kwaicdn.com", "ksycdn", "yximgs.com", "kspkg.com", "kwimgs.com"};
        for (String hh : hosts) {
            if (low.contains(hh)) return true;
        }
        return false;
    }

    /** 从「720P_4000K_...」这类文件名里算画质分，或从 br= 码率取，挑最高的那档 */
    private static int qualityScore(String path) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{3,4})p_(\\d{3,4})k").matcher(path);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1)) * 10000
                        + Integer.parseInt(m.group(2));
            } catch (Throwable t) {
                return 0;
            }
        }
        java.util.regex.Matcher b = java.util.regex.Pattern
                .compile("[?&]br=(\\d{3,6})").matcher(path);
        if (b.find()) {
            try {
                return Integer.parseInt(b.group(1));
            } catch (Throwable t) {
                return 0;
            }
        }
        return 0;
    }

    private static String nameOf(String platform) {
        if ("douyin".equals(platform)) return "抖音";
        if ("xiaohongshu".equals(platform)) return "小红书";
        return "快手";
    }

    /** 页面里抓到的昵称优先，抓不到才退回平台名（总比文件名全是「抖音」好） */
    private static String authorName(String scraped, String platform) {
        String s = scraped == null ? "" : scraped.trim();
        s = s.replaceAll("^@", "").replaceAll("\\s+", " ").trim();
        // 昵称里常混进「关注」「已关注」这类按钮文案
        s = s.replaceAll("(已关注|关注|粉丝|获赞)$", "").trim();
        if (s.length() < 1 || s.length() > 24) return nameOf(platform);
        return s;
    }

    /** 页面标题里常带一堆后缀，掐掉一部分更好看 */
    private static String clean(String t) {
        if (t == null) return "";
        String s = t.replace("- 抖音", "").replace("- 快手", "").replace("- 小红书", "")
                .replace("抖音", "").replace("快手", "").replace(" - 小红书", "").trim();
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

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
