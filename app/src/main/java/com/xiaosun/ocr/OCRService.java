package com.xiaosun.ocr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * OCR 前台服务：加载本地模型并用 HTTP 暴露识别接口。
 *
 * 重要：包名 / 类名 / 方法签名必须与 libnjocr.so 里写死的 JNI 符号一致，
 * 符号形如：
 *   Java_<包名下划线化>_<类名>_<方法名>
 * 当前为：
 *   Java_com_xiaosun_ocr_OCRService_initModels
 *   Java_com_xiaosun_ocr_OCRService_nativeProcessBitmap
 *   Java_com_xiaosun_ocr_OCRService_nativeSwitchTier
 *   Java_com_xiaosun_ocr_OCRService_nativeGetTier
 *   Java_com_xiaosun_ocr_OCRService_nativeSetRapidDir
 *
 * 注意：该 .so 没有 JNI_OnLoad，无法动态注册，
 * 所以**改包名或类名必须同步重编 .so**，否则一启动就 UnsatisfiedLinkError。
 *
 * 防冻结措施：
 *   1. 常驻前台服务（START_STICKY + 常驻通知）
 *   2. 持有 PARTIAL_WAKE_LOCK，请求处理期间保证 CPU 不睡
 *   3. stopWithTask=false，从最近任务划掉也不被杀
 *   4. 提供忽略电池优化申请入口（Doze 白名单）
 */
public class OCRService extends Service {

    private static final String TAG = "NJOCR";

    /** 界面通过 Intent 传入截屏授权结果的 action / extra key。 */
    public static final String ACTION_START_CAPTURE = "com.xiaosun.ocr.START_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "njocr_channel";
    private static final int NOTIFICATION_ID = 1666;

    /** 供界面判断服务是否已在运行。 */
    public static volatile boolean isRunning = false;

    /**
     * 最近一次识别所用位图的像素尺寸。
     *
     * 重要：识别返回的坐标是相对「送入引擎的那张位图」的像素值，
     * 而界面上的预览图通常被降采样过（decodeSampled），
     * 必须用这两个值把坐标换算回预览图，否则画出来的框会偏大、错位。
     */
    public static volatile int lastOcrImageWidth = 0;
    public static volatile int lastOcrImageHeight = 0;

    /** 最近一次「截屏识别」时屏幕画面的缓存文件路径（供界面显示预览）。 */
    public static volatile String lastCapturePath = null;


    /** 截屏授权是否真的就绪（供界面查询，避免盲目标记成功）。 */
    public static volatile boolean isScreenCaptureReady = false;

    private OcrHttpServer httpServer;

    /** 屏幕截图管理器（MediaProjection 授权后可反复截屏）。 */
    private ScreenCaptureManager screenCapture;

    /** 基于 root 的截屏管理器：有 root 时用它，免去截屏授权弹窗。 */
    private RootScreenCaptureManager rootCapture;

    /** 当前生效的截屏方式，供界面显示。 */
    public static volatile String captureMode = "none";

    /** CPU 唤醒锁：仅在处理 OCR 请求期间持有，避免长时间待机耗电。 */
    private PowerManager.WakeLock cpuLock;

    /** 正在处理的请求数，归零后释放唤醒锁。 */
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    static {
        // 与原版一致
        System.loadLibrary("njocr");
    }

    // ===================== native 接口（由 libnjocr.so 实现） =====================

    /** 加载 assets 中的 det/rec 模型与字典。 */
    public native boolean initModels(AssetManager assetManager);

    /** 识别位图。type: 1=纯文本 2=Json数组(含坐标) 3=详细Json(含矩形) */
    public native String nativeProcessBitmap(Bitmap bitmap, int type);

    /**
     * 切换模型档位（新增，不影响上面两个符号的兼容性）。
     *
     * @param tier {@link #TIER_TINY} 或 {@link #TIER_SMALL}
     * @return 是否切换成功
     */
    public native boolean nativeSwitchTier(AssetManager assetManager, int tier);

    /** 查询当前档位。 */
    public native int nativeGetTier();

    /** 设置 RapidOCR 的模型解包目录（传 cacheDir 即可）。 */
    public native void nativeSetRapidDir(String dir);

    // ===================== 模型档位 =====================

    /** 轻量档：速度快，模型小（默认）。 */
    public static final int TIER_TINY = 0;
    /** 高精度档：字典更大，保留更多标点/符号。 */
    public static final int TIER_SMALL = 1;
    /** RapidOCR：clipper 精确后处理 + AngleNet 方向纠正。 */
    public static final int TIER_RAPID = 2;

    /** 当前档位，供界面与接口共用。 */
    public static volatile int currentTier = TIER_TINY;

    /** 档位名称，用于日志与接口返回。 */
    public static String tierName(int tier) {
        switch (tier) {
            case TIER_SMALL: return "small";
            case TIER_RAPID: return "rapid";
            default:         return "tiny";
        }
    }

    /** 由名称解析档位，无法识别时返回 -1。 */
    public static int tierFromName(String name) {
        if (name == null) return -1;
        switch (name.trim().toLowerCase()) {
            case "tiny":  case "0": return TIER_TINY;
            case "small": case "1": return TIER_SMALL;
            case "rapid": case "2": return TIER_RAPID;
            default: return -1;
        }
    }

    /**
     * 切换档位并持久化。
     *
     * 需在后台线程调用（加载模型有耗时）。
     * 失败时保持原档位不变，不会把可用模型弄丢。
     */
    public boolean switchTier(int tier) {
        // 注意：三档都要放行（曾漏掉 rapid，导致它被直接拒掉、根本进不到 native）
        if (tier != TIER_TINY && tier != TIER_SMALL && tier != TIER_RAPID) return false;
        if (tier == currentTier && nativeGetTier() == tier) return true;
        try {
            boolean ok = nativeSwitchTier(getAssets(), tier);
            if (ok) {
                currentTier = tier;
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putInt(KEY_TIER, tier).apply();
                Log.i(TAG, "档位切换成功: " + tierName(tier));
            } else {
                Log.e(TAG, "档位切换失败，保持 " + tierName(currentTier));
            }
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "档位切换异常: " + t);
            return false;
        }
    }

    private static final String PREFS = "njocr_prefs";
    private static final String KEY_TIER = "model_tier";

    // ===================== 屏幕截图 =====================

    /**
     * 由界面传入 MediaProjection 授权结果，建立投屏会话。
     *
     * 重要顺序（Android 10+ 强制要求）：
     *   必须先让前台服务带上 mediaProjection 类型跑起来，之后才能调 getMediaProjection()，
     *   否则 getMediaProjection 会直接返回 null。
     */
    public void startScreenCapture(int resultCode, Intent data) {
        try {
            // ① 先切前台服务类型
            upgradeToMediaProjectionForeground();

            // ② 再获取 MediaProjection
            MediaProjectionManager mgr = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mgr == null) {
                Log.e(TAG, "无法获取 MediaProjectionManager");
                return;
            }
            MediaProjection projection = mgr.getMediaProjection(resultCode, data);
            if (projection == null) {
                Log.e(TAG, "获取 MediaProjection 失败：resultCode=" + resultCode
                        + "（授权结果无效或前台服务类型不正确）");
                return;
            }

            // ③ 建立投屏会话
            if (screenCapture != null) {
                screenCapture.stop();
                screenCapture = null;
            }
            isScreenCaptureReady = false;
            ScreenCaptureManager mgrCapture = new ScreenCaptureManager(this);
            mgrCapture.start(projection);
            screenCapture = mgrCapture;
            isScreenCaptureReady = true;
            Log.i(TAG, "截屏功能已启用, 分辨率=" + mgrCapture.screenWidth()
                    + "x" + mgrCapture.screenHeight());
        } catch (Throwable e) {
            Log.e(TAG, "启动截屏失败: " + e);
            screenCapture = null;
            isScreenCaptureReady = false;
        }
    }

    /** 是否已完成截屏授权（或以 root 方式可用）。 */
    public boolean isScreenCaptureReady() {
        // ① root 截屏优先：无需授权
        if (rootCapture != null && rootCapture.isReady()) {
            return true;
        }
        // ② 退回 MediaProjection
        return screenCapture != null && screenCapture.isReady();
    }

    /**
     * 截取当前屏幕。
     *
     * 顺序：root 截屏 → MediaProjection。
     * 有 root 时完全不需要「开启截屏授权」，也不会出现录屏弹窗。
     */
    public synchronized Bitmap captureScreen() throws Exception {
        // ① root 截屏
        if (rootCapture != null && rootCapture.isReady()) {
            try {
                Bitmap bmp = rootCapture.capture();
                if (bmp != null) {
                    captureMode = "root";
                    return bmp;
                }
            } catch (Throwable t) {
                Log.w(TAG, "root 截屏失败，尝试退回 MediaProjection: " + t);
            }
        }
        // ② MediaProjection
        if (screenCapture != null && screenCapture.isReady()) {
            Bitmap bmp = screenCapture.capture();
            captureMode = "mediaprojection";
            return bmp;
        }
        throw new IllegalStateException(
                "无可用截屏方式：既没有 root，也还没在 App 内点「开启截屏授权」");
    }

    /**
     * 把前台服务切换为含 mediaProjection 类型，否则 Android 10+ 会拒绝投屏。
     */
    private void upgradeToMediaProjectionForeground() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                                | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "切换前台服务类型失败: " + e.getMessage());
        }
    }

    // ===================== 唤醒锁 =====================

    /** 请求开始：持有 CPU 唤醒锁，防止息屏时 CPU 被冻结导致响应超时。 */
    void acquireCpuLock() {
        if (activeRequests.incrementAndGet() == 1) {
            try {
                if (cpuLock == null) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NJOCR:ocr");
                        cpuLock.setReferenceCounted(false);
                    }
                }
                if (cpuLock != null && !cpuLock.isHeld()) {
                    cpuLock.acquire(60 * 1000L);
                }
            } catch (Exception e) {
                Log.e(TAG, "获取唤醒锁失败: " + e.getMessage());
            }
        }
    }

    /** 请求结束：没有进行中的请求时释放唤醒锁。 */
    void releaseCpuLock() {
        if (activeRequests.decrementAndGet() <= 0) {
            activeRequests.set(0);
            try {
                if (cpuLock != null && cpuLock.isHeld()) {
                    cpuLock.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ===================== 生命周期 =====================

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service Creating...");
        isRunning = true;
        startForegroundCompat();

        new Thread(() -> {
            // ① 优先探测 root 截屏：有 root 就完全不需要「截屏授权」
            try {
                RootScreenCaptureManager rc = new RootScreenCaptureManager(this);
                if (rc.start()) {
                    rootCapture = rc;
                    captureMode = "root";
                    isScreenCaptureReady = true;
                    Log.i(TAG, "已启用 root 截屏，无需截屏授权");
                }
            } catch (Throwable t) {
                Log.w(TAG, "root 截屏探测失败: " + t);
            }

            // ② 加载模型
            try {
                // RapidOCR 只接受文件路径，先把解包目录告诉 native
                try {
                    java.io.File rapidDir = new java.io.File(getCacheDir(), "rapid");
                    // 必须先把目录建好：native 侧只会 fopen 写文件，不会建目录
                    if (!rapidDir.exists() && !rapidDir.mkdirs()) {
                        Log.w(TAG, "创建 RapidOCR 目录失败: " + rapidDir);
                    }
                    nativeSetRapidDir(rapidDir.getAbsolutePath());
                } catch (Throwable t) {
                    Log.w(TAG, "设置 RapidOCR 目录失败: " + t);
                }

                Log.i(TAG, "开始初始化模型...");
                boolean ok = initModels(getAssets());
                if (ok) {
                    Log.i(TAG, "模型初始化成功！");
                    // ③ 恢复上次选定的档位（默认 tiny，与原行为一致）
                    int saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getInt(KEY_TIER, TIER_TINY);
                    if (saved != TIER_TINY) {
                        if (switchTier(saved)) {
                            Log.i(TAG, "已恢复档位: " + tierName(saved));
                        } else {
                            Log.w(TAG, "恢复档位失败，退回 tiny");
                            currentTier = TIER_TINY;
                        }
                    }
                } else {
                    Log.e(TAG, "模型初始化失败，请检查 assets 文件！");
                }
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "JNI 链接失败: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "模型初始化异常: " + e.getMessage());
            }
            startHttpServer();
        }, "njocr-init").start();
    }

    /**
     * 返回 START_STICKY：进程被系统回收后会自动重建服务（对无人值守场景很重要）。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 可能被外部 am start-foreground-service 重复拉起，这里保证通知一直在
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundCompat();
        }

        // 界面传来截屏授权结果时，建立投屏会话
        if (intent != null && ACTION_START_CAPTURE.equals(intent.getAction())) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData != null) {
                startScreenCapture(resultCode, resultData);
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        isScreenCaptureReady = false;
        captureMode = "none";
        try {
            if (rootCapture != null) {
                rootCapture.quit();
                rootCapture = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (screenCapture != null) {
                screenCapture.stop();
                screenCapture = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (httpServer != null) {
                httpServer.stop();
                httpServer = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (cpuLock != null && cpuLock.isHeld()) {
                cpuLock.release();
            }
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ===================== 内部实现 =====================

    private void startHttpServer() {
        try {
            if (httpServer != null) {
                return;
            }
            httpServer = new OcrHttpServer(this);
            httpServer.start();
            Log.i(TAG, "HTTP 服务已启动: 127.0.0.1:1666");
        } catch (Exception e) {
            Log.e(TAG, "HTTP 启动失败: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "小孙OCR 服务", NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("小孙OCR 服务端运行中")
                .setContentText("监听地址: 127.0.0.1:1666")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();

        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE 是 API 34 才引入的常量，
        // 在 API 29~33 上传入会与 manifest 声明不匹配而抛异常，故分级处理。
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
}