package com.downx.app;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facebook 解析：把原视频链接塞进官方插件 embed 页，里面就有 hd/sd 直链（匿名可用）。
 */
public class FbFetcher {

    private static final Pattern P_HD = Pattern.compile("\"hd_src\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern P_SD = Pattern.compile("\"sd_src\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern P_TITLE = Pattern.compile("<title>([^<]+)</title>");
    /** 插件页里的作者：{"owner":{"__typename":"User","name":"xxx"}}，也有写成 author 的 */
    private static final Pattern P_OWNER = Pattern.compile(
            "\"(?:owner|author|actor)\"\\s*:\\s*\\{[^{}]*?\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern P_DESC = Pattern.compile(
            "<meta[^>]*og:description[^>]*content=\"([^\"]{4,300})\"");

    public static TweetFetcher.Result fetchSync(String link) throws Exception {
        String real = link;
        if (hostOf(real).contains("fb.watch") || hostOf(real).contains("fb.me")) {
            try { real = Net.resolve(real, 15000); } catch (Exception ignored) { }
        }

        String plugin = "https://www.facebook.com/plugins/video.php?href="
                + java.net.URLEncoder.encode(real, "UTF-8")
                + "&show_text=false&width=480&height=480";
        String html = Net.get(plugin, new String[][]{
                {"User-Agent", Net.UA},
                {"Accept-Language", "en-US,en;q=0.9"}}, 20000);

        String hd = unescape(first(P_HD, html));
        String sd = unescape(first(P_SD, html));
        if (hd.isEmpty() && sd.isEmpty()) {
            throw new Exception("拿不到视频地址（该帖子可能不公开，或需要登录才能看）");
        }

        TweetFetcher.Result r = new TweetFetcher.Result();
        r.platform = "facebook";
        r.tweetUrl = real;
        r.handle = "facebook";
        r.author = "Facebook";
        Matcher tm = P_TITLE.matcher(html);
        if (tm.find()) {
            r.text = tm.group(1).trim();
            int bar = r.text.indexOf('|');
            if (bar > 0) {
                r.author = r.text.substring(0, bar).trim();
                r.text = r.text.substring(bar + 1).trim();
            }
        }
        // 「Facebook」只是兜底名，能从页面拿到真实发布者就用真实的
        String who = unescape(first(P_OWNER, html));
        if (!who.isEmpty()) {
            r.author = who;
        } else if ("Facebook".equalsIgnoreCase(r.author) || r.author.isEmpty()) {
            r.author = "Facebook";
        }
        if (r.text.isEmpty()) r.text = unescape(first(P_DESC, html));
        // 标题里的尾巴：「xxx - 视频 | Facebook」之类
        r.text = r.text.replace(" | Facebook", "").replace("| Facebook", "").trim();
        // 「Facebook」「Video」这种不是标题
        if ("Facebook".equalsIgnoreCase(r.text) || "Video".equalsIgnoreCase(r.text)
                || r.text.length() < 2) {
            r.text = "";
        }
        if (r.text.length() > 200) r.text = r.text.substring(0, 200);

        add(r, hd, "视频 · 高清");
        add(r, sd, "视频 · 普通画质");
        if (r.items.isEmpty()) throw new Exception("没拿到视频地址");
        return r;
    }

    private static void add(TweetFetcher.Result r, String url, String label) {
        if (url == null || url.isEmpty()) return;
        TweetFetcher.Media m = new TweetFetcher.Media();
        m.url = url;
        m.thumb = "";
        m.label = label;
        m.kind = "video";
        m.ext = "mp4";
        r.items.add(m);
    }

    /** 插件页里的地址是 JSON 转义过的（\/、\u0025 等） */
    private static String unescape(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char nx = s.charAt(i + 1);
                if (nx == '/' || nx == '"' || nx == '\\') {
                    if (nx != '\\') sb.append(nx);
                    i++;
                    continue;
                }
                if (nx == 'u' && i + 5 < s.length()) {
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (Exception ignored) { }
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String hostOf(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }
}
