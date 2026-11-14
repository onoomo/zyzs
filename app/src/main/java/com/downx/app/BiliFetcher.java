package com.downx.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站：b23.tv 短链 → BV 号 → 官方 web 接口
 *   1. /x/web-interface/view  拿标题、封面、UP 主、cid（多 P 每 P 一个 cid）
 *   2. /x/player/playurl      拿 mp4 直链（platform=html5 给单文件 durl）
 *
 * 下载时需要带 Referer: https://www.bilibili.com/ ，否则 CDN 会 403。
 * 未登录：html5 接口只给 720P 单文件（实测请求 1080P 会被强制降回 720P）。
 * 登录：走 dash 接口，画面和声音分开下再合成，1080P 及以上要大会员账号。
 */
public class BiliFetcher {

    private static final Pattern P_BV = Pattern.compile("(BV[0-9A-Za-z]{10,12})");
    private static final Pattern P_AV = Pattern.compile("(?:/video/av|^av)(\\d+)", Pattern.CASE_INSENSITIVE);

    /** 异步入口（下载失败自愈用）：与 TweetFetcher.fetch 同一套回调协议 */
    public static void fetch(final String link, final TweetFetcher.Listener cb) {
        new Thread(() -> {
            try {
                cb.onDone(fetchSync(link));
            } catch (Throwable t) {
                cb.onFail(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }, "bili-fetch").start();
    }

    public static TweetFetcher.Result fetchSync(String link) throws Exception {
        String resolved = link;
        String low = link.toLowerCase();
        if (low.contains("b23.tv")) {
            try {
                String r = Net.resolve(link, 12000);
                if (r != null && !r.isEmpty()) resolved = r;
            } catch (Throwable ignored) { }
        }

        String bv = bvOf(resolved);
        if (bv == null) bv = bvOf(link);
        String av = null;
        if (bv == null) {
            Matcher m = P_AV.matcher(resolved);
            if (m.find()) av = m.group(1);
        }
        if (bv == null && av == null) {
            throw new Exception("没识别到 BV 号，请粘贴「分享 → 复制链接」的完整内容");
        }

        String q = bv != null ? "bvid=" + bv : "aid=" + av;
        String viewJson;
        try {
            viewJson = Net.get("https://api.bilibili.com/x/web-interface/view?" + q,
                    new String[][]{{"User-Agent", Net.UA_DESKTOP},
                            {"Referer", "https://www.bilibili.com/"}}, 18000);
        } catch (Exception e) {
            throw new Exception("连不上 B 站接口：" + e.getMessage());
        }

        JSONObject view = new JSONObject(viewJson);
        if (view.optInt("code", -1) != 0) {
            throw new Exception("B 站返回：" + view.optString("message", "内容不存在或已下架"));
        }
        JSONObject data = view.optJSONObject("data");
        if (data == null) throw new Exception("B 站没有返回视频信息");

        // 真实画面尺寸：B 站封面一律 16:9 横图，分不出这条是竖拍还是横拍，
        // 所以把 view 接口给的 dimension 一路带到解析结果里（rotate 90/270 要交换宽高）
        int dimW = 0, dimH = 0;
        JSONObject dim = data.optJSONObject("dimension");
        if (dim != null) {
            dimW = dim.optInt("width", 0);
            dimH = dim.optInt("height", 0);
            int rot = dim.optInt("rotate", 0);
            if (rot == 90 || rot == 270) { int t = dimW; dimW = dimH; dimH = t; }
        }

        String title = data.optString("title", "");
        String cover = data.optString("pic", "");
        if (cover.startsWith("http://")) cover = "https://" + cover.substring(7);
        String bvid = data.optString("bvid", bv);
        JSONObject owner = data.optJSONObject("owner");
        String up = owner == null ? "bilibili" : owner.optString("name", "bilibili");
        String finalBv = bvid;

        TweetFetcher.Result r = new TweetFetcher.Result();
        r.platform = "bilibili";
        r.tweetUrl = "https://www.bilibili.com/video/" + finalBv;
        r.handle = "bilibili";
        r.author = up;
        r.text = title;
        r.date = "";

        // 多 P：每 P 一个 cid，分开列出来
        JSONArray pages = data.optJSONArray("pages");
        int listed = 0;
        boolean logged = BiliSession.has(null);
        if (pages != null && pages.length() > 0) {
            for (int i = 0; i < pages.length() && i < 10; i++) {
                JSONObject p = pages.optJSONObject(i);
                if (p == null) continue;
                String cid = p.optString("cid", "");
                if (cid.isEmpty()) continue;
                String part = p.optString("part", "");
                String label = pages.length() > 1
                        ? "P" + (i + 1) + (part.isEmpty() ? "" : " · " + part)
                        : "视频";
                if (listed(r, finalBv, cid, label, cover, logged, dimW, dimH)) listed++;
            }
        }
        if (listed == 0) {
            String cid = data.optString("cid", "");
            if (!cid.isEmpty() && listed(r, finalBv, cid, "视频", cover, logged, dimW, dimH)) listed++;
        }
        if (listed == 0) throw new Exception("B 站没给出可下载的地址（这条可能是番剧或需要大会员）");
        StringBuilder dbg = new StringBuilder("logged=").append(logged)
                .append(" pages=").append(pages == null ? -1 : pages.length())
                .append(" items=").append(r.items.size());
        for (TweetFetcher.Media m : r.items) dbg.append(" | ").append(m.label);
        android.util.Log.i("BiliFetcher", dbg.toString());
        return r;
    }

    /** 登录时先试 dash 高清，不行再退回 720P 单文件 */
    private static boolean listed(TweetFetcher.Result r, String bvid, String cid,
                                  String label, String cover, boolean logged,
                                  int dimW, int dimH) {
        if (logged) {
            try {
                if (addDash(r, bvid, cid, label, cover)) {
                    // 双保险：dash 每档都带一份 html5 单文件直链做跨协议兜底，
                    // dash 整条链路被拒（节点拒/签名失效）时下载器换它从头下
                    String fb = durlOf(bvid, cid, 80);
                    if (fb != null) {
                        for (TweetFetcher.Media m : r.items) m.fallbackUrl = fb;
                    }
                    return true;
                }
            } catch (Exception e) {
                // dash 挂了（风控/番剧等）就退回 720P，别让用户下不了；日志留线索
                android.util.Log.w("BiliFetcher", "dash 退回 720P: " + e);
            }
        }
        return addPlay(r, bvid, cid, label, cover, dimW, dimH);
    }

    /** 旧协议直链（html5 单文件）：只取一条，不进列表，给 dash 流当跨协议兜底用 */
    private static String durlOf(String bvid, String cid, int qn) {
        try {
            String json = Net.get("https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                            + "&cid=" + cid + "&qn=" + qn + "&fnval=1&fnver=0&fourk=1"
                            + "&platform=html5&high_quality=1",
                    new String[][]{{"User-Agent", Net.UA_DESKTOP},
                            {"Referer", "https://www.bilibili.com/video/" + bvid}}, 15000);
            JSONObject o = new JSONObject(json);
            if (o.optInt("code", -1) != 0) return null;
            JSONObject d = o.optJSONObject("data");
            JSONArray durl = d == null ? null : d.optJSONArray("durl");
            String u = durl == null || durl.length() == 0 ? ""
                    : durl.optJSONObject(0).optString("url", "");
            return u.startsWith("http") ? u : null;
        } catch (Exception e) {
            return null;    // 兜底拿不到就算了，主链继续用
        }
    }

    /**
     * 登录态 dash：每档一条（最高 4K，普通账号到 1080P），
     * 画面和声音是两条流，下载器会下完合成（和 YouTube 1080P 同一条路）。
     *
     * 注意：带 Cookie 的 web playurl 必须做 wbi 签名（w_rid+wts），否则 B 站风控直接拒绝，
     * 表现就是「明明登录了还是只有 720P」——dash 失败被吞掉退回了单文件。
     */
    private static boolean addDash(TweetFetcher.Result r, String bvid, String cid,
                                   String label, String cover) throws Exception {
        String json = Net.get("https://api.bilibili.com/x/player/playurl?" + wbiQuery(bvid, cid),
                new String[][]{{"User-Agent", Net.UA_DESKTOP},
                        {"Referer", "https://www.bilibili.com/video/" + bvid},
                        {"Cookie", BiliSession.cookie(null)}}, 18000);
        JSONObject o = new JSONObject(json);
        if (o.optInt("code", -1) != 0) {
            throw new Exception(o.optString("message", "接口失败"));
        }
        JSONObject d = o.optJSONObject("data");
        JSONObject dash = d == null ? null : d.optJSONObject("dash");
        if (dash == null) throw new Exception("no dash");

        // 音轨取第一路（最高音质），全部档共用
        JSONArray auds = dash.optJSONArray("audio");
        String aUrl = "";
        if (auds != null && auds.length() > 0) {
            JSONObject a0 = auds.optJSONObject(0);
            if (a0 != null) aUrl = firstUrl(a0);
        }

        // 同一档有 avc/hevc/av01 三种编码，avc 兼容性最好，优先留它
        JSONArray vids = dash.optJSONArray("video");
        if (vids == null || vids.length() == 0) throw new Exception("no video");
        Map<Integer, JSONObject> best = new LinkedHashMap<>();
        for (int i = 0; i < vids.length(); i++) {
            JSONObject v = vids.optJSONObject(i);
            if (v == null) continue;
            int id = v.optInt("id");
            if (id <= 0) continue;
            String codec = v.optString("codecs", "");
            JSONObject old = best.get(id);
            if (old == null || (codec.startsWith("avc")
                    && !old.optString("codecs", "").startsWith("avc"))) {
                best.put(id, v);
            }
        }
        List<Integer> ids = new ArrayList<>(best.keySet());
        Collections.sort(ids, Collections.reverseOrder());   // 高清在前
        android.util.Log.i("BiliFetcher", "dash ok, 档位=" + ids);
        int n = ids.size();
        for (int k = 0; k < n; k++) {
            JSONObject v = best.get(ids.get(k));
            String u = firstUrl(v);
            if (!u.startsWith("http")) continue;
            TweetFetcher.Media m = new TweetFetcher.Media();
            m.url = u;
            m.audioUrl = aUrl;
            // 同一签名的备用节点：主节点拒（403）时下载器会自动换它，不用重新解析
            JSONArray bu = v.optJSONArray("backupUrl");
            if (bu == null) bu = v.optJSONArray("backup_url");
            if (bu != null && bu.length() > 0) m.altUrl = bu.optString(0, "");
            m.thumb = cover;
            m.kind = "video";
            m.ext = "mp4";
            // 真实像素宽高：解析页靠它把预览框摆成竖条/横幅，并标「竖屏/横屏」
            m.vw = v.optInt("width", 0);
            m.vh = v.optInt("height", 0);
            String q = qualityName(ids.get(k)) + (n > 1 ? "" : "");
            String or = m.orient();
            m.label = label + " · " + q + " · 带声音" + (or.isEmpty() ? "" : " · " + or);
            r.items.add(m);
        }
        return !r.items.isEmpty();
    }

    /** baseUrl / base_url 兼容取 */
    private static String firstUrl(JSONObject v) {
        String u = v.optString("baseUrl", "");
        if (u.isEmpty()) u = v.optString("base_url", "");
        return u;
    }

    // ==================== wbi 签名 ====================

    /** B 站公开的 wbi 混淆表（固定不变），重排 img_key+sub_key 后取前 32 位 */
    private static final int[] WBI_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    private static volatile String sMixinKey = "";
    private static volatile long sMixinAt = 0L;

    /** 从 nav 接口取 img_key/sub_key 算 mixin key，缓存 6 小时 */
    private static String mixinKey() throws Exception {
        if (!sMixinKey.isEmpty() && System.currentTimeMillis() - sMixinAt < 6 * 3600_000L) {
            return sMixinKey;
        }
        String json = Net.get("https://api.bilibili.com/x/web-interface/nav",
                new String[][]{{"User-Agent", Net.UA_DESKTOP},
                        {"Referer", "https://www.bilibili.com/"}}, 12000);
        JSONObject data = new JSONObject(json).optJSONObject("data");
        JSONObject wbi = data == null ? null : data.optJSONObject("wbi_img");
        if (wbi == null) throw new Exception("wbi: nav 没给 key");
        String raw = stem(wbi.optString("img_url")) + stem(wbi.optString("sub_url"));
        if (raw.length() < 64) throw new Exception("wbi: key 长度不对");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) sb.append(raw.charAt(WBI_TAB[i]));
        sMixinKey = sb.toString();
        sMixinAt = System.currentTimeMillis();
        return sMixinKey;
    }

    /** "abc.png" → "abc"（key 是文件名去掉扩展名） */
    private static String stem(String url) {
        if (url == null) return "";
        int s = url.lastIndexOf('/') + 1;
        int e = url.indexOf('.', s);
        if (e < 0) e = url.length();
        return url.substring(s, e);
    }

    /** bvid,cid,fnval,fourk,platform,qn,wts 按字母序拼接后 md5(query+mixinKey) 得 w_rid */
    private static String wbiQuery(String bvid, String cid) throws Exception {
        long wts = System.currentTimeMillis() / 1000L;
        // 不带 platform（对齐 YunX）：参数按字母序参与 wbi 签名，改这里必须连签名串一起改
        String q = "bvid=" + bvid + "&cid=" + cid
                + "&fnval=4048&fnver=0&fourk=1&qn=120&wts=" + wts;
        return q + "&w_rid=" + md5(q + mixinKey());
    }

    private static String md5(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("MD5").digest(s.getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String qualityName(int id) {
        switch (id) {
            case 120: return "4K";
            case 116: return "1080P60";
            case 112: return "1080P+";
            case 80: return "1080P";
            case 74: return "720P60";
            case 64: return "720P";
            case 32: return "480P";
            case 16: return "360P";
            default: return id + "P";
        }
    }

    /**
     * 列出可下的每一档 mp4（720P / 480P / 360P，未登录能拿到哪档就列哪档）。
     *
     * 以前是「取到第一档就 return」——所以下载页永远只有一个按钮，用户没法选。
     * 现在每档都要一次 playurl，按实际返回的 quality 起名；B 站会把过高的 qn
     * 降级返回，所以必须用 URL 去重，否则会出现两个一模一样的按钮。
     */
    private static boolean addPlay(TweetFetcher.Result r, String bvid, String cid,
                                   String label, String cover, int dimW, int dimH) {
        int[] qns = {64, 32, 16};
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<Integer> seenQ = new java.util.HashSet<>();
        int added = 0;
        for (int i = 0; i < qns.length; i++) {
            try {
                String json = Net.get("https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                                + "&cid=" + cid + "&qn=" + qns[i] + "&fnval=0&fourk=0&platform=html5&high_quality=1",
                        new String[][]{{"User-Agent", Net.UA_DESKTOP},
                                {"Referer", "https://www.bilibili.com/video/" + bvid}}, 15000);
                JSONObject o = new JSONObject(json);
                if (o.optInt("code", -1) != 0) continue;
                JSONObject d = o.optJSONObject("data");
                if (d == null) continue;
                JSONArray durl = d.optJSONArray("durl");
                if (durl == null || durl.length() == 0) continue;
                JSONObject one = durl.optJSONObject(0);
                String url = one == null ? "" : one.optString("url", "");
                if (!url.startsWith("http")) continue;
                int q = d.optInt("quality", qns[i]);
                // 降级时不同 qn 会拿到同一档，只是 CDN 域名不同 —— 按 quality 去重
                if (!seenQ.add(q)) continue;
                if (!seen.add(url)) continue;

                TweetFetcher.Media m = new TweetFetcher.Media();
                m.url = url;
                JSONArray bu = one.optJSONArray("backup_url");
                if (bu != null && bu.length() > 0) m.altUrl = bu.optString(0, "");
                m.thumb = cover;      // 封面给预览用，不然下载页是一块黑
                m.kind = "video";
                m.ext = "mp4";
                m.vw = dimW;
                m.vh = dimH;
                // 用服务端实际返回的 quality 起名，别用我们请求的 qn（会被降级）
                String or = m.orient();
                m.label = label + " · " + qualityName(q) + (or.isEmpty() ? "" : " · " + or);
                r.items.add(m);
                added++;
            } catch (Exception ignored) { }
        }
        return added > 0;
    }

    static String bvOf(String link) {
        if (link == null) return null;
        Matcher m = P_BV.matcher(link);
        return m.find() ? m.group(1) : null;
    }
}
