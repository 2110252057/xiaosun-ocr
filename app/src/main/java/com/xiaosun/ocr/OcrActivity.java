package com.xiaosun.ocr;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import static com.xiaosun.ocr.Palette.*;
import static com.xiaosun.ocr.Ui.*;

/**
 * 文字识别页（从首页进入的二级页面）。
 *
 * 打开自动拉起本地 OCR 服务（端口见 {@link OCRService#HTTP_PORT}）。
 * 页面内容：服务总开关 / 截屏方式 / 图片预览 / 识别参数 / 识别结果
 *
 * 调试页：选中图片直接在预览框显示，识别后把检测框画在预览图上，
 * 结果区按所选格式显示，三种格式都只显示「识别到的内容」，不显示
 * status/time/tier 这类包装字段（详见 {@code formatResult}）。
 */
public class OcrActivity extends AppCompatActivity {

    // 版本号不再硬编码：直接取 app/build.gradle 中 defaultConfig 声明的 versionName。
    // 从此版本号只有一个来源，不可能再出现多处不一致。
    private static final String APP_VERSION = "v" + BuildConfig.VERSION_NAME;
    // 【修复⑪】本机接口地址不再硬编码端口：直接复用 Http.URL_BASE，
    // 而 Http.URL_BASE 又由 OCRService.HTTP_PORT 拼出。
    // 于是"服务实际监听端口 / 通知栏文案 / 界面文档 / 客户端请求"四处
    // 全部来自同一个常量，改端口只改一处。
    private static final String LOCAL_URL = Http.URL_BASE;
    /**
     * 无障碍服务在系统「无障碍」列表里的显示名。
     *
     * 必须与 strings.xml 的 a11y_service_name 一字不差 ——
     * 引导用户去勾选时，文案里出现的就是这个名字，对不上他就找不到。
     */
    private static final String A11Y_LABEL = "Grace 截图";
    private static final int REQ_PICK_IMAGE = 1001;
    private static final int REQ_SCREEN_CAPTURE = 1002;
    /** 全屏框选页回传请求码。 */
    private static final int REQ_REGION_SELECT = 1003;

    /**
     * 预览框高度。
     *
     * 220dp 是「能看清缩略图、又不把下面的参数卡挤出屏幕」的折中值。
     * 框选区域不在这个框里做（那样连一条窄带都算不上），
     * 而是打开全屏的 {@link RegionSelectActivity}。
     */
    private static final int PREVIEW_BOX_H_DP = 220;

    /** 页面内容容器：本页所有卡片都加进它。 */
    private LinearLayout contentContainer;

    /** 服务状态点 / 状态文字。 */
    private TextView tvStatusDot;
    private TextView tvStatusText;
    /** 状态卡副标题（接口地址）。关闭时它不该继续显示一个没人监听的地址。 */
    private TextView tvStatusSub;
    /** 保活提示行：未加入电池优化白名单时才显示。 */
    private TextView tvBatteryText;
    /** 服务总开关。留着引用，是为了「自动启用」时能把开关拨到开启态。 */
    private android.widget.Switch mainSwitch;
    private boolean serviceStarting = false;

    private ImageView previewImage;
    private TextView previewPlaceholder;
    private TextView tvPreviewInfo;
    /** 区域说明行：显示当前区域是整屏还是某个固定区域，以及「改区域 / 清除」。 */
    private TextView tvRegionInfo;
    private Button btnRegionClear;
    private Bitmap previewBitmap;
    private String currentPath = "";
    private TextView tvResult;
    private Spinner formatSpinner;
    private EditText etPath;

    /** 模型档位选择器与说明文字。 */
    private Spinner tierSpinner;
    private TextView tierInfo;
    private boolean tierSwitching = false;

    /** 档位显示名（索引与 OCRService.TIER_* 对应）。 */
    private static final String[] TIER_LABELS = {
            "tiny · 轻量（快）",
            "small · 高精度",
            "rapid · ML Kit（最快）",
    };

    /** 屏幕截图授权是否已就绪。 */
    private boolean captureReady = false;
    private TextView capInfo;

    /**
     * 停在本页时的低频刷新。
     *
     * 与首页同理：模型在后台加载，「正在加载模型… → 已就绪」的跳变
     * 不发生在 onResume 期间，只刷一次的话卡片会一直停在「加载中」。
     * onPause 里摘掉，避免退到后台还在跑。
     */
    private final android.os.Handler ticker =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            updateCaptureInfo();
            ticker.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 装日志（幂等）。最先装 —— 后面任何一步崩了都能留下堆栈。
        AppLog.install(this);

        // 把「从最近任务隐藏」对齐到设置值。
        // AppTask.setExcludeFromRecents 是就地生效的，不会重建界面。
        OCRService.applyRecents(this);

        requestPermissionsIfNeeded();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        root.addView(buildTopBar());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, dp(this, 4), 0, 0);
        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 32));
        scrollView.addView(contentContainer);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        buildOcrSettingsPage(contentContainer);
        ensureServiceRunning();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台刷新截屏方式（root 探测在服务里异步完成）
        refreshCaptureMode();
        // 回前台时对齐一次「从最近任务隐藏」：可能是别的入口改了设置，
        // 也可能系统重建过 task，重设一遍最稳（值相同时是空操作）。
        OCRService.applyRecents(this);
        /*
     * 保活状态也可能在这次离开期间变了：用户可能刚去系统设置里把本应用
     * 加进电池优化白名单，也可能在首页「⋮」菜单里切了「从最近任务隐藏」。
     * ensureServiceRunning() 在本页早已运行时是直接 return 的，不会
     * 顺手刷新；所以这里必须显式刷一次，否则那条「⚠ 未加入白名单」的
     * 警示行会一直挂在已加白的机器上。（refreshStatus 里全是 null 检查，
     * 页面还没建好时调也安全，重复调是幂等的。）
     */
        refreshStatus();

        // 区域可能刚从框选页回来变了（也覆盖「被外部工具改坏」的情况）。
        // 必须在这里刷而不是只在保存后刷：保存后那次刷新发生在页面还
        // 不可见的时候，用户看不见；onResume 才是"重新可见"的唯一稳定时机。
        updateRegionInfo();

        // 停留期间低频刷新：模型加载完成的跳变不会正好落在 onResume 上。
        ticker.removeCallbacks(tick);
        ticker.postDelayed(tick, 800);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面不可见就没必要再刷，摘掉避免后台空转。
        ticker.removeCallbacks(tick);
    }

    /**
     * 等服务的截屏能力就绪后刷新界面文案。
     *
     * 不能用固定延时：服务的 root 探测里有 `su -c id`（首次会弹授权框，
     * 超时上限 20s）和一次试拍，慢设备上远超 1 秒。
     * 固定 1200ms 会导致界面显示"未授权"，而服务里其实已经可用，误导用户。
     */
    private void refreshCaptureMode() {
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final int[] tries = {0};
        final int maxTries = 60;      // 60 × 250ms = 最多等 15s
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                // 无障碍是进程内实时状态，不必等服务的静态标志同步
                boolean ready = OCRService.screenCaptureReady
                        || OcrAccessibilityService.isReady();
                captureReady = ready;
                updateCaptureInfo();
                // 已就绪，或已重试到上限（避免无限轮询）就停下
                if (!ready && ++tries[0] < maxTries) {
                    h.postDelayed(this, 250);
                }
            }
        };
        h.postDelayed(poll, 250);
    }

    /** 主动检测 root 截屏（服务已在启动时自动探测，这里给一个手动入口）。 */
    private void probeRootCapture() {
        toast("正在检测 root 截屏…");
        new Thread(() -> {
            boolean rootOk = RootScreenCaptureManager.isRootAvailable();
            boolean using = OCRService.screenCaptureReady && "root".equals(OCRService.captureMode);
            runOnUiThread(() -> {
                captureReady = OCRService.screenCaptureReady;
                updateCaptureInfo();
                if (using) {
                    toast("root 截屏可用，已免去截屏授权");
                } else if (rootOk) {
                    toast("检测到 root，但截屏初始化失败，请查看日志");
                } else {
                    toast("未检测到 root，请改用「开启截屏授权」");
                }
            });
        }, "xsocr-root-probe").start();
    }

    // ============================================================
    //  顶部栏
    // ============================================================

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        /*
         * 与首页统一的浅灰顶栏（原来是品牌蓝竖向渐变 + 白字）。
         * 蓝色渐变已去掉，brandGradient() 一并删除 —— 没有第二个用处。
         * 右上角「⋮」也已移除，全应用级开关只留在首页。
         */
        bar.setBackgroundColor(C_BG);
        bar.setPadding(dp(this, 6), dp(this, 18), dp(this, 16), dp(this, 18));

        // 二级页：左上角返回箭头回到首页（功能列表）
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(C_TEXT);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 44)));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("文字识别");
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(C_TEXT);
        title.setLetterSpacing(0.02f);
        column.addView(title);

        TextView sub = new TextView(this);
        sub.setText("PaddleOCR v6 · NCNN 本地识别  " + APP_VERSION);
        sub.setTextSize(11.5f);
        sub.setTextColor(C_SUB);
        column.addView(sub);
        bar.addView(column, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return bar;
    }

    // ============================================================
    //  服务状态
    // ============================================================

    private void ensureServiceRunning() {
        // 开关关了就不主动拉起 —— 这正是「只用滑动手势」的人要的：
        // 打开 App 不再顺带把他不用的 OCR 服务和模型载进内存。
        if (!OCRService.isEnabled(this)) {
            return;
        }
        if (OCRService.isRunning || serviceStarting) {
            return;
        }
        try {
            Intent intent = new Intent(this, OCRService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            serviceStarting = true;
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                serviceStarting = false;
                refreshStatus();
            }, 4000);
        } catch (Exception e) {
            toast("服务启动失败: " + e.getMessage());
        }
        refreshStatus();
    }

    /**
     * 服务没开时，就地把它打开而不是把用户挡回去。
     *
     * 【为什么要改这件事】
     * 服务默认关闭之后，用户第一次点「开始识别」必然是「没开」的状态。
     * 如果他点一下按钮只收到一句「请先去打开开关」，等于每一步都要他
     * 自己先想清楚顺序 —— 而他此刻的意图非常明确：我要识别这张图。
     * 所以这里直接代为开启，并把值落盘（setEnabled 会写 Prefs），
     * 之后每次启动就都按「已启用」走，只有他主动关掉才回到关闭态。
     *
     * @return 恒为 true；留着返回值是为了让调用处的 if 读起来是个守卫。
     */
    private boolean warnIfOcrDisabled() {
        if (OCRService.isEnabled(this)) {
            return true;
        }
        OCRService.setEnabled(this, true);
        serviceStarting = false;
        ensureServiceRunning();
        toast("已自动启用 OCR 服务");
        // 只把开关拨到开启态，不重建整页 ——
        // 重建会连带清掉用户刚输入的图片路径和已选的输出格式，
        // 那比「开关看着没动」更让人困惑。
        if (mainSwitch != null) {
            mainSwitch.setChecked(true);
        }
        refreshStatus();
        return true;
    }

    private void refreshStatus() {
        boolean enabled = OCRService.isEnabled(this);
        boolean ready = OCRService.isRunning;
        if (tvStatusDot != null) {
            // 只有「开着且真的跑起来了」才是绿点；
            // 关闭状态和一个正在加载的黄点视觉上就不该混淆。
            tvStatusDot.setBackgroundResource(
                    enabled && ready ? R.drawable.dot_ok : R.drawable.dot_warn);
        }
        if (tvStatusText != null) {
            if (!enabled) {
                tvStatusText.setText("服务已关闭");
                tvStatusText.setTextColor(C_SUB);
            } else {
                tvStatusText.setText(ready ? "服务已就绪" : "正在加载模型…");
                tvStatusText.setTextColor(ready ? C_TEXT : C_SUB);
            }
        }
        if (tvStatusSub != null) {
            // 关闭时不能继续显示接口地址：那个端口已经没人监听，
            // 用户照着它去调只会拿到「连接被拒」，还以为是服务坏了。
            tvStatusSub.setText(enabled ? LOCAL_URL : "接口未监听");
        }
        if (tvBatteryText != null) {
            // 只在「没进白名单」时才露出来。
            // 进了白名单就没什么好说的了，留着只是占地方；
            // 而没进白名单时 Android 会随时冻结后台进程，表现为
            // 「服务莫名停止」—— 这一条必须让用户看见。
            boolean ok = isIgnoringBatteryOptimizations();
            tvBatteryText.setText("⚠ 未加入电池优化白名单，后台可能被冻结。点这里去设置。");
            tvBatteryText.setTextColor(C_WARN);
            tvBatteryText.setVisibility(ok ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 合并后的「OCR 服务」卡片：状态点 + 标题 + 接口地址 + 总开关。
     *
     * ════════════════════════════════════════════════════════════
     * 【为什么把状态卡和开关卡并成一张】
     * ════════════════════════════════════════════════════════════
     * 它们回答的是同一个问题 ——「服务开着吗、能用吗」。拆成两张卡时，
     * 用户要先读「服务已就绪」再往下找开关，两个信息在视觉上还被一条
     * 分割线隔开，反倒像是两件不相干的事。并成一张后：左边状态点告诉
     * 你现在行不行，右边开关就是让它行/不行的那个东西。
     *
     * ════════════════════════════════════════════════════════════
     * 【关掉之后影响什么】
     * ════════════════════════════════════════════════════════════
     * 只影响 HTTP 接口这条链路：接口不再监听、模型不再常驻、开机不再自启。
     * 边缘滑动手势是另一个服务，不受影响 —— 这正是本开关要服务的人群：
     * 只想用手势触发宏、不想养着一个 OCR 服务的人。
     */
    private View buildOcrServiceCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(borderedBg(this, C_SURFACE, C_DIVIDER, 14));
        card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        card.setElevation(dp(this, 1));

        // ---- 左侧：状态点 ----
        TextView dot = new TextView(this);
        dot.setBackgroundResource(R.drawable.dot_ok);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(this, 9), dp(this, 9));
        dotLp.setMarginEnd(dp(this, 12));
        card.addView(dot, dotLp);
        tvStatusDot = dot;

        // ---- 中间：标题 + 接口地址 ----
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText("OCR 服务");
        t.setTextSize(14.5f);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(C_TEXT);
        col.addView(t);
        tvStatusText = t;

        TextView sub = new TextView(this);
        sub.setTextSize(11.5f);
        sub.setTextColor(C_SUB);
        sub.setLineSpacing(dp(this, 3), 1f);
        // 副标题＝接口地址；关掉开关后 refreshStatus() 会把它改成「接口未监听」
        sub.setText(LOCAL_URL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(this, 4);
        col.addView(sub, sl);
        tvStatusSub = sub;

        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ---- 右侧：总开关 ----
        mainSwitch = new android.widget.Switch(this);
        mainSwitch.setChecked(OCRService.isEnabled(this));
        mainSwitch.setOnCheckedChangeListener((b, checked) -> {
            OCRService.setEnabled(this, checked);
            if (checked) {
                // 【为什么要清掉 serviceStarting】
                // ensureServiceRunning() 见到 serviceStarting=true 会直接返回，
                // 那个标志是上一次启动的 4 秒保护窗口、只在 postDelayed 里复位。
                // 若用户正好在这个窗口内「先关后开」，这次打开就会被短路掉，
                // 而 4 秒后没有任何代码会替他重试 —— 界面会永远停在
                // 「正在加载模型…」，看着就像开关坏了。
                // 这里清掉是安全的：重复 startForegroundService 本身幂等。
                serviceStarting = false;
                // 开启：立刻拉起来，不用等下次进 App。
                ensureServiceRunning();
            }
            // 关闭时 setEnabled 已经 stopService 了，这里不用再管。
            // 稍等一下再刷新：服务的 isRunning 是在 onDestroy 里翻的，
            // 立刻刷新会短暂显示成「正在加载模型…」，看着像没关掉。
            new android.os.Handler(getMainLooper()).postDelayed(this::refreshStatus, 600);
            toast(checked ? "已启用 OCR 服务" : "已关闭 OCR 服务");
        });
        card.addView(mainSwitch);

        return card;
    }

    /**
     * 已在白名单就不重复申请。
     *
     * 系统对已在白名单的应用会弹一个「无需更改」的框，很困惑，
     * 所以这里先自己判一次。
     */
    private void openBatteryWhitelist() {
        if (isIgnoringBatteryOptimizations()) {
            toast("已在电池优化白名单里");
            return;
        }
        requestIgnoreBatteryOptimizations();
        // 从系统页返回后 onResume 会再刷一次，这里先不管
    }

    // ============================================================
    //  文字识别页内容
    // ============================================================

    /**
     * 拼出整页内容：服务卡 / 截屏卡 / 预览 / 路径 / 格式 / 档位 / 结果。
     *
     * 顺序即使用频率：先看服务开着没、能不能截屏；再看预览与参数；
     * 最后是结果 —— 结果通常在点完「开始识别」之后才有，放最后不碍事。
     */

    private void buildOcrSettingsPage(ViewGroup parent) {
        // ---------- 服务状态 + 总开关（合并成一张） ----------
        parent.addView(buildOcrServiceCard(), cardLp());

        // 「后台保活」与「从最近任务隐藏」原先各是一张卡，现在都收进
        // 顶栏「⋮」菜单了 —— 这两项都是配一次就不再动的设置，放在页面里
        // 反而把「截屏识别」「识别结果」这些常用内容挤到了下面。
        //
        // 但保活状态必须还能一眼看到：没进白名单的机器上 Android 会随时
        // 冻结后台进程，表现是「服务莫名停止」。这里只留一行提示，
        // 不进白名单时才显示，点一下就能去设置。
        tvBatteryText = new TextView(this);
        tvBatteryText.setTextSize(11.5f);
        tvBatteryText.setTextColor(C_WARN);
        tvBatteryText.setLineSpacing(0f, 1.35f);
        tvBatteryText.setPadding(dp(this, 4), dp(this, 2), dp(this, 4), dp(this, 2));
        tvBatteryText.setOnClickListener(v -> openBatteryWhitelist());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 负的 topMargin：卡片下方已经有 cardLp() 的 12dp 间距，这里贴回去一点，
        // 让它看起来像紧跟着服务卡的附带说明，而不是另起一段。
        blp.topMargin = -dp(this, 4);
        parent.addView(tvBatteryText, blp);

        // ---------- 截屏识别 ----------
        addSectionTitle(parent, "屏幕截屏识别");

        LinearLayout capCard = new LinearLayout(this);
        capCard.setOrientation(LinearLayout.VERTICAL);
        capCard.setBackground(borderedBg(this, C_SURFACE, C_DIVIDER, 14));
        capCard.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        capCard.setElevation(dp(this, 1));

        TextView capTitle = new TextView(this);
        capTitle.setText("截屏识别");
        capTitle.setTextSize(14);
        capTitle.setTypeface(null, Typeface.BOLD);
        capTitle.setTextColor(C_TEXT);
        capCard.addView(capTitle);

        capInfo = new TextView(this);
        capInfo.setTextSize(11.5f);
        capInfo.setPadding(0, dp(this, 4), 0, 0);
        capCard.addView(capInfo);

        LinearLayout capRow = new LinearLayout(this);
        capRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams capRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capRowLp.topMargin = dp(this, 10);
        capRow.setLayoutParams(capRowLp);
        capRow.addView(secondaryButton("检测 root 截屏", v -> probeRootCapture()), weightLp());
        capRow.addView(secondaryButton("截屏并识别", v -> captureAndOcr()), weightLp());
        capCard.addView(capRow);
        // ---------- 区域识别 ----------
        // 区域是「配一次、长期用」的东西：某个固定位置（比如某个 App 的某个面板）
        // 一旦框定，之后只需点「识别区域」，不必每次重画。
        // 所以这里的按钮是「设区域 / 识别区域 / 清除」三个动作，
        // 而不是一次性的「框选后立刻识别」。
        LinearLayout regionRow = new LinearLayout(this);
        regionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams regionRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        regionRowLp.topMargin = dp(this, 4);
        regionRow.setLayoutParams(regionRowLp);
        regionRow.addView(secondaryButton("设区域…", v -> pickRegionFullScreen()), weightLp());
        regionRow.addView(secondaryButton("识别区域", v -> ocrSavedRegion()), weightLp());
        btnRegionClear = secondaryButton("清除区域", v -> clearSavedRegion());
        regionRow.addView(btnRegionClear, weightLp());
        capCard.addView(regionRow);
        tvRegionInfo = new TextView(this);
        tvRegionInfo.setTextSize(11.5f);
        tvRegionInfo.setTextColor(C_SUB);
        tvRegionInfo.setLineSpacing(0f, 1.3f);
        tvRegionInfo.setPadding(0, dp(this, 8), 0, 0);
        capCard.addView(tvRegionInfo);

        LinearLayout capRow2 = new LinearLayout(this);
        capRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams capRow2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capRow2Lp.topMargin = dp(this, 4);
        capRow2.setLayoutParams(capRow2Lp);
        // 三种截屏方式并列：无障碍（最省事，免 root 免授权）／root（自动）／
        // MediaProjection（手动授权一次）。
        capRow2.addView(secondaryButton("无障碍截图", v -> openAccessibilitySettings()),
                weightLp());
        capRow2.addView(secondaryButton("开启截屏授权", v -> requestScreenCapture()),
                weightLp());
        capRow2.addView(secondaryButton("悬浮窗权限", v -> requestOverlayPermission()),
                weightLp());
        capCard.addView(capRow2);

        TextView capNote = new TextView(this);
        capNote.setText("① 无障碍（推荐）：勾选「" + A11Y_LABEL + "」，免 root、不弹框。\n"
                + "② root 截屏：有 root 时自动启用。\n"
                + "③ 截屏授权：无 root 时的兜底，每次重启需手点一次。\n"
                + "任一可用即可，不必都开。");
        capNote.setTextSize(11);
        capNote.setTextColor(C_HINT);
        capNote.setLineSpacing(0f, 1.3f);
        capNote.setPadding(0, dp(this, 8), 0, 0);
        capCard.addView(capNote);

        parent.addView(capCard, cardLp());
        updateCaptureInfo();

        // ---------- 图片预览框 ----------
        addSectionTitle(parent, "图片预览");

        LinearLayout previewCard = new LinearLayout(this);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setBackground(borderedBg(this, C_SURFACE, C_DIVIDER, 14));
        previewCard.setElevation(dp(this, 1));
        previewCard.setGravity(Gravity.CENTER);

        // 预览区（固定高度，居中显示）
        android.widget.FrameLayout previewBox = new android.widget.FrameLayout(this);
        previewBox.setBackgroundColor(0xFFEFF1F6);
        previewBox.setMinimumHeight(dp(this, 200));
        previewImage = new ImageView(this);
        previewImage.setAdjustViewBounds(true);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImage.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams imgLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        previewBox.addView(previewImage, imgLp);
        previewPlaceholder = new TextView(this);
        previewPlaceholder.setText("选择图片后显示预览");
        previewPlaceholder.setTextSize(13);
        previewPlaceholder.setTextColor(C_HINT);
        previewPlaceholder.setGravity(Gravity.CENTER);
        previewBox.addView(previewPlaceholder, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, PREVIEW_BOX_H_DP));
        previewCard.addView(previewBox, boxLp);

        tvPreviewInfo = new TextView(this);
        tvPreviewInfo.setText("未选择图片");
        tvPreviewInfo.setTextSize(11.5f);
        tvPreviewInfo.setTextColor(C_SUB);
        tvPreviewInfo.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), 0);
        previewCard.addView(tvPreviewInfo);

        LinearLayout previewBtns = new LinearLayout(this);
        previewBtns.setOrientation(LinearLayout.HORIZONTAL);
        previewBtns.setPadding(dp(this, 10), dp(this, 10), dp(this, 10), dp(this, 10));
        previewBtns.addView(secondaryButton("选择图片", v -> pickImage()), weightLp());
        previewBtns.addView(secondaryButton("重新加载", v -> loadPreview(currentPath)), weightLp());
        previewBtns.addView(secondaryButton("清空", v -> clearPreview()), weightLp());
        previewCard.addView(previewBtns);

        parent.addView(previewCard, cardLp());

        // ---------- 识别参数（路径 / 格式 / 档位 合成一张卡） ----------
        addSectionTitle(parent, "识别参数");

        LinearLayout paramCard = new LinearLayout(this);
        paramCard.setOrientation(LinearLayout.VERTICAL);
        paramCard.setBackground(borderedBg(this, C_SURFACE, C_DIVIDER, 14));
        paramCard.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        paramCard.setElevation(dp(this, 1));

        paramCard.addView(fieldLabel("图片路径"));
        etPath = new EditText(this);
        styleInput(etPath);
        etPath.setHint("如 /sdcard/test.png");
        etPath.setText("/sdcard/test.png");
        etPath.setSingleLine(true);
        etPath.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                currentPath = s.toString().trim();
            }
        });
        paramCard.addView(etPath, inputLp());
        currentPath = "/sdcard/test.png";

        paramCard.addView(fieldLabel("输出格式"));
        formatSpinner = new Spinner(this);
        String[] modes = {"1 · 文本", "2 · 坐标", "3 · 矩形"};
        formatSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, modes));
        formatSpinner.setBackground(borderedBg(this, C_SURFACE, C_DIVIDER, 12));
        formatSpinner.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        paramCard.addView(formatSpinner, inputLp());

        paramCard.addView(fieldLabel("模型档位"));
        tierSpinner = new Spinner(this);
        tierSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, TIER_LABELS));
        tierSpinner.setBackground(borderedBg(this, C_SURFACE, C_DIVIDER, 12));
        tierSpinner.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        tierSpinner.setSelection(Math.max(0, Math.min(TIER_LABELS.length - 1,
                OCRService.currentTier)), false);
        tierSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                applyTier(pos);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        paramCard.addView(tierSpinner, inputLp());

        tierInfo = new TextView(this);
        tierInfo.setTextSize(11.5f);
        tierInfo.setTextColor(C_SUB);
        tierInfo.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams tierLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tierLp.topMargin = dp(this, 6);
        paramCard.addView(tierInfo, tierLp);
        updateTierInfo();

        parent.addView(paramCard, cardLp());

        // ---------- 开始识别 ----------
        Button btnOcr = primaryButton("开始识别");
        LinearLayout.LayoutParams ocrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 48));
        ocrLp.topMargin = dp(this, 18);
        parent.addView(btnOcr, ocrLp);

        // ---------- 识别结果 ----------
        addSectionTitle(parent, "识别结果");
        tvResult = new TextView(this);
        tvResult.setText("等待识别…");
        tvResult.setTextSize(12);
        tvResult.setTypeface(Typeface.MONOSPACE);
        tvResult.setTextColor(C_CODE_TEXT);
        tvResult.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        GradientDrawable resBg = new GradientDrawable();
        resBg.setColor(C_CODE_BG);
        resBg.setCornerRadius(dp(this, 14));
        tvResult.setBackground(resBg);
        tvResult.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tvResult.setMinLines(6);
        tvResult.setTextIsSelectable(true);
        LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resLp.topMargin = dp(this, 4);
        parent.addView(tvResult, resLp);

        // 结果下方一排小按钮：复制 / 清空。
        // 复制是真正的高频动作（识别完就要拿去用），而长按选中再拖挡手指。
        LinearLayout resBtns = new LinearLayout(this);
        resBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rbLp.topMargin = dp(this, 8);
        resBtns.addView(secondaryButton("复制文字", v -> copyResult()), weightLp());
        resBtns.addView(secondaryButton("清空结果", v -> {
            if (tvResult != null) {
                tvResult.setText("等待识别…");
            }
        }), weightLp());
        parent.addView(resBtns, rbLp);

        // 日志已收进顶栏「⋮」菜单（LogCard.buildMenuButton），不再占页面底部。
        // ---------- 事件 ----------
        btnOcr.setOnClickListener(v -> startOcr());

        // 原先这里还有「对外接口」一整段：本地接口地址 + curl 示例，
        // 加「自动化插件」的宿主配置步骤与回传变量名。已删除，理由同各页说明：
        // 它们是「怎么用」的说明书，不是这一页的操作。地址与状态页里已经显示着
        // （服务卡上就有 http://127.0.0.1:1666），接口路由与变量名详见 README
        // 的「本地 HTTP 接口」「MacroDroid / Tasker 插件」两章。

        // 进入页面时自动加载一次预览
        loadPreview(currentPath);
    }

    // ============================================================
    //  图片选择与预览
    // ============================================================

    private void pickImage() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    // ============================================================
    //  屏幕截屏
    // ============================================================

    private void updateCaptureInfo() {
        if (capInfo == null) {
            return;
        }
        // 无障碍是「有没有勾选」的实时状态，不看 captureMode ——
        // captureMode 只在真正截过一次之后才会被写上。
        boolean a11y = OcrAccessibilityService.isReady();
        boolean root = "root".equals(OCRService.captureMode);
        boolean mp = "mediaprojection".equals(OCRService.captureMode);
        if (a11y) {
            capInfo.setText("● 无障碍截图已启用，无需授权，可直接截屏识别");
            capInfo.setTextColor(C_OK);
            captureReady = true;
        } else if (root) {
            capInfo.setText("● root 截屏已启用，无需授权，可直接截屏识别");
            capInfo.setTextColor(C_OK);
            captureReady = true;
        } else if (captureReady || mp) {
            capInfo.setText("● 已授权（MediaProjection），可直接截屏识别");
            capInfo.setTextColor(C_OK);
        } else {
            // 未就绪时，把「悬浮窗权限」也一起提示出来 ——
            // 没有它，插件在后台无法自动弹授权框
            boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || android.provider.Settings.canDrawOverlays(this);
            StringBuilder sb = new StringBuilder();
            sb.append("○ 未就绪，三种方式任选其一：\n")
              .append("· 点「无障碍截图」勾选一次，之后永久免授权（推荐）\n")
              .append("· 有 root 的设备会自动启用 root 截屏\n")
              .append("· 或点「开启截屏授权」走录屏授权（重启后需重新点）");
            if (!overlay) {
                sb.append("\n⚠ 未开启「悬浮窗权限」：插件在后台无法自动申请截屏");
            }
            capInfo.setText(sb.toString());
            capInfo.setTextColor(C_WARN);
        }
    }

    /** 通过服务暴露的静态标志确认截屏是否真的就绪。 */
    private void probeCaptureReady() {
        captureReady = OCRService.screenCaptureReady || OcrAccessibilityService.isReady();
        updateCaptureInfo();
        if (captureReady) {
            toast("截屏授权已开启，现在可直接截屏识别");
        } else {
            toast("截屏初始化失败，请查看日志或重新授权");
        }
    }

    /** 申请屏幕捕获权限（系统会弹一次授权框，授权后长期有效）。 */
    private void requestScreenCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            toast("当前系统版本不支持截屏识别");
            return;
        }
        try {
            android.media.projection.MediaProjectionManager mgr =
                    (android.media.projection.MediaProjectionManager)
                            getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mgr == null) {
                toast("无法获取投屏服务");
                return;
            }
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE);
        } catch (Exception e) {
            toast("申请截屏失败: " + e.getMessage());
        }
    }

    /**
     * 跳到系统「无障碍」设置页。
     *
     * 【为什么不能像普通权限那样 requestPermissions】
     * 无障碍服务只能由用户在系统设置里手动勾选，应用无权自行开启 ——
     * 这是 Android 的设计（否则任何 App 都能偷偷拿到屏幕内容）。
     * 所以这里只负责把用户送到那一页，剩下的由他自己决定。
     */
    private void openAccessibilitySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("无障碍截图需要 Android 11 及以上，"
                    + "本机请改用 root 或「开启截屏授权」");
            return;
        }
        if (OcrAccessibilityService.isReady()) {
            toast("无障碍截图已开启，可直接截屏识别");
            return;
        }
        try {
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            toast("请在列表中找到「" + A11Y_LABEL + "」并开启");
        } catch (Exception e) {
            toast("无法打开无障碍设置: " + e.getMessage());
        }
    }

    /**
     * 申请「悬浮窗权限」（SYSTEM_ALERT_WINDOW）。
     *
     * 【为什么需要】
     * MacroDroid 在后台触发插件时，本应用可能处于后台/已死状态。
     * 此时要弹出截屏授权框，就必须能从后台启动 Activity ——
     * 而 Android 10+ 只允许「拥有悬浮窗权限」的应用这么做。
     *
     * 没有这个权限时，插件仍能工作，但需要用户先手动打开一次 App 授权。
     *
     * 注意：该权限不能通过 requestPermissions 申请，
     * 必须跳转到系统设置页由用户手动开启。
     */
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("当前系统版本无需此权限");
            return;
        }
        try {
            if (android.provider.Settings.canDrawOverlays(this)) {
                toast("悬浮窗权限已开启");
                return;
            }
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            toast("请在系统设置里允许「显示在其他应用上层」");
        } catch (Exception e) {
            // 部分 ROM 不支持带 package 的跳转，退回通用设置页
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception e2) {
                toast("无法打开悬浮窗权限设置: " + e2.getMessage());
            }
        }
    }

    // ============================================================
    //  区域识别
    // ============================================================

    /**
     * 设区域：截一张全屏图 → 打开全屏框选页 → 把结果存下来。
     *
     * <p>【为什么框选要在独立的全屏页里做】
     * 页内预览框只有 {@code PREVIEW_BOX_H_DP} 那么高，一张 1080×2400 的截图
     * 塞进去只剩一条窄带，手指一按就盖掉半屏，用户只能靠猜。
     * 区域是「配一次、长期用」的东西，配的时候就该给足面积。
     *
     * <p>【为什么先截屏】
     * 用户框的是「此刻屏幕上的某个位置」，只有拿真实截屏当底图，
     * 他看到的画面才和之后每次识别时一致。
     */
    private void pickRegionFullScreen() {
        if (!warnIfOcrDisabled()) {
            return;
        }
        if (!ensureCaptureReady()) {
            return;
        }
        tvResult.setText("正在截屏，请在整屏上拖出要识别的区域…");
        new Thread(() -> {
            // 走 /shot 而不是 POST /：只要一张图，不必白跑一遍整屏识别。
            String resp = Http.get("/shot", 20000);
            final String path = parseShotPath(resp);
            runOnUiThread(() -> {
                if (path == null) {
                    // 失败原因尽量说清楚：这里最常见的是「截屏方式没就绪」，
                    // 而不是 OCR 本身有问题，提示要对得上。
                    tvResult.setText(resp == null
                            ? "截屏请求失败：服务未响应"
                            : "截屏失败：" + resp);
                    return;
                }
                RegionSelectActivity.open(this, path, true, REQ_REGION_SELECT);
            });
        }).start();
    }

    /**
     * 截屏能力是否就绪；不就绪时给出「该点哪里」的提示并返回 false。
     *
     * <p>抽出来是因为「设区域」与「识别」两条路都要先过这一关，
     * 提示语必须一致 —— 两处各写一份，改一处忘一处就会让用户在
     * 一个入口看到「请开启无障碍截图」，另一个入口看到「正在初始化」。
     */
    private boolean ensureCaptureReady() {
        boolean readyNow = OCRService.screenCaptureReady
                || OcrAccessibilityService.isReady();
        if (readyNow || captureReady) {
            return true;
        }
        if (RootScreenCaptureManager.isRootAvailable()) {
            toast("正在初始化 root 截屏，请稍候再试");
            refreshCaptureMode();
        } else {
            toast("请先开启「无障碍截图」，或点「开启截屏授权」");
            openAccessibilitySettings();
        }
        return false;
    }

    /**
     * 识别已保存的区域。
     *
     * <p>【为什么不每次重新框】这正是需求：「选一次就一直用这个区域」。
     * 每次识别都重画一遍，等于把「配一次」退化成「每次都配」。
     *
     * <p>【为什么每次识别都重新截屏】
     * 区域存的是比例，不依赖任何一张具体截图 —— 真正要识别的是「此刻
     * 屏幕上这一块」的内容。所以这里不传 path，让服务端自己截当前屏幕，
     * 再按同一个比例乘真实宽高裁剪。
     * （反过来说，若拿一张旧路径去识别，读到的是上一次的屏幕内容，
     *   那才是真正的错。）
     */
    private void ocrSavedRegion() {
        if (!warnIfOcrDisabled()) {
            return;
        }
        OcrRegion saved = OcrRegion.load(this);
        if (saved == null) {
            toast("请先点「设区域…」框出一块区域");
            return;
        }
        if (!ensureCaptureReady()) {
            return;
        }
        final int type = formatSpinner == null ? 2 : formatSpinner.getSelectedItemPosition() + 1;
        // 区域识别天然关心坐标（用户就是想知道「这块在哪」）。
        // 若当前档位是「1 · 文本」，返回里没有坐标可画 —— 不改用户的选择，
        // 只在结果说明里如实点出，免得他以为功能坏了。
        tvResult.setText(type < 2
                ? "正在识别区域…（文本档不返回坐标，想看框请把输出格式切到「坐标」或「矩形」）"
                : "正在识别区域…");
        final String region = saved.request();
        new Thread(() -> {
            String result = doHttpRegionRequest(null, region, type);
            runOnUiThread(() -> {
                showResult(result);
                if (type >= 2) {
                    drawBoxesOnBitmapFromResult(result);
                }
                toast("区域识别完成");
            });
        }).start();
    }

    /** 忘掉已存区域，回到整屏识别。 */
    private void clearSavedRegion() {
        OcrRegion.clear(this);
        updateRegionInfo();
        toast("已清除区域，之后按整屏识别");
    }

    /**
     * 刷新区域说明行。
     *
     * <p>区域是「看不见的状态」—— 它不像预览图那样摆在眼前。
     * 不显式写出来，用户就分不清「这次识别的是整屏还是那块」，
     * 结果不对时会去怀疑识别引擎，而问题其实在区域设置上。
     */
    private void updateRegionInfo() {
        if (tvRegionInfo == null) {
            return;
        }
        OcrRegion r = OcrRegion.load(this);
        if (r == null) {
            tvRegionInfo.setText("当前区域：整屏　（点「设区域…」在全屏上框一块，之后一直用它）");
        } else {
            tvRegionInfo.setText("当前区域：左/上/右/下 = " + r.percentText()
                    + "，占屏幕 " + Math.round(r.area() * 100) + "%");
        }
        // 没有区域时「清除区域」是个空操作，置灰比点完什么都不发生更诚实。
        if (btnRegionClear != null) {
            btnRegionClear.setEnabled(r != null);
        }
    }

    /** 从 /shot 的返回里取出图片路径；失败返回 null。 */
    private String parseShotPath(String resp) {
        if (resp == null) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(resp);
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("status") || o.get("status").getAsInt() != 200) {
                return null;
            }
            JsonElement data = o.get("data");
            if (data == null || !data.isJsonObject()) {
                return null;
            }
            JsonElement p = data.getAsJsonObject().get("path");
            return (p == null || p.isJsonNull()) ? null : p.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 带 region 参数的一次识别请求。
     *
     * @param path   要识别的图片路径；传空表示让服务端自己截屏
     * @param region "l,t,r,b" 归一化比例
     * @param type   1=文本 2=含坐标 3=含矩形
     */
    private String doHttpRegionRequest(String path, String region, int type) {
        String json;
        if (path == null || path.length() == 0) {
            json = "{\"type\":" + type + ",\"region\":\"" + region + "\"}";
        } else {
            json = "{\"path\":\"" + path.replace("\\", "\\\\") + "\",\"type\":" + type
                    + ",\"region\":\"" + region + "\"}";
        }
        String resp = Http.post(this, json, 10000, 60000);
        if (resp == null) {
            return "请求失败：服务未响应"
                    + "\n\n服务可能仍在加载模型，请稍等几秒后重试。";
        }
        if (resp.length() == 0) {
            return "{\"status\": 200, \"data\": \"无返回内容，服务可能仍在加载模型\"}";
        }
        return resp;
    }

    /** 截屏后直接走本地接口识别。 */
    private void captureAndOcr() {
        // 开关没开时截屏识别同样走不通（服务不在，HTTP 直接失败）。
        if (!warnIfOcrDisabled()) {
            return;
        }
        if (!ensureCaptureReady()) {
            return;
        }
        final int type = formatSpinner == null ? 1 : formatSpinner.getSelectedItemPosition() + 1;
        tvResult.setText("正在截屏识别…");
        new Thread(() -> {
            // 不传任何图片参数，服务端会自动截屏
            String result = doHttpRequest(null, type);
            runOnUiThread(() -> {
                showResult(result);
                if (type >= 2) {
                    drawBoxesOnBitmapFromResult(result);
                }
            });
        }).start();
    }

    /** 把截屏结果里的坐标画在预览图上。 */
    private void drawBoxesOnBitmapFromResult(String result) {
        try {
            // 关键：必须画在「本次识别的那张截图」上。
            // 之前一直用 previewBitmap，若用户此前选过图片，
            // 就是把屏幕坐标画在另一张图上，位置必然完全不对。
            Bitmap src = null;
            String capPath = OCRService.lastCapturePath;
            if (capPath != null && new File(capPath).exists()) {
                src = decodeSampled(capPath, 1600);
            }
            // 兜底：拿不到截屏缓存时才退回当前预览图
            if (src == null) {
                src = (previewBitmap == null || previewBitmap.isRecycled())
                        ? null : previewBitmap.copy(Bitmap.Config.ARGB_8888, true);
            }
            if (src == null) {
                return;
            }
            if (!src.isMutable()) {
                Bitmap mutable = src.copy(Bitmap.Config.ARGB_8888, true);
                src.recycle();
                src = mutable;
            }
            if (src == null) {
                return;
            }
            drawBoxes(src, result);
        } catch (Exception ignored) {
            // 画框是纯展示，解码或绘制异常不应该让已经拿到的识别结果丢失。
        }
    }

    /**
     * 切换档位。
     *
     * 刻意走 HTTP 接口而不是直接调 Service——
     * 这样界面切换和外部调用切换是**同一条代码路径**，
     * 不会出现"界面切了但接口没切"的不一致。
     * 接口里 tier 是全局状态，切换后对所有请求生效。
     */
    private void applyTier(int tier) {
        if (tierSwitching) return;
        if (tier == OCRService.currentTier) {
            updateTierInfo();
            return;
        }

        ensureServiceRunning();

        tierSwitching = true;
        updateTierInfo();
        final String tierName = OCRService.tierName(tier);
        new Thread(() -> {
            // 【修复⑪】统一走 Http.post（与插件/BroadcastReceiver 同一条实现），
            // 不再保留 Activity 内那第二份几乎相同的 postJson。
            // 超时与原 postJson 一致：连接 5s / 读取 30s。
            String resp = Http.post(this,
                    "{\"tier\":\"" + tierName + "\",\"action\":\"setTier\"}", 5000, 30000);

            // 以返回体里的 "tier=xxx" 为准，而不是含糊地找 status 200——
            // 后者一旦接口返回其它 200 响应就会误判成功。
            boolean ok = resp != null && resp.contains("tier=" + tierName);
            String respFinal = resp;

            runOnUiThread(() -> {
                tierSwitching = false;
                if (ok) {
                    OCRService.currentTier = tier;
                    toast("已切换到 " + tierName + " 档");
                } else {
                    String reason = respFinal == null ? "服务未响应" : respFinal.trim();
                    if (reason.length() > 120) reason = reason.substring(0, 120) + "…";
                    toast("切换失败：" + reason);
                    if (tierSpinner != null) {
                        tierSpinner.setSelection(OCRService.currentTier, false);
                    }
                }
                updateTierInfo();
            });
        }, "xsocr-tier").start();
    }

    /** 刷新档位说明文字。 */
    private void updateTierInfo() {
        if (tierInfo == null) return;
        String cur = OCRService.tierName(OCRService.currentTier);
        if (tierSwitching) {
            tierInfo.setText("正在切换模型档位…（small 首次加载需 1~3 秒）");
            return;
        }
        String detail;
        switch (OCRService.currentTier) {
            case OCRService.TIER_RAPID:
                detail = "ML Kit（捆绑版，中英），无需 Google Play 服务。";
                break;
            case OCRService.TIER_SMALL:
                detail = "高精度档：字典 18708 字符。";
                break;
            default:
                detail = "轻量档：字典 6904 字符，速度最快。";
                break;
        }
        tierInfo.setText("当前：" + cur + "\n" + detail);
    }

    /** 把截屏授权结果交给服务，建立投屏会话。 */
    private void startCaptureSession(int resultCode, Intent data) {
        try {
            Intent intent = new Intent(this, OCRService.class);
            intent.setAction(OCRService.ACTION_START_CAPTURE);
            intent.putExtra(OCRService.EXTRA_RESULT_CODE, resultCode);
            intent.putExtra(OCRService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            // 等投屏会话建立后再确认状态（不要盲目标记成功）
            new android.os.Handler(getMainLooper()).postDelayed(this::probeCaptureReady, 900);
        } catch (Exception e) {
            toast("开启截屏失败: " + e.getMessage());
        }
    }

    /**
     * 服务尚未拉起时，用轮询等待它就绪，再把授权结果送过去。
     *
     * 原先是在主线程 {@code Thread.sleep(800)}，既有 ANR 风险，
     * 又因为固定时长在慢设备上不够而随机失败。改为轮询 + 上限。
     */
    private void waitServiceThenStartCapture(int resultCode, Intent data) {
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final int[] tries = {0};
        final int maxTries = 40;      // 40 × 250ms = 最多等 10s
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (OCRService.isRunning) {
                    startCaptureSession(resultCode, data);
                    return;
                }
                if (++tries[0] >= maxTries) {
                    toast("服务启动超时，请重试");
                    return;
                }
                h.postDelayed(this, 250);
            }
        };
        h.postDelayed(poll, 250);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                toast("已取消截屏授权");
                return;
            }
            // 注意：这里是主线程，绝不能 sleep。
            // 服务没起来时先拉起，截屏授权结果延后再送（见 postStartCapture）。
            if (!OCRService.isRunning) {
                ensureServiceRunning();
                waitServiceThenStartCapture(resultCode, data);
                return;
            }

            startCaptureSession(resultCode, data);
            return;
        }

        if (requestCode == REQ_REGION_SELECT) {
            if (resultCode != RESULT_OK || data == null) {
                // 用户在框选页点了「取消」：不动已存区域。
                // 这是有意的 —— 取消只该放弃这一次操作，不该顺手把他的配置清掉。
                return;
            }
            float[] r = {
                    data.getFloatExtra(RegionSelectActivity.EXTRA_LEFT, 0f),
                    data.getFloatExtra(RegionSelectActivity.EXTRA_TOP, 0f),
                    data.getFloatExtra(RegionSelectActivity.EXTRA_RIGHT, 0f),
                    data.getFloatExtra(RegionSelectActivity.EXTRA_BOTTOM, 0f)
            };
            // save() 自己会拒绝反序/越界（并清掉旧值），这里不再重复校验。
            OcrRegion.save(this, r);
            updateRegionInfo();
            tvResult.setText("已保存区域，之后点「识别区域」只识别这一块。");
            toast("区域已保存，之后一直用这块");
            return;
        }

        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            // 复制到应用缓存目录，便于本地服务 / 调试页读取
            File dst = new File(getCacheDir(), "ocr_input.png");
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                toast("无法读取所选图片");
                return;
            }
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            in.close();

            currentPath = dst.getAbsolutePath();
            if (etPath != null) {
                etPath.setText(currentPath);
            }
            loadPreview(currentPath);
        } catch (Exception e) {
            toast("导入图片失败: " + e.getMessage());
        }
    }

    /** 加载图片到预览框（不画框）。 */
    private void loadPreview(String path) {
        if (previewImage == null) {
            return;
        }
        if (path == null || path.length() == 0) {
            return;
        }
        Bitmap bmp = decodeSampled(path, 1600);
        if (bmp == null) {
            previewImage.setVisibility(View.GONE);
            if (previewPlaceholder != null) {
                previewPlaceholder.setVisibility(View.VISIBLE);
                previewPlaceholder.setText("图片不存在或无法解码");
            }
            if (tvPreviewInfo != null) {
                tvPreviewInfo.setText("未选择图片");
            }
            return;
        }
        if (previewBitmap != null && previewBitmap != bmp && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        previewBitmap = bmp;
        showBitmap(bmp);
        if (tvPreviewInfo != null) {
            tvPreviewInfo.setText(bmp.getWidth() + " × " + bmp.getHeight() + "   " + path);
        }
    }

    private void showBitmap(Bitmap bmp) {
        previewImage.setImageBitmap(bmp);
        previewImage.setVisibility(View.VISIBLE);
        if (previewPlaceholder != null) {
            previewPlaceholder.setVisibility(View.GONE);
        }
    }

    private void clearPreview() {
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        previewBitmap = null;
        if (previewImage != null) {
            previewImage.setImageDrawable(null);
            previewImage.setVisibility(View.GONE);
        }
        if (previewPlaceholder != null) {
            previewPlaceholder.setVisibility(View.VISIBLE);
            previewPlaceholder.setText("选择图片后显示预览");
        }
        if (tvPreviewInfo != null) {
            tvPreviewInfo.setText("未选择图片");
        }
    }

    /** 按最大边长采样解码，避免大图 OOM。 */
    private Bitmap decodeSampled(String path, int maxSide) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            int longer = Math.max(opts.outWidth, opts.outHeight);
            while (longer / sample > maxSide) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    //  识别
    // ============================================================

    private void startOcr() {
        final String p = etPath == null ? "" : etPath.getText().toString().trim();
        final int type = formatSpinner == null ? 1 : formatSpinner.getSelectedItemPosition() + 1;

        // 开关关着就别往下走了：ensureServiceRunning() 此时会直接返回，
        // 后面的 HTTP 请求只会拿到「连接失败」，用户完全不知道是开关没开。
        if (!warnIfOcrDisabled()) {
            return;
        }
        if (p.length() == 0) {
            toast("请输入图片路径或选择图片");
            return;
        }
        if (!new File(p).exists()) {
            toast("图片不存在: " + p);
            return;
        }

        ensureServiceRunning();
        tvResult.setText("识别中…");

        new Thread(() -> {
            String result = doHttpRequest(p, type);
            runOnUiThread(() -> {
                showResult(result);
                // 识别完成后，把检测框画到预览图上
                if (type >= 2) {
                    drawBoxesOnPreview(p, result);
                }
            });
        }).start();
    }

    /** 把结果区的文字写进剪贴板。 */
    private void copyResult() {
        if (tvResult == null) {
            return;
        }
        String text = tvResult.getText() == null ? "" : tvResult.getText().toString();
        if (text.length() == 0) {
            toast("还没有结果");
            return;
        }
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("剪贴板不可用");
            return;
        }
        cm.setPrimaryClip(android.content.ClipData.newPlainText("OCR", text));
        toast("已复制到剪贴板");
    }

    /**
     * 展示识别结果。
     *
     * <p>【三种格式都只显示「识别内容」，不显示包装字段】
     * 服务端返回的是 {@code {"status":200,"data":[...],"time":25,"tier":"tiny",
     * "fullWidth":1156,"fullHeight":2510,"regionLeft":68,"regionTop":418}}。
     * 其中 status/time/tier 是运行状态，fullWidth/regionLeft 那四个是
     * 「坐标基于多大的画布」的换算依据 —— 它们对**调用方程序**有用，
     * 对**用眼睛看结果的人**只是噪声。
     *
     * <p>所以结果区按格式只给内容：
     * <ul>
     *   <li>1 文本 —— 只有文字，一行一条；</li>
     *   <li>2 坐标 —— {@code text(x,y)} 一行一条；</li>
     *   <li>3 矩形 —— {@code text(x,y,w,h)} 一行一条。</li>
     * </ul>
     *
     * <p>【为什么不再原样显示整段 JSON】2.12 之前 2/3 是原样显示的，
     * 用户看到的是「选哪种格式都跟着一长串 status/time/fullWidth」，
     * 分不清哪些是自己要的。真正需要完整 JSON 的场景是给程序调用，
     * 那条路径走的是本地 HTTP 接口，不经过这个界面 ——
     * 界面把包装字段藏起来不会影响任何外部调用。
     *
     * <p>解析不出来（出错、非 JSON）时仍**原样显示**：那时候
     * status 和错误信息恰恰是用户唯一能看到的线索。
     */
    private void showResult(String result) {
        if (tvResult == null) {
            return;
        }
        if (result == null) {
            tvResult.setText("无返回内容");
            return;
        }

        int type = formatSpinner == null ? 1 : formatSpinner.getSelectedItemPosition() + 1;
        String shown = formatResult(result, type);
        if (shown != null) {
            tvResult.setText(shown);
            return;
        }
        // 解析失败：原样显示，让用户至少能看到 status / 错误信息。
        tvResult.setText(result);
    }

    /**
     * 按格式把返回体渲染成给人看的结果；渲染不出来返回 null。
     *
     * @param type 1=文本 2=坐标 3=矩形
     */
    private static String formatResult(String result, int type) {
        try {
            JsonElement el = JsonParser.parseString(result);
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            JsonObject obj = el.getAsJsonObject();
            JsonElement dataEl = obj.get("data");
            if (dataEl == null || dataEl.isJsonNull()) {
                return null;
            }

            // ---------- 1 · 文本 ----------
            if (type <= 1) {
                return plainLines(dataEl);
            }

            // ---------- 2 · 坐标 / 3 · 矩形 ----------
            // 每项一行：文字在最前，坐标跟在括号里。
            // 不直接输出 JSON 是为了「一眼能读」：这是给人看的结果区，
            // 不是给程序解析的（程序走 HTTP 接口，拿的是完整 JSON）。
            if (!dataEl.isJsonArray()) {
                // type>=2 但 data 不是数组（例如纯字符串），退回文字模式
                return plainLines(dataEl);
            }
            StringBuilder sb = new StringBuilder();
            for (JsonElement item : dataEl.getAsJsonArray()) {
                if (item == null || item.isJsonNull() || !item.isJsonObject()) {
                    continue;
                }
                JsonObject o = item.getAsJsonObject();
                JsonElement t = o.get("text");
                String text = (t == null || t.isJsonNull()) ? "" : t.getAsString();
                if (text.length() == 0) {
                    // 没有文字的项不占一行：否则结果区会冒出「(310,594)」这种
                    // 只有坐标的怪行。
                    continue;
                }
                String line = type >= 3
                        ? text + coord4(o)
                        : text + coord2(o);
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            // 一项都没有时返回 null，让调用方原样显示 ——
            // 空数组往往是「这块区域里没识别到字」，原样显示还能看到 status。
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code (x,y)}；缺字段时给空串，不伪造 0 坐标。 */
    private static String coord2(JsonObject o) {
        Integer x = intField(o, "x");
        Integer y = intField(o, "y");
        if (x == null || y == null) {
            return "";
        }
        return "(" + x + "," + y + ")";
    }

    /** {@code (x,y,w,h)}；缺任一字段时给空串。 */
    private static String coord4(JsonObject o) {
        Integer x = intField(o, "x");
        Integer y = intField(o, "y");
        Integer w = intField(o, "w");
        Integer h = intField(o, "h");
        if (x == null || y == null || w == null || h == null) {
            return "";
        }
        return "(" + x + "," + y + "," + w + "," + h + ")";
    }

    private static Integer intField(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsInt();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 从 {@code data} 里取出一行一条的文字；取不出来返回 null。
     *
     * <p>【为什么要处理三种形态】{@code data} 可能是：
     * <ul>
     *   <li>{@code [{"text":"a"}]} —— type=1 的常态；</li>
     *   <li>{@code ["a","b"]} —— 裸字符串数组；</li>
     *   <li>{@code "整段文字"} —— 某些路径会把文字拼成一个字符串。</li>
     * </ul>
     * 【曾经的 bug】原实现只判断 {@code isJsonPrimitive()}（只认第三种），
     * 而实际走的是第一种 —— 于是「文本」档把整段 JSON 原样显示了出来。
     *
     * <p>只认字符串形态：{@code data} 本身是数字、或数组里混进数字，
     * 都说明返回体结构不对，这时返回 null 交给调用方原样显示，
     * 而不是把 {@code 123} 当成一行识别结果。
     *
     * <p>各项之间用换行连接：一行一条本就是 OCR 结果的天然形状，
     * 用逗号或空串拼起来会读不出结构。
     */
    private static String plainLines(JsonElement dataEl) {
        if (dataEl == null || dataEl.isJsonNull()) {
            return null;
        }
        if (dataEl.isJsonPrimitive()) {
            // 只认字符串形态。data 是数字/布尔（如 "data":123）说明返回体结构不对，
            // 这时该原样显示让用户看到异常，而不是把 "123" 当成识别结果。
            if (!dataEl.getAsJsonPrimitive().isString()) {
                return null;
            }
            return dataEl.getAsString();
        }
        if (!dataEl.isJsonArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement item : dataEl.getAsJsonArray()) {
            if (item == null || item.isJsonNull()) {
                continue;
            }
            String line = null;
            if (item.isJsonObject()) {
                JsonElement t = item.getAsJsonObject().get("text");
                if (t != null && !t.isJsonNull()) {
                    line = t.getAsString();
                }
            } else if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                // 只认字符串。数字/布尔（如 data:[123]）不是识别到的文字，
                // 宽容地转成 "123" 只会在结果区多出一行莫名其妙的数字。
                line = item.getAsString();
            }
            if (line == null || line.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        // 空数组不返回空串：交给调用方原样显示，用户还能看到 status/错误信息，
        // 不会只得到一个空白结果区。
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 解析结果里的坐标并在预览图上画框。 */
    private void drawBoxesOnPreview(String path, String result) {
        Bitmap base = decodeSampled(path, 1600);
        if (base == null) {
            return;
        }
        Bitmap mutable = base.copy(Bitmap.Config.ARGB_8888, true);
        base.recycle();
        if (mutable == null) {
            return;
        }
        drawBoxes(mutable, result);
    }

    /** 在给定位图上画出识别框。 */
    private void drawBoxes(Bitmap target, String result) {
        try {
            JsonElement el = JsonParser.parseString(result);
            if (!el.isJsonObject()) {
                return;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("status") || obj.get("status").getAsInt() != 200) {
                return;
            }
            JsonElement dataEl = obj.get("data");
            if (dataEl == null || !dataEl.isJsonArray()) {
                return;
            }
            JsonArray arr = dataEl.getAsJsonArray();
            if (arr.size() == 0) {
                showBitmap(target);
                return;
            }

            Canvas canvas = new Canvas(target);
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, target.getWidth() / 500f));
            paint.setColor(0xFF12B76A);

            int w = target.getWidth();
            int h = target.getHeight();

            // ===== 坐标换算（两级）=====
            // 识别返回的 x/y/w/h 是相对「送入引擎的那张位图」的像素值。
            // 而 target 是预览用的缩略图（decodeSampled 会按 inSampleSize 降采样）。
            //
            // 【第一级：裁剪偏移 → 全屏】区域识别时引擎拿到的是框内一小块，
            //   它给出的坐标不带「这一块在屏幕哪儿」，必须先加回选区左上角。
            //   服务端已经对返回的 JSON 做过平移（见 OcrHttpServer.shiftCoords），
            //   所以这里读到的一般已是全屏坐标 —— 但若调用方用的是旧缓存结果，
            //   或 localStorage 里存的是未平移的，下面这行兜底能让它也能对上。
            //
            // 【第二级：全屏 → 预览缩略图】两者分辨率不同，直接画会整体放大、
            //   往右下偏移，再被裁剪贴边 —— 这就是「画到缩略图上不准」的原因。
            //
            // 【为什么用 lastFullImageWidth 而不是 lastOcrImageWidth】
            //   区域识别时后者是「裁剪块」的宽高，拿它算缩放比例会算错；
            //   全图像素尺寸才是预览图对应的那个坐标系。非区域识别时
            //   两者相等，行为与改造前完全一致。
            int srcW = OCRService.lastFullImageWidth > 0
                    ? OCRService.lastFullImageWidth : OCRService.lastOcrImageWidth;
            int srcH = OCRService.lastFullImageHeight > 0
                    ? OCRService.lastFullImageHeight : OCRService.lastOcrImageHeight;
            float sx = (srcW > 0) ? (float) w / (float) srcW : 1f;
            float sy = (srcH > 0) ? (float) h / (float) srcH : 1f;

            for (JsonElement item : arr) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject o = item.getAsJsonObject();
                int x = getInt(o, "x", -1);
                int y = getInt(o, "y", -1);
                int bw = getInt(o, "w", -1);
                int bh = getInt(o, "h", -1);

                // 兼容 left/top/right/bottom 形式
                if (x < 0 || y < 0 || bw <= 0 || bh <= 0) {
                    int left = getInt(o, "left", -1);
                    int top = getInt(o, "top", -1);
                    int right = getInt(o, "right", -1);
                    int bottom = getInt(o, "bottom", -1);
                    if (left >= 0 && top >= 0 && right > 0 && bottom > 0) {
                        x = left;
                        y = top;
                        bw = right - left;
                        bh = bottom - top;
                    }
                }
                if (x < 0 || y < 0 || bw <= 0 || bh <= 0) {
                    continue;
                }

                int l = Math.max(0, Math.round(x * sx));
                int t = Math.max(0, Math.round(y * sy));
                int r = Math.min(w, Math.round((x + bw) * sx));
                int b = Math.min(h, Math.round((y + bh) * sy));
                if (r <= l || b <= t) {
                    continue;
                }

                canvas.drawRect(new RectF(l, t, r, b), paint);
            }

            // 先切换再回收，且绝不能回收正在显示的这张。
            // 原写法先 recycle 再 showBitmap，当 target == previewBitmap 时
            // 会把即将显示的位图回收掉，导致 "trying to use a recycled bitmap" 崩溃。
            Bitmap old = previewBitmap;
            previewBitmap = target;
            showBitmap(target);
            if (old != null && old != target && !old.isRecycled()) {
                old.recycle();
            }
        } catch (Exception ignored) {
            // 此处已在 showBitmap 之后，回收失败只会漏一张旧位图，绝不能让它把新图带崩。
        }
    }

    private int getInt(JsonObject o, String key, int def) {
        try {
            if (!o.has(key) || o.get(key).isJsonNull()) {
                return def;
            }
            JsonElement e = o.get(key);
            if (e.getAsJsonPrimitive().isNumber()) {
                return (int) e.getAsDouble();
            }
            return (int) Double.parseDouble(e.getAsString());
        } catch (Exception ex) {
            return def;
        }
    }

    // ============================================================
    //  网络请求
    // ============================================================

    private String doHttpRequest(String path, int type) {
        // 【修复⑪】原来这里是一整份自写的 HttpURLConnection 实现，
        // 与 Http.post() / postJson() 三份几乎重复，改动极易漏改。
        // 现在统一复用 Http.post：
        //   · 端口来自 OCRService.HTTP_PORT（唯一来源）
        //   · 超时保持原值：连接 10s / 读取 60s（大图 + rapid 档确实慢）
        // 返回 null（连接/读取失败）时再转成原来的提示文案，界面表现不变。
        String json;
        if (path == null || path.length() == 0) {
            json = "{\"type\":" + type + "}";
        } else {
            json = "{\"path\":\"" + path.replace("\\", "\\\\") + "\",\"type\":" + type + "}";
        }

        String resp = Http.post(this, json, 10000, 60000);
        if (resp == null) {
            return "请求失败：服务未响应"
                    + "\n\n服务可能仍在加载模型，请稍等几秒后重试。";
        }
        if (resp.length() == 0) {
            return "{\"status\": 200, \"data\": \"无返回内容，服务可能仍在加载模型\"}";
        }
        return resp;
    }

    // ============================================================
    //  保活设置
    // ============================================================

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("当前系统版本无需设置");
            return;
        }
        if (isIgnoringBatteryOptimizations()) {
            toast("已在白名单中，无需重复设置");
            return;
        }
        try {
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                toast("请找到 Grace 并设为「不限制」");
            } catch (Exception e2) {
                toast("请手动到 设置-电池 关闭本应用限制");
            }
        }
    }

    // ============================================================
    //  通用 UI
    // ============================================================

    /**
     * 卡片内的字段名（比 addSectionTitle 小一号，不占整行分隔）。
     *
     * 一张卡里搞三个输入项时，每个都用 section 标题会把卡片拆成四段，
     * 看不出它们属于同一件事；这里只用小字标注，层次得以保留。
     */
    private TextView fieldLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(C_SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 10);
        lp.bottomMargin = dp(this, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addSectionTitle(ViewGroup parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(C_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 18);
        lp.bottomMargin = dp(this, 10);
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 12);
        return lp;
    }

        private void styleInput(EditText et) {
        et.setBackground(borderedBg(this, C_SURFACE, C_DIVIDER, 12));
        et.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        et.setTextSize(13.5f);
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_HINT);
    }

    private LinearLayout.LayoutParams inputLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 2);
        return lp;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(this, 42), 1f);
        lp.setMargins(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        return lp;
    }

    private Button primaryButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14.5f);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setStateListAnimator(null);

        GradientDrawable base = new GradientDrawable();
        base.setColor(C_BRAND);
        base.setCornerRadius(dp(this, 12));
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), base, null);
        btn.setBackground(ripple);
        return btn;
    }

    private Button secondaryButton(String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12.5f);
        btn.setAllCaps(false);
        btn.setTextColor(C_BRAND);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setStateListAnimator(null);

        GradientDrawable base = new GradientDrawable();
        base.setColor(C_BRAND_SOFT);
        base.setCornerRadius(dp(this, 12));
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x334F6BED), base, null);
        btn.setBackground(ripple);
        btn.setOnClickListener(listener);
        return btn;
    }

        private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {
                    // 个别 ROM 没有这个设置页，startActivity 会抛 ActivityNotFound；失败就停在当前页。
                }
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    /** 供首页复用：带 NEW_TASK 兜底，传入非 Activity 上下文也安全。 */
    public static void open(android.content.Context ctx) {
        Intent i = new Intent(ctx, OcrActivity.class);
        if (!(ctx instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }

}