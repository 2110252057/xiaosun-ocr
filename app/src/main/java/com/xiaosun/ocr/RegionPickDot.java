package com.xiaosun.ocr;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

import static com.xiaosun.ocr.Palette.*;
import static com.xiaosun.ocr.Ui.*;

/**
 * 「框选区域」用的悬浮球：点一下才开始框选。
 *
 * <h3>为什么需要它</h3>
 * 插件配置界面是宿主（MacroDroid / Tasker）拉起来的普通页面。
 * 原来点「框选区域…」会**立刻**截图，可那一刻前台正是 Grace 自己的配置界面 ——
 * 截到的必然是这一页，而用户真正想框的是别的 App 的页面。
 * 于是框选底图永远不对，用户只能靠猜比例。
 *
 * 所以改成两步：
 * <ol>
 *   <li>先摆一个悬浮球（本类）；</li>
 *   <li>用户切到目标页面后点它，这时才截图并进入框选。</li>
 * </ol>
 *
 * <h3>为什么用系统级悬浮窗而不是往 Activity 里塞 View</h3>
 * 用户一旦切到别的 App，本 App 的 Activity 就进了后台，它自己的窗口不再可见。
 * 要「浮在别人的页面上」，只能用 {@code TYPE_APPLICATION_OVERLAY}
 * （Android 8.0 以下退回 {@code TYPE_PHONE}），这需要
 * {@code SYSTEM_ALERT_WINDOW} 权限 —— 清单里已声明，运行时还要用户在
 * 系统设置里勾一次。
 *
 * <h3>为什么用 Application Context</h3>
 * 悬浮窗的生命周期不受任何 Activity 约束：用户在宿主配置页点完按钮、
 * 切到别的 App，Activity 会 stop 甚至被回收，但悬浮球必须还在。
 * 持有 Activity 引用还会泄漏整个界面。
 *
 * <h3>为什么点完先 hide 再截</h3>
 * 悬浮球本身也是一扇窗口，不先撤掉它就会出现在截图里，
 * 框选底图上会多出一颗蓝色圆球。撤窗后要等一两帧让合成器刷新，
 * 所以调用方在 {@link Callback#onTap()} 之后还有一段延时，见
 * {@code PluginEditActivity.captureThenPick}。
 */
final class RegionPickDot {

    /** 用户对悬浮球做了什么。两个回调触发前，悬浮球都已经撤掉了。 */
    interface Callback {
        /** 点了一下：现在可以截图了。 */
        void onTap();

        /** 长按：放弃这次框选。 */
        void onCancel();
    }

    /** 同一时刻只允许存在一个，重复 show 会先把旧的撤掉。 */
    private static RegionPickDot sCurrent;

    /** 长按多久算「取消」。短于这个时长且没移动，就是单击。 */
    private static final long LONG_PRESS_MS = 700;

    private final Callback cb;

    private final WindowManager wm;
    private final TextView ball;
    private final WindowManager.LayoutParams lp;

    /** 判定拖动的手指容差，取自系统的 touch slop，各机型手感一致。 */
    private final int slop;

    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private long downAt;
    private boolean moved;

    private boolean removed;

    private RegionPickDot(Context c, Callback cb) {
        this.cb = cb;
        this.wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();

        lp = new WindowManager.LayoutParams();
        lp.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // 不抢焦点：悬浮球只是被点一下，不该把用户正在用的输入法/列表抢走。
        // 不加 FLAG_NOT_TOUCH_MODAL 也不会挡住其它区域，因为窗口本身只有球那么大。
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = dp(c, 52);
        lp.height = dp(c, 52);
        // 先摆在屏幕左侧偏下的位置：不压状态栏，也不挡底部手势条。
        lp.x = dp(c, 12);
        lp.y = dp(c, 260);

        ball = new TextView(c);
        ball.setText("框");
        ball.setTextSize(15);
        ball.setTextColor(0xFFFFFFFF);
        ball.setGravity(Gravity.CENTER);
        ball.setAlpha(0.94f);
        ball.setBackground(solidBg(c, C_BRAND, 26));
        ball.setContentDescription("点击开始框选区域");
        ball.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        downX = lp.x;
                        downY = lp.y;
                        downAt = SystemClock.uptimeMillis();
                        moved = false;
                        // 按下时稍微加深，给一点「摸到了」的反馈。
                        ball.setAlpha(0.75f);
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (e.getRawX() - downRawX);
                        int dy = (int) (e.getRawY() - downRawY);
                        // 超过 slop 才算拖动。不设这个门槛的话，
                        // 手指稍微抖一下就会把「点击」判成「拖动」，点不动。
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            moved = true;
                        }
                        if (moved) {
                            lp.x = downX + dx;
                            lp.y = downY + dy;
                            updateLayout();
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP: {
                        ball.setAlpha(0.94f);
                        long held = SystemClock.uptimeMillis() - downAt;
                        if (moved) {
                            // 只是在挪位置，既不算点击也不算取消。
                            return true;
                        }
                        if (held >= LONG_PRESS_MS) {
                            hide();
                            cb.onCancel();
                            return true;
                        }
                        hide();
                        cb.onTap();
                        return true;
                    }

                    case MotionEvent.ACTION_CANCEL:
                        ball.setAlpha(0.94f);
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    /** 「显示在其他应用上层」是否已授权。没有它就装不出悬浮球。 */
    static boolean canShow(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /**
     * 跳到系统的「显示在其他应用上层」授权页。
     *
     * <p>带上 package: 前缀可以直接定位到本 App 那一项，
     * 省得用户在长长的列表里自己找。个别 ROM 不认这个带包名的 Intent，
     * 所以再兜一层不带参数的。
     */
    static void requestPermission(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + c.getPackageName()));
            if (!(c instanceof android.app.Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                if (!(c instanceof android.app.Activity)) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                c.startActivity(i);
            } catch (Throwable ignored) {
                // 连设置页都打不开的机型上，只能靠调用方给文字提示了。
            }
        }
    }

    static boolean isShowing() {
        return sCurrent != null;
    }

    /**
     * 摆出悬浮球。会先把上一个撤掉，保证屏幕上最多一个。
     *
     * @return 是否成功摆出（权限没了、或系统拒绝 addView 都返回 false）
     */
    static boolean show(Context c, Callback cb) {
        hide();
        if (c == null || !canShow(c)) {
            return false;
        }
        Context app = c.getApplicationContext();
        RegionPickDot dot = new RegionPickDot(app == null ? c : app, cb);
        try {
            dot.wm.addView(dot.ball, dot.lp);
        } catch (Throwable t) {
            // 权限在「检查」和「addView」之间被撤销、或某些 ROM 直接拒绝，
            // 都在这里兜住 —— 让调用方走「没装出来」的分支给提示。
            return false;
        }
        sCurrent = dot;
        return true;
    }

    /** 撤掉悬浮球。没在显示时是空操作，可以随便调。 */
    static void hide() {
        RegionPickDot dot = sCurrent;
        sCurrent = null;
        if (dot != null) {
            dot.removeSelf();
        }
    }

    private void removeSelf() {
        if (removed) {
            return;
        }
        removed = true;
        try {
            wm.removeViewImmediate(ball);
        } catch (Throwable ignored) {
            // 窗口可能已经被系统收走了（比如权限被撤销），忽略即可。
        }
    }

    private void updateLayout() {
        if (removed) {
            return;
        }
        try {
            wm.updateViewLayout(ball, lp);
        } catch (Throwable ignored) {
            // 窗口不在时 update 会抛，摆球本来就是尽力而为。
        }
    }
}