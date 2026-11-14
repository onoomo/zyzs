package com.downx.app;

import java.util.List;

/**
 * 把 YouTube 的拒绝原因翻译成用户看得懂的话：纯 Java，不碰 Android，主机验证台可跑（tests/yt）。
 *
 * <p>为什么需要这一层：原来不管什么原因，最后都是一句
 * "YouTube 没给出直链：可能需要人机验证/PO Token，或者代理没生效" ——
 * 用户根本分不清是「软件坏了」「代理没开」还是「这条视频本身不给下」。
 *
 * <p>实测（2026-11-09）三种情况差别很大：
 * <ul>
 *   <li>普通公开视频：五个客户端里至少有 iOS / ANDROID 能给几十条直链，正常下载；</li>
 *   <li>被判定需要人工确认的视频（涉敏/擦边/推广类常见）：全部 LOGIN_REQUIRED，
 *       重试 24 次、带 visitorData、换客户端、第三方实例全试过，稳定拿不到 —— 这是 YouTube 的账号级限制，
 *       第三方 App 没有登录态就下不了，重试也没用，必须如实告诉用户；</li>
 *   <li>视频被删/私有：UNPLAYABLE 或 ERROR，给的是另一套原因。</li>
 * </ul>
 * 所以提示必须按原因分开，别让用户白折腾。
 */
class YtDeny {

    private YtDeny() { }

    /**
     * @param title      视频标题（拿不到传空）
     * @param raw        每个客户端的拒绝记录，格式 "STATUS|reason"
     * @param anyResponse 是否至少有一个客户端正常返回了 JSON（区分"被拒"和"根本连不上"）
     */
    static String message(String title, List<String> raw, boolean anyResponse) {
        boolean login = false, unplayable = false, error = false;
        String reason = "";
        if (raw != null) {
            for (String s : raw) {
                if (s == null || s.isEmpty()) continue;
                String st = s;
                String rs = "";
                int bar = s.indexOf('|');
                if (bar >= 0) {
                    st = s.substring(0, bar);
                    rs = s.substring(bar + 1).trim();
                }
                if (reason.isEmpty() && !rs.isEmpty()) reason = rs;
                if ("LOGIN_REQUIRED".equals(st)) login = true;
                else if ("UNPLAYABLE".equals(st)
                        || "AGE_CHECK_REQUIRED".equals(st)
                        || "AGE_VERIFICATION_REQUIRED".equals(st)) unplayable = true;
                else if ("ERROR".equals(st)) error = true;
            }
        }

        String head = (title == null || title.trim().isEmpty()) ? "" : "《" + shortTitle(title.trim()) + "》";
        String why = cn(reason);

        if (login) {
            return head + "这条视频 YouTube 要求登录验证，没登录的第三方 App 拿不到直链"
                    + (why.isEmpty() ? "" : "（" + why + "）")
                    + "。换一条视频试试。";
        }
        if (unplayable) {
            return head + "这条视频不可播放" + (why.isEmpty() ? "" : "：" + why)
                    + "。可能是私有、已删除，或者限制了地区。";
        }
        if (error) {
            return head + "YouTube 拒绝提供这条视频" + (why.isEmpty() ? "" : "：" + why) + "。";
        }
        if (!anyResponse) {
            return "连不上 YouTube。国内需要代理，请确认代理已连接、并且是全局模式后重试。";
        }
        return "YouTube 这次没给出可用的视频格式，稍后再试一次；一直这样就把链接发我。";
    }

    /** 常见英文原因翻成中文；没见过的保留原文，总比什么都不说强 */
    static String cn(String r) {
        if (r == null || r.isEmpty()) return "";
        String s = r.toLowerCase().replace('\u2019', '\'').replace('\u2018', '\'').trim();
        if (s.contains("not a bot")) return "YouTube 判定当前网络像机器人";
        if (s.contains("confirm your age") || s.contains("age")) return "需要确认年龄";
        if (s.contains("private")) return "私有视频";
        if (s.contains("removed") || s.contains("deleted")) return "视频已被删除";
        if (s.contains("not available in your country") || s.contains("country")) return "在你所在地区不可播放";
        if (s.contains("unavailable") || s.contains("not available")) return "视频不可用";
        if (s.contains("no longer supported")) return "这个客户端版本已被 YouTube 停用";
        if (s.contains("members-only") || s.contains("members only")) return "会员专属视频";
        return r.length() > 60 ? r.substring(0, 60) + "…" : r;
    }

    private static String shortTitle(String t) {
        return t.length() <= 24 ? t : t.substring(0, 24) + "…";
    }
}
