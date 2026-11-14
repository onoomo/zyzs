package com.downx.app;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.List;

/**
 * 记录日志文件的读写：纯 Java，只认 {@link File}，主机验证台能跑真实文件 IO（tests/history）。
 *
 * <p>为什么要单独一层：编解码（{@link RecLog}）和落盘（这一层）都要在主机上验过才敢装机。
 * 落盘最容易出事的是"写到一半"：所以整体重写一律走 {@code tmp → rename} 原子替换，
 * 中途断电最多丢掉这次改动，绝不会留下半份记录。
 */
class RecFile {

    static final String NAME = "items.log";
    private static final String TMP = NAME + ".tmp";
    private static final String BAK = NAME + ".bak";

    private RecFile() { }

    static boolean ensureDir(File dir) {
        if (dir == null) return false;
        if (dir.isDirectory()) return true;
        return dir.mkdirs() || dir.isDirectory();
    }

    /**
     * 读所有行，按文件顺序（旧 → 新）放进 out。
     *
     * @return 读到的行数；文件不存在返回 -1（调用方据此判断要不要走老数据迁移）
     */
    static int read(File dir, List<String> out) {
        File f = new File(dir, NAME);
        if (!f.isFile()) {
            // 上次整体重写在"改名成备份"和"临时文件改名"之间断了：主文件不在，但备份是完整的旧版本。
            // 不恢复的话，这里会返回 -1，被当成"全新安装"，记录就全没了。
            File bak = new File(dir, BAK);
            if (bak.isFile()) bak.renameTo(f);
        }
        if (!f.isFile()) return -1;
        BufferedReader r = null;
        int n = 0;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1 << 16);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                out.add(line);
                n++;
            }
        } catch (Throwable t) {
            // 读到一半失败：把已经读到的当作全部（总比整份丢掉强），调用方之后会整体重写
            return n;
        } finally {
            close(r);
        }
        return n;
    }

    /**
     * 追加一行。文件不存在返回 false —— 这时只写这一行会让其余记录凭空消失，
     * 必须由调用方改成整体重写。
     */
    static boolean append(File dir, String line) {
        if (!ensureDir(dir)) return false;
        File f = new File(dir, NAME);
        if (!f.isFile()) return false;
        FileOutputStream fo = null;
        try {
            byte[] b = (line + "\n").getBytes("UTF-8");
            fo = new FileOutputStream(f, true);
            fo.write(b);
            fo.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            close(fo);
        }
    }

    /**
     * 整体重写。lines 必须已经按"旧 → 新"排好。
     *
     * <p>步骤：写 tmp → 原文件改名成 bak → tmp 改名成正式 → 删 bak。
     * 任何一步失败都尽量把原文件换回去，宁可这次白写，也不能让记录凭空少一半。
     */
    static boolean rewrite(File dir, List<String> lines) {
        if (!ensureDir(dir)) return false;
        File f = new File(dir, NAME);
        File tmp = new File(dir, TMP);
        File bak = new File(dir, BAK);
        BufferedWriter w = null;
        try {
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"), 1 << 16);
            if (lines != null) {
                for (String line : lines) {
                    if (line == null || line.isEmpty()) continue;
                    w.write(line);
                    w.write("\n");
                }
            }
            w.flush();
            w.close();
            w = null;

            if (bak.exists()) bak.delete();
            if (f.exists() && !f.renameTo(bak)) {
                // 备份失败就别动原文件了，直接覆盖 tmp 重来风险更大
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(f)) {
                if (bak.exists()) bak.renameTo(f);   // 换名失败：把原文件换回去
                tmp.delete();
                return false;
            }
            bak.delete();
            return true;
        } catch (Throwable t) {
            tmp.delete();
            if (!f.exists() && bak.exists()) bak.renameTo(f);
            return false;
        } finally {
            close(w);
        }
    }

    private static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
