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
 * 【本服务不监听任何事件】
 * 它只为「截图」这一项能力存在：onAccessibilityEvent 是空实现，
 * 不去读屏幕上的文本、控件，也不接收触摸/按键事件，
 * 因此不会碰到用户的任何内容数据。
 *
 * 【它是可选的】
 * 不开启本服务时，应用其余功能完全不受影响 ——
 * 只是退回 root / MediaProjection 这两条老路。
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
        // 通知 OCRService 同步它的静态标志，否则插件仍会认为「截屏未就绪」
        try {
            OCRService.onAccessibilityStateChanged(true);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 刻意留空：不订阅任何事件，避免触碰用户数据。
    }

    @Override
    public void onInterrupt() {
        // 本服务没有手势/反馈动作，无需处理中断。
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        Log.i(TAG, "无障碍服务已断开（用户在设置里关闭了它）");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        try {
            executor.shutdownNow();
        } catch (Throwable ignored) {
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
