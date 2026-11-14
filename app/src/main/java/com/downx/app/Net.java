package com.downx.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream; // 保留备用

/** 极简 HTTP 工具，零第三方依赖 */
public class Net {

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    /**
     * B 站专用桌面 UA（对齐 YunX 的 UA_CHROME）。实测（tests/bili）：
     * bilivideo.com 标准 CDN 对「与解析签名不匹配的 UA」一律 403，
     * 移动 UA 拿到的 URL 在 backup 节点上桌面 UA 才放行；桌面 UA 全场景 206。
     * 所以 B 站从解析到下载必须全程用这一个 UA。
     */
    public static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** host 是不是 B 站的 CDN（下载这类 URL 要换桌面 UA，见 UA_DESKTOP 注释） */
    public static boolean isBiliCdn(String url) {
        if (url == null) return false;
        try {
            String h = new URL(url).getHost();
            return h.endsWith("bilivideo.com") || h.endsWith("bilivideo.cn")
                    || h.endsWith("bilibili.com");
        } catch (Throwable t) {
            return false;
        }
    }

    public static String get(String url) throws IOException {
        return get(url, null, 15000);
    }

    public static String get(String url, String[][] headers, int timeoutMs) throws IOException {
        Resp r = getResp(url, headers, timeoutMs);
        if (r.code >= 400) throw new IOException("HTTP " + r.code);
        return r.body;
    }

    /** 带状态码的 GET：失败时也能拿到响应体，便于看清 Instagram 到底返回了什么 */
    public static final class Resp {
        public int code;
        public String body = "";
        public String finalUrl = "";
        /** 服务端下发的 cookie（多条用 "; " 拼好），需要维持会话的站点用得上 */
        public String cookies = "";

        public String head(int n) {
            String s = body == null ? "" : body.replaceAll("\\s+", " ").trim();
            return s.length() > n ? s.substring(0, n) : s;
        }

        @Override
        public String toString() {
            return "HTTP " + code + " " + head(60);
        }
    }

    public static Resp getResp(String url, String[][] headers, int timeoutMs) {
        Resp out = new Resp();
        HttpURLConnection c = null;
        try {
            c = open(url, headers, timeoutMs);
            out.code = c.getResponseCode();
            out.finalUrl = c.getURL() == null ? url : c.getURL().toString();
            out.cookies = readCookies(c);
            InputStream in = out.code >= 400 ? c.getErrorStream() : c.getInputStream();
            out.body = readAll(in);
        } catch (Throwable t) {
            out.code = -1;
            out.body = "EX " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** GET 并跟随重定向，返回最终 URL（用于 t.co 短链还原） */
    public static String resolve(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = open(url, null, timeoutMs);
        InputStream in = c.getInputStream();
        readAll(in); // 读完才会完成跳转
        String finalUrl = c.getURL().toString();
        c.disconnect();
        return finalUrl;
    }

    /**
     * 表单 POST，返回状态码 / 响应体 / 服务端下发的 Cookie。
     * 登录这类操作必须能看到 4xx 的响应体（IG 的错误原因都在 body 里），所以不抛异常。
     */
    public static Resp postForm(String url, String[][] headers, String body, int timeoutMs) {
        Resp out = new Resp();
        HttpURLConnection c = null;
        try {
            c = open(url, headers, timeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body == null ? 0 : body.getBytes("UTF-8").length);
            if (body != null) c.getOutputStream().write(body.getBytes("UTF-8"));
            out.code = c.getResponseCode();
            out.finalUrl = c.getURL() == null ? url : c.getURL().toString();
            out.cookies = readCookies(c);
            InputStream in = out.code >= 400 ? c.getErrorStream() : c.getInputStream();
            out.body = readAll(in);
        } catch (Throwable t) {
            out.code = -1;
            out.body = "EX " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
        return out;
    }

    public static String post(String url, String[][] headers, int timeoutMs) throws IOException {
        HttpURLConnection c = open(url, headers, timeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.getOutputStream().write(new byte[0]);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = readAll(in);
        c.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code);
        return body;
    }

    private static HttpURLConnection open(String url, String[][] headers, int timeoutMs)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Accept", "*/*");
        if (headers != null) {
            for (String[] h : headers) c.setRequestProperty(h[0], h[1]);
        }
        return c;
    }

    /** 收集响应头里的 Set-Cookie（只留 name=value，去掉 Path/Expires 那些属性） */
    private static String readCookies(HttpURLConnection c) {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.Map<String, java.util.List<String>> h = c.getHeaderFields();
            if (h == null) return "";
            for (java.util.Map.Entry<String, java.util.List<String>> en : h.entrySet()) {
                if (en.getKey() == null || !"set-cookie".equalsIgnoreCase(en.getKey())) continue;
                if (en.getValue() == null) continue;
                for (String raw : en.getValue()) {
                    if (raw == null) continue;
                    int semi = raw.indexOf(';');
                    String kv = (semi > 0 ? raw.substring(0, semi) : raw).trim();
                    if (kv.isEmpty()) continue;
                    if (sb.indexOf(kv) >= 0) continue;
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(kv);
                }
            }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    private static String readAll(InputStream raw) throws IOException {
        if (raw == null) return "";
        InputStream in = raw;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }
}
