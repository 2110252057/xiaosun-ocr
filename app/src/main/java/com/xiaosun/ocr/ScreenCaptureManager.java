package com.xiaosun.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * 屏幕截图管理器（基于 MediaProjection）。
 *
 * 注意：Android 14 (API 34) 起，createVirtualDisplay 之前必须先 registerCallback，
 * 否则会抛 IllegalStateException。
 */
public class ScreenCaptureManager {

    private static final String TAG = "XiaoSunOCR";

    private final Context context;

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;

    private int width;
    private int height;
    private int densityDpi;

    public ScreenCaptureManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 是否已获得授权、可以截图。 */
    public synchronized boolean isReady() {
        return projection != null && virtualDisplay != null;
    }

    public int screenWidth() {
        return width;
    }

    public int screenHeight() {
        return height;
    }

    /** 建立投屏会话（授权一次即可长期复用）。 */
    public synchronized void start(MediaProjection mediaProjection) throws Exception {
        if (projection != null) {
            return;
        }
        this.projection = mediaProjection;

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            throw new IllegalStateException("无法获取 WindowManager");
        }
        Display display = wm.getDefaultDisplay();
        display.getRealMetrics(metrics);

        // 用真实屏幕尺寸建立 VirtualDisplay，不交换宽高。
        // 系统输出的镜像帧始终是「当前方向」的画面，宽高交换反而会取不到帧。
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        densityDpi = metrics.densityDpi;

        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("屏幕尺寸异常: " + width + "x" + height);
        }

        handlerThread = new HandlerThread("XiaoSunOCR-Screen");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        // Android 14+ 强制要求：投屏前先注册回调
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "MediaProjection 已被系统停止");
                synchronized (ScreenCaptureManager.this) {
                    releaseResources();
                }
            }
        };
        mediaProjection.registerCallback(projectionCallback, handler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "XiaoSunOCR-Screen",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, handler);

        if (virtualDisplay == null) {
            throw new IllegalStateException("创建 VirtualDisplay 失败");
        }

        Log.i(TAG, "屏幕捕获已就绪: " + width + "x" + height + " dpi=" + densityDpi
                + " rotation=" + display.getRotation());
    }

    /** 截取当前屏幕。 */
    public synchronized Bitmap capture() throws Exception {
        if (projection == null || imageReader == null) {
            throw new IllegalStateException("未获得截屏授权");
        }

        // 丢弃可能残留的旧帧
        Image stale = imageReader.acquireLatestImage();
        if (stale != null) {
            stale.close();
        }

        // 等待一帧新画面（最多等 1 秒）
        Image image = null;
        for (int i = 0; i < 100; i++) {
            image = imageReader.acquireLatestImage();
            if (image != null) {
                break;
            }
            Thread.sleep(10);
        }
        if (image == null) {
            throw new IllegalStateException("截屏失败：未获取到画面");
        }

        Bitmap bitmap;
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            // 处理行对齐 padding，否则画面会整体错位
            int bmpWidth = width + rowPadding / pixelStride;
            Bitmap padded = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);

            if (bmpWidth != width) {
                bitmap = Bitmap.createBitmap(padded, 0, 0, width, height);
                padded.recycle();
            } else {
                bitmap = padded;
            }
        } finally {
            image.close();
        }

        // 横屏时把画面旋正，保证坐标与显示方向一致
        bitmap = normalizeRotation(bitmap);

        // 去掉透明通道，避免 OCR 引擎把画面当空白
        Bitmap opaque = toOpaque(bitmap);
        if (opaque != bitmap) {
            bitmap.recycle();
        }
        return opaque;
    }

    private Bitmap normalizeRotation(Bitmap src) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return src;
            }
            Display display = wm.getDefaultDisplay();
            int rotation = display.getRotation();
            int degrees;
            switch (rotation) {
                case 1:
                    degrees = 90;
                    break;
                case 2:
                    degrees = 180;
                    break;
                case 3:
                    degrees = 270;
                    break;
                default:
                    return src;
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(
                    src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
            if (rotated != src) {
                src.recycle();
            }
            return rotated;
        } catch (Exception e) {
            return src;
        }
    }

    private Bitmap toOpaque(Bitmap src) {
        try {
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(0xFF000000);
            canvas.drawBitmap(src, 0f, 0f, null);
            return out;
        } catch (Exception e) {
            return src;
        }
    }

    /** 只释放底层资源，不停止 projection（供回调内部使用）。 */
    private void releaseResources() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (handlerThread != null) {
                handlerThread.quitSafely();
                handlerThread = null;
            }
        } catch (Exception ignored) {
        }
        handler = null;
        projection = null;
        projectionCallback = null;
    }

    /** 释放投屏会话。 */
    public synchronized void stop() {
        try {
            if (projectionCallback != null && projection != null) {
                projection.unregisterCallback(projectionCallback);
            }
        } catch (Exception ignored) {
        }
        try {
            if (projection != null) {
                projection.stop();
            }
        } catch (Exception ignored) {
        }
        releaseResources();
    }
}