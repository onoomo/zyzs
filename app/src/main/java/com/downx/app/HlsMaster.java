package com.downx.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读 m3u8 **主清单**，列出里面每一档画质。
 *
 * 为什么需要它：X / IG 大多数时候在 JSON 里直接给好几条 mp4（我们遍历就能出多档），
 * 但有些帖子只给一条 mp4、多档只存在于 HLS 主清单（content_type 为 x-mpegURL）里。
 * 这时把主清单读一遍、按 RESOLUTION 列出各档，用户才能选。
 *
 * 只返回宽高，**不返回子清单地址**：下载器本来就吃「主清单 + pick=高度」这一套，
 * 让它自己去挑子流，还能顺带认出 EXT-X-MEDIA 里的独立音轨（否则下出来没声音）。
 */
public class HlsMaster {

    private static final Pattern P_RES = Pattern.compile("RESOLUTION=(\\d{2,5})x(\\d{2,5})");
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    /** 读主清单，返回各档 {宽, 高}，按高从大到小；读不到就返回空表 */
    public static List<int[]> renditions(String masterUrl, String referer) {
        if (masterUrl == null || masterUrl.isEmpty()) return new ArrayList<>();
        try {
            String[][] hs = referer == null || referer.isEmpty()
                    ? new String[][]{{"User-Agent", UA}}
                    : new String[][]{{"User-Agent", UA}, {"Referer", referer}};
            return parse(Net.get(masterUrl, hs, 9000));
        } catch (Throwable ignored) {
            // 读不到就当没有：调用方会继续用原来的单条直链，不影响下载
            return new ArrayList<>();
        }
    }

    /**
     * 画质怎么叫：**按短边**。
     *
     * 竖屏 1080x1920 全网都叫 1080P，不叫 1920P —— 1920 是竖屏的"高"，
     * 只有把手机横过来才是 1080。所以命名统一取 min(宽,高)：
     * 1920x1080 → 1080p，1080x1920 → 1080p，3840x2160 → 2160p。
     *
     * 注意：这只管**显示和文件名**；下载器匹配流用的是 height（见 expandHls 里的 pick），
     * 竖屏必须传 1920 才能命中 1080x1920 那一档。两件事别混。
     */
    public static String label(int w, int h) {
        return Math.min(w, h) + "p";
    }

    /** 纯文本解析（单独抽出来，是为了能在桌面 JDK 上直接测，不用连网/不用手机） */
    public static List<int[]> parse(String txt) {
        List<int[]> out = new ArrayList<>();
        if (txt == null || !txt.contains("#EXT-X-STREAM-INF")) return out;
        for (String raw : txt.split("\r?\n")) {
            String l = raw.trim();
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
            Matcher m = P_RES.matcher(l);
            if (!m.find()) continue;
            try {
                int w = Integer.parseInt(m.group(1));
                int h = Integer.parseInt(m.group(2));
                if (w <= 0 || h <= 0) continue;
                boolean dup = false;
                for (int[] o : out) if (o[0] == w && o[1] == h) { dup = true; break; }
                if (!dup) out.add(new int[]{w, h});
            } catch (NumberFormatException ignored) { }
        }
        Collections.sort(out, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return b[1] - a[1];
            }
        });
        return out;
    }
}
