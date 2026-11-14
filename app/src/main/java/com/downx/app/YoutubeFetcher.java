package com.downx.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube：走 InnerTube 播放器接口，按多个客户端轮流试。
 *
 * YouTube 只对 360P / 720P 给「音画一体」的 mp4（formats 里的 itag 18 / 22）。
 * 1080P 及以上全是 DASH 分离流：一条纯视频（H.264）、一条纯音频（m4a）。
 * 所以这里把两条都取出来，交给 DlService 下载完用 MediaMuxer 合成一个 mp4
 * ——只搬 sample 不解码不重编码，几十秒就能合完，而且是有声音的 1080P。
 *
 * 客户端顺序：iOS → ANDROID_VR → MWEB → ANDROID → TVHTML5
 * 需要在能访问 YouTube 的网络下使用（国内要开代理）。
 */
public class YoutubeFetcher {

    private static final Pattern P_ID = Pattern.compile(
            "(?:v=|/shorts/|/embed/|youtu\\.be/)([A-Za-z0-9_-]{11})");

    private static final String URL_API = "https://www.youtube.com/youtubei/v1/player";

    /** 想要的分辨率，从高到低；只有 avc1 的 mp4 轨道能进 mp4 容器 */
    private static final int[] WANT_H = {1080, 720, 480, 360};

    private static class Client {
        final String body;
        final String ua;
        final String[][] headers;

        Client(String body, String ua, String[][] headers) {
            this.body = body;
            this.ua = ua;
            this.headers = headers;
        }
    }

    public static TweetFetcher.Result fetchSync(String link) throws Exception {
        final String id = videoId(link);
        if (id == null) throw new Exception("没识别到 YouTube 视频 ID，请粘贴完整链接");

        Client[] clients = {
                new Client(body(id, "IOS", "20.10.4", "\"deviceModel\":\"iPhone16,2\","),
                        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                        new String[][]{{"X-Goog-Api-Format-Version", "2"}}),
                new Client(body(id, "ANDROID_VR", "1.60.19",
                        "\"deviceModel\":\"Quest 3\",\"androidSdkVersion\":32,"),
                        "com.google.android.apps.youtube.vr.oculus/1.60.19 "
                                + "(Linux; U; Android 12; en_US)", null),
                new Client(body(id, "MWEB", "2.20250310.01.00", ""), Net.UA, null),
                new Client(body(id, "ANDROID", "20.10.38", "\"androidSdkVersion\":34,"),
                        "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip", null),
                new Client(body(id, "TVHTML5", "7.20250312.16.00", ""),
                        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version", null),
        };

        TweetFetcher.Result best = null;
        int bestScore = 0;
        List<String> deny = new ArrayList<>();
        boolean[] anyResponse = new boolean[1];
        int netFail = 0;

        for (Client c : clients) {
            int[] score = new int[1];
            TweetFetcher.Result r = tryClient(c, id, score, deny, anyResponse);
            if (!anyResponse[0]) {
                // 一个都没连上：换客户端也没用，别让用户干等五个客户端的超时
                if (++netFail >= 2) break;
                continue;
            }
            if (r == null) continue;
            if (score[0] > bestScore) {      // 分数 = 能拿到的最高分辨率，越高越优先
                best = r;
                bestScore = score[0];
            }
            if (bestScore >= 1080) break;    // 已经拿到 1080P，不用再试别的客户端
        }
        if (best != null) return best;
        // 失败时把 YouTube 的原话带上：要区分"视频不给下"和"代理没开"，
        // 否则用户只会看到"解析失败"，以为软件坏了（见 YtDeny 的说明）。
        throw new Exception(YtDeny.message(oembedTitle(id), deny, anyResponse[0]));
    }

    private static String body(String id, String name, String version, String extra) {
        return "{\"context\":{\"client\":{\"clientName\":\"" + name + "\","
                + "\"clientVersion\":\"" + version + "\"," + extra
                + "\"hl\":\"en\",\"gl\":\"US\"},"
                + "\"user\":{\"lockedSafetyMode\":false}},"
                + "\"videoId\":\"" + id + "\",\"contentCheckOk\":true,\"racyCheckOk\":true}";
    }

    /**
     * @param score       出参：这次拿到的最好质量（1080/720/360…），0 表示没拿到可用流
     * @param deny        出参：YouTube 拒绝的原因（"STATUS|reason"），失败时交给 {@link YtDeny} 翻译
     * @param anyResponse 出参：是否至少有一个客户端正常返回了 JSON（区分"被拒"和"连不上"）
     */
    private static TweetFetcher.Result tryClient(Client c, String id, int[] score,
                                                 List<String> deny, boolean[] anyResponse) {
        String json;
        try {
            json = postJson(URL_API, c.body, c.ua, c.headers);
        } catch (Exception e) {
            return null;
        }
        if (json == null || json.isEmpty()) return null;
        anyResponse[0] = true;
        try {
            JSONObject o = new JSONObject(json);
            JSONObject ps = o.optJSONObject("playabilityStatus");
            String status = ps == null ? "" : ps.optString("status", "");
            if (!"OK".equals(status)) {
                deny.add(status + "|" + (ps == null ? "" : ps.optString("reason", "")));
                return null;
            }

            JSONObject details = o.optJSONObject("videoDetails");
            JSONObject streaming = o.optJSONObject("streamingData");
            if (streaming == null) {
                deny.add("OK|没有 streamingData（可能正在直播或刚发布）");
                return null;
            }

            TweetFetcher.Result r = new TweetFetcher.Result();
            r.platform = "youtube";
            r.tweetUrl = "https://www.youtube.com/watch?v=" + id;
            r.handle = "youtube";
            r.author = details == null ? "YouTube" : details.optString("author", "YouTube");
            r.text = details == null ? "" : details.optString("title", "");
            if (details != null) {
                JSONObject thumbs = details.optJSONObject("thumbnail");
                JSONArray arr = thumbs == null ? null : thumbs.optJSONArray("thumbnails");
                if (arr != null && arr.length() > 0) {
                    JSONObject last = arr.optJSONObject(arr.length() - 1);
                    if (last != null) r.avatar = last.optString("url", "");
                }
            }

            fillDash(r, streaming.optJSONArray("adaptiveFormats"), score);
            fillProgressive(r, streaming.optJSONArray("formats"), score);
            if (r.items.isEmpty()) {
                deny.add("OK|只有 VP9/AV1 或需要签名解密的流，没有可直接下载的 mp4");
                return null;
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * DASH 分离流：挑一条 H.264 视频轨 + 一条 m4a 音轨，两条分别下载后合并。
     * 这是拿到 1080P 有声的唯一路子 —— YouTube 没有 1080P 的整包文件。
     */
    private static void fillDash(TweetFetcher.Result r, JSONArray adaptive, int[] score) {
        if (adaptive == null) return;

        // 同分辨率有多条（30fps/60fps、不同码率），留码率最大的那条
        Map<Integer, JSONObject> byHeight = new LinkedHashMap<>();
        JSONObject bestAudio = null;
        long bestAudioRate = -1;

        for (int i = 0; i < adaptive.length(); i++) {
            JSONObject f = adaptive.optJSONObject(i);
            if (f == null) continue;
            String url = f.optString("url", "");
            if (!url.startsWith("http")) continue;     // signatureCipher 的跳过（本 App 不解密）
            String mime = f.optString("mimeType", "");

            if (mime.startsWith("audio/mp4")) {
                long rate = f.optLong("bitrate", 0);
                if (rate > bestAudioRate) {
                    bestAudioRate = rate;
                    bestAudio = f;
                }
                continue;
            }
            if (!mime.startsWith("video/mp4") || !mime.contains("avc1")) continue;

            int h = f.optInt("height", 0);
            if (h <= 0) continue;
            long rate = f.optLong("bitrate", 0);
            JSONObject old = byHeight.get(h);
            if (old == null || old.optLong("bitrate", 0) < rate) byHeight.put(h, f);
        }

        // 只要音轨没拿到，就还是无声视频，交给调用方继续找别的客户端
        if (bestAudio == null) return;
        final String audioUrl = bestAudio.optString("url", "");
        if (!audioUrl.startsWith("http")) return;

        List<Integer> hs = new ArrayList<>(byHeight.keySet());
        Collections.sort(hs, Collections.reverseOrder());

        int added = 0;
        for (int h : hs) {
            if (added >= 3) break;
            boolean want = false;
            for (int w : WANT_H) if (w == h) want = true;
            if (!want) continue;                  // 1440P/2160P 只有 vp9/av1，mp4 装不下，跳过
            JSONObject f = byHeight.get(h);
            if (f == null) continue;

            TweetFetcher.Media m = new TweetFetcher.Media();
            m.url = f.optString("url", "");
            m.audioUrl = audioUrl;
            m.kind = "video";
            m.ext = "mp4";
            m.label = h + "P · 带声音";
            r.items.add(m);
            if (h > score[0]) score[0] = h;
            added++;
        }
    }

    /** 渐进式：画面+声音本来就在一个 mp4 里，itag 18=360P、22=720P */
    private static void fillProgressive(TweetFetcher.Result r, JSONArray formats, int[] score) {
        if (formats == null) return;
        JSONObject best = null;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null) continue;
            String url = f.optString("url", "");
            if (!url.startsWith("http")) continue;
            if (!f.optString("mimeType", "").contains("video/mp4")) continue;
            if (best == null || f.optInt("height", 0) > best.optInt("height", 0)) best = f;
        }
        if (best == null) return;
        int h = best.optInt("height", 0);

        // DASH 已经给了同分辨率的有声版本，这条就是重复的，不用列
        for (TweetFetcher.Media m : r.items) {
            if (m.label != null && m.label.startsWith(h + "P")) return;
        }
        TweetFetcher.Media m = new TweetFetcher.Media();
        m.url = best.optString("url", "");
        m.kind = "video";
        m.ext = "mp4";
        m.label = (h > 0 ? h + "P" : "视频") + " · 带声音";
        r.items.add(m);
        if (h > score[0]) score[0] = h;
    }

    private static String postJson(String url, String body, String ua, String[][] extra)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "*/*");
        if (extra != null) {
            for (String[] h : extra) c.setRequestProperty(h[0], h[1]);
        }
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
        if (in != null) in.close();
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return out.toString("UTF-8");
    }

    static String videoId(String link) {
        if (link == null) return null;
        Matcher m = P_ID.matcher(link);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 失败时补一个标题：oEmbed 不需要登录，被 bot check 挡住的视频它照样给标题，
     * 这样用户一眼能确认"是不是我要下的那条"。拿不到就返回空，不耽误报错。
     */
    private static String oembedTitle(String id) {
        HttpURLConnection c = null;
        try {
            String u = "https://www.youtube.com/oembed?url=https://youtu.be/" + id + "&format=json";
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", Net.UA);
            if (c.getResponseCode() >= 400) return "";
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new JSONObject(out.toString("UTF-8")).optString("title", "");
        } catch (Throwable t) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
