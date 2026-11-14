package com.downx.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 原版(com.ziyuan.Assistant)下载/录制引擎落库桥：
 * 原版 HistoryStore.add 的每个调用点（DlManager 下载、RecManager 直播录制）
 * 都会同步登记到本 App 两处：
 *   1. HistoryStore —— 「媒体管理」逐条文件管理页（FilesActivity）
 *   2. MainActivity 的下载任务列表（downx_demo/tasks，tabs 全部/视频/音频/任务/下载）
 * 这样两个媒体管理界面都能看到原版引擎下载/录制的文件，并可播放 / 删除。
 */
public final class ZyBridge {
    private static final String PREF = "downx_demo";
    private static final String KEY_TASKS = "tasks";

    private ZyBridge() {}

    public static void add(Context c, String type, String title, String path) {
        try {
            if (path == null || path.isEmpty()) return;
            HistoryStore.Entry e = new HistoryStore.Entry();
            String p = path.startsWith("file://") ? path.substring(7) : path;
            java.io.File f = new java.io.File(path.startsWith("file://") ? p : path);
            e.name = f.getName();
            if (e.name.isEmpty() || e.name.equals("/")) e.name = title;
            e.sub = "原版引擎·" + (type == null ? "视频" : type);
            e.kind = (type != null && (type.contains("图") || type.contains("照") || type.contains("photo"))) ? "photo" : "video";
            e.uri = path;   // content://(MediaStore) 或 file://(直通，见 Store.playUri)
            e.key = "zy:" + path;
            e.visible = false;
            e.ts = System.currentTimeMillis();
            HistoryStore.add(c, e);
            addTask(c, e.name);
        } catch (Throwable ignored) { }
    }

    /** 同步登记一条进首页「媒体管理」下载任务列表（原版引擎的文件也出现在那里） */
    private static void addTask(Context c, String fileName) {
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr;
            try { arr = new JSONArray(sp.getString(KEY_TASKS, "[]")); }
            catch (Throwable t) { arr = new JSONArray(); }
            // 去重：同名文件只登记一次
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && fileName.equals(o.optString("file"))) return;
            }
            String base = fileName;
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            String time = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            JSONObject o = new JSONObject();
            o.put("title", base);
            o.put("url", "");
            o.put("quality", "原画");
            o.put("category", "video");
            o.put("time", time);
            o.put("state", "已下载");
            o.put("file", fileName);
            JSONArray out = new JSONArray();
            out.put(o);
            for (int i = 0; i < arr.length() && i < 199; i++) out.put(arr.get(i));
            sp.edit().putString(KEY_TASKS, out.toString()).apply();
        } catch (Throwable ignored) { }
    }
}
