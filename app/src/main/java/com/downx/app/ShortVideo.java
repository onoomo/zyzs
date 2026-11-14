package com.downx.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音 / 快手的公共部分：认平台、抠作品 ID、列出候选页面。
 *
 * 为什么这里只剩「找页面」这一件事：
 * 这两家都改成前端渲染了 —— 服务端返回的 HTML 里既没有 play_addr 也没有 mp4，
 * 视频地址由页面自己的 JS 现算签名（a_bogus 那套）再去请求。
 * 所以任何「免签名接口」都是短命的（旧 iteminfo 已经返回空包）。
 * 能长期用的只有一条：用真实浏览器打开官方页面，让平台自己的 JS 去签名，
 * 我们只把播放器实际拉流的地址截下来。页面改版了顶多补条 URL 特征。
 */
public class ShortVideo {

    /** 7xxxxxxxxxxxxxxxxxx / 图文 note 都会出现在这几种路径里 */
    private static final Pattern P_DY = Pattern.compile(
            "(?:video|note|share_video|slides|share/slides|shipin)/(\\d{15,25})");
    private static final Pattern P_DY_MODAL = Pattern.compile("[?&]modal_id=(\\d{15,25})");
    private static final Pattern P_KS = Pattern.compile(
            "(?:short-video|photo|fw/photo|video)/([A-Za-z0-9_-]{6,40})");

    static final String UA_M = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1";

    public static boolean isDouyin(String link) {
        String h = hostOf(link);
        return h.contains("douyin.com") || h.contains("iesdouyin.com");
    }

    public static boolean isKuaishou(String link) {
        String h = hostOf(link);
        return h.contains("kuaishou.com") || h.contains("gifshow.com")
                || h.contains("chenzhongtech.com");
    }

    /** 作品 ID：先看整条链接，短链还原后再看一次 */
    static String idOf(boolean douyin, String link) {
        if (link == null) return null;
        if (douyin) {
            Matcher m = P_DY.matcher(link);
            if (m.find()) return m.group(1);
            Matcher m2 = P_DY_MODAL.matcher(link);
            if (m2.find()) return m2.group(1);
            return null;
        }
        Matcher m = P_KS.matcher(link);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 候选页面，按「最可能直接放视频」排序。
     * 一个不行就换下一个，别在一棵树上吊死。
     */
    static List<String> pagesOf(boolean douyin, String id) {
        List<String> out = new ArrayList<>();
        if (id == null) return out;
        if (douyin) {
            out.add("https://www.iesdouyin.com/share/video/" + id + "/");
            out.add("https://m.douyin.com/share/video/" + id);
            out.add("https://www.douyin.com/video/" + id);
        } else {
            // 快手分享短链现在就是跳到 www.kuaishou.com/short-video/<id>，
            // 这条页在移动 UA 下会直接给 H5 播放器（能截到 mp4 和封面）；
            // c.kuaishou.com/fw/photo 只会再跳一次，放第二位兜底。
            out.add("https://www.kuaishou.com/short-video/" + id);
            out.add("https://c.kuaishou.com/fw/photo/" + id);
        }
        return out;
    }

    /** 短链还原（v.douyin.com / v.kuaishou.com）；失败就原样返回 */
    static String resolve(String link) {
        return resolve(link, 5000);
    }

    /**
     * 短链还原。超时给短一点：这一步只是为了拿作品 ID，
     * 卡在这里十几秒就是用户感知到的「解析卡住」。
     */
    static String resolve(String link, int timeoutMs) {
        String h = hostOf(link);
        if (h.contains("v.douyin.com") || h.contains("v.kuaishou.com")
                || h.contains("xhslink.") || h.contains("b23.tv")) {
            try {
                String r = Net.resolve(link, timeoutMs);
                if (r != null && !r.isEmpty()) return r;
            } catch (Throwable ignored) { }
        }
        return link;
    }

    /** 链接里所有 http(s) 地址（分享文案里常夹中文） */
    static Set<String> linksIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("https?://[^\\s，,。；;、\"'）+]+").matcher(text);
        while (m.find()) out.add(m.group());
        out.add(text.trim());
        return out;
    }

    /**
     * 从分享口令（用户「复制链接」拿到的整段文字）里抠标题文案。
     *
     * 为什么需要：快手 H5 分享页的 document.title 是空的，用桌面 UA 还会被踢到
     * 「浏览器版本过低」页。唯一稳定的标题来源就是用户粘进来的这段口令。
     *
     * 输入：
     *   https://v.kuaishou.com/n3KkLyq3 "狠人大队 "小炮子 "小炮子是个人才
     *   该作品在快手被播放过14.1万次，点击链接，打开【快手】直接观看！
     * 输出：
     *   狠人大队 小炮子 小炮子是个人才
     */
    /**
     * 从任意文本里抠出受支持平台的链接（粘贴分享口令时用）。
     * 放在这个纯 Java 类里是为了能在主机上直接跑测试，不用装手机。
     *
     * @return 找到的第一条受支持链接，找不到返回 null
     */
    static String linkIn(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://\\S+").matcher(text);
        while (m.find()) {
            String s = m.group();
            String low = s.toLowerCase();
            if (low.contains("twitter.com") || low.contains("x.com")
                    || low.contains("t.co")
                    || low.contains("instagram.com") || low.contains("instagr.am")
                    || low.contains("twimg.com")
                    || low.contains("tiktok.com")
                    || low.contains("facebook.com") || low.contains("fb.watch")
                    || low.contains("fb.me")
                    || low.contains("pinterest.") || low.contains("pin.it")
                    || low.contains("douyin.com") || low.contains("iesdouyin.com")
                    || low.contains("kuaishou.com") || low.contains("gifshow.com")
                    || low.contains("chenzhongtech.com")
                    || low.contains("xiaohongshu.com") || low.contains("xhslink.")
                    || low.contains("bilibili.com") || low.contains("b23.tv")
                    || low.contains("youtube.com") || low.contains("youtu.be")
                    || low.contains("fxtwitter") || low.contains("vxtwitter")) {
                return s.replaceAll("[),.;\\]]+$", "");
            }
        }
        return null;
    }

    static String captionFromShare(String share) {
        if (share == null) return "";
        String s = share;
        // 链接本身、平台固定尾巴都不要
        s = s.replaceAll("https?://\\S+", " ");
        s = s.replaceAll("该作品在快手被播放过[^，,。]*[，,。]?", " ");
        s = s.replaceAll("点击链接[^，,。]*[，,。]?", " ");
        s = s.replaceAll("打开【?快手】?直接观看[！!。]?", " ");
        s = s.replaceAll("复制此链接[^，,。]*[，,。]?", " ");
        s = s.replaceAll("来自【?抖音】?[^，,。]*[，,。]?", " ");
        // ---- 抖音口令：`8.36 复制打开抖音，看看【某某的作品】正文 # 话题 … 01/07 E@u.SY` ----
        s = s.replaceAll("^\\s*\\d+(\\.\\d+)?\\s*", " ");        // 开头的版本号 8.36
        s = s.replaceAll("[A-Za-z]{2,4}:\\/\\s*", " ");           // 口令码 gOe:/
        s = s.replaceAll("复制打开抖音[，,]?", " ");
        s = s.replaceAll("看看", " ");
        s = s.replaceAll("【[^】]*作品】", " ");                   // 【喵星人的作品】这类包装
        s = s.replaceAll("打开[Dd]ou音搜索[，,]?", " ");
        s = s.replaceAll("直接观看视频[！!。]?", " ");
        s = s.replaceAll("\\b\\d{2}/\\d{2}\\b", " ");             // 尾部的 11/23
        s = s.replaceAll("\\b[A-Za-z]@[A-Za-z0-9.]+\\b", " ");    // 尾部的 E@m.QK
        s = s.replaceAll("[，,。！!；;、#\\s]+", " ").trim();
        // 口令里到处是平台加的引号（"狠人大队 "小炮子），全去掉再收尾
        s = s.replaceAll("[\"“”'‘’「」]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() < 2) return "";
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s;
    }

    static String hostOf(String link) {
        try {
            String s = link;
            int p = s.indexOf("://");
            if (p > 0) s = s.substring(p + 3);
            int slash = s.indexOf('/');
            if (slash > 0) s = s.substring(0, slash);
            int colon = s.indexOf(':');
            if (colon > 0) s = s.substring(0, colon);
            return s.toLowerCase();
        } catch (Throwable t) {
            return "";
        }
    }
}
