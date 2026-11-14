package com.downx.app;

/**
 * 下载并发名额：视频独占一条，图片最多 n 条并行。
 *
 * 为什么单独抽出来：这类「占位/释放」的计数最容易漏一条路径，
 * 而漏掉的后果是下载功能整个哑掉（永远提示"还有一条正在下载"），
 * 在手机上根本没法排查。抽成纯 Java 就能在主机上跑几万次随机授权/释放，
 * 直接验证「不超限、不泄漏、视频不与图片混跑」。
 */
final class Slots {

    private final int maxPhoto;
    private int active;
    private int photos;

    Slots(int maxPhoto) {
        this.maxPhoto = maxPhoto;
    }

    /**
     * 申请一个名额。
     *
     * @param photo 是不是图片任务
     * @return 申请**之前**的在跑数量（0 表示这是新的一批，调用方据此清零批次计数）；
     *         -1 表示拒绝（视频在跑 / 图片已满 / 图片想插进视频里）
     */
    synchronized int admit(boolean photo) {
        if (active == 0) {
            active = 1;
            photos = photo ? 1 : 0;
            return 0;
        }
        if (!photo) return -1;              // 视频：一条就够，绝不并行
        if (photos != active) return -1;    // 里面已经有视频了
        if (active >= maxPhoto) return -1;  // 图片到上限
        int before = active;
        active++;
        photos++;
        return before;
    }

    /** 释放一个名额；多放不会把计数减成负数（异常路径重复 release 也不至于把状态搞坏） */
    synchronized void release(boolean photo) {
        if (active > 0) active--;
        if (photo && photos > 0) photos--;
    }

    synchronized int active() {
        return active;
    }

    synchronized int photos() {
        return photos;
    }
}
