package com.downx.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小红书：xhslink.com 短链 → 笔记页（带 xsec_token）→ 页面里的 __INITIAL_STATE__ 拿
 * 无水印视频（stream.h264.masterUrl）或原图（imageList.urlDefault）。
 *
 * 注意：分享链接里的 xsec_token 有时效，过期会跳到 404 安全页，这种情况只能让用户重新复制。
 */
public class XhsFetcher {

    private static final Pattern P_NOTE = Pattern.compile(
            "(?:explore|discovery/item|item)/([0-9a-fA-F]{24})");
    private static final Pattern P_STATE = Pattern.compile(
            "window\\.__INITIAL_STATE__\\s*=\\s*(\\{.*?\\})\\s*(?:;|</script>)", Pattern.DOTALL);
    private static final Pattern P_TITLE = Pattern.compile("<title>([^<]*)</title>");

    public static TweetFetcher.Result fetchSync(String link) throws Exception {
        String resolved = link;
        String low = link.toLowerCase();
        if (low.contains("xhslink.com") || low.contains("xhslink.cn")) {
            try {
                String r = Net.resolve(link, 12000);
                if (r != null && !r.isEmpty()) resolved = r;
            } catch (Throwable ignored) { }
        }
        String noteId = noteId(resolved);
        if (noteId == null) noteId = noteId(link);
        if (noteId == null) {
            throw new Exception("没识别到小红书笔记 ID，请粘贴 App 里「分享 → 复制链接」的完整内容");
        }

        // 参照 CYQawa/YunX 的 XhsParser：两条路径都试一次 —— 小红书对它们的风控策略不同，
        // 未登录时经常一条跳 /login、另一条却还能出数据。
        //   explore   ：桌面 Chrome UA（网页版笔记页）
        //   discovery ：iPhone UA + origin / X-Requested-With / sec-fetch-*（App 内嵌 WebView 形态）
        // xsec_token 必须原样带在查询串里，缺了/过期一律落登录墙。
        String query = queryOf(resolved.isEmpty() ? link : resolved);
        String[] urls = {
                "https://www.xiaohongshu.com/explore/" + noteId + query,
                "https://www.xiaohongshu.com/discovery/item/" + noteId + query,
        };
        String lastErr = "";
        for (int i = 0; i < urls.length; i++) {
            Net.Resp resp = Net.getResp(urls[i], i == 0 ? exploreHeaders() : discoveryHeaders(), 18000);
            if (resp.code >= 400) {
                lastErr = "HTTP " + resp.code;
                continue;
            }
            if (isLoginWall(resp.finalUrl, resp.body)) {
                lastErr = "需要登录";
                continue;
            }
            // /404/sec_xxx 是小红书统一的「笔记看不到」页：token 过期、笔记被删、设了私密
            // 全落到这里。带给用户的话必须能照做（重新复制分享链接），别说「解析失败」。
            if (isExpiredPage(resp.finalUrl, resp.body)) {
                lastErr = "已过期";
                continue;
            }
            TweetFetcher.Result r = parse(resp.body, noteId);
            if (r != null) return r;
            lastErr = "页面里没找到视频或图片";
        }

        if ("需要登录".equals(lastErr)) {
            throw new Exception("小红书这条笔记要登录才能看（未登录会被拦到登录页）。"
                    + "先去小红书 App 里登录一次，或换一条公开笔记再试");
        }
        if ("已过期".equals(lastErr)) {
            throw new Exception("小红书这条链接打不开了（token 过期、笔记被删或设了私密）。"
                    + "在小红书 App 里重新点「分享 → 复制链接」，用新链接再试一次");
        }
        if (lastErr.contains("HTTP 4")) {
            throw new Exception("小红书这条链接已过期（安全校验失败），请在 App 里重新复制一次分享链接");
        }
        throw new Exception("小红书页面里没找到视频或图片，可能需要登录才能看");
    }

    /** 原分享链接的查询串（含 xsec_token） */
    private static String queryOf(String u) {
        if (u == null) return "";
        int q = u.indexOf('?');
        return q >= 0 && q < u.length() - 1 ? u.substring(q) : "";
    }

    private static String[][] exploreHeaders() {
        return new String[][]{
                {"User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"},
                {"Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"},
                {"Referer", "https://www.xiaohongshu.com/"},
                {"Accept-Language", "zh-CN,zh;q=0.9"},
                {"Cookie", "webId=1; xsecappid=xhs-pc-web"},
        };
    }

    private static String[][] discoveryHeaders() {
        return new String[][]{
                {"User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) "
                        + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"},
                {"Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"},
                {"Referer", "https://www.xiaohongshu.com/"},
                {"origin", "https://www.xiaohongshu.com"},
                {"x-requested-with", "XMLHttpRequest"},
                {"sec-fetch-site", "same-origin"},
                {"sec-fetch-mode", "cors"},
                {"sec-fetch-dest", "empty"},
                {"Accept-Language", "zh-CN,zh;q=0.9"},
                {"Cookie", "webId=1; xsecappid=xhs-pc-web"},
        };
    }

    /** 被拦到登录页/验证码页：换路径也没用，直接给用户一句能照做的话 */
    private static boolean isLoginWall(String finalUrl, String body) {
        String f = finalUrl == null ? "" : finalUrl.toLowerCase();
        if (f.contains("/login") || f.contains("website-login") || f.contains("captcha")) return true;
        return body != null && body.contains("__INITIAL_STATE__") == false
                && (f.contains("login") || (body.contains("请登录") && body.length() < 4000));
    }

    /** /404/sec_xxx 或页面里带 sec_ 校验标记：token 过期 / 笔记被删 / 私密 */
    private static boolean isExpiredPage(String finalUrl, String body) {
        String f = finalUrl == null ? "" : finalUrl.toLowerCase();
        if (f.contains("/404/")) return true;
        return body != null && body.contains("__INITIAL_STATE__") == false
                && body.contains("sec_") && body.contains("originalUrl");
    }

    private static TweetFetcher.Result parse(String html, String noteId) throws Exception {
        Matcher sm = P_STATE.matcher(html);
        if (!sm.find()) return null;
        // 小红书这段 JSON 里混着 undefined，直接解析会炸，先替换掉
        String json = sm.group(1).replace("undefined", "null");
        JSONObject state;
        try {
            state = new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
        JSONObject note = noteOf(state, noteId);
        if (note == null) return null;

        TweetFetcher.Result r = new TweetFetcher.Result();
        r.platform = "xiaohongshu";
        r.tweetUrl = "https://www.xiaohongshu.com/explore/" + noteId;
        r.handle = "xiaohongshu";
        r.text = note.optString("title", "");
        if (r.text.isEmpty()) r.text = note.optString("desc", "");
        JSONObject user = note.optJSONObject("user");
        r.author = user == null ? "小红书" : user.optString("nickName", "小红书");
        if (user != null && user.optString("nickName", "").isEmpty()) {
            r.author = user.optString("nickname", "小红书");
        }
        r.avatar = user == null ? "" : user.optString("avatar", "");

        // 视频笔记：h264 里挑 defaultStream，没标就取第一条
        Set<String> videos = new LinkedHashSet<>();
        String cover = "";
        JSONObject video = note.optJSONObject("video");
        if (video != null) {
            JSONObject media = video.optJSONObject("media");
            JSONObject stream = media == null ? null : media.optJSONObject("stream");
            String[] kinds = {"h264", "h265", "av1", "h266"};
            for (String kind : kinds) {
                JSONArray arr = stream == null ? null : stream.optJSONArray(kind);
                if (arr == null || arr.length() == 0) continue;
                JSONObject pick = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s == null) continue;
                    if (pick == null) pick = s;
                    if (s.optBoolean("defaultStream", false)) { pick = s; break; }
                }
                if (pick == null) continue;
                String u = https(pick.optString("masterUrl", ""));
                if (!u.startsWith("https") && !u.startsWith("http")) {
                    JSONArray back = pick.optJSONArray("backupUrls");
                    if (back != null && back.length() > 0) u = https(back.optString(0, ""));
                }
                if (u.startsWith("http")) {
                    videos.add(u);
                    break;
                }
            }
            JSONObject image = media == null ? null : media.optJSONObject("image");
            if (image != null) {
                JSONObject first = image.optJSONObject("firstFrame");
                if (first != null) cover = first.optString("url", "");
                if (cover.isEmpty()) cover = image.optString("thumbnail", "");
            }
        }

        if (videos.isEmpty()) {
            // ★ 参照 YunX：有 video 对象却没直链 = 「视频笔记没取到流」。
            //   这时候退回图片是误导（用户会以为这条笔记本来就只有图），明确报错更有用。
            if (video != null) {
                throw new Exception("这条是视频笔记，但小红书没给出视频直链（多半要登录）。"
                        + "先去 App 里登录一次再试，或换一条笔记");
            }
            String first = firstPhoto(note);
            if (cover.isEmpty()) cover = first;
        } else {
            // 视频封面大多在 imageList[0] 里，firstFrame 常常是空的
            String first = firstPhoto(note);
            if (cover.isEmpty() || !cover.startsWith("http")) cover = first;

            // ① 无水印原片：video.consumer.originVideoKey 直接拼 CDN，
            //    这一条是作者上传的原文件（画质最高、没有右下角水印）
            String originKey = "";
            JSONObject consumer = video == null ? null : video.optJSONObject("consumer");
            if (consumer != null) originKey = consumer.optString("originVideoKey", "");
            if (originKey.length() > 8) {
                TweetFetcher.Media m = new TweetFetcher.Media();
                m.url = "https://sns-video-bd.xhscdn.com/" + originKey;
                m.kind = "video";
                m.ext = "mp4";
                m.thumb = cover;
                m.label = "原片 · 无水印";
                r.items.add(m);
            }

            // ② 网页版流：转码过、体积小，但可能带水印，作为备选
            for (String u : videos) {
                TweetFetcher.Media m = new TweetFetcher.Media();
                m.url = u;
                m.kind = "video";
                m.ext = "mp4";
                m.thumb = cover;
                m.label = "网页版 · 体积小";
                r.items.add(m);
            }
            return r;
        }

        // 图文笔记：先把带网页水印规格的地址换算成去水印原图（YunX 的
        // watermarkFreeOrRaw 同款算法：丢掉时间戳桶+散列两段路径，再截掉 "!" 后的处理规格），
        // 再逐张 HEAD 探测，404/410 就回退原始地址 —— 宁可带水印，也要能下能看
        JSONArray imgs = note.optJSONArray("imageList");
        if (imgs != null && imgs.length() > 0) {
            java.util.List<String> raw = new java.util.ArrayList<>();
            for (int i = 0; i < imgs.length() && raw.size() < 30; i++) {
                String u = photoUrl(imgs.optJSONObject(i));
                if (!u.isEmpty()) raw.add(u);
            }
            java.util.List<String> resolvedImgs = resolveImages(raw);
            for (int i = 0; i < resolvedImgs.size(); i++) {
                String u = resolvedImgs.get(i);
                TweetFetcher.Media m = new TweetFetcher.Media();
                m.url = u;
                m.kind = "photo";
                m.ext = "jpg";
                m.thumb = u;
                m.label = "图片 " + (i + 1) + " · 原图";
                r.items.add(m);
            }
            if (!r.items.isEmpty()) return r;
        }
        return null;
    }

    /** 小红书原图域名：只带图片 ID、不带任何处理规格，出来的就是作者原图 */
    private static final String XHS_IMG_CDN = "https://sns-img-bd.xhscdn.com";

    /**
     * 把带处理规格的图片地址换算成去水印原图。
     *
     * 例：https://sns-webpic-qc.xhscdn.com/202501011200/0f5e/<图片ID>!nd_dft_wlteh_webp_3
     *   → https://sns-img-bd.xhscdn.com/<图片ID>
     * 取图片 ID 用「截掉 ! 和 ? 之后取最后一段」：比 YunX 的「丢前两段再拼」更耐路径变化
     * （实测同一个 CDN 上既有 /时间戳/散列/ID，也有 /年/月/日/散列/ID 的形态）。
     */
    static String watermarkFree(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw;
        int bang = s.indexOf('!');
        if (bang >= 0) s = s.substring(0, bang);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        if (slash < 0 || slash == s.length() - 1) return raw;
        String id = s.substring(slash + 1);
        if (id.isEmpty() || id.indexOf(':') >= 0) return raw;
        return XHS_IMG_CDN + "/" + id;
    }

    /** 去水印原图真的不存在（只认 404/410；超时/405 都算「还能用」） */
    private static boolean missing(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestProperty("User-Agent", Net.UA);
            c.setRequestProperty("Referer", "https://www.xiaohongshu.com/");
            int code = c.getResponseCode();
            return code == 404 || code == 410;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** 逐张换算 + 探测；并行跑，图集十几张也不至于把解析拖到十几秒 */
    private static java.util.List<String> resolveImages(java.util.List<String> raw) {
        final String[] out = new String[raw.size()];
        if (raw.isEmpty()) return java.util.Collections.emptyList();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(6, raw.size()));
        try {
            java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                final int idx = i;
                fs.add(pool.submit(() -> {
                    String origin = raw.get(idx);
                    String clean = watermarkFree(origin);
                    out[idx] = clean.equals(origin) || !missing(clean) ? clean : origin;
                }));
            }
            for (java.util.concurrent.Future<?> f : fs) {
                try { f.get(8, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) { }
            }
        } finally {
            pool.shutdownNow();
        }
        java.util.List<String> res = new java.util.ArrayList<>();
        for (int i = 0; i < out.length; i++) res.add(out[i] == null ? raw.get(i) : out[i]);
        return res;
    }

    /**
     * 找笔记对象。2025 年底起网页换成
     * __INITIAL_STATE__.noteData.data.noteData，旧版是 note.noteDetailMap[id].note，
     * 两种都认，新结构优先。
     */
    private static JSONObject noteOf(JSONObject state, String noteId) {
        JSONObject noteData = state.optJSONObject("noteData");
        if (noteData != null) {
            JSONObject data = noteData.optJSONObject("data");
            JSONObject note = data == null ? null : data.optJSONObject("noteData");
            if (note != null) return note;
        }
        JSONObject noteMap = state.optJSONObject("note");
        noteMap = noteMap == null ? null : noteMap.optJSONObject("noteDetailMap");
        if (noteMap != null) {
            JSONObject item = noteMap.optJSONObject(noteId);
            JSONObject note = item == null ? null : item.optJSONObject("note");
            if (note != null) return note;
            // 有时 key 不是 noteId，取第一个带 note 的
            java.util.Iterator<String> it = noteMap.keys();
            while (it.hasNext()) {
                JSONObject v = noteMap.optJSONObject(it.next());
                if (v != null && v.optJSONObject("note") != null) return v.optJSONObject("note");
            }
        }
        return null;
    }

    /**
     * 小红书 CDN 给的直链常是 http://，Android 9 以上默认禁止明文 HTTP，
     * 会被系统直接拦掉（表现为"下载失败"）。xhscdn 支持 https，统一升级。
     */
    private static String https(String u) {
        if (u != null && u.startsWith("http://")) return "https://" + u.substring(7);
        return u;
    }

    /** 单张图的地址：优先 imageList[].url，没有就翻 infoList 里的高清场景 */
    private static String photoUrl(JSONObject im) {
        if (im == null) return "";
        String u = https(im.optString("url", ""));
        if (u.startsWith("https") || u.startsWith("http://localhost")) return u;
        JSONArray info = im.optJSONArray("infoList");
        if (info != null) {
            for (int i = 0; i < info.length(); i++) {
                JSONObject x = info.optJSONObject(i);
                if (x == null) continue;
                String scene = x.optString("imageScene", "");
                String cu = https(x.optString("url", ""));
                if (!cu.startsWith("http")) continue;
                if ("H5_DTL".equals(scene)) return cu;
                if (u.isEmpty()) u = cu;
            }
        }
        return u.startsWith("http") ? u : "";
    }

    private static String firstPhoto(JSONObject note) {
        JSONArray imgs = note.optJSONArray("imageList");
        if (imgs == null || imgs.length() == 0) return "";
        return photoUrl(imgs.optJSONObject(0));
    }

    /** 作品页地址：HTTP 解析失败时交给内置浏览器兜底 */
    public static String pageUrlOf(String link, String resolved) {
        String id = noteId(resolved);
        if (id == null) id = noteId(link);
        return id == null ? null : resolved;
    }

    static String noteId(String link) {
        if (link == null) return null;
        Matcher m = P_NOTE.matcher(link);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 小红书图片地址后面的「!h5_1080jpg」是 CDN 的清晰度/格式后缀，
     * 直接去掉会 403（实测），只能原样保留；这里只摘掉查询串里的缩放参数。
     */
    static String originOf(String u) {
        if (u == null || u.isEmpty()) return "";
        int q = u.indexOf("?imageView2");
        if (q > 0) u = u.substring(0, q);
        return u;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
