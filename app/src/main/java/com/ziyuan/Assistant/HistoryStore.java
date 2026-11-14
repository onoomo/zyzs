package com.ziyuan.Assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;

/** 历史记录持久化：完成后进 SharedPreferences，下载页 tab 显示 */
public class HistoryStore {

    public static class Item {
        public String type;   // "视频" / "录制"
        public String title;
        public String path;   // 播放地址（file:// 或 content://）
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("dl_history", Context.MODE_PRIVATE);
    }

    /** 历史条目数(供下载页按需刷新: 仅数量变化才重新解析) */
    public static int count(Context c) {
        try { return sp(c).getInt("count", 0); } catch (Throwable ignored) { return 0; }
    }

    public static void add(Context c, String type, String title, String path) {
        try { com.downx.app.ZyBridge.add(c, type, title, path); } catch (Throwable ignored) {}
        try {
            SharedPreferences s = sp(c);
            int n = s.getInt("count", 0);
            SharedPreferences.Editor e = s.edit();
            e.putString("t" + n, type + "\u0001" + title + "\u0001" + path);
            e.putInt("count", n + 1);
            e.apply();
        } catch (Throwable ignored) {}
    }

    public static void clearAll(Context c) {
        try { sp(c).edit().clear().apply(); } catch (Throwable ignored) {}
    }

    /** 新→旧 */
    public static ArrayList<Item> load(Context c) {
        ArrayList<Item> out = new ArrayList<Item>();
        try {
            SharedPreferences s = sp(c);
            int n = s.getInt("count", 0);
            for (int i = n - 1; i >= 0; i--) {
                String raw = s.getString("t" + i, null);
                if (raw == null) continue;
                String[] p = raw.split("\u0001");
                if (p.length < 3) continue;
                Item it = new Item();
                it.type = p[0]; it.title = p[1]; it.path = p[2];
                out.add(it);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void removeAt(Context c, int index) {  // index 为 load 顺序（新→旧）
        try {
            SharedPreferences s = sp(c);
            int n = s.getInt("count", 0);
            int real = n - 1 - index;
            SharedPreferences.Editor e = s.edit();
            for (int i = real; i < n - 1; i++) {
                e.putString("t" + i, s.getString("t" + (i + 1), null));
            }
            e.remove("t" + (n - 1));
            e.putInt("count", Math.max(0, n - 1));
            e.apply();
        } catch (Throwable ignored) {}
    }
}
