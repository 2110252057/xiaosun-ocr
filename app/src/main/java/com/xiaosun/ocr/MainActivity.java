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
import android.util.TypedValue;
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

/**
 * 主界面。
 *
 * 打开 App 自动拉起本地 OCR 服务（端口见 {@link OCRService#HTTP_PORT}）。
 * 两个页签：OCR设置 / 滑动触发
 *
 * 调试页：选中图片直接在预览框显示，识别后把检测框画在预览图上，
 * 结果区按所选格式显示（纯文本时只输出文字本身）。
 */
public class MainActivity extends AppCompatActivity {

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
    private static final String A11Y_LABEL = "小孙OCR 截图";
    private static final int REQ_PICK_IMAGE = 1001;
    private static final int REQ_SCREEN_CAPTURE = 1002;

    // ---- 设计规范 ----
    private static final int C_BRAND = 0xFF4F6BED;
    private static final int C_BRAND_DARK = 0xFF3B54C4;
    private static final int C_BRAND_SOFT = 0xFFEDF0FE;
    private static final int C_BG = 0xFFF5F6FA;
    private static final int C_SURFACE = 0xFFFFFFFF;
    private static final int C_DIVIDER = 0xFFEAECF2;
    private static final int C_TEXT = 0xFF1A1D26;
    private static final int C_TEXT_SUB = 0xFF6B7280;
    private static final int C_HINT = 0xFF9CA3AF;
    private static final int C_OK = 0xFF12B76A;
    private static final int C_WARN = 0xFFF79009;
    private static final int C_CODE_BG = 0xFF1E2029;
    private static final int C_CODE_TEXT = 0xFFC8D0E0;

    /**
     * 页签列表。
     *
     * 【为什么只剩两个】
     * 原来第一页是「接口文档」（HTTP 接口的参数表 + 多语言调用示例），
     * 第二页是「在线调试」（开关、状态、保活、截屏、图片预览）。
     * 但宏早就可以直接在 MacroDroid 里选插件配置，照着文档手写 HTTP 调用
     * 这条路已经没人走了 —— 文档页从此只是占地方。
     * 于是文档页整页删除，它上面唯一仍然有用的「总开关」搬到调试页，
     * 两页合并成一页，改名叫「OCR设置」。
     */
    private final String[] tabs = {"OCR设置", "滑动触发"};
    private LinearLayout contentContainer;
    // 长度跟随 tabs，避免以后加页签时数组越界。
    private final View[] tabViews = new View[tabs.length];
    private final TextView[] tabTexts = new TextView[tabs.length];
    /** 「滑动触发」页的状态文字（服务未运行时为 null）。 */
    private TextView tvGestureState;
    /**
     * 「滑动菜单」卡片右侧那行状态文案。
     *
     * 提成成员是因为它需要能单独刷新：用户在菜单配置页里开关了菜单、
     * 增删了菜单项，返回本页时这行字必须跟着变，否则显示的还是进来时那次
     * 的快照（"未启用"却其实已启用）。
     */
    private TextView tvMenuSub;

    private TextView tvStatusDot;
    private TextView tvStatusText;
    /** 状态卡副标题（接口地址）。关闭时它不该继续显示一个没人监听的地址。 */
    private TextView tvStatusSub;
    private TextView tvBatteryText;
    private View batteryCard;
    private boolean serviceStarting = false;
    /**
     * 初始化 Switch 期间抑制回调。
     *
     * setChecked() 本身就会触发一次 OnCheckedChangeListener，
     * 若不管它，打开设置页的瞬间就会平白重建一次 task。
     */
    private boolean suppressRecentsToggle = false;
    /**
     * 「手势正在启动…」状态的重试计数与开关。
     *
     * 服务的 gsRunning 是在 onStartCommand（主线程）里翻的，
     * 而页面构建几乎总发生在它之前，所以刚进这一页看到的必然是「正在启动…」。
     * 留几次重试让它自己变成「运行中」，否则用户会以为服务根本没起来。
     * 具体计数放在延迟任务自己身上，这里只需要一个「是否已有链在排」的标志。
     */
    private boolean gestureRecheckPending = false;
    /** 延迟刷新状态用的 Handler。 */
    private android.os.Handler gestureRecheckHandler;
    /** 重试间隔与次数：12 × 500ms = 6 秒，足够系统把服务拉起来。 */
    private static final int GESTURE_RECHECK_MAX = 12;
    private static final int GESTURE_RECHECK_INTERVAL = 500;

    // 调试页状态
    private ImageView previewImage;
    private TextView previewPlaceholder;
    private TextView tvPreviewInfo;
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
        root.addView(buildSegmentedTabs());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, dp(4), 0, 0);
        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(16), dp(8), dp(16), dp(32));
        scrollView.addView(contentContainer);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        switchTab(0);
        ensureServiceRunning();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureServiceRunning();
        // 回到前台刷新截屏方式（root 探测在服务里异步完成）
        refreshCaptureMode();
        /*
         * 手势服务在这里再主动拉一次。
         *
         * 光靠 START_STICKY 不够：服务被系统重启过一次之后
         * （dumpsys 里 restartCount=1、lastActivity==restartTime），
         * 就再也没有任何代码主动拉过它，手柄也就一直不出现。
         * sync() 会按总开关决定起还是停，是幂等的。
         */
        EdgeGestureService.sync(this);
        // 回前台时对齐一次「从最近任务隐藏」：可能是别的入口改了设置，
        // 也可能系统重建过 task，重设一遍最稳（值相同时是空操作）。
        OCRService.applyRecents(this);
        // 从手势设置页返回、或刚被划掉后重新进来时，开关/权限/运行状态可能都变了。
        refreshGestureState();
        // 「滑动菜单」的开关与项数是在子页里改的，返回本页必须重读，
        // 否则卡片上还写着进来时那次的旧状态。
        refreshMenuCard();
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
        bar.setBackground(brandGradient());
        bar.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView logo = new TextView(this);
        logo.setText("A");
        logo.setTextSize(20);
        logo.setTypeface(null, Typeface.BOLD);
        logo.setTextColor(C_BRAND);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setColor(Color.WHITE);
        logoBg.setCornerRadius(dp(12));
        logo.setBackground(logoBg);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        logoLp.setMarginEnd(dp(14));
        bar.addView(logo, logoLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("小孙OCR");
        title.setTextSize(21);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setLetterSpacing(0.02f);
        column.addView(title);

        TextView sub = new TextView(this);
        sub.setText("PaddleOCR v6 · NCNN 本地识别  " + APP_VERSION);
        sub.setTextSize(11.5f);
        sub.setTextColor(0xCCFFFFFF);
        column.addView(sub);

        bar.addView(column);
        return bar;
    }

    private GradientDrawable brandGradient() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{C_BRAND, C_BRAND_DARK});
        g.setCornerRadius(0f);
        return g;
    }

    // ============================================================
    //  分段式页签
    // ============================================================

    private View buildSegmentedTabs() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setBackgroundColor(C_SURFACE);
        wrapper.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(C_BG);
        segBg.setCornerRadius(dp(12));
        seg.setBackground(segBg);
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));

        for (int i = 0; i < tabs.length; i++) {
            final int index = i;

            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(9), 0, dp(9));

            TextView tv = new TextView(this);
            tv.setText(tabs[i]);
            tv.setTextSize(13.5f);
            tv.setGravity(Gravity.CENTER);
            item.addView(tv);

            item.setOnClickListener(v -> switchTab(index));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            seg.addView(item, lp);

            tabViews[i] = item;
            tabTexts[i] = tv;
        }

        wrapper.addView(seg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(C_SURFACE);
        outer.addView(wrapper);
        View line = new View(this);
        line.setBackgroundColor(C_DIVIDER);
        outer.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return outer;
    }

    private void switchTab(int index) {
        for (int i = 0; i < tabViews.length; i++) {
            boolean active = (i == index);
            tabViews[i].setBackground(active ? roundedBg(C_SURFACE, 9) : transparentBg());
            tabViews[i].setElevation(active ? dp(2) : 0);
            tabTexts[i].setTextColor(active ? C_BRAND : C_TEXT_SUB);
            tabTexts[i].setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }

        contentContainer.removeAllViews();
        tvStatusDot = null;
        tvStatusText = null;
        tvStatusSub = null;
        tvBatteryText = null;
        batteryCard = null;
        previewImage = null;
        previewPlaceholder = null;
        tvPreviewInfo = null;
        tvResult = null;
        formatSpinner = null;
        etPath = null;
        tierSpinner = null;
        tierInfo = null;
        // 切页时把还排着的状态重试链掐掉：旧页面马上就不存在了，重试没有意义。
        cancelGestureRecheck();
        tvGestureState = null;
        tvMenuSub = null;
        if (index == 0) {
            buildOcrSettingsPage(contentContainer);
        } else {
            buildGesturePage(contentContainer);
        }
        refreshStatus();
    }

    private GradientDrawable roundedBg(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable transparentBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        return d;
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
     * 开关没开时给一句明确提示。
     *
     * 【为什么需要它】
     * 关闭开关后，{@link #ensureServiceRunning()} 会直接返回（这是有意的），
     * 于是所有依赖本地接口的操作都会以「连接失败」收场 ——
     * 用户看到的是网络错误，根本想不到是自己在设置里关掉了服务。
     * 这种「静默失败」最难自查，所以入口处一律先拦一次、说清原因。
     *
     * @return true 表示服务可用、可以继续；false 表示已提示并应中止。
     */
    private boolean warnIfOcrDisabled() {
        if (OCRService.isEnabled(this)) {
            return true;
        }
        toast("OCR 服务已在设置里关闭 —— 先打开「启用 OCR 服务」");
        return false;
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
                tvStatusText.setTextColor(C_TEXT_SUB);
            } else {
                tvStatusText.setText(ready ? "服务已就绪" : "正在加载模型…");
                tvStatusText.setTextColor(ready ? C_TEXT : C_TEXT_SUB);
            }
        }
        if (tvStatusSub != null) {
            // 关闭时不能继续显示接口地址：那个端口已经没人监听，
            // 用户照着它去调只会拿到「连接被拒」，还以为是服务坏了。
            tvStatusSub.setText(enabled ? LOCAL_URL : "接口未监听");
        }
        if (tvBatteryText != null) {
            boolean ok = isIgnoringBatteryOptimizations();
            tvBatteryText.setText(ok ? "已加入电池优化白名单" : "未加入白名单，后台可能被冻结");
            tvBatteryText.setTextColor(ok ? C_TEXT : C_WARN);
            if (batteryCard != null) {
                batteryCard.setBackground(borderedBg(C_SURFACE, ok ? C_DIVIDER : 0x33F79009, 14));
            }
        }
    }

    private View statusCard(String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setElevation(dp(1));

        TextView dot = new TextView(this);
        dot.setBackgroundResource(R.drawable.dot_ok);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotLp.setMarginEnd(dp(12));
        card.addView(dot, dotLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextSize(14);
        t1.setTypeface(null, Typeface.BOLD);
        t1.setTextColor(C_TEXT);
        col.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(subtitle);
        t2.setTextSize(11.5f);
        t2.setTextColor(C_TEXT_SUB);
        col.addView(t2);

        card.addView(col);
        return card;
    }

    // ============================================================
    //  滑动触发页（入口）
    // ============================================================
    /**
     * 「滑动触发」页：只做入口与状态展示，细节交给 {@link EdgeGestureActivity}。
     *
     * 放在主界面而不是藏进二级菜单，是因为这个功能需要「随手开随手关」：
     * 手势引擎开着时会常驻一个悬浮窗，用户得能一眼看到它是否在运行。
     */
    private void buildGesturePage(ViewGroup parent) {
        // ---------- 状态卡 ----------
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setElevation(dp(1));

        TextView title = new TextView(this);
        title.setText("边缘滑动手势");
        title.setTextSize(15f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(C_TEXT);
        card.addView(title);

        tvGestureState = new TextView(this);
        tvGestureState.setTextSize(12.5f);
        tvGestureState.setTextColor(C_TEXT_SUB);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(6);
        card.addView(tvGestureState, stLp);

        TextView desc = new TextView(this);
        desc.setText("在屏幕边缘放一个可拖动的滑动手柄，手指从手柄上划过设定距离就"
                + "立刻触发（滑过即响应，不必抬手）。支持上/下/左/右加四个斜向"
                + "共 8 个方向，每个方向的触发距离都能单独调，也可以让整条边缘都可滑动。");
        desc.setTextSize(12.5f);
        desc.setTextColor(C_HINT);
        desc.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(10);
        card.addView(desc, descLp);

        parent.addView(card, cardLp());

        // ---------- 两个入口按钮 ----------
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button btnOpen = secondaryButton("打开手势设置", v -> EdgeGestureActivity.open(this));
        Button btnActive = secondaryButton("去启用", v -> {
            // 没开总开关就直接开，已经开了就跳进设置页（用户大概是想调参数）
            if (!EdgeGesturePrefs.isEnabled(this)) {
                EdgeGesturePrefs.setEnabled(this, true);
                EdgeGestureService.sync(this);
                toast("已启用边缘滑动触发");
                refreshGestureState();
            } else {
                EdgeGestureActivity.open(this);
            }
        });
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        bLp.topMargin = dp(12);
        bLp.rightMargin = dp(5);
        row.addView(btnOpen, bLp);
        LinearLayout.LayoutParams bLp2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        bLp2.topMargin = dp(12);
        bLp2.leftMargin = dp(5);
        row.addView(btnActive, bLp2);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 滑动菜单 ----------
        // 菜单是「手势的产物」，跟手柄、触发距离是一回事，所以放在这一页；
        // 早先挂在「手势设置」二级页里，得点进去才找得到。
        buildMenuCard(parent);

        // ---------- 和宏怎么配合 ----------
        addSectionTitle(parent, "怎么让宏收到手势");
        LinearLayout tip = new LinearLayout(this);
        tip.setOrientation(LinearLayout.VERTICAL);
        tip.setBackground(borderedBg(C_BRAND_SOFT, 0x00000000, 14));
        tip.setPadding(dp(16), dp(14), dp(16), dp(14));
        addTipLine(tip, "插件方式",
                "MacroDroid → 触发器 → 「小孙OCR · 边缘滑动事件」，"
                        + "选好方向即可；方向不符的宏不会被触发。"
                        + "滑出菜单选中后，结果会以菜单变量的形式交给宏，\n"
                        + "变量名叫什么由你在 MacroDroid 里自己定。");
        parent.addView(tip, cardLp());

        refreshGestureState();
    }

    /**
     * 「滑动菜单」入口卡片。
     *
     * <p>菜单是「全局一套」的 —— 任何一个手柄滑过阈值都弹同一份菜单，
     * 项序号也从同一套列表算起。所以它放在「滑动触发」页，和手柄、
     * 触发距离并列，而不是塞进某个手柄的编辑页（那样会让人以为
     * 每个手柄能各配一菜单）。
     */
    private void buildMenuCard(ViewGroup parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setElevation(dp(1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("滑动菜单");
        t.setTextSize(14.5f);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(C_TEXT);
        col.addView(t);
        TextView sub = new TextView(this);
        sub.setTextSize(11.5f);
        sub.setTextColor(C_HINT);
        sub.setLineSpacing(0f, 1.35f);
        tvMenuSub = sub;
        refreshMenuCard();
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(3);
        col.addView(sub, sl);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(C_HINT);
        arrow.setPadding(dp(8), 0, 0, 0);
        row.addView(arrow);
        row.setOnClickListener(v -> EdgeGestureMenuActivity.open(this));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(card, cardLp());
    }

    /**
     * 刷新「滑动菜单」卡片的状态文案。
     *
     * 开关与菜单项都由 {@link EdgeGestureMenuActivity} 改，
     * 本页只负责如实显示；所以每次回到前台都要重读一次 Prefs。
     *
     * tvMenuSub 为 null 时（没打开「滑动触发」页 / 页面重建中）安全跳过，
     * 与 {@link #refreshGestureState()} 的写法一致。
     */
    private void refreshMenuCard() {
        if (tvMenuSub == null) {
            return;
        }
        boolean on = EdgeGesturePrefs.isMenuEnabled(this);
        int n = EdgeGesturePrefs.getMenuItems(this).size();
        if (!on) {
            tvMenuSub.setText("未启用 · 现在是「滑过距离立即触发」");
        } else if (n == 0) {
            tvMenuSub.setText("已启用，但一项都没配 —— 先添加菜单项");
        } else {
            tvMenuSub.setText("已启用 · " + n + " 项 · 滑出后不抬手就能选");
        }
    }

    /** 方块提示卡里的一行（标题 + 说明）。 */
    private void addTipLine(ViewGroup parent, String head, String body) {
        TextView h = new TextView(this);
        h.setText(head);
        h.setTextSize(12.5f);
        h.setTypeface(null, Typeface.BOLD);
        h.setTextColor(C_BRAND_DARK);
        if (parent.getChildCount() > 0) {
            h.setPadding(0, dp(10), 0, 0);
        }
        parent.addView(h);

        TextView b = new TextView(this);
        b.setText(body);
        b.setTextSize(12f);
        b.setTextColor(C_TEXT_SUB);
        b.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        parent.addView(b, lp);
    }

    /** 刷新「滑动触发」页的状态文案（没打开这一页时 tvGestureState 为 null，安全跳过）。 */
    private void refreshGestureState() {
        if (tvGestureState == null) {
            return;
        }
        boolean on = EdgeGesturePrefs.isEnabled(this);
        boolean overlay = android.provider.Settings.canDrawOverlays(this);
        String s;
        int color;
        if (!on) {
            s = "已关闭 · 手势引擎未运行";
            color = C_TEXT_SUB;
        } else if (!overlay) {
            s = "已开启，但缺少「悬浮窗权限」，手柄无法显示";
            color = C_WARN;
        } else if (EdgeGesturePrefs.gsRunning) {
            s = "运行中 · 当前 " + EdgeGesturePrefs.gsHandleCount + " 个手柄";
            color = C_OK;
        } else {
            s = "已开启，服务正在启动…";
            color = C_TEXT_SUB;
            // 状态还没就绪，排一条自收敛的重试链，稍后自己刷新。
            scheduleGestureRecheck(0);
        }
        tvGestureState.setText(s);
        tvGestureState.setTextColor(color);
        if (EdgeGesturePrefs.gsRunning) {
            // 已经跑到「运行中」，就不必再重试了。
            cancelGestureRecheck();
        }
    }

    /**
     * 排一条「稍后重新读一次手势状态」的延迟任务。
     *
     * 背景：gsRunning 是服务的静态标志，只有 onStartCommand 真正执行到那一行才为 true。
     * 用户从最近任务里划掉应用时，Activity 会走 onResume → refreshGestureState，
     * 而此刻服务往往还在重建途中，于是界面停在「服务正在启动…」。
     * 这里让它在 6 秒内自己多读几次，服务一就绪界面就自动变成「运行中」。
     *
     * 写法上刻意把计数放在任务自己身上（不用成员变量）：
     * 每条链各数各的，永远不会互相踩，也不必担心重复排队造成计数错乱。
     *
     * @param delays 已经延迟过几次
     */
    private void scheduleGestureRecheck(final int delays) {
        if (tvGestureState == null) {
            return;
        }
        // 已经排了一条链就别再排第二条（每条链都排在同一个 Handler 上，
        // 靠这个标志保证同一时刻最多只有一条链在跑）。
        if (gestureRecheckPending) {
            return;
        }
        if (delays >= GESTURE_RECHECK_MAX) {
            return;
        }
        gestureRecheckPending = true;
        if (gestureRecheckHandler == null) {
            gestureRecheckHandler = new android.os.Handler(getMainLooper());
        }
        gestureRecheckHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                gestureRecheckPending = false;
                if (tvGestureState == null) {
                    return;
                }
                if (EdgeGesturePrefs.gsRunning) {
                    // 服务就绪了：复用它把文案与颜色一起改对。
                    refreshGestureState();
                    return;
                }
                if (!EdgeGesturePrefs.isEnabled(MainActivity.this)) {
                    // 用户中途关了总开关，不必再等。
                    refreshGestureState();
                    return;
                }
                if (!android.provider.Settings.canDrawOverlays(MainActivity.this)) {
                    // 缺悬浮窗权限，服务起不来，重试也不会变，交给 refreshGestureState 报状态。
                    refreshGestureState();
                    return;
                }
                // 还是没就绪，但说明服务确实在启动途中，我们主动再拉它一次，
                // 避免它卡在「已请求但一直没走 onStartCommand」的状态。
                EdgeGestureService.sync(MainActivity.this);
                scheduleGestureRecheck(delays + 1);
            }
        }, GESTURE_RECHECK_INTERVAL);
    }

    /** 手势状态已确定时，取消还在排队的重试，省掉无谓的唤醒。 */
    private void cancelGestureRecheck() {
        gestureRecheckPending = false;
        if (gestureRecheckHandler != null) {
            gestureRecheckHandler.removeCallbacksAndMessages(null);
        }
    }

    /**
     * 「启用 OCR 服务」开关卡片。
     *
     * 【为什么单独做成一张卡而不是塞进状态卡】
     * 状态卡说的是「现在是什么情况」，这张卡说的是「要不要开」。
     * 混在一起点击区域会跟状态文字打架（用户点状态也会误触开关）。
     *
     * 【关掉之后影响什么】
     * 只影响 HTTP 接口这条链路：接口不再监听、模型不再常驻、开机不再自启。
     * 边缘滑动手势是另一个服务，不受影响 —— 这正是本开关要服务的人群：
     * 只想用手势触发宏、不想养着一个 OCR 服务的人。
     */
    private View buildOcrSwitchCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setElevation(dp(1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("启用 OCR 服务");
        t.setTextSize(14.5f);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(C_TEXT);
        col.addView(t);
        TextView sub = new TextView(this);
        sub.setTextSize(11.5f);
        sub.setTextColor(C_TEXT_SUB);
        sub.setLineSpacing(dp(3), 1f);
        sub.setText("关闭后接口不再监听，也不开机自启；\n"
                + "「边缘滑动手势」是另一个服务，不受影响。");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(4);
        col.addView(sub, sl);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(OCRService.isEnabled(this));
        sw.setOnCheckedChangeListener((b, checked) -> {
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
        row.addView(sw);
        card.addView(row);
        return card;
    }

    /**
     * 「从最近任务隐藏」开关。
     *
     * ════════════════════════════════════════════════════════════
     * 【这个开关在干什么】
     * ════════════════════════════════════════════════════════════
     * 打开后，多任务（最近任务）界面里看不到本应用这张卡片。
     * 适合「平时只用手势触发、不想让它占着多任务」的用法。
     *
     * ════════════════════════════════════════════════════════════
     * 【不影响什么】
     * ════════════════════════════════════════════════════════════
     *  · 前台服务照常运行，通知照常在；
     *  · 边缘滑动手势照常可用；
     *  · 只是多任务里那张卡片没了，想进来从桌面图标点。
     */
    private View buildHideRecentsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setElevation(dp(1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("从最近任务隐藏");
        t.setTextSize(14f);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(C_TEXT);
        col.addView(t);
        TextView sub = new TextView(this);
        sub.setTextSize(11.5f);
        sub.setTextColor(C_HINT);
        sub.setLineSpacing(0f, 1.35f);
        sub.setText("开启后多任务界面里不显示本应用。\n"
                + "服务与手势不受影响，从桌面图标照常进入。");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(4);
        col.addView(sub, sl);
        card.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch sw = new android.widget.Switch(this);
        suppressRecentsToggle = true;
        sw.setChecked(OCRService.isHiddenFromRecents(this));
        suppressRecentsToggle = false;
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (suppressRecentsToggle) return;   // 初始化时别误触发
            OCRService.setHiddenFromRecents(this, checked);
            // 就地生效，界面不重载：切到多任务立刻就能看到结果。
            OCRService.applyRecents(this);
            toast(checked ? "已从最近任务隐藏" : "已恢复显示在最近任务");
        });
        card.addView(sw);
        return card;
    }





    // ============================================================
    //  OCR设置页
    // ============================================================
    /**
     * 「OCR设置」页 = 原「在线调试」页 + 原「接口文档」页里的总开关。
     *
     * 【合并理由】宏现在直接在 MacroDroid 里选插件配置，没人再照着
     * 接口文档手写 HTTP 请求了。文档页删掉，只把仍然有用的
     * 「启用 OCR 服务」开关搬过来 —— 它本来就是「要不要开这个服务」，
     * 和这一页的状态、保活、截屏、预览是同一件事。
     */

    private void buildOcrSettingsPage(ViewGroup parent) {
        // 服务状态
        LinearLayout card = (LinearLayout) statusCard("服务已就绪", LOCAL_URL);
        parent.addView(card, cardLp());
        tvStatusDot = (TextView) card.getChildAt(0);
        tvStatusText = (TextView) ((LinearLayout) card.getChildAt(1)).getChildAt(0);
        // 副标题＝接口地址；关掉开关后 refreshStatus() 会把它显示成「接口未监听」
        tvStatusSub = (TextView) ((LinearLayout) card.getChildAt(1)).getChildAt(1);
        // 总开关：自「接口文档」页并入
        addCard(parent, buildOcrSwitchCard());

        // 电池优化
        LinearLayout bat = new LinearLayout(this);
        bat.setOrientation(LinearLayout.HORIZONTAL);
        bat.setGravity(Gravity.CENTER_VERTICAL);
        bat.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        bat.setPadding(dp(16), dp(14), dp(16), dp(14));
        bat.setElevation(dp(1));
        bat.setOnClickListener(v -> requestIgnoreBatteryOptimizations());

        TextView batIcon = new TextView(this);
        batIcon.setText("⚡");
        batIcon.setTextSize(16);
        LinearLayout.LayoutParams biLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        biLp.setMarginEnd(dp(12));
        bat.addView(batIcon, biLp);

        LinearLayout batCol = new LinearLayout(this);
        batCol.setOrientation(LinearLayout.VERTICAL);
        TextView bt1 = new TextView(this);
        bt1.setText("后台保活");
        bt1.setTextSize(14);
        bt1.setTypeface(null, Typeface.BOLD);
        bt1.setTextColor(C_TEXT);
        batCol.addView(bt1);
        TextView bt2 = new TextView(this);
        bt2.setText("点击设置电池优化白名单");
        bt2.setTextSize(11.5f);
        batCol.addView(bt2);
        tvBatteryText = bt2;
        bat.addView(batCol);
        batteryCard = bat;
        parent.addView(bat, cardLp());

        LinearLayout keepRow = new LinearLayout(this);
        keepRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams krLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        krLp.topMargin = dp(10);
        keepRow.setLayoutParams(krLp);
        keepRow.addView(secondaryButton("电池优化白名单",
                v -> requestIgnoreBatteryOptimizations()), weightLp());
        keepRow.addView(secondaryButton("应用设置", v -> openAppSettings()), weightLp());
        parent.addView(keepRow);

        // ---------- 从最近任务隐藏 ----------
        addCard(parent, buildHideRecentsCard());

        // ---------- 截屏识别 ----------
        addSectionTitle(parent, "屏幕截屏识别");

        LinearLayout capCard = new LinearLayout(this);
        capCard.setOrientation(LinearLayout.VERTICAL);
        capCard.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        capCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        capCard.setElevation(dp(1));
        capCard.setId(android.R.id.widget_frame);

        TextView capTitle = new TextView(this);
        capTitle.setText("截屏识别");
        capTitle.setTextSize(14);
        capTitle.setTypeface(null, Typeface.BOLD);
        capTitle.setTextColor(C_TEXT);
        capCard.addView(capTitle);

        capInfo = new TextView(this);
        capInfo.setTextSize(11.5f);
        capInfo.setPadding(0, dp(4), 0, 0);
        capCard.addView(capInfo);

        LinearLayout capRow = new LinearLayout(this);
        capRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams capRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capRowLp.topMargin = dp(10);
        capRow.setLayoutParams(capRowLp);
        capRow.addView(secondaryButton("检测 root 截屏", v -> probeRootCapture()), weightLp());
        capRow.addView(secondaryButton("截屏并识别", v -> captureAndOcr()), weightLp());
        capCard.addView(capRow);

        LinearLayout capRow2 = new LinearLayout(this);
        capRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams capRow2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        capRow2Lp.topMargin = dp(4);
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
        capNote.setText("① 无障碍截图（推荐）：点开后勾选「" + A11Y_LABEL + "」，"
                + "之后随时可截、不弹框、不需要 root。\n"
                + "② root 截屏：设备有 root 时自动启用（screencap），同样免授权。\n"
                + "③ 截屏授权：无 root 时的兜底，每次重启都要手点一次录屏授权。\n\n"
                + "三者按上面的顺序自动挑选，任一可用即可，不必都开。\n"
                + "「悬浮窗权限」建议一并开启：MacroDroid 在后台触发插件时，"
                + "App 需要它才能自动弹出截屏授权框（否则只能先手动打开 App 授权）。");
        capNote.setTextSize(11);
        capNote.setTextColor(C_HINT);
        capNote.setLineSpacing(0f, 1.3f);
        capNote.setPadding(0, dp(8), 0, 0);
        capCard.addView(capNote);

        parent.addView(capCard, cardLp());
        updateCaptureInfo();

        // ---------- 摄像头实时识别 ----------
        // 放在「截屏识别」之后、「图片预览」之前：
        // 它是另一条「输入源」，和「从文件选图」是同类，挨着放最符合阅读顺序。
        addSectionTitle(parent, "摄像头实时识别");

        LinearLayout camCard = new LinearLayout(this);
        camCard.setOrientation(LinearLayout.VERTICAL);
        camCard.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        camCard.setElevation(dp(1));
        camCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView camTitle = new TextView(this);
        camTitle.setText("对准数字，自动识别");
        camTitle.setTextSize(14.5f);
        camTitle.setTypeface(null, Typeface.BOLD);
        camTitle.setTextColor(C_TEXT);
        camCard.addView(camTitle);

        TextView camDesc = new TextView(this);
        camDesc.setText("调起后置摄像头，连续识别画面里的数字（小票、电表、车牌都行）；"
                + "也可以点页内「相册」选一张已有图片识别，识别后会把原图显示出来、"
                + "并在含数字的行上描绿框、框里写着这一行抽到的数字。\n"
                + "识别在本机完成，不联网、不上传。结果只保留数字，点一下就能复制。");
        camDesc.setTextSize(12f);
        camDesc.setTextColor(C_TEXT_SUB);
        camDesc.setLineSpacing(0f, 1.35f);
        camDesc.setPadding(0, dp(6), 0, 0);
        camCard.addView(camDesc);

        TextView camWarn = new TextView(this);
        camWarn.setText("⚠ 实时识别比较耗电，长时间使用机身会发热；用完记得退出这一页。");
        camWarn.setTextSize(11f);
        camWarn.setTextColor(C_HINT);
        camWarn.setLineSpacing(0f, 1.3f);
        camWarn.setPadding(0, dp(8), 0, 0);
        camCard.addView(camWarn);

        Button btnCamera = primaryButton("打开摄像头识别");
        LinearLayout.LayoutParams camBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        camBtnLp.topMargin = dp(12);
        camCard.addView(btnCamera, camBtnLp);
        btnCamera.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, CameraOcrActivity.class));
            } catch (Throwable t) {
                toast("打不开摄像头页面：" + t.getClass().getSimpleName());
            }
        });
        parent.addView(camCard, cardLp());

        // ---------- 图片预览框 ----------
        addSectionTitle(parent, "图片预览");

        LinearLayout previewCard = new LinearLayout(this);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        previewCard.setElevation(dp(1));
        previewCard.setGravity(Gravity.CENTER);

        // 预览区（固定高度，居中显示）
        android.widget.FrameLayout previewBox = new android.widget.FrameLayout(this);
        previewBox.setBackgroundColor(0xFFEFF1F6);
        previewBox.setMinimumHeight(dp(200));

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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        previewCard.addView(previewBox, boxLp);

        tvPreviewInfo = new TextView(this);
        tvPreviewInfo.setText("未选择图片");
        tvPreviewInfo.setTextSize(11.5f);
        tvPreviewInfo.setTextColor(C_TEXT_SUB);
        tvPreviewInfo.setPadding(dp(14), dp(10), dp(14), 0);
        previewCard.addView(tvPreviewInfo);

        LinearLayout previewBtns = new LinearLayout(this);
        previewBtns.setOrientation(LinearLayout.HORIZONTAL);
        previewBtns.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewBtns.addView(secondaryButton("选择图片", v -> pickImage()), weightLp());
        previewBtns.addView(secondaryButton("重新加载", v -> loadPreview(currentPath)), weightLp());
        previewBtns.addView(secondaryButton("清空", v -> clearPreview()), weightLp());
        previewCard.addView(previewBtns);

        parent.addView(previewCard, cardLp());

        // ---------- 图片路径 ----------
        addSectionTitle(parent, "图片路径");
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
        parent.addView(etPath, inputLp());
        currentPath = "/sdcard/test.png";

        // ---------- 识别格式 ----------
        addSectionTitle(parent, "识别格式");
        formatSpinner = new Spinner(this);
        String[] modes = {"1 · 纯文本", "2 · Json 数组（含坐标）", "3 · 详细 Json（含矩形）"};
        formatSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, modes));
        formatSpinner.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 12));
        formatSpinner.setPadding(dp(12), dp(10), dp(12), dp(10));
        parent.addView(formatSpinner, inputLp());

        // ---------- 模型档位 ----------
        addSectionTitle(parent, "模型档位");
        tierSpinner = new Spinner(this);
        tierSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, TIER_LABELS));
        tierSpinner.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 12));
        tierSpinner.setPadding(dp(12), dp(10), dp(12), dp(10));
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
        parent.addView(tierSpinner, inputLp());

        tierInfo = new TextView(this);
        tierInfo.setTextSize(11.5f);
        tierInfo.setTextColor(C_TEXT_SUB);
        tierInfo.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams tierLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tierLp.topMargin = dp(6);
        parent.addView(tierInfo, tierLp);
        updateTierInfo();

        // ---------- 开始识别 ----------
        Button btnOcr = primaryButton("开始识别");
        LinearLayout.LayoutParams ocrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        ocrLp.topMargin = dp(18);
        parent.addView(btnOcr, ocrLp);

        // ---------- 识别结果 ----------
        addSectionTitle(parent, "识别结果");
        tvResult = new TextView(this);
        tvResult.setText("等待识别…");
        tvResult.setTextSize(12);
        tvResult.setTypeface(Typeface.MONOSPACE);
        tvResult.setTextColor(C_CODE_TEXT);
        tvResult.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable resBg = new GradientDrawable();
        resBg.setColor(C_CODE_BG);
        resBg.setCornerRadius(dp(14));
        tvResult.setBackground(resBg);
        tvResult.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        tvResult.setMinLines(6);
        tvResult.setTextIsSelectable(true);
        LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resLp.topMargin = dp(4);
        parent.addView(tvResult, resLp);

        // ---------- 日志 ----------
        // 放在识别结果之后：出问题时用户才往下找，平时不碍事。
        addSectionTitle(parent, "日志与排障");
        addCard(parent, LogCard.build(this));

        // ---------- 事件 ----------
        btnOcr.setOnClickListener(v -> startOcr());

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

    /** 截屏后直接走本地接口识别。 */
    private void captureAndOcr() {
        // 开关没开时截屏识别同样走不通（服务不在，HTTP 直接失败）。
        if (!warnIfOcrDisabled()) {
            return;
        }
        // 先按服务的真实状态判断（root 可用时 captureReady 可能还没同步过来）
        boolean readyNow = OCRService.screenCaptureReady
                || OcrAccessibilityService.isReady();
        if (!readyNow && !captureReady) {
            // 有 root 但还没探测完时，只提示等待，不要弹授权框
            if (RootScreenCaptureManager.isRootAvailable()) {
                toast("正在初始化 root 截屏，请稍候再试");
                refreshCaptureMode();
            } else {
                toast("请先开启「无障碍截图」，或点「开启截屏授权」");
                openAccessibilitySettings();
            }
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
                // 说明已随实现更新：rapid 不再是 native RapidOCR，而是 ML Kit。
                detail = "ML Kit 文字识别（捆绑版，英文 + 中文）：模型随 APK 打包，"
                        + "无需 Google Play 服务、不联网下载；中英混排与倾斜文字表现好，"
                        + "输出行级包围盒与置信度。";
                break;
            case OCRService.TIER_SMALL:
                detail = "高精度档：字典 18708 字符，保留更多标点与符号。";
                break;
            default:
                detail = "轻量档：字典 6904 字符，速度最快、占用最小。";
                break;
        }
        tierInfo.setText("当前：" + cur + "\n" + detail
                + "\n外部调用可传 tier 字段切换（tiny / small / rapid），"
                + "切换后所有请求都用新档位。");
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

    /** 展示识别结果。type=1 只显示纯文字；type=2/3 原样显示服务端返回内容。 */
    private void showResult(String result) {
        if (tvResult == null) {
            return;
        }
        if (result == null) {
            tvResult.setText("无返回内容");
            return;
        }

        int type = formatSpinner == null ? 1 : formatSpinner.getSelectedItemPosition() + 1;

        // 只有纯文本模式需要从 {"status":200,"data":"文字"} 中取出文字
        if (type == 1) {
            try {
                JsonElement el = JsonParser.parseString(result);
                if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    JsonElement dataEl = obj.get("data");
                    if (dataEl != null && dataEl.isJsonPrimitive()) {
                        tvResult.setText(dataEl.getAsString());
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // type=2/3 以及其它情况：原样显示服务端返回内容
        tvResult.setText(result);
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

            // ===== 坐标换算（关键修复）=====
            // 识别返回的 x/y/w/h 是相对「送入引擎的位图」的像素值，
            // 而 target 是预览用的缩略图（decodeSampled 会按 inSampleSize 降采样）。
            // 二者分辨率不同，直接画会导致框整体放大、往右下偏移，
            // 再被裁剪贴边——这就是"画到缩略图上不准"的原因。
            int srcW = OCRService.lastOcrImageWidth;
            int srcH = OCRService.lastOcrImageHeight;
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
                toast("请找到 小孙OCR 并设为「不限制」");
            } catch (Exception e2) {
                toast("请手动到 设置-电池 关闭本应用限制");
            }
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开应用设置");
        }
    }

    // ============================================================
    //  通用 UI
    // ============================================================

    private void addSectionTitle(ViewGroup parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(C_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(10);
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    private void addCard(ViewGroup parent, View card) {
        parent.addView(card, cardLp());
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        return lp;
    }

    private GradientDrawable borderedBg(int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1f), stroke);
        return d;
    }

    private void styleInput(EditText et) {
        et.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 12));
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setTextSize(13.5f);
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_HINT);
    }

    private LinearLayout.LayoutParams inputLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        return lp;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
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
        base.setCornerRadius(dp(12));
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
        base.setCornerRadius(dp(12));
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x334F6BED), base, null);
        btn.setBackground(ripple);
        btn.setOnClickListener(listener);
        return btn;
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
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
}