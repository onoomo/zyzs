package com.downx.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 把「纯视频轨 + 纯音频轨」合成一个 mp4。
 *
 * YouTube 的 1080P 只有 DASH 分离流，官方 App 也是下载两条再实时合流。
 * 这里用 MediaExtractor 原样搬 sample 进 MediaMuxer —— 不解码、不重编码，
 * 所以几秒钟就能合完，画质一点不掉，音量也原样保留。
 */
public class Mux {

    private static final int BUF = 4 * 1024 * 1024;

    public interface Progress {
        void pct(int p);
    }

    public static void merge(File video, File audio, File out, Progress cb) throws Exception {
        MediaExtractor vEx = new MediaExtractor();
        MediaExtractor aEx = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            vEx.setDataSource(video.getAbsolutePath());
            int vT = firstTrack(vEx, "video/");
            if (vT < 0) throw new Exception("视频文件里没有画面轨道");
            MediaFormat vFmt = vEx.getTrackFormat(vT);

            aEx.setDataSource(audio.getAbsolutePath());
            int aT = firstTrack(aEx, "audio/");
            if (aT < 0) throw new Exception("音频文件里没有声音轨道");
            MediaFormat aFmt = aEx.getTrackFormat(aT);

            long vDur = vFmt.containsKey(MediaFormat.KEY_DURATION)
                    ? vFmt.getLong(MediaFormat.KEY_DURATION) : 0;

            if (out.exists()) out.delete();
            mux = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int vOut = mux.addTrack(vFmt);
            int aOut = mux.addTrack(aFmt);

            // 竖屏视频在 mp4 里靠旋转标记表达，不解码就得原样带过去
            try {
                if (vFmt.containsKey("rotation-degrees")) {
                    int deg = vFmt.getInteger("rotation-degrees");
                    if (deg != 0) mux.setOrientationHint(deg);
                }
            } catch (Throwable ignored) { }

            // 两条流各自的起始时间可能不同，用同一个基准挪到 0，
            // 两条之间的相对差保持不变，音画就不会错位。
            vEx.selectTrack(vT);
            aEx.selectTrack(aT);
            long v0 = vEx.getSampleTime();
            long a0 = aEx.getSampleTime();
            long base = Math.min(v0 < 0 ? 0 : v0, a0 < 0 ? 0 : a0);

            mux.start();

            copy(vEx, vOut, mux, -base, vDur, cb, 0, 82);
            copy(aEx, aOut, mux, -base, vDur, cb, 82, 99);

            mux.stop();
        } finally {
            try { if (mux != null) mux.release(); } catch (Throwable ignored) { }
            try { vEx.release(); } catch (Throwable ignored) { }
            try { aEx.release(); } catch (Throwable ignored) { }
        }
    }

    public static void remux(File in, File out, Progress cb) throws Exception {
        remux(in, null, out, cb);
    }

    /**
     * 单个文件内重封装：把 HLS 拼出来的 .ts（MPEG-TS）转成相册能认的 mp4。
     *
     * 两个坑：
     *  ① 系统抽取器对 TS 里的 AAC 会丢一半以上的帧（实测 627 秒只剩 260 秒），
     *     所以音频自己按 PES/ADTS 抽帧、按 1024 采样重排时间轴；
     *  ② 几十个分片拼成的 TS，分片接缝处时间戳会跳变，MediaExtractor 一遇到跳变
     *     就判 EOS 收工 —— 71MB 只转出 9MB，成片"只有声音没有画面"。
     *     所以视频按分片逐段抽取（setDataSource(fd, offset, length)），
     *     每段时间轴重新排队后接在上一段后面，整条片子才完整。
     *
     * segLens：每个分片的字节数，null 表示当单个文件处理（老路径，兼容）。
     */
    public static void remux(File in, long[] segLens, File out, Progress cb) throws Exception {
        File aacTmp = new File(in.getParentFile(), in.getName() + ".aac");
        Aac aac = null;
        try {
            try {
                aac = parseTsAac(in, aacTmp);
            } catch (Throwable t) {
                aac = null;
            }

            MediaMuxer mux = null;
            java.io.FileInputStream fis = null;
            try {
                if (out.exists()) out.delete();
                mux = new MediaMuxer(out.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                fis = new java.io.FileInputStream(in);
                java.io.FileDescriptor fd = fis.getFD();

                // 分片字节区间：没有就整文件当一段
                long[] lens = segLens;
                if (lens == null || lens.length == 0) lens = new long[]{in.length()};

                // 先用第一段拿到视频 format（SPS/PPS 都在里面）
                MediaExtractor first = new MediaExtractor();
                MediaFormat vFmt = null;
                int firstTrackIdx = -1;
                try {
                    first.setDataSource(fd, 0, lens[0]);
                    firstTrackIdx = firstTrack(first, "video/");
                    if (firstTrackIdx >= 0) vFmt = first.getTrackFormat(firstTrackIdx);
                } catch (Throwable ignored) {
                } finally {
                    try { first.release(); } catch (Throwable ignored) { }
                }
                if (vFmt == null && (aac == null || aac.frames == 0)) {
                    throw new Exception("分片里没解析出音视频轨道");
                }

                int vOut = -1;
                if (vFmt != null) {
                    vOut = mux.addTrack(vFmt);
                    // 竖屏靠旋转标记表达
                    try {
                        if (vFmt.containsKey("rotation-degrees")) {
                            int deg = vFmt.getInteger("rotation-degrees");
                            if (deg != 0) mux.setOrientationHint(deg);
                        }
                    } catch (Throwable ignored) { }
                }

                int aOut = -1;
                if (aac != null && aac.frames > 0) {
                    MediaFormat af = MediaFormat.createAudioFormat(
                            MediaFormat.MIMETYPE_AUDIO_AAC, aac.sampleRate, aac.channels);
                    // AudioSpecificConfig：5bit AOT + 4bit 采样率索引 + 4bit 声道数
                    int srIdx = srIndex(aac.sampleRate);
                    int asc = ((aac.profile + 1) << 11) | (Math.max(0, srIdx) << 7)
                            | ((aac.channels & 0x0F) << 3);
                    af.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{
                            (byte) ((asc >> 8) & 0xFF), (byte) (asc & 0xFF)}));
                    aOut = mux.addTrack(af);
                }

                mux.start();

                // ① 视频：逐段抽取，时间轴首尾相接
                if (vOut >= 0) {
                    long off = 0;
                    long gBase = 0;
                    ByteBuffer vBuf = ByteBuffer.allocateDirect(BUF);
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    int lastPct = -1;
                    long totalBytes = in.length();
                    long doneBytes = 0;
                    for (int s = 0; s < lens.length; s++) {
                        if (cb != null) {
                            int p = (int) Math.min(99, doneBytes * 100 / Math.max(1, totalBytes));
                            if (p != lastPct) {
                                lastPct = p;
                                cb.pct(p);
                            }
                        }
                        MediaExtractor ex = new MediaExtractor();
                        try {
                            ex.setDataSource(fd, off, lens[s]);
                            int t = firstTrack(ex, "video/");
                            if (t < 0) continue;
                            ex.selectTrack(t);
                            long base = -1;
                            long relLast = 0;
                            long step = 0;
                            long prev = -1;
                            while (true) {
                                vBuf.clear();
                                int size;
                                try {
                                    size = ex.readSampleData(vBuf, 0);
                                } catch (Throwable e) {
                                    size = -1;
                                }
                                if (size < 0) break;
                                long pts = ex.getSampleTime();
                                if (pts < 0) break;
                                if (base < 0) base = pts;
                                long rel = pts - base;
                                if (rel < 0) rel = 0;
                                if (prev >= 0 && step == 0 && rel > prev) step = rel - prev;
                                prev = rel;
                                info.offset = 0;
                                info.size = size;
                                info.presentationTimeUs = gBase + rel;
                                info.flags = ex.getSampleFlags();
                                try {
                                    mux.writeSampleData(vOut, vBuf, info);
                                } catch (Throwable ignored) { }
                                relLast = rel;
                                ex.advance();
                            }
                            // 下一段接在「本段最后一帧 + 一帧间隔」之后，不留缝也不重叠
                            gBase += relLast + (step > 0 ? step : 33333);
                        } catch (Throwable ignored) {
                            // 这一段读不出来就跳过，别的段还能用
                        } finally {
                            try { ex.release(); } catch (Throwable ignored) { }
                        }
                        off += lens[s];
                        doneBytes += lens[s];
                    }
                }

                // ② 音频：自己解析出来的裸 AAC，按帧时长排时间轴
                if (aOut >= 0) {
                    ByteBuffer aBuf = ByteBuffer.allocateDirect(BUF);
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    long a0 = aac.pts[0];
                    try (java.io.FileInputStream afis = new java.io.FileInputStream(aacTmp)) {
                        for (int i = 0; i < aac.frames; i++) {
                            int sz = aac.size[i];
                            aBuf.clear();
                            int got = 0;
                            while (got < sz) {
                                int r = afis.read(aTmpBytes, 0, Math.min(sz - got, aTmpBytes.length));
                                if (r <= 0) break;
                                aBuf.put(aTmpBytes, 0, r);
                                got += r;
                            }
                            if (got <= 0) continue;
                            info.offset = 0;
                            info.size = got;
                            info.presentationTimeUs = Math.max(0, aac.pts[i] - a0);
                            info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                            try {
                                mux.writeSampleData(aOut, aBuf, info);
                            } catch (Throwable ignored) { }
                        }
                    }
                }

                if (cb != null) cb.pct(100);
                mux.stop();
            } finally {
                try { if (mux != null) mux.release(); } catch (Throwable ignored) { }
                try { if (fis != null) fis.close(); } catch (Throwable ignored) { }
            }
        } finally {
            try { aacTmp.delete(); } catch (Throwable ignored) { }
        }
    }

    private static final byte[] aTmpBytes = new byte[64 * 1024];

    /** AAC 帧集合：从 TS 里自己抽出来的裸 AAC（已去掉 ADTS 头） */
    public static final class Aac {
        public int frames;
        public long[] pts = new long[0];
        public int[] size = new int[0];
        public int sampleRate;
        public int channels;
        public int profile;
    }

    private static int srIndex(int rate) {
        int[] t = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
                16000, 12000, 11025, 8000, 73500};
        for (int i = 0; i < t.length; i++) {
            if (t[i] == rate) return i;
        }
        return 4;
    }

    private static final int[] AAC_SR_TABLE = {96000, 88200, 64000, 48000, 44100,
            32000, 24000, 22050, 16000, 12000, 11025, 8000, 73500};

    /**
     * 扫整个 .ts：按 188 字节包找音频 PID，重组 PES，剥掉 ADTS 头，
     * 把裸 AAC 帧写到 aacOut，每帧时间戳按 PES PTS + 帧序排好。
     */
    private static Aac parseTsAac(File ts, File aacOut) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(ts);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(aacOut);
        java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos, 1 << 16);
        Aac res = new Aac();
        try {
            byte[] buf = new byte[1 << 20];
            int fill = 0;
            int pos = 0;
            int audioPid = -1;
            byte[] acc = new byte[1 << 16];
            int accLen = 0;
            Grow g = new Grow();
            St st = new St();

            while (true) {
                if (pos + 188 > fill) {          // 缓冲里没有完整包了
                    if (pos > 0) {
                        System.arraycopy(buf, pos, buf, 0, fill - pos);
                        fill -= pos;
                        pos = 0;
                    }
                    if (fill >= buf.length) break;   // 缓冲满了还不成包，异常文件
                    int r = fis.read(buf, fill, buf.length - fill);
                    if (r <= 0) break;              // 真正读完
                    fill += r;
                    continue;
                }
                if (buf[pos] != 0x47) {   // 失同步：找下一个 0x47
                    int p = pos;
                    while (p < fill && buf[p] != 0x47) p++;
                    if (p + 188 > fill) {
                        pos = p;
                        continue;             // 交给上面的补数据逻辑
                    }
                    pos = p;
                }
                byte[] pkt = new byte[188];
                System.arraycopy(buf, pos, pkt, 0, 188);
                pos += 188;

                int pid = ((pkt[1] & 0x1F) << 8) | (pkt[2] & 0xFF);
                boolean pusi = (pkt[1] & 0x40) != 0;
                int afc = (pkt[3] >> 4) & 3;
                if (afc == 0 || afc == 2) continue;
                int off = 4;
                if (afc == 3) off = 5 + (pkt[4] & 0xFF);
                if (off >= 188) continue;

                if (audioPid < 0) {
                    // 还没锁定音频 PID：只在包起始处看是不是音频 PES + ADTS 同步
                    if (!pusi) continue;
                    if (188 - off < 9) continue;
                    if (pkt[off] != 0 || pkt[off + 1] != 0 || pkt[off + 2] != 1) continue;
                    int sid = pkt[off + 3] & 0xFF;
                    if (sid < 0xC0 || sid > 0xDF) continue;
                    int hl = pkt[off + 8] & 0xFF;
                    int d0 = off + 9 + hl;
                    if (d0 + 1 < 188 && (pkt[d0] & 0xFF) == 0xFF
                            && (pkt[d0 + 1] & 0xF6) == 0xF0) {
                        audioPid = pid;   // 锁定的这一包也要正常收进去
                    } else {
                        continue;
                    }
                }
                if (pid != audioPid) continue;

                if (pusi) {
                    // 收尾上一包 PES
                    if (accLen > 0) feedPes(acc, accLen, g, res, bos, st);
                    accLen = 0;
                    int pl = 188 - off;
                    if (pl > acc.length) acc = new byte[Math.max(pl, acc.length * 2)];
                    System.arraycopy(pkt, off, acc, 0, pl);
                    accLen = pl;
                } else {
                    if (accLen == 0) continue;
                    int pl = 188 - off;
                    if (accLen + pl > acc.length) {
                        byte[] nb = new byte[Math.max(accLen + pl, acc.length * 2)];
                        System.arraycopy(acc, 0, nb, 0, accLen);
                        acc = nb;
                    }
                    System.arraycopy(pkt, off, acc, accLen, pl);
                    accLen += pl;
                }
            }
            if (accLen > 0) {
                feedPes(acc, accLen, g, res, bos, st);
            }
            bos.flush();
            res.frames = g.n;
            res.pts = g.pts();
            res.size = g.sizes();
            if (res.frames == 0 || res.sampleRate <= 0) return null;
            return res;
        } finally {
            try { fis.close(); } catch (Throwable ignored) { }
            try { bos.close(); } catch (Throwable ignored) { }
        }
    }

    /** feedPes 的跨包状态 */
    private static final class St {
        long frameDur = 0;   // 一帧 AAC 的时长（微秒）
        long offset = 0;     // 时间轴整体偏移（分段 PTS 回跳时往上抬）
        long lastT = Long.MIN_VALUE;
    }

    private static void feedPes(byte[] acc, int len, Grow g, Aac res,
                                java.io.OutputStream bos, St st) {
        try {
            if (len < 9) return;
            if (acc[0] != 0 || acc[1] != 0 || acc[2] != 1) return;
            int flags2 = acc[7] & 0xFF;
            int hl = acc[8] & 0xFF;
            long pts;
            if ((flags2 & 0x80) != 0 && len >= 14) {
                long p1 = (acc[9] >> 1) & 0x07;
                long p2 = (((acc[10] & 0xFF) << 7) | ((acc[11] & 0xFF) >> 1)) & 0x7FFF;
                long p3 = (((acc[12] & 0xFF) << 7) | ((acc[13] & 0xFF) >> 1)) & 0x7FFF;
                pts = (p1 << 30) | (p2 << 15) | p3;
            } else {
                return;
            }
            long pesUs = pts * 1000000L / 90000L;
            int d = 9 + hl;
            int i = d;
            int k = 0;
            while (i + 7 <= len) {
                int b0 = acc[i] & 0xFF;
                int b1 = acc[i + 1] & 0xFF;
                if (b0 == 0xFF && (b1 & 0xF6) == 0xF0) {
                    int ln = ((acc[i + 3] & 0x03) << 11) | ((acc[i + 4] & 0xFF) << 3)
                            | ((acc[i + 5] & 0xFF) >> 5);
                    if (ln <= 0) break;
                    if (i + ln > len) break;   // 不完整帧（多半是文件末尾被截断）
                    int h = (b1 & 0x01) == 0 ? 9 : 7;
                    int srIdx = (acc[i + 2] >> 2) & 0x0F;
                    int ch = ((acc[i + 2] & 0x01) << 2) | ((acc[i + 3] >> 6) & 0x03);
                    if (res.sampleRate == 0 && srIdx < AAC_SR_TABLE.length) {
                        res.sampleRate = AAC_SR_TABLE[srIdx];
                        res.channels = ch == 0 ? 2 : ch;
                        res.profile = (acc[i + 2] >> 6) & 0x03;
                        st.frameDur = 1024 * 1000000L / res.sampleRate;
                    }
                    // 时间戳：PES 起始 PTS + 帧序 × 1024 采样；PTS 回跳时整体上抬
                    long t = pesUs + st.offset + (long) k * st.frameDur;
                    if (t <= st.lastT) {
                        st.offset += (st.lastT + st.frameDur) - t;
                        t = pesUs + st.offset + (long) k * st.frameDur;
                    }
                    st.lastT = t;
                    g.add(t, ln - h);
                    bos.write(acc, i + h, ln - h);
                    k++;
                    i += ln;
                } else {
                    i++;
                }
            }
        } catch (Throwable ignored) { }
    }

    /** 每帧的时间戳与字节数，同步增长 */
    private static final class Grow {
        long[] p = new long[4096];
        int[] s = new int[4096];
        int n;

        void add(long pts, int size) {
            if (n == p.length) {
                long[] np = new long[n * 2];
                System.arraycopy(p, 0, np, 0, n);
                p = np;
                int[] ns = new int[n * 2];
                System.arraycopy(s, 0, ns, 0, n);
                s = ns;
            }
            p[n] = pts;
            s[n] = size;
            n++;
        }

        long[] pts() {
            long[] r = new long[n];
            System.arraycopy(p, 0, r, 0, n);
            return r;
        }

        int[] sizes() {
            int[] r = new int[n];
            System.arraycopy(s, 0, r, 0, n);
            return r;
        }
    }

    private static int firstTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void copy(MediaExtractor ex, int outTrack, MediaMuxer mux, long shiftUs,
                             long totalUs, Progress cb, int fromPct, int toPct) {
        ByteBuffer buf = ByteBuffer.allocateDirect(BUF);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int last = -1;
        while (true) {
            buf.clear();
            int size;
            try {
                size = ex.readSampleData(buf, 0);
            } catch (Throwable t) {
                break;
            }
            if (size < 0) break;
            long pts = ex.getSampleTime() + shiftUs;
            if (pts < 0) pts = 0;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = pts;
            info.flags = ex.getSampleFlags();
            try {
                mux.writeSampleData(outTrack, buf, info);
            } catch (Throwable t) {
                break;
            }
            ex.advance();

            if (cb != null && totalUs > 0) {
                int p = fromPct + (int) ((toPct - fromPct) * Math.min(1.0, pts / (double) totalUs));
                if (p != last) {
                    last = p;
                    cb.pct(p);
                }
            }
        }
    }

    /** 简易探测：文件里有几条轨道、视频多高。用于合并后自检 */
    public static String probe(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat m = ex.getTrackFormat(i);
                String mime = m.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (sb.length() > 0) sb.append(" + ");
                if (mime.startsWith("video/")) {
                    sb.append("画面 ").append(m.getInteger(MediaFormat.KEY_WIDTH))
                            .append("x").append(m.getInteger(MediaFormat.KEY_HEIGHT));
                } else if (mime.startsWith("audio/")) {
                    sb.append("声音 ").append(m.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? m.getInteger(MediaFormat.KEY_SAMPLE_RATE) + "Hz" : mime);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            try { ex.release(); } catch (Throwable ignored) { }
        }
    }
}
