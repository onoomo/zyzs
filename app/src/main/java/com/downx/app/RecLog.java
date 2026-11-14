package com.downx.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 下载记录日志的编解码与合并：纯 Java，不碰 Android，主机验证台可直接跑（tests/history）。
 *
 * <p>为什么要有这一层：记录原来存在 SharedPreferences 的一个 JSON 字符串里，
 * 每加一条都要把整个数组重新序列化再写一遍（O(n) 写放大）。下 1 万条时，
 * 单次 add 要重写几 MB，累计写盘二十多 GB —— 不丢数据，但会卡、伤闪存。
 *
 * <p>现在改成「一行一条、只追加」的日志：
 * <ul>
 *   <li>新增记录 = 在文件尾追加一行，O(1)；</li>
 *   <li>改一条记录（比如补上竖屏/横屏标记）= 再追加一行它的最新状态，同样 O(1)；</li>
 *   <li>加载时同一 key 后写覆盖先写（last-write-wins），位置仍按它第一次出现的先后，</li>
 * </ul>
 * 所以文件里会同时存在同一条记录的多个版本，这是设计如此：合并由 {@link #merge} 负责。
 * 行数膨胀到记录数的 3 倍时会整体压缩一次（低频）。
 *
 * <p>行格式（制表符分隔，字段里的 \ \t \n \r 全部转义，所以一行永远是真正的一行）：
 * <pre>
 *   1 \t name \t sub \t pageUrl \t thumb \t kind \t uri \t key \t visible(1/0) \t ts \t orientation
 * </pre>
 * 首字段是格式版本号。以后加字段就往后排，旧版本读新行时多出来的字段直接忽略。
 */
class RecLog {

    /** 每行的字段数（不含行首版本号） */
    static final int FIELDS = 10;

    /** 行首版本号 */
    static final char VERSION = '1';

    private RecLog() { }

    /** 把字段数组编成一行（不含换行符）。null 字段按空串处理。 */
    static String encode(String[] f) {
        StringBuilder b = new StringBuilder(256);
        b.append(VERSION);
        for (int i = 0; i < FIELDS; i++) {
            b.append('\t');
            escape(b, f != null && i < f.length ? f[i] : null);
        }
        return b.toString();
    }

    /**
     * 把一行解回字段数组。坏行返回 null（调用方跳过这一行就行，不要因为一行坏了丢掉整份记录）。
     * 返回值长度恒为 {@link #FIELDS}，且每个元素都不是 null。
     */
    static String[] decode(String line) {
        if (line == null || line.length() < 2) return null;
        if (line.charAt(0) != VERSION || line.charAt(1) != '\t') return null;
        String[] raw = line.substring(2).split("\t", -1);
        if (raw.length < FIELDS) return null;
        String[] out = new String[FIELDS];
        for (int i = 0; i < FIELDS; i++) out[i] = unescape(raw[i]);
        return out;
    }

    /**
     * 合并日志：
     *
     * @param fileOrder 按文件顺序（旧 → 新）读出来的记录
     * @param keyOf     一条记录的唯一标识（同一 key 视为同一条记录的不同版本）
     * @return 记录列表，最新的在前；同 key 取最后出现的那个版本，位置按它第一次出现的位置
     */
    static <T> List<T> merge(List<T> fileOrder, Function<T, String> keyOf) {
        Map<String, T> map = new LinkedHashMap<>();
        if (fileOrder != null) {
            for (T e : fileOrder) {
                if (e == null) continue;
                String k = keyOf == null ? "" : keyOf.apply(e);
                map.put(k == null ? "" : k, e);   // 已存在：替换值、保留首次出现的位置
            }
        }
        List<T> out = new ArrayList<>(map.values());
        Collections.reverse(out);                 // 文件是 旧→新，列表要 新→前
        return out;
    }

    // ---------------- 转义 ----------------

    private static void escape(StringBuilder b, String s) {
        if (s == null) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '\t': b.append("\\t"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                default: b.append(c);        // emoji 的代理对逐 char 追加，UTF-8 写出时仍是对的
            }
        }
    }

    private static String unescape(String s) {
        if (s == null) return "";
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i + 1 >= s.length()) break;      // 末尾孤立的反斜杠：丢掉，不要把它带进文件名
            char n = s.charAt(++i);
            if (n == 't') b.append('\t');
            else if (n == 'n') b.append('\n');
            else if (n == 'r') b.append('\r');
            else if (n == '\\') b.append('\\');
            else {                               // 不认识的转义：原样保留（手工改文件时不至于吃掉字符）
                b.append('\\');
                b.append(n);
            }
        }
        return b.toString();
    }
}
