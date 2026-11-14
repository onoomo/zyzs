package com.downx.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** 本机设置：应用锁、密码哈希、下载文件是否进相册 */
public class Prefs {

    private static final String F = "downx_settings";

    /** 本次前台是否已解锁（内存态，进程重启即失效） */
    public static boolean sessionUnlocked = false;

    /** 是否需要弹锁屏 */
    public static boolean needsLock(Context c) {
        return lockEnabled(c) && hasPin(c);
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(F, Context.MODE_PRIVATE);
    }

    // ---------------- 短视频嗅探（自学习） ----------------

    /**
     * 记住「哪一条候选页面能出流」。平台改版后第一次可能要试几条，
     * 试通之后就知道下次直接从这条开始，不用再一条条撞。
     */
    public static int svPage(Context c, String platform) {
        return sp(c).getInt("sv_page_" + platform, 0);
    }

    public static void setSvPage(Context c, String platform, int idx) {
        sp(c).edit().putInt("sv_page_" + platform, Math.max(0, idx)).apply();
    }

    /** 短链 → 作品 ID 的缓存，省掉每次那一次跳转（口令解析卡顿的主要来源之一） */
    public static String svId(Context c, String link) {
        return sp(c).getString("sv_id_" + Integer.toHexString(String.valueOf(link).hashCode()), "");
    }

    public static void setSvId(Context c, String link, String id) {
        if (id == null || id.isEmpty()) return;
        sp(c).edit().putString("sv_id_" + Integer.toHexString(String.valueOf(link).hashCode()), id).apply();
    }

    // ---------------- 应用锁 ----------------

    public static boolean lockEnabled(Context c) {
        return sp(c).getBoolean("lock", false);
    }

    public static void setLockEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("lock", v).apply();
    }

    public static boolean hasPin(Context c) {
        return !sp(c).getString("pin", "").isEmpty();
    }

    /** 用 PBKDF2 存哈希，不存明文 */
    public static void setPin(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        sp(c).edit()
                .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("pin", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
                .putInt("pinLen", pin == null ? 6 : pin.length())
                .apply();
    }

    /** 已设置的密码位数；老版本是 4 位，新设置的是 6 位 */
    public static int pinLength(Context c) {
        int n = sp(c).getInt("pinLen", 0);
        if (n >= 4 && n <= 8) return n;
        return 4;
    }

    public static boolean checkPin(Context c, String pin) {
        String stored = sp(c).getString("pin", "");
        String saltB64 = sp(c).getString("salt", "");
        if (stored.isEmpty() || saltB64.isEmpty()) return false;
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        return stored.equals(Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP));
    }

    public static void clearPin(Context c) {
        sp(c).edit().remove("pin").remove("salt").remove("pinLen").putBoolean("lock", false).apply();
    }

    /** 是否允许用指纹解锁；关掉后只能输密码 */
    public static boolean bioEnabled(Context c) {
        return sp(c).getBoolean("bio", true);
    }

    public static void setBioEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("bio", v).apply();
    }

    private static byte[] hash(String pin, byte[] salt) {
        try {
            KeySpec ks = new PBEKeySpec(pin.toCharArray(), salt, 12000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(ks).getEncoded();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    // ---------------- 首页排列样式 ----------------

    /** 竖版三列：一行 3 个，9:16，默认 */
    public static final int LAYOUT_GRID_3 = 0;
    /** 竖版四列：一行 4 个，紧凑 */
    public static final int LAYOUT_GRID_4 = 1;
    /** 竖版两列：一行 2 个，大图 */
    public static final int LAYOUT_GRID_2 = 2;
    /** 方形封面墙：一行 3 个，1:1 裁切 */
    public static final int LAYOUT_SQUARE = 3;
    /** 单列大卡：整行 16:9，底部带作者信息 */
    public static final int LAYOUT_WIDE = 4;
    /** 明细列表：左图右文 */
    public static final int LAYOUT_LIST = 5;
    /** 双列瀑布流：按原始比例高低错落 */
    public static final int LAYOUT_MASONRY = 6;
    /** 横滑大图：一张占大半屏，左右滑动像拉抽屉 */
    public static final int LAYOUT_DRAWER = 7;
    /** 竖横分区：上排竖屏视频，下排横屏视频，播放时左右滑只在同方向里切 */
    public static final int LAYOUT_SPLIT = 8;

    public static final int LAYOUT_COUNT = 9;

    public static int layoutMode(Context c) {
        // 新用户默认「竖横双抽屉」：上排竖屏、下排横屏，各一条横滑抽屉；
        // 老用户选过别的样式仍以他选的为准（sp 里有值就不会走到默认）。
        int v = sp(c).getInt("layout", LAYOUT_SPLIT);
        return (v < 0 || v >= LAYOUT_COUNT) ? LAYOUT_SPLIT : v;
    }

    public static void setLayoutMode(Context c, int v) {
        sp(c).edit().putInt("layout", v).apply();
    }

    // ---------------- 首页方向筛选 ----------------

    /** 只看竖屏 */
    public static final String DIR_VERT = "v";
    /** 只看横屏 */
    public static final String DIR_HORZ = "h";
    /** 全部（含图片和方向未知的） */
    public static final String DIR_ALL = "all";

    /**
     * 当前排列样式下要显示哪个方向。每种样式各记各的，
     * 互不干扰；「横滑抽屉」第一次进来默认竖屏，其余样式默认全部。
     */
    public static String dirFilter(Context c, int mode) {
        String def = mode == LAYOUT_DRAWER ? DIR_VERT : DIR_ALL;
        String v = sp(c).getString("dir_" + mode, def);
        if (!DIR_VERT.equals(v) && !DIR_HORZ.equals(v)) v = DIR_ALL;
        return v;
    }

    public static void setDirFilter(Context c, int mode, String f) {
        sp(c).edit().putString("dir_" + mode, f == null ? DIR_ALL : f).apply();
    }

    public static String layoutName(int mode) {
        switch (mode) {
            case LAYOUT_GRID_4: return "四列紧凑";
            case LAYOUT_GRID_2: return "两列大图";
            case LAYOUT_SQUARE: return "方形封面墙";
            case LAYOUT_WIDE: return "单列大卡";
            case LAYOUT_LIST: return "明细列表";
            case LAYOUT_MASONRY: return "双列瀑布流";
            case LAYOUT_DRAWER: return "横滑抽屉";
            case LAYOUT_SPLIT: return "竖横双抽屉";
            default: return "三列竖版";
        }
    }

    public static String layoutDesc(int mode) {
        switch (mode) {
            case LAYOUT_GRID_4: return "一行 4 个，一屏看到更多";
            case LAYOUT_GRID_2: return "一行 2 个，缩略图更清楚";
            case LAYOUT_SQUARE: return "一行 3 个正方形，图片不容易被裁";
            case LAYOUT_WIDE: return "每条占满一行，大画面 + 作者信息";
            case LAYOUT_LIST: return "左图右文，带时间和文件大小";
            case LAYOUT_MASONRY: return "两列高低错落，按原始比例显示";
            case LAYOUT_DRAWER: return "缩略图占大半屏，左右滑动一张张翻";
            case LAYOUT_SPLIT: return "上排竖屏、下排横屏，各是一条横滑抽屉，播放时只滑同方向";
            default: return "一行 3 个竖版，最像短视频列表";
        }
    }

    // ---------------- 相册可见 ----------------

    /** true = 保存到系统相册目录（Movies / Pictures 下的 DownX） */
    public static boolean galleryVisible(Context c) {
        return sp(c).getBoolean("gallery", true);
    }

    public static void setGalleryVisible(Context c, boolean v) {
        sp(c).edit().putBoolean("gallery", v).apply();
    }
}
