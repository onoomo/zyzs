package com.downx.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Range 分片并发下载器（单文件 + 按偏移并发写）。
 *
 * 为什么要有它：原来的单连接下载一个 TCP 流跑满一条链路的带宽，长视频（几百兆）
 * 只能干等；同一台机器上 8 条连接往往能把 CDN 的出口用满，实测能快 3~6 倍。
 *
 * 设计取舍（A 方案）：
 *   - **只用一个文件**，每个 worker 各自持一个 RandomAccessFile，按 offset 写自己
 *     那一片。不需要 seg_xxx.part 再合并一次，省掉「合并」这一步的读写与磁盘峰值。
 *   - 续传靠**已完成区间位图**（存成 "0-15,20-31" 这种紧凑串），而不是靠文件长度
 *     —— 并发写会让文件长度不可靠（后面的片先到，长度先涨）。
 *   - 服务器不支持 Range（回 200）、或文件太小（< 4MB）、或只有一片时，**直接放弃
 *     并发**，由调用方退回原来的单连接路径，行为完全不变。
 *
 * 从 YunX（AGPL-3.0）学到并重写的几条，事实性思路可用、代码是按本项目风格重写的：
 *   1. 校验 Content-Range 的起点与总长，对不上就判失败，绝不把错位的数据写进文件；
 *   2. 已写字节必须等于预期字节，写不满就继续要（防「下完打不开」的空洞文件）；
 *   3. 慢连接抢占：持续低速就掐断换连接，保留已写字节、零退避重排；
 *   4. 分片失败不立刻整单失败，先重排给别的连接试，超过次数才放弃并保留进度。
 */
public final class ChunkedFetch {

    /** 宿主回调：暂停/取消检查点、进度上报、状态落盘 */
    public interface Ctl {
        /** 已取消则抛异常；已暂停则原地阻塞（和工作线程共用同一套语义） */
        void gate();

        /** 已下载字节 / 总字节（仅在百分比变化时调） */
        void progress(long done, long total);

        /** 把 Parts.State 落盘（位图、已下字节已经填好） */
        void persist();

        void log(String line);
    }

    public static final class Result {
        /** false = 这条流不适合并发，调用方应退回单连接 */
        public boolean used;
        public long total = -1;
        public String contentType = "";
    }

    /** 并发连接数上限。手机上 8 条已经能把常见 CDN 的出口吃满，再多容易被风控 */
    private static final int MAX_WORKERS = 8;
    /** 小于这个体积不值得并发（连接建立的开销比省下的时间还大） */
    private static final long MIN_TOTAL = 4L * 1024 * 1024;
    private static final int BUF = 64 * 1024;
    /** 慢连接判定：低于 max(12KB/s, 全局平均/2) 且持续 15 秒，就掐掉换一条 */
    private static final long SLOW_MIN_BPS = 12 * 1024;
    private static final long SLOW_HOLD_MS = 15000;
    /** 同一片最多被整体重排几次，超过就判定这条链接真的不行了 */
    private static final int CHUNK_TRIES = 6;
    /** 单片内连续失败（无进展）几次就把这片让给别人 */
    private static final int INNER_FAILS = 3;

    /** 服务器不理解 Range：整单退回单连接 */
    private static final class RangeUnsupported extends RuntimeException {
        RangeUnsupported() { super("range unsupported"); }
    }

    /** 硬错误（403/404/远端文件变了）：不重试，直接抛给外层（可触发备用流） */
    private static final class HardReject extends RuntimeException {
        final Exception ex;

        HardReject(Exception ex) {
            super(ex.getMessage());
            this.ex = ex;
        }
    }

    /**
     * gate() 抛出来的取消/中断。必须和「网络错误」分开：
     * 网络错误可以换连接重试，用户取消却要立刻收手，不能拿 6 次重试去磨。
     */
    private static final class GateStop extends RuntimeException {
        GateStop() { super("gate stop"); }
    }

    private final File out;
    private final String url;
    private final String referer;
    private final Parts.State st;
    private final Ctl ctl;

    private volatile boolean stop;
    private volatile boolean rangeBroken;
    private final List<HttpURLConnection> live =
            Collections.synchronizedList(new ArrayList<HttpURLConnection>());
    private final AtomicReference<Throwable> fatal = new AtomicReference<>();

    private long total;
    private long cs;
    private int chunks;
    private String contentType = "";
    /**
     * 所有 worker 共用一个 FileChannel：定位写（write(buf, pos)）不走共享游标，
     * 天然线程安全。比每个 worker 各开一个 RandomAccessFile 更稳 ——
     * 多线程同时 create 同一个文件在 Windows 上会偶发「拒绝访问」。
     */
    private FileChannel ch;

    private BitSet done;
    private final Object lock = new Object();          // 保护 done / st 的写入 / 位图串
    private AtomicLong doneBytes;
    private AtomicIntegerArray tries;
    private final ConcurrentLinkedQueue<long[]> queue =
            new ConcurrentLinkedQueue<>();              // {片号, 从哪个字节开始}

    private long startMs;
    private long lastPersistMs;
    private long lastProgMs;
    private int lastPct = -1;

    public ChunkedFetch(File out, String url, String referer, Parts.State st, Ctl ctl) {
        this.out = out;
        this.url = url;
        this.referer = referer;
        this.st = st;
        this.ctl = ctl;
    }

    /** 用户取消时调用：掐断所有在途连接，worker 会立刻从读上醒来收尾 */
    public void abort() {
        stop = true;
        synchronized (live) {
            for (HttpURLConnection c : live) {
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    public Result run() throws Exception {
        Result r = new Result();
        if (!probe(r)) {
            // 探测阶段就把旧的分片记录作废：位图和数据只对得上同一个文件，
            // 换了流还留着旧文件只会让单连接路径把空洞当已下完
            discardOld();
            return r;
        }

        prepare();

        startMs = System.currentTimeMillis();
        lastPersistMs = startMs;
        lastProgMs = startMs;

        int workers = Math.min(MAX_WORKERS, chunks);
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        if (heapMb < 96) workers = Math.min(workers, 4);   // 小内存机器少开几条
        if (workers < 1) workers = 1;

        List<Thread> ts = new ArrayList<>(workers);
        RandomAccessFile raf = null;
        try {
            // 先把文件建出来（只有这里会 create，worker 只管往里面写）
            raf = new RandomAccessFile(out, "rw");
            ch = raf.getChannel();
            for (int i = 0; i < workers; i++) {
                Thread t = new Thread(this::work, "cf" + i);
                ts.add(t);
                t.start();
            }
            for (Thread t : ts) t.join();
        } finally {
            ch = null;
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) { }
            }
        }

        ctl.gate();                       // 已取消会从这里把 Canceled 抛出去

        if (rangeBroken) {
            discardOld();
            r.used = false;
            return r;
        }

        Throwable f = fatal.get();
        if (f != null) {
            if (f instanceof RuntimeException) throw (RuntimeException) f;
            if (f instanceof Exception) throw (Exception) f;
            throw new Exception(f);
        }

        int left = chunks - countDone();
        if (left > 0) {
            throw new Exception("还有 " + left + " 片没下完；进度已保存，回主页可以继续");
        }
        long len = out.exists() ? out.length() : 0;
        if (len < total) {
            // 位图说齐了但文件短：说明有片没落盘，宁可报错也不交付半个文件
            throw new Exception("文件长度不对（" + len + "/" + total + "），进度已保存，可以继续");
        }
        if (len > total) Parts.truncate(out, total);

        synchronized (lock) {
            st.vDone = true;
            st.lenV = total;
            st.totalV = total;
            st.cs = cs;
            st.ranges = "";               // 下完了，位图不用留
        }
        ctl.persist();

        r.used = true;
        r.total = total;
        r.contentType = contentType;
        return r;
    }

    // ---------------- 探测 / 规划 ----------------

    /**
     * 用 Range: bytes=0-0 探一下：既拿总长，也确认服务器真的支持分片。
     * 只花 1 个字节的流量，比 HEAD 可靠（不少 CDN 的 HEAD 不给 Content-Length）。
     */
    private boolean probe(Result r) throws Exception {
        HttpURLConnection c = null;
        try {
            c = openConn("bytes=0-0");
            int code = c.getResponseCode();
            contentType = c.getContentType() == null ? "" : c.getContentType();
            r.contentType = contentType;
            if (code == 206) {
                long[] cr = parseContentRange(c.getHeaderField("Content-Range"));
                if (cr == null || cr[0] != 0 || cr[2] <= 0) {
                    ctl.log("分片探测：Content-Range 不完整，退回单连接");
                    return false;
                }
                total = cr[2];
                cs = chunkSizeFor(total);
                chunks = (int) ((total + cs - 1) / cs);
                if (total < MIN_TOTAL || chunks < 2) {
                    ctl.log("分片探测：" + total + " 字节偏小，走单连接");
                    return false;
                }
                r.total = total;
                ctl.log("分片探测：共 " + total + " 字节，" + chunks + " 片 × "
                        + (cs / 1024) + "KB");
                return true;
            }
            if (code == 200) {
                ctl.log("分片探测：服务器忽略 Range，退回单连接");
                return false;
            }
            if (code == 403 || code == 404 || code == 410) {
                throw new Exception("服务器拒绝（HTTP " + code + "），链接可能已过期");
            }
            if (code >= 400) {
                ctl.log("分片探测：HTTP " + code + "，退回单连接");
                return false;
            }
            return false;
        } catch (IOException e) {
            throw new Exception("连接失败：" + e.getMessage());
        } finally {
            close(c);
        }
    }

    private static long chunkSizeFor(long t) {
        if (t <= 24L * 1024 * 1024) return 1L * 1024 * 1024;
        if (t <= 192L * 1024 * 1024) return 2L * 1024 * 1024;
        return 4L * 1024 * 1024;
    }

    /** 续传判断：分片粒度与总长都对得上才复用旧位图，否则当作换了流 */
    private void prepare() {
        done = new BitSet(chunks);
        tries = new AtomicIntegerArray(chunks);
        doneBytes = new AtomicLong(0);

        boolean sameGrid = st.totalV == total && (st.cs == cs || st.cs == 0);
        boolean hasMap = st.ranges != null && !st.ranges.isEmpty();

        if (sameGrid && hasMap) {
            parseRanges(st.ranges);
            ctl.log("续传：已有 " + countDone() + "/" + chunks + " 片");
        } else if (sameGrid) {
            // 同一股流但没有位图（上一版是单连接，或上次在头一片中途挂掉）：
            // 旧数据一定是从 0 连续写下来的，把整片的前缀保留，半个片丢掉重下
            long l = out.exists() ? out.length() : 0;
            if (l > total) l = total;
            long keep = (l / cs) * cs;
            if (keep > 0) {
                for (int i = 0; i < keep / cs; i++) done.set(i);
                Parts.truncate(out, keep);
                ctl.log("续传：按已有文件保留前 " + (keep / cs) + " 片");
            } else if (l > 0) {
                Parts.truncate(out, 0);
            }
        } else {
            Parts.truncate(out, 0);
            ctl.log("换了流或分片粒度变化，旧数据作废重下");
        }

        long d = 0;
        for (int i = done.nextSetBit(0); i >= 0; i = done.nextSetBit(i + 1)) d += lenOf(i);
        doneBytes.set(d);

        synchronized (lock) {
            st.lenV = d;
            st.totalV = total;
            st.cs = cs;
            st.ranges = rle();
        }
        ctl.persist();

        for (int i = 0; i < chunks; i++) {
            if (!done.get(i)) queue.add(new long[]{i, offOf(i)});
        }
    }

    /** 探测失败时把旧的分片痕迹清掉，免得单连接路径把带空洞的文件当续传起点 */
    private void discardOld() {
        if (st.cs <= 0 && (st.ranges == null || st.ranges.isEmpty())) return;
        Parts.truncate(out, 0);
        synchronized (lock) {
            st.cs = 0;
            st.ranges = "";
            st.lenV = 0;
            st.totalV = -1;
        }
        ctl.persist();
    }

    // ---------------- worker ----------------

    private void work() {
        while (!stop) {
            long[] seg = queue.poll();
            if (seg == null) return;
            int idx = (int) seg[0];
            if (isDone(idx)) continue;
            long[] pos = {Math.max(seg[1], offOf(idx))};
            try {
                fetchChunk(idx, pos);
                markDone(idx);
            } catch (RangeUnsupported ru) {
                rangeBroken = true;
                stop = true;
                return;
            } catch (GateStop gs) {
                return;                       // fatal 里已经存了原始异常（Canceled）
            } catch (HardReject hr) {
                fatal.compareAndSet(null, hr.ex);
                stop = true;
                return;
            } catch (Throwable t) {
                if (stop) return;
                int n = tries.incrementAndGet(idx);
                ctl.log("第 " + (idx + 1) + " 片失败（第 " + n + " 次）：" + t.getMessage());
                if (n >= CHUNK_TRIES) {
                    fatal.compareAndSet(null, t);
                    stop = true;
                    return;
                }
                // 从已经写到的位置接着要，不把这一片已经下的部分扔掉
                queue.add(new long[]{idx, pos[0]});
            }
        }
    }

    /**
     * 下一片：按 64KB 读、按偏移定位写、逐块对账。
     * 一次调用会把 [from, 片尾] 全部写完才返回。
     */
    private void fetchChunk(int idx, long[] posRef) throws Exception {
        long end = endOf(idx);
        long pos = posRef[0];
        int fails = 0;

        while (pos <= end) {
            if (stop) throw new IOException("aborted");
            gateHard();

            HttpURLConnection c = null;
            long before = pos;
            boolean preempted = false;
            try {
                c = openConn("bytes=" + pos + "-" + end);
                int code = c.getResponseCode();
                if (code == 200) throw new RangeUnsupported();      // 整文件返回：不支持分片
                if (code == 416) throw new HardReject(new Exception("服务器不接受分片请求（416）"));
                if (code == 403 || code == 404 || code == 410) {
                    throw new HardReject(new Exception(
                            "服务器拒绝（HTTP " + code + "），链接可能已过期"));
                }
                if (code >= 400) throw new IOException("HTTP " + code);

                long[] cr = parseContentRange(c.getHeaderField("Content-Range"));
                if (cr == null) throw new RangeUnsupported();
                if (cr[0] != pos) {
                    throw new IOException("Content-Range 起点对不上（" + cr[0] + "≠" + pos + "）");
                }
                if (cr[2] > 0 && cr[2] != total) {
                    throw new HardReject(new Exception(
                            "远端文件变了（" + cr[2] + "≠" + total + "），旧进度作废"));
                }

                InputStream in = c.getInputStream();
                byte[] buf = new byte[BUF];
                long winStart = System.currentTimeMillis();
                long winBytes = 0;
                long slowSince = 0;
                try {
                    while (pos <= end) {
                        int want = (int) Math.min(BUF, end - pos + 1);
                        int n = in.read(buf, 0, want);
                        if (n < 0) break;                            // 提前收尾：外层会接着要
                        writeAt(buf, n, pos);
                        pos += n;
                        posRef[0] = pos;
                        tick(n);

                        winBytes += n;
                        long now = System.currentTimeMillis();
                        if (now - winStart >= 1000) {
                            long bps = winBytes * 1000 / Math.max(1, now - winStart);
                            if (bps < slowBps()) {
                                if (slowSince == 0) {
                                    slowSince = now;
                                } else if (now - slowSince >= SLOW_HOLD_MS) {
                                    preempted = true;
                                    ctl.log("第 " + (idx + 1) + " 片太慢（"
                                            + (bps / 1024) + "KB/s），换连接");
                                    throw new IOException("slow connection");
                                }
                            } else {
                                slowSince = 0;
                            }
                            winStart = now;
                            winBytes = 0;
                        }
                    }
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                }
                if (pos > before) fails = 0;
            } catch (RangeUnsupported | HardReject e) {
                throw e;
            } catch (Exception e) {
                if (stop) throw e;
                fails++;
                if (fails >= INNER_FAILS) throw e;
                // 抢占换连接不退避（退了反而空等）；真出错才稍等
                if (!preempted) {
                    try { Thread.sleep(300L * fails); } catch (InterruptedException ignored) { }
                }
            } finally {
                close(c);
            }
        }
    }

    // ---------------- 字节账 / 进度 / 位图 ----------------

    private void tick(long n) {
        long d = doneBytes.addAndGet(n);
        if (total <= 0) return;
        long now = System.currentTimeMillis();
        int p = (int) (d * 92 / total);
        if (p != lastPct && now - lastProgMs >= 200) {
            lastPct = p;
            lastProgMs = now;
            ctl.progress(d, total);
        }
        if (now - lastPersistMs >= 1500) {
            lastPersistMs = now;
            saveState(d);
        }
    }

    private void markDone(int idx) {
        synchronized (lock) {
            done.set(idx);
            st.lenV = countBytes();
            st.totalV = total;
            st.cs = cs;
            st.ranges = rle();
            lastPersistMs = System.currentTimeMillis();
            // 落盘也要在锁里：Parts.save 是「写 tmp 再改名」，两个 worker 同时写会互相踩
            ctl.persist();
        }
        // 这里不报进度：d 只算「完整片」，比 doneBytes（含正在写的那片）小，
        // 拿它上报会让进度条倒退一两格。进度统一由 tick() 单调上报。
    }

    private void saveState(long d) {
        synchronized (lock) {
            st.lenV = d;
            st.totalV = total;
            st.cs = cs;
            st.ranges = rle();
            ctl.persist();
        }
    }

    /** 已经下完的字节（只算完整片，不算正在写的那片，避免进度来回跳） */
    private long countBytes() {
        long d = 0;
        for (int i = done.nextSetBit(0); i >= 0; i = done.nextSetBit(i + 1)) d += lenOf(i);
        return d;
    }

    private int countDone() {
        synchronized (lock) {
            return done.cardinality();
        }
    }

    private boolean isDone(int idx) {
        synchronized (lock) {
            return done.get(idx);
        }
    }

    /** "0-15,20-31" ←→ 位图；只存连续段，几百片的位图也就几十个字符 */
    private String rle() {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < chunks) {
            if (!done.get(i)) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < chunks && done.get(j + 1)) j++;
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
            if (j > i) sb.append('-').append(j);
            i = j + 1;
        }
        return sb.toString();
    }

    private void parseRanges(String s) {
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int dash = p.indexOf('-');
            try {
                if (dash < 0) {
                    int i = Integer.parseInt(p);
                    if (i >= 0 && i < chunks) done.set(i);
                } else {
                    int a = Integer.parseInt(p.substring(0, dash));
                    int b = Integer.parseInt(p.substring(dash + 1));
                    if (a < 0) a = 0;
                    if (b >= chunks) b = chunks - 1;
                    if (a <= b) done.set(a, b + 1);
                }
            } catch (Throwable ignored) { }
        }
    }

    private long slowBps() {
        long g = doneBytes.get() * 1000 / Math.max(1, System.currentTimeMillis() - startMs);
        return Math.max(SLOW_MIN_BPS, g / 2);
    }

    private long offOf(int idx) {
        return (long) idx * cs;
    }

    private long endOf(int idx) {
        long e = offOf(idx) + cs - 1;
        return Math.min(e, total - 1);
    }

    private long lenOf(int idx) {
        return endOf(idx) - offOf(idx) + 1;
    }

    // ---------------- HTTP ----------------

    /** 取消/暂停检查点：宿主抛的异常要原样存下来，最后交给 run() 抛回去 */
    private void gateHard() {
        try {
            ctl.gate();
        } catch (RuntimeException e) {
            fatal.compareAndSet(null, e);
            stop = true;
            throw new GateStop();
        }
    }

    /** 定位写：一定把 n 个字节全部写进去，写不满就继续写，防半截数据 */    private void writeAt(byte[] buf, int n, long pos) throws IOException {
        FileChannel c = ch;
        if (c == null) throw new IOException("文件已关闭");
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
        long p = pos;
        while (bb.hasRemaining()) {
            int k = c.write(bb, p);
            if (k <= 0) throw new IOException("写入文件失败（磁盘满了？）");
            p += k;
        }
    }

    private HttpURLConnection openConn(String range) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", Net.UA);
        c.setRequestProperty("Accept", "*/*");
        // 分片是按原始字节算的，被压缩过就全错了，必须显式禁掉
        c.setRequestProperty("Accept-Encoding", "identity");
        if (referer != null && !referer.isEmpty()) {
            c.setRequestProperty("Referer", referer);
        }
        if (range != null) c.setRequestProperty("Range", range);
        live.add(c);
        return c;
    }

    private void close(HttpURLConnection c) {
        if (c == null) return;
        live.remove(c);
        try { c.disconnect(); } catch (Throwable ignored) { }
    }

    /** "bytes 0-1048575/12345678" → {start, end, total}；total 是 * 时给 -1 */
    private static long[] parseContentRange(String v) {
        if (v == null) return null;
        int sp = v.indexOf(' ');
        if (sp < 0) return null;
        String rest = v.substring(sp + 1).trim();
        int slash = rest.indexOf('/');
        if (slash < 0) return null;
        String range = rest.substring(0, slash).trim();
        String tot = rest.substring(slash + 1).trim();
        int dash = range.indexOf('-');
        if (dash < 0) return null;
        try {
            long a = Long.parseLong(range.substring(0, dash).trim());
            long b = Long.parseLong(range.substring(dash + 1).trim());
            long t = "*".equals(tot) ? -1 : Long.parseLong(tot);
            return new long[]{a, b, t};
        } catch (Throwable t) {
            return null;
        }
    }
}
