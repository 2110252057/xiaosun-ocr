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
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
    }

    private WindowManager.LayoutParams baseParams(boolean leftEdge) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams();
        p.type = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;
        p.flags = 808;
        p.format = -3;
        p.gravity = (leftEdge ? 0x800003 : 0x800005) | 0x30;
        p.x = 0;
        return p;
    }

    private void addHandleView(EdgeGesturePrefs.Handle h) {
        WindowManager.LayoutParams p = this.baseParams(h.isEdgeLeft());
        p.width = Math.max(1, this.dp(h.width));
        p.height = this.dp(h.height);
        int[] scr = this.screenSize();
        int maxY = Math.max(0, scr[1] - p.height);
        p.y = (int)((float)maxY * ((float)h.offsetPercent / 100.0f));
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

        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (this.menuOpen) {
                this.drawMenu(c);
            } else {
                this.drawBar(c, this.handle, 0.0f, this.getHeight(), this.handle.isEdgeLeft(), this.flash);
            }
            this.syncGestureExclusion();
        }

        @Override
        protected List<Rect> buildExclusionRects() {
            ArrayList<Rect> out = new ArrayList<Rect>(1);
            if (!this.handle.blockGesture) {
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

        protected abstract boolean hitTest(float var1, float var2);

        protected abstract void applyDrag(float var1, float var2);

        protected abstract void applyDragEnd();

        protected abstract List<Rect> buildExclusionRects();

        protected void syncGestureExclusion() {
            if (Build.VERSION.SDK_INT < 29) {
                return;
            }
            if (this.menuOpen) {
                return;
            }
            List<Rect> want = this.buildExclusionRects();
            if (want == null) {
                want = new ArrayList<Rect>(0);
            }
            if (want.equals(this.reportedRects)) {
                return;
            }
            this.reportedRects.clear();
            for (Rect r : want) {
                this.reportedRects.add(new Rect(r));
            }
            try {
                this.setSystemGestureExclusionRects(want);
            }
            catch (Throwable t) {
                Log.w((String)EdgeGestureService.TAG, (String)("\u624b\u52bf\u6392\u9664\u4e0a\u62a5\u5931\u8d25: " + t));
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

        protected boolean tryOpenMenu(int dir, float rawX, float rawY) {
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
            return true;
        }

        protected void closeMenu() {
            if (!this.menuOpen) {
                return;
            }
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
                return;
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
                    d.setBounds(Math.round(cx - side / 2.0f), Math.round(cy - side / 2.0f), Math.round(cx + side / 2.0f), Math.round(cy + side / 2.0f));
                    d.draw(c);
                } else {
                    String t2 = this.menuItems.get((int)i).text;
                    if (t2 != null && t2.length() > 0) {
                        p.setColor(on ? -1 : (this.isLightColor(this.menuBgColor) ? -14540254 : -1));
                        p.setTextSize(iconPx * 0.34f);
                        p.setTextAlign(Paint.Align.CENTER);
                        float cy = cy0 - (p.descent() + p.ascent()) / 2.0f;
                        c.drawText(t2.substring(0, 1), cx0, cy, p);
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
                    if (this.handle.event == 1 && !this.menuConfigured()) {
                        this.fired = true;
                        EdgeGestureService.this.fire(this.handle, -1);
                    }
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
                    if (this.handle.event == 0 && !this.fired && (dir = EdgeGestureService.dirOf(dx, dy)) >= 0 && this.handle.enabled[dir] && dist >= (float)this.dp(this.handle.distanceOf(dir))) {
                        this.fired = true;
                        if (this.menuConfigured() && this.tryOpenMenu(dir, e.getRawX(pi), e.getRawY(pi))) {
                            return true;
                        }
                        EdgeGestureService.this.fire(this.handle, dir);
                    }
                    return true;
                }
                case 1: 
                case 3: {
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
                                EdgeGesturePrefs.recordMenuChoice(idx, it.text, it.action);
                            } else {
                                EdgeGesturePrefs.recordMenuChoice(-1, "", "");
                            }
                            this.closeMenu();
                            EdgeGestureService.this.fire(this.handle, dir);
                        } else {
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
                    } else if (!this.fired && this.moved && this.handle.event == 2) {
                        EdgeGestureService.this.fire(this.handle, -1);
                    }
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
