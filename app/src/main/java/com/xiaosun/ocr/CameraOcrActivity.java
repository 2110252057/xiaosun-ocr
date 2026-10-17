package com.xiaosun.ocr;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
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
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * 二、识别为什么必须走 OCRService（跟随主界面设置的档位）
 * ════════════════════════════════════════════════════════════════
 * 【曾经的做法及其问题】这里原来自己 new 一个 {@link MlKitEngine}，
 * 也就是「本页永远用 ML Kit」。可主界面是有档位设置（tiny / small /
 * rapid）的，用户选了 small（native PP-OCRv6，识别率明显更高）之后，
 * 这一页却还在用 ML Kit 出结果 —— 设置等于被无视。
 *
 * 【为什么不能自建一份 native 引擎】libnjocr.so 里的 det_net、rec_net、
 * is_loaded、g_lock 全是**进程级静态变量**，整个进程只存在一套模型：
 * 自建第二份根本做不到，重复 initModels 还会把服务正在用的模型顶掉。
 *
 * 【所以】统一走 {@link OCRService#processBitmap}，它在内部按 currentTier
 * 分流（tiny/small → native，rapid → ML Kit）。设置里选什么，这里就用什么。
 * 服务实例由 {@link OCRService#get()} 提供（onBind 返回 null，所以服务
 * 在 onCreate/onDestroy 里挂/摘一个静态引用）。
 *
 * 【代价】OCR 服务未启用时本页不能识别，状态栏会直接说明原因
 * （「启用 OCR 服务已关闭」/「模型仍在加载中」），不会静默退回 ML Kit。
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
 *
 * ════════════════════════════════════════════════════════════════
 * 七、相册模式为什么要「显示图片 + 画框」
 * ════════════════════════════════════════════════════════════════
 * 取件码场景的输入常常是一张很长的清单截图（几十上百行，每行一串
 * 四位数字）。只把数字抽出来摆成一列，人根本对不上号 —— 究竟框的是
 * 哪一行、有没有漏、有没有把「货架号」当成了取件码，光看数字看不出来。
 * 所以相册模式做两件事：
 *   · 原图直接显示在页面上（居中缩放，上方给返回栏、下方让开结果面板）；
 *   · 用 type=2 识别拿到每行包围盒，只给「这一行里确实有数字」的行描框，
 *     并把这一行抽出来的数字**写在框里** —— 清单截图上的字又小又挤，
 *     人眼要确认「这个框取的是 8512 还是 8415」，只能靠框里那串数字，
 *     光有框看不出来。
 *   · 框里的数字有下限：整张长图缩到手机上时单行可能只有 6 个屏幕像素，
 *     这种密度下写字只剩一层白噪点，所以行高不到约 11px 就不画 ——
 *     双指放大到能读了它自己出现，不需要额外开关。
 * 图与框由同一个 {@link GalleryOverlay} 画：一次算准 scale 与偏移，
 * 图在哪框就在哪，不会出现两层各自缩放导致的错位。
 */
public class CameraOcrActivity extends AppCompatActivity {

    private static final String TAG = "XSOCR-Camera";

    /** 运行时权限请求码。 */
    private static final int REQ_CAMERA = 9101;

    /** 相册选图请求码。 */
    private static final int REQ_PICK_IMAGE = 9102;

    /**
     * 相册图解码后的像素总量上限（约 48MB ARGB_8888）。
     *
     * 【为什么不用「最长边」限制】取件码清单是一张又宽又长的截图，按最长边
     * 等比缩放会把宽度一起压下去 —— 一张 1080×8000 的图缩到最长边 2560，
     * 宽度只剩 345 像素，一行四位数字连笔画都挤没了，识别率断崖下跌。
     * 所以改成：**宽度**不超 {@link #MAX_GALLERY_WIDTH}、**总像素**不超本值，
     * 两者都不必牺牲宽度。高度超了由分块识别解决（见 {@link #CHUNK_H}）。
     */
    private static final int MAX_GALLERY_PIXELS = 12_000_000;

    /** 解码后宽度上限。超过就降采样，太宽的图对识别没好处，只烧内存。 */
    private static final int MAX_GALLERY_WIDTH = 2400;

    /**
     * 分块识别：每块的高度（像素）。
     *
     * ML Kit 拿到一张 8000 像素高的图会自己缩到很小的尺寸再识别，小字直接
     * 消失；切成 1400 高的块逐块送进去，每块的字高才是「原始字高」，
     * 这是长截图能认出字的前提。
     */
    private static final int CHUNK_H = 1400;

    /**
     * 相邻块的重叠高度。
     *
     * 不重叠的话，正好压在切缝上的那一行会被切成两半，两边都认不出；
     * 重叠 160 像素（约两行）让每行至少在某一整块里完整出现。
     */
    private static final int CHUNK_OVERLAP = 160;

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

    /** 相册图片 + 识别框的显示层（实时模式下 GONE）。 */
    private GalleryOverlay overlay;
    /** 底部结果面板的实测高度，用来给图片让出显示空间。 */
    private int panelHeight = 0;

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
    /**
     * 识别是否统一走 {@link OCRService#processBitmap}。
     *
     * 【为什么不再自建引擎】原来这里持有 `volatile MlKitEngine engine`，
     * 也就是「摄像头页永远用 ML Kit」——跟主界面设置的档位完全无关。
     * 但用户选了 small（native PP-OCRv6，识别率高得多），这一页却还在用
     * ML Kit 出结果，等于设置被无视。
     *
     * 而且 native 引擎根本没法在界面里单独建一份：libnjocr.so 里的
     * det_net / rec_net / is_loaded / g_lock 全是**进程级静态变量**，
     * 整个进程只有一套模型。所以这页必须借 OCRService 那一份用，
     * 顺带就自动跟随了档位。
     *
     * 代价：OCR 服务没启用时这一页不能识别（会在状态栏说明原因）。
     * 这个代价是用户认可的 —— 「启用 OCR 以后才能用也没关系」。
     */
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
        ensureService();
        AppLog.i(TAG, "摄像头实时识别页已打开");
    }

    /**
     * 确保 OCR 服务在跑 —— 本页的识别完全依赖它。
     *
     * 【为什么要主动拉】用户可能从桌面快捷方式/最近任务直接进到这一页，
     * 而服务的拉起原本只在 {@code MainActivity.ensureServiceRunning()} 里做。
     * 不拉的话这一页会一直显示「OCR 服务未启动」，看上去像坏了。
     *
     * 【为什么不在开关关闭时也硬拉】那个开关是用户明确关掉的
     * （只想用手势、不想扛前台通知和模型内存），硬拉等于推翻他的选择。
     * 所以开关关闭时只在状态栏说清楚，不启动。
     */
    private void ensureService() {
        if (OCRService.get() != null) {
            return;
        }
        if (!OCRService.isEnabled(this)) {
            setStatus("「启用 OCR 服务」已关闭，本页无法识别", true);
            return;
        }
        try {
            Intent intent = new Intent(this, OCRService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            setStatus("正在启动 OCR 服务…", false);
            AppLog.i(TAG, "OCR 服务未运行，已在本页拉起");
        } catch (Throwable t) {
            AppLog.w(TAG, "拉起 OCR 服务失败: " + t);
            setStatus("OCR 服务启动失败：" + t.getClass().getSimpleName(), true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopping = false;
        // 上次是「识别中被切走」离开的：图还留在 overlay 上没回收。回到页面时
        // 相册模式已经解除（onPause 清了 galleryPinned），该把图撤掉给实时预览让路。
        if (!galleryPinned && !galleryBusy && overlay != null
                && overlay.getVisibility() == View.VISIBLE) {
            hideGalleryImage();
        }
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
        // 正在识别时不要抢走 overlay 里的位图：识别线程还在用它（切块、送
        // ML Kit），回收掉会让识别抛「recycled bitmap」。此时连 galleryBusy
        // 也保持 true，等识别自己回来收尾（它会在 stopping 时负责回收）。
        if (!galleryBusy) {
            hideGalleryImage();
            galleryBusy = false;
        }
        closeCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopping = true;
        closeCamera();
        // 走 destroy 时 overlay 还没回收的位图必须在这里收掉：onPause 可能因为
        // 「识别还在跑」而跳过回收，之后识别回来发现 stopping 会自己收；
        // 但如果页面是直接被销毁、识别线程随后退出，就只能靠这一句兜底。
        if (!galleryBusy) {
            hideGalleryImage();
        }
        // 【不要动引擎】识别走的是 OCRService 那份共享实例，
        // 在这里 close 会把整个 App 的识别能力一起关掉（服务也再要一遍模型）。
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
                            // 相册图正盖在预览之上（不透明），此时再推帧只是白烧电：
                            // 画面谁也看不见，识别也已经暂停。
                            if (galleryPinned || galleryBusy
                                    || (overlay != null && overlay.getVisibility() == View.VISIBLE)) {
                                return;
                            }
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

        String raw = "";
        String err = null;
        try {
            // 顺序很重要：先清错误位、再 recognize、再 parse，最后才读错误位。
            // 之前写成「recognize 后立刻读」会把 parseTexts 里记下的错误吞掉，
            // 表现为识别失败时界面只是「没识别到数字」，查不到真正原因。
            String json = recognizeJson(upright, 1);
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

    /**
     * 统一识别出口：把位图交给 {@link OCRService#processBitmap}，
     * 用**主界面设置的那个档位**来识别。
     *
     * 【为什么是它】processBitmap 内部已经按 currentTier 分流：
     *   tiny / small → native PP-OCRv6（libnjocr.so 里那套模型）
     *   rapid        → MlKitEngine（ML Kit）
     * 也就是说「设置里选了什么，这里就用什么」，不需要在这一页再判断一次。
     *
     * 【为什么不自己 new 引擎】native 侧是进程级单例（.so 里的静态
     * det_net / rec_net / is_loaded），自建第二份既做不到、也没有意义；
     * 重复 initModels 还会把服务正在用的模型顶掉。
     *
     * 服务不可用时返回与 native 同构的错误 JSON（status=503），
     * 让上层 parseTexts / parseLines 走既有错误分支，不必各写一套。
     *
     * @param bmp  待识别位图（生命周期由调用方负责）
     * @param type 1=纯文本；>=2 含坐标
     * @return 与 native 格式一致的 JSON（**绝不返回 null**）
     */
    private String recognizeJson(Bitmap bmp, int type) {
        OCRService svc = OCRService.get();
        if (svc == null) {
            String why = OCRService.isEnabled(this)
                    ? "OCR 服务未启动，请回到主界面稍候，或检查「启用 OCR 服务」开关"
                    : "「启用 OCR 服务」已关闭，本页无法识别";
            return "{\"status\": 503, \"data\": \"Error: " + why + "\"}";
        }
        if (!OCRService.engineReady()) {
            String why = OCRService.engineBlockReason();
            if (why == null || why.isEmpty()) {
                why = "模型仍在加载中";
            }
            return "{\"status\": 503, \"data\": \"Error: " + why + "\"}";
        }
        try {
            return svc.processBitmap(bmp, type);
        } catch (Throwable t) {
            AppLog.w(TAG, "识别调用失败: " + t);
            return "{\"status\": 500, \"data\": \"Error: " + t + "\"}";
        }
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
                // 带上档位：换了 small / rapid 之后识别效果差别很大，
                // 出问题时要能一眼看出「现在到底是谁在算」。
                String tier = OCRService.tierName(OCRService.currentTier);
                if (tier != null && !tier.isEmpty()) {
                    meta += " · " + tier;
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

        // 先出图、后出框：相册图看一眼就知道选没选对，没必要让人盯着
        // 「识别中…」干等一两秒。框等识别完再补上去。
        final Bitmap shown = bmp;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showGalleryImage(shown, null);
                setStatus("相册图片识别中…", false);
            }
        });

        List<TextLine> lines = null;
        String err = null;
        int chunks = 0;
        try {
            // chunkCount[0] 出参：实际切了几块（1 = 整张一次认出），只用于展示
            int[] chunkCount = new int[1];
            lines = recognizeInChunks(bmp, chunkCount);
            err = lastError;
            lastError = null;
            chunks = chunkCount[0];
        } catch (Throwable t) {
            err = t.toString();
        }
        // 注意：这里**不**回收 bmp。它已经交给 overlay 显示在屏幕上了，
        // 回收掉再画就会崩（Canvas: trying to use a recycled bitmap）。
        // 失败时由 hideGalleryImage() 统一回收。

        final List<TextLine> resultLines = lines;
        final long cost = System.currentTimeMillis() - t0;
        final String error = err;
        final int chunkUsed = chunks;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                galleryBusy = false;
                if (stopping) {
                    // 识别期间用户离开了页面：别再往已经不可见的界面上写状态，
                    // 直接把图收掉，回到页面时是干净的实时页。
                    hideGalleryImage();
                    return;
                }
                if (error != null || resultLines == null) {
                    hideGalleryImage();
                    startRepeatingPreview();
                    setStatus("相册识别失败：" + error, true);
                    paused = false;
                    updatePauseButton();
                    scheduleShot();
                    return;
                }

                // 拆成「全文」与「数字行框」两部分：
                //   · 全文跑既有的 extractNumbers，口径与实时识别完全一致；
                //   · 框只留在有数字的行上 —— 整张图每行都描框会糊成一片，
                //     反而看不出哪行是取件码。
                // 框上带 label：就是这一行抽出来的数字（与下方列表同一套口径），
                // 这样「框住的那行」与「列表里的那串」能一对一印证。
                StringBuilder sb = new StringBuilder();
                List<NumBox> boxes = new ArrayList<>();
                for (TextLine ln : resultLines) {
                    if (ln.text != null) {
                        sb.append(ln.text).append('\n');
                    }
                    if (ln.box == null) {
                        continue;
                    }
                    String label = extractNumbers(ln.text);
                    if (label.isEmpty()) {
                        continue;
                    }
                    boxes.add(new NumBox(ln.box, label));
                }
                final String text = sb.toString();
                final int rawLines = resultLines.size();
                final String numbers = extractNumbers(text);

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
                if (!boxes.isEmpty()) {
                    meta.append(" · 框出 ").append(boxes.size()).append(" 行");
                }
                if (chunkUsed > 1) {
                    meta.append(" · 分 ").append(chunkUsed).append(" 块识别");
                }
                if (tvMeta != null) {
                    tvMeta.setText(meta.toString());
                }
                // 补框：同一张位图，overlay 内部判断到是同一个对象就不会回收
                showGalleryImage(shown, boxes);
                setStatus("相册识别完成：绿框标出含数字的行，放大即可看到框里的数字", false);
                AppLog.i(TAG, "相册识别完成：行 " + rawLines + "，数字 "
                        + countNumbers(numbers) + "，框 " + boxes.size()
                        + "，耗时 " + cost + "ms");
            }
        });
    }

    /**
     * 分块识别一张（可能很长的）相册图。
     *
     * 【为什么要分块】ML Kit 内部会把超大图缩到很小的尺寸再识别，一张
     * 8000 像素高的取件码截图整体送进去，每行四位数字会被缩成两三个像素，
     * 认出来一堆乱码。切块之后每块的字高保持原样，识别率才有保证。
     *
     * 【为什么不重不漏】相邻块重叠 {@link #CHUNK_OVERLAP} 像素，但每块
     * 只采纳「行中心落在本块专属区」的行 —— 重叠区交给先出现的那一块，
     * 后一块在重叠区里只补前一块没覆盖到的部分。判定用行中心而不是
     * 整框包含，是因为压在切缝上的行框必然跨块，用「框完全在内」会两边都不收。
     *
     * @param chunkCount 出参：实际切了几块（1 表示没切）
     * @return 全部行（坐标已加回整图 y），失败返回 null 且 lastError 有值
     */
    private List<TextLine> recognizeInChunks(Bitmap bmp, int[] chunkCount) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        List<TextLine> all = new ArrayList<>();

        // 不太长就直接整张识别，省掉切块开销
        if (h <= CHUNK_H * 3 / 2) {
            chunkCount[0] = 1;
            lastError = null;
            String json = recognizeJson(bmp, 2);
            List<TextLine> once = parseLines(json);
            if (lastError != null) {
                return null;
            }
            return once == null ? all : once;
        }

        int step = CHUNK_H - CHUNK_OVERLAP;
        // 先算出总块数，纯粹为了给用户显示进度：长图要切七八块、每块一次
        // ML Kit 调用，不给进度的话这几秒界面一动不动，很像卡死。
        int total = (h + step - 1) / step;
        int idx = 0;
        for (int y0 = 0; y0 < h; y0 += step) {
            int y1 = Math.min(h, y0 + CHUNK_H);
            // 本块「独占」的纵向范围：首块从头开始，末块到图底结束，
            // 中间块各让掉半个重叠区给邻居。
            int ownTop = (y0 == 0) ? 0 : y0 + CHUNK_OVERLAP / 2;
            int ownBottom = (y1 >= h) ? h : y1 - CHUNK_OVERLAP / 2;

            Bitmap sub = null;
            try {
                sub = Bitmap.createBitmap(bmp, 0, y0, w, y1 - y0);
            } catch (Throwable t) {
                AppLog.w(TAG, "切块失败 y0=" + y0 + ": " + t);
                lastError = "切块失败: " + t;
                return null;
            }
            try {
                lastError = null;
                String json = recognizeJson(sub, 2);
                List<TextLine> part = parseLines(json);
                if (lastError != null) {
                    return null;
                }
                if (part != null) {
                    for (TextLine ln : part) {
                        if (ln.box == null) {
                            continue;
                        }
                        // 行中心换回整图坐标，再决定这一行归谁
                        float centerY = y0 + (ln.box.top + ln.box.bottom) / 2f;
                        if (centerY < ownTop || centerY >= ownBottom) {
                            continue;
                        }
                        RectF lifted = new RectF(ln.box.left, ln.box.top + y0,
                                ln.box.right, ln.box.bottom + y0);
                        all.add(new TextLine(ln.text, lifted));
                    }
                }
            } finally {
                if (sub != null && !sub.isRecycled()) {
                    try {
                        sub.recycle();
                    } catch (Throwable ignore) {
                        // 已回收
                    }
                }
            }
            idx++;
            if (total > 2) {
                setStatus("长图分块识别中… " + idx + "/" + total, false);
            }
            if (y1 >= h) {
                break;
            }
        }
        chunkCount[0] = idx;
        AppLog.i(TAG, "长图分块识别: " + w + "x" + h + " → " + idx + " 块，共 "
                + all.size() + " 行");
        return all;
    }

    /**
     * 解码相册图：先读尺寸算采样率，再真正解码，最后按 EXIF 摆正。
     *
     * 相册图动辄 4000×3000，直接 ARGB_8888 解码要 48MB，低端机上
     * 有大概率 OOM；inSampleSize 是 2 的幂，按「宽度 ≤ MAX_GALLERY_WIDTH
     * 且总像素 ≤ MAX_GALLERY_PIXELS」降采样就够识别了（不按最长边 ——
     * 长截图等比缩会把宽度压没，见 MAX_GALLERY_PIXELS 的注释）。
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
            // 采样率按「宽度上限 + 总像素上限」算，不按最长边 ——
            // 见 MAX_GALLERY_PIXELS 的注释：长截图等比缩会把字压没。
            int sample = 1;
            while ((bounds.outWidth / sample) > MAX_GALLERY_WIDTH
                    || ((long) (bounds.outWidth / sample) * (bounds.outHeight / sample))
                    > MAX_GALLERY_PIXELS) {
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

    /**
     * 显示相册图（并把数字行框上去）。
     *
     * 调用两次：解码后先传 boxes=null 出图，识别完再带上框补画一次。
     * 同一个 Bitmap 传两次是安全的 —— {@link GalleryOverlay#setImage}
     * 只在「新旧不是同一个对象」时才回收旧的。
     */
    private void showGalleryImage(Bitmap bmp, List<NumBox> boxes) {
        if (overlay == null || bmp == null) {
            return;
        }
        if (stopping) {
            // 页面已经离开（比如识别过程中按了返回），别再往界面上挂图。
            // 但如果 overlay 此刻正持有这张图（第二次带框调用），就不能回收 ——
            // 回收了 overlay 再画就会崩，交给 hideGalleryImage/clear 处理。
            if (overlay.getImage() != bmp) {
                try {
                    bmp.recycle();
                } catch (Throwable ignore) {
                    // 已回收
                }
            }
            return;
        }
        overlay.setImage(bmp, boxes);
        overlay.setVisibility(View.VISIBLE);
        // 图片已经整屏盖住预览了，没必要继续往 Surface 上推帧 —— 停掉省电。
        try {
            if (session != null) {
                session.stopRepeating();
            }
        } catch (Throwable ignore) {
            // 会话可能已被关闭
        }
    }

    /** 撤掉相册图（顺带回收位图），回到实时预览。 */
    private void hideGalleryImage() {
        if (overlay == null) {
            return;
        }
        overlay.clear();
        overlay.setVisibility(View.GONE);
    }

    /**
     * 解析 recognize(type=2) 的结果：每行取 text 与包围盒。
     *
     * 与 {@link #parseTexts} 的唯一区别是保留坐标（要画框）。
     * 出错时同样返回 null 并置 {@link #lastError}。
     */
    private List<TextLine> parseLines(String json) {
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
            List<TextLine> out = new ArrayList<>();
            if (arr == null) {
                return out;
            }
            for (JsonElement el : arr) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject item = el.getAsJsonObject();
                if (!item.has("text")) {
                    continue;
                }
                String text = item.get("text").getAsString();
                RectF box = null;
                if (item.has("x") && item.has("y") && item.has("w") && item.has("h")) {
                    int x = item.get("x").getAsInt();
                    int y = item.get("y").getAsInt();
                    int w = item.get("w").getAsInt();
                    int h = item.get("h").getAsInt();
                    // 坐标 <0 表示这一行没给包围盒，别画到左上角去
                    if (x >= 0 && y >= 0 && w > 0 && h > 0) {
                        box = new RectF(x, y, x + w, y + h);
                    }
                }
                out.add(new TextLine(text, box));
            }
            return out;
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
    }

    /**
     * 一个「数字框」：图上那一行的位置 + 这一行抽出来的数字。
     *
     * 与 {@link TextLine} 的区别是语义：TextLine 装识别原文，NumBox 装的
     * 已经是按本项目规则抽好的数字串（如 {@code "8512"}；一行里有多个数字
     * 时是 {@code "2 2"}），直接画到框里。
     */
    private static final class NumBox {
        final RectF rect;
        final String label;

        NumBox(RectF rect, String label) {
            this.rect = rect;
            this.label = label;
        }
    }

    /** 一行识别结果：文本 + 图片像素坐标（没有包围盒时 box 为 null）。 */
    private static final class TextLine {
        final String text;
        final RectF box;

        TextLine(String text, RectF box) {
            this.text = text;
            this.box = box;
        }
    }

    /**
     * 相册图片 + 识别框的显示层。
     *
     * 为什么不拆成「ImageView 显示图」+「另一个 View 画框」两层：两层各自
     * 算一次「图在哪、缩放多少」，稍有偏差框就和文字错位；而且两层要跟着
     * 尺寸变化同步，多一份状态就多一个 bug 来源。这里一次算准，
     * 先画图、再用同一套坐标画框。
     *
     * 绘制区域由 padding 决定：上边给顶部返回栏让位、下边给结果面板让位，
     * 两者之间的可用区里把图按 CENTER_INSIDE 居中。
     *
     * 【为什么要能缩放】取件码清单是张很长的截图，整张塞进手机屏幕后
     * 单行只剩三五个像素高，框叠在一起、数字根本看不清 —— 那这个「框」
     * 就等于没做。所以支持双指放大、单指拖动、双击在 1x/2.5x 之间切换，
     * 放大后框线宽保持不变（按屏幕坐标画，不随图放大变粗）。
     */
    private class GalleryOverlay extends View
            implements GestureDetector.OnGestureListener,
            GestureDetector.OnDoubleTapListener {

        private Bitmap image;
        private final List<NumBox> boxes = new ArrayList<>();
        private final Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint boxFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** 框里的数字：白色实心 + 深色描边，黑白底图上都能读。 */
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** 字号上下限与内边距（像素），构造时算好，onDraw 里别再反复 dp()。 */
        private final float labelMaxPx;
        private final float labelMinPx;
        private final float labelPadPx;
        /** 未缩放时图片所占的屏幕矩形（每次 onDraw 重算）。 */
        private final RectF baseRect = new RectF();
        private final RectF oneBox = new RectF();
        private final float roundPx;
        private final float minBoxH;
        private final float baseStroke;

        private final GestureDetector gestures;
        private final ScaleGestureDetector scaler;

        /** 用户额外放大的倍数（1 = 整图适配屏幕）。 */
        private float userScale = 1f;
        /** 相对「居中位置」的平移。 */
        private float panX = 0f;
        private float panY = 0f;
        /** 放大上限：再大也看不出更多东西，只会糊。 */
        private static final float MAX_SCALE = 8f;

        GalleryOverlay(Context context) {
            super(context);
            setBackgroundColor(0xFF000000);
            setClickable(true);   // 吃掉点击，别穿到下面的预览层去
            roundPx = dp(3);
            // 只在下限以下才撑高，且撑得很少：取件码清单一张图能有 200 多行，
            // 每行在屏幕上只分到七八像素，撑到 10dp 会让上下相邻的框全部叠住，
            // 整片糊成绿色。所以能看见就不撑。
            minBoxH = dp(3);
            baseStroke = dp(1.8f);
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(baseStroke);
            // 亮绿：取件码截图多是黑底白字，绿框在两种底色上都够跳
            boxPaint.setColor(0xFF3DDC97);
            // 框内极淡的一层绿，让「这一行被选中」在缩得很小的长图上也能一眼看到
            boxFill.setStyle(Paint.Style.FILL);
            boxFill.setColor(0x223DDC97);

            labelMaxPx = dp(13);
            // 下限用 dp(3.2) ≈ 11 屏幕像素（560dpi 下）：这是 4 位数字还能
            // 认出来的最小字号。取 dp(8) 的话等于 28 屏幕像素，而 238 行的
            // 清单图 1x 时每行只有 6px，得放大 4.7 倍才够 —— 那用户在全景和
            // 双击 2.5x 下都看不到数字，会以为功能没做。
            labelMinPx = dp(3.2f);
            labelPadPx = dp(2.5f);
            labelPaint.setStyle(Paint.Style.FILL);
            labelPaint.setColor(0xFFFFFFFF);
            labelPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            labelEdge.setStyle(Paint.Style.STROKE);
            labelEdge.setStrokeJoin(Paint.Join.ROUND);
            labelEdge.setColor(0xE6000000);
            labelEdge.setTypeface(labelPaint.getTypeface());

            gestures = new GestureDetector(context, this);
            gestures.setOnDoubleTapListener(this);
            scaler = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector d) {
                            setUserScale(userScale * d.getScaleFactor());
                            return true;
                        }
                    });
        }

        /** 当前持有的位图（供外部判断「这张图是不是已经在显示」，避免误回收）。 */
        Bitmap getImage() {
            return image;
        }

        void setImage(Bitmap bmp, List<NumBox> newBoxes) {
            Bitmap old = image;
            boolean sameImage = (old == bmp);
            image = bmp;
            boxes.clear();
            if (newBoxes != null) {
                boxes.addAll(newBoxes);
            }
            if (!sameImage) {
                // 换了一张图才复位缩放；同一张图补框时保持用户当前的放大位置，
                // 否则刚放大找到某一行，框一补上来画面就跳回全景，白找了。
                resetView();
            }
            if (old != null && old != bmp && !old.isRecycled()) {
                try {
                    old.recycle();
                } catch (Throwable ignore) {
                    // 正在绘制时回收会抛，忽略即可
                }
            }
            invalidate();
        }

        void clear() {
            Bitmap old = image;
            image = null;
            boxes.clear();
            resetView();
            if (old != null && !old.isRecycled()) {
                try {
                    old.recycle();
                } catch (Throwable ignore) {
                    // 同上
                }
            }
            invalidate();
        }

        private void resetView() {
            userScale = 1f;
            panX = 0f;
            panY = 0f;
        }

        private void setUserScale(float target) {
            float s = target;
            if (s < 1f) {
                s = 1f;
            }
            if (s > MAX_SCALE) {
                s = MAX_SCALE;
            }
            userScale = s;
            clampPan();
            invalidate();
        }

        /**
         * 限制平移范围：图比可用区大时最多拖到边缘，比可用区小时不许拖
         * （否则能把图拖出屏幕外，剩一片黑，看着像崩了）。
         */
        private void clampPan() {
            float availW = getWidth() - getPaddingLeft() - getPaddingRight();
            float availH = getHeight() - getPaddingTop() - getPaddingBottom();
            if (availW <= 0 || availH <= 0 || baseRect.width() <= 0) {
                panX = 0f;
                panY = 0f;
                return;
            }
            float maxX = Math.max(0f, (baseRect.width() * userScale - availW) / 2f);
            float maxY = Math.max(0f, (baseRect.height() * userScale - availH) / 2f);
            if (panX > maxX) {
                panX = maxX;
            } else if (panX < -maxX) {
                panX = -maxX;
            }
            if (panY > maxY) {
                panY = maxY;
            } else if (panY < -maxY) {
                panY = -maxY;
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            clampPan();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (image == null) {
                return super.onTouchEvent(e);
            }
            scaler.onTouchEvent(e);
            if (!scaler.isInProgress()) {
                gestures.onTouchEvent(e);
            }
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                // 缩回接近 1 倍就吸附回正中，避免留一点点偏移看着别扭
                if (userScale < 1.02f) {
                    resetView();
                    invalidate();
                }
            }
            return true;
        }

        // ---------- 单指拖动 ----------
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public void onShowPress(MotionEvent e) {
            // 不需要
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            return false;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            if (userScale <= 1.001f) {
                return false;   // 没放大时拖动没意义
            }
            panX -= dx;
            panY -= dy;
            clampPan();
            invalidate();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            // 不需要
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            return false;   // 长图浏览靠拖，不做惯性，免得甩丢位置
        }

        // ---------- 双击 ----------
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (userScale > 1.5f) {
                resetView();
            } else {
                setUserScale(2.5f);
            }
            invalidate();
            return true;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            return false;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return false;
        }

        /**
         * 把这一行抽出的数字写进框里。
         *
         * 字号取 {@code min(rawH × 0.78, labelMaxPx)}，rawH 是框在
         * **当前屏幕上**的真实高度（已含用户缩放），所以放大后数字自然变大，
         * 直到 labelMaxPx 封顶（免得一个框里顶个巨大数字）。
         * rawH 低于 labelMinPx 时**整个不画**（不是缩到下限）——
         * 下限只有约 11 个屏幕像素，再往下就没有字形可言了。
         *
         * @param rawH 撑高之前的框高（屏幕像素，已含用户缩放）
         */
        private void drawLabel(Canvas canvas, String text, RectF boxRect, float rawH) {
            if (text == null || text.isEmpty()) {
                return;
            }
            // 屏幕行高不够 → 不画。取件码清单一张图能有 200 多行，3072×4096
            // 的图缩到手机上每行只剩 6 像素，这种密度下硬画数字只会得到
            // 一层糊在一起的白色噪点，比空框更难看清。放大到行高够读了再写。
            if (rawH < labelMinPx) {
                return;
            }
            float fontSize = rawH * 0.78f;
            if (fontSize > labelMaxPx) {
                fontSize = labelMaxPx;
            }
            labelPaint.setTextSize(fontSize);
            float avail = boxRect.width() - labelPadPx * 2f;
            if (avail <= 0) {
                return;
            }
            float measured = labelPaint.measureText(text);
            if (measured > avail) {
                fontSize *= avail / measured;
                if (fontSize < labelMinPx * 0.6f) {
                    // 框窄到放不下：画出来就是一团白点，不如不画
                    return;
                }
                labelPaint.setTextSize(fontSize);
            }
            labelEdge.setTextSize(fontSize);
            labelEdge.setStrokeWidth(Math.max(1f, fontSize * 0.22f));

            // 垂直居中：用 ascent/descent 摆正基线，别让数字贴着框上沿
            float baseline = boxRect.centerY()
                    - (labelPaint.descent() + labelPaint.ascent()) / 2f;
            // 水平位置夹到视口内：放大后框的左边界可能已经滑到屏幕外，
            // 数字若还跟着“粘”在框左沿就一起出屏了，看上去仍是空框。
            float textW = labelPaint.measureText(text);
            float x = boxRect.left + labelPadPx;
            float minX = labelPadPx;
            float maxX = getWidth() - labelPadPx - textW;
            if (x > maxX) {
                x = maxX;
            }
            if (x < minX) {
                x = minX;
            }
            canvas.drawText(text, x, baseline, labelEdge);
            canvas.drawText(text, x, baseline, labelPaint);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap bmp = image;
            if (bmp == null || bmp.isRecycled()) {
                return;
            }
            float availW = getWidth() - getPaddingLeft() - getPaddingRight();
            float availH = getHeight() - getPaddingTop() - getPaddingBottom();
            if (availW <= 0 || availH <= 0) {
                return;
            }
            // CENTER_INSIDE：整张图完整可见，且不变形
            float scale = Math.min(availW / bmp.getWidth(), availH / bmp.getHeight());
            float drawW = bmp.getWidth() * scale;
            float drawH = bmp.getHeight() * scale;
            float left = getPaddingLeft() + (availW - drawW) / 2f;
            float top = getPaddingTop() + (availH - drawH) / 2f;
            baseRect.set(left, top, left + drawW, top + drawH);

            float cx = baseRect.centerX();
            float cy = baseRect.centerY();
            float tx = cx + panX;
            float ty = cy + panY;

            // 图片：直接交给 canvas 变换，缩放由 GPU 采样，最省事也最快
            canvas.save();
            canvas.translate(tx, ty);
            canvas.scale(userScale, userScale);
            canvas.translate(-cx, -cy);
            canvas.drawBitmap(bmp, null, baseRect, imgPaint);
            canvas.restore();

            // 框：按「屏幕坐标」自己算，不放在上面那个变换里 ——
            // 否则放大 8 倍时线宽也跟着粗 8 倍，糊成一片。
            float stroke = baseStroke / (float) Math.sqrt(userScale);
            boxPaint.setStrokeWidth(stroke);
            for (NumBox nb : boxes) {
                if (nb == null || nb.rect == null) {
                    continue;
                }
                RectF box = nb.rect;
                // ① 先映射到「未缩放时的屏幕坐标」
                float l = baseRect.left + box.left * scale;
                float t = baseRect.top + box.top * scale;
                float r = baseRect.left + box.right * scale;
                float b = baseRect.top + box.bottom * scale;
                // ② 再套上与图片相同的缩放与平移
                l = tx + (l - cx) * userScale;
                r = tx + (r - cx) * userScale;
                t = ty + (t - cy) * userScale;
                b = ty + (b - cy) * userScale;
                // 整张长图缩下来后单行可能只有两三个像素高，框会糊成一条线；
                // 撑到最小高度，至少能看清「这一行被选中了」。
                // 但撑高幅度最多翻倍 —— 238 行的清单图上相邻行只隔 6px，
                // 一律撑到 minBoxH 会让上下相邻的框直接叠在一起。
                // rawH 要留「撑高之前」的值：字号该按真实可见高度算，
                // 按撑高后的值算会得到一个和图上文字毫无关系的字号。
                float rawH = b - t;
                float target = Math.min(minBoxH, rawH * 2f);
                if (rawH < target) {
                    float mid = (t + b) / 2f;
                    t = mid - target / 2f;
                    b = mid + target / 2f;
                }
                oneBox.set(l, t, r, b);
                // 淡绿填充只在「框没被撑高」时才画：
                // 一旦被撑高，说明真实行高小于 minBoxH，相邻行的间距也同样小，
                // 一层层半透明绿叠上去会连成整片绿，反而看不出哪一行被选中。
                // 这时只留描边；放大到 rawH ≥ minBoxH 后填充自然出现。
                if (rawH >= minBoxH) {
                    canvas.drawRoundRect(oneBox, roundPx, roundPx, boxFill);
                }
                canvas.drawRoundRect(oneBox, roundPx, roundPx, boxPaint);
                drawLabel(canvas, nb.label, oneBox, rawH);
            }
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

        // 相册图 + 识别框这一层盖在预览之上。z 序由 addView 先后决定：
        // 它在 top / bottom 之前加，所以返回栏和结果面板仍然压在它上面、
        // 按钮不会被图片挡住。
        overlay = new GalleryOverlay(this);
        overlay.setVisibility(View.GONE);
        rootLayout.addView(overlay, new FrameLayout.LayoutParams(
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
                    // 撤掉相册图、把预览重新推起来（显示图片时停过 repeating）
                    hideGalleryImage();
                    startRepeatingPreview();
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

        // 结果面板是 wrap_content（数字多时会长高），so 用实测高度给图片留地，
        // 否则长清单被面板遮掉下面一截，用户以为没识别全。
        bottom.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                int h = b - t;
                if (h > 0 && h != panelHeight) {
                    panelHeight = h;
                    if (overlay != null) {
                        overlay.setPadding(0, dp(60), 0, h + dp(10));
                        overlay.invalidate();
                    }
                }
            }
        });
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