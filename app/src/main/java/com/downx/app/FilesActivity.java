package com.downx.app;

// 本工程 namespace 为 com.example.immersive，且 nonTransitiveRClass=true，需显式 import
import com.example.immersive.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 逐条管理：每个文件一个开关，单独决定它在不在相册里。
 * 开关打开 = 相册可见；关掉 = 搬到 App 私有目录，相册里看不到。
 * 只动被切换的那一条，其他文件不受影响。
 */
public class FilesActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ThemeMode.apply(base));
    }

    private LinearLayout listBox;
    private TextView tvSummary;
    private boolean busy;

    private LinearLayout selectBar;
    private TextView tvSelCount;
    private ImageView btnPick;
    private boolean pickMode;
    private final java.util.List<HistoryStore.Entry> picked = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);
        Immersive.apply(this);
        listBox = findViewById(R.id.listBox);
        tvSummary = findViewById(R.id.tvSummary);
        selectBar = findViewById(R.id.selectBar);
        tvSelCount = findViewById(R.id.tvSelCount);
        btnPick = findViewById(R.id.btnPick);
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (pickMode) exitPick();
            else finish();
        });
        btnPick.setOnClickListener(v -> {
            if (pickMode) exitPick();
            else enterPick();
        });
        findViewById(R.id.btnSelExit).setOnClickListener(v -> exitPick());
        findViewById(R.id.btnSelAll).setOnClickListener(v -> selectAll());
        findViewById(R.id.btnSelDelRecord).setOnClickListener(v -> deletePicked(false));
        findViewById(R.id.btnSelDelFile).setOnClickListener(v -> deletePicked(true));
        render();
    }

    @Override
    public void onBackPressed() {
        if (pickMode) {
            exitPick();
            return;
        }
        super.onBackPressed();
    }

    private static String keyOf(HistoryStore.Entry e) {
        return (e.name == null ? "" : e.name) + "|" + e.ts;
    }

    private boolean isPicked(HistoryStore.Entry e) {
        String k = keyOf(e);
        for (int i = 0; i < picked.size(); i++) {
            if (keyOf(picked.get(i)).equals(k)) return true;
        }
        return false;
    }

    private void enterPick() {
        pickMode = true;
        picked.clear();
        btnPick.setImageResource(R.drawable.ic_x);
        btnPick.setColorFilter(0xFF9E9EA8);
        render();
        toast("多选已开启：点条目勾选，底部可批量删除");
    }

    private void exitPick() {
        pickMode = false;
        picked.clear();
        btnPick.setImageResource(R.drawable.ic_check_square);
        btnPick.setColorFilter(0xFFFFC86B);
        render();
    }

    private void togglePicked(HistoryStore.Entry e) {
        String k = keyOf(e);
        for (int i = 0; i < picked.size(); i++) {
            if (keyOf(picked.get(i)).equals(k)) {
                picked.remove(i);
                render();
                return;
            }
        }
        picked.add(e);
        render();
    }

    private void selectAll() {
        List<HistoryStore.Entry> all = HistoryStore.list(this);
        if (all.isEmpty()) return;
        if (picked.size() == all.size()) {
            picked.clear();
            toast("已取消全选");
        } else {
            picked.clear();
            picked.addAll(all);
            toast("已全选 " + all.size() + " 项");
        }
        render();
    }

    private void updateBar() {
        selectBar.setVisibility(pickMode ? View.VISIBLE : View.GONE);
        int n = picked.size();
        tvSelCount.setText(n == 0 ? "未选择 · 点条目勾选" : "已选 " + n + " 项");
    }

    /** 批量删除：withFile=true 连文件一起删 */
    private void deletePicked(final boolean withFile) {
        if (picked.isEmpty()) {
            toast("先勾选要删除的内容");
            return;
        }
        final int n = picked.size();
        String msg = withFile
                ? "选中的 " + n + " 个文件会被彻底删除，释放存储空间，删除后无法恢复。"
                : "只把选中的 " + n + " 条记录从列表里移除，文件仍留在手机上。";
        new AlertDialog.Builder(this)
                .setTitle(withFile ? "批量删除文件" : "批量删除记录")
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton(withFile ? "彻底删除" : "删除记录", (d, w) -> {
                    final java.util.List<HistoryStore.Entry> copy = new java.util.ArrayList<>(picked);
                    // 先抹记录 + 刷新列表（立刻消失），文件交给后台线程删，
                    // 否则几百兆的视频会让界面卡住好几秒，看起来像"删了没反应"
                    java.util.List<HistoryStore.Entry> all = HistoryStore.list(this);
                    java.util.List<HistoryStore.Entry> keep = new java.util.ArrayList<>();
                    for (HistoryStore.Entry x : all) {
                        boolean hit = false;
                        for (HistoryStore.Entry e : copy) {
                            if (keyOf(x).equals(keyOf(e))) { hit = true; break; }
                        }
                        if (!hit) keep.add(x);
                    }
                    HistoryStore.saveAll(this, keep);
                    exitPick();
                    render();
                    toast(withFile ? ("已彻底删除 " + n + " 项") : ("已移除 " + n + " 条记录"));
                    if (!withFile) return;
                    new Thread(() -> {
                        int fail = 0;
                        for (HistoryStore.Entry e : copy) {
                            if (!deleteFileOf(e)) fail++;
                        }
                        if (fail > 0) {
                            final int f = fail;
                            runOnUiThread(() -> toast("有 " + f
                                    + " 个文件系统不允许删除，请到相册里手动删除"));
                        }
                    }, "del").start();
                })
                .show();
    }

    private boolean deleteFileOf(HistoryStore.Entry e) {
        boolean ok = false;
        try {
            if (e.uri != null && e.uri.startsWith("file://")) {
                File f = new File(Uri.parse(e.uri).getPath());
                ok = f.exists() && f.delete();
            }
            if (!ok && e.uri != null && e.uri.startsWith("content://")) {
                try {
                    if (getContentResolver().delete(Uri.parse(e.uri), null, null) > 0) ok = true;
                } catch (Throwable ignored) { }
            }
            if (e.visible) {
                if (Store.removePublic(this, e)) ok = true;
            } else {
                File f = Store.privateFile(this, e.kind, e.name);
                if (f == null || !f.exists() || f.delete()) ok = true;
            }
        } catch (Throwable ignored) { }
        return ok;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy) render();
    }

    private void render() {
        listBox.removeAllViews();
        List<HistoryStore.Entry> list = HistoryStore.list(this);
        if (pickMode) {
            // 清掉已被删掉的勾选
            java.util.List<HistoryStore.Entry> keep = new java.util.ArrayList<>();
            for (HistoryStore.Entry p : picked) {
                for (HistoryStore.Entry x : list) {
                    if (keyOf(x).equals(keyOf(p))) { keep.add(x); break; }
                }
            }
            picked.clear();
            picked.addAll(keep);
            if (list.isEmpty()) pickMode = false;
        }
        updateBar();
        int hidden = 0;
        for (HistoryStore.Entry e : list) {
            if (!e.visible) hidden++;
        }
        tvSummary.setText(list.isEmpty()
                ? "还没有下载记录"
                : "共 " + list.size() + " 个文件，" + hidden + " 个已隐藏\n"
                        + "开关打开 = 相册里能看到，关掉 = 只在本 App 里");
        if (list.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("去首页下载几条，这里就会出现列表");
            t.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_faint));
            t.setTextSize(13f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(40), 0, 0);
            listBox.addView(t);
            return;
        }
        // 多选模式逐条显示（方便单条勾选）；平时把同一来源的图片叠成一摞，
        // 微缩图一眼能看出这组大概都有什么，点进去左右滑着看
        if (!pickMode) {
            java.util.Set<String> grouped = new java.util.HashSet<>();
            java.util.List<Object> rows = new java.util.ArrayList<>();
            java.util.Map<String, List<HistoryStore.Entry>> photoGroups = new java.util.LinkedHashMap<>();
            for (HistoryStore.Entry e : list) {
                if (!"photo".equals(e.kind) || e.pageUrl == null || e.pageUrl.isEmpty()) {
                    rows.add(e);
                    continue;
                }
                List<HistoryStore.Entry> g = photoGroups.get(e.pageUrl);
                if (g == null) {
                    g = new java.util.ArrayList<>();
                    photoGroups.put(e.pageUrl, g);
                    rows.add(marker(g));     // 组入口放在该组第一张出现的位置
                } else {
                    g.add(e);
                }
            }
            // rows 里放的是组头（含组员列表的 marker），走一遍渲染
            for (Object o : rows) {
                if (o instanceof GroupMarker) {
                    GroupMarker gm = (GroupMarker) o;
                    if (gm.group.size() >= 2) {
                        listBox.addView(buildStack(gm.group));
                        continue;
                    }
                    for (HistoryStore.Entry e : gm.group) listBox.addView(buildRow(e));
                } else {
                    listBox.addView(buildRow((HistoryStore.Entry) o));
                }
            }
            return;
        }
        for (HistoryStore.Entry e : list) {
            listBox.addView(buildRow(e));
        }
    }

    /** 分组占位对象：标记"这里是某一组图片的入口" */
    private static final class GroupMarker {
        final List<HistoryStore.Entry> group;

        GroupMarker(List<HistoryStore.Entry> g) {
            group = g;
        }
    }

    private static GroupMarker marker(List<HistoryStore.Entry> g) {
        return new GroupMarker(g);
    }

    /**
     * 同一来源 N 张图的叠加行：底层两张错位垫底 + 顶层显示第一张，
     * 右下角「N 张」角标；点开用系统图片查看器打开。
     */
    private View buildStack(final List<HistoryStore.Entry> group) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        row.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);

        int off = dp(5);
        FrameLayout stack = new FrameLayout(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                dp(54) + off * Math.min(2, group.size() - 1),
                dp(54) + off * Math.min(2, group.size() - 1));
        slp.rightMargin = dp(12);
        stack.setLayoutParams(slp);

        // 从底层往上叠：第 2、3 张垫底错位，第一张在顶上
        int layers = Math.min(3, group.size());
        for (int layer = layers - 1; layer >= 0; layer--) {
            final HistoryStore.Entry e = group.get(layer);
            ImageView iv = new ImageView(this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(54), dp(54));
            lp.leftMargin = off * layer;
            lp.topMargin = off * layer;
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_media);
            iv.setClipToOutline(true);
            stack.addView(iv);
            Uri u = null;
            try {
                if (Store.exists(this, e)) u = Store.uriFor(this, e);
            } catch (Throwable ignored) { }
            ThumbLoader.load(this, iv, u, e.kind, e.thumb);
        }

        TextView badge = new TextView(this);
        badge.setText(group.size() + " 张");
        badge.setTextSize(10f);
        badge.setTextColor(0xFF0D0D11);
        badge.setBackgroundResource(R.drawable.bg_chip_on);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM | Gravity.END;
        stack.addView(badge, blp);
        row.addView(stack);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(this);
        HistoryStore.Entry first = group.get(0);
        t1.setText((first.sub == null || first.sub.isEmpty() ? "图片" : first.sub) + " · 一组");
        t1.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_text));
        t1.setTextSize(13.5f);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText("点开左右滑着看 · " + group.size() + " 张图片");
        t2.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_dim));
        t2.setTextSize(11.5f);
        col.addView(t2);

        row.addView(col);
        row.setOnClickListener(v -> openViewer(group));
        row.setOnLongClickListener(v -> {
            openViewer(group);
            return true;
        });
        return row;
    }

    /**
     * 原版跳图集页左右滑看整组图。这里改用系统图片查看器打开该组第一张：
     * Store.playUri 返回 LocalFiles 授权过的 content:// uri，避免额外引入图集 / 播放器 UI，
     * 同时保留「同来源多图叠成一摞」的分组渲染与点击行为。
     */
    private void openViewer(List<HistoryStore.Entry> group) {
        if (group.isEmpty()) return;
        try {
            HistoryStore.Entry e = group.get(0);
            Uri u = Store.playUri(this, e);
            if (u == null) { toast("文件不存在"); return; }
            android.content.Intent i =
                    new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(u, "image/*");
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(i, "查看图片"));
        } catch (Throwable t) {
            toast("没有可用的图片查看器");
        }
    }

    private View buildRow(final HistoryStore.Entry e) {
        final boolean alive = Store.exists(this, e);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        row.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);

        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(54), dp(54));
        ilp.rightMargin = dp(12);
        iv.setLayoutParams(ilp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.bg_media);
        iv.setClipToOutline(true);
        row.addView(iv);
        Uri u = null;
        try {
            if (alive) u = Store.uriFor(this, e);
        } catch (Throwable ignored) { }
        ThumbLoader.load(this, iv, u, e.kind, e.thumb);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = new TextView(this);
        String title = e.sub == null || e.sub.isEmpty() ? e.name : e.sub;
        t1.setText(title);
        t1.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_text));
        t1.setTextSize(13.5f);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t1);

        TextView t2 = new TextView(this);
        String size = "";
        try {
            File f = e.visible
                    ? Store.publicFile(e.kind, e.name)
                    : Store.privateFile(this, e.kind, e.name);
            if (f.exists()) size = " · " + mb(f.length());
        } catch (Throwable ignored) { }
        String state = !alive ? "文件不存在" : (e.visible ? "相册可见" : "已隐藏");
        t2.setText(HistoryStore.fmtTs(e.ts) + " · " + state + size);
        t2.setTextColor(alive ? androidx.core.content.ContextCompat.getColor(this, com.example.immersive.R.color.set_dim) : 0xFFFF6B6B);
        t2.setTextSize(11.5f);
        t2.setSingleLine(true);
        col.addView(t2);
        row.addView(col);

        Switch sw = new Switch(this);
        sw.setChecked(e.visible);
        sw.setEnabled(alive && !busy);
        sw.setOnCheckedChangeListener((v, on) -> {
            if (on == e.visible) return;
            move(e, on);
        });

        if (pickMode) {
            final boolean on = isPicked(e);
            row.setBackgroundResource(on ? R.drawable.bg_card_sel : R.drawable.bg_card);
            TextView chk = new TextView(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(26), dp(26));
            clp.leftMargin = dp(4);
            chk.setLayoutParams(clp);
            chk.setBackgroundResource(on ? R.drawable.bg_check_on : R.drawable.bg_check_off);
            chk.setGravity(Gravity.CENTER);
            chk.setText(on ? "✓" : "");
            chk.setTextColor(0xFFFFFFFF);
            chk.setTextSize(14f);
            chk.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(chk);
            row.setOnClickListener(v -> togglePicked(e));
        } else {
            row.addView(sw);
        }
        return row;
    }

    private void move(final HistoryStore.Entry e, final boolean to) {
        if (busy) {
            render();
            return;
        }
        busy = true;
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setMessage(to ? "正在放回相册…" : "正在从相册移出…")
                .setCancelable(false)
                .create();
        dlg.show();
        new Thread(() -> {
            final boolean ok = Store.moveOne(this, e, to);
            runOnUiThread(() -> {
                dlg.dismiss();
                busy = false;
                if (!ok) {
                    toast(to ? "放回相册失败，可能存储空间不足"
                            : "移出失败：系统不允许 App 改动这个文件，可去相册手动删");
                } else {
                    toast(to ? "已放回相册" : "已移出相册，相册里不再显示");
                }
                render();
            });
        }, "moveOne").start();
    }

    private String mb(long len) {
        if (len < 1024) return len + " B";
        if (len < 1024 * 1024) return (len / 1024) + " KB";
        return String.format(java.util.Locale.CHINA, "%.1f MB", len / 1024f / 1024f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
