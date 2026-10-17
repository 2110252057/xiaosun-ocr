package com.xiaosun.ocr;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.YuvImage;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 摄像头实时识别页（后置摄像头 + 本地 ML Kit，只保留数字）。
 *
 * 除实时识别外，还提供「相册」：从文件选择器挑一张已有图片，用同一套
 * 数字规则识别一次（详见第六节）。
 *
 * ════════════════════════════════════════════════════════════════
 * 一、为什么用 Camera2 而不是 CameraX
 * ════════════════════════════════════════════════════════════════
 * CameraX 要再引 camera-core / camera-camera2 / camera-lifecycle /
 * camera-view 四个 androidx 库，APK 会再涨，而且本机只能在设备上构建后
 * 才知道有没有依赖冲突。而 {@code android.hardware.camera2} 是平台 API，
 * 在 android.jar 里就有，**零新增依赖**，静态编译即可验证。
 *
 * ════════════════════════════════════════════════════════════════
 * 二、识别为什么不用 OCRService
 * ════════════════════════════════════════════════════════════════
 * {@link OCRService#onBind} 返回 null（它不接受 bind），也没有单例字段，
 * 界面拿不到它的实例；而 {@link MlKitEngine} 是包内可见类，本页与它同包，
 * 直接 new 一个即可。模型是 bundled 版（不联网、不依赖 Play 服务），
 * 代价只是多一份识别器实例的内存，退出本页即 close。
 *
 * ════════════════════════════════════════════════════════════════
 * 三、为什么不是「一直以帧率识别」
 * ════════════════════════════════════════════════════════════════
 * 预览用 setRepeatingRequest（只挂预览 Surface），识别则另外用单次
 * capture() 插一帧到 ImageReader 上。这样：
 *   · 预览是流畅的、AE/AF 一直在收敛；
 *   · 识别频率由「识别完 → 等 intervalMs → 再抓」决定，不会跑满帧率，
 *     也就不会把 CPU/GPU 长时间顶在满负荷（实时识别本来就费电，这是
 *     本项目能让它「温和一点」的唯一手段）。
 *
 * ════════════════════════════════════════════════════════════════
 * 四、数字提取规则（本页的核心语义）
 * ════════════════════════════════════════════════════════════════
 * 需求是「只识别数字」，因此识别出的文本不会原样展示，只从中抽数字：
 *   1. 全角数字（０-９）先转半角；
 *   2. 「数字之间的千分位逗号」先去掉：1,234 → 1234（1,234.56 → 1234.56）；
 *   3. 剩下的逗号当小数点：1,23 → 1.23（欧洲写法）；
 *   4. 最后按 \d+(\.\d+)? 抽取，**负号不保留** —— 小票/电表/车牌上的
 *      连字符绝大多数是分隔符而不是负号，保留它会把 2024-001 变成
 *      「2024」「-001」两段，反而更难用。
 *
 * ════════════════════════════════════════════════════════════════
 * 五、为什么结果要「连续两次一样」才刷新
 * ════════════════════════════════════════════════════════════════
 * 手持时机身会抖，同一行数字偶尔会掉一两位，结果区就会疯狂闪烁。
 * 这里要求连续 2 次抽出的结果完全一致才更新界面（{@link #HITS_TO_SETTLE}），
 * 抖动被吃掉，真正的数字一稳住就立刻显示。
 *
 * ════════════════════════════════════════════════════════════════
 * 六、相册模式（同一页里的第二条输入源）
 * ════════════════════════════════════════════════════════════════
 * 底部「相册」按钮走 ACTION_OPEN_DOCUMENT 选图，识别一次即出结果。
 * 与实时识别的差别只在「输入从哪来」，数字规则完全共用：
 *   · 走 SAF，**不需要 READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE**；
 *   · 解码前先读尺寸算 inSampleSize（最长边 ≤ 1920），避免大图 OOM；
 *   · 按 EXIF 方向摆正后再送识别 —— 竖拍的图像素其实是横着存的，
 *     不摆正识别率会明显下降；
 *   · 识别完把界面「钉住」（{@link #galleryPinned}）并暂停实时，
 *     否则下一帧摄像头画面会立刻盖掉刚看清的结果；点「继续」恢复。
 */
public class CameraOcrActivity extends AppCompatActivity {

    private static final String TAG = "XSOCR-Camera";

    /** 运行时权限请求码。 */
    private static final int REQ_CAMERA = 9101;

    /** 相册选图请求码。 */
    private static final int REQ_PICK_IMAGE = 9102;

    /**
     * 相册图解码后的最长边上限。
     *
     * 比摄像头那 720 宽得多：相册图是静态的、只识别一次，没有「每秒重算」
     * 的压力，放大一点对小字（比如小票底部的一行条码数字）更有利。
     */
    private static final int MAX_GALLERY_SIDE = 1920;

    /** 连续命中多少次才认为结果稳定。 */
    private static final int HITS_TO_SETTLE = 2;

    /** 抓帧超时（毫秒）：图像迟迟不来时强制解锁，避免整条链路卡死。 */
    private static final long SHOT_TIMEOUT_MS = 10_000L;

    /** 识别节奏（毫秒），三档循环。 */
    private static final long[] INTERVALS = {200L, 350L, 800L};
    private static final String[] INTERVAL_LABELS = {"快", "标准", "省电"};

    // ============================================================
    //  视图
    // ============================================================
    private FrameLayout rootLayout;
    private TextureView textureView;
    private TextView tvStatus;
    private TextView tvResult;
    private TextView tvMeta;
    private Button btnPause;
    private Button btnTorch;
    private Button btnRate;

    // ============================================================
    //  Camera2
    // ============================================================
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private Surface previewSurface;
    private String cameraId;
    private int sensorOrientation = 90;
    private Size frameSize;

    private HandlerThread bgThread;
    private Handler bgHandler;
    private HandlerThread ocrThread;
    private Handler ocrHandler;

    // ============================================================
    //  状态
    // ============================================================
    private volatile MlKitEngine engine;
    /**
     * 界面是否被「相册识别结果」钉住。
     *
     * 选完相册图后摄像头预览还在，若不钉住，用户刚看清相册图的结果，
     * 下一帧画面就把它覆盖掉了，等于白选。钉住时同时把 {@link #paused}
     * 置为 true（真正停掉实时识别），点「继续」双向解除：
     * 既清掉钉住标记、也恢复实时识别。
     */
    private volatile boolean galleryPinned = false;
    /**
     * 「相册图正在识别」窗口。
     *
     * onActivityResult → onResume 是固定顺序，而这时 {@link #galleryPinned}
     * 还来不及置位（要等识别完成）。没有这个标志的话，onResume 会把
     * paused 改回 false，实时抓帧就跟相册识别一起排队白跑一轮。
     */
    private volatile boolean galleryBusy = false;
    /** 打开选图器之前是否已手动暂停，取消选图时按原样恢复。 */
    private volatile boolean pausedBeforePick = false;
    private volatile boolean stopping = true;
    private volatile boolean paused = false;
    private volatile boolean capturing = false;
    private volatile boolean opening = false;
    private volatile boolean torchOn = false;
    private volatile int intervalIndex = 1;

    // 结果稳定化（只在主线程读写）
    private String pendingText = "";
    private int pendingHits = 0;
    private String shownText = "";

    // ============================================================
    //  生命周期
    // ============================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 实时识别时屏幕不能自动熄灭。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        ocrThread = new HandlerThread("xsocr-cam-ocr");
        ocrThread.start();
        ocrHandler = new Handler(ocrThread.getLooper());

        bgThread = new HandlerThread("xsocr-cam-bg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        buildUi();
        setContentView(rootLayout);

        textureView.setSurfaceTextureListener(surfaceListener);
        AppLog.i(TAG, "摄像头实时识别页已打开");
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopping = false;
        // 相册结果被钉住、或相册图正在识别时不要解除暂停：
        // 从文件选择器返回的顺序是 onActivityResult → onResume，
        // 若这里无条件 paused=false，会把相册模式刚设的暂停顶掉。
        if (!galleryPinned && !galleryBusy) {
            paused = false;
        }
        updatePauseButton();
        if (hasCameraPermission()) {
            startPreviewIfPossible();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopping = true;
        // 离开这一页就解除「相册结果钉住」：下次进来应该是个干净的实时页，
        // 而不是还钉着上次那张相册图、还停着识别。
        galleryPinned = false;
        galleryBusy = false;
        closeCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopping = true;
        closeCamera();
        final MlKitEngine e = engine;
        engine = null;
        if (e != null) {
            ocrHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        e.close();
                    } catch (Throwable ignore) {
                        // 关识别器失败不影响退出
                    }
                }
            });
        }
        ocrThread.quitSafely();
        bgThread.quitSafely();
        AppLog.i(TAG, "摄像头实时识别页已关闭");
    }

    // ============================================================
    //  权限
    // ============================================================
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startPreviewIfPossible();
        } else {
            setStatus("没有相机权限，无法打开摄像头", true);
            toast("需要相机权限才能实时识别");
            AppLog.w(TAG, "用户拒绝了相机权限");
        }
    }

    // ============================================================
    //  预览（TextureView 生命周期）
    // ============================================================
    private final TextureView.SurfaceTextureListener surfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                    if (!stopping && hasCameraPermission() && cameraDevice == null) {
                        openCamera();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
                    configureTransform(w, h);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                    closeCamera();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
                    // 不需要逐帧处理
                }
            };

    private void startPreviewIfPossible() {
        if (textureView.isAvailable()) {
            if (cameraDevice == null) {
                openCamera();
            }
        } else {
            setStatus("相机画面准备中…", false);
        }
    }

    // ============================================================
    //  打开相机
    // ============================================================
    private void openCamera() {
        if (cameraManager == null) {
            setStatus("设备不支持相机接口", true);
            return;
        }
        if (opening || cameraDevice != null) {
            // onResume 与 surfaceAvailable 都可能触发，不能开两次
            return;
        }
        opening = true;
        boolean launched = false;
        try {
            cameraId = pickBackCameraId();
            if (cameraId == null) {
                setStatus("没有找到可用的后置摄像头", true);
                return;
            }
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraId);
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            StreamConfigurationMap map =
                    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                setStatus("相机能力读取失败", true);
                return;
            }
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes == null || sizes.length == 0) {
                setStatus("该摄像头不支持 YUV 输出", true);
                return;
            }
            frameSize = chooseFrameSize(sizes);

            imageReader = ImageReader.newInstance(frameSize.getWidth(),
                    frameSize.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image img = null;
                    try {
                        img = reader.acquireNextImage();
                    } catch (Throwable t) {
                        AppLog.w(TAG, "取帧失败: " + t);
                    }
                    if (img == null) {
                        finishShot();
                        return;
                    }
                    handleImage(img);
                }
            }, ocrHandler);

            setStatus("正在打开摄像头…", false);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            cameraManager.openCamera(cameraId, deviceCallback, bgHandler);
            launched = true;
            AppLog.i(TAG, "打开相机 id=" + cameraId + " 帧尺寸=" + frameSize.getWidth()
                    + "x" + frameSize.getHeight() + " sensorOrientation=" + sensorOrientation);
        } catch (Throwable t) {
            setStatus("打开摄像头失败：" + t.getClass().getSimpleName(), true);
            AppLog.e(TAG, "openCamera 失败", t);
        } finally {
            // 没能把 openCamera 调出去（提前 return 或抛异常）时必须复位，
            // 否则这个标志会永久为 true，之后再也开不了相机。
            if (!launched) {
                opening = false;
            }
        }
    }

    /** 优先后置；没有后置就退而取第一个。 */
    private String pickBackCameraId() throws Exception {
        String[] ids = cameraManager.getCameraIdList();
        if (ids == null || ids.length == 0) {
            return null;
        }
        for (String id : ids) {
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return ids[0];
    }

    /**
     * 选帧尺寸：短边不超过 720 里挑最大的。
     *
     * 识别耗时大致随像素数线性增长，1280×720 已经足够读清小票上的数字；
     * 再往上（1080p/4K）只是白白让每一帧慢一倍，对准确率几乎没帮助。
     */
    private Size chooseFrameSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            int longSide = Math.max(s.getWidth(), s.getHeight());
            int shortSide = Math.min(s.getWidth(), s.getHeight());
            if (shortSide > 720 || longSide > 1440) {
                continue;
            }
            if (best == null) {
                best = s;
                continue;
            }
            int bestShort = Math.min(best.getWidth(), best.getHeight());
            int bestLong = Math.max(best.getWidth(), best.getHeight());
            if (shortSide > bestShort || (shortSide == bestShort && longSide > bestLong)) {
                best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice device) {
            cameraDevice = device;
            opening = false;
            createSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice device) {
            AppLog.w(TAG, "相机被抢占/断开");
            device.close();
            cameraDevice = null;
            setStatus("相机被其它应用占用", true);
        }

        @Override
        public void onError(@NonNull CameraDevice device, int error) {
            AppLog.e(TAG, "相机错误 code=" + error);
            device.close();
            cameraDevice = null;
            setStatus("相机出错（code=" + error + "）", true);
        }
    };

    private void createSession() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null || cameraDevice == null || imageReader == null) {
                return;
            }
            st.setDefaultBufferSize(frameSize.getWidth(), frameSize.getHeight());
            previewSurface = new Surface(st);
            configureTransform(textureView.getWidth(), textureView.getHeight());

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession s) {
                            session = s;
                            startRepeatingPreview();
                            setStatus("识别中…", false);
                            scheduleShot();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                            setStatus("相机会话配置失败", true);
                            AppLog.e(TAG, "createCaptureSession 配置失败");
                        }
                    }, bgHandler);
        } catch (Throwable t) {
            setStatus("创建相机会话失败", true);
            AppLog.e(TAG, "createSession 失败", t);
        }
    }

    /** 预览只用 repeating request（不挂 ImageReader），保证画面流畅且 AE/AF 稳定。 */
    private void startRepeatingPreview() {
        try {
            if (session == null || cameraDevice == null || previewSurface == null) {
                return;
            }
            CaptureRequest.Builder b =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            applyCommonSettings(b);
            session.setRepeatingRequest(b.build(), null, bgHandler);
        } catch (Throwable t) {
            AppLog.w(TAG, "预览 request 提交失败: " + t);
        }
    }

    /** 手电筒开关后要重提一次预览 request，画面亮度才会跟着变。 */
    private void refreshRepeatingPreview() {
        startRepeatingPreview();
    }

    private void applyCommonSettings(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.FLASH_MODE,
                torchOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
    }

    /**
     * TextureView 变换（把 16:9 的横向相机画面转成竖屏铺满）。
     *
     * 直接用官方 Camera2Basic 的 configureTransform 逻辑：先把 buffer
     * 按 view 矩形对齐，再等比放大，最后按屏幕旋转补一次 rotate。
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || frameSize == null || viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, frameSize.getHeight(), frameSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / frameSize.getHeight(),
                    (float) viewWidth / frameSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    // ============================================================
    //  抓帧与识别
    // ============================================================
    private final Runnable shotRunnable = new Runnable() {
        @Override
        public void run() {
            if (stopping || paused || session == null || cameraDevice == null
                    || imageReader == null) {
                capturing = false;
                return;
            }
            try {
                CaptureRequest.Builder b =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(imageReader.getSurface());
                // 同时挂预览 surface：只挂 ImageReader 时，部分机型会把这一帧
                // 当成「非预览帧」而重算曝光，画面会闪一下、AF 也会抖动。
                if (previewSurface != null) {
                    b.addTarget(previewSurface);
                }
                applyCommonSettings(b);
                session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                    // 不需要逐帧回调，图像从 ImageReader 里拿
                }, bgHandler);
                ocrHandler.postDelayed(watchdogRunnable, SHOT_TIMEOUT_MS);
            } catch (Throwable t) {
                AppLog.w(TAG, "抓帧失败: " + t);
                capturing = false;
                if (!stopping && !paused) {
                    ocrHandler.postDelayed(this, 1000);
                }
            }
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (capturing) {
                AppLog.w(TAG, "抓帧超时，重新调度");
                capturing = false;
                scheduleShot();
            }
        }
    };

    private void scheduleShot() {
        if (stopping || paused || session == null || imageReader == null) {
            return;
        }
        if (capturing) {
            return;
        }
        capturing = true;
        ocrHandler.removeCallbacks(shotRunnable);
        ocrHandler.postDelayed(shotRunnable, INTERVALS[intervalIndex]);
    }

    private void finishShot() {
        ocrHandler.removeCallbacks(watchdogRunnable);
        capturing = false;
        scheduleShot();
    }

    /** 在 ocrHandler 线程执行：YUV → Bitmap → ML Kit → 抽数字 → 回主线程。 */
    private void handleImage(Image image) {
        long t0 = System.currentTimeMillis();
        Bitmap bmp = null;
        try {
            bmp = imageToBitmap(image);
        } catch (Throwable t) {
            AppLog.w(TAG, "YUV 转位图失败: " + t);
        } finally {
            try {
                image.close();
            } catch (Throwable ignore) {
                // 已关闭
            }
        }
        if (bmp == null) {
            finishShot();
            return;
        }

        Bitmap upright = toUpright(bmp);
        MlKitEngine eng = engine;
        if (eng == null) {
            eng = new MlKitEngine();
            eng.init();
            engine = eng;
        }

        String raw = "";
        String err = null;
        try {
            // 顺序很重要：先清错误位、再 recognize、再 parse，最后才读错误位。
            // 之前写成「recognize 后立刻读」会把 parseTexts 里记下的错误吞掉，
            // 表现为识别失败时界面只是「没识别到数字」，查不到真正原因。
            String json = eng.recognize(upright, 1);
            lastError = null;
            String parsed = parseTexts(json);
            String parseErr = lastError;
            lastError = null;
            if (parseErr != null) {
                err = parseErr;
            } else {
                raw = parsed == null ? "" : parsed;
            }
        } catch (Throwable t) {
            err = t.toString();
        } finally {
            try {
                upright.recycle();
            } catch (Throwable ignore) {
                // 已被旋转替换掉时无法回收，无所谓
            }
        }

        final long cost = System.currentTimeMillis() - t0;
        final String text = raw == null ? "" : raw;
        final String error = err;
        onResult(text, cost, error);
        finishShot();
    }

    /** recognize 的错误串（跨方法传值用，只在 ocrHandler 线程写、主线程读）。 */
    private volatile String lastError = null;

    private void onResult(final String rawText, final long cost, final String error) {
        final String extracted = extractNumbers(rawText);
        final int rawLines = rawText.isEmpty() ? 0 : rawText.split("\n").length;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 相册结果被钉住时，实时帧只跑不刷界面 —— 否则用户刚看清
                // 相册图的结果，下一秒就被摄像头画面盖掉了。
                if (galleryPinned) {
                    return;
                }
                if (error != null) {
                    setStatus("识别异常：" + error, true);
                    return;
                }
                if (extracted.equals(pendingText)) {
                    pendingHits++;
                } else {
                    pendingText = extracted;
                    pendingHits = 1;
                }
                if (pendingHits >= HITS_TO_SETTLE && !extracted.equals(shownText)) {
                    shownText = extracted;
                    renderResult();
                }
                String meta = "共 " + countNumbers(shownText) + " 个数字 · 耗时 " + cost + " ms";
                if (rawLines > 0) {
                    meta += " · 识别到 " + rawLines + " 行";
                }
                if (pendingHits > 0 && pendingHits < HITS_TO_SETTLE) {
                    meta += " · 等待稳定…";
                }
                if (tvMeta != null) {
                    tvMeta.setText(meta);
                }
            }
        });
    }

    // ============================================================
    //  相册选图识别
    // ============================================================
    /**
     * 打开系统文件选择器挑一张图。
     *
     * 用 ACTION_OPEN_DOCUMENT 而不是读相册数据库：
     *   · 走 SAF，**不需要 READ_EXTERNAL_STORAGE**（Android 13 起那个权限
     *     已拆成 READ_MEDIA_IMAGES，适配成本高且用户嫌烦）；
     *   · 用户能选到「下载」「云盘」「微信文件」里的图，不限于相册 App。
     */
    private void pickFromGallery() {
        try {
            // 记住当前暂停状态：用户本就在暂停，取消选图后不该被强制拉回实时。
            pausedBeforePick = paused;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Throwable t) {
            toast("打不开文件选择器");
            AppLog.w(TAG, "打开相册失败: " + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            // 用户取消了：恢复选图前的暂停状态，不要擅自把用户拽回实时。
            paused = pausedBeforePick;
            updatePauseButton();
            if (!paused) {
                setStatus("识别中…", false);
                scheduleShot();
            }
            return;
        }
        final Uri uri = data.getData();
        // 选图期间（Activity 会 onPause → 相机已关）先停心跳，回来后不必
        // 和相册识别抢同一个识别器；识别完成后由 recognizeGalleryImage 决定
        // 是否继续保持暂停。
        galleryBusy = true;
        paused = true;
        updatePauseButton();
        setStatus("相册图片识别中…", false);
        AppLog.i(TAG, "相册选图: " + uri);
        ocrHandler.post(new Runnable() {
            @Override
            public void run() {
                recognizeGalleryImage(uri);
            }
        });
    }

    /**
     * 识别一张相册图（在 ocrHandler 线程执行）。
     *
     * 为什么必须 post 到 ocrHandler：ML Kit 的 TextRecognizer 不是线程安全的，
     * 而实时识别也在这条线程上跑。排队执行天然串行，不需要额外加锁。
     */
    private void recognizeGalleryImage(Uri uri) {
        long t0 = System.currentTimeMillis();
        Bitmap bmp = decodeGalleryBitmap(uri);
        if (bmp == null) {
            final String msg = "图片读取失败（文件可能已删除或格式不支持）";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    galleryBusy = false;
                    setStatus(msg, true);
                    paused = false;
                    updatePauseButton();
                    scheduleShot();
                }
            });
            return;
        }

        MlKitEngine eng = engine;
        if (eng == null) {
            eng = new MlKitEngine();
            eng.init();
            engine = eng;
        }

        String parsed = null;
        String err = null;
        try {
            String json = eng.recognize(bmp, 1);
            lastError = null;
            parsed = parseTexts(json);
            err = lastError;
            lastError = null;
        } catch (Throwable t) {
            err = t.toString();
        } finally {
            try {
                bmp.recycle();
            } catch (Throwable ignore) {
                // 已回收
            }
        }

        final long cost = System.currentTimeMillis() - t0;
        final String text = parsed == null ? "" : parsed;
        final String error = err;
        final String numbers = extractNumbers(text);
        final int rawLines = text.isEmpty() ? 0 : text.split("\n").length;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                galleryBusy = false;
                if (error != null) {
                    setStatus("相册识别失败：" + error, true);
                    paused = false;
                    updatePauseButton();
                    scheduleShot();
                    return;
                }
                // 钉住界面：实时帧不再覆盖这次结果
                galleryPinned = true;
                paused = true;
                updatePauseButton();
                pendingText = "";
                pendingHits = 0;
                shownText = numbers;
                if (numbers.isEmpty()) {
                    // 相册图里确实一个数字都没有时，不能说「还没识别到」——
                    // 那会被误读成「没识别成功」。这里明确区分两种空。
                    tvResult.setTextColor(0xFF9CA3AF);
                    tvResult.setText(rawLines > 0
                            ? "（这张图里有文字，但没有数字）"
                            : "（这张图里没识别到文字）");
                } else {
                    renderResult();
                }
                StringBuilder meta = new StringBuilder("相册图片 · 共 ")
                        .append(countNumbers(numbers)).append(" 个数字 · 耗时 ")
                        .append(cost).append(" ms");
                if (rawLines > 0) {
                    meta.append(" · 识别到 ").append(rawLines).append(" 行");
                }
                if (tvMeta != null) {
                    tvMeta.setText(meta.toString());
                }
                setStatus("相册识别完成（实时已暂停，点「继续」恢复）", false);
                AppLog.i(TAG, "相册识别完成：" + countNumbers(numbers) + " 个数字，耗时 " + cost + "ms");
            }
        });
    }

    /**
     * 解码相册图：先读尺寸算采样率，再真正解码，最后按 EXIF 摆正。
     *
     * 相册图动辄 4000×3000，直接 ARGB_8888 解码要 48MB，低端机上
     * 有大概率 OOM；inSampleSize 是 2 的幂，缩到最长边 ≤ MAX_GALLERY_SIDE
     * 就够识别了。
     */
    private Bitmap decodeGalleryBitmap(Uri uri) {
        try {
            // ① 只读尺寸
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                return null;
            }
            try {
                BitmapFactory.decodeStream(is, null, bounds);
            } finally {
                closeQuietly(is);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / sample > MAX_GALLERY_SIDE) {
                sample *= 2;
            }

            // ② 真正解码（必须重新开一个流：流不可复用）
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream is2 = getContentResolver().openInputStream(uri);
            if (is2 == null) {
                return null;
            }
            Bitmap raw;
            try {
                raw = BitmapFactory.decodeStream(is2, null, opt);
            } finally {
                closeQuietly(is2);
            }
            if (raw == null) {
                return null;
            }
            AppLog.i(TAG, "相册图解码: " + bounds.outWidth + "x" + bounds.outHeight
                    + " sample=" + sample + " -> " + raw.getWidth() + "x" + raw.getHeight());
            return applyExif(raw, readExifOrientation(uri));
        } catch (Throwable t) {
            AppLog.w(TAG, "相册图解码失败: " + t);
            return null;
        }
    }

    /** 读取 EXIF 方向；读不到就当正常（0 度）。 */
    private int readExifOrientation(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
            try {
                ExifInterface exif = new ExifInterface(is);
                return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            } finally {
                closeQuietly(is);
            }
        } catch (Throwable t) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /**
     * 按 EXIF 方向把图摆正。
     *
     * 竖着拍的照片，像素其实是横着存的，只有 EXIF 记了一句「转 90 度」。
     * 不摆正的话送给识别器的就是一整张躺倒的图，识别率会掉一大截。
     */
    private Bitmap applyExif(Bitmap src, int orientation) {
        if (src == null) {
            return null;
        }
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                return src;
        }
        try {
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (out != null && out != src) {
                try {
                    src.recycle();
                } catch (Throwable ignore) {
                    // 无所谓
                }
                return out;
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "EXIF 摆正失败: " + t);
        }
        return src;
    }

    private static void closeQuietly(InputStream is) {
        if (is == null) {
            return;
        }
        try {
            is.close();
        } catch (Throwable ignore) {
            // 关流失败无影响
        }
    }

    // ============================================================
    //  位图转换
    // ============================================================
    /**
     * Image(YUV_420_888) → NV21 → JPEG → Bitmap。
     *
     * 用绝对下标访问 plane，不依赖 buffer 的 position/limit；
     * 某些机型 UV plane 的 rowStride 比宽还大（带 padding），
     * 按行 stride 取才不会把画面搓成斜的。
     */
    private Bitmap imageToBitmap(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) {
            return null;
        }
        byte[] nv21 = new byte[w * h + w * h / 2];

        Image.Plane yp = planes[0];
        ByteBuffer yb = yp.getBuffer();
        int yRow = yp.getRowStride();
        int yPix = yp.getPixelStride();
        int p = 0;
        for (int r = 0; r < h; r++) {
            int base = r * yRow;
            for (int c = 0; c < w; c++) {
                nv21[p++] = yb.get(base + c * yPix);
            }
        }

        Image.Plane up = planes[1];
        Image.Plane vp = planes[2];
        ByteBuffer ub = up.getBuffer();
        ByteBuffer vb = vp.getBuffer();
        int uRow = up.getRowStride();
        int uPix = up.getPixelStride();
        int vRow = vp.getRowStride();
        int vPix = vp.getPixelStride();
        int halfW = w / 2;
        int halfH = h / 2;
        for (int r = 0; r < halfH; r++) {
            int uBase = r * uRow;
            int vBase = r * vRow;
            for (int c = 0; c < halfW; c++) {
                // NV21 是 VU 交错
                nv21[p++] = vb.get(vBase + c * vPix);
                nv21[p++] = ub.get(uBase + c * uPix);
            }
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream(w * h / 4);
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        if (!yuv.compressToJpeg(new Rect(0, 0, w, h), 80, bos)) {
            return null;
        }
        byte[] jpeg = bos.toByteArray();
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opt);
    }

    /**
     * 把帧旋正。相机 sensor 一般装在横向（orientation=90），
     * 而本页锁定竖屏，所以直接按 sensorOrientation 顺时针转即可。
     */
    private Bitmap toUpright(Bitmap src) {
        if (src == null) {
            return null;
        }
        int deg = ((sensorOrientation % 360) + 360) % 360;
        if (deg == 0) {
            return src;
        }
        try {
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (out != null && out != src) {
                try {
                    src.recycle();
                } catch (Throwable ignore) {
                    // 无所谓
                }
                return out;
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "旋正失败: " + t);
        }
        return src;
    }

    // ============================================================
    //  结果解析（数字提取）
    // ============================================================
    /** 从 recognize() 的 JSON 里取出所有行的文本（换行拼接）；出错返回 null 并置 lastError。 */
    private String parseTexts(String json) {
        if (json == null) {
            lastError = "识别返回为空";
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            int status = o.has("status") ? o.get("status").getAsInt() : 0;
            if (status != 200) {
                String msg = "status=" + status;
                if (o.has("data") && o.get("data").isJsonPrimitive()) {
                    msg = o.get("data").getAsString();
                }
                lastError = msg;
                return null;
            }
            JsonArray arr = o.getAsJsonArray("data");
            if (arr == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : arr) {
                if (el != null && el.isJsonObject()) {
                    JsonObject item = el.getAsJsonObject();
                    if (item.has("text")) {
                        sb.append(item.get("text").getAsString()).append('\n');
                    }
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
    }

    /** 数字串：整数或带小数。 */
    private static final Pattern NUM = Pattern.compile("\\d+(?:\\.\\d+)?");
    /** 三位一组的千分位（前面有数字、后面正好三位且不再是数字）。 */
    private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");

    /**
     * 从识别文本里抽出数字，一行一个、按出现顺序、去掉「与上一行重复」的项。
     *
     * 具体规则见类注释第四节。空结果返回空串（不是 null），
     * 调用方据空串显示占位提示。
     */
    static String extractNumbers(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // ① 全角数字与全角小数点到半角
        StringBuilder half = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '０' && c <= '９') {
                half.append((char) ('0' + (c - '０')));
            } else if (c == '．') {
                half.append('.');
            } else {
                half.append(c);
            }
        }
        String s = half.toString();

        StringBuilder out = new StringBuilder();
        for (String line : s.split("[\r\n]+")) {
            // prev 必须在每行开头清空：小票上「单价 12.00 / 小计 12.00」这类
            // 相邻行出现相同数字是常态，跨行去重会把真实金额吃掉。
            String prev = null;
            // ② 千分位逗号去掉
            String norm = THOUSANDS.matcher(line).replaceAll("");
            // ③ 剩下的逗号当小数点
            norm = norm.replace(',', '.');
            // ④ 逐行抽数字
            StringBuilder lineNums = new StringBuilder();
            Matcher m = NUM.matcher(norm);
            while (m.find()) {
                String num = m.group();
                if (num.equals(prev)) {
                    // 同一行里紧跟着的重复值（如 "123 123"）只留一个
                    continue;
                }
                if (lineNums.length() > 0) {
                    lineNums.append(' ');
                }
                lineNums.append(num);
                prev = num;
            }
            if (lineNums.length() > 0) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(lineNums);
            }
        }
        return out.toString();
    }

    /** 数字个数（按空白切分），用于底部小字统计。 */
    private static int countNumbers(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String part : text.split("\\s+")) {
            if (!part.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private void renderResult() {
        if (tvResult == null) {
            return;
        }
        if (shownText.isEmpty()) {
            tvResult.setTextColor(0xFF9CA3AF);
            tvResult.setText("（还没识别到数字，把号码对准画面中央）");
        } else {
            tvResult.setTextColor(0xFFFFFFFF);
            tvResult.setText(shownText);
        }
        // 这里不动 tvMeta —— 它的内容由 onResult 统一拼（数字数 + 耗时），
        // 两处都写会互相覆盖，看上去像「数字统计一闪就没了」。
    }

    // ============================================================
    //  关闭
    // ============================================================
    private void closeCamera() {
        ocrHandler.removeCallbacks(shotRunnable);
        ocrHandler.removeCallbacks(watchdogRunnable);
        capturing = false;
        try {
            if (session != null) {
                session.close();
                session = null;
            }
        } catch (Throwable ignore) {
            // 已断开
        }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Throwable ignore) {
            // 已断开
        }
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Throwable ignore) {
            // 已关闭
        }
        try {
            if (previewSurface != null) {
                previewSurface.release();
                previewSurface = null;
            }
        } catch (Throwable ignore) {
            // 已释放
        }
    }

    // ============================================================
    //  界面
    // ============================================================
    private void buildUi() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(0xFF000000);

        textureView = new TextureView(this);
        rootLayout.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------- 顶部：返回 + 状态 ----------
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));

        Button back = pill("← 返回", Color.WHITE, 0x55202020);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(80), dp(36));
        top.addView(back, backLp);

        tvStatus = new TextView(this);
        tvStatus.setText("相机画面准备中…");
        tvStatus.setTextSize(12f);
        tvStatus.setTextColor(0xFFEDEDED);
        tvStatus.setPadding(dp(10), 0, 0, 0);
        top.addView(tvStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        rootLayout.addView(top, topLp);

        // ---------- 底部：结果面板 ----------
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xE6101318);
        panelBg.setCornerRadius(dp(18));
        bottom.setBackground(panelBg);
        bottom.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView hint = new TextView(this);
        hint.setText("对准要识别的数字，结果自动刷新（只保留数字）");
        hint.setTextSize(11.5f);
        hint.setTextColor(0xFF9CA3AF);
        bottom.addView(hint);

        tvResult = new TextView(this);
        tvResult.setTextSize(20f);
        tvResult.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvResult.setTextColor(0xFF9CA3AF);
        tvResult.setText("（还没识别到数字，把号码对准画面中央）");
        tvResult.setPadding(0, dp(8), 0, dp(6));
        tvResult.setMaxLines(6);
        tvResult.setLineSpacing(0f, 1.15f);
        tvResult.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyResult();
            }
        });
        bottom.addView(tvResult);

        tvMeta = new TextView(this);
        tvMeta.setTextSize(11f);
        tvMeta.setTextColor(0xFF6B7280);
        tvMeta.setText("等待第一帧…");
        bottom.addView(tvMeta);

        // ---------- 底部：按钮行（两行）----------
        // 五个按钮挤一行，每个只剩 1/5 宽度，中文两个字都点不准，
        // 所以拆成 3 + 2 两行。
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        bottom.addView(row, rowLp);

        Button btnGallery = pill("相册", 0xFFFFFFFF, 0x33FFFFFF);
        btnGallery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFromGallery();
            }
        });
        row.addView(btnGallery, weightLp());

        Button btnCopy = pill("复制", 0xFFFFFFFF, 0x33FFFFFF);
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyResult();
            }
        });
        row.addView(btnCopy, weightLp());

        btnPause = pill("暂停", 0xFFFFFFFF, 0x33FFFFFF);
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                paused = !paused;
                updatePauseButton();
                if (paused) {
                    setStatus("已暂停识别（画面仍在预览）", false);
                } else {
                    // 「继续」同时承担「解除相册结果固定」：清掉固定标记，
                    // 让下一帧实时结果能重新刷到界面上。
                    if (galleryPinned) {
                        galleryPinned = false;
                        shownText = "";
                        pendingText = "";
                        pendingHits = 0;
                        renderResult();
                    }
                    setStatus("识别中…", false);
                    scheduleShot();
                }
            }
        });
        row.addView(btnPause, weightLp());

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(6);
        bottom.addView(row2, row2Lp);

        btnTorch = pill("手电筒", 0xFFFFFFFF, 0x33FFFFFF);
        btnTorch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                torchOn = !torchOn;
                updateTorchButton();
                refreshRepeatingPreview();
            }
        });
        row2.addView(btnTorch, weightLp());

        btnRate = pill("节奏·" + INTERVAL_LABELS[intervalIndex], 0xFFFFFFFF, 0x33FFFFFF);
        btnRate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                intervalIndex = (intervalIndex + 1) % INTERVALS.length;
                btnRate.setText("节奏·" + INTERVAL_LABELS[intervalIndex]);
                setStatus("识别节奏：" + INTERVAL_LABELS[intervalIndex]
                        + "（间隔 " + INTERVALS[intervalIndex] + " ms）", false);
            }
        });
        row2.addView(btnRate, weightLp());

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.gravity = Gravity.BOTTOM;
        bottomLp.setMargins(dp(12), 0, dp(12), dp(18));
        rootLayout.addView(bottom, bottomLp);
    }

    private void updatePauseButton() {
        if (btnPause == null) {
            return;
        }
        btnPause.setText(paused ? "继续" : "暂停");
        btnPause.setTextColor(paused ? 0xFFFFD166 : 0xFFFFFFFF);
    }

    private void updateTorchButton() {
        if (btnTorch == null) {
            return;
        }
        btnTorch.setTextColor(torchOn ? 0xFFFFD166 : 0xFFFFFFFF);
        btnTorch.setText(torchOn ? "关灯" : "手电筒");
    }

    private void setStatus(final String text, final boolean warn) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (tvStatus == null) {
                    return;
                }
                tvStatus.setText(text);
                tvStatus.setTextColor(warn ? 0xFFFF9F43 : 0xFFEDEDED);
            }
        });
    }

    private void copyResult() {
        if (shownText == null || shownText.isEmpty()) {
            toast("还没有识别到数字");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("小孙OCR 数字", shownText));
                toast("已复制 " + countNumbers(shownText) + " 个数字");
            }
        } catch (Throwable t) {
            toast("复制失败：" + t.getClass().getSimpleName());
        }
    }

    private Button pill(String text, int textColor, int bgColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTypeface(null, Typeface.BOLD);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(dp(18));
        b.setBackground(d);
        return b;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(38), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}