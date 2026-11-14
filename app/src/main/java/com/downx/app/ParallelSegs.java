package com.downx.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HLS 分片并行（滑动窗口）：N 个 worker 抓片，主线程**按清单顺序**一片一片合走。
 *
 * 为什么不直接把整段都下完再合并：那样磁盘峰值是「全部临时片 + 已合并的文件」≈ 2 倍。
 * 一条 1.5GB 的 HLS 就需要 3GB 空闲，手机上经常直接失败。
 * 这里把「已下载但还没被合走」的片数压在 ahead 以内，峰值 ≈ 成片 1 倍 + ahead 片。
 *
 * 为什么合流必须回到主线程：Mux.remux 靠每片字节数逐段 seek，顺序错一片就整条花掉；
 * 而且进度、segLens、断点续传的记账都在那边，交给 worker 并行写必然串位。
 *
 * 单独抽出来的原因：这套「窗口 + 失败即整体放弃 + 一片不落」的逻辑手机上无法自查，
 * 必须在主机上跑真分片比对哈希（tests/hlsp）。
 */
final class ParallelSegs {

    interface Fetcher {
        /**
         * 下载 url 到 dest。约定：**原子提交** —— 失败时 dest 不能存在或不能是半截，
         * 否则续传会把半截分片当成完整的拼进去。
         */
        long fetch(String url, File dest) throws Exception;
    }

    /** 按序消费一片；由调用方负责删除临时文件 */
    interface Sink {
        void accept(int index, File part) throws Exception;
    }

    private final int workers;

    ParallelSegs(int workers) {
        this.workers = Math.max(1, workers);
    }

    /**
     * @param from  从第几片开始（续传用，之前的片调用方已经合过了）
     * @param ahead 窗口深度：最多几片「下好了但还没被合走」
     */
    void runWindow(final List<String> segs, final int from, final File dir, final String prefix,
                   final int ahead, final Fetcher f, final Sink sink) throws Exception {
        final int n = segs.size();
        if (from >= n) return;
        // 只剩一两片，或者本来就要串行：开线程比下载还贵，直接顺序来（窗口退化成 1）
        if (workers == 1 || n - from < 3) {
            for (int i = from; i < n; i++) {
                File p = part(dir, prefix, i);
                f.fetch(segs.get(i), p);
                sink.accept(i, p);
            }
            return;
        }

        // 窗口至少要有 worker 那么多，否则 worker 会一直抢不到位、等于串行。
        // 上限 512：Semaphore 的许可数有上限（Integer.MAX_VALUE 会 overflow），
        // 而 512 片未消费已经远超任何真实场景，不必真去表达"无限"。
        final int win = Math.max(workers, Math.min(Math.max(1, ahead), 512));
        final Semaphore slots = new Semaphore(win);
        final AtomicInteger next = new AtomicInteger(from);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        final AtomicBoolean stop = new AtomicBoolean(false);
        final Object lock = new Object();
        final boolean[] ready = new boolean[n];

        List<Thread> ts = new ArrayList<>();
        int w = Math.min(workers, n - from);
        for (int k = 0; k < w; k++) {
            Thread t = new Thread(() -> {
                while (!stop.get() && err.get() == null) {
                    // 带超时地等窗口位：这样 stop/err 置起来时能立刻退出，
                    // 不会有一堆 worker 永远卡在 acquire 上（那就变成"取消后还在跑"）
                    boolean got;
                    try {
                        got = slots.tryAcquire(200, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!got) continue;
                    if (stop.get() || err.get() != null) {
                        slots.release();
                        return;
                    }
                    int i = next.getAndIncrement();
                    if (i >= n) {
                        slots.release();
                        return;
                    }
                    try {
                        f.fetch(segs.get(i), part(dir, prefix, i));
                        synchronized (lock) {
                            ready[i] = true;
                            lock.notifyAll();
                        }
                    } catch (Throwable e) {
                        err.compareAndSet(null, e);
                        slots.release();
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                        return;
                    }
                }
            }, "seg" + k);
            ts.add(t);
            t.start();
        }

        Throwable fail = null;
        try {
            for (int i = from; i < n; i++) {
                synchronized (lock) {
                    while (!ready[i] && err.get() == null && !stop.get()) {
                        lock.wait(300);
                    }
                }
                if (!ready[i]) {
                    Throwable e = err.get();
                    throw rethrow(e != null ? e
                            : new Exception("分片 " + (i + 1) + "/" + n + " 没有下完"));
                }
                sink.accept(i, part(dir, prefix, i));
                slots.release();        // 这一片合走了，窗口空出一格
            }
        } catch (Throwable e) {
            fail = e;
            err.compareAndSet(null, e);
        } finally {
            stop.set(true);
            // 把卡在窗口上的 worker 全部放开，让它们看到 stop 后退出
            slots.release(w + 1);
            for (Thread t : ts) {
                try {
                    t.join(3000);
                } catch (InterruptedException ignored) { }
            }
        }
        if (fail != null) throw rethrow(fail);
    }

    static File part(File dir, String prefix, int i) {
        return new File(dir, prefix + i + ".part");
    }

    /** 保留原始类型（Canceled 是 RuntimeException，不能被包成 Exception 后丢掉语义） */
    private static Exception rethrow(Throwable e) {
        if (e instanceof RuntimeException) throw (RuntimeException) e;
        if (e instanceof Error) throw (Error) e;
        return e instanceof Exception ? (Exception) e : new Exception(e);
    }
}
