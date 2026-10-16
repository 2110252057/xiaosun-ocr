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

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OCR 前台服务：加载本地模型并用 HTTP 暴露识别接口。
 *
 * 【JNI 约定】native 库（libnjocr.so）用静态符号绑定，没有 JNI_OnLoad，
 * 因此无法动态注册，符号名必须与 Java 侧完全对应，形如：
 *   Java_<包名下划线化>_<类名>_<方法名>
 * 当前 Java 侧声明的 native 方法（共 4 个）：
 *   Java_com_xiaosun_ocr_OCRService_initModels
 *   Java_com_xiaosun_ocr_OCRService_nativeProcessBitmap
 *   Java_com_xiaosun_ocr_OCRService_nativeSwitchTier
 *   Java_com_xiaosun_ocr_OCRService_nativeGetTier
 *
 * 注意：.so 里还多导出一个 Java_com_xiaosun_ocr_OCRService_nativeSetRapidDir
 * （RapidOCR 时代的死 API，Java 侧声明已删除）。**未调用的导出符号无害**，
 * 它只是"多余的导出符号"，不会查找、不影响库加载。
 * 核对方法：nm -D --defined-only <abi>/libnjocr.so | grep Java_com_xiaosun
 *
 * ⚠️ 改包名 / 类名 / 方法签名，都必须同步重编 native 库，
 *    否则一启动就 UnsatisfiedLinkError。
 *
 * 防冻结措施：
 *   1. 常驻前台服务（START_STICKY + 常驻通知）
 *   2. 持有 PARTIAL_WAKE_LOCK，请求处理期间保证 CPU 不睡
 *   3. stopWithTask=false，从最近任务划掉也不被杀
 *   4. 提供忽略电池优化申请入口（Doze 白名单）
 */
public class OCRService extends Service {

    private static final String TAG = "XiaoSunOCR";

    /** 界面通过 Intent 传入截屏授权结果的 action / extra key。 */
    public static final String ACTION_START_CAPTURE = "com.xiaosun.ocr.START_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "xsocr_service";
    /** 早期版本用过的渠道 ID，启动时清理掉，避免系统设置里残留重复项。 */
    private static final String LEGACY_CHANNEL_ID = "njocr_channel";
    /**
     * 通知 ID。★ 与 {@link #HTTP_PORT} 的 1666 只是**数值巧合**，
     * 二者语义无关（这里是通知标识，那里是监听端口），改一个不要顺手改另一个。
     */
    private static final int NOTIFICATION_ID = 1666;

    /** HTTP 监听端口（唯一来源，OcrHttpServer 与界面提示都引用它）。 */
    public static final int HTTP_PORT = 1666;

    /**
     * HTTP 服务是否已真正开始监听。
     *
     * 与 {@link #isRunning} 的区别：
     *   isRunning —— 服务对象已创建（onCreate 里就置 true，此时模型/HTTP 可能还没好）；
     *   httpReady —— HTTP 已能响应请求。插件的 ping 应该看这个而不是 isRunning。
     *
     * 【修复】原来 onCreate 把 root 探测、模型加载、启动 HTTP 串成一条线，
     * 最坏情况（root 探测最长 20s + 截屏命令探测 8s）要 20~30 秒 HTTP 才可用，
     * 而插件的 ping 预算只有 300/1500ms，必然被误判成"服务未就绪"。
     * 现在先把 HTTP 起起来，重活挪到后台线程。
     */
    public static volatile boolean httpReady = false;

    /** 供界面判断服务是否已在运行。 */
    public static volatile boolean isRunning = false;

    /**
     * 诊断：模型是否加载完成。
     *
     * 没有它的话，模型加载失败（例如 assets 里模型文件缺失）时接口依旧返回
     * status 200 + 空结果，调用方完全看不出哪里不对。
     * 有它之后 /last 会带上 "modelReady": false，一眼可定位。
     */
    public static volatile boolean modelReady = false;

    /** 诊断：模型加载失败的原因（成功时为空串）。 */
    public static volatile String modelError = "";

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


    /**
     * 截屏能力是否就绪的**缓存值**（供 BroadcastReceiver 等拿不到 Service 实例的地方读取）。
     *
     * 【命名说明】原来这里的字段名叫 isScreenCaptureReady，与同文件的
     * 实例方法 isScreenCaptureReady() 完全同名（字段与方法命名空间不同，能编译），
     * 极易误读成"同一个东西"。而实际上：
     *   字段 = 手工维护的快照，可能滞后；
     *   方法 = 实时探测 rootCapture / screenCapture 的真实状态。
     * 现改名为 screenCaptureReady，并由实例方法负责回写，保证字段不会落后。
     */
    public static volatile boolean screenCaptureReady = false;

    private OcrHttpServer httpServer;

    /** 屏幕截图管理器（MediaProjection 授权后可反复截屏）。 */
    private ScreenCaptureManager screenCapture;

    /** 基于 root 的截屏管理器：有 root 时用它，免去截屏授权弹窗。 */
    private RootScreenCaptureManager rootCapture;

    /**
     * ML Kit 文字识别引擎（**rapid 档位**的实现）。
     *
     * 【为什么 rapid 不再是 native 档位】见 {@link #TIER_RAPID} 处的说明：
     * rapid 已从 libnjocr.so 的 native 档位改为「由本字段承载的 ML Kit 引擎」，
     * 因此它**不再进 native**，{@link #nativeSwitchTier} 只处理 tiny / small。
     *
     * 【并发】ML Kit 的 TextRecognizer 非线程安全，同一实例不可并发 process()。
     * 本字段的 init / recognize / close 全部在 {@link #engineLock} 内调用。
     */
    private MlKitEngine mlkitEngine;

    /** 当前生效的截屏方式，供界面显示。 */
    public static volatile String captureMode = "none";

    /** CPU 唤醒锁：仅在处理 OCR 请求期间持有，避免长时间待机耗电。 */
    private PowerManager.WakeLock cpuLock;

    /** 正在处理的请求数，归零后释放唤醒锁。 */
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    /**
     * native 引擎互斥锁。
     *
     * 【修复⑧并发竞态】NanoHTTPD 是每请求一个线程，而 native 侧的
     * det/rec 会话是**进程内单份、非线程安全**的。原来只有 captureScreen()
     * 加了 synchronized，nativeProcessBitmap 和 nativeSwitchTier 都是裸调用，
     * 于是同时来两个 OCR 请求（例如 MacroDroid 连发两条宏），
     * 或"正在识别时外部切档位"，都会并发进 native —— 结果是崩溃、
     * 结果串台（返回别人的文本）或直接返回 null。
     *
     * 这里统一串行化"识别"与"切档位"这两种会碰 native 会话的操作。
     * 注意：锁只保护 native 调用本身，图片解码等前置步骤在锁外完成，
     * 不会因为一张大图解码而把其它请求堵住。
     */
    private final Object engineLock = new Object();

    static {
        // 加载 native 推理引擎。
        // 名称必须与 jniLibs 下的文件名 libnjocr.so 一致 ——
        // 改这里就必须同步重命名 .so，否则 UnsatisfiedLinkError。
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

    // 【已移除】nativeSetRapidDir(String)
    //
    // 它原本用于把 RapidOCR 的模型解包目录告诉 native。rapid 档自 v1.0 起改由
    // ML Kit 实现（见 {@link #TIER_RAPID}），该方法随之成为死 API，已连同
    // assets/rapid/ 一并清除。
    //
    // 【为什么删声明不影响 native】已用 `nm -D` 核实 libnjocr.so：
    //   · 未导出 JNI_OnLoad，也不做 RegisterNatives —— 是纯静态名字绑定；
    //   · .so 里仍导出 Java_com_xiaosun_ocr_OCRService_nativeSetRapidDir 符号，
    //     但那是"多余的导出符号"，无人查找、不影响库加载。
    // 未被调用的 native 方法声明不会产生链接错误，因此删掉它**无需重编 .so**。

    // ===================== 模型档位 =====================

    /** 轻量档：速度快，模型小（默认）。 */
    public static final int TIER_TINY = 0;
    /**
     * 高精度档（native）：字典更大，保留更多标点/符号。
     */
    public static final int TIER_SMALL = 1;

    /**
     * ML Kit 档（**已取代原 RapidOCR native 档**）。
     *
     * ========================================================================
     * 【本次改动：rapid 从 native 档位改为 ML Kit 引擎】
     * ========================================================================
     * 原来 rapid 是 libnjocr.so 里的第 3 个 native 档位（RapidOCR：
     * clipper 精确后处理 + AngleNet 方向纠正，assets/rapid/ 下带一份
     * cls.bin 方向分类模型）。
     *
     * 现在 rapid **不进 native**，改由 {@link MlKitEngine} 承担，
     * 即：rapid 档 = Google ML Kit 文字识别（捆绑版，英文 + 中文）。
     *
     * 【对外契约完全不变】这一点很关键，所以档位编号仍是 2、
     * 名字仍是 "rapid"、接口仍接受 "rapid"/"2"：
     *   · 已有的宏（Tasker/MacroDroid）配了 rapid 不会失效
     *   · MainActivity 的档位下拉、PluginEditActivity 的档位选项无需改
     *   · 返回 JSON 里的 "tier":"rapid" 保持原样
     * 只有"背后是谁在算"换了，调用方无感。
     *
     * 【为什么选它】（对比原 RapidOCR）
     *   · 中英混排效果好，方向/倾斜容忍度高
     *   · 模型与引擎随 APK 打包（bundled），**不依赖 Google Play 服务**、
     *     不联网下载（详见 MlKitEngine 类注释中的实证说明）
     *   · 与 PP-OCR 形成互补：PP-OCR 输出带矩形/多边形，ML Kit 输出行级包围盒
     *
     * 【代价】APK 体积增加约 18MB（捆绑模型 + 推理 .so）。
     *
     * 【assets/rapid/ 已删除】rapid 遗物已彻底清除（回收 3.45MB）：
     * 经核对，该目录下 det.bin/rec.bin/keys.txt 与顶层 tiny 的对应文件
     * **字节数完全相同**，说明 rapid 当年复用的就是 tiny 的 det/rec 模型，
     * 它独有的只有 cls.bin（方向分类）。既然 rapid 已不走 native，这些文件
     * 不会被任何代码读取，故连同 nativeSetRapidDir 一起删除。
     * （顶层 tiny 与 small/ 的模型不受影响，两档照常工作。）
     */
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
        // 必须显式指定 Locale.ROOT：默认 Locale 在土耳其语等环境下，
        // "I".toLowerCase() 会得到 "ı"（无点），导致 "TINY"/"SMALL" 匹配静默失效。
        switch (name.trim().toLowerCase(Locale.ROOT)) {
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
     *
     * 【修复】整段放进 engineLock：nativeSwitchTier 会重建 native 侧会话，
     * 若此时另一个线程正在 nativeProcessBitmap 里推理，会出现
     * "会话被销毁 → 崩溃 / 返回空" 以及"用错档位的模型"问题。
     */
    public boolean switchTier(int tier) {
        // 注意：三档都要放行（曾漏掉 rapid，导致它被直接拒掉、根本进不到 native）
        if (tier != TIER_TINY && tier != TIER_SMALL && tier != TIER_RAPID) return false;
        synchronized (engineLock) {
            return switchTierLocked(tier);
        }
    }

    /**
     * 切档位实体实现；调用前必须已持有 {@link #engineLock}。
     *
     * 【ML Kit 档的特殊处理】TIER_RAPID 现在由 ML Kit 承担，**不进 native**：
     * 若照样调 nativeSwitchTier(2)，native 侧要么不认识、要么又去加载
     * assets/rapid/（含 cls.bin），既浪费启动时间，也是本次要摆脱的旧路径。
     * 因此这里对 rapid 只做「记录 + 持久化」，native 保持原档位不动
     * （它当前的 tiny/small 模型继续待命，将来切回时无需重新加载）。
     */
    private boolean switchTierLocked(int tier) {
        if (tier == TIER_RAPID) {
            return switchToMlKitLocked();
        }
        // tiny / small：走 native 档位
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

    /**
     * 切到 ML Kit 档（TIER_RAPID），不触碰 native。
     *
     * 若 ML Kit 还没建好就顺手建一次 —— 这样"先切 rapid 再识别"和
     * "先识别再切 rapid"两种顺序都能用。
     */
    private boolean switchToMlKitLocked() {
        if (mlkitEngine == null) {
            mlkitEngine = new MlKitEngine();
        }
        boolean ok = mlkitEngine.init();
        String mlErr = mlkitEngine.getInitError();

        // 即使 ML Kit 初始化失败（ok==false），也**照样把档位记下来**：
        // 这样 /last 能如实汇报"当前是 rapid 且它坏了"，而不是悄悄退回 tiny
        // 让用户以为是自己配错了。识别时会返回明确的 503 错误。
        currentTier = TIER_RAPID;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putInt(KEY_TIER, TIER_RAPID).apply();

        if (ok) {
            // 降级到拉丁时会带回"中文不可用"的说明，必须一并暴露出来，
            // 否则用户识别中文拿到空结果却无从排查。
            if (mlErr != null && !mlErr.isEmpty()) {
                Log.w(TAG, "ML Kit 档已就绪（有降级告警）: " + mlErr);
            } else {
                Log.i(TAG, "档位切换成功: rapid（ML Kit，英文+中文）");
            }
        } else {
            Log.e(TAG, "档位已记为 rapid，但 ML Kit 不可用: " + mlErr);
        }
        return true;
    }
private static final String PREFS = "xsocr_prefs";
    private static final String KEY_TIER = "model_tier";
    /** 「启用 OCR 服务」开关。默认 true —— 老用户升级后行为不变。 */
    private static final String KEY_ENABLED = "ocr_enabled";
    /**
     * 是否把本应用从「最近任务」列表里隐藏。
     *
     * 只影响多任务界面里的那张卡片；服务、通知、手势一律照旧。
     * 默认 false —— 老用户升级后行为和以前完全一样。
     */
    private static final String KEY_HIDE_RECENTS = "hide_from_recents";

    // ===================== 总开关 =====================
    //
    // 【为什么要有这个开关】
    // 本应用有两套互不相干的能力：
    //   ① HTTP OCR 接口（常驻前台服务 + 通知 + 模型常驻内存）
    //   ② 边缘滑动触发（悬浮窗手柄 → 广播给 MacroDroid）
    // 只想用②的人（比如「滑一下就让宏干别的事」）不该被迫扛着①：
    // 一个前台通知、几百 MB 模型内存、以及开机自启。
    // 关掉之后②照常工作，只是手势触发时不再顺带预热 OCR。
    //
    // 【关掉时正在跑的服务怎么办】
    // 直接 stopService()，不给它「自己重启」的机会。
    // 服务是 START_STICKY，但 stopService 是明确的停止意图，
    // 系统不会把它再拉起来 —— 否则开关就成了摆设。
    //
    // 【默认值为什么是 true】
    // 这个开关是后加的。老用户升级后 SharedPreferences 里没有这个键，
    // 若默认 false 会让他们的接口一夜之间全挂 —— 必须默认 true。
    /** OCR 服务当前是否启用。 */
    public static boolean isEnabled(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    /**
     * 设置总开关。
     *
     * 只负责改值 + 停服务；「要不要重新拉起来」由调用方决定
     * （界面用 startService，开机自启用 startForegroundService），
     * 免得这里多一份启动逻辑、和各调用方各写各的。
     */
    public static void setEnabled(Context c, boolean on) {
        c.getApplicationContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply();
        if (!on) stop(c);
    }
    /** 主动停止服务（关闭开关时用）。 */
    public static void stop(Context c) {
        try {
            c.getApplicationContext().stopService(new Intent(c, OCRService.class));
        } catch (Throwable t) {
            Log.w(TAG, "停止 OCR 服务失败: " + t);
        }
    }

    /**
     * 本应用当前是否从「最近任务」里隐藏。
     *
     * 默认 false：这个开关是后加的，老用户 prefs 里没这个键，
     * 默认不隐藏，行为跟以前一致。
     */
    public static boolean isHiddenFromRecents(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_HIDE_RECENTS, false);
    }

    /** 只写设置值，不动界面 —— 具体应用交给 {@link #applyRecents(Context)}。 */
    public static void setHiddenFromRecents(Context c, boolean hidden) {
        c.getApplicationContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_HIDE_RECENTS, hidden).apply();
    }

    /**
     * 把「从最近任务隐藏」应用到本应用的 task 上。
     *
     * ════════════════════════════════════════════════════════════
     * 【为什么用 AppTask，而不是重建 task】
     * ════════════════════════════════════════════════════════════
     * 最初走的是「重建 task」：拿一个带
     * FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 的 Intent 把 Activity 重开一遍，
     * 指望新 task 记下这个标志。
     *
     * 实测（MIUI / Android 14）行不通：重建时系统会**复用已存在的那个
     * task**（task id 没变），而 task 的 baseIntent 在它首次创建时就定下了，
     * 后到的标志被直接丢掉。日志里那句「重建 task」确实打了，可 dumpsys
     * 一看 flags 还是 0x10000000，排除位根本不在。
     *
     * 真正对口的 API 是 {@link android.app.ActivityManager.AppTask}：
     * 从 getAppTasks() 拿到本应用的 task，直接
     * {@code setExcludeFromRecents(true/false)} —— 就地改，立即生效，
     * 既不用重建、也就没有「标志丢失」这一说。
     *
     * ════════════════════════════════════════════════════════════
     * 【为什么遍历全部 task】
     * ════════════════════════════════════════════════════════════
     * getAppTasks() 返回的是**本应用**的全部 task，可能不止一个
     * （比如从通知、快捷方式各拉起过一个）。隐藏与否是针对整个应用的
     * 观感，统一设成同一个值才不会有「藏了一个、冒出另一个」。
     *
     * 单个 task 设置失败不影响其余：某些 ROM 对非前台 task 可能拒绝，
     * 所以每个都单独 try，失败只记日志。
     */
    public static void applyRecents(Context ctx) {
        if (ctx == null) return;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        try {
            boolean hidden = isHiddenFromRecents(ctx);
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            java.util.List<android.app.ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks == null || tasks.isEmpty()) return;
            int okCount = 0;
            for (android.app.ActivityManager.AppTask t : tasks) {
                if (t == null) continue;
                try {
                    t.setExcludeFromRecents(hidden);
                    ++okCount;
                } catch (Throwable e) {
                    Log.w(TAG, "单个 task 设置失败: " + e);
                }
            }
            Log.i(TAG, "已" + (hidden ? "隐藏" : "恢复") + " " + okCount + "/" + tasks.size()
                    + " 个 task 在最近任务中的显示");
        } catch (Throwable t) {
            Log.w(TAG, "设置最近任务可见性失败: " + t);
        }
    }

    // ===================== 统一识别入口 =====================

    /**
     * 识别入口（唯一），按当前档位分流到 native 或 ML Kit。
     *
     * 【为什么要这个开关】OcrHttpServer 有两个识别调用点
     * （POST / 主流程 与 GET /ocr 便捷路由）。如果各自写一遍
     * "if (rapid) 走 ML Kit else 走 native"，日后新增档位时必然漏改一处。
     * 统一收到这里，分流逻辑只有一份。
     *
     * 【为什么要加 engineLock】ML Kit 的 TextRecognizer 非线程安全，
     * 同一实例不可并发 process()。而本方法可能在两条 HTTP 请求线程上
     * 同时进入，必须串行化 —— 与 native 识别共用同一把 engineLock，
     * 意味着"native 推理"与"ML Kit 推理"之间也互斥，虽然二者互不影响，
     * 但这样最省心（本来单机 OCR 也吃不满多核，串行不会成为瓶颈）。
     * 注意：nativeProcessBitmap 内部不再加锁，避免同锁重入两遍。
     *
     * @param bitmap 待识别位图（调用方负责 recycle 时机不受本方法影响）
     * @param type   1=纯文本；>=2 含坐标
     * @return 与 native 格式一致的 JSON 文本（含 status 字段）
     */
    public String processBitmap(Bitmap bitmap, int type) {
        if (bitmap == null) {
            return "{\"status\": 400, \"data\": \"Error: bitmap is null\"}";
        }
        synchronized (engineLock) {
            if (currentTier == TIER_RAPID) {
                // rapid = ML Kit 档
                if (mlkitEngine == null) {
                    // 兜底：正常情况下 switchToMlKitLocked() 已建好；
                    // 这里再保一层，防止 currentTier 被直接赋值（如恢复档位时）绕过初始化。
                    mlkitEngine = new MlKitEngine();
                    mlkitEngine.init();
                }
                return mlkitEngine.recognize(bitmap, type);
            }
            // tiny / small = native 档
            return nativeProcessBitmap(bitmap, type);
        }
    }

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
            // 先清掉旧会话的缓存标志，避免上面 stop() 之后仍显示"已就绪"
            screenCaptureReady = false;
            ScreenCaptureManager mgrCapture = new ScreenCaptureManager(this);
            mgrCapture.start(projection);
            screenCapture = mgrCapture;
            // 走实例方法回写：只有 mgrCapture.isReady() 为真才置 true
            refreshScreenCaptureFlag();
            Log.i(TAG, "截屏功能已启用, 分辨率=" + mgrCapture.screenWidth()
                    + "x" + mgrCapture.screenHeight());
        } catch (Throwable e) {
            Log.e(TAG, "启动截屏失败: " + e);
            screenCapture = null;
            screenCaptureReady = false;
        }
    }

    /** 是否至少有一种截屏方式可用（无障碍 / root / MediaProjection）。 */
    public boolean isScreenCaptureReady() {
        // ① 无障碍截图：勾选一次即永久可用，不需要 root、不弹授权框，排在最前
        if (OcrAccessibilityService.isReady()) {
            return true;
        }
        // ② root 截屏优先：无需授权
        if (rootCapture != null && rootCapture.isReady()) {
            return true;
        }
        // ③ 退回 MediaProjection
        return screenCapture != null && screenCapture.isReady();
    }

    /**
     * 无障碍服务连接/断开时由 {@link OcrAccessibilityService} 回调。
     *
     * 【为什么需要它】无障碍服务由系统绑定，连接与断开都不经过本服务，
     * 因此 screenCaptureReady 这个静态标志不会自己更新 ——
     * 用户刚在系统设置里勾选完、切回 App，插件却仍以为「截屏没就绪」。
     *
     * 只处理「连上」这一个方向：断开时不能简单置 false，
     * 因为 root / MediaProjection 可能仍然可用，真实状态应由
     * isScreenCaptureReady() 判定（界面在 onResume 会重新刷新文案）。
     */
    static void onAccessibilityStateChanged(boolean connected) {
        if (connected) {
            screenCaptureReady = true;
        }
    }

    /**
     * 把「截屏是否就绪」的实时状态回写进静态缓存字段 {@link #screenCaptureReady}。
     *
     * 【为什么必须统一走这里】这个静态字段是给 BroadcastReceiver、其它 Activity
     * 这类拿不到 Service 实例的地方读的。如果各处自己手工写 true/false，
     * 一旦 root 探测失败、或 MediaProjection 会话中途失效，
     * 字段就会永远停在 true —— 插件便一直以为"截屏可用"，
     * 直到真正调 captureScreen() 才报错，排查起来很费劲。
     * 统一由 isScreenCaptureReady() 这条唯一判据回写，字段就不会落后于真实状态。
     */
    private void refreshScreenCaptureFlag() {
        screenCaptureReady = isScreenCaptureReady();
    }

    /**
     * 截取当前屏幕。
     *
     * 顺序：无障碍截图 → root 截屏 → MediaProjection。
     * 前两者都不需要「开启截屏授权」、也不会弹录屏框；
     * 无障碍还额外不需要 root，所以放在最前面。
     * 任一条路失败都继续往下试，不会因为某条路临时失效就整体不可用。
     */
    public synchronized Bitmap captureScreen() throws Exception {
        // ① 无障碍截图
        OcrAccessibilityService a11y = OcrAccessibilityService.get();
        if (a11y != null && a11y.canTakeScreenshot()) {
            try {
                Bitmap bmp = a11y.capture();
                if (bmp != null) {
                    captureMode = "accessibility";
                    return bmp;
                }
            } catch (Throwable t) {
                Log.w(TAG, "无障碍截图失败，尝试退回其它方式: " + t);
            }
        }
        // ② root 截屏
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
                "无可用截屏方式：无障碍截图未开启、没有 root，"
                        + "也还没在 App 内点「开启截屏授权」");
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
                        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XiaoSunOCR:ocr");
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
        // 【总开关拦截·第一道】必须在**这一行的后面之前**做完：
        // onCreate 一旦继续往下走，就会 startForegroundCompat()（弹通知）、
        // startHttpServer()（监听端口）、以及后台线程加载模型 ——
        // 那时即便 onStartCommand 里再 stopSelf，通知和几百 MB 模型
        // 也已经白白付出了一次代价。
        //
        // 所以开关关闭时这里**原地返回**：不弹通知、不开端口、不加载模型。
        // onStartCommand 里还有第二道拦截负责收尾（stopSelf + 不粘性）。
        if (!isEnabled(this)) {
            Log.i(TAG, "OCR 服务已关闭（用户设置），onCreate 不做任何初始化");
            stopSelf();
            return;
        }
        Log.i(TAG, "Service Creating...");
        isRunning = true;
        startForegroundCompat();

        // 【修复⑥：启动顺序】先把 HTTP 服务起起来，再做重活。
        //
        // 原来的顺序是「root 探测 → 模型加载 → 启动 HTTP」全串行，
        // 而 root 探测内部有 `su -c id`（最长 20s）和 `command -v screencap`（8s），
        // 最坏情况 20~30 秒内 HTTP 端口根本没人监听：
        //   · 插件 ping 预算只有 300ms / 1500ms → 必然误判"服务未就绪"
        //   · 界面上的「正在加载模型…」也会一直卡着
        // 现在 HTTP 立刻可用（此时识别请求会明确返回"模型加载中"，
        // 而不是连接被拒），重活全部挪到 xsocr-init 线程。
        startHttpServer();

        new Thread(this::initHeavyStuff, "xsocr-init").start();
    }

    /**
     * 耗时初始化：root 截屏探测、模型加载、恢复上次档位。
     *
     * 在独立线程执行，不阻塞 HTTP 服务上线。
     */
    private void initHeavyStuff() {
        // ① 优先探测 root 截屏：有 root 就完全不需要「截屏授权」
        try {
            RootScreenCaptureManager rc = new RootScreenCaptureManager(this);
            if (rc.start()) {
                rootCapture = rc;
                captureMode = "root";
                // 走实例方法回写，避免字段与真实状态不一致
                refreshScreenCaptureFlag();
                Log.i(TAG, "已启用 root 截屏，无需截屏授权");
            }
        } catch (Throwable t) {
            Log.w(TAG, "root 截屏探测失败: " + t);
        }

        // ② 加载模型
        try {
            Log.i(TAG, "开始初始化模型...");
            // 【修复⑧】initModels 也会改写 native 侧会话，必须与并发识别互斥
            boolean ok;
            synchronized (engineLock) {
                ok = initModels(getAssets());
            }
            if (ok) {
                modelReady = true;
                modelError = "";
                Log.i(TAG, "模型初始化成功！");
                // ③ 恢复上次选定的档位（默认 tiny，与原行为一致）
                int saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getInt(KEY_TIER, TIER_TINY);
                if (saved == TIER_RAPID) {
                    // === 本次改动：rapid 现在由 ML Kit 承担，不再初始化 native 的 rapid ===
                    //
                    // 原代码会把 assets/rapid/ 解包目录告诉 native
                    // （nativeSetRapidDir(getCacheDir()/rapid)），再 nativeSwitchTier(2)
                    // 让 native 去加载 RapidOCR 模型（含 cls.bin 方向分类）。
                    // 现在 rapid 整档改由 ML Kit 实现，native 侧再也不需要这些文件，
                    // 因此这里只把 ML Kit 引擎准备起来。
                    //
                    // 效果：启动时少加载一份模型（含一次解包 + 一次 native 会话重建），
                    // 冷启动更快；而"上次选了 rapid"的用户重启后仍自动回到 rapid。
                    switchTier(TIER_RAPID);
                    Log.i(TAG, "已恢复档位: rapid（ML Kit，英文+中文）");
                } else if (saved != TIER_TINY) {
                    if (switchTier(saved)) {
                        Log.i(TAG, "已恢复档位: " + tierName(saved));
                    } else {
                        Log.w(TAG, "恢复档位失败，退回 tiny");
                        currentTier = TIER_TINY;
                    }
                }
            } else {
                // 【修复】原来的日志只说"失败"，接口侧却依旧是 200 + 空结果，
                // 外部调用方完全无从判断。现在把原因记到 modelError 并通过 /last 暴露。
                modelReady = false;
                modelError = "initModels 返回 false：assets 中模型/字典文件可能缺失";
                Log.e(TAG, "模型初始化失败，请检查 assets 文件！");
            }
        } catch (UnsatisfiedLinkError e) {
            modelReady = false;
            modelError = "JNI 链接失败: " + e.getMessage();
            Log.e(TAG, "JNI 链接失败: " + e.getMessage());
        } catch (Exception e) {
            modelReady = false;
            modelError = "模型初始化异常: " + e.getMessage();
            Log.e(TAG, "模型初始化异常: " + e.getMessage());
        }
    }

    /**
     * 本应用私有权限名，需与 AndroidManifest 中声明的
     * {@code com.xiaosun.ocr.permission.CONTROL_SERVICE} 完全一致。
     */
    private static final String PERM_CONTROL = "com.xiaosun.ocr.permission.CONTROL_SERVICE";

    /**
     * 判断当前的 startForegroundService/startService 调用者是否被允许。
     *
     * 【修复⑭】本服务必须是 exported=true 的，否则自动化脚本无法用
     * `am start-foreground-service` 拉起它 —— 而"能被外部拉起"正是核心用法。
     * 但导出的代价是：**任何** App 都能拉起/重复拉起本服务，
     * 白占前台通知、干扰用户。
     *
     * 这里做最小必要的校验：
     *   · 自身进程（界面调用、授权结果回传）—— 放行
     *   · 系统 / shell / root（uid 0 或 1000 / 2000）—— 放行，
     *     自动化脚本正是通过它们发起的
     *   · 持有 CONTROL_SERVICE（signature 级）的同签名应用 —— 放行
     *   · 其余第三方 App —— 拒绝
     *
     * 注意用 Binder.getCallingUid() 而不是 Process.myUid()：
     * 我们必须判断"是谁在调我"。
     */
    private boolean isCallerAllowed() {
        try {
            int uid = android.os.Binder.getCallingUid();
            if (uid == android.os.Process.myUid()) {
                return true;
            }
            // root(0) / system(1000) / shell(2000)：am 命令的常见发起者
            if (uid == 0 || uid == android.os.Process.SYSTEM_UID
                    || uid == 2000) {
                return true;
            }
            return checkCallingPermission(PERM_CONTROL)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            // 校验本身出问题时宁可放行，避免把正常调用方挡在门外
            Log.w(TAG, "调用者校验异常，按放行处理: " + t);
            return true;
        }
    }

    /**
     * 返回 START_STICKY：进程被系统回收后会自动重建服务（对无人值守场景很重要）。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 【修复⑭】调用者校验：挡住第三方 App 的随意拉起/骚扰。
        // 必须在 startForegroundCompat() 之前判断，否则会先弹出一个通知。
        if (!isCallerAllowed()) {
            Log.w(TAG, "拒绝未授权调用者的启动请求（uid 校验未通过）");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        // 【总开关拦截】这是唯一一个所有启动路径都必经的关口：
        //   界面、开机自启、插件（宏里的 OCR 动作）、手势预热 ——
        //   全都最终落到 onStartCommand。
        // 在这里统一拦一次，比在每个调用方各写一遍可靠得多
        // （调用方漏一个，开关就形同虚设）。
        //
        // 必须放在 startForegroundCompat() 之前：否则会先闪一个通知再退出。
        // 也必须在 isCallerAllowed() 之后：避免给未授权调用者返回
        // 「配置状态」这种信息。
        if (!isEnabled(this)) {
            Log.i(TAG, "OCR 服务已关闭（用户设置），拒绝启动请求");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

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
        httpReady = false;
        screenCaptureReady = false;
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
        // 释放 ML Kit 识别器：它内部持有 native 侧资源（.so 里的 pipeline），
        // 不关会随服务反复重启而累积。同样放进 engineLock，避免与正在进行的识别打架。
        try {
            synchronized (engineLock) {
                if (mlkitEngine != null) {
                    mlkitEngine.close();
                    mlkitEngine = null;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "关闭 ML Kit 引擎失败: " + t);
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
            // 【修复⑥配套】HTTP 真正开始监听后才置位。
            // 插件侧的 ping 判断应该看它（而不是 isRunning）：
            // isRunning 在 onCreate 一进来就是 true，但那时端口还没人监听。
            httpReady = true;
            Log.i(TAG, "HTTP 服务已启动: 127.0.0.1:" + HTTP_PORT);
        } catch (Exception e) {
            Log.e(TAG, "HTTP 启动失败: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                // 清理早期版本遗留的通知渠道，避免在系统设置里出现重复项
                try {
                    if (nm.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
                        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID);
                    }
                } catch (Throwable ignored) {
                }

                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(
                            CHANNEL_ID, "小孙OCR 服务", NotificationManager.IMPORTANCE_LOW);
                    channel.setShowBadge(false);
                    nm.createNotificationChannel(channel);
                }
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
                // 端口取自 HTTP_PORT（唯一来源），避免"通知写 A、实际监听 B"
                .setContentText("监听地址: 127.0.0.1:" + HTTP_PORT)
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