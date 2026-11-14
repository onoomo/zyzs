package com.downx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Instagram 登录：App 内直接输账号密码（官方登录页，密码不经过本 App）。
 *
 * 2026-11-09 记要（别再退回"贴 Cookie"那套）：
 * · 之前判"IG 登录页加载不出来"是误判——对照组用了夸克浏览器，而夸克没走 VPN。
 *   小米系统浏览器同一时刻能完整打开 IG，说明网络与 IG 都没问题。
 * · 之前 WebView 版"按钮点了没反应"的真因是**没挂 WebChromeClient**：
 *   IG 登录页用 JS 弹窗/confirm 做流程控制，WebView 默认不实现 dialog，
 *   JS 线程直接卡住，表现就是整个页面点不动。本类补上了 WebChromeClient。
 * · UA 用移动版（和系统浏览器一致），页面排版正常，也避免被当成可疑设备。
 *
 * 登录成功的判定不看页面文字，只看 CookieManager 里是否出现 sessionid，
 * 再用 web_profile_info 校验一次（返回 "user" 才算真登录），然后存进 IgSession。
 */
public class IgLoginActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ThemeMode.apply(base));
    }

    private static final String TAG = "DownX-IGLOG";
    private static final String LOGIN_URL = "https://www.instagram.com/accounts/login/";
    /** 登录后能返回用户资料；匿名会 401，用它判定会话是否真的有效 */
    private static final String VERIFY =
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=instagram";

    private WebView web;
    private ProgressBar bar;
    private TextView tvHint;
    private Button btnClose;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean finishing;      // 已经拿到登录态，正在验证/收尾
    private boolean verified;
    private boolean polling;        // 轮询已启动，避免 onPageFinished 多次触发开出多条链
    private int ticks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        Immersive.apply(this);
        setup(web);
        // 上次可能已经登录过（WebView 的 Cookie 是持久的），先探一次
        String ck = cookies();
        if (IgSession.usable(ck)) {
            hint("检测到已登录的会话，正在确认…");
            verifyAndSave(ck);
        } else {
            web.loadUrl(LOGIN_URL);
        }
    }

    // ---------------------------------------------------------------- 界面

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_bg));

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(14), dp(10), dp(12));

        TextView title = new TextView(this);
        title.setText("登录 Instagram");
        title.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_text));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnClose = new Button(this);
        btnClose.setText("关闭");
        btnClose.setAllCaps(false);
        btnClose.setTextSize(13);
        btnClose.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_faint));
        btnClose.setBackgroundColor(Color.TRANSPARENT);
        btnClose.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        top.addView(btnClose);
        root.addView(top);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        tvHint = new TextView(this);
        tvHint.setText("在下面输入 Instagram 账号和密码，登录成功后会自动返回");
        tvHint.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_dim));
        tvHint.setTextSize(11.5f);
        tvHint.setLineSpacing(dp(3), 1f);
        tvHint.setPadding(dp(18), dp(8), dp(18), dp(8));
        root.addView(tvHint);

        FrameLayout box = new FrameLayout(this);
        web = new WebView(this);
        box.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("账号密码只提交给 Instagram 官方登录页，本 App 不读取也不保存。");
        foot.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_faint));
        foot.setTextSize(10.5f);
        foot.setPadding(dp(18), dp(8), dp(18), dp(12));
        root.addView(foot);

        return root;
    }

    // ---------------------------------------------------------------- WebView

    private void setup(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);          // IG 别开新窗口，否则回调丢失
        s.setUserAgentString(Net.UA);                // 与系统浏览器一致的移动 UA
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "page finished " + url);
                checkLogin();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return intercept(req.getUrl() == null ? "" : req.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return intercept(url == null ? "" : url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (err != null && err.getErrorCode() < 0) return;   // 子资源错误忽略
                if (req != null && req.isForMainFrame()) {
                    hint("打不开 Instagram（" + (err == null ? "网络错误" : err.getDescription())
                            + "）。请确认 VPN 已连接，并把 DownX 加入代理应用列表。");
                }
            }
        });

        // ★ 关键：没有 WebChromeClient，IG 登录页的 JS 弹窗会把按钮卡死
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                bar.setProgress(p);
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String msg, JsResult res) {
                res.confirm();          // 直接放行，避免阻塞登录流程
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String msg, JsResult res) {
                new AlertDialog.Builder(IgLoginActivity.this)
                        .setMessage(msg)
                        .setPositiveButton("确定", (d, x) -> res.confirm())
                        .setNegativeButton("取消", (d, x) -> res.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String msg,
                                      String def, android.webkit.JsPromptResult res) {
                res.confirm(def);
                return true;
            }
        });
    }

    /** 拦掉"用 App 打开"这类跳转，别把登录页甩到 IG App 或应用商店 */
    private boolean intercept(String url) {
        if (TextUtils.isEmpty(url)) return false;
        if (url.startsWith("intent://") || url.startsWith("market://")
                || url.contains("play.google.com") || url.contains("itunes.apple.com")) {
            return true;
        }
        // 站内跳转放行（登录后回首页、验证码页都靠它）
        return false;
    }

    // ---------------------------------------------------------------- 登录检测

    /** 和 B 站登录同一套：轮询 Cookie，出现 sessionid 即成功 */
    private void checkLogin() {
        if (polling || finishing || verified) return;   // onPageFinished 会重复触发，只留一条链
        polling = true;
        tick();
    }

    private void tick() {
        if (finishing || verified) {
            polling = false;
            return;
        }
        String ck = cookies();
        if (IgSession.usable(ck)) {
            hint("登录成功，正在确认会话…");
            verifyAndSave(ck);
            return;
        }
        if (ticks++ > 600) {                 // 约 8 分钟，够走两步验证
            polling = false;
            hint("还没检测到登录。输入账号密码并点「登录」后会自动返回；"
                    + "卡在加载就检查代理是否生效。");
            return;
        }
        main.postDelayed(this::tick, 800);
    }

    private String cookies() {
        try {
            String s = CookieManager.getInstance().getCookie("https://www.instagram.com");
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private void verifyAndSave(final String cookie) {
        if (verified) return;
        finishing = true;
        hint("正在验证登录状态…");
        new Thread(() -> {
            Net.Resp r = verify(cookie);
            boolean ok = r.code == 200 && r.body != null && r.body.contains("\"user\"");
            main.post(() -> {
                if (ok) {
                    done("登录成功，已保存登录状态。", "Instagram 登录成功");
                } else if (r.code == 401) {
                    // 401 = IG 明确说这个会话无效：可能还在验证码/两步验证页，继续等
                    finishing = false;
                    polling = false;
                    hint("登录还没完成，请按页面提示继续。");
                    main.postDelayed(this::checkLogin, 2500);
                } else {
                    // 429 / 网络抖动只说明验证接口暂时不通，不代表会话无效
                    //（实测 web_profile_info 对 IP 限流很严，429 很常见）。
                    // 有 sessionid 就先保存，下载时接口会自动续期并再次校验。
                    done("已保存登录状态（验证接口暂时被限流，不影响使用）。", "Instagram 已登录");
                }
            });
        }, "ig-verify").start();
    }

    private void done(String msg, String toast) {
        verified = true;
        IgSession.save(this, cookies());
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) { }
        hint(msg);
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        main.postDelayed(this::finish, 900);
    }

    private Net.Resp verify(String cookie) {
        String[][] hs = new String[][]{
                {"Cookie", cookie},
                {"User-Agent", Net.UA},
                {"x-ig-app-id", "936619743392459"},
                {"Accept", "*/*"},
                {"Referer", "https://www.instagram.com/"},
        };
        Net.Resp r = Net.getResp(VERIFY, hs, 20000);
        if (r.code == -1) {                 // 冷启动首个请求常连不上，重试一次
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            r = Net.getResp(VERIFY, hs, 20000);
        }
        Log.i(TAG, "verify " + r.code + " " + (r.body == null ? "" :
                r.body.replaceAll("\\s+", " ").substring(0, Math.min(120, r.body.length()))));
        return r;
    }

    private void hint(String s) {
        if (tvHint == null) return;
        tvHint.setText(s);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
