package com.downx.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载过程中的临时数据与「下到哪儿了」的状态。
 *
 * 注意：**跨次续传已关闭**（用户明确要求）。现在这些文件只服务于
 * 「同一次下载运行内」的重试：
 *
 *   state.txt   键值对状态，先写 tmp 再改名，保证崩溃时不会写到一半
 *   data_v      视频流数据（单流 / HLS 分片拼接 / YouTube 视频轨）
 *   data_a      音轨数据（YouTube 分离音轨时才有）
 *
 * 规则：
 *   - 每次开始下载前先清掉同任务残留（DlService 里 Parts.clear）——
 *     两次下载的字节绝不会拼在一个文件里；
 *   - 下载成功、失败、用户取消，都会把整个任务目录删掉，
 *     不留半成品占空间（老版本留下的残留由 Parts.clearAll 启动时清）；
 *   - 同一次运行里断流，仍可用 Range/分片序号接着写，不用从头拉。
 */
public class Parts {

    public static final class State {
        public String id = "";
        public String name = "", kind = "video", thumb = "", page = "", sub = "";
        public String key = "", referer = "", pick = "", alt = "", altName = "";
        public String platform = "", label = "";
        public boolean visible = true;
        /** HLS 清单指纹：分片数 + 首尾分片路径，判断续传时拿到的是不是同一条流 */
        public String sig = "";
        /** 每个分片的字节数（逗号分隔）：重封装要按分片边界逐段抽，否则接缝处会断 */
        public String offs = "";
        public int segV, segA;            // 视频 / 音轨已完成分片数
        public int segCountV;             // 视频总分片数（算百分比用）
        public long lenV, lenA;           // 视频 / 音轨已写入字节
        public long totalV = -1, totalA = -1;
        public boolean vDone, aDone;
        /**
         * 分片并发下载的已完成区间位图，形如 "0-15,20-31"。
         * 并发写会让文件长度失去意义（后面的片可能先到），所以续传只能靠位图。
         */
        public String ranges = "";
        /** 分片粒度：换了粒度位图就对不上，必须重下 */
        public long cs;
        public long ts;

        /** 大致完成度，只用于列表展示；算不出来返回 -1 */
        public int pct() {
            if (segCountV > 0 && totalA <= 0) return Math.min(99, segV * 100 / segCountV);
            if (totalV > 0 && (totalA <= 0 || aDone)) return Math.min(99, (int) (lenV * 100 / totalV));
            if (totalV > 0 && totalA > 0) {
                return Math.min(99, (int) ((lenV + lenA) * 100 / (totalV + totalA)));
            }
            return -1;
        }
    }

    // ---------------- 路径 ----------------

    private static File root(Context c) {
        File base = c.getExternalCacheDir();
        if (base == null) base = c.getCacheDir();
        return new File(base, "parts");
    }

    private static File dirOf(Context c, String id) {
        File d = new File(root(c), id == null || id.isEmpty() ? "unknown" : id);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 某条任务某个流的数据文件；stream = "v" / "a" */
    public static File dataFile(Context c, String id, String stream) {
        return new File(dirOf(c, id), "data_" + stream);
    }

    /** 任务 id：同一条作品 + 同一档画质视为同一条下载 */
    public static String idOf(String key, String pick) {
        return md5((key == null ? "" : key) + "|" + (pick == null ? "" : pick));
    }

    // ---------------- 读写 ----------------

    public static void save(Context c, State s) {
        try {
            File d = dirOf(c, s.id);
            StringBuilder sb = new StringBuilder();
            line(sb, "id", s.id);
            line(sb, "name", s.name);
            line(sb, "kind", s.kind);
            line(sb, "thumb", s.thumb);
            line(sb, "page", s.page);
            line(sb, "sub", s.sub);
            line(sb, "key", s.key);
            line(sb, "referer", s.referer);
            line(sb, "pick", s.pick);
            line(sb, "alt", s.alt);
            line(sb, "altName", s.altName);
            line(sb, "platform", s.platform);
            line(sb, "label", s.label);
            line(sb, "visible", String.valueOf(s.visible));
            line(sb, "sig", s.sig);
            line(sb, "offs", s.offs);
            line(sb, "segV", String.valueOf(s.segV));
            line(sb, "segA", String.valueOf(s.segA));
            line(sb, "segCountV", String.valueOf(s.segCountV));
            line(sb, "lenV", String.valueOf(s.lenV));
            line(sb, "lenA", String.valueOf(s.lenA));
            line(sb, "totalV", String.valueOf(s.totalV));
            line(sb, "totalA", String.valueOf(s.totalA));
            line(sb, "vDone", String.valueOf(s.vDone));
            line(sb, "aDone", String.valueOf(s.aDone));
            line(sb, "ranges", s.ranges);
            line(sb, "cs", String.valueOf(s.cs));
            line(sb, "ts", String.valueOf(s.ts));
            File tmp = new File(d, "state.tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(sb.toString().getBytes("UTF-8"));
            }
            // 改名是原子的：崩溃时 state.txt 要么是上一版要么是这一版，不会是半个
            File out = new File(d, "state.txt");
            if (out.exists()) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            if (!tmp.renameTo(out)) {
                // 极少见：改不了名就直接覆盖写
                try (FileOutputStream os = new FileOutputStream(out)) {
                    os.write(sb.toString().getBytes("UTF-8"));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) { }
    }

    public static State load(Context c, String id) {
        try {
            File f = new File(dirOf(c, id), "state.txt");
            if (!f.exists() || f.length() == 0) return null;
            byte[] b = new byte[(int) Math.min(f.length(), 65536)];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(b);
                if (n <= 0) return null;
            }
            String txt = new String(b, "UTF-8");
            State s = new State();
            for (String l : txt.split("\n")) {
                int i = l.indexOf('=');
                if (i <= 0) continue;
                String k = l.substring(0, i).trim();
                String v = l.substring(i + 1).trim();
                switch (k) {
                    case "id": s.id = v; break;
                    case "name": s.name = v; break;
                    case "kind": s.kind = v; break;
                    case "thumb": s.thumb = v; break;
                    case "page": s.page = v; break;
                    case "sub": s.sub = v; break;
                    case "key": s.key = v; break;
                    case "referer": s.referer = v; break;
                    case "pick": s.pick = v; break;
                    case "alt": s.alt = v; break;
                    case "altName": s.altName = v; break;
                    case "platform": s.platform = v; break;
                    case "label": s.label = v; break;
                    case "visible": s.visible = "true".equals(v); break;
                    case "sig": s.sig = v; break;
                    case "offs": s.offs = v; break;
                    case "segV": s.segV = i2(v); break;
                    case "segA": s.segA = i2(v); break;
                    case "segCountV": s.segCountV = i2(v); break;
                    case "lenV": s.lenV = l2(v); break;
                    case "lenA": s.lenA = l2(v); break;
                    case "totalV": s.totalV = l2(v); break;
                    case "totalA": s.totalA = l2(v); break;
                    case "vDone": s.vDone = "true".equals(v); break;
                    case "aDone": s.aDone = "true".equals(v); break;
                    case "ranges": s.ranges = v; break;
                    case "cs": s.cs = l2(v); break;
                    case "ts": s.ts = l2(v); break;
                }
            }
            if (s.id.isEmpty()) s.id = id;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 还没下完的任务列表（按时间倒序，最新的在前） */
    public static List<State> pending(Context c) {
        List<State> out = new ArrayList<>();
        try {
            File[] ds = root(c).listFiles();
            if (ds == null) return out;
            for (File d : ds) {
                State s = load(c, d.getName());
                if (s != null) out.add(s);
            }
            out.sort((a, b) -> Long.compare(b.ts, a.ts));
        } catch (Throwable ignored) { }
        return out;
    }

    /** 下载成功或用户取消后，整个任务目录清掉 */
    public static void clear(Context c, String id) {
        try {
            File d = dirOf(c, id);
            File[] fs = d.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            d.delete();
        } catch (Throwable ignored) { }
    }

    /**
     * 一次性清掉所有未完成任务的目录。
     * App 已取消断点续传：这些半成品不再有人认领，只会白占空间
     * （用户吐槽过"占内存、忘了是哪个链接还找不到"）。
     */
    public static void clearAll(Context c) {
        try {
            File r = root(c);
            File[] ds = r.listFiles();
            if (ds == null) return;
            for (File d : ds) {
                File[] fs = d.listFiles();
                if (fs != null) {
                    for (File f : fs) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                d.delete();
            }
        } catch (Throwable ignored) { }
    }

    /** 把文件截到指定长度：崩溃时尾部可能多写了不完整的分片，得掐掉 */
    public static void truncate(File f, long len) {
        try {
            if (!f.exists()) return;
            if (f.length() == len) return;
            RandomAccessFile r = new RandomAccessFile(f, "rw");
            r.setLength(Math.max(0, len));
            r.close();
        } catch (Throwable ignored) { }
    }

    // ---------------- 小工具 ----------------

    private static void line(StringBuilder sb, String k, String v) {
        if (v == null) v = "";
        sb.append(k).append('=').append(v.replace('\n', ' ').replace('\r', ' '))
                .append('\n');
    }

    private static int i2(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long l2(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bs = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bs) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(s.hashCode());
        }
    }
}
