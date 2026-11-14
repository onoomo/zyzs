package com.downx.app;

// 本工程 namespace 为 com.example.immersive，且 nonTransitiveRClass=true，需显式 import
import com.example.immersive.R;

import android.app.Activity;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 使用说明：给第一次上手的人看的简版手册。
 * 内容在代码里拼，改文案不用动布局。
 */
public class HelpActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ThemeMode.apply(base));
    }

    private LinearLayout body;
    private boolean firstSection = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_help);
        Immersive.apply(this);
        body = findViewById(R.id.helpBody);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        build();
        addVersion();
    }

    private void build() {
        addNote("复制链接 → 粘贴 → 挑画质下载，存进相册。不用注册；"
                + "B 站和 Instagram 在设置里登录后能解锁更多清晰度。");

        addSection("国内平台");
        addSub("短视频");
        addCard(
                new String[]{"抖音", "无水印原片"},
                new String[]{"快手", "无水印原片"},
                new String[]{"小红书", "无水印视频 + 图集原图"}
        );
        addSub("长视频");
        addCard(
                new String[]{"B 站（哔哩哔哩）", "不登录 720P；登录后 1080P，大会员可到 4K 原画"}
        );

        addSection("国外平台 · 需要开代理");
        addSub("短视频");
        addCard(
                new String[]{"TikTok", "无水印原片（部分网络会被风控）"},
                new String[]{"Instagram", "帖子 / Reels；登录后可列 1280P / 640P 多档"},
                new String[]{"X（Twitter）", "原始画质，多档可选"},
                new String[]{"Facebook", "公开视频"}
        );
        addSub("长视频");
        addCard(
                new String[]{"YouTube（暂时不可用）",
                        "YouTube 现在要求登录验证，第三方 App 拿不到直链；普通视频偶尔能下，可以试"}
        );
        addSub("图片");
        addCard(
                new String[]{"Pinterest", "公开图片原图"}
        );

        addNote("下载中可随时暂停或取消；失败了重新点下载会从头开始"
                + "（半成品自动清掉，不占空间）。链接失效导致失败时，回原 App 重新复制一次即可。");
        addNote("国外平台连不上时先确认代理已开；下国内平台建议关掉代理，走直连更快。");
    }

    // ---------------- 拼装 ----------------

    private void addSection(String title) {
        TextView t = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(firstSection ? 20 : 24);
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        t.setText(title);
        t.setTextColor(ContextCompat.getColor(this, R.color.set_faint));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        t.setLetterSpacing(0.08f);
        body.addView(t);
        firstSection = false;
    }

    /** 分组小标题：短视频 / 长视频 / 图片，比正文小一号，一眼分得清 */
    private void addSub(String title) {
        TextView t = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        t.setText(title);
        t.setTextColor(ContextCompat.getColor(this, R.color.set_dim));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        body.addView(t);
    }

    /** 一张卡片，每一条是 {标题, 说明} */
    private void addCard(String[]... items) {
        LinearLayout card = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);

        for (int i = 0; i < items.length; i++) {
            if (i > 0) card.addView(divider());
            card.addView(row(items[i][0], items[i][1]));
        }
        body.addView(card);
    }

    private View row(String title, String desc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ContextCompat.getColor(this, R.color.set_text));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        box.addView(t);

        if (desc != null && !desc.isEmpty()) {
            TextView d = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(3);
            d.setLayoutParams(lp);
            d.setText(desc);
            d.setTextColor(ContextCompat.getColor(this, R.color.set_faint));
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            d.setLineSpacing(dp(3), 1f);
            box.addView(d);
        }
        return box;
    }

    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        v.setLayoutParams(lp);
        v.setBackgroundColor(ContextCompat.getColor(this, R.color.set_stroke));
        return v;
    }

    private void addNote(String text) {
        TextView t = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        t.setText(text);
        t.setTextColor(ContextCompat.getColor(this, R.color.set_dim));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setLineSpacing(dp(4), 1f);
        body.addView(t);
    }

    private void addVersion() {
        String v = "1.0";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            v = pi.versionName;
        } catch (Throwable ignored) { }
        TextView t = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(26);
        t.setLayoutParams(lp);
        t.setText("媒体管理 " + v + " · 使用说明与支持平台");
        t.setTextColor(ContextCompat.getColor(this, R.color.set_faint));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        t.setGravity(android.view.Gravity.CENTER);
        body.addView(t);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
