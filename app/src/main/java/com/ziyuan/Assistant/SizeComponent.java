package com.ziyuan.Assistant;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.os.Handler;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.player.VideoView;

/** 控制层左上角 width/height 显示，随控制条显隐 */
public class SizeComponent implements IControlComponent {

    private final VideoView videoView;
    private TextView tv;
    private final Handler h = new Handler();
    private Runnable tick;

    public SizeComponent(VideoView vv) { videoView = vv; }

    @Override
    public void attach(final ControlWrapper controlWrapper) {
        tick = new Runnable() {
            public void run() {
                try {
                    int[] sz = videoView.getVideoSize();
                    if (sz != null && sz[0] > 0 && sz[1] > 0)
                        tv.setText("width: " + sz[0] + " height: " + sz[1]);
                } catch (Throwable ignored) {}
                h.postDelayed(this, 500);
            }
        };
        h.postDelayed(tick, 500);
    }

    @Override
    public View getView() {
        if (tv == null) {
            tv = new TextView(videoView.getContext());
            tv.setText("width: 0 height: 0");
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(11);
            tv.setPadding(16, 8, 16, 8);
            tv.setShadowLayer(2, 1, 1, 0x88000000);
            tv.setVisibility(View.GONE);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
            lp.topMargin = 96; // 避开标题栏
            tv.setLayoutParams(lp);
        }
        return tv;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, android.view.animation.Animation anim) {
        if (tv != null) tv.setVisibility(isVisible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onPlayStateChanged(int playState) { }

    @Override
    public void onPlayerStateChanged(int playerState) { }

    @Override
    public void setProgress(int duration, int position) { }

    @Override
    public void onLockStateChanged(boolean isLocked) { }
}
