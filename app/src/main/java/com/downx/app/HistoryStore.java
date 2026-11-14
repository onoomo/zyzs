package com.downx.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 下载历史。仅保存在设备内（App 私有目录），不上传。
 *
 * <p>存储方式（v1.3.6 起）：{@code filesDir/history/items.log}，一行一条、只追加，
 * 详见 {@link RecLog}。之所以不用 SharedPreferences / 每次全量重写：
 * 那种写法每加一条记录都要重新序列化整个列表（O(n)），一万条时单条写几 MB，
 * 批量下载会明显卡顿、且反复擦写同一块闪存。
 *
 * <p>写入策略（重要）：
 * <ul>
 *   <li>新增、改方向：追加一行，O(1)，不重写文件；</li>
 *   <li>删除单条、清空、去重、调整顺序、超上限清理：整体重写（低频，走 tmp→rename 原子替换）；</li>
 *   <li>行数涨到记录数的 3 倍以上时压缩一次，把同一记录的多个版本合并掉。</li>
 * </ul>
 *
 * <p>追加不 fsync：进程被杀时数据已在页缓存里，仍会落盘；只有断电/内核崩溃才可能丢最后几条，
 * 而启动时的磁盘对账（{@link Store#restoreHistory}）会把文件补回来，所以不为此付每次 fsync 的代价。
 */
public class HistoryStore {

    public static class Entry {
        public String name = "";   // 文件名
        public String sub = "";    // 作者 / 说明
        public String pageUrl = ""; // 原帖链接
        public String thumb = "";  // 网络封面
        public String kind = "video"; // video / photo
        public String uri = "";    // 相册模式下的 MediaStore uri
        public String key = "";    // 去重用的唯一标识（原帖 + 清晰度）
        public boolean visible = true; // 是否在相册目录
        public long ts;
        /** 视频方向："v" 竖屏 / "h" 横屏 / "" 还没测过（图片恒为空） */
        public String orientation = "";
    }

    private static final String PREF = "downx_history";
    private static final String KEY = "items";
    private static final String STORE_PREF = "downx_store";
    /** 用户主动删掉的记录名，补扫描时不再"复活" */
    private static final String KEY_GONE = "gone";

    /** 新存储的位置（相对 filesDir），文件名见 {@link RecFile#NAME} */
    private static final String DIR = "history";
    /** 从老版本搬过来的原始 JSON 留一份，出问题能人工找回 */
    private static final String LEGACY_BAK = "legacy-v1.json";

    /**
     * 记录上限：纯保险，不参与日常裁剪。
     *
     * 原来是 60 —— 用户一次下 40 张多图就把 30 多个老视频挤出了列表（文件还在、
     * 列表空了，用户以为被删了）。下载器凭什么限制用户能记多少条？
     * 现在改成 5000 当兜底，且 {@link RecRules#trim} 只在超过上限时才动手，
     * 动手时也先淘汰「文件已经不在了」的记录，活记录一条都不碰。
     */
    static final int MAX = 5000;

    /** 行数超过 记录数×3+200 就压缩一次（同一记录被更新过多次时才会触发） */
    private static final int COMPACT_SLACK = 200;

    private static final Object LOCK = new Object();
    /** 内存里的记录列表，最新的在前；null 表示还没加载 */
    private static List<Entry> cache;
    /** 日志文件里当前有多少行（含同一记录的多个版本），用于判断该不该压缩 */
    private static int cacheLines;
    /** cache 对应的目录，换了 Context/目录就重新加载 */
    private static String cacheDir;

    // ---------------------------------------------------------------- 读

    public static List<Entry> list(Context ctx) {
        synchronized (LOCK) {
            ensure(ctx);
            return new ArrayList<>(cache);   // 给调用方一份副本，随便改结构，别动 cache
        }
    }

    /** 内存里找不到才真正读文件；一次加载后长期复用 */
    private static void ensure(Context ctx) {
        File dir = dirOf(ctx);
        if (cache != null && dir.getAbsolutePath().equals(cacheDir)) return;
        load(ctx, dir);
    }

    private static void load(Context ctx, File dir) {
        List<Entry> fileOrder = new ArrayList<>();   // 文件顺序：旧 → 新
        List<String> raw = new ArrayList<>();
        int lines = RecFile.read(dir, raw);
        boolean migrated = false;

        if (lines >= 0) {
            for (String line : raw) {
                Entry e = fromLine(line);
                if (e != null) fileOrder.add(e);   // 坏行只丢这一行，不整份报废
            }
        } else {
            lines = 0;
            // 首次升级到新格式：把老 SharedPreferences 里的 JSON 数组搬过来
            List<Entry> old = legacy(ctx);           // 老数组是"新在前"
            for (int i = old.size() - 1; i >= 0; i--) fileOrder.add(old.get(i));
            migrated = true;
        }

        cache = mergeCache(fileOrder);
        cacheDir = dir.getAbsolutePath();
        cacheLines = lines;

        if (migrated) {
            rewrite(ctx);      // 落成新格式（老数据为空时也会建一个空文件，下次就不再走迁移分支）
            clearLegacy(ctx);  // 老 JSON 已经备份成文件，没必要再占着 SharedPreferences
            cacheLines = cache.size();
        }
    }

    private static List<Entry> mergeCache(List<Entry> fileOrder) {
        return RecLog.merge(fileOrder, e -> (e.name == null ? "" : e.name) + "\u0000" + e.ts);
    }

    // ---------------------------------------------------------------- 写

    public static void add(Context ctx, Entry e) {
        synchronized (LOCK) {
            ensure(ctx);
            cache.add(0, e);
            // 这个文件名又被下载了一次：从"用户删过"的名单里拿掉，别再被当成删过的
            forget(ctx, e.name);

            int before = cache.size();
            RecRules.trim(cache, MAX, x -> fileAlive(ctx, x));
            if (cache.size() != before) {
                // 清掉了失效记录：文件里那些行也得去掉，只能整体重写
                rewrite(ctx);
            } else if (!append(ctx, e)) {
                rewrite(ctx);   // 追加失败（文件被删/无权限）：全量重写兜底
            } else {
                maybeCompact(ctx);
            }
        }
    }

    public static void saveAll(Context ctx, List<Entry> all) {
        synchronized (LOCK) {
            cache = new ArrayList<>(all);
            cacheDir = dirOf(ctx).getAbsolutePath();
            rewrite(ctx);
        }
    }

    public static void remove(Context ctx, Entry e) {
        synchronized (LOCK) {
            ensure(ctx);
            for (int i = 0; i < cache.size(); i++) {
                Entry x = cache.get(i);
                if (x.name != null && x.name.equals(e.name) && x.ts == e.ts) {
                    cache.remove(i);
                    break;
                }
            }
            // 这是用户明确删的：记一笔，别让启动补扫描把它又捞回来
            bury(ctx, e.name);
            rewrite(ctx);
        }
    }

    public static void clear(Context ctx) {
        synchronized (LOCK) {
            ensure(ctx);
            buryAll(ctx, cache);   // 一次写完，别一条一条地写 SharedPreferences（5000 条就是 5000 次全量写）
            cache = new ArrayList<>();
            rewrite(ctx);
        }
    }

    /**
     * 补记视频方向（"v"/"h"），只更新匹配的那一条。
     * 只追加一行最新状态（O(1)），不重写整个文件 —— 下载完一批视频会连着调很多次。
     */
    public static void updateOrientation(Context ctx, String name, long ts, String o) {
        if (o == null || o.isEmpty()) return;
        synchronized (LOCK) {
            ensure(ctx);
            for (Entry e : cache) {
                if (e.name != null && e.name.equals(name) && e.ts == ts) {
                    if (o.equals(e.orientation)) return;
                    e.orientation = o;
                    if (!append(ctx, e)) rewrite(ctx);
                    else maybeCompact(ctx);
                    return;
                }
            }
            // 找不到就不写：免得把一条"已经没有的记录"凭空追加回文件里
        }
    }

    /**
     * 清掉重复记录：同一帖同位置下过两遍时，列表里会出现两条一模一样的。
     * 以 key（原帖 + 清晰度）为主判重，其次按文件名（忽略「多图/」目录前缀）。
     * 只删记录，不动文件——重复条目的文件名相同，删文件会连保留的那条一起毁掉。
     */
    public static int dedupe(Context ctx) {
        synchronized (LOCK) {
            ensure(ctx);
            List<Entry> keep = RecRules.dedupe(cache,
                    e -> e.key == null ? "" : e.key,
                    e -> Store.stripAlbum(e.name) + "|" + (e.kind == null ? "" : e.kind));
            int removed = cache.size() - keep.size();
            if (removed > 0) {
                cache = new ArrayList<>(keep);
                rewrite(ctx);
            }
            return removed;
        }
    }

    // ---------------------------------------------------------------- 文件

    private static File dirOf(Context ctx) {
        File d = new File(ctx.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 追加一行。返回 false 表示这次没写成，调用方要整体重写兜底。 */
    private static boolean append(Context ctx, Entry e) {
        File dir = dirOf(ctx);
        if (!RecFile.append(dir, toLine(e))) {
            // 文件不在了（被清理、被写坏）或写失败：只写这一条会让其余记录凭空消失，
            // 必须整体重写兜底
            rewrite(ctx);
            return true;
        }
        cacheLines++;
        return true;
    }

    /** 整体重写：先写 tmp，再原子替换，避免写一半断电把整份记录毁掉。 */
    private static void rewrite(Context ctx) {
        List<String> lines = new ArrayList<>(cache.size());
        for (int i = cache.size() - 1; i >= 0; i--) {   // 文件顺序 = 旧 → 新
            lines.add(toLine(cache.get(i)));
        }
        if (RecFile.rewrite(dirOf(ctx), lines)) cacheLines = cache.size();
    }

    private static void maybeCompact(Context ctx) {
        if (cacheLines > cache.size() * 3 + COMPACT_SLACK) rewrite(ctx);
    }

    private static String toLine(Entry e) {
        String[] f = new String[RecLog.FIELDS];
        f[0] = e.name;
        f[1] = e.sub;
        f[2] = e.pageUrl;
        f[3] = e.thumb;
        f[4] = e.kind;
        f[5] = e.uri;
        f[6] = e.key;
        f[7] = e.visible ? "1" : "0";
        f[8] = Long.toString(e.ts);
        f[9] = e.orientation;
        return RecLog.encode(f);
    }

    private static Entry fromLine(String line) {
        String[] f = RecLog.decode(line);
        if (f == null) return null;
        Entry e = new Entry();
        e.name = f[0];
        e.sub = f[1];
        e.pageUrl = f[2];
        e.thumb = f[3];
        e.kind = f[4].isEmpty() ? "video" : f[4];
        e.uri = f[5];
        e.key = f[6];
        e.visible = "1".equals(f[7]);
        try {
            e.ts = Long.parseLong(f[8]);
        } catch (Throwable ignored) {
            e.ts = 0;
        }
        e.orientation = f[9];
        if (e.name.isEmpty()) return null;   // 没名字的记录没法用，丢掉
        return e;
    }

    // ---------------------------------------------------------------- 老数据迁移

    /** 读老版本存在 SharedPreferences 里的 JSON 数组（新在前），并把原文备份成文件 */
    private static List<Entry> legacy(Context ctx) {
        List<Entry> out = new ArrayList<>();
        String raw = null;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            raw = sp.getString(KEY, "[]");
            if (raw == null || raw.trim().isEmpty() || "[]".equals(raw.trim())) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.name = o.optString("name", o.optString("title", ""));
                e.sub = o.optString("sub");
                e.pageUrl = o.optString("pageUrl");
                e.thumb = o.optString("thumb");
                e.kind = o.optString("kind", "video");
                e.uri = o.optString("uri");
                e.key = o.optString("key");
                e.visible = o.optBoolean("visible", true);
                e.ts = o.optLong("ts");
                e.orientation = o.optString("orientation");
                if (!e.name.isEmpty()) out.add(e);
            }
        } catch (Throwable ignored) {
        }
        backupLegacy(ctx, raw);
        return out;
    }

    private static void backupLegacy(Context ctx, String raw) {
        if (raw == null || raw.length() < 3) return;
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(new File(dirOf(ctx), LEGACY_BAK));
            fo.write(raw.getBytes("UTF-8"));
            fo.flush();
        } catch (Throwable ignored) {
        } finally {
            close(fo);
        }
    }

    private static void clearLegacy(Context ctx) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().remove(KEY).apply();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 杂项

    /** 记录指向的文件还在不在（判不了就当活着：宁可留着记录，也别误删） */
    private static boolean fileAlive(Context ctx, Entry e) {
        try {
            if (e == null || e.name == null || e.name.isEmpty()) return false;
            File f = e.visible
                    ? Store.publicFile(e.kind, e.name)
                    : Store.privateFile(ctx, e.kind, e.name);
            return f != null && f.exists();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 用户主动删掉的记录名（含清空列表）：补扫描时跳过，避免"删了又回来" */
    public static void bury(Context ctx, String name) {
        String n = Store.stripAlbum(name);
        if (n.isEmpty()) return;
        SharedPreferences sp = ctx.getSharedPreferences(STORE_PREF, Context.MODE_PRIVATE);
        Set<String> cur = sp.getStringSet(KEY_GONE, new HashSet<String>());
        if (cur.contains(n)) return;                  // 已经在名单里：不用再写一次
        Set<String> s = new HashSet<>(cur);           // getStringSet 返回的集合不能直接改
        if (s.add(n)) sp.edit().putStringSet(KEY_GONE, s).apply();
    }

    /** 批量记入"已删除名单"：读一次、写一次 */
    private static void buryAll(Context ctx, List<Entry> all) {
        if (all == null || all.isEmpty()) return;
        SharedPreferences sp = ctx.getSharedPreferences(STORE_PREF, Context.MODE_PRIVATE);
        Set<String> s = new HashSet<>(sp.getStringSet(KEY_GONE, new HashSet<String>()));
        int before = s.size();
        for (Entry e : all) {
            if (e == null) continue;
            String n = Store.stripAlbum(e.name);
            if (!n.isEmpty()) s.add(n);
        }
        if (s.size() != before) sp.edit().putStringSet(KEY_GONE, s).apply();
    }

    public static void forget(Context ctx, String name) {
        String n = Store.stripAlbum(name);
        if (n.isEmpty()) return;
        SharedPreferences sp = ctx.getSharedPreferences(STORE_PREF, Context.MODE_PRIVATE);
        Set<String> cur = sp.getStringSet(KEY_GONE, new HashSet<String>());
        if (!cur.contains(n)) return;                 // 绝大多数情况：没删过，什么都不用做
        Set<String> s = new HashSet<>(cur);
        if (s.remove(n)) sp.edit().putStringSet(KEY_GONE, s).apply();
    }

    /**
     * 清空"用户删过的名单"。
     * 只有「重新扫描相册」会调用：等于允许那些删掉的记录再补回来一次
     * （文件不在了就补不回来，所以不会凭空造记录）。
     */
    public static void clearBuried(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(STORE_PREF, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_GONE).apply();
    }

    public static boolean isBuried(Context ctx, String name) {
        String n = Store.stripAlbum(name);
        if (n.isEmpty()) return false;
        return ctx.getSharedPreferences(STORE_PREF, Context.MODE_PRIVATE)
                .getStringSet(KEY_GONE, new HashSet<String>()).contains(n);
    }

    public static String fmtTs(long ts) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ts));
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
