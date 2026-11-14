package com.downx.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 下载记录的裁剪规则：纯逻辑、不碰 Android，主机验证台能直接跑（tests/history）。
 *
 * 这里放的是最容易闯祸的两件事 ——「超出上限怎么淘汰」和「重复记录怎么去」。
 * 教训（v1.3.4 当天）：上限原来是 60，`add()` 一律砍尾巴。用户一次下载 40 张多图，
 * 40 条新记录把 30 多个老视频记录生生挤掉了，文件还在、列表里却没了 ——
 * 用户第一反应就是「我下载的视频怎么都没了」。
 * 所以：上限要提高，超限时优先淘汰文件已经不在了的，绝不能无脑砍。
 */
class RecRules {

    /**
     * 清理失效记录。
     *
     * 语义在 v1.3.5 改过一次，务必看清：
     * - 老版本是「超过 max 就砍尾巴」——一次下 40 张多图，30 多个老视频记录被顶掉，
     *   文件还在、列表却空了，用户以为文件被删了。**活记录绝不能这样删**。
     * - 现在 max 只是"什么时候去清失效记录"的触发线：超过它时把文件已经不在的记录清掉；
     *   清完还超就超着，一条活记录都不动（宁可慢，也不能让用户的记录凭空消失）。
     *
     * alive 判定失败时应返回 true（判不了就当活着）。
     */
    static <T> void trim(List<T> all, int max, Predicate<T> alive) {
        if (all == null || all.size() <= max) return;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (!alive.test(all.get(i))) all.remove(i);
        }
    }

    /**
     * 同名 / 同 key 只留最新那一条（列表约定：最新的在前）。
     * key 为空的条目只按名字判重。
     */
    static <T> List<T> dedupe(List<T> all, Function<T, String> keyOf, Function<T, String> nameOf) {
        Set<String> seenKey = new HashSet<>();
        Set<String> seenName = new HashSet<>();
        List<T> keep = new ArrayList<>();
        for (T e : all) {
            String k = keyOf.apply(e);
            String n = nameOf.apply(e);
            if (k != null && !k.isEmpty() && seenKey.contains(k)) continue;
            if (seenName.contains(n)) continue;
            if (k != null && !k.isEmpty()) seenKey.add(k);
            seenName.add(n);
            keep.add(e);
        }
        return keep;
    }
}
