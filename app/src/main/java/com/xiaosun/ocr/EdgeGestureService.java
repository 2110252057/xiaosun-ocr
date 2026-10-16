/*
 * Decompiled with CFR 0.152.
 */
package com.xiaosun.ocr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;
import com.xiaosun.ocr.EdgeGesturePrefs;
import com.xiaosun.ocr.MainActivity;
import com.xiaosun.ocr.MenuIcons;
import com.xiaosun.ocr.OCRService;
import com.xiaosun.ocr.R;
import com.xiaosun.ocr.TaskerEventProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class EdgeGestureService
extends Service {
    private static final String TAG = "XSOCR-Gesture";
    public static final String ACTION_START = "com.xiaosun.ocr.action.EDGE_GESTURE_START";
    public static final String ACTION_STOP = "com.xiaosun.ocr.action.EDGE_GESTURE_STOP";
    public static final String ACTION_RELOAD = "com.xiaosun.ocr.action.EDGE_GESTURE_RELOAD";
    public static volatile boolean isRunning = false;
    private static final String CHANNEL_ID = "xsocr_gesture";
    private static final int NOTIFICATION_ID = 23042;
    private static final long LONG_PRESS_MS = 380L;
    private static final int MOVE_SLOP_DP = 10;
    private static final long FLASH_MS = 170L;
    private static final int MAX_EXCLUDE_DP = 200;
    private WindowManager wm;
    private final List<View> views = new ArrayList<View>();
    private BroadcastReceiver reloadReceiver;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EdgeGesturePrefs.Handle activeHandle;
    private static final long WATCHDOG_INTERVAL_MS = 2500L;
    private final Runnable watchdogTask = new Runnable(){

        @Override
        public void run() {
            try {
                if (EdgeGesturePrefs.isEnabled((Context)EdgeGestureService.this) && EdgeGestureService.this.canOverlay() && !EdgeGestureService.this.anyViewAttached()) {
                    Log.i((String)EdgeGestureService.TAG, (String)"\u770b\u95e8\u72d7\uff1a\u624b\u67c4\u7a97\u53e3\u4e0d\u5728\uff0c\u91cd\u5efa");
                    EdgeGestureService.this.rebuild();
                }
            }
            catch (Throwable t) {
                Log.w((String)EdgeGestureService.TAG, (String)("\u770b\u95e8\u72d7\u5f02\u5e38: " + t));
            }
            EdgeGestureService.this.ui.postDelayed((Runnable)this, 2500L);
        }
    };

    // ============================================================
    //  防烧屏：像素漂移
    // ============================================================

    /** 随机源。漂移幅度只有几个像素，用普通 Random 足够。 */
    private final Random burninRandom = new Random();

    private final Runnable burninTask = new Runnable(){

        @Override
        public void run() {
            try {
                EdgeGestureService.this.doBurninShift();
            }
            catch (Throwable t) {
                Log.w((String)EdgeGestureService.TAG, (String)("\u9632\u70e7\u5c4f\u6f02\u79fb\u5f02\u5e38: " + t));
            }
            EdgeGestureService.this.scheduleBurnin();
        }
    };

    /**
     * 安排下一次漂移。任何一处「配置变了 / 手柄重建了」都走这里，
     * 因此它是幂等的（先撤旧任务再排新的）。
     *
     * 若功能被关掉（或幅度为 0），顺手把已经漂出去的手柄拨回原位 ——
     * 否则会停在「漂移后」的位置上，看起来像是位置错了。
     */
    private void scheduleBurnin() {
        this.ui.removeCallbacks(this.burninTask);
        if (!EdgeGesturePrefs.isEnabled((Context)this)) {
            return;
        }
        if (!EdgeGesturePrefs.isBurnInEnabled((Context)this)
                || EdgeGesturePrefs.getBurnInRangeDp((Context)this) <= 0) {
            this.resetBurnin();
            return;
        }
        long ms = (long)EdgeGesturePrefs.getBurnInIntervalSec((Context)this) * 1000L;
        this.ui.postDelayed(this.burninTask, ms);
    }

    private void doBurninShift() {
        if (!EdgeGesturePrefs.isEnabled((Context)this)) {
            return;
        }
        if (!EdgeGesturePrefs.isBurnInEnabled((Context)this)) {
            return;
        }
        int rangePx = this.dp(EdgeGesturePrefs.getBurnInRangeDp((Context)this));
        if (rangePx <= 0) {
            return;
        }
        // 息屏时手柄本来也不显示，没必要动（也就省掉了无谓的窗口事务）。
        if (!this.screenOn()) {
            return;
        }
        for (View v : this.views) {
            if (!(v instanceof GestureView)) continue;
            try {
                ((GestureView)v).applyBurninShift(this.burninRandom, rangePx);
            }
            catch (Throwable t) {
                Log.w((String)TAG, (String)("\u9632\u70e7\u5c4f\u6f02\u79fb\u5931\u8d25: " + t));
            }
        }
    }

    /** 把所有手柄拨回原位（基准位置）。 */
    private void resetBurnin() {
        for (View v : this.views) {
            if (!(v instanceof GestureView)) continue;
            try {
                ((GestureView)v).resetBurninShift();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    private boolean screenOn() {
        try {
            PowerManager pm = (PowerManager)this.getSystemService("power");
            return pm == null || pm.isInteractive();
        }
        catch (Throwable t) {
            return true;
        }
    }

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, EdgeGestureService.class);
            i.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        }
        catch (Throwable t) {
            Log.w((String)TAG, (String)("\u542f\u52a8\u624b\u52bf\u670d\u52a1\u5931\u8d25: " + t));
        }
    }

    public static void stop(Context c) {
        try {
            Intent i = new Intent(c, EdgeGestureService.class);
            i.setAction(ACTION_STOP);
            c.startService(i);
        }
        catch (Throwable t) {
            Log.w((String)TAG, (String)("\u505c\u6b62\u624b\u52bf\u670d\u52a1\u5931\u8d25: " + t));
        }
    }

    public static void reload(Context c) {
        try {
            Intent i = new Intent(ACTION_RELOAD);
            i.setPackage(c.getPackageName());
            c.sendBroadcast(i);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    public static void sync(Context c) {
        if (EdgeGesturePrefs.isEnabled(c)) {
            EdgeGestureService.start(c);
        } else {
            EdgeGestureService.stop(c);
        }
    }

    public void onCreate() {
        super.onCreate();
        // 日志要在这里就装好：手柄相关的问题（加窗失败、崩溃）
        // 全都发生在服务进程里，等用户去点界面就来不及了。
        // install() 幂等，和 MainActivity 里重复调用没有副作用。
        AppLog.install(this);
        AppLog.i(TAG, "EdgeGestureService.onCreate 启动，sdk=" + Build.VERSION.SDK_INT);
        this.wm = (WindowManager)this.getSystemService("window");
        this.reloadReceiver = new BroadcastReceiver(){

            public void onReceive(Context context, Intent intent) {
                EdgeGestureService.this.rebuild();
            }
        };
        try {
            IntentFilter f = new IntentFilter(ACTION_RELOAD);
            if (Build.VERSION.SDK_INT >= 33) {
                this.registerReceiver(this.reloadReceiver, f, 4);
            } else {
                this.registerReceiver(this.reloadReceiver, f);
            }
        }
        catch (Throwable t) {
            Log.w((String)TAG, (String)("\u6ce8\u518c\u914d\u7f6e\u53d8\u66f4\u76d1\u542c\u5931\u8d25: " + t));
        }
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        String action;
        String string = action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            this.stopSelf();
            return 2;
        }
        this.goForeground();
        if (!this.canOverlay()) {
            this.toast("\u7f3a\u5c11\u300c\u60ac\u6d6e\u7a97\u6743\u9650\u300d\uff0c\u65e0\u6cd5\u663e\u793a\u624b\u52bf\u624b\u67c4");
            this.stopSelf();
            return 2;
        }
        if (!EdgeGesturePrefs.isEnabled((Context)this)) {
            this.stopSelf();
            return 2;
        }
        if (this.views.isEmpty() || !this.anyViewAttached()) {
            this.rebuild();
        }
        isRunning = true;
        EdgeGesturePrefs.gsRunning = true;
        EdgeGesturePrefs.refreshRuntime((Context)this);
        this.ui.removeCallbacks(this.watchdogTask);
        this.ui.postDelayed(this.watchdogTask, 2500L);
        this.scheduleBurnin();
        return 1;
    }

    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        this.ui.postDelayed(this::rebuild, 260L);
    }

    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i((String)TAG, (String)"\u4efb\u52a1\u88ab\u5212\u6389\uff0c\u91cd\u5efa\u624b\u67c4");
        this.ui.removeCallbacks(this.watchdogTask);
        if (EdgeGesturePrefs.isEnabled((Context)this) && this.canOverlay()) {
            this.rebuild();
        }
        this.ui.postDelayed(this.watchdogTask, 2500L);
    }

    public void onDestroy() {
        isRunning = false;
        this.ui.removeCallbacks(this.watchdogTask);
        this.ui.removeCallbacks(this.burninTask);
        EdgeGesturePrefs.gsRunning = false;
        this.removeAllViews();
        if (this.reloadReceiver != null) {
            try {
                this.unregisterReceiver(this.reloadReceiver);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            this.reloadReceiver = null;
        }
        try {
            this.stopForeground(true);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        super.onDestroy();
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    private void rebuild() {
        this.removeAllViews();
        this.activeHandle = null;
        if (!EdgeGesturePrefs.isEnabled((Context)this)) {
            this.stopSelf();
            return;
        }
        List<EdgeGesturePrefs.Handle> handles = EdgeGesturePrefs.getHandles((Context)this);
        int shown = 0;
        // 只做「手柄条」这一种形态：一个手柄一个窗口，各管各的触摸区。
        // （早先还有「整个边缘一条竖带」的形态，会把整条边的系统返回
        //   全废掉，已下线 —— 理由见 EdgeGesturePrefs 里触控区域那段注释。）
        for (EdgeGesturePrefs.Handle h : handles) {
            try {
                this.addHandleView(h);
                ++shown;
            }
            catch (Throwable t) {
                Log.w((String)TAG, (String)("\u624b\u67c4\u6dfb\u52a0\u5931\u8d25: " + t));
            }
        }
        EdgeGesturePrefs.gsHandleCount = shown;
        AppLog.i(TAG, "手柄重建完成: 显示 " + shown + " 个 / 配置 "
                + handles.size() + " 个，总开关=" + EdgeGesturePrefs.isEnabled(this));
        this.scheduleBurnin();
    }

    private WindowManager.LayoutParams baseParams(boolean leftEdge) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams();
        p.type = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
        p.flags = 808;
        p.format = -3;
        p.gravity = (leftEdge ? 0x800003 : 0x800005) | 0x30;
        p.x = 0;
        // 允许悬浮窗铺到刘海 / 挖孔 / 曲面屏的「短边」区域。
        //
        // 这一项是照着 MacroDroid 侧边栏抄的：它的
        // DrawerOverlayHandleService 在 API 28+ 会对 LayoutParams 调用
        //   LayoutParams.layoutInDisplayCutoutMode =
        //       LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;      // 值为 1
        // （反编译里是 Lgd5;->c(LayoutParams;I) 这个桥接方法，就是写这个字段。）
        //
        // 为什么必须设：不设时窗口会被系统按 display cutout 的 inset 从物理
        // 边缘往里推一段，手柄表面看仍然贴边（因为绘制在窗口内），但窗口
        // 本身离开屏幕真边缘之后，我们上报的手势排除区也跟着一起内缩，
        // 落在系统手势热区之外 —— 表现就是「上报成功，系统照样侧滑返回」。
        //
        // 字段是 API 28 才有的，条件保护住即可；低版本根本走不到这一句，
        // 不会有 NoSuchFieldError（常量会被编译期内联，字段访问只在
        // SDK_INT >= 28 的分支里执行）。
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    private void addHandleView(EdgeGesturePrefs.Handle h) {
        WindowManager.LayoutParams p = this.baseParams(h.isEdgeLeft());
        p.width = Math.max(1, this.dp(h.width));
        p.height = this.dp(h.height);
        int[] scr = this.screenSize();
        int maxY = Math.max(0, scr[1] - p.height);
        p.y = (int)((float)maxY * ((float)h.offsetPercent / 100.0f));
        AppLog.i(TAG, "加手柄: " + h.name + " 宽=" + p.width + " 高=" + p.height
                + " y=" + p.y + " 贴边=" + (h.isEdgeLeft() ? "左" : "右")
                + " 屏蔽手势=" + h.blockGesture + " 类型=" + p.type
                + " 屏幕=" + scr[0] + "x" + scr[1]);
        HandleView v = new HandleView(h, p);
        v.setLayoutParams((ViewGroup.LayoutParams)p);
        this.wm.addView((View)v, (ViewGroup.LayoutParams)p);
        this.views.add(v);
    }

    private void removeAllViews() {
        for (View v : this.views) {
            try {
                this.wm.removeViewImmediate(v);
            }
            catch (Throwable throwable) {}
        }
        this.views.clear();
    }

    private boolean anyViewAttached() {
        for (View v : this.views) {
            if (v == null || !v.isAttachedToWindow()) continue;
            return true;
        }
        return false;
    }

    private void invalidateAll() {
        for (View v : this.views) {
            v.invalidate();
        }
    }

    private void fire(EdgeGesturePrefs.Handle handle, int dir) {
        if (handle == null) {
            return;
        }
        AppLog.i(TAG, "触发手势: 手柄=" + handle.name + " 方向=" + (dir >= 0 ? dir : -1)
                + " 事件=" + handle.event + " 震动=" + handle.haptic);
        // 【不要在这里清菜单结果】
        // 选中路径是「先 recordMenuChoice(idx, ...) 再 fire()」——
        // 在这儿清零会把刚写好的选中值抹掉，菜单选了也传不出去。
        // 「菜单没启用」那条路径的清零放在它的调用点上。
        EdgeGesturePrefs.recordGesture((Context)this, handle, dir, handle.event);
        if (handle.haptic) {
            EdgeGesturePrefs.buzz((Context)this, true);
        }
        this.ensureOcrService();
        this.broadcastRequestQuery(dir);
        this.setFlash(true);
        this.invalidateAll();
        this.ui.postDelayed(() -> {
            this.setFlash(false);
            this.invalidateAll();
        }, 170L);
    }

    private void setFlash(boolean on) {
        for (View v : this.views) {
            if (!(v instanceof GestureView)) continue;
            ((GestureView)v).flash = on;
        }
    }

    private void broadcastRequestQuery(int dir) {
        String dirKey = dir >= 0 && dir < 8 ? EdgeGesturePrefs.DIR_KEYS[dir] : "";
        try {
            Intent ev = new Intent("com.twofortyfouram.locale.intent.action.REQUEST_QUERY");
            ev.putExtra("com.twofortyfouram.locale.intent.extra.ACTIVITY", this.getPackageName() + ".TaskerEventConfigActivity");
            TaskerEventProtocol.addEventData((Intent)ev, (String)dirKey);
            this.sendBroadcast(ev);
        }
        catch (Throwable t) {
            Log.w((String)TAG, (String)("\u4e0a\u62a5\u4e8b\u4ef6\u67e5\u8be2\u5931\u8d25: " + t));
        }
    }

    private void saveHandle() {
        try {
            if (this.activeHandle == null) {
                return;
            }
            ArrayList<EdgeGesturePrefs.Handle> all = new ArrayList<EdgeGesturePrefs.Handle>(EdgeGesturePrefs.getHandles((Context)this));
            for (int i = 0; i < all.size(); ++i) {
                if (((EdgeGesturePrefs.Handle)all.get((int)i)).id != this.activeHandle.id) continue;
                all.set(i, this.activeHandle);
                break;
            }
            EdgeGesturePrefs.saveHandles((Context)this, all);
            this.toast("\u4f4d\u7f6e\u5df2\u4fdd\u5b58");
        }
        catch (Throwable t) {
            Log.w((String)TAG, (String)("\u4fdd\u5b58\u624b\u67c4\u4f4d\u7f6e\u5931\u8d25: " + t));
        }
        this.invalidateAll();
    }

    private void ensureOcrService() {
        try {
            if (!OCRService.isEnabled((Context)this)) {
                return;
            }
            if (OCRService.isRunning) {
                return;
            }
            Intent s = new Intent((Context)this, OCRService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                this.startForegroundService(s);
            } else {
                this.startService(s);
            }
        }
        catch (Throwable t) {
            Log.w((String)TAG, (String)("\u62c9\u8d77 OCR \u670d\u52a1\u5931\u8d25: " + t));
        }
    }

    private static int dirOf(float dx, float dy) {
        if (dx == 0.0f && dy == 0.0f) {
            return -1;
        }
        double deg = Math.toDegrees(Math.atan2(-dy, dx));
        int oct = (int)Math.round(deg / 45.0);
        if (oct > 4) {
            oct -= 8;
        }
        if (oct < -4) {
            oct += 8;
        }
        int[] map = new int[]{6, 5, 4, 3, 2, 1, 0, 7, 6};
        return map[oct + 4];
    }

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays((Context)this);
        }
        return true;
    }

    private int[] screenSize() {
        try {
            WindowManager w;
            WindowManager windowManager = w = this.wm != null ? this.wm : (WindowManager)this.getSystemService("window");
            if (Build.VERSION.SDK_INT >= 30) {
                Rect r = w.getCurrentWindowMetrics().getBounds();
                return new int[]{r.width(), r.height()};
            }
            DisplayMetrics dm = new DisplayMetrics();
            w.getDefaultDisplay().getRealMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels};
        }
        catch (Throwable t) {
            DisplayMetrics dm = this.getResources().getDisplayMetrics();
            return new int[]{dm.widthPixels, dm.heightPixels};
        }
    }

    private int dp(float v) {
        return (int)(v * this.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void goForeground() {
        try {
            NotificationManager nm;
            if (Build.VERSION.SDK_INT >= 26 && (nm = (NotificationManager)this.getSystemService(NotificationManager.class)) != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, (CharSequence)"\u8fb9\u7f18\u624b\u52bf\u89e6\u53d1", 1);
                ch.setDescription("\u663e\u793a\u8fb9\u7f18\u6ed1\u52a8\u624b\u67c4\u65f6\u9a7b\u7559");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent((Context)this, MainActivity.class);
            open.addFlags(0x10000000);
            int piFlags = Build.VERSION.SDK_INT >= 23 ? 0x4000000 : 0;
            PendingIntent pi = PendingIntent.getActivity((Context)this, (int)0, (Intent)open, (int)piFlags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder((Context)this, CHANNEL_ID) : new Notification.Builder((Context)this);
            Notification n = b.setContentTitle((CharSequence)"\u8fb9\u7f18\u624b\u52bf\u5df2\u5f00\u542f").setContentText((CharSequence)"\u6ed1\u52a8\u624b\u67c4\u5df2\u663e\u793a\u5728\u5c4f\u5e55\u8fb9\u7f18").setSmallIcon(R.drawable.dot_ok).setContentIntent(pi).setOngoing(true).build();
            this.startForeground(23042, n);
        }
        catch (Throwable t) {
            Log.w((String)TAG, (String)("\u8f6c\u4e3a\u524d\u53f0\u670d\u52a1\u5931\u8d25: " + t));
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText((Context)this.getApplicationContext(), (CharSequence)msg, (int)0).show();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private class HandleView
    extends GestureView {
        HandleView(EdgeGesturePrefs.Handle h, WindowManager.LayoutParams p) {
            super(h, p);
        }
        @Override
        protected boolean hitTest(float rawX, float rawY) {
            return true;
        }

        /**
         * 每次布局完成都重报一次手势排除区 —— MacroDroid 侧边栏用的就是这个时机。
         *
         * 反编译里它的做法很直接：{@code ExcludeGestureFrameLayout.onLayout()}
         * 先 super，再用刚拿到的 (l,t,r,b) 造 Rect 交给
         * {@code ViewCompat.setSystemGestureExclusionRects()}。整个类就干这一件事，
         * 它被直接当作手柄窗口的根视图。
         *
         * 为什么非要挂在 onLayout：排除区是「窗口本地坐标 → 由框架换算成屏幕坐标」，
         * 因此只要窗口的**位置或尺寸**变了，之前那次上报的换算基准就过期了。
         * 改 y（拖动、防烧屏漂移）、切边、改宽高、系统栏/旋转导致重新布局，
         * 全都会走到这里；只在 onDraw 里报，纯移动窗口时不一定会有重绘帧，
         * 就会停留在旧的换算结果上。
         *
         * 与 {@link #syncGestureExclusion()} 里的 equals 去重配合，
         * 布局没实质变化时不会重复打日志，也不会反复走系统调用。
         */
        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            try {
                this.syncGestureExclusion();
            }
            catch (Throwable th) {
                AppLog.w(EdgeGestureService.TAG, "布局后重报手势排除区失败: " + th);
            }
        }


        protected void onDraw(Canvas c) {
            super.onDraw(c);
            // onDraw 里抛异常会一路冒到 ViewRootImpl，直接崩掉进程 ——
            // 「点菜单闪退」最典型的成因就在这里（第三方图标 Drawable、
            // 异常尺寸、字体度量等都可能抛）。所以整段绘制必须有兜底：
            // 画不出来只是这一帧难看，绝不能把应用带走。
            try {
                if (this.menuOpen) {
                    this.drawMenu(c);
                } else {
                    this.drawBar(c, this.handle, 0.0f, this.getHeight(), this.handle.isEdgeLeft(), this.flash);
                }
            } catch (Throwable t) {
                AppLog.e(EdgeGestureService.TAG, "悬浮窗绘制异常（已跳过本帧）: menuOpen="
                        + this.menuOpen + " 尺寸=" + this.getWidth() + "x" + this.getHeight(), t);
            }
            this.syncGestureExclusion();
        }

        @Override
        protected List<Rect> buildExclusionRects() {
            ArrayList<Rect> out = new ArrayList<Rect>(1);
            if (!this.handle.blockGesture) {
                // 该手柄没开「屏蔽系统手势」，属于配置项，不是故障。
                // 报「屏蔽没生效」时先看这里 —— 很可能是开关没开。
                AppLog.w(EdgeGestureService.TAG, "该手柄未开启屏蔽系统手势: " + this.handle.name);
                return out;
            }
            out.add(new Rect(0, 0, this.getWidth(), this.getHeight()));
            return out;
        }

        @Override
        protected void applyDrag(float dx, float dy) {
            float centerX;
            boolean left;
            int[] scr = EdgeGestureService.this.screenSize();
            int maxY = Math.max(0, scr[1] - this.params.height);
            int ny = (int)(this.dragStartY + dy);
            if (ny < 0) {
                ny = 0;
            }
            if (ny > maxY) {
                ny = maxY;
            }
            boolean bl = left = (centerX = (float)this.params.width / 2.0f + dx) < (float)scr[0] / 2.0f;
            if (left != this.handle.isEdgeLeft()) {
                this.handle.edge = left ? 0 : 1;
            }
            this.params.gravity = (this.handle.isEdgeLeft() ? 0x800003 : 0x800005) | 0x30;
            this.params.x = 0;
            this.params.y = ny;
            // 用户手动拖过之后，基准就是拖动结果；
            // 否则下一次漂移会把窗口拉回「拖动前」的旧基准附近。
            this.burninBaseY = ny;
            this.handle.offsetPercent = maxY == 0 ? 0 : Math.round((float)ny * 100.0f / (float)maxY);
            try {
                EdgeGestureService.this.wm.updateViewLayout((View)this, (ViewGroup.LayoutParams)this.params);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            this.syncGestureExclusion();
            this.invalidate();
        }

        @Override
        protected void applyDragEnd() {
            EdgeGestureService.this.saveHandle();
        }
    }

    private abstract class GestureView
    extends View {
        protected EdgeGesturePrefs.Handle handle;
        protected final WindowManager.LayoutParams params;
        protected final Paint paint;
        protected final RectF rect;
        protected float downRawX;
        protected float downRawY;
        protected float dragStartY;
        protected boolean fired;
        protected boolean moved;
        protected boolean dragMode;
        protected boolean flash;
        protected int activePointerId;
        protected final Runnable longPress;
        private final List<Rect> reportedRects;
        protected boolean menuOpen;
        protected int menuOpenDir;
        private List<EdgeGesturePrefs.MenuItem> menuItems;
        private final List<Drawable> menuIcons;
        private final List<RectF> menuRects;
        private int menuHover;
        private int menuLayoutW;
        private int menuLayoutH;
        private long lastHitLog;
        private float menuIconPx;
        private boolean menuShowText;
        private boolean menuArc;
        private float menuArcX;
        private float menuArcY;
        private float menuAnchorRawX;
        private float menuAnchorRawY;
        private float menuArcFacing;
        private int menuArcPerRing;
        private boolean menuFillLight;
        private int menuBgColor;
        private float menuTextPx;
        private float menuTextGapPx;
        private float menuArcTextPx;
        private float menuArcTextGapPx;
        private int menuTextColor;
        private int menuSelColor;
        private float menuHitScale;
        private float menuCx;
        private float menuCy;
        private float menuRing0R;
        private float menuArcIconPx;
        private float menuArcCell;
        private int menuSavedWidth;
        private int menuSavedHeight;
        private int menuSavedGravity;
        private int menuSavedX;
        private int menuSavedY;
        /**
         * 防烧屏漂移的**基准窗口 y**（未漂移时应处的位置，px）。
         *
         * 为什么单独存一个基准，而不用 params.y：
         *   params.y 是「当前显示位置」，漂移会把它改掉；
         *   而 {@link #resetBurninShift()} 和下次漂移都需要知道
         *   「回到哪儿 / 从哪儿算偏移」，所以基准必须另存。
         *   Integer.MIN_VALUE = 还没定过基准，首次漂移时再取。
         *
         * 用 protected 而非 private：{@link HandleView} 是它的**兄弟**内部类
         * （不是子类），兄弟持有的字段在 Java 里不算「继承成员」，
         * private 会导致 `this.burninBaseY` 报 has private access。
         */
        protected int burninBaseY = Integer.MIN_VALUE;

        /**
         * 手指此刻是否正按在这个手柄上。
         *
         * 注意不能用 activePointerId 代替：那个字段只在 ACTION_DOWN 时被赋值，
         * 抬手时并不会复位成 -1，拿它判断会变成「一旦碰过就永远算按着」，
         * 漂移从此再不执行。
         */
        private boolean touching;

        /** 「低版本无法屏蔽系统手势」只提示一次，免得刷屏。 */
        private boolean lowApiLogged;

        /** drawMenu 是每帧调用的，形状日志只记头两帧。 */
        private int menuDrawLogged;
        /**
         * 触摸类日志（按下 / 被取消）最多记这么多条。
         *
         * 这两条日志是给「屏蔽系统手势到底有没有用」做判据的，
         * 平时会跟着每一次触摸刷 —— 限个总量，长时间用也不至于把
         * 512KB 的日志轮转掉、把别的线索顶出去。
         */
        private int touchLogged;

        /** 把矩形列表拼成可读文本，给日志用。 */
        private String rectsToString(List<Rect> list) {
            StringBuilder sb = new StringBuilder();
            for (Rect r : list) {
                if (sb.length() > 0) sb.append(' ');
                sb.append('[').append(r.left).append(',').append(r.top)
                        .append(',').append(r.right).append(',').append(r.bottom).append(']');
            }
            return sb.toString();
        }

        /**
         * 本窗口在屏幕上的实际矩形 —— 排查「排除区坐标对不对」用。
         *
         * 上报的矩形是**窗口本地坐标**（[0,0,宽,高]），框架会自动换算到
         * 屏幕坐标再交给系统，所以系统眼里的生效范围 = 窗口屏幕位置 + 上报矩形。
         * 这条日志把窗口位置也记下来，两边一拼就知道那块区域到底盖在哪。
         */
        private String windowRectOnScreen() {
            try {
                int[] loc = new int[2];
                this.getLocationOnScreen(loc);
                return "[" + loc[0] + "," + loc[1] + " "
                        + this.getWidth() + "x" + this.getHeight() + "]"
                        + " 已布局=" + this.isLaidOut()
                        + " 已附着=" + this.isAttachedToWindow();
            }
            catch (Throwable t) {
                return "(取窗口位置失败:" + t + ")";
            }
        }

        /**
         * 系统手势预留区：左右边缘各有这么宽的一段是「系统独占」的。
         *
         * 排查「屏蔽了却没效果」时看这个：手柄如果比它还窄，多出来的部分
         * 本来就不受系统手势影响；真要屏蔽，得整块盖住这段边缘区才有效。
         * 悬浮窗常常取不到（返回 0 或抛异常），取不到就记 unknown，不影响功能。
         */
        private String systemGestureInsetsDesc() {
            try {
                WindowInsets wi = this.getRootWindowInsets();
                if (wi == null) {
                    return "unknown(insets=null)";
                }
                Insets si = wi.getSystemGestureInsets();
                return "left=" + si.left + " right=" + si.right
                        + " top=" + si.top + " bottom=" + si.bottom;
            }
            catch (Throwable t) {
                return "unknown(" + t + ")";
            }
        }

        GestureView(EdgeGesturePrefs.Handle h, WindowManager.LayoutParams p) {
            super((Context)EdgeGestureService.this);
            this.paint = new Paint(1);
            this.rect = new RectF();
            this.activePointerId = -1;
            this.longPress = new Runnable(){

                @Override
                public void run() {
                    if (GestureView.this.moved) {
                        return;
                    }
                    GestureView.this.dragMode = true;
                    GestureView.this.setAlpha(0.85f);
                    EdgeGesturePrefs.buzz((Context)EdgeGestureService.this, true);
                    EdgeGestureService.this.toast("\u677e\u624b\u5373\u4fdd\u5b58\u4f4d\u7f6e");
                    GestureView.this.invalidate();
                }
            };
            this.reportedRects = new ArrayList<Rect>();
            this.menuOpenDir = -1;
            this.menuItems = new ArrayList<EdgeGesturePrefs.MenuItem>();
            this.menuIcons = new ArrayList<Drawable>();
            this.menuRects = new ArrayList<RectF>();
            this.menuHover = -1;
            this.menuLayoutW = -1;
            this.menuLayoutH = -1;
            this.menuArcPerRing = 5;
            this.menuFillLight = true;
            this.menuBgColor = -1;
            this.menuTextPx = 0.0f;
            this.menuTextGapPx = 0.0f;
            this.menuArcTextPx = 0.0f;
            this.menuArcTextGapPx = 0.0f;
            this.menuTextColor = -14540254;
            this.menuSelColor = -16718337;
            this.menuHitScale = 1.0f;
            this.handle = h;
            this.params = p;
            this.paint.setStyle(Paint.Style.FILL);
        }

        protected int dp(float v) {
            return EdgeGestureService.this.dp(v);
        }

        protected float dragAnchor() {
            return this.params.y;
        }

        /**
         * 防烧屏：在垂直方向随机挪一点点，并立刻应用到窗口。
         *
         * 只动 y 不动 x —— 手柄要始终贴着屏幕边缘，横向移动会破坏这个语义。
         * 偏移不会写回 {@code handle.offsetPercent}，所以它是临时的：
         * 重启 / 关掉开关就回到用户设定好的位置。
         *
         * @param rangePx 漂移半径（px），实际落在 [-rangePx, +rangePx] 内
         */
        protected void applyBurninShift(Random rnd, int rangePx) {
            if (rangePx <= 0) {
                return;
            }
            // 菜单展开时窗口是全屏的（params 已被改写），拖动时也不该插手
            if (this.menuOpen || this.dragMode) {
                return;
            }
            // 手指正按在手柄上时不动 —— 判定虽然用的是 rawX/rawY（屏幕坐标，
            // 不受窗口平移影响），但按住期间平白多一次窗口事务没有意义，
            // 也免得「手指还没抬，条子先跑了」的观感。
            if (this.touching) {
                return;
            }
            if (!this.isAttachedToWindow()) {
                return;
            }
            if (this.burninBaseY == Integer.MIN_VALUE) {
                this.burninBaseY = this.params.y;
            }
            int[] scr = EdgeGestureService.this.screenSize();
            int maxY = Math.max(0, scr[1] - this.params.height);
            int dy = rnd.nextInt(rangePx * 2 + 1) - rangePx;
            int ny = this.burninBaseY + dy;
            if (ny < 0) {
                ny = 0;
            }
            if (ny > maxY) {
                ny = maxY;
            }
            if (ny == this.params.y) {
                return;
            }
            this.params.y = ny;
            try {
                EdgeGestureService.this.wm.updateViewLayout((View)this, (ViewGroup.LayoutParams)this.params);
            }
            catch (Throwable t) {
                Log.w((String)EdgeGestureService.TAG, (String)("\u9632\u70e7\u5c4f\u6f02\u79fb\u5931\u8d25: " + t));
            }
        }

        /** 拨回基准位置（关掉防烧屏 / 幅度设成 0 时调用）。 */
        protected void resetBurninShift() {
            if (this.burninBaseY == Integer.MIN_VALUE) {
                return;
            }
            if (this.params.y == this.burninBaseY) {
                return;
            }
            this.params.y = this.burninBaseY;
            try {
                EdgeGestureService.this.wm.updateViewLayout((View)this, (ViewGroup.LayoutParams)this.params);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }

        protected abstract boolean hitTest(float var1, float var2);

        protected abstract void applyDrag(float var1, float var2);

        protected abstract void applyDragEnd();

        protected abstract List<Rect> buildExclusionRects();

        /**
         * 上一次上报时的**窗口几何指纹**。
         *
         * 光比较矩形内容是不够的：手柄窗口的矩形永远是 {@code (0,0,w,h)}，
         * 改 y 拖动一下、防烧屏漂移一下，矩形一个字符都没变，但排除区在屏幕
         * 上的实际位置已经整块平移了 —— 此时框架需要重新做一次
         * 「窗口本地坐标 → 屏幕坐标」换算。只按矩形去重会把这种必要的重报
         * 连同日志一起吞掉，现象就是「位置明明变了，排除区还留在老地方」。
         *
         * 所以指纹里必须带上窗口的 x/y/宽/高/gravity 和贴哪一边，
         * 以及当前是否已布局（未布局时算出来的位置不作数，等 onLayout 再来）。
         */
        private String exclusionKey = "";

        /** 组装当前窗口几何指纹；取不到关键信息时返回空串表示「这次不作数」。 */
        private String exclusionKeyNow() {
            if (!this.isAttachedToWindow()) {
                return "";
            }
            return this.params.width + "x" + this.params.height
                    + "@" + this.params.x + "," + this.params.y
                    + "/g" + this.params.gravity
                    + "/e" + (this.handle.isEdgeLeft() ? "L" : "R");
        }

        protected void syncGestureExclusion() {
            if (Build.VERSION.SDK_INT < 29) {
                // setSystemGestureExclusionRects 是 API 29 才有的平台能力。
                // 低于 29 的机器（Android 9 及以下）没有任何原生手段屏蔽
                // 系统侧滑返回 —— 用户报「屏蔽没生效」时先看这一条。
                if (!lowApiLogged) {
                    lowApiLogged = true;
                    AppLog.w(EdgeGestureService.TAG, "系统手势屏蔽不可用: sdk="
                            + Build.VERSION.SDK_INT + " < 29（该机型无平台 API）");
                }
                return;
            }
            if (this.menuOpen) {
                return;
            }
            List<Rect> want = this.buildExclusionRects();
            if (want == null) {
                want = new ArrayList<Rect>(0);
            }
            String key = this.exclusionKeyNow();
            // 矩形没变 **且** 窗口几何没变，才算真的不用重报。
            if (want.equals(this.reportedRects) && key.equals(this.exclusionKey)) {
                return;
            }
            this.reportedRects.clear();
            for (Rect r : want) {
                this.reportedRects.add(new Rect(r));
            }
            this.exclusionKey = key;
            try {
                this.setSystemGestureExclusionRects(want);
                // ---- 诊断：这次上报系统认不认 ----
                // 只记「调过了」说明不了问题，下面三项才是判据：
                //   回读   —— View 层有没有把值静默丢掉
                //   窗口   —— 换算到屏幕上是哪一块（系统按屏幕坐标判定）
                //   手势区 —— 系统在边缘独占多宽，手柄有没有真的压上去
                String back;
                try {
                    List<Rect> got = this.getSystemGestureExclusionRects();
                    back = " 回读=" + (got == null ? "null" : rectsToString(got));
                }
                catch (Throwable t2) {
                    back = " 回读失败:" + t2;
                }
                AppLog.i(EdgeGestureService.TAG, "手势排除区已上报: " + want.size()
                        + " 块 " + rectsToString(want)
                        + " (sdk=" + Build.VERSION.SDK_INT + ")"
                        + " 窗口=" + this.windowRectOnScreen()
                        + " 手势区=" + this.systemGestureInsetsDesc()
                        + back);
            }
            catch (Throwable t) {
                Log.w((String)EdgeGestureService.TAG, (String)("\u624b\u52bf\u6392\u9664\u4e0a\u62a5\u5931\u8d25: " + t));
                AppLog.e(EdgeGestureService.TAG, "手势排除区上报失败 (sdk="
                        + Build.VERSION.SDK_INT + ")", t);
            }
        }

        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (this.menuOpen && (w != this.menuLayoutW || h != this.menuLayoutH)) {
                this.menuRects.clear();
            }
            this.menuRects.clear();
            if (this.menuOpen) {
                this.menuHover = -1;
            }
            this.syncGestureExclusion();
        }

        protected boolean menuConfigured() {
            try {
                if (!EdgeGesturePrefs.isMenuEnabled(this.getContext())) {
                    return false;
                }
                return !EdgeGesturePrefs.parseMenuItems(EdgeGesturePrefs.getMenuItemsRaw(this.getContext())).isEmpty();
            }
            catch (Throwable t) {
                return false;
            }
        }

        /**
         * 弹出滑动菜单。
         *
         * 外面套一层兜底：菜单这摊子牵扯配置解析、图标加载（第三方 App 的
         * Drawable 什么样都有）、窗口改全屏、实时布局，任何一环在别的机型上
         * 出意外，都不该让整个应用直接退出。真出事了就有记录地退开，
         * 用户看到的是「这次没弹出来」，而不是「应用不见了」。
         */
        protected boolean tryOpenMenu(int dir, float rawX, float rawY) {
            try {
                return this.tryOpenMenuInner(dir, rawX, rawY);
            } catch (Throwable t) {
                AppLog.e(EdgeGestureService.TAG, "菜单展开异常，已安全退出本次展开", t);
                this.menuOpen = false;
                this.menuHover = -1;
                try {
                    this.menuItems = new ArrayList<EdgeGesturePrefs.MenuItem>();
                    this.menuIcons.clear();
                    this.menuRects.clear();
                    int w = this.menuSavedWidth > 0
                            ? this.menuSavedWidth : Math.max(1, this.dp(this.handle.width));
                    int h = this.menuSavedHeight > 0
                            ? this.menuSavedHeight : Math.max(1, this.dp(this.handle.height));
                    this.params.width = w;
                    this.params.height = h;
                    this.params.gravity = this.menuSavedGravity != 0
                            ? this.menuSavedGravity
                            : ((this.handle.isEdgeLeft() ? 0x800003 : 0x800005) | 0x30);
                    this.params.x = 0;
                    this.params.y = this.menuSavedY;
                    EdgeGestureService.this.wm.updateViewLayout((View)this, (ViewGroup.LayoutParams)this.params);
                } catch (Throwable t2) {
                    AppLog.e(EdgeGestureService.TAG, "菜单异常后恢复窗口失败", t2);
                }
                this.invalidate();
                return false;
            }
        }

        private boolean tryOpenMenuInner(int dir, float rawX, float rawY) {
            List<EdgeGesturePrefs.MenuItem> items = EdgeGesturePrefs.getMenuItems(this.getContext());
            if (items.isEmpty()) {
                return false;
            }
            this.menuItems = items;
            this.menuIcons.clear();
            for (EdgeGesturePrefs.MenuItem it : items) {
                this.menuIcons.add(this.loadMenuIcon(it.source));
            }
            this.menuIconPx = this.dp(EdgeGesturePrefs.getMenuIconDp(this.getContext()));
            this.menuShowText = EdgeGesturePrefs.isMenuShowText(this.getContext());
            String layout = EdgeGesturePrefs.getMenuLayout(this.getContext());
            this.menuArc = "arc".equals(layout);
            this.menuArcPerRing = EdgeGesturePrefs.getMenuArcPerRing(this.getContext());
            this.menuFillLight = EdgeGesturePrefs.isMenuLightBubble(this.getContext());
            this.menuBgColor = EdgeGesturePrefs.getMenuBgColor(this.getContext());
            this.menuTextColor = EdgeGesturePrefs.getMenuTextColor(this.getContext());
            this.menuSelColor = EdgeGesturePrefs.getMenuSelColor(this.getContext());
            this.menuTextPx = this.dp(EdgeGesturePrefs.getMenuTextDp(this.getContext()));
            this.menuTextGapPx = this.dp(EdgeGesturePrefs.getMenuTextGap(this.getContext()));
            this.menuArcTextPx = this.menuTextPx;
            this.menuArcTextGapPx = this.menuTextGapPx;
            this.menuHitScale = (float)EdgeGesturePrefs.getMenuHitScale(this.getContext()) / 100.0f;
            // ---- 菜单展开：把当时的配置与触发点全记下来 ----
            // 这个功能在别的机型上闪退过，而这些参数（项数、图标数、
            // 布局、命中倍率）就是复现的钥匙，出问题时必须一眼可见。
            int iconNull = 0;
            for (Drawable d : this.menuIcons) {
                if (d == null) iconNull++;
            }
            AppLog.i(EdgeGestureService.TAG, "菜单展开请求: 方向=" + dir
                    + " 项数=" + items.size() + " 无图标=" + iconNull
                    + " 图标=" + (int)this.menuIconPx + "px 显示文字=" + this.menuShowText
                    + " 布局=" + layout + " 每排=" + this.menuArcPerRing
                    + " 每排覆盖=" + EdgeGesturePrefs.getMenuRowCounts(this.getContext()).length
                    + " 命中倍率=" + this.menuHitScale
                    + " 锚点=(" + (int)rawX + "," + (int)rawY + ")"
                    + " 窗口原=" + this.menuSavedWidth + "x" + this.menuSavedHeight
                    + " y=" + this.menuSavedY);
            this.menuAnchorRawX = rawX;
            this.menuAnchorRawY = rawY;
            this.menuArcFacing = (float)Math.toRadians((double)dir * 45.0 - 90.0);
            this.menuSavedWidth = this.params.width;
            this.menuSavedHeight = this.params.height;
            this.menuSavedGravity = this.params.gravity;
            this.menuSavedX = this.params.x;
            this.menuSavedY = this.params.y;
            this.params.width = -1;
            this.params.height = -1;
            this.params.gravity = 0x800033;
            this.params.x = 0;
            this.params.y = 0;
            try {
                EdgeGestureService.this.wm.updateViewLayout((View)this, (ViewGroup.LayoutParams)this.params);
            }
            catch (Throwable t) {
                Log.w((String)EdgeGestureService.TAG, (String)("\u83dc\u5355\u5c55\u5f00\u5931\u8d25: " + t));
                AppLog.e(EdgeGestureService.TAG, "菜单展开失败（改全屏窗口被拒）", t);
                this.params.width = this.menuSavedWidth;
                this.params.height = this.menuSavedHeight;
                this.params.gravity = this.menuSavedGravity;
                this.params.x = this.menuSavedX;
                this.params.y = this.menuSavedY;
                return false;
            }
            this.menuOpen = true;
            this.menuOpenDir = dir;
            this.menuHover = -1;
            this.clearGestureExclusion();
            this.invalidate();
            AppLog.i(EdgeGestureService.TAG, "菜单已展开: 全屏 " + this.getWidth() + "x" + this.getHeight());
            return true;
        }

        protected void closeMenu() {
            if (!this.menuOpen) {
                return;
            }
            AppLog.i(EdgeGestureService.TAG, "菜单收起: 悬停项=" + this.menuHover
                    + " 项数=" + this.menuItems.size());
            this.menuOpen = false;
            this.menuLayoutW = -1;
            this.menuLayoutH = -1;
            this.menuHover = -1;
            this.menuItems = new ArrayList<EdgeGesturePrefs.MenuItem>();
            this.menuIcons.clear();
            this.menuRects.clear();
            this.params.width = this.menuSavedWidth;
            this.params.height = this.menuSavedHeight;
            this.params.gravity = this.menuSavedGravity;
            this.params.x = this.menuSavedX;
            this.params.y = this.menuSavedY;
            try {
                EdgeGestureService.this.wm.updateViewLayout((View)this, (ViewGroup.LayoutParams)this.params);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            this.reportedRects.clear();
            this.syncGestureExclusion();
            this.invalidate();
        }

        /**
         * 图标画不出来时的兜底：就地画文字。
         *
         * 和「本来就没配图标」的显示保持一致（取前两个字），
         * 这样用户看到的是「这个按钮没图标」，而不是「少了一个按钮」。
         */
        private void drawMenuTextFallback(Canvas c, int i, float cx0, float cy0,
                                          float iconPx, boolean on, Paint p) {
            try {
                if (i < 0 || i >= this.menuItems.size()) return;
                String t = this.menuItems.get(i).text;
                if (t == null || t.length() == 0) return;
                String label = t.length() > 2 ? t.substring(0, 2) : t;
                p.setColor(on ? -1 : (this.isLightColor(this.menuBgColor) ? -14540254 : -1));
                p.setTextSize(iconPx * (label.length() > 1 ? 0.26f : 0.34f));
                p.setTextAlign(Paint.Align.CENTER);
                float cy = cy0 - (p.descent() + p.ascent()) / 2.0f;
                c.drawText(label, cx0, cy, p);
            } catch (Throwable ignored) {
            }
        }

        private void clearGestureExclusion() {
            if (Build.VERSION.SDK_INT < 29) {
                return;
            }
            if (this.reportedRects.isEmpty()) {
                return;
            }
            this.reportedRects.clear();
            try {
                this.setSystemGestureExclusionRects(new ArrayList());
                AppLog.i(EdgeGestureService.TAG, "手势排除区已清空（菜单展开时不挡系统手势）");
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }

        private Drawable loadMenuIcon(String source) {
            Drawable d = MenuIcons.load((Context)this.getContext(), (String)source);
            if (d != null) {
                return d;
            }
            try {
                String s;
                String string = s = source == null ? "" : source.trim();
                if (!s.startsWith("app:")) {
                    return null;
                }
                String pkg = s.substring(4).trim();
                if (pkg.length() == 0) {
                    return null;
                }
                Intent it = this.getContext().getPackageManager().getLaunchIntentForPackage(pkg);
                if (it == null) {
                    Log.w((String)EdgeGestureService.TAG, (String)("\u56fe\u6807\u53d6\u4e0d\u5230\uff0c\u53ef\u80fd\u88ab\u5305\u53ef\u89c1\u6027\u6321\u4f4f(" + pkg + ")"));
                    return null;
                }
                ResolveInfo ri = this.getContext().getPackageManager().resolveActivity(it, 0);
                if (ri != null && ri.activityInfo != null) {
                    return ri.activityInfo.loadIcon(this.getContext().getPackageManager());
                }
                if (it.getComponent() != null) {
                    return this.getContext().getPackageManager().getActivityIcon(it.getComponent());
                }
            }
            catch (Throwable t) {
                Log.w((String)EdgeGestureService.TAG, (String)("\u56fe\u6807\u515c\u5e95\u52a0\u8f7d\u4e5f\u5931\u8d25(" + source + "): " + t));
            }
            return null;
        }

        private void layoutMenuRects() {
            this.menuRects.clear();
            int n = this.menuItems.size();
            if (n == 0) {
                return;
            }
            int w = this.getWidth();
            int h = this.getHeight();
            int[] scr = EdgeGestureService.this.screenSize();
            if (scr[0] > 0 && scr[1] > 0 && ((float)w < (float)scr[0] * 0.8f || (float)h < (float)scr[1] * 0.8f)) {
                w = scr[0];
                h = scr[1];
            }
            if (w <= 0 || h <= 0) {
                AppLog.w(EdgeGestureService.TAG, "菜单布局放弃: 尺寸非法 w=" + w + " h=" + h
                        + " 窗口=" + this.getWidth() + "x" + this.getHeight());
                return;
            }
            this.menuLayoutW = w;
            this.menuLayoutH = h;
            int[] loc = new int[2];
            this.getLocationOnScreen(loc);
            this.menuArcX = this.menuAnchorRawX - (float)loc[0];
            this.menuArcY = this.menuAnchorRawY - (float)loc[1];
            float gap = this.dp(EdgeGesturePrefs.getMenuGap(this.getContext()));
            float textH = this.menuShowText ? this.menuTextPx + this.menuTextGapPx : 0.0f;
            float cell = this.menuIconPx + textH;
            float pad = this.dp(12.0f);
            this.layoutMenuLine(w, h, cell, gap, pad, n);
            AppLog.i(EdgeGestureService.TAG, "菜单布局: 画布=" + w + "x" + h
                    + " 窗口=" + this.getWidth() + "x" + this.getHeight()
                    + " 项数=" + n + " 格子=" + (int)cell + " 图标=" + (int)this.menuIconPx
                    + " 文字高=" + (int)textH + " 间距=" + (int)gap
                    + " 缩放后图标=" + (int)this.menuArcIconPx
                    + " 圆心=(" + (int)this.menuCx + "," + (int)this.menuCy + ")");
            if (w != this.getWidth() || h != this.getHeight()) {
                Log.d((String)EdgeGestureService.TAG, (String)("LAYOUT \u7528\u5c4f\u5e55\u5c3a\u5bf8\u515c\u5e95: " + w + "x" + h + " \u7a97\u53e3=" + this.getWidth() + "x" + this.getHeight() + " \u56fe\u6807=" + (int)this.menuArcIconPx));
            }
        }

        private void layoutMenuLine(int w, int h, float cell, float gap, float pad, int n) {
            float cc;
            float envH;
            int take;
            int defPerRow = Math.max(1, this.menuArcPerRing);
            int[] perRow = EdgeGesturePrefs.getMenuRowCounts(this.getContext());
            int[] cnt = new int[n + 1];
            int rows = 0;
            for (int left = n; left > 0 && rows <= n; left -= take, ++rows) {
                int n2 = take = rows < perRow.length ? perRow[rows] : defPerRow;
                if (take < 1) {
                    take = 1;
                }
                if (take > left) {
                    take = left;
                }
                cnt[rows] = take;
            }
            float sp = this.menuIconPx + gap;
            float rowGap = this.menuIconPx + (float)this.dp(EdgeGesturePrefs.getMenuRingGap(this.getContext()));
            float dirX = 0.70710677f;
            float dirY = -0.70710677f;
            float offX = -0.70710677f;
            float offY = -0.70710677f;
            float[] relX = new float[n];
            float[] relY = new float[n];
            float relMinX = 0.0f;
            float relMinY = 0.0f;
            float relMaxX = 0.0f;
            float relMaxY = 0.0f;
            int idx = 0;
            for (int r = 0; r < rows && idx < n; ++r) {
                for (int c = 0; c < cnt[r] && idx < n; ++idx, ++c) {
                    float u = ((float)c - (float)(cnt[r] - 1) * 0.5f) * sp;
                    float x = u * 0.70710677f + (float)r * rowGap * -0.70710677f;
                    float y = u * -0.70710677f + (float)r * rowGap * -0.70710677f;
                    relX[idx] = x;
                    relY[idx] = y;
                    if (idx == 0) {
                        relMinX = x - cell / 2.0f;
                        relMaxX = x + cell / 2.0f;
                        relMinY = y - cell / 2.0f;
                        relMaxY = y + cell / 2.0f;
                        continue;
                    }
                    relMinX = Math.min(relMinX, x - cell / 2.0f);
                    relMinY = Math.min(relMinY, y - cell / 2.0f);
                    relMaxX = Math.max(relMaxX, x + cell / 2.0f);
                    relMaxY = Math.max(relMaxY, y + cell / 2.0f);
                }
            }
            float MIN_SCALE = 0.12f;
            float availW = Math.max((float)this.dp(4.0f), (float)w - pad * 2.0f);
            float availH = Math.max((float)this.dp(4.0f), (float)h - pad * 2.0f);
            float envW = Math.max(1.0f, relMaxX - relMinX);
            float scale = Math.min(1.0f, Math.min(availW / envW, availH / (envH = Math.max(1.0f, relMaxY - relMinY))));
            if (Float.isNaN(scale) || scale <= 0.0f) {
                AppLog.w(EdgeGestureService.TAG, "菜单缩放异常, 退化为兜底值: scale=" + scale
                        + " 包络=" + envW + "x" + envH + " 可用=" + availW + "x" + availH
                        + " cell=" + cell + " 图标=" + this.menuIconPx);
                scale = 0.12f;
            }
            if (scale < 0.12f) {
                scale = 0.12f;
            }
            float loX = pad - relMinX * scale;
            float hiX = (float)w - pad - relMaxX * scale;
            float loY = pad - relMinY * scale;
            float hiY = (float)h - pad - relMaxY * scale;
            float cx = Math.min(Math.max(this.menuArcX, Math.min(loX, hiX)), Math.max(loX, hiX));
            float cy = Math.min(Math.max(this.menuArcY, Math.min(loY, hiY)), Math.max(loY, hiY));
            float k = scale;
            this.menuArcCell = cc = cell * k;
            float ic = this.menuArcIconPx = cell > 0.0f ? cc * (this.menuIconPx / cell) : this.menuIconPx;
            this.menuArcTextPx = this.menuTextPx * k;
            this.menuArcTextGapPx = this.menuTextGapPx * k;
            this.menuRects.clear();
            for (int i = 0; i < n; ++i) {
                float px = cx + relX[i] * k;
                float py = cy + relY[i] * k;
                this.menuRects.add(new RectF(px - ic / 2.0f, py - ic / 2.0f, px + ic / 2.0f, py + ic / 2.0f));
            }
            this.menuCx = cx;
            this.menuCy = cy;
            this.menuRing0R = 0.0f;
        }

        private boolean isLightColor(int argb) {
            int a = argb >>> 24 & 0xFF;
            int r = argb >> 16 & 0xFF;
            int g = argb >> 8 & 0xFF;
            int b = argb & 0xFF;
            float alpha = (float)a / 255.0f;
            float lum = 0.299f * (float)(r = (int)((float)r * alpha + 204.0f * (1.0f - alpha))) + 0.587f * (float)(g = (int)((float)g * alpha + 204.0f * (1.0f - alpha))) + 0.114f * (float)(b = (int)((float)b * alpha + 204.0f * (1.0f - alpha)));
            return lum > 140.0f;
        }

        private int hitMenu(float rawX, float rawY) {
            try {
                return hitMenuInner(rawX, rawY);
            } catch (Throwable t) {
                // 命中判定出问题只该「点不中」，绝不该把应用打崩
                AppLog.e(EdgeGestureService.TAG, "菜单命中判定异常 手指=("
                        + (int)rawX + "," + (int)rawY + ")", t);
                return -1;
            }
        }

        private int hitMenuInner(float rawX, float rawY) {
            if (this.menuRects.size() != this.menuItems.size()) {
                this.layoutMenuRects();
            }
            if (this.menuArc) {
                long now;
                float iconR = this.menuArcIconPx * 0.5f * this.menuHitScale;
                float rr2 = iconR * iconR;
                int best = -1;
                float bestD2 = Float.MAX_VALUE;
                for (int i = 0; i < this.menuRects.size(); ++i) {
                    float ccy;
                    float ddy;
                    RectF r = this.menuRects.get(i);
                    float ccx = r.centerX();
                    float ddx = rawX - ccx;
                    float d2 = ddx * ddx + (ddy = rawY - (ccy = r.centerY())) * ddy;
                    if (!(d2 <= rr2) || !(d2 < bestD2)) continue;
                    bestD2 = d2;
                    best = i;
                }
                if (best < 0 && this.menuRects.size() > 0 && (now = System.currentTimeMillis()) - this.lastHitLog > 300L) {
                    this.lastHitLog = now;
                    RectF f = this.menuRects.get(0);
                    Log.d((String)EdgeGestureService.TAG, (String)("HIT \u672a\u547d\u4e2d \u624b\u6307=(" + (int)rawX + "," + (int)rawY + ") \u9996\u9879=(" + (int)f.centerX() + "," + (int)f.centerY() + ") \u534a\u5f84=" + (int)iconR + " \u9879\u6570=" + this.menuRects.size()));
                }
                return best;
            }
            for (int i = 0; i < this.menuRects.size(); ++i) {
                if (!this.menuRects.get(i).contains(rawX, rawY)) continue;
                return i;
            }
            return -1;
        }

        protected void drawMenu(Canvas c) {
            float textGap;
            int n = this.menuItems.size();
            if (n == 0) {
                AppLog.w(EdgeGestureService.TAG, "drawMenu 时菜单项为空（配置被清？）");
                return;
            }
            // drawMenu 每帧都跑 —— 这里千万不能无脑记日志，否则一秒钟刷几百行，
            // 有用的信息全被冲掉。只在首帧记两条形状数据（布局结果另有埋点），
            // 真画崩了由下面的图标兜底去记异常。
            if (this.menuDrawLogged < 2) {
                this.menuDrawLogged++;
                AppLog.i(EdgeGestureService.TAG, "菜单首帧: 项数=" + n
                        + " 矩形数=" + this.menuRects.size()
                        + " 图标数=" + this.menuIcons.size()
                        + " 悬停=" + this.menuHover);
            }
            if (this.menuRects.size() != n) {
                this.layoutMenuRects();
            }
            Paint p = new Paint(1);
            Paint stroke = new Paint(1);
            stroke.setStyle(Paint.Style.STROKE);
            float iconPx = this.menuArc ? this.menuArcIconPx : this.menuIconPx;
            float textPx = this.menuArc ? this.menuArcTextPx : this.menuTextPx;
            float f = textGap = this.menuArc ? this.menuArcTextGapPx : this.menuTextGapPx;
            if (this.menuArc && this.menuRing0R > 0.0f) {
                float br = Math.min(iconPx / 2.0f * 0.92f, this.menuRing0R * 0.45f);
                p.setColor(this.menuFillLight ? 0x26FFFFFF : -1725816286);
                c.drawCircle(this.menuCx, this.menuCy, br, p);
                stroke.setStrokeWidth((float)this.dp(1.5f));
                stroke.setColor(this.menuFillLight ? 0x22FFFFFF : 0x55FFFFFF);
                c.drawCircle(this.menuCx, this.menuCy, br, stroke);
            }
            for (int i = 0; i < n; ++i) {
                String t;
                Drawable d;
                RectF r = this.menuRects.get(i);
                boolean on = i == this.menuHover;
                float cx0 = r.centerX();
                float cy0 = r.top + iconPx / 2.0f;
                float rad = iconPx / 2.0f;
                p.setColor(on ? this.menuSelColor : this.menuBgColor);
                c.drawCircle(cx0, cy0, rad, p);
                stroke.setStrokeWidth(on ? (float)this.dp(3.0f) : (float)this.dp(1.5f));
                if (on) {
                    stroke.setColor(-1);
                } else {
                    boolean lightBg = this.isLightColor(this.menuBgColor);
                    stroke.setColor(lightBg ? 0x22000000 : 0x66FFFFFF);
                }
                c.drawCircle(cx0, cy0, rad, stroke);
                Drawable drawable2 = d = i < this.menuIcons.size() ? this.menuIcons.get(i) : null;
                if (d != null) {
                    float side = iconPx * 0.62f;
                    float cx = cx0;
                    float cy = cy0;
                    // 图标绘制兜底：第三方应用的 Drawable 五花八门，
                    // 有的在 setBounds/draw 时会抛异常（自定义 Drawable、
                    // 已失效的 RemoteViews 资源等）。一个图标画不出来
                    // 不该让整个菜单——乃至整个悬浮窗——崩掉。
                    try {
                        d.setBounds(Math.round(cx - side / 2.0f), Math.round(cy - side / 2.0f), Math.round(cx + side / 2.0f), Math.round(cy + side / 2.0f));
                        d.draw(c);
                    } catch (Throwable dt) {
                        AppLog.e(EdgeGestureService.TAG, "菜单图标绘制失败, 该项退化为文字: "
                                + (i < this.menuItems.size() ? this.menuItems.get(i).source : "?"), dt);
                        this.drawMenuTextFallback(c, i, cx0, cy0, iconPx, on, p);
                    }
                } else {
                    String t2 = this.menuItems.get((int)i).text;
                    if (t2 != null && t2.length() > 0) {
                        // 没有图标就用文字顶着，取前两个字 ——
                        // 一个字信息量太少（「微」「设」看不出是哪个应用），
                        // 两个字基本能认出来，又不至于溢出圆底。
                        // 名字本来就只有一个字时，自然只显示一个字。
                        String label = t2.length() > 2 ? t2.substring(0, 2) : t2;
                        p.setColor(on ? -1 : (this.isLightColor(this.menuBgColor) ? -14540254 : -1));
                        // 两个字比一个字宽，字号相应收一点，免得顶出圆底。
                        p.setTextSize(iconPx * (label.length() > 1 ? 0.26f : 0.34f));
                        p.setTextAlign(Paint.Align.CENTER);
                        float cy = cy0 - (p.descent() + p.ascent()) / 2.0f;
                        c.drawText(label, cx0, cy, p);
                    }
                }
                if (!this.menuShowText || (t = this.menuItems.get((int)i).text) == null || t.length() <= 0) continue;
                p.setColor(on ? this.menuSelColor : this.menuTextColor);
                p.setTextSize(textPx);
                p.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm = p.getFontMetrics();
                float ty = r.top + iconPx + textGap - fm.ascent;
                c.drawText(t, cx0, ty, p);
            }
        }

        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case 0: {
                    if (!this.hitTest(e.getX(), e.getY())) {
                        return false;
                    }
                    this.touching = true;
                    // 记下手指落点与窗口位置：一眼看出落点到底在不在
                    // 我们上报给系统的那块排除区里。
                    if (this.touchLogged < 100) {
                        this.touchLogged++;
                        AppLog.i(EdgeGestureService.TAG, "手柄按下: Raw=("
                                + (int)e.getRawX() + "," + (int)e.getRawY() + ")"
                                + " 窗口=" + this.windowRectOnScreen());
                    }
                    this.downRawX = e.getRawX();
                    this.downRawY = e.getRawY();
                    EdgeGestureService.this.activeHandle = this.handle;
                    this.fired = false;
                    this.moved = false;
                    this.dragMode = false;
                    if (this.menuOpen) {
                        this.closeMenu();
                    }
                    this.menuOpen = false;
                    this.menuHover = -1;
                    this.menuRects.clear();
                    this.activePointerId = e.getPointerId(0);
                    this.dragStartY = this.dragAnchor();
                    EdgeGestureService.this.ui.removeCallbacks(this.longPress);
                    EdgeGestureService.this.ui.postDelayed(this.longPress, 380L);
                    // 「按下手柄时触发」分支已下线（见 EVENT_TRIGGER 注释）：
                    // 手指一碰手柄就发宏，和滑动菜单、误触混在一起。
                    this.invalidate();
                    return true;
                }
                case 2: {
                    int dir;
                    int pi = e.findPointerIndex(this.activePointerId);
                    if (pi < 0) {
                        return true;
                    }
                    float dx = e.getRawX(pi) - this.downRawX;
                    float dy = e.getRawY(pi) - this.downRawY;
                    float dist = (float)Math.hypot(dx, dy);
                    if (this.menuOpen) {
                        int idx = this.hitMenu(e.getX(pi), e.getY(pi));
                        if (idx != this.menuHover) {
                            this.menuHover = idx;
                            this.invalidate();
                        }
                        return true;
                    }
                    if (this.dragMode) {
                        this.applyDrag(dx, dy);
                        return true;
                    }
                    if (!this.moved && dist > (float)this.dp(10.0f)) {
                        this.moved = true;
                        EdgeGestureService.this.ui.removeCallbacks(this.longPress);
                    }
                    if (!this.moved) {
                        return true;
                    }
                    if (!this.fired && (dir = EdgeGestureService.dirOf(dx, dy)) >= 0 && this.handle.enabled[dir] && dist >= (float)this.dp(this.handle.distanceOf(dir))) {
                        this.fired = true;
                        if (this.menuConfigured() && this.tryOpenMenu(dir, e.getRawX(pi), e.getRawY(pi))) {
                            // 菜单刚弹开、还没选中任何项 —— 清掉上一次的结果。
                            // 中途触摸被系统抢走（没有抬手）时，宏就不该收到
                            // 上一次的旧选中值。
                            EdgeGesturePrefs.recordMenuChoice(-1, "", "");
                            return true;
                        }
                        // 走到这里 = 本次没有菜单（没启用菜单，或菜单没弹出来）：
                        // 清掉上一次的选中值，免得静态字段里的陈旧值被当成本次结果
                        // 传给宏（buildMenuVars 靠 menuIndex < 0 判断「不回传」）。
                        EdgeGesturePrefs.recordMenuChoice(-1, "", "");
                        EdgeGestureService.this.fire(this.handle, dir);
                    }
                    return true;
                }
                case 1: 
                case 3: {
                    // ACTION_CANCEL(3) = 这批触摸被系统抢走了（多半是边缘返回手势）。
                    // 「屏蔽开关开着却没效果」时，这条是最直接的判据：
                    // 排除区真被系统接受的话，那一带的滑动会完整送进来，不会有 CANCEL。
                    if (e.getActionMasked() == 3 && this.touchLogged < 100) {
                        this.touchLogged++;
                        AppLog.w(EdgeGestureService.TAG,
                                "触摸被取消(ACTION_CANCEL)：这批手势被系统抢走了"
                                + " 手指Raw=(" + (int)e.getRawX() + "," + (int)e.getRawY() + ")"
                                + " 窗口=" + this.windowRectOnScreen());
                    }
                    this.touching = false;
                    EdgeGestureService.this.ui.removeCallbacks(this.longPress);
                    if (this.menuOpen) {
                        int pi;
                        boolean up = e.getActionMasked() == 1;
                        int idx = -1;
                        if (up && (pi = e.findPointerIndex(this.activePointerId)) >= 0) {
                            idx = this.hitMenu(e.getX(pi), e.getY(pi));
                        }
                        int dir = this.menuOpenDir;
                        if (up) {
                            if (idx >= 0 && idx < this.menuItems.size()) {
                                EdgeGesturePrefs.MenuItem it = this.menuItems.get(idx);
                                AppLog.i(EdgeGestureService.TAG, "菜单选中: 索引=" + idx
                                        + " 文字=" + it.text + " 动作=" + it.action
                                        + " 方向=" + dir);
                                EdgeGesturePrefs.recordMenuChoice(idx, it.text, it.action);
                            } else {
                                AppLog.i(EdgeGestureService.TAG, "菜单取消（抬手处没命中任何项）: 手指=("
                                        + (int)e.getX() + "," + (int)e.getY() + ")"
                                        + " —— 本次不回传菜单变量");
                                EdgeGesturePrefs.recordMenuChoice(-1, "", "");
                            }
                            this.closeMenu();
                            EdgeGestureService.this.fire(this.handle, dir);
                        } else {
                            // 这批触摸被系统抢走（ACTION_CANCEL），没等到抬手，
                            // 同样按「没选中」处理 —— 不回传菜单变量。
                            AppLog.i(EdgeGestureService.TAG,
                                    "菜单取消（触摸被系统抢走，未抬手）—— 本次不回传菜单变量");
                            EdgeGesturePrefs.recordMenuChoice(-1, "", "");
                            this.closeMenu();
                        }
                        this.fired = false;
                        this.moved = false;
                        this.setAlpha(1.0f);
                        this.invalidate();
                        return true;
                    }
                    if (this.dragMode) {
                        this.dragMode = false;
                        this.applyDragEnd();
                    }
                    // 「滑动未达距离后抬手时触发」分支已下线
                    // （见 EVENT_TRIGGER 注释）：抬手不再触发任何宏。
                    this.fired = false;
                    this.moved = false;
                    this.setAlpha(1.0f);
                    this.invalidate();
                    return true;
                }
            }
            return super.onTouchEvent(e);
        }

        protected void drawBar(Canvas c, EdgeGesturePrefs.Handle h, float top, float bottom, boolean edgeLeft, boolean active) {
            float right;
            float left;
            float vis = Math.min(this.dp(h.visibleWidth), this.getWidth());
            int w = this.getWidth();
            if (edgeLeft) {
                left = 0.0f;
                right = vis;
            } else {
                right = w;
                left = right - vis;
            }
            int alpha = Math.round(255.0f * ((float)h.alpha / 100.0f));
            if (alpha < 8) {
                alpha = 8;
            }
            int rgb = h.color & 0xFFFFFF;
            int color = alpha << 24 | rgb;
            if (this.dragMode && h == EdgeGestureService.this.activeHandle) {
                right = Math.min((float)w, right + (float)this.dp(6.0f));
                color = Math.min(255, alpha + 90) << 24 | rgb;
            } else if (active) {
                color = alpha << 24 | 0xFFFFFF;
            }
            float r = Math.min((float)this.dp(h.radius), Math.max(2.0f, (right - left) / 2.0f));
            this.rect.set(left, top, right, bottom);
            this.paint.setColor(color);
            c.drawRoundRect(this.rect, r, r, this.paint);
            if (active) {
                Paint stroke = new Paint(1);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth((float)this.dp(2.0f));
                stroke.setColor(0x66FFFFFF);
                c.drawRoundRect(this.rect, r, r, stroke);
            }
        }
    }
}