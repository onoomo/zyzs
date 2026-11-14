package com.downx.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 文件落盘。
 * 可见模式：写进系统相册目录（Movies/DownX、Pictures/DownX），相册里能看到；
 * 隐藏模式：写进 Android/data/<包名>/files/Movies|Pictures/DownX ，
 *           目录内放 .nomedia，相册看不到；去 /storage/emulated/0/Android/data/<包名>/ 能直接找到。
 *           代价：卸载 App 时系统会连这个目录一起删（该路径的固有行为）。
 * Android/media/<包名> 是旧版本用过的隐藏位置，现在只用于读取兼容与回搬，不再写入。
 */
public class Store {

    public static final String SUB = "DownX";

    /** 赞赏码的文件名前缀：它存在 Pictures/DownX 但不是下载产物，补救扫描必须跳过 */
    public static final String DONATE_QR_PREFIX = "DownX_赞赏码_";

    /** 旧版（收款码时期）保存过的前缀，扫描同样要跳过，避免旧文件被误当下载产物复活 */
    public static final String DONATE_QR_PREFIX_OLD = "DownX_打赏收款码_";

    /** 该文件名是否为赞赏/收款码图片（非下载产物） */
    public static boolean isDonateQr(String name) {
        return name.startsWith(DONATE_QR_PREFIX) || name.startsWith(DONATE_QR_PREFIX_OLD);
    }

    public static String dirType(String kind) {
        return "photo".equals(kind) ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES;
    }

    public static String mime(String name, String kind) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "video/mp4";
    }

    // ---------------- 隐藏模式的位置：Android/data/<包名>/files ----------------
    //
    // 隐藏文件就放在 App 私有目录里，用户去 /storage/emulated/0/Android/data/<包名>/
    // 能直接找到（files/Movies/DownX、files/Pictures/DownX）。
    // 这里免权限可写、API 21~36 全兼容；放一个空的 .nomedia，MediaStore 跳过，
    // 相册里照样看不到。代价是卸载 App 时系统会删掉这个目录。
    //
    // 下面 mediaRoot() 定位的是旧版用过的 Android/media/<包名>，只用于读取兼容
    // 与启动时把老文件回搬，绝不再往那里写入。

    private static File mediaRootCache;
    private static boolean mediaRootProbed;

    /** 旧版隐藏目录 Android/media/<包名>；只有它确实存在（旧版本写过）才返回，绝不新建 */
    public static File mediaRoot(Context c) {
        if (mediaRootProbed) return mediaRootCache;
        mediaRootProbed = true;
        mediaRootCache = null;
        try {
            // getExternalFilesDir 的实际层级是 .../Android/data/<包名>/files，
            // 不同厂商还可能多套一层，所以逐级向上找名为 Android 的那一层，别硬数层数
            File cur = c.getExternalFilesDir(null);
            File android = null;
            while (cur != null) {
                if ("Android".equals(cur.getName())) {
                    android = cur;
                    break;
                }
                cur = cur.getParentFile();
            }
            if (android == null) return null;
            File root = new File(new File(android, "media"), c.getPackageName());
            // 新版本已不往 Android/media 写，这里只读旧残留；目录不存在就直接返回 null，
            // 避免在用户设备上凭空建出一个空目录
            if (root.isDirectory()) mediaRootCache = root;
        } catch (Throwable t) {
            mediaRootCache = null;
        }
        return mediaRootCache;
    }

    /** 隐藏文件的唯一写入/主目录：Android/data/<包名>/files/Movies|Pictures/DownX */
    public static File privateDir(Context c, String kind) {
        File base = c.getExternalFilesDir(dirType(kind));
        if (base == null) base = new File(c.getFilesDir(), dirType(kind));
        File d = new File(base, SUB);
        if (!d.exists()) d.mkdirs();
        // .nomedia：让 MediaStore 跳过整棵目录，隐藏后相册里看不到
        File nomedia = new File(d, ".nomedia");
        if (!nomedia.exists()) {
            try { nomedia.createNewFile(); } catch (Throwable ignored) { }
        }
        return d;
    }

    /** 旧版隐藏目录 Android/media/<包名>/Movies|Pictures/DownX，仅供读取兼容 / 回搬；可能不存在 */
    public static File mediaPrivateDir(Context c, String kind) {
        File root = mediaRoot(c);
        if (root == null) return null;
        return new File(new File(root, dirType(kind)), SUB);
    }

    /** 与 privateDir 同路径（Android/data/.../files/Movies|Pictures/DownX），保留给旧调用点 */
    public static File legacyPrivateDir(Context c, String kind) {
        File base = c.getExternalFilesDir(dirType(kind));
        if (base == null) base = new File(c.getFilesDir(), dirType(kind));
        return new File(base, SUB);
    }

    public static File publicDir(String kind) {
        return new File(Environment.getExternalStoragePublicDirectory(dirType(kind)), SUB);
    }

    /** 定位隐藏文件：Android/data 主目录优先，旧版 Android/media 兜底（老文件照样能播、能删） */
    public static File privateFile(Context c, String kind, String name) {
        File f = new File(privateDir(c, kind), name);
        if (f.exists()) return f;
        File media = mediaPrivateDir(c, kind);
        if (media != null) {
            File old = new File(media, name);
            if (old.exists()) return old;
        }
        return f;
    }

    /** 写入目标：固定 Android/data 私有目录，不再写 Android/media */
    public static File privateWriteTarget(Context c, String kind, String name) {
        File f = new File(privateDir(c, kind), name);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        return f;
    }

    // ---------------- 老文件搬家 / 重装后补历史 ----------------

    /**
     * 启动时调用：把旧版本藏在 Android/media/<包名> 里的文件搬回
     * Android/data/<包名>/files/Movies|Pictures/DownX，让用户去私有目录就能找到。
     * 返回搬动的文件数。同分区是 rename，几乎不耗时；跨卷（外置 SD）退回复制。
     */
    public static int migrateHidden(Context c) {
        int moved = 0;
        for (String kind : new String[]{"video", "photo"}) {
            File oldDir = mediaPrivateDir(c, kind);
            if (oldDir == null) continue;
            File[] fs = oldDir.listFiles();
            if (fs == null || fs.length == 0) continue;
            File newDir = privateDir(c, kind);
            for (File f : fs) {
                if (f.isDirectory() || f.getName().startsWith(".")) continue;
                File t = new File(newDir, f.getName());
                try {
                    if (t.exists()) {
                        // 新位置已经有同名文件，以新的为准，老的多余一份删掉
                        if (f.delete()) moved++;
                        continue;
                    }
                    if (f.renameTo(t)) {
                        moved++;
                        continue;
                    }
                    try (InputStream in = new FileInputStream(f);
                         OutputStream os = new FileOutputStream(t)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    if (t.length() == f.length() && f.delete()) moved++;
                    else t.delete();
                } catch (Throwable ignored) { }
            }
            // 旧的空目录顺手清掉（只删空目录，绝不递归）
            try {
                String[] left = oldDir.list();
                if (left != null && left.length == 0) oldDir.delete();
            } catch (Throwable ignored) { }
        }
        return moved;
    }

    private static String guessKind(String name) {
        String n = name.toLowerCase();
        return (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".gif")) ? "photo" : "video";
    }

    /**
     * 把目录里存在、但下载历史里没有的文件补成记录。
     * 专治"卸载重装后文件还在、列表却空了"——用户会以为文件被删了。
     * 重装后 SharedPreferences 是空的，所以能扫到全部旧文件。
     */
    public static int restoreHistory(Context c) {
        List<HistoryStore.Entry> all = HistoryStore.list(c);
        java.util.Set<String> known = new java.util.HashSet<>();
        for (HistoryStore.Entry e : all) {
            if (e.name != null && !e.name.isEmpty()) known.add(e.name);
        }
        int added = 0;

        // 1) 隐藏目录：直接扫盘（Android/data 主目录 + 旧版 Android/media 残留）
        for (String kind : new String[]{"video", "photo"}) {
            java.util.List<File> dirs = new java.util.ArrayList<>();
            dirs.add(privateDir(c, kind));
            File media = mediaPrivateDir(c, kind);
            if (media != null) dirs.add(media);
            for (File dir : dirs) {
                File[] fs = dir.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName();
                    if (!f.isFile() || f.length() <= 0) continue;
                    if (n.startsWith(".") || n.endsWith(".part")) continue;
                    if (Store.isDonateQr(n)) continue;   // 赞赏码不是下载产物
                    if (HistoryStore.isBuried(c, n)) continue;      // 用户主动删过的不复活
                    if (!known.add(n)) continue;
                    HistoryStore.Entry e = new HistoryStore.Entry();
                    e.name = n;
                    e.kind = guessKind(n);
                    e.visible = false;
                    e.uri = "";
                    e.ts = f.lastModified();
                    e.sub = "之前下载";
                    all.add(e);
                    added++;
                }
            }
        }

        // 2) 相册目录：MediaStore 里还有记录，重装后同样能捞回来
        if (Build.VERSION.SDK_INT >= 29) {
            for (String kind : new String[]{"video", "photo"}) {
                Uri col = "photo".equals(kind)
                        ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                android.database.Cursor cur = null;
                try {
                    cur = c.getContentResolver().query(col,
                            new String[]{MediaStore.MediaColumns._ID,
                                    MediaStore.MediaColumns.DISPLAY_NAME,
                                    MediaStore.MediaColumns.DATE_ADDED,
                                    MediaStore.MediaColumns.RELATIVE_PATH},
                            MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                            new String[]{dirType(kind) + "/" + SUB + "%"},
                            MediaStore.MediaColumns.DATE_ADDED + " DESC");
                    while (cur != null && cur.moveToNext()) {
                        String n = cur.getString(1);
                        if (n == null || n.isEmpty()) continue;
                        if (Store.isDonateQr(n)) continue;   // 赞赏码不是下载产物
                        // 多图帖在相册里是 Pictures/DownX/多图/xxx.jpg，
                        // 记录里必须写成「多图/xxx.jpg」，否则拼出来的路径是空的，
                        // 补回来的记录一进去就显示"文件已不在"
                        String rel = cur.getString(3);
                        if (rel != null) {
                            int cut = rel.indexOf(SUB + "/");
                            if (cut >= 0) {
                                String sub2 = rel.substring(cut + SUB.length() + 1);
                                while (sub2.endsWith("/")) sub2 = sub2.substring(0, sub2.length() - 1);
                                if (!sub2.isEmpty()) n = sub2 + "/" + n;
                            }
                        }
                        if (HistoryStore.isBuried(c, n)) continue;      // 用户主动删过的不复活
                        if (!known.add(n)) continue;
                        HistoryStore.Entry e = new HistoryStore.Entry();
                        e.name = n;
                        e.kind = kind;
                        e.visible = true;
                        e.uri = android.content.ContentUris
                                .withAppendedId(col, cur.getLong(0)).toString();
                        e.ts = cur.getLong(2) * 1000L;
                        e.sub = "之前下载";
                        all.add(e);
                        added++;
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
                }
            }
        }

        if (added > 0) {
            java.util.Collections.sort(all, (a, b) -> Long.compare(b.ts, a.ts));
            RecRules.trim(all, HistoryStore.MAX, x -> true);
            HistoryStore.saveAll(c, all);
        }
        return added;
    }

    /**
     * 启动时调用（后台线程）：搬老文件 + 补历史。
     * 补历史每个安装只做一次：既覆盖"卸载重装"（SharedPreferences 被清空），
     * 又不会把用户主动从列表里删掉的记录反复"复活"。
     * 返回 [搬动条数, 补录条数]。
     */
    public static int[] upgradeStorage(Context c) {
        int moved = migrateHidden(c);
        int added = 0;
        try {
            // 每次启动都比对一遍「磁盘上有、记录里没有」的文件并补回记录。
            // 原来一辈子只补一次（restored 标记），列表一旦被顶掉就再也回不来。
            // 用户主动删过的名字在"已删除名单"里（HistoryStore.bury），不会被复活。
            added = restoreHistory(c);
        } catch (Throwable ignored) { }
        return new int[]{moved, added};
    }

    public static File publicFile(String kind, String name) {
        return new File(publicDir(kind), name);
    }

    public static File legacyPrivateFile(Context c, String kind, String name) {
        return new File(legacyPrivateDir(c, kind), name);
    }

    public interface Progress {
        void pct(int p);
    }

    /**
     * 探测视频是竖屏还是横屏（"v"/"h"，读不出返回 null）。
     * 手机竖拍的 mp4 里 width/height 是横的，靠 rotation 元数据换算回来。
     */
    public static String probeOrientation(Context c, HistoryStore.Entry e) {
        android.media.MediaMetadataRetriever m = new android.media.MediaMetadataRetriever();
        try {
            boolean ok = false;
            try {
                m.setDataSource(c, playUri(c, e));
                ok = true;
            } catch (Throwable t) {
                android.util.Log.w("DownX", "probe: uri 读不了 " + e.name + " -> " + t);
            }
            if (!ok) {
                File f = e.visible ? publicFile(e.kind, e.name) : privateFile(c, e.kind, e.name);
                m.setDataSource(f.getAbsolutePath());
            }
            int w = int0(m.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = int0(m.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rot = int0(m.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rot == 90 || rot == 270) {
                int t = w; w = h; h = t;
            }
            if (w <= 0 || h <= 0) return null;
            return w > h ? "h" : "v";
        } catch (Throwable t) {
            android.util.Log.w("DownX", "probe 失败 " + e.name, t);
            return null;
        } finally {
            try { m.release(); } catch (Throwable ignored) { }
        }
    }

    private static int int0(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return 0; }
    }

    // ---------------- 写入 ----------------

    /** 写入并返回可播放的 uri 字符串（相册模式拿 MediaStore uri，隐藏模式为空） */
    public static String write(Context c, InputStream in, long total, String kind, String name,
                               boolean visible, Progress cb) throws Exception {
        if (!visible) {
            writePrivate(c, in, total, kind, name, cb);
            return "";
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return writeMediaStore(c, in, total, kind, name, cb);
        }
        File f = writeLegacy(c, in, total, kind, name, cb);
        return "file://" + f.getAbsolutePath();
    }

    private static void writePrivate(Context c, InputStream in, long total, String kind,
                                     String name, Progress cb) throws Exception {
        File dir = privateDir(c, kind);
        File out = new File(dir, name);
        // 多图帖会写成「多图/文件名」，先把子目录建出来
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(dir, name + ".part");
        File tmpParent = tmp.getParentFile();
        if (tmpParent != null && !tmpParent.exists()) tmpParent.mkdirs();
        try (OutputStream os = new FileOutputStream(tmp)) {
            copy(in, total, os, cb);
        } catch (Throwable t) {
            // 存到一半失败：临时文件别留在目录里白占空间
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw t;
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) throw new Exception("保存失败（无法重命名临时文件）");
    }

    private static File writeLegacy(Context c, InputStream in, long total, String kind, String name,
                                    Progress cb) throws Exception {
        File dir = publicDir(kind);
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, name);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream os = new FileOutputStream(out)) {
            copy(in, total, os, cb);
        } catch (Throwable t) {
            // 失败时把半个文件删掉：相册目录里留个坏文件既占空间、
            // 又会被「补救扫描」误认成一条下载记录
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw t;
        }
        try {
            MediaScannerConnection.scanFile(c, new String[]{out.getAbsolutePath()},
                    new String[]{mime(name, kind)}, null);
        } catch (Throwable ignored) { }
        return out;
    }

    private static String writeMediaStore(Context c, InputStream in, long total, String kind,
                                          String name, Progress cb) throws Exception {
        ContentResolver cr = c.getContentResolver();
        Uri collection = "photo".equals(kind)
                ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

        // name 可能带子目录（多图帖统一放「多图」里）：
        // DISPLAY_NAME 只能放文件名，目录要进 RELATIVE_PATH
        String rel = dirType(kind) + "/" + SUB;
        String fileName = name;
        int slash = name == null ? -1 : name.lastIndexOf('/');
        if (slash > 0) {
            rel = rel + "/" + name.substring(0, slash);
            fileName = name.substring(slash + 1);
        }

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime(name, kind));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = cr.insert(collection, v);
        if (uri == null) throw new Exception("相册目录不可写，请在设置里关闭「保存到相册」后重试");
        boolean ok = false;
        try {
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new Exception("无法写入相册文件");
            try (OutputStream o = os) {
                copy(in, total, o, cb);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            ok = true;
            return uri.toString();
        } finally {
            if (!ok) {
                try { cr.delete(uri, null, null); } catch (Throwable ignored) { }
            }
        }
    }

    private static void copy(InputStream in, long total, OutputStream out, Progress cb)
            throws Exception {
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        int last = -1;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (cb != null && total > 0) {
                int p = (int) (done * 100 / total);
                if (p != last) {
                    last = p;
                    cb.pct(p);
                }
            }
        }
        out.flush();
    }

    // ---------------- 定位 / 播放 ----------------

    /** 拿可播放的 uri：相册模式优先用 MediaStore 的，隐藏模式走本 App 的内部文件通道 */
    public static Uri uriFor(Context c, HistoryStore.Entry e) {
        if (e.uri != null && e.uri.startsWith("file://")) return Uri.parse(e.uri);  // 原版引擎桥接
        if (e.uri != null && e.uri.startsWith("content://")) return Uri.parse(e.uri);
        File f = e.visible ? publicFile(e.kind, e.name) : privateFile(c, e.kind, e.name);
        return LocalFiles.uriFor(c, f);
    }

    /** 这个 MediaStore uri 是否还活着：行要存在，DATA 指向的文件也要真的在盘上 */
    public static boolean uriAlive(Context c, Uri u) {
        try {
            android.database.Cursor cur = c.getContentResolver().query(u,
                    new String[]{MediaStore.MediaColumns.DATA}, null, null, null);
            if (cur != null) {
                boolean ok = false;
                if (cur.moveToFirst()) {
                    String p = cur.getString(0);
                    ok = p != null && new File(p).exists();
                }
                cur.close();
                if (ok) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * 播放专用 uri：先验证缓存的 MediaStore uri；失效了就按文件名在相册里重找，
     * 再不行退到文件路径通道。文件被重新下载/清理过之后记录还能继续播。
     */
    public static Uri playUri(Context c, HistoryStore.Entry e) {
        if (e.uri != null && e.uri.startsWith("file://")) return Uri.parse(e.uri);  // 原版引擎桥接：直通本地文件
        if (e.uri != null && e.uri.startsWith("content://")) {
            Uri u = Uri.parse(e.uri);
            if (uriAlive(c, u)) return u;
        }
        if (e.visible && Build.VERSION.SDK_INT >= 29 && e.name != null && !e.name.isEmpty()) {
            try {
                Uri col = "photo".equals(e.kind)
                        ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                android.database.Cursor cur = c.getContentResolver().query(col,
                        new String[]{MediaStore.MediaColumns._ID},
                        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{e.name, dirType(e.kind) + "/" + SUB + "%"},
                        MediaStore.MediaColumns.DATE_ADDED + " DESC");
                if (cur != null) {
                    Uri found = null;
                    if (cur.moveToFirst()) {
                        long id = cur.getLong(0);
                        found = android.content.ContentUris.withAppendedId(col, id);
                    }
                    cur.close();
                    if (found != null) {
                        e.uri = found.toString();   // 顺手把缓存修好
                        return found;
                    }
                }
            } catch (Throwable ignored) { }
        }
        File f = e.visible ? publicFile(e.kind, e.name) : privateFile(c, e.kind, e.name);
        if (f.exists()) return LocalFiles.uriFor(c, f);
        // 都不行就返回原来的，让播放器给出明确提示
        if (e.uri != null && e.uri.startsWith("content://")) return Uri.parse(e.uri);
        return LocalFiles.uriFor(c, f);
    }

    /** 播放失败后的兜底 uri：绕开 MediaStore，直接走文件路径 */
    public static Uri fallbackUri(Context c, HistoryStore.Entry e) {
        File f = e.visible ? publicFile(e.kind, e.name) : privateFile(c, e.kind, e.name);
        if (f.exists()) return LocalFiles.uriFor(c, f);
        return null;
    }

    public static boolean exists(Context c, HistoryStore.Entry e) {
        File f = e.visible ? publicFile(e.kind, e.name) : privateFile(c, e.kind, e.name);
        if (f.exists()) return true;
        // 文件不在了再看 MediaStore 记录还在不在（避免整页查询拖慢列表）
        if (e.uri != null && e.uri.startsWith("content://")) {
            return uriAlive(c, Uri.parse(e.uri));
        }
        return false;
    }

    // ---------------- 切换相册可见性 ----------------

    /** 把已下载的文件搬到目标位置。返回处理条数 */
    public static int migrate(Context c, boolean toVisible) {
        List<HistoryStore.Entry> list = HistoryStore.list(c);
        int moved = 0;
        failed = 0;
        for (HistoryStore.Entry e : list) {
            if (e.visible == toVisible) continue;
            if (e.name == null || e.name.isEmpty()) continue;
            boolean ok;
            try {
                ok = toVisible ? moveOut(c, e) : moveIn(c, e);
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) moved++;
            else failed++;
        }
        HistoryStore.saveAll(c, list);
        return moved;
    }

    /** 上一次 migrate 里没能处理的条数（多为旧版本留下的、系统不让改的文件） */
    public static int failed = 0;

    /** 单条搬家：只处理这一条，不影响其他文件。返回是否成功 */
    public static boolean moveOne(Context c, HistoryStore.Entry e, boolean toVisible) {
        if (e == null || e.name == null || e.name.isEmpty()) return false;
        if (e.visible == toVisible) return true;
        boolean ok;
        try {
            ok = toVisible ? moveOut(c, e) : moveIn(c, e);
        } catch (Throwable t) {
            ok = false;
        }
        if (ok) {
            List<HistoryStore.Entry> all = HistoryStore.list(c);
            for (HistoryStore.Entry x : all) {
                if (x.name != null && x.name.equals(e.name) && x.ts == e.ts) {
                    x.visible = e.visible;
                    x.uri = e.uri;
                    break;
                }
            }
            HistoryStore.saveAll(c, all);
        }
        return ok;
    }

    /** 统计：返回 [总数, 已隐藏数] */
    public static int[] stats(Context c) {
        List<HistoryStore.Entry> list = HistoryStore.list(c);
        int hidden = 0;
        for (HistoryStore.Entry e : list) {
            if (!e.visible) hidden++;
        }
        return new int[]{list.size(), hidden};
    }

    /**
     * 是否已经下过同一条：key 相同且文件还在。
     * 老版本记录没有 key，用文件名兜底（文件名含推文 ID + 清晰度，本身就唯一）。
     */
    public static boolean isDuplicate(Context c, String key, String name) {
        // 多图帖的文件放在「多图/」子目录里，比较时要剥掉目录前缀，
        // 否则同一个文件换个位置就会当成新的，列表里多出一条重复记录
        String plain = name == null ? "" : stripAlbum(name);
        boolean haveKey = key != null && !key.isEmpty();
        for (HistoryStore.Entry e : HistoryStore.list(c)) {
            if (!exists(c, e)) continue;
            if (haveKey && !key.isEmpty() && key.equals(e.key)) return true;
            if (!plain.isEmpty() && plain.equals(stripAlbum(e.name))) {
                // 文件名相同 ≠ 同一条作品：快手/抖音标题抓不到时文件名就长一个样
                // （快手_来自快手的视频_video.mp4），不同视频会互相被判成重复，
                // 明明不是同一条却提示「重复下载」。只要两边都有 key 而且不一样，
                // 就说明是两条不同的作品，文件名撞了也放它过。
                boolean eKeyEmpty = e.key == null || e.key.isEmpty();
                if (haveKey && !eKeyEmpty) continue;
                return true;
            }
        }
        return false;
    }

    /** 去掉「多图/」这种相册子目录前缀，只留文件名 */
    public static String stripAlbum(String name) {
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    /** 私有 -> 相册 */
    private static boolean moveOut(Context c, HistoryStore.Entry e) throws Exception {
        File src = privateFile(c, e.kind, e.name);
        if (!src.exists()) return false;
        String uri;
        try (InputStream in = new FileInputStream(src)) {
            uri = write(c, in, src.length(), e.kind, e.name, true, null);
        }
        e.uri = uri;
        e.visible = true;
        src.delete();
        return true;
    }

    /** 相册 -> 私有 */
    private static boolean moveIn(Context c, HistoryStore.Entry e) throws Exception {
        InputStream in = null;
        long len = 0;
        if (e.uri != null && e.uri.startsWith("content://")) {
            try {
                in = c.getContentResolver().openInputStream(Uri.parse(e.uri));
            } catch (Throwable ignored) { }
        }
        File pub = publicFile(e.kind, e.name);
        if (in == null) {
            if (!pub.exists()) return false;
            len = pub.length();
            in = new FileInputStream(pub);
        }
        // 明确用「本次实际写入的目标」，不要用 privateFile()：它会兜底到旧版
        // Android/media 路径，回滚时可能误删旧文件
        File priv = new File(privateDir(c, e.kind), e.name);
        try (InputStream i = in) {
            writePrivate(c, i, len, e.kind, e.name, null);
        }
        // 删掉相册里的那一份；删不掉就退回原状态，避免留下两份
        boolean removed = removePublic(c, e);
        if (!removed) {
            if (priv.exists()) priv.delete();
            return false;
        }
        e.uri = "";
        e.visible = false;
        return true;
    }

    /** 删除相册目录里的那份文件（含 MediaStore 记录） */
    public static boolean removePublic(Context c, HistoryStore.Entry e) {
        if (e.uri != null && e.uri.startsWith("content://")) {
            try { c.getContentResolver().delete(Uri.parse(e.uri), null, null); } catch (Throwable ignored) { }
        }
        File pub = publicFile(e.kind, e.name);
        if (!pub.exists()) return true;
        try {
            if (pub.delete()) return true;
        } catch (Throwable ignored) { }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Uri col = "photo".equals(e.kind)
                        ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
                c.getContentResolver().delete(col, sel,
                        new String[]{dirType(e.kind) + "/" + SUB + "%", e.name});
            } catch (Throwable ignored) { }
        }
        return !pub.exists();
    }
}
