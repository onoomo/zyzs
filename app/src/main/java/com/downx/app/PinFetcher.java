package com.downx.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pinterest 解析：抓帖子页里的 og:image 与 video_list（V_720P 等），无需登录。
 */
public class PinFetcher {

    private static final Pattern P_ID = Pattern.compile("/pin/(\\d+)");
    private static final Pattern P_OG_IMG = Pattern.compile("<meta[^>]*og:image[^>]*>");
    private static final Pattern P_CONTENT = Pattern.compile("content=\"([^\"]+)\"");
    private static final Pattern P_TITLE = Pattern.compile("<meta[^>]*og:title[^>]*>");
    private static final Pattern P_VIDEO = Pattern.compile(
            "\"V_(\\d+)P\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"");
    /** 作者：{"pinner":{"username":"x","full_name":"Y"}} */
    private static final Pattern P_FULLNAME = Pattern.compile(
            "\"full_name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){2,60})\"");
    private static final Pattern P_USER = Pattern.compile(
            "\"pinner\"\\s*:\\s*\\{[^{}]*?\"username\"\\s*:\\s*\"([A-Za-z0-9_.\\-]{2,40})\"");
    private static final Pattern P_DESC = Pattern.compile(
            "<meta[^>]*og:description[^>]*content=\"([^\"]{4,300})\"");

    public static TweetFetcher.Result fetchSync(String link) throws Exception {
        String real = link;
        if (hostOf(real).contains("pin.it")) {
            try { real = Net.resolve(real, 15000); } catch (Exception ignored) { }
        }
        Matcher im = P_ID.matcher(real);
        if (!im.find()) throw new Exception("没识别到 Pinterest 帖子 ID");
        String id = im.group(1);

        String html = Net.get("https://www.pinterest.com/pin/" + id + "/",
                new String[][]{
                        {"User-Agent", Net.UA},
                        {"Accept-Language", "en-US,en;q=0.9"}}, 25000);

        TweetFetcher.Result r = new TweetFetcher.Result();
        r.platform = "pinterest";
        r.tweetUrl = "https://www.pinterest.com/pin/" + id + "/";
        r.handle = "pinterest";
        r.author = "Pinterest";

        String title = meta(P_TITLE, html);
        if (!title.isEmpty()) {
            r.text = title.replace(" | Pinterest", "").replace(" - Pinterest", "").trim();
            if (r.text.length() > 200) r.text = r.text.substring(0, 200);
        }
        // 真实作者：pinner.full_name → pinner.username
        String who = pick(P_FULLNAME, html);
        if (who.isEmpty()) who = pick(P_USER, html);
        if (!who.isEmpty()) r.author = who;
        if (r.text.isEmpty()) r.text = pick(P_DESC, html);

        String image = meta(P_OG_IMG, html);
        if (!image.isEmpty() && image.contains("/736x/")) {
            image = image.replace("/736x/", "/originals/");
        }

        // 视频（视频 Pin 才有）
        Map<String, String> videos = new LinkedHashMap<>();
        Matcher vm = P_VIDEO.matcher(html);
        while (vm.find()) {
            String q = vm.group(1);
            String url = vm.group(2).replace("\\/", "/");
            if (url.startsWith("http") && !videos.containsKey(q)) videos.put(q, url);
        }
        for (Map.Entry<String, String> en : videos.entrySet()) {
            TweetFetcher.Media m = new TweetFetcher.Media();
            m.url = en.getValue();
            m.thumb = image;
            m.label = "视频 · " + en.getKey() + "p";
            m.kind = "video";
            m.ext = "mp4";
            r.items.add(m);
        }

        if (!image.isEmpty()) {
            TweetFetcher.Media m = new TweetFetcher.Media();
            m.url = image;
            m.thumb = image;
            m.label = "图片 · 原图";
            m.kind = "photo";
            m.ext = "jpg";
            r.items.add(m);
        }

        if (r.items.isEmpty()) throw new Exception("这条 Pinterest 内容取不到媒体，可能是私密图板");
        return r;
    }

    private static String meta(Pattern p, String html) {
        Matcher m = p.matcher(html);
        if (!m.find()) return "";
        Matcher c = P_CONTENT.matcher(m.group());
        return c.find() ? c.group(1) : "";
    }

    private static String pick(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return "";
        String v = m.group(1).replace("\\/", "/").replace("\\\"", "\"")
                .replace("\\u0026", "&").replaceAll("\\s+", " ").trim();
        if ("pinterest".equalsIgnoreCase(v) || "null".equalsIgnoreCase(v)) return "";
        return v;
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
