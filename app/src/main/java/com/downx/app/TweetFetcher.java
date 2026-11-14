package com.downx.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多源解析：官方访客接口(全清晰度) -> fxtwitter -> syndication。
 * 哪个可用走哪个，全部失败才报错。
 */
public class TweetFetcher {

    private static final String TAG = "DownX-X";
    private static final String UA_WEB = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    public static class Media {
        public String url;     // 直链
        public String thumb;   // 封面/缩略图
        public String label;   // 展示名，如 1080p · 2.1Mbps / 图片
        public String kind;    // video / photo / gif
        public String ext = "mp4";
        /** 音轨直链：不为空说明这是 DASH 分离流，下载完要和音轨合并（YouTube 1080P） */
        public String audioUrl;
        /**
         * 同一签名的备用直链（B 站 dash/durl 的 backupUrl）：主链所在节点拒绝时
         * 换它再试，签名不变所以不用重新解析。为空说明该平台没有备用流。
         */
        public String altUrl;
        /**
         * 跨协议的旧式直链兜底（双保险的第二层）：B 站登录态走 dash 分离流，
         * 这里存同一条视频的 html5 单文件直链（DownX 旧协议）。
         * dash 整条链路（含备用节点）都被拒时，下载器换它从头下 ——
         * 宁可低清可下，不锁死在一条链路上。
         */
        public String fallbackUrl;
        /** HLS：指定要哪一档（如 "720"），下载器据此在 master 清单里挑流 */
        public String pick;
        /**
         * 视频真实像素宽高（0 = 不知道）。B 站封面一律是 16:9 横图，
         * 光看缩略图分不出这条是竖拍还是横拍，解析页的预览框和「竖/横」标签都靠它。
         */
        public int vw, vh;

        /** 竖拍还是横拍；未知返回空串 */
        public String orient() {
            if (vw <= 0 || vh <= 0) return "";
            return vw > vh ? "横屏" : "竖屏";
        }
    }

    public static class Result {
        public String platform = "twitter";
        public String author = "";
        public String handle = "";
        public String avatar = "";
        public String text = "";
        public String date = "";
        public String tweetUrl = "";
        /** 抖音/快手的作品 ID：抓不到标题时用它保证文件名唯一（见 MainActivity.doDownload） */
        public String svId = "";
        /** 标题是不是解析器自己编的通用句（「来自xx的视频」）：下载时要补作品 ID 防撞名 */
        public boolean svGeneric = false;
        public final List<Media> items = new ArrayList<>();
    }

    public interface Listener {
        void onDone(Result r);
        void onFail(String msg);
    }

    private static final String BEARER =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA";

    private static final Pattern P_STATUS = Pattern.compile("status(?:es)?/(\\d+)");
    private static final Pattern P_LINK = Pattern.compile("https?://\\S+");
    /**
     * 直链里的分辨率片段。X 现在给的是
     * `.../vid/avc1/1920x1080/xxx.mp4`（中间多了编码目录），
     * 老格式是 `.../vid/1920x1080/xxx.mp4`，两种都要认，
     * 否则全部退化成"标准"，用户就看不出哪档是 1080P。
     */
    private static final Pattern P_DIM =
            Pattern.compile("(?:^|/)(\\d{3,4})x(\\d{3,4})(?:/|$)");

    /** 异步解析入口 */
    public static void fetch(final String rawUrl, final Listener cb) {
        new Thread(() -> {
            Result r = null;
            String err = null;
            try {
                r = fetchSync(rawUrl);
            } catch (Exception e) {
                err = friendly(e);
            }
            final Result fr = r;
            final String ferr = err;
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            if (fr != null) h.post(() -> cb.onDone(fr));
            else h.post(() -> cb.onFail(ferr));
        }, "fetch").start();
    }

    public static Result fetchSync(String rawUrl) throws Exception {
        // Bluesky 已经摘掉了（它的公共接口在多数网络下连不通），
        // 这里给一句明确的话，别让人以为是链接识别坏了
        if (rawUrl != null && rawUrl.toLowerCase().contains("bsky.app")) {
            throw new Exception("Bluesky 已不再支持（它的接口在当前网络下连不通）");
        }
        String link = extractLink(rawUrl);
        if (link == null) throw new Exception("没有识别到链接，请粘贴包含 https:// 的完整链接");

        // t.co 等短链还原
        String host = hostOf(link);
        if (host.contains("t.co") || host.contains("youtu")
                || host.contains("b23.tv") || host.contains("xhslink")) {
            try { link = Net.resolve(link, 10000); } catch (Exception ignored) { }
        }

        if (hostOf(link).contains("instagram.com") || hostOf(link).contains("instagr.am")) {
            return IgFetcher.fetchSync(link);
        }
        String lh = hostOf(link);
        if (lh.contains("tiktok.com")) {
            return TikTokFetcher.fetchSync(link);
        }
        if (lh.contains("facebook.com") || lh.contains("fb.watch") || lh.contains("fb.me")) {
            return FbFetcher.fetchSync(link);
        }
        if (lh.contains("pinterest.") || lh.contains("pin.it")) {
            return PinFetcher.fetchSync(link);
        }
        // 抖音 / 快手：服务端页面已不含视频地址（改成前端签名请求了），
        // 只能走内置浏览器嗅探，主页解析入口会拦在前面，这里只是兜底提示
        if (ShortVideo.isDouyin(link) || ShortVideo.isKuaishou(link)) {
            throw new Exception("抖音 / 快手要走内置浏览器解析，请在主页直接粘贴链接");
        }
        if (lh.contains("xiaohongshu.com") || lh.contains("xhslink.")) {
            return XhsFetcher.fetchSync(link);
        }
        if (lh.contains("bilibili.com") || lh.contains("b23.tv")) {
            return BiliFetcher.fetchSync(link);
        }
        if (lh.contains("youtube.com") || lh.contains("youtu.be")) {
            return YoutubeFetcher.fetchSync(link);
        }

        String id = extractTweetId(link);
        if (id == null) {
            // 可能直接是 video.twimg.com 直链
            if (hostOf(link).contains("twimg.com")) {
                Result r = new Result();
                r.tweetUrl = link;
                Media m = new Media();
                m.url = link;
                m.label = "视频 · 原始画质";
                m.kind = "video";
                m.thumb = link;
                r.items.add(m);
                return r;
            }
            throw new Exception("没有识别到推文 ID，请粘贴推文的完整链接");
        }

        Exception last = null;
        Result noMedia = null;
        Result best = null;
        // 1) 网页端同款 syndication 接口：能给全部清晰度（1080p/720p/480p 通常三档）
        try {
            Result r = viaSyndication(id);
            Log.i(TAG, "syndication items=" + r.items.size() + " video=" + videoCount(r));
            if (!r.items.isEmpty()) {
                best = r;
                if (videoCount(r) >= 2) return r;
            } else {
                noMedia = r;
            }
        } catch (Exception e) {
            last = e;
            Log.w(TAG, "syndication fail: " + e);
        }
        // 2) 官方访客接口：同样能拿全部清晰度
        try {
            Result r = viaGuestApi(id);
            Log.i(TAG, "guest items=" + r.items.size() + " video=" + videoCount(r));
            if (!r.items.isEmpty()) {
                if (best == null || videoCount(r) > videoCount(best)) best = r;
                if (videoCount(r) >= 2) return r;
            } else if (noMedia == null) {
                noMedia = r;
            }
        } catch (Exception e) {
            last = e;
            Log.w(TAG, "guest fail: " + e);
        }
        // 3) fxtwitter：只有一路最佳画质，做兜底
        try {
            Result r = viaFx(id);
            Log.i(TAG, "fx items=" + r.items.size() + " video=" + videoCount(r));
            if (!r.items.isEmpty()) {
                if (best == null || videoCount(r) > videoCount(best)) best = r;
            } else if (noMedia == null) {
                noMedia = r;
            }
        } catch (Exception e) {
            last = e;
            Log.w(TAG, "fx fail: " + e);
        }

        if (best != null) return best;

        if (noMedia != null) throw new Exception("这条推文里没有可下载的图片或视频");
        if (last != null && last.getMessage() != null && last.getMessage().contains("HTTP 4")) {
            throw new Exception("推文不存在，或作者设置了仅登录可见（受保护内容无法下载）");
        }
        throw new Exception("连不上 Twitter。请确认代理/VPN 已连接后重试");
    }

    // ---------------- 来源 1：官方访客接口（可拿全部清晰度） ----------------

    private static Result viaGuestApi(String id) throws Exception {
        String token = Net.post(
                "https://api.twitter.com/1.1/guest/activate.json",
                new String[][]{{"Authorization", "Bearer " + BEARER},
                        {"User-Agent", "TwitterAndroid/10.58.0"}},
                8000);
        String gt = new JSONObject(token).optString("guest_token");
        if (gt.isEmpty()) throw new Exception("guest token 获取失败");

        String json = Net.get(
                "https://api.twitter.com/1.1/statuses/show.json?id=" + id
                        + "&tweet_mode=extended&include_entities=true",
                new String[][]{{"Authorization", "Bearer " + BEARER},
                        {"x-guest-token", gt},
                        {"User-Agent", "TwitterAndroid/10.58.0"}},
                8000);

        JSONObject j = new JSONObject(json);
        JSONObject user = j.getJSONObject("user");
        Result r = base(j, id);
        r.author = user.optString("name");
        r.handle = user.optString("screen_name");
        r.avatar = user.optString("profile_image_url_https").replace("_normal", "");
        r.date = fmtDate(j.optString("created_at"));
        r.text = j.optString("full_text");

        JSONArray media = j.getJSONObject("extended_entities").getJSONArray("media");
        for (int i = 0; i < media.length(); i++) {
            JSONObject m = media.getJSONObject(i);
            String type = m.optString("type");
            if ("photo".equals(type)) {
                Media it = new Media();
                it.url = m.getString("media_url_https") + "?name=orig";
                it.thumb = m.getString("media_url_https") + "?name=small";
                it.label = "图片 " + (i + 1) + " · 原图";
                it.kind = "photo";
                it.ext = extOf(it.url, "jpg");
                r.items.add(it);
            } else {
                JSONObject vi = m.getJSONObject("video_info");
                JSONArray vs = vi.getJSONArray("variants");
                String thumb = m.getString("media_url_https");
                List<JSONObject> mp4s = new ArrayList<>();
                for (int k = 0; k < vs.length(); k++) {
                    JSONObject v = vs.getJSONObject(k);
                    if (v.optString("content_type").contains("mp4")) mp4s.add(v);
                }
                Collections.sort(mp4s, (a, b) -> Long.compare(b.optLong("bitrate"), a.optLong("bitrate")));
                int total = mp4s.size();
                List<Media> added = new ArrayList<>();
                for (int k = 0; k < total; k++) {
                    JSONObject v = mp4s.get(k);
                    String url = v.getString("url");
                    Media it = new Media();
                    it.url = url;
                    it.thumb = thumb;
                    it.kind = "gif".equals(type) ? "gif" : "video";
                    it.label = variantLabel(url, k, total, "gif".equals(type));
                    added.add(it);
                }
                // 只有一条 mp4 时，多档可能藏在 HLS 主清单里（X 有些视频只给 m3u8）
                if (added.size() < 2) {
                    List<Media> ex = expandHls(findHls(vs), thumb, "gif".equals(type));
                    r.items.addAll(ex.size() >= 2 ? ex : added);
                } else {
                    r.items.addAll(added);
                }
            }
        }
        return r;
    }

    // ---------------- 来源 2：fxtwitter ----------------

    private static Result viaFx(String id) throws Exception {
        String json = Net.get("https://api.fxtwitter.com/status/" + id, null, 9000);
        JSONObject j = new JSONObject(json);
        JSONObject tweet = j.getJSONObject("tweet");
        JSONObject author = tweet.optJSONObject("author");
        Result r = base(j, id);
        r.tweetUrl = tweet.optString("url", r.tweetUrl);
        if (author != null) {
            r.author = author.optString("name");
            r.handle = author.optString("screen_name");
            r.avatar = author.optString("avatar_url");
        }
        r.text = tweet.optString("text");
        r.date = fmtIso(tweet.optString("created_at"));

        JSONObject media = tweet.optJSONObject("media");
        if (media == null) return r;
        JSONArray vids = media.optJSONArray("videos");
        if (vids != null) {
            for (int i = 0; i < vids.length(); i++) {
                JSONObject v = vids.getJSONObject(i);
                String type = v.optString("type", "video");
                boolean gif = "gif".equals(type);
                String thumb = v.optString("thumbnail_url");

                // fxtwitter 的 videos[] 里其实也带 variants（多码率 mp4）。
                // 以前只取 v.url（最高一档），所以走这条兜底路时「只有一个画质」；
                // 现在跟官方接口一样，把每档都摆出来
                JSONArray vs = v.optJSONArray("variants");
                List<JSONObject> mp4s = new ArrayList<>();
                if (vs != null) {
                    for (int k = 0; k < vs.length(); k++) {
                        JSONObject x = vs.optJSONObject(k);
                        if (x == null) continue;
                        if (x.optString("content_type").contains("mp4")
                                && !x.optString("url").isEmpty()) {
                            mp4s.add(x);
                        }
                    }
                }
                if (mp4s.size() > 1) {
                    Collections.sort(mp4s, (a, b) -> Long.compare(b.optLong("bitrate"), a.optLong("bitrate")));
                    int total = mp4s.size();
                    for (int k = 0; k < total; k++) {
                        String u = mp4s.get(k).getString("url");
                        Media it = new Media();
                        it.url = u;
                        it.thumb = thumb;
                        it.kind = gif ? "gif" : "video";
                        it.label = variantLabel(u, k, total, gif);
                        r.items.add(it);
                    }
                    continue;
                }
                // 只有一条 mp4：看 HLS 主清单里有没有更多档
                List<Media> ex = expandHls(findHls(vs), thumb, gif);
                if (ex.size() >= 2) {
                    r.items.addAll(ex);
                    continue;
                }

                Media it = new Media();
                it.url = v.getString("url");
                it.thumb = thumb;
                it.kind = gif ? "gif" : "video";
                it.label = gif ? "GIF · mp4" : "视频 · 最佳画质";
                double d = v.optDouble("duration", 0);
                if (d > 0) it.label += " · " + (int) d + "s";
                r.items.add(it);
            }
        }
        JSONArray photos = media.optJSONArray("photos");
        if (photos != null) {
            for (int i = 0; i < photos.length(); i++) {
                JSONObject p = photos.getJSONObject(i);
                Media it = new Media();
                it.url = p.getString("url");
                it.thumb = it.url;
                it.label = "图片 " + (i + 1) + " · 原图";
                it.kind = "photo";
                it.ext = extOf(it.url, "jpg");
                r.items.add(it);
            }
        }
        return r;
    }

    // ---------------- 来源 3：syndication（无需登录，网页端同款接口） ----------------

    private static Result viaSyndication(String id) throws Exception {
        String token = syndToken(Long.parseLong(id));
        String url = "https://cdn.syndication.twimg.com/tweet-result?id=" + id
                + "&lang=en&token=" + token;
        String json = Net.get(url, new String[][]{
                {"User-Agent", UA_WEB},
                {"Referer", "https://platform.twitter.com/"},
                {"Accept", "application/json"}}, 8000);
        JSONObject j = new JSONObject(json);
        JSONObject user = j.optJSONObject("user");
        Result r = base(j, id);
        Log.i(TAG, "synd body=" + json.substring(0, Math.min(200, json.length())));
        if (user != null) {
            r.author = user.optString("name");
            r.handle = user.optString("screen_name");
            r.avatar = user.optString("profile_image_url_https").replace("_normal", "");
        }
        r.text = j.optString("text");
        r.date = fmtIso(j.optString("created_at"));

        JSONArray media = j.optJSONArray("mediaDetails");
        if (media == null) return r;
        for (int i = 0; i < media.length(); i++) {
            JSONObject m = media.getJSONObject(i);
            String type = m.optString("type");
            if ("photo".equals(type)) {
                Media it = new Media();
                it.url = m.getString("media_url_https") + "?name=orig";
                it.thumb = m.getString("media_url_https") + "?name=small";
                it.label = "图片 " + (i + 1) + " · 原图";
                it.kind = "photo";
                it.ext = extOf(it.url, "jpg");
                r.items.add(it);
            } else {
                JSONObject vi = m.getJSONObject("video_info");
                JSONArray vs = vi.getJSONArray("variants");
                String thumb = m.getString("media_url_https");
                List<JSONObject> mp4s = new ArrayList<>();
                for (int k = 0; k < vs.length(); k++) {
                    JSONObject v = vs.getJSONObject(k);
                    if (v.optString("content_type").contains("mp4")) mp4s.add(v);
                }
                Collections.sort(mp4s, (a, b) -> Long.compare(b.optLong("bitrate"), a.optLong("bitrate")));
                int total = mp4s.size();
                List<Media> added = new ArrayList<>();
                for (int k = 0; k < total; k++) {
                    JSONObject v = mp4s.get(k);
                    String vurl = v.getString("url");
                    Media it = new Media();
                    it.url = vurl;
                    it.thumb = thumb;
                    it.kind = "gif".equals(type) ? "gif" : "video";
                    it.label = variantLabel(vurl, k, total, "gif".equals(type));
                    added.add(it);
                }
                if (added.size() < 2) {
                    List<Media> ex = expandHls(findHls(vs), thumb, "gif".equals(type));
                    r.items.addAll(ex.size() >= 2 ? ex : added);
                } else {
                    r.items.addAll(added);
                }
            }
        }
        return r;
    }

    // ---------------- 工具方法 ----------------

    private static Result base(JSONObject j, String id) {
        Result r = new Result();
        r.tweetUrl = "https://x.com/i/status/" + id;
        return r;
    }

    public static String extractLink(String text) {
        // 实现在 ShortVideo 里（纯 Java，主机验证台能直接测）
        return ShortVideo.linkIn(text);
    }

    static String extractTweetId(String link) {
        Matcher m = P_STATUS.matcher(link);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * variants 里找 HLS 主清单地址（content_type 为 application/x-mpegURL，或网址带 .m3u8）。
     * X / IG 的 mp4 档有时只给一条，其余档只在主清单里。
     */
    static String findHls(JSONArray vs) {
        if (vs == null) return null;
        for (int i = 0; i < vs.length(); i++) {
            JSONObject v = vs.optJSONObject(i);
            if (v == null) continue;
            String u = v.optString("url", "");
            if (u.isEmpty()) continue;
            String ct = v.optString("content_type", "").toLowerCase();
            if (ct.contains("mpegurl") || u.toLowerCase().contains(".m3u8")) return u;
        }
        return null;
    }

    /**
     * 把 HLS 主清单展开成多档（1080P / 720P / …）。
     * 每档都是「同一个主清单 + pick=高度」，下载器会自己挑子流、认出独立音轨。
     * 只有一档或读不到就返回空表，调用方会退回原来的单条直链。
     */
    static List<Media> expandHls(String masterUrl, String thumb, boolean gif) {
        List<Media> out = new ArrayList<>();
        if (masterUrl == null || masterUrl.isEmpty()) return out;
        List<int[]> rs = HlsMaster.renditions(masterUrl, null);
        if (rs.size() < 2) return out;
        for (int[] r : rs) {
            int w = r[0], h = r[1];
            Media it = new Media();
            it.url = masterUrl;
            it.pick = String.valueOf(h);                    // 下载器按 height 匹配 RESOLUTION
            it.thumb = thumb;
            it.kind = gif ? "gif" : "video";
            it.ext = "mp4";
            it.label = "HLS · " + HlsMaster.label(w, h);    // 竖屏 1080x1920 → 1080P（不叫 1920P）
            out.add(it);
        }
        return out;
    }

    /** 同一视频的多档清晰度短标签：最高 / 高清 / 流畅 */
    static String variantLabel(String url, int k, int total, boolean gif) {        String res = resOf(url);
        if (total <= 1) return (gif ? "GIF" : "视频") + " · " + res;
        String tag;
        if (k == 0) tag = "最高";
        else if (k == 1) tag = "高清";
        else if (k == 2) tag = "流畅";
        else tag = "备选 " + k;
        return tag + " · " + res;
    }

    /** 从直链里的 /1080x1920/ 这类片段取出短边分辨率 */    static String resOf(String url) {
        Matcher m = P_DIM.matcher(url);
        if (m.find()) {
            try {
                int w = Integer.parseInt(m.group(1));
                int h = Integer.parseInt(m.group(2));
                if (w > 0 && h > 0) return Math.min(w, h) + "p";
            } catch (Exception ignored) { }
        }
        return "标准";
    }

    static int videoCount(Result r) {
        int n = 0;
        for (Media m : r.items) {
            if (!"photo".equals(m.kind)) n++;
        }
        return n;
    }

    static String qualityLabel(String url, long bitrate, String prefix) {
        int w = 0, h = 0;
        Matcher m = P_DIM.matcher(url);
        if (m.find()) {
            w = Integer.parseInt(m.group(1));
            h = Integer.parseInt(m.group(2));
        }
        StringBuilder sb = new StringBuilder(prefix).append(" · ");
        if (w > 0 && h > 0) {
            int p = Math.min(w, h);
            sb.append(p).append("p");
            if (w > h) sb.append(" (竖屏 ").append(w).append("x").append(h).append(")");
        } else {
            sb.append("标准");
        }
        if (bitrate > 0) sb.append(" · ").append(String.format("%.1fMbps", bitrate / 1e6));
        return sb.toString();
    }

    private static String extOf(String url, String def) {
        int q = url.indexOf('?');
        String u = q > 0 ? url.substring(0, q) : url;
        int dot = u.lastIndexOf('.');
        if (dot > 0 && dot > u.length() - 6) {
            String e = u.substring(dot + 1).toLowerCase();
            if (e.matches("[a-z0-9]{2,4}")) return e;
        }
        return def;
    }

    /** JS 端 token 算法的 Java 移植：((id/1e15)*PI).toString(36) 去掉 0 和小数点 */
    static String syndToken(long id) {
        double v = (id / 1e15) * Math.PI;
        long intPart = (long) Math.floor(v);
        double frac = v - intPart;
        StringBuilder sb = new StringBuilder(Long.toString(intPart, 36));
        if (frac > 0) {
            sb.append('.');
            final String digits = "0123456789abcdefghijklmnopqrstuvwxyz";
            int n = 0;
            while (frac > 0 && n < 24) {
                frac *= 36;
                int d = (int) Math.floor(frac);
                if (d > 35) d = 35;
                sb.append(digits.charAt(d));
                frac -= d;
                n++;
            }
        }
        String s = sb.toString();
        // JS 里是 .replace(/0\./, '')：只去掉开头的 "0."，不能误删中间的 0
        if (s.startsWith("0.")) s = s.substring(2);
        return s;
    }

    private static String fmtDate(String twitterDate) {
        // "Wed Oct 05 12:34:56 +0000 2022"
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat(
                    "EEE MMM dd HH:mm:ss Z yyyy", java.util.Locale.ENGLISH);
            java.util.Date d = in.parse(twitterDate);
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
            return out.format(d);
        } catch (Exception e) {
            return twitterDate == null ? "" : twitterDate;
        }
    }

    private static String fmtIso(String iso) {
        // "2022-10-05T12:34:56.000Z"
        if (iso == null || iso.length() < 16) return iso == null ? "" : iso;
        try {
            String s = iso.replace("Z", "+0000").replace("T", " ");
            if (s.length() > 16 && s.charAt(10) == ' ') {
                java.text.SimpleDateFormat in = new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH);
                java.util.Date d = in.parse(s.substring(0, 19));
                java.text.SimpleDateFormat out = new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(d);
                cal.add(java.util.Calendar.HOUR, 8); // UTC -> 北京时间
                return out.format(cal.getTime());
            }
        } catch (Exception ignored) { }
        return iso;
    }

    /** 把底层英文网络异常翻成人话，所有平台共用 */
    public static String beautify(String raw) {
        String msg = raw == null ? "" : raw;
        String low = msg.toLowerCase();
        if (low.contains("timeout") || low.contains("timed out") || low.contains("failed to connect")
                || low.contains("connect failed") || low.contains("enotfound")
                || low.contains("unknownhost") || low.contains("unable to resolve host")
                || low.contains("err_connection") || low.contains("econnrefused")
                || low.contains("network is unreachable") || low.contains("broken pipe")) {
            return "连不上服务器。这个平台在国内需要代理/VPN，请确认代理已连接后重试";
        }
        if (low.contains("http 404") || low.contains("http 410")) {
            return "内容不存在，或已被作者删除";
        }
        return msg;
    }

    static String friendly(Throwable e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        String pretty = beautify(msg);
        if (!pretty.equals(msg)) return pretty;
        return "解析失败：" + msg;
    }
}
