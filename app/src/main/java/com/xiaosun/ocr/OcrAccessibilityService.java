package com.xiaosun.ocr;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无障碍截图服务。
 *
 * 【为什么再加第三条截屏路径】
 * 已有的两条各有硬伤：
 *   · root 截屏       —— 设备没 root 就没戏；
 *   · MediaProjection —— 每次重启都要用户手点一次录屏授权弹窗，
 *                        无人值守 / 宏触发场景基本没法用。
 * 而 Android 11（API 30）给无障碍服务开了 takeScreenshot()：
 * 用户只需在系统设置里勾选一次本服务，之后**随时可截、不弹框、不需要 root**。
 *
 * 【它监听什么】
 * 只为「截图」这一项能力存在时，onAccessibilityEvent 本可以是空实现。
 * 但手柄的「黑名单」功能需要一个实时的前台应用判据（悬浮窗自己不知道
 * 用户切到了哪个 App），所以现在额外订阅了**唯一一种**事件：
 *
 *     TYPE_WINDOW_STATE_CHANGED —— 只从里面取 event.getPackageName()，
 *     也就是「哪个应用到了前台」这一个字段。
 *
 * 不读 getText()、不读 getContentDescription()、不遍历控件树，
 * 因此除了「包名」之外，仍然碰不到用户的任何内容数据。
 * 不想要这个能力，把 accessibility_service_config.xml 里的
 * accessibilityEventTypes 删掉即可 —— 代价是「手柄黑名单」失效
 * （它只认这一条判据，见 {@link ForegroundAppDetector}），其余功能不受影响。
 *
 * 【它是可选的】
 * 不开启本服务时，应用其余功能完全不受影响 ——
 * 只是退回 root / MediaProjection 这两条截屏老路，
 * 以及「手柄黑名单」不会生效（判断不出前台是哪个应用，手柄照常显示）。
 */
public class OcrAccessibilityService extends AccessibilityService {
    private static final String TAG = "XiaoSunOCR-A11yCap";
    /** 单次截图的等待上限：正常一帧内就回调，8 秒已是极宽的兜底。 */
    private static final long CAPTURE_TIMEOUT_MS = 8_000;

    /** 已连接的实例；未连接或已断开时为 null。 */
    private static volatile OcrAccessibilityService instance;

    /** 截图回调用的执行器。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // =====================================================================
    //  静态查询入口（给 Service / Activity / Receiver 用）
    // =====================================================================

    /**
     * 无障碍截图通道当前是否可用。
     *
     * 判据是「已连接 + 系统确实授予了截图能力」两者同时成立 ——
     * 只看 instance 非空是不够的：Android 11 以下没有该能力，
     * 某些 ROM 也可能在服务信息里不给这个 capability 位。
     */
    public static boolean isReady() {
        OcrAccessibilityService s = instance;
        return s != null && s.canTakeScreenshot();
    }

    /** 取当前实例，未连接时返回 null。 */
    public static OcrAccessibilityService get() {
        return instance;
    }

    // =====================================================================
    //  生命周期
    // =====================================================================

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接，可截图=" + canTakeScreenshot());
        // 把手柄黑名单的「前台应用侦测」挂到这个实例上
        ForegroundAppDetector.bind(this, this);
        // 通知 OCRService 同步它的静态标志，否则插件仍会认为「截屏未就绪」
        try {
            OCRService.onAccessibilityStateChanged(true);
        } catch (Throwable ignored) {
            // 服务刚连上时 OCRService 可能还没起来，通知不到就算了，下次截图会再同步。
        }
    }

    /**
     * 唯一订阅的事件：窗口状态变化（见类注释）。
     *
     * 这里只取包名，别的字段一个都不碰。
     * 顺带一提为什么不用 TYPE_WINDOW_STATE_CHANGED 之外的：
     * 内容变化（TYPE_WINDOW_CONTENT_CHANGED）会疯狂刷屏，
     * 触摸/按键事件则属于「用户输入」，都不该为了黑名单去订阅。
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                ForegroundAppDetector.onWindowChanged(event.getPackageName());
            }
        } catch (Throwable t) {
            Log.w(TAG, "处理窗口事件失败: " + t);
        }
    }

    @Override
    public void onInterrupt() {
        // 本服务没有手势/反馈动作，无需处理中断。
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        ForegroundAppDetector.unbind(this);
        Log.i(TAG, "无障碍服务已断开（用户在设置里关闭了它）");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        ForegroundAppDetector.unbind(this);
        try {
            executor.shutdownNow();
        } catch (Throwable ignored) {
            // 线程池已经关过时 shutdownNow 无副作用，忽略。
        }
        super.onDestroy();
    }

    // =====================================================================
    //  截图
    // =====================================================================

    /**
     * 系统是否授予了「截图」能力。
     *
     * 能力位 {@code CAPABILITY_CAN_TAKE_SCREENSHOT} 与 takeScreenshot() 同为 API 30 引入。
     */
    public boolean canTakeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                return false;
            }
            return (info.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0;
        } catch (Throwable t) {
            Log.w(TAG, "读取无障碍能力失败: " + t);
            return false;
        }
    }

    /**
     * 截取当前屏幕（同步阻塞版）。必须在工作线程调用。
     *
     * 【为什么要把异步回调包成同步】
     * takeScreenshot 是回调式的，而 OCRService.captureScreen() 是同步返回
     * Bitmap 的老接口（HTTP 线程、插件线程都在等它的返回值）。
     * 用 CountDownLatch 等回调最省事，也便于上面统一按异常处理。
     *
     * 【为什么必须 copy 一次】
     * 回调给的是 HardwareBuffer 包装出的 HARDWARE 位图，
     * 而 OCR 引擎要读像素（ARGB_8888），硬件位图必须先落到软件内存；
     * 且必须在本方法内完成 copy —— 缓冲一 close，那张硬件位图就没用了。
     */
    public Bitmap capture() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new IllegalStateException("无障碍截图需要 Android 11 及以上");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> ok = new AtomicReference<>();
        final AtomicReference<Throwable> bad = new AtomicReference<>();

        takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = null;
                try {
                    buffer = result.getHardwareBuffer();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    Bitmap sw = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                    if (sw == null) {
                        bad.set(new IllegalStateException("无障碍截图：位图转换失败"));
                    } else {
                        ok.set(sw);
                    }
                } catch (Throwable t) {
                    bad.set(t);
                } finally {
                    if (buffer != null) {
                        try {
                            buffer.close();
                        } catch (Throwable ignored) {
                            // 关闭截图缓冲区失败就放弃，图已经拿到或已经失败，这里只是收尾。
                        }
                    }
                    latch.countDown();
                }
            }

            @Override
            public void onFailure(int errorCode) {
                bad.set(new IllegalStateException("无障碍截图失败，错误码=" + errorCode));
                latch.countDown();
            }
        });

        if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("无障碍截图超时");
        }
        if (bad.get() != null) {
            throw new Exception(bad.get());
        }
        Bitmap bmp = ok.get();
        if (bmp == null) {
            throw new IllegalStateException("无障碍截图返回空图");
        }
        return bmp;
    }
}