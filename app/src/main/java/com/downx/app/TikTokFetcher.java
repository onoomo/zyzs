package com.downx.app;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TikTok 解析：走 tikwm 公开接口（无需登录），可拿无水印 / 高清 / 音乐。
 */
public class TikTokFetcher {

    private static final Pattern P_ID = Pattern.compile("video/(\\d+)");

    public static TweetFetcher.Result fetchSync(String link) throws Exception {
        String real = link;
        String host = hostOf(real);
        if (host.contains("vm.tiktok.com") || host.contains("vt.tiktok.com")) {
            try { real = Net.resolve(real, 15000); } catch (Exception ignored) { }
        }
        String api = "https://www.tikwm.com/api/?url="
                + java.net.URLEncoder.encode(real, "UTF-8") + "&hd=1";
        String res = Net.get(api, new String[][]{
                {"User-Agent", Net.UA},
                {"Accept", "application/json"}}, 20000);

        JSONObject j = new JSONObject(res);
        if (j.optInt("code", -1) != 0) {
            String msg = j.optString("msg", "TikTok 解析失败");
            if (msg.contains("not found") || msg.contains("deleted")) {
                throw new Exception("这条 TikTok 不存在或已被删除");
            }
            throw new Exception("TikTok 解析失败：" + msg);
        }
        JSONObject d = j.getJSONObject("data");

        TweetFetcher.Result r = new TweetFetcher.Result();
        r.platform = "tiktok";
        r.tweetUrl = real;
        JSONObject a = d.optJSONObject("author");
        if (a != null) {
            r.author = a.optString("nickname");
            r.handle = a.optString("unique_id");
            r.avatar = a.optString("avatar");
        }
        r.text = d.optString("title");
        long ct = d.optLong("create_time");
        if (ct > 0) {
            r.date = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(ct * 1000));
        }
        String cover = d.optString("cover");
        int dur = d.optInt("duration");

        // 高清无水印（存在才有）
        add(r, d.optString("hdplay"), cover,
                "视频 · 高清无水印" + (dur > 0 ? " · " + dur + "s" : ""), true);
        // 普通无水印
        add(r, d.optString("play"), cover,
                "视频 · 无水印" + (dur > 0 ? " · " + dur + "s" : ""), true);
        // 音乐
        String music = d.optString("music");
        if (!music.isEmpty()) {
            TweetFetcher.Media m = new TweetFetcher.Media();
            m.url = music;
            m.thumb = cover;
            m.label = "音频 · MP3";
            m.kind = "video";
            m.ext = "mp3";
            r.items.add(m);
        }
        if (r.items.isEmpty()) throw new Exception("没拿到视频地址，稍后再试");
        return r;
    }

    private static void add(TweetFetcher.Result r, String url, String thumb,
                            String label, boolean video) {
        if (url == null || url.isEmpty()) return;
        TweetFetcher.Media m = new TweetFetcher.Media();
        m.url = url;
        m.thumb = thumb;
        m.label = label;
        m.kind = video ? "video" : "photo";
        m.ext = video ? "mp4" : "jpg";
        r.items.add(m);
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URI(url).getHost() == null ? "" : new java.net.URI(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}
