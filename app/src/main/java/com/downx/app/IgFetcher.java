package com.downx.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instagram 解析。总规矩（2026-11-09 用户定死，别再串改）：
 * 分流开关只有一个布尔 loggedIn() —— 登录为「真」，未登录为「假」，两条并行线路各自完整：
 *
 * 真线（已登录，带 sessionid）：
 *   ① webapi —— 网页版 media info，一次拿 DASH 全档清晰度（主力）
 *   ② embed HTML —— 接口被限流时兜底，至少能下单档
 * 假线（未登录，匿名 cookie）：
 *   ① graphql —— 网页前端给匿名访客的接口（yt-dlp 匿名下载同款），结构化 JSON
 *   ② embed HTML —— graphql 被风控时兜底
 *   ③ 镜像站 —— IG 全被拒时最后一条路
 * 会话中途被 IG 判死（401）时真线退场、假线自动接力。
 */
public class IgFetcher {

    private static final String TAG = "DownX-IG";

    private static final String WEB_APP_ID = "936619743392459";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final Pattern P_CODE =
            Pattern.compile("instagram\\.com/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)");
    private static final Pattern P_VIDEO_URL = Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_DISPLAY_URL = Pattern.compile("\"display_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_OG_VIDEO =
            Pattern.compile("<meta[^>]+property=\"og:video\"[^>]+content=\"([^\"]+)\"");
    private static final Pattern P_OG_IMAGE =
            Pattern.compile("<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"");
    private static final Pattern P_OG_TITLE =
            Pattern.compile("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"");
    private static final Pattern P_VIDEO_TAG = Pattern.compile("<video[^>]+src=\"([^\"]+)\"");

    /** 上一次解析各通道的诊断信息，报错时带给用户 */
    public static String lastLog = "";

    public static String shortcode(String link) {
        Matcher m = P_CODE.matcher(link);
        return m.find() ? m.group(1) : null;
    }

    public static TweetFetcher.Result fetchSync(String link) throws Exception {
        String code = shortcode(link);
        if (code == null) throw new Exception("没有识别到 Instagram 帖子链接（支持 /p/、/reel/）");

        TweetFetcher.Result r = new TweetFetcher.Result();
        r.platform = "instagram";
        r.tweetUrl = "https://www.instagram.com/p/" + code + "/";

        String pk = shortcodeToId(code);
        // ★ 唯一的分流开关：真=登录线，假=匿名线。通道只在各自线内排序，互不借用
        boolean logged = loggedIn();
        Log.i(TAG, "start code=" + code + " pk=" + pk
                + " lane=" + (logged ? "AUTH(真)" : "ANON(假)"));
        StringBuilder log = new StringBuilder();

        if (logged) {
            // ── 真线 ──
            try {
                viaWebApi(code, pk, r);
            } catch (Exception e) {
                log.append("webapi:").append(brief(e)).append(' ');
            }
            if (r.items.isEmpty()) {
                try { viaEmbed(code, r); } catch (Exception e) {
                    log.append("embed:").append(brief(e)).append(' ');
                }
            }
        } else {
            // ── 假线 ──
            try {
                viaGraphql(code, r);
            } catch (Exception e) {
                log.append("gql:").append(brief(e)).append(' ');
            }
            if (r.items.isEmpty()) {
                try { viaEmbed(code, r); } catch (Exception e) {
                    log.append("embed:").append(brief(e)).append(' ');
                }
            }
            if (r.items.isEmpty()) {
                try { viaMirror(code, r); } catch (Exception e) {
                    log.append("mirror:").append(brief(e)).append(' ');
                }
            }
        }

        // 真线打完发现会话刚好被 IG 判死（401 → markExpired）：假线接力，用户无感
        if (r.items.isEmpty() && logged && !loggedIn()) {
            Log.i(TAG, "会话中途失效，假线接力");
            try {
                viaGraphql(code, r);
            } catch (Exception e) {
                log.append("gql*").append(brief(e)).append(' ');
            }
            if (r.items.isEmpty()) {
                try { viaEmbed(code, r); } catch (Exception e) {
                    log.append("embed*").append(brief(e)).append(' ');
                }
            }
        }

        if (!r.items.isEmpty()) return done(r, logged ? "真线" : "假线");

        lastLog = log.toString().trim();
        Log.w(TAG, "lane " + (logged ? "AUTH" : "ANON") + " all failed: " + lastLog);
        String tip = loggedIn()
                ? "（登录线路：可能这条是私密帖且你没关注，或帖子已删）"
                : "（匿名线路：公开帖正常可下；失败多为私密帖、帖子已删、代理未生效，"
                  + "或 DownX 没被加进 VPN 的代理应用列表。登录后可解锁更多）";
        throw new Exception("Instagram 解析失败" + tip + "。诊断：" + lastLog);
    }

    private static TweetFetcher.Result done(TweetFetcher.Result r, String channel) {
        Log.i(TAG, "ok via " + channel + " items=" + r.items.size());
        return r;
    }

    // ---------- 通道 1：网页版 media info ----------

    private static void viaWebApi(String code, String pk, TweetFetcher.Result r) throws Exception {
        if (pk == null) throw new Exception("bad shortcode");
        String cookie = cookieOrAnon();
        List<String[]> hs = new ArrayList<>();
        hs.add(new String[]{"User-Agent", MOBILE_UA});
        hs.add(new String[]{"x-ig-app-id", WEB_APP_ID});
        hs.add(new String[]{"x-asbd-id", "129477"});
        hs.add(new String[]{"x-ig-www-claim", "0"});
        hs.add(new String[]{"x-requested-with", "XMLHttpRequest"});
        hs.add(new String[]{"Referer", "https://www.instagram.com/p/" + code + "/"});
        hs.add(new String[]{"Accept", "*/*"});
        hs.add(new String[]{"Accept-Language", "en-US,en;q=0.9"});
        hs.add(new String[]{"Cookie", cookie});
        String csrf = cookieValue(cookie, "csrftoken");
        if (!csrf.isEmpty()) hs.add(new String[]{"X-CSRFToken", csrf});
        String url = "https://www.instagram.com/api/v1/media/" + pk + "/info/";
        Net.Resp resp = getRetry(url, hs.toArray(new String[0][]), 15000);
        Log.i(TAG, "webapi " + resp.code + " " + resp.head(120));
        if (resp.code != 200) throw new Exception(why(resp));
        parseItemsJson(resp.body, r);
    }

    // ---------- 假线①：匿名 GraphQL ----------

    /**
     * 网页前端给匿名访客用的接口，yt-dlp / gallery-dl 匿名下载 IG 用的就是它。
     * 带匿名 cookie（mid/csrftoken/ig_did）+ 固定 doc_id 即可，
     * 返回 data.xdt_shortcode_media —— parseItemsJson 已兼容这个结构。
     */
    private static void viaGraphql(String code, TweetFetcher.Result r) throws Exception {
        String vars = "{\"shortcode\":\"" + code
                + "\",\"fetch_tagged_user_count\":null,\"hoisted_comment_id\":null,"
                + "\"hoisted_reply_id\":null}";
        String cookie = anonCookie();
        String csrf = cookieValue(cookie, "csrftoken");
        String lsd = sLsd.isEmpty() ? "AVo0fg_n" : sLsd;
        Log.i(TAG, "graphql 前置 lsd=" + (sLsd.isEmpty() ? "缺" : "有")
                + " csrf=" + (csrf.isEmpty() ? "缺" : "有"));
        String form = "av=0&__d=www&__user=0&__a=1&__req=k&__ccg=UNKNOWN&__comet_req=7&dpr=1"
                + "&lsd=" + enc(lsd)
                + "&doc_id=8845758582119845"
                + "&variables=" + enc(vars);
        List<String[]> hs = new ArrayList<>();
        hs.add(new String[]{"User-Agent", MOBILE_UA});
        hs.add(new String[]{"Content-Type", "application/x-www-form-urlencoded"});
        hs.add(new String[]{"X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery"});
        hs.add(new String[]{"X-IG-App-ID", WEB_APP_ID});
        hs.add(new String[]{"X-FB-LSD", lsd});
        if (!csrf.isEmpty()) hs.add(new String[]{"X-CSRFToken", csrf});
        hs.add(new String[]{"X-ASBD-ID", "129477"});
        hs.add(new String[]{"Origin", "https://www.instagram.com"});
        hs.add(new String[]{"Sec-Fetch-Site", "same-origin"});
        hs.add(new String[]{"Sec-Fetch-Mode", "cors"});
        hs.add(new String[]{"Sec-Fetch-Dest", "empty"});
        hs.add(new String[]{"Referer", "https://www.instagram.com/p/" + code + "/"});
        hs.add(new String[]{"Cookie", cookie});
        Net.Resp resp = null;
        // 403 多半是匿名 cookie/LSD 被风控盯上：强制换一套新的再试一次
        for (int tryi = 0; tryi < 2; tryi++) {
            if (tryi == 1) {
                synchronized (IgFetcher.class) { sAnon = null; sAnonAt = 0; sLsd = ""; }
                cookie = anonCookie();
                csrf = cookieValue(cookie, "csrftoken");
                lsd = sLsd.isEmpty() ? lsd : sLsd;
                setHeader(hs, "Cookie", cookie);
                setHeader(hs, "X-CSRFToken", csrf.isEmpty() ? "x" : csrf);
                setHeader(hs, "X-FB-LSD", lsd);
                Log.i(TAG, "graphql 403 后换新 cookie 重试 lsd=" + (sLsd.isEmpty() ? "缺" : "有"));
            }
            resp = Net.postForm("https://www.instagram.com/graphql/query",
                    hs.toArray(new String[0][]), form, 15000);
            Log.i(TAG, "graphql " + resp.code + " " + resp.head(160));
            if (resp.code == 200) break;
        }
        if (resp.code != 200) throw new Exception(why(resp));
        parseItemsJson(resp.body, r);
    }

    private static void setHeader(List<String[]> hs, String key, String value) {
        for (int i = 0; i < hs.size(); i++) {
            if (hs.get(i)[0].equals(key)) { hs.set(i, new String[]{key, value}); return; }
        }
        hs.add(new String[]{key, value});
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    // ---------- 通道：embed 页 ----------

    private static void viaEmbed(String code, TweetFetcher.Result r) throws Exception {
        String url = "https://www.instagram.com/p/" + code + "/embed/captioned/";
        String[][] hs = {
                {"User-Agent", MOBILE_UA},
                {"Referer", "https://www.instagram.com/"},
                {"Accept", "text/html,application/xhtml+xml"}
        };
        Net.Resp resp = Net.getResp(url, hs, 15000);
        Log.i(TAG, "embed " + resp.code + " len=" + resp.body.length() + " " + resp.head(80));
        if (resp.code != 200) throw new Exception("HTTP " + resp.code);
        String html = resp.body;

        String video = first(P_VIDEO_URL, html);
        String img = first(P_DISPLAY_URL, html);
        if (video == null) video = first(P_VIDEO_TAG, html);
        if (img == null) img = first(P_OG_IMAGE, html);

        if (r.text.isEmpty()) {
            String cap = jsonField(html, "text");
            if (cap != null) r.text = unescape(cap);
        }
        if (r.handle.isEmpty()) {
            String user = jsonField(html, "username");
            if (user != null) {
                r.handle = unescape(user);
                r.author = r.handle;
            }
        }

        if (video != null && !video.contains("blob:")) {
            addVideo(r, unescape(video), img == null ? null : unescape(img), 0, 0, "视频 · 最佳画质");
            if (img != null) addPhoto(r, unescape(img), "封面图 · 原图");
        } else if (img != null) {
            addPhoto(r, unescape(img), "图片 · 原图");
        }
        if (r.items.isEmpty()) throw new Exception("no media");
    }

    // ---------- 通道：镜像 ----------

    private static void viaMirror(String code, TweetFetcher.Result r) throws Exception {
        String[] mirrors = {
                "https://ddinstagram.com/p/" + code,
                "https://kkinstagram.com/p/" + code,
                "https://www.ddinstagram.com/p/" + code
        };
        Exception last = null;
        for (String url : mirrors) {
            Net.Resp resp = Net.getResp(url, new String[][]{{"User-Agent", MOBILE_UA}}, 12000);
            Log.i(TAG, "mirror " + url + " -> " + resp.code);
            if (resp.code != 200) {
                last = new Exception("HTTP " + resp.code);
                continue;
            }
            String video = first(P_OG_VIDEO, resp.body);
            String img = first(P_OG_IMAGE, resp.body);
            String title = first(P_OG_TITLE, resp.body);
            if (video != null) {
                addVideo(r, video, img, 0, 0, "视频 · 最佳画质");
            } else if (img != null) {
                addPhoto(r, img, "图片 · 原图");
            }
            if (title != null && r.text.isEmpty()) r.text = unescape(title);
            if (!r.items.isEmpty()) return;
        }
        throw last == null ? new Exception("mirror failed") : last;
    }

    // ---------- 解析 JSON（v1 / graphql 都兼容） ----------

    private static void parseItemsJson(String body, TweetFetcher.Result r) throws Exception {
        JSONObject j = new JSONObject(body);
        if (j.has("items")) {
            parseV1Items(j.getJSONArray("items"), r);
            return;
        }
        if (j.has("graphql")) {
            parseShortcodeMedia(j.getJSONObject("graphql").getJSONObject("shortcode_media"), r);
            return;
        }
        if (j.has("data")) {
            JSONObject data = j.optJSONObject("data");
            if (data != null) {
                JSONObject sm = data.optJSONObject("xdt_shortcode_media");
                if (sm == null) sm = data.optJSONObject("shortcode_media");
                if (sm != null) {
                    parseShortcodeMedia(sm, r);
                    return;
                }
            }
        }
        String msg = j.optString("message", "");
        throw new Exception(msg.isEmpty() ? "no items" : msg);
    }

    private static void parseV1Items(JSONArray items, TweetFetcher.Result r) throws Exception {
        if (items.length() == 0) throw new Exception("empty items");
        JSONObject it = items.getJSONObject(0);
        JSONObject user = it.optJSONObject("user");
        if (user != null) {
            if (r.handle.isEmpty()) r.handle = user.optString("username");
            String full = user.optString("full_name", "");
            if (!full.isEmpty()) r.author = full;
            String pic = user.optString("profile_pic_url", "");
            if (!pic.isEmpty()) r.avatar = pic;
        }
        JSONObject cap = it.optJSONObject("caption");
        if (cap != null && r.text.isEmpty()) r.text = cap.optString("text");
        long taken = it.optLong("taken_at", 0);
        if (taken > 0 && r.date.isEmpty()) {
            r.date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(taken * 1000L));
        }

        JSONArray carousel = it.optJSONArray("carousel_media");
        if (carousel != null) {
            for (int i = 0; i < carousel.length(); i++) {
                addV1Media(carousel.getJSONObject(i), r, carousel.length() > 1 ? i + 1 : 0);
            }
        } else {
            addV1Media(it, r, 0);
        }
    }

    /** 一条媒体：视频把所有清晰度都列出来，图片取最大的一张 */
    private static void addV1Media(JSONObject m, TweetFetcher.Result r, int index) {
        JSONArray vv = m.optJSONArray("video_versions");
        String img = bestImage(m);

        // IG 的 video_versions 现在经常只给一档（实测 1 条），真正的多档在 DASH 清单里：
        // 每个 <Representation> 是一档（常见 360/720/1080），BaseURL 就是那一档的直链。
        // 想在下载页挑分辨率，就得从这儿取。
        Dash dash = dashVariants(m);
        // IG 的 DASH 把画面和声音拆成两条流：只取视频那几档、不带音轨，
        // 下出来就是「有画面没声音」。所以必须同时拿到 audio 的地址才走 dash，
        // 拿不到就退回 video_versions（那条是音画合一的完整文件）。
        if (addDashVideos(r, dash, img, index)) {
            if (img != null && index == 0) addPhoto(r, img, "封面图 · 原图");
            return;
        }
        if (dash.videos.size() >= 2) {
            Log.i(TAG, "dash 有 " + dash.videos.size() + " 档但没音轨，退回完整文件");
        }

        if (vv != null && vv.length() > 0) {
            List<int[]> dims = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < vv.length(); i++) {
                JSONObject v = vv.optJSONObject(i);
                if (v == null) continue;
                String u = v.optString("url", "");
                if (u.isEmpty()) continue;
                if (urls.contains(u)) continue;
                urls.add(u);
                dims.add(new int[]{v.optInt("width", 0), v.optInt("height", 0)});
            }
            // 大分辨率排前面
            Integer[] order = new Integer[urls.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return shortSide(dims.get(b)) - shortSide(dims.get(a));
                }
            });
            int count = urls.size();
            Log.i(TAG, "video_versions=" + count + " img=" + (img != null));
            for (int k = 0; k < count; k++) {
                int i = order[k];
                int w = dims.get(i)[0], h = dims.get(i)[1];
                String label = qualityLabel(w, h, k, count);
                if (index > 0) label = "视频 " + index + " · " + label;
                addVideo(r, urls.get(i), img, w, h, label);
            }
            // 视频帖也把封面原图列出来，想存封面图不用另找工具
            if (img != null && index == 0) addPhoto(r, img, "封面图 · 原图");
        } else if (img != null) {
            addPhoto(r, img, index > 0 ? "图片 " + index + " · 原图" : "图片 · 原图");
        }
    }

    /**
     * 把 DASH 里的多档视频列出来。每档都是**纯视频流**，必须把 dash.audio 一起带上，
     * 下载器下完会合轨；没音轨就返回 false，让调用方退回音画合一的单文件。
     */
    private static boolean addDashVideos(TweetFetcher.Result r, Dash dash, String img, int index) {
        if (dash == null || dash.videos.size() < 2 || dash.audio.isEmpty()) return false;
        Log.i(TAG, "dash video=" + dash.videos.size() + " audio=ok");
        int n = dash.videos.size();
        for (int k = 0; k < n; k++) {
            String[] v = dash.videos.get(k);
            int w = parseInt(v[1]), h = parseInt(v[2]);
            String label = qualityLabel(w, h, k, n);
            if (index > 0) label = "视频 " + index + " · " + label;
            TweetFetcher.Media mm = addVideoItem(r, v[0], img, w, h, label);
            if (mm != null) mm.audioUrl = dash.audio;
        }
        return true;
    }

    private static void parseShortcodeMedia(JSONObject sm, TweetFetcher.Result r) throws Exception {
        JSONObject owner = sm.optJSONObject("owner");
        if (owner != null) {
            if (r.handle.isEmpty()) r.handle = owner.optString("username");
            if (r.author.isEmpty()) r.author = owner.optString("full_name", r.handle);
        }
        if (r.text.isEmpty()) {
            JSONObject capEdge = sm.optJSONObject("edge_media_to_caption");
            if (capEdge != null) {
                JSONArray edges = capEdge.optJSONArray("edges");
                if (edges != null && edges.length() > 0) {
                    r.text = edges.getJSONObject(0).getJSONObject("node").optString("text");
                }
            }
        }
        long taken = sm.optLong("taken_at_timestamp", 0);
        if (taken > 0 && r.date.isEmpty()) {
            r.date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(taken * 1000L));
        }

        JSONObject sidecar = sm.optJSONObject("edge_sidecar_to_children");
        if (sidecar != null && sidecar.optJSONArray("edges") != null) {
            JSONArray edges = sidecar.getJSONArray("edges");
            for (int i = 0; i < edges.length(); i++) {
                addGraphqlMedia(edges.getJSONObject(i).getJSONObject("node"), r,
                        edges.length() > 1 ? i + 1 : 0);
            }
        } else {
            addGraphqlMedia(sm, r, 0);
        }
    }

    private static void addGraphqlMedia(JSONObject n, TweetFetcher.Result r, int index) {
        String video = n.optString("video_url");
        String img = n.optString("display_url");
        if (!video.isEmpty() && !"null".equals(video)) {
            // graphql 里也带 DASH 清单（dash_info）：有就列多档，没有才退回单条 video_url
            if (!addDashVideos(r, dashVariants(n), img, index)) {
                String label = "视频 · 最佳画质";
                if (index > 0) label = "视频 " + index + " · 最佳画质";
                addVideo(r, video, img, 0, 0, label);
            }
            if (index == 0 && !img.isEmpty()) addPhoto(r, img, "封面图 · 原图");
        } else if (!img.isEmpty()) {
            addPhoto(r, img, index > 0 ? "图片 " + index + " · 原图" : "图片 · 原图");
        }
    }

    // ---------- 媒体条目 ----------

    private static void addVideo(TweetFetcher.Result r, String url, String thumb,
                                 int w, int h, String label) {
        addVideoItem(r, url, thumb, w, h, label);
    }

    private static TweetFetcher.Media addVideoItem(TweetFetcher.Result r, String url, String thumb,
                                                   int w, int h, String label) {
        if (url == null || url.isEmpty()) return null;
        for (TweetFetcher.Media old : r.items) {
            if (url.equals(old.url)) return null;
        }
        TweetFetcher.Media m = new TweetFetcher.Media();
        m.url = url;
        m.thumb = thumb;
        m.label = label;
        m.kind = "video";
        m.ext = "mp4";
        r.items.add(m);
        return m;
    }

    private static void addPhoto(TweetFetcher.Result r, String url, String label) {
        if (url == null || url.isEmpty()) return;
        TweetFetcher.Media m = new TweetFetcher.Media();
        m.url = url;
        m.thumb = url;
        m.label = label;
        m.kind = "photo";
        m.ext = "jpg";
        r.items.add(m);
    }

    /** 按短边给清晰度起名：1080×1920 → 1080p，多个时带上第一个是"最佳" */
    private static String qualityLabel(int w, int h, int k, int count) {
        String res;
        if (w <= 0 || h <= 0) {
            res = "标准";
        } else {
            res = Math.min(w, h) + "p";
        }
        if (count <= 1) return "视频 · " + res;
        String tag;
        if (k == 0) tag = "最高";
        else if (k == 1) tag = "高清";
        else if (k == 2) tag = "流畅";
        else tag = "备选 " + k;
        return tag + " · " + res;
    }

    /** DASH 解析结果：视频各档 + 一条音轨 */
    private static final class Dash {
        final List<String[]> videos = new ArrayList<>();   // {url, width, height}
        String audio = "";
    }

    /**
     * 解析 video_dash_manifest（完整 MPD，含 video 和 audio 两个 AdaptationSet）。
     * 只取 video 那几档会得到无声文件，所以要把 audio 的 BaseURL 一起取出来。
     */
    private static Dash dashVariants(JSONObject m) {
        Dash d = new Dash();
        String xml = m.optString("video_dash_manifest", "");
        // graphql 那条路把清单放在 dash_info 里，不认这个字段就永远只有一档
        if (xml == null || xml.isEmpty()) {
            JSONObject di = m.optJSONObject("dash_info");
            if (di != null) xml = di.optString("video_dash_manifest", "");
        }
        if (xml == null || xml.isEmpty()) return d;

        Matcher as = Pattern.compile("<AdaptationSet([^>]*)>(.*?)</AdaptationSet>",
                Pattern.DOTALL).matcher(xml);
        boolean anySet = false;
        while (as.find()) {
            anySet = true;
            String setAttrs = as.group(1);
            String inner = as.group(2);
            String setMime = attrStr(setAttrs, "mimeType");
            boolean setAudio = setMime.toLowerCase().contains("audio");
            Matcher rep = Pattern.compile("<Representation([^>]*)>(.*?)</Representation>",
                    Pattern.DOTALL).matcher(inner);
            while (rep.find()) {
                String attrs = rep.group(1);
                String body = rep.group(2);
                String url = first(Pattern.compile("<BaseURL>([^<]+)</BaseURL>"), body);
                if (url == null || url.trim().isEmpty()) continue;
                String u = unescape(url.trim());
                String rmime = attrStr(attrs, "mimeType").toLowerCase();
                boolean audio = setAudio || rmime.contains("audio");
                if (audio) {
                    if (d.audio.isEmpty()) d.audio = u;
                    continue;
                }
                int w = attrInt(attrs, "width");
                int h = attrInt(attrs, "height");
                if (h == 0) h = attrInt(setAttrs, "height");
                if (w == 0) w = attrInt(setAttrs, "width");
                boolean dup = false;
                for (String[] o : d.videos) if (o[0].equals(u)) { dup = true; break; }
                if (dup) continue;
                d.videos.add(new String[]{u, String.valueOf(w), String.valueOf(h)});
            }
        }

        // 没有 AdaptationSet 包裹的老格式：直接扫 Representation（这时通常只有视频）
        if (!anySet) {
            Matcher rep = Pattern.compile("<Representation([^>]*)>(.*?)</Representation>",
                    Pattern.DOTALL).matcher(xml);
            while (rep.find()) {
                String attrs = rep.group(1);
                String body = rep.group(2);
                String url = first(Pattern.compile("<BaseURL>([^<]+)</BaseURL>"), body);
                if (url == null || url.trim().isEmpty()) continue;
                d.videos.add(new String[]{unescape(url.trim()),
                        String.valueOf(attrInt(attrs, "width")),
                        String.valueOf(attrInt(attrs, "height"))});
            }
        }

        Collections.sort(d.videos, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return parseInt(b[2]) - parseInt(a[2]);   // 高的排前面
            }
        });
        Log.i(TAG, "dash: videos=" + d.videos.size() + " audio=" + (d.audio.isEmpty() ? "-" : "ok"));
        return d;
    }

    private static String attrStr(String attrs, String key) {
        Matcher m = Pattern.compile(key + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : "";
    }

    private static int attrInt(String attrs, String key) {
        Matcher m = Pattern.compile(key + "=\"(\\d+)\"").matcher(attrs);
        return m.find() ? parseInt(m.group(1)) : 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int shortSide(int[] dim) {
        if (dim == null || dim[0] <= 0 || dim[1] <= 0) return 0;
        return Math.min(dim[0], dim[1]);
    }

    private static String bestImage(JSONObject m) {
        JSONObject iv2 = m.optJSONObject("image_versions2");
        if (iv2 == null) return null;
        JSONArray cands = iv2.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) return null;
        String best = null;
        int bestArea = -1;
        for (int i = 0; i < cands.length(); i++) {
            JSONObject o = cands.optJSONObject(i);
            if (o == null) continue;
            int area = o.optInt("width", 0) * o.optInt("height", 0);
            if (area > bestArea) {
                bestArea = area;
                best = o.optString("url", null);
            }
        }
        return best;
    }

    // ---------- 工具 ----------

    static String shortcodeToId(String code) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        BigInteger id = BigInteger.ZERO;
        for (int i = 0; i < code.length(); i++) {
            int v = alphabet.indexOf(code.charAt(i));
            if (v < 0) return null;
            id = id.multiply(BigInteger.valueOf(64)).add(BigInteger.valueOf(v));
        }
        return id.toString();
    }

    /**
     * 发请求用的 cookie：登录了就带登录态，没登录也带一份匿名 cookie。
     *
     * 注意：匿名 cookie 现在**已经不够用了**。IG 对无 sessionid 的请求统一回
     * {@code 401 {"require_login":true}}，连公开帖的 embed 页都只剩空壳。
     * 所以这里的顺序是登录态优先，匿名只是让"未登录时的报错更准确"。
     */
    private static String cookieOrAnon() {
        String login = IgSession.cached();
        if (IgSession.usable(login)) {
            // 登录 cookie 里已经带 csrftoken/mid，补两个 IG 网页端固定字段即可
            return login + "; ig_nrcb=1; dpr=2";
        }
        return anonCookie();
    }

    /** 未登录时给用户的提示要用不同的说法（这不是"帖子被删了"，是没给身份） */
    static boolean loggedIn() {
        return IgSession.usable(IgSession.cached());
    }

    private static String sAnon;
    private static long sAnonAt;
    /** 首页 HTML 里带出的 LSD token，graphql POST 要一起带上 */
    private static String sLsd = "";

    private static synchronized String anonCookie() {
        long now = System.currentTimeMillis();
        if (sAnon != null && now - sAnonAt < 30 * 60 * 1000L) return sAnon;
        try {
            Net.Resp r = Net.getResp("https://www.instagram.com/", new String[][]{
                    {"User-Agent", MOBILE_UA},
                    {"Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"},
                    {"Accept-Language", "en-US,en;q=0.9"}
            }, 15000);
            Log.i(TAG, "anon warm " + r.code + " cookies=" + (r.cookies == null ? 0 : r.cookies.length()));
            if (r.cookies != null && !r.cookies.isEmpty()) {
                sAnon = r.cookies + "; ig_nrcb=1; dpr=2";
                sAnonAt = now;
            }
            // 顺手取 LSD：没有它 graphql 部分风控会拒。注意真实格式是
            // ["LSD",[],{"token":"..."}]，中间的 [] 不能漏（漏了就永远"缺"）
            if (r.body != null) {
                java.util.regex.Matcher m = Pattern.compile(
                        "\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"").matcher(r.body);
                if (m.find()) sLsd = m.group(1);
            }
        } catch (Throwable t) {
            Log.w(TAG, "anon warm fail " + t);
        }
        if (sAnon == null) sAnon = "ig_nrcb=1; dpr=2";
        return sAnon;
    }

    /**
     * 媒体信息接口偶尔在处理第一条请求时与代理握手超时（Net 返回 -1），
     * 这类失败和帖子本身无关，重试一次基本就过。只重试网络层错误，
     * HTTP 层的拒绝（401/400/404）照原样往上抛，别把错误提示搅浑。
     */
    private static Net.Resp getRetry(String url, String[][] hs, int timeoutMs) {
        Net.Resp resp = Net.getResp(url, hs, timeoutMs);
        if (resp.code == -1) {
            Log.w(TAG, "net fail, retry once " + url);
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            resp = Net.getResp(url, hs, timeoutMs);
        }
        // 会话续期：IG 会在响应里轮换 sessionid，不写回本地就会莫名"过几天失效"
        if (resp.code == 200) {
            IgSession.mergeFromResponse(resp.cookies);
        } else if (resp.code == 401) {
            IgSession.markExpired();
        }
        return resp;
    }

    /** 把 IG 的拒绝翻译成人话：登录墙和网络不通要给不同的提示 */
    private static String why(Net.Resp r) {
        if (r.code == -1) return "连不上 Instagram（检查代理）";
        String raw = r.body == null ? "" : r.body;
        String b = raw.toLowerCase();
        if (b.contains("challenge_required")) return "App 通道要 App 会话（网页 Cookie 进不去，属正常）";
        if (b.contains("login_required") || b.contains("require_login")
                || b.contains("checkpoint_required") || r.code == 401) {
            // 匿名请求被 IG 拒绝是常态（服务器端风控），不代表"你必须登录"：
            // embed 匿名路仍然可用，所以只如实报告状态码
            return "接口被 IG 拒绝（HTTP " + r.code + "：" + brief(raw) + "）";
        }
        if (r.code == 404) return "帖子不存在或已删除";
        if (r.code == 429) return "请求太频繁，过一会儿再试";
        return "HTTP " + r.code + "：" + brief(raw);
    }

    /** 诊断用：把响应体压成一行短摘要，别把整页 HTML 塞进提示 */
    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        return t.length() > 90 ? t.substring(0, 90) + "…" : t;
    }

    static String cookieValue(String cookie, String key) {
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith(key + "=")) return p.substring(key.length() + 1);
        }
        return "";
    }

    private static String first(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String jsonField(String html, String key) {
        return first(Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]{0,400})\""), html);
    }

    static String unescape(String s) {
        if (s == null) return null;
        return s.replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\\"", "\"")
                .replace("&amp;", "&");
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m == null) return "err";
        return m.length() > 40 ? m.substring(0, 40) : m;
    }
}
