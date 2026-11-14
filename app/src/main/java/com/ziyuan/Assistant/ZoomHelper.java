package com.ziyuan.Assistant;

import android.app.Activity;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

/**
 * 播放器手势：双指捏合缩放（无损）+ 放大后单指拖动 + 双击复位。
 *
 * 在 Activity.dispatchTouchEvent 层拦截，避免被控制器 / 播放器自身的触摸处理吃掉。
 * 直接对渲染层 TextureView 做 Matrix 变换（GPU 合成，不重新解码，画质无损）。
 */
public final class ZoomHelper {

    private static final float MIN = 1.0f;   // 最小 1x（不缩小，避免黑边）
    private static final float MAX = 4.0f;   // 最大 4x
    private static final float EPS = 1.001f;

    private static ZoomHelper sInst;

    private TextureView target;
    private final Matrix matrix = new Matrix();
    private float scale = 1f;
    private float tx, ty;
    private int mode = 0;            // 0=无, 1=双指缩放, 2=单指拖动
    private float startDist, startScale;
    private float lastX, lastY;
    private float downX, downY;
    private long lastTapMs;

    private ZoomHelper() { }

    /** 在 Activity.dispatchTouchEvent 中调用；返回 true 表示本次手势已被消费 */
    public static boolean handle(Activity act, MotionEvent ev) {
        if (sInst == null) sInst = new ZoomHelper();
        return sInst.onTouch(act, ev);
    }

    /** 页面销毁时释放视图引用，避免泄漏 */
    public static void clear() {
        if (sInst != null) {
            sInst.target = null;
            sInst.scale = 1f;
            sInst.tx = 0f;
            sInst.ty = 0f;
            sInst.mode = 0;
        }
    }

    private boolean onTouch(Activity act, MotionEvent ev) {
        TextureView tv = resolve(act);
        if (tv == null || tv.getWidth() == 0 || tv.getHeight() == 0) return false;

        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                lastX = downX;
                lastY = downY;
                mode = 0;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() >= 2) {
                    mode = 1;
                    startDist = distance(ev);
                    startScale = scale;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() >= 2 && mode == 1) {
                    float d = distance(ev);
                    if (startDist > 1f) {
                        setScale(tv, clamp(startScale * (d / startDist), MIN, MAX));
                    }
                    return true;
                }
                if (mode == 2 || (scale > EPS && ev.getPointerCount() == 1)) {
                    mode = 2;
                    if (scale > EPS) {
                        tx += ev.getX() - lastX;
                        ty += ev.getY() - lastY;
                        lastX = ev.getX();
                        lastY = ev.getY();
                        apply(tv);
                        return true;
                    }
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerCount() - 1 == 1) {
                    // 双指变单指：继续以剩下那根手指拖动
                    mode = 2;
                    int idx = ev.getActionIndex() == 0 ? 1 : 0;
                    if (idx < ev.getPointerCount()) {
                        lastX = ev.getX(idx);
                        lastY = ev.getY(idx);
                    }
                } else {
                    mode = 0;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (scale > EPS) {
                    float moved = Math.abs(ev.getX() - downX) + Math.abs(ev.getY() - downY);
                    long now = System.currentTimeMillis();
                    if (moved < 30 && now - lastTapMs < 300) {
                        resetZoom(tv);
                        lastTapMs = 0;
                        mode = 0;
                        return true;   // 双击复位，消费掉
                    }
                    if (moved < 30) lastTapMs = now;
                }
                mode = 0;
                break;

            case MotionEvent.ACTION_CANCEL:
                mode = 0;
                break;

            default:
                break;
        }
        return false;
    }

    private TextureView resolve(Activity act) {
        if (target != null && target.isAttachedToWindow() && target.getWidth() > 0) return target;
        target = findTexture(act.getWindow().getDecorView());
        return target;
    }

    private static TextureView findTexture(View v) {
        if (v instanceof TextureView) return (TextureView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextureView t = findTexture(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private void setScale(TextureView tv, float ns) {
        scale = ns;
        apply(tv);
    }

    private void apply(TextureView tv) {
        int w = tv.getWidth();
        int h = tv.getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float maxX = (scale - 1f) * w / 2f;
        float maxY = (scale - 1f) * h / 2f;
        if (maxX < 0f) maxX = 0f;
        if (maxY < 0f) maxY = 0f;
        if (tx > maxX) tx = maxX;
        if (tx < -maxX) tx = -maxX;
        if (ty > maxY) ty = maxY;
        if (ty < -maxY) ty = -maxY;

        matrix.reset();
        matrix.setScale(scale, scale, cx, cy);
        matrix.postTranslate(tx, ty);
        tv.setTransform(matrix);
    }

    private void resetZoom(TextureView tv) {
        scale = 1f;
        tx = 0f;
        ty = 0f;
        matrix.reset();
        tv.setTransform(matrix);
    }

    private static float distance(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0f;
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
