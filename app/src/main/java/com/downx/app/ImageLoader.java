package com.downx.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 轻量图片加载器：内存缓存 + 后台线程 */
public class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(24 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final Map<String, Integer> WAITING = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 图片就绪回调：把宽高比（w/h）带回给调用方，用来把预览框摆成竖版竖着、横版横着 */
    public interface OnReady {
        void onReady(float whRatio);
    }

    public static void load(String url, ImageView iv) {
        load(url, iv, null);
    }

    public static void load(final String url, final ImageView iv, final OnReady cb) {
        if (url == null || url.isEmpty()) return;
        Bitmap bm = CACHE.get(url);
        if (bm != null) {
            iv.setImageBitmap(bm);
            if (cb != null) cb.onReady(bm.getWidth() * 1f / Math.max(1, bm.getHeight()));
            return;
        }
        iv.setTag(url);
        if (WAITING.putIfAbsent(url, 1) != null) return;
        new Thread(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", Net.UA);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                c.disconnect();
                byte[] data = out.toByteArray();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sampleFor(data);
                b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (b != null) CACHE.put(url, b);
            } catch (Exception ignored) { }
            WAITING.remove(url);
            final Bitmap fb = b;
            MAIN.post(() -> {
                if (fb != null && url.equals(iv.getTag())) {
                    iv.setImageBitmap(fb);
                    if (cb != null) cb.onReady(fb.getWidth() * 1f / Math.max(1, fb.getHeight()));
                }
            });
        }, "img").start();
    }

    /** 同步取图（供缩略图等后台线程使用） */
    public static Bitmap fetchSync(String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap cached = CACHE.get(url);
        if (cached != null) return cached;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", Net.UA);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            c.disconnect();
            byte[] data = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sampleFor(data);
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b != null) CACHE.put(url, b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int sampleFor(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int max = Math.max(o.outWidth, o.outHeight);
        int s = 1;
        while (max / (s * 2) >= 1100) s *= 2; // 预览图控制在 1100px 左右即可
        return s;
    }
}
