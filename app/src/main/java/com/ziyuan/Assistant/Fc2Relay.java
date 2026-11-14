package com.ziyuan.Assistant;

import java.io.OutputStream;
import java.net.Socket;
import java.security.cert.X509Certificate;

/**
 * FC2 系直播控制信令客户端：连 wss 控制通道，
 * connect_complete 后发 get_hls_information 拿 HLS m3u8 地址（挑 mode 最高的画质）。
 * HLS 地址交给嗅探/播放/录制现有管线。心跳 25s 保活。
 */
public class Fc2Relay {

    private static volatile String wsUrl = null;
    private static volatile boolean running = false;
    private static volatile boolean connected = false;
    private static volatile String hlsUrl = null;
    private static volatile String debugInfo = "";
    private static volatile String lastLog = "";
    private static volatile int retry = 0;
    private static volatile String cookie = null;
    private static int msgId = 0;
    private static volatile long lastRecv = 0;

    public static boolean isConnected() { return connected; }
    public static String getHls() { return hlsUrl; }
    public static String debugInfo() { return debugInfo; }
    public static String lastLog() { return lastLog; }

    public static synchronized void start(String url) { start(url, null); }

    public static synchronized void start(String url, String ck) {
        if (url == null || (!url.startsWith("ws://") && !url.startsWith("wss://"))) return;
        // 同一频道的连接还在跑就不重启（页面会频繁重建连接，不能跟着清零）
        if (running && isConnected()) {
            if (wsUrl != null && sameChannel(wsUrl, url)) return;
        }
        String keepHls = hlsUrl;
        stop();
        wsUrl = url;
        cookie = ck;
        retry = 0;
        hlsUrl = keepHls;  // 粘住已取得的 HLS
        running = true;
        new Thread(new Runnable() { public void run() { loop(); } }).start();
    }

    private static boolean sameChannel(String a, String b) {
        int ia = a.indexOf("/control/channels/");
        int ib = b.indexOf("/control/channels/");
        if (ia < 0 || ib < 0) return a.equals(b);
        int ja = a.indexOf('?', ia), jb = b.indexOf('?', ib);
        String ca = a.substring(ia, ja > 0 ? ja : a.length());
        String cb = b.substring(ib, jb > 0 ? jb : b.length());
        return ca.equals(cb);
    }

    public static synchronized void stop() {
        running = false;
        connected = false;
    }

    private static void loop() {
        while (running && retry < 8) {
            try { wsRun(wsUrl); } catch (Throwable ignored) {}
            if (!running) break;
            connected = false;
            retry++;
            try { Thread.sleep(2000); } catch (Throwable e) { break; }
        }
        connected = false;
    }

    private static void wsRun(String url) throws Exception {
        boolean ssl = url.startsWith("wss://");
        String rest = url.substring(ssl ? 6 : 5);
        int pi = rest.indexOf('/');
        String host = rest;
        String path = "/";
        if (pi >= 0) { host = rest.substring(0, pi); path = rest.substring(pi); }
        String hostOnly = host;
        int port = ssl ? 443 : 80;
        int hi = host.indexOf(':');
        if (hi >= 0) { port = Integer.parseInt(host.substring(hi + 1)); hostOnly = host.substring(0, hi); }

        Socket sk = ssl ? trustAll().createSocket(hostOnly, port) : new Socket(hostOnly, port);
        java.io.InputStream in = sk.getInputStream();
        OutputStream out = sk.getOutputStream();

        String key = java.util.Base64.getEncoder().encodeToString(rand16());
        String req = "GET " + path + " HTTP/1.1\r\n"
            + "Host: " + host + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "Origin: https://" + hostOnly + "\r\n"
            + "User-Agent: Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile\r\n";
        if (cookie != null && cookie.length() > 0) req += "Cookie: " + cookie + "\r\n";
        req += "\r\n";
        out.write(req.getBytes());
        out.flush();

        String line;
        boolean ok = false;
        while ((line = readLine(in)) != null) {
            if (line.startsWith("HTTP/1.1 101") || line.startsWith("HTTP/1.0 101")) ok = true;
            if (line.isEmpty()) break;
        }
        if (!ok) { sk.close(); throw new Exception("handshake fail"); }

        connected = true;
        retry = 0;
        lastRecv = System.currentTimeMillis();

        byte[] hdr = new byte[2];
        java.io.ByteArrayOutputStream txtAcc = new java.io.ByteArrayOutputStream();
        boolean ready = false;
        boolean gotPlaylists = false;
        long lastHb = System.currentTimeMillis();
        long lastHeartbeatRecv = System.currentTimeMillis();

        while (running) {
            // 读一帧
            readFull(in, hdr, 0, 2);
            boolean fin = (hdr[0] & 0x80) != 0;
            int op = hdr[0] & 0x0F;
            boolean masked = (hdr[1] & 0x80) != 0;
            long len = hdr[1] & 0x7F;
            if (len == 126) { byte[] e2 = new byte[2]; readFull(in, e2, 0, 2); len = ((e2[0] & 0xFF) << 8) | (e2[1] & 0xFF); }
            else if (len == 127) { byte[] e8 = new byte[8]; readFull(in, e8, 0, 8); len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (e8[i] & 0xFF); }
            byte[] mask = masked ? new byte[4] : null;
            if (masked) readFull(in, mask, 0, 4);
            byte[] payload = new byte[(int) len];
            if (len > 0) readFull(in, payload, 0, (int) len);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            lastRecv = System.currentTimeMillis();

            if (op == 0x9) { out.write(pong(payload)); out.flush(); continue; }
            if (op == 0x8) { sk.close(); throw new Exception("close frame"); }
            boolean isJson = (op == 0x1 || op == 0x0) || (payload.length > 0 && payload[0] == '{');
            if (isJson) {
                txtAcc.write(payload, 0, payload.length);
                if (!fin) continue;
                String msg = txtAcc.toString("UTF-8");
                txtAcc.reset();
                lastLog = ("< " + (msg.length() > 80 ? msg.substring(0, 80) : msg)) + "\n" + lastLog;
                if (lastLog.length() > 1500) lastLog = lastLog.substring(0, 1500);
                boolean[] gp = {gotPlaylists};
                handleMessage(msg, out, gp);
                gotPlaylists = gp[0];

                // 就绪后每5秒重发 get_hls_information，直到拿到列表（官方实现同款重试）
                if (msg.contains("connect_complete") || msg.contains("initial_connect")) {
                    ready = true;
                    lastHb = System.currentTimeMillis();  // v146 成功时序:就绪后约5秒首请求
                }
                if (ready && !gotPlaylists && System.currentTimeMillis() - lastHb > 5000) {
                    sendText(out, msg("get_hls_information"));
                    lastHb = System.currentTimeMillis();
                    lastLog = "> get_hls_information @" + System.currentTimeMillis() % 100000 + "\n" + lastLog;
                }
            }

            // 心跳：25s 没发就发 heartbeat
            if (System.currentTimeMillis() - lastHb > 25000) {
                sendText(out, msg("heartbeat"));
                lastHb = System.currentTimeMillis();
            }
            // 90s 无任何数据则断开重连
            if (System.currentTimeMillis() - lastRecv > 90000 && System.currentTimeMillis() - lastHeartbeatRecv > 90000) {
                sk.close(); throw new Exception("stale");
            }
        }
        sk.close();
    }

    private static void handleMessage(String msg, OutputStream out, boolean[] gotFlag) {
        try {
            if (msg.contains("playlists") && msg.contains("url")) {
                gotFlag[0] = true;
                // _response_ 携带 HLS 信息：挑 mode 最高（>=90 减 90 比较）
                String best = null; int bestMode = -1;
                java.util.regex.Matcher mu = java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(msg);
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\"mode\"\\s*:\\s*(\\d+)").matcher(msg);
                java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
                java.util.ArrayList<Integer> modes = new java.util.ArrayList<Integer>();
                while (mu.find()) urls.add(mu.group(1).replace("\\/", "/"));
                while (mm.find()) modes.add(Integer.parseInt(mm.group(1)));
                for (int i = 0; i < urls.size() && i < modes.size(); i++) {
                    int m = modes.get(i);
                    int cmp = m >= 90 ? m - 90 : m;
                    if (cmp > bestMode) { bestMode = cmp; best = urls.get(i); }
                }
                if (best != null) hlsUrl = best;
                debugInfo = "playlists=" + urls.size();
            } else if (msg.contains("\"name\"")) {
                debugInfo = msg.length() > 90 ? msg.substring(0, 90) : msg;
            }
        } catch (Throwable ignored) {}
    }

    private static String msg(String name) {
        return "{\"name\":\"" + name + "\",\"arguments\":{},\"id\":" + (++msgId) + "}";
    }

    private static void sendText(OutputStream out, String text) throws Exception {
        byte[] pl = text.getBytes("UTF-8");
        byte[] mask = rand16();
        java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
        f.write(0x81);
        if (pl.length < 126) {
            f.write(0x80 | pl.length);
        } else if (pl.length < 65536) {
            f.write(0x80 | 126);
            f.write((pl.length >> 8) & 0xFF); f.write(pl.length & 0xFF);
        } else {
            f.write(0x80 | 127);
            long l = pl.length;
            for (int i = 7; i >= 0; i--) f.write((int) ((l >> (8 * i)) & 0xFF));
        }
        f.write(mask);
        for (int i = 0; i < pl.length; i++) f.write(pl[i] ^ mask[i % 4]);
        out.write(f.toByteArray());
        out.flush();
    }

    private static byte[] pong(byte[] payload) {
        int pl = Math.min(payload == null ? 0 : payload.length, 125);
        byte[] mask = rand16();
        byte[] out = new byte[2 + 4 + pl];
        out[0] = (byte) 0x8A;
        out[1] = (byte) (0x80 | pl);
        System.arraycopy(mask, 0, out, 2, 4);
        for (int i = 0; i < pl; i++) out[6 + i] = (byte) (payload[i] ^ mask[i % 4]);
        return out;
    }

    private static byte[] rand16() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    private static String readLine(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int c = -1;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (b.size() == 0 && c < 0) return null;
        return b.toString();
    }

    private static void readFull(java.io.InputStream in, byte[] b, int off, int len) throws Exception {
        int got = 0;
        while (got < len) {
            int n = in.read(b, off + got, len - got);
            if (n < 0) throw new Exception("eof");
            got += n;
        }
    }

    private static javax.net.ssl.SSLSocketFactory trustAll() throws Exception {
        javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[]{
            new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
        sc.init(null, tm, new java.security.SecureRandom());
        return sc.getSocketFactory();
    }
}
