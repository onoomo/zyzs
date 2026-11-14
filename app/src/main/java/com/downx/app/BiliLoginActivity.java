package com.downx.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 在应用内登录 B 站（官方网页，支持扫码）。登录成功后把 Cookie 存在本机，
 * 下载高清视频（1080P 及以上）时带上它。
 */
public class BiliLoginActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ThemeMode.apply(base));
    }

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean finished;
    private int ticks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_bg));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/124.0.0.0 Mobile Safari/537.36");
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        WebViewClient client = new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                check();
            }
        };
        web.setWebViewClient(client);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_card));
        bar.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("登录 B 站");
        title.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_text));
        title.setTextSize(15);
        bar.addView(title);

        TextView tip = new TextView(this);
        tip.setText("推荐用「扫码登录」最方便。账号密码只在 B 站官方页面输入，本应用不保存密码。"
                + "登录后未登录只能下 720P 的限制就解开了。");
        tip.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_dim));
        tip.setTextSize(11);
        tip.setPadding(0, dp(4), 0, dp(8));
        bar.addView(tip);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        bar.addView(pb);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP;
        root.addView(bar, blp);

        web.loadUrl("https://passport.bilibili.com/h5-app/passport/login");
        setContentView(root);
        Immersive.apply(this);

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished) return;
                check();
                ticks++;
                if (ticks > 1200) { // 约 10 分钟，扫码从容一点
                    finish();
                } else {
                    main.postDelayed(this, 500);
                }
            }
        }, 800);
    }

    private void check() {
        if (finished) return;
        try {
            String cookie = CookieManager.getInstance().getCookie("https://www.bilibili.com");
            if (cookie != null && cookie.contains("SESSDATA=")) {
                finished = true;
                CookieManager.getInstance().flush();
                BiliSession.save(this, cookie);
                Toast.makeText(this, "B 站登录成功，高清下载已解锁", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                main.postDelayed(this::finish, 600);
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack() && !finished) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
