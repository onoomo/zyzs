package com.downx.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * 缩略图：视频抽第一帧，图片直接解码；本地读不到再用网络封面兜底。
 *
 * 三级缓存，保证「不黑闪」：
 *   1. 内存 LruCache（命中立即显示，零延迟、无动画）
 *   2. 磁盘缓存 cacheDir/thumb（冷启动靠它，几十毫秒出图）
 *   3. 现场抽帧 / 网络下载（首次才走，出图时淡入过渡，不硬切）
 */
public class ThumbLoader {

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(24 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 缩略图解码完成后回报真实宽高，瀑布流用它算卡片高度 */
    public interface OnSize {
        void onSize(int w, int h);
    }

    public static void load(final Context ctx, final ImageView iv, final Uri uri,
                            final String kind, final String fallbackUrl) {
        load(ctx, iv, uri, kind, fallbackUrl, null);
    }

    public static void load(final Context ctx, final ImageView iv, final Uri uri,
                            final String kind, final String fallbackUrl, final OnSize cb) {
        // 前缀是缓存版本：改了取帧策略就换一个，让老的黑封面自动作废重取
        final String key = "v4|" + (uri == null ? "-" : uri.toString()) + "|" + kind
                + "|" + (fallbackUrl == null ? "" : fallbackUrl);

        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            apply(iv, cached, cb, false);
            return;
        }
        // 注意：这里不再 setImageDrawable(null)。
        // 以前未命中就把图清空，列表一重建整屏缩略图同时变黑，看起来就是"闪一下"。
        // 保留原有画面（新建的 ImageView 本来就是空的）才不会有黑闪。
        iv.setTag(key);

        new Thread(() -> {
            // 先看磁盘：上次启动留在这儿的缩略图，读起来只要几十毫秒
            Bitmap b = readDisk(ctx, key);
            if (b != null) {
                CACHE.put(key, b);
                final Bitmap db = b;
                MAIN.post(() -> {
                    if (key.equals(iv.getTag())) apply(iv, db, cb, false);
                });
                return;
            }
            b = uri == null ? null : decode(ctx, uri, kind);
            // 本地抽出来的帧如果还是偏黑（片头黑场、纯黑开场、整段前奏都是暗的），
            // 就换成站点给的官方封面 —— 那张是站方截的，一定有人物，
            // 宁可用网图也别让用户看一块黑
            if (b != null && !"photo".equals(kind)
                    && fallbackUrl != null && !fallbackUrl.isEmpty()
                    && liveness(b) < 0.30f) {
                Bitmap net = ImageLoader.fetchSync(fallbackUrl);
                if (net != null) {
                    b.recycle();
                    b = net;
                }
            }
            if (b == null && fallbackUrl != null && !fallbackUrl.isEmpty()) {
                b = ImageLoader.fetchSync(fallbackUrl);
            }
            if (b != null) {
                CACHE.put(key, b);
                saveDisk(ctx, key, b);
            }
            final Bitmap fb = b;
            MAIN.post(() -> {
                if (fb != null && key.equals(iv.getTag())) apply(iv, fb, cb, true);
            });
        }, "thumb").start();
    }

    /** fade=true 时淡入，避免占位块直接跳成图片那一下的突兀感 */
    private static void apply(ImageView iv, Bitmap b, OnSize cb, boolean fade) {
        iv.setImageBitmap(b);
        if (fade) {
            iv.setAlpha(0f);
            iv.animate().alpha(1f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            iv.animate().cancel();
            iv.setAlpha(1f);
        }
        if (cb != null) cb.onSize(b.getWidth(), b.getHeight());
    }

    // ---------------- 磁盘缓存 ----------------

    /** 缩略图磁盘缓存，控制在 600px、2000 张以内，不会撑爆存储 */
    private static File dirOf(Context ctx) {
        try {
            File d = new File(ctx.getApplicationContext().getCacheDir(), "thumb");
            if (!d.exists() && !d.mkdirs()) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File fileOf(Context ctx, String key) {
        File d = dirOf(ctx);
        if (d == null) return null;
        return new File(d, md5(key) + ".jpg");
    }

    private static Bitmap readDisk(Context ctx, String key) {
        try {
            File f = fileOf(ctx, key);
            if (f == null || !f.exists() || f.length() == 0) return null;
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    private static void saveDisk(Context ctx, String key, Bitmap b) {
        try {
            File f = fileOf(ctx, key);
            if (f == null) return;
            Bitmap out = b;
            int max = Math.max(b.getWidth(), b.getHeight());
            if (max > 640) {
                float s = 640f / max;
                out = Bitmap.createScaledBitmap(b,
                        Math.max(1, Math.round(b.getWidth() * s)),
                        Math.max(1, Math.round(b.getHeight() * s)), true);
            }
            try (FileOutputStream fos = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, 82, fos);
            }
            if (out != b) out.recycle();
            trim(ctx);
        } catch (Throwable ignored) { }
    }

    private static long lastTrim;

    /** 超过 2000 张就按最旧的删一半 */
    private static void trim(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastTrim < 60000) return;
        lastTrim = now;
        try {
            File d = dirOf(ctx);
            File[] fs = d == null ? null : d.listFiles();
            if (fs == null || fs.length < 2000) return;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < fs.length / 2; i++) {
                //noinspection ResultOfMethodCallIgnored
                fs[i].delete();
            }
        } catch (Throwable ignored) { }
    }

    private static String md5(String s) {
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

    // ---------------- 解码 ----------------

    private static Bitmap decode(Context ctx, Uri uri, String kind) {
        if (!"photo".equals(kind)) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(ctx, uri);
                return pickFrame(r);
            } catch (Throwable ignored) {
            } finally {
                try { r.release(); } catch (Throwable ignored) { }
            }
        }
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] data = bos.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0) return null;
            int s = 1;
            while (bounds.outWidth / (s * 2) >= 420 && s < 16) s *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 视频封面取帧。不少片子开头是黑场 / 淡入，抓第一帧就是一张黑图，
     * 首页根本分不清是哪条。这里按「先近后远」几个时间点各取一帧，
     * 谁先够亮够有内容就用谁；都不行就退而用最不黑的那张。
     *
     * 省资源的点：① 只在第一帧被判为黑/糊时才继续取，正常视频只解 1 次；
     *          ② 取的是缩放后的小帧（API 27+ 直接让解码器出小图）；
     *          ③ 结果进内存 + 磁盘缓存，同一个文件一辈子只算一次。
     */
    private static Bitmap pickFrame(MediaMetadataRetriever r) {
        long durUs = 0;
        try {
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) durUs = Long.parseLong(d) * 1000L;
        } catch (Throwable ignored) { }

        // 第一轮：片头附近几个点（绝大多数视频到这儿就有画面了，只解 1~2 次）
        final long[] early = {300_000L, 1_200_000L, 2_500_000L, 4_000_000L, 7_000_000L};
        Bitmap best = null;
        float bestScore = -1f;
        for (long t : early) {
            if (durUs > 0 && t > durUs * 85 / 100) break;   // 别取到片尾
            Bitmap b = frameAt(r, t);
            if (b == null) continue;
            float score = liveness(b);
            if (score >= GOOD_FRAME) return b;              // 够清楚，直接收工
            if (score > bestScore) {
                if (best != null && best != b) best.recycle();
                best = b;
                bestScore = score;
            }
        }

        // 第二轮：片头一直是黑场（logo 动画、纯黑开场、长前奏）时，
        // 按全片比例再找 —— 只看开头 7 秒永远出不来画面
        if (bestScore < GOOD_FRAME && durUs > 8_000_000L) {
            final int[] relPct = {8, 15, 25, 40, 55};
            for (int pct : relPct) {
                long t = durUs * pct / 100;
                Bitmap b = frameAt(r, t);
                if (b == null) continue;
                float score = liveness(b);
                if (score >= GOOD_FRAME) {
                    if (best != null && best != b) best.recycle();
                    return b;
                }
                if (score > bestScore) {
                    if (best != null && best != b) best.recycle();
                    best = b;
                    bestScore = score;
                }
            }
        }
        // 第三轮：拿不到时长时（重封装过的 mp4 偶尔会这样）按比例定位就失效了，
        // 改用固定时间点一路往后找 —— P 站这类片头常有十几秒黑场和 logo
        if (bestScore < GOOD_FRAME && durUs <= 0) {
            final long[] later = {8_000_000L, 15_000_000L, 30_000_000L,
                    60_000_000L, 120_000_000L, 300_000_000L};
            for (long t : later) {
                Bitmap b = frameAt(r, t);
                if (b == null) continue;
                float score = liveness(b);
                if (score >= GOOD_FRAME) {
                    if (best != null && best != b) best.recycle();
                    return b;
                }
                if (score > bestScore) {
                    if (best != null && best != b) best.recycle();
                    best = b;
                    bestScore = score;
                }
            }
        }
        if (best == null) {
            try { best = r.getFrameAtTime(); } catch (Throwable ignored) { }
        }
        return best;
    }

    /** 单帧解码：能缩就缩，省内存也省解码时间 */
    private static Bitmap frameAt(MediaMetadataRetriever r, long us) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                Bitmap b = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        480, 480);
                if (b != null) return b;
            }
            return r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 判定「这张图像不像有画面」的及格线 */
    private static final float GOOD_FRAME = 0.40f;

    /**
     * 给一帧打 0~1 的"有内容"分：太暗（黑场/淡入）或太平（纯色）都低分。
     * 只缩到 24×24 采样，几百微秒的事。
     */
    private static float liveness(Bitmap b) {
        try {
            final int N = 24;
            Bitmap small = Bitmap.createScaledBitmap(b, N, N, false);
            int[] px = new int[N * N];
            small.getPixels(px, 0, N, 0, 0, N, N);
            if (small != b) small.recycle();
            double sum = 0, sum2 = 0;
            for (int c : px) {
                double l = 0.299 * ((c >> 16) & 0xFF)
                        + 0.587 * ((c >> 8) & 0xFF)
                        + 0.114 * (c & 0xFF);
                sum += l;
                sum2 += l * l;
            }
            double mean = sum / px.length;
            double var = Math.max(0, sum2 / px.length - mean * mean);
            double bright = mean / 255.0;                     // 亮度
            double contrast = Math.min(1.0, Math.sqrt(var) / 110.0); // 对比度/细节
            return (float) (bright * 0.78 + contrast * 0.55);
        } catch (Throwable t) {
            return 1f;   // 算不出来就当它没问题，别耽误显示
        }
    }
}
