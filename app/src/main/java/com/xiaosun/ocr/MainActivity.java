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

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 主界面。
 *
 * 打开 App 自动拉起本地 OCR 服务（127.0.0.1:1666）。
 * 两个页签：接口文档 / 在线调试
 *
 * 调试页：选中图片直接在预览框显示，识别后把检测框画在预览图上，
 * 结果区按所选格式显示（纯文本时只输出文字本身）。
 */
public class MainActivity extends AppCompatActivity {

    private static final String APP_VERSION = "v2.0.1";
    private static final String LOCAL_URL = "http://127.0.0.1:1666";
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

    private final String[] tabs = {"接口文档", "在线调试"};

    private LinearLayout contentContainer;
    private final View[] tabViews = new View[2];
    private final TextView[] tabTexts = new TextView[2];

    private TextView tvStatusDot;
    private TextView tvStatusText;
    private TextView tvBatteryText;
    private View batteryCard;
    private boolean serviceStarting = false;

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
            "tiny · 轻量（最快）",
            "small · 高精度",
            "rapid · RapidOCR（方向纠正）",
    };

    /** 屏幕截图授权是否已就绪。 */
    private boolean captureReady = false;
    private TextView capInfo;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
                boolean ready = OCRService.isScreenCaptureReady;
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
            boolean using = OCRService.isScreenCaptureReady && "root".equals(OCRService.captureMode);
            runOnUiThread(() -> {
                captureReady = OCRService.isScreenCaptureReady;
                updateCaptureInfo();
                if (using) {
                    toast("root 截屏可用，已免去截屏授权");
                } else if (rootOk) {
                    toast("检测到 root，但截屏初始化失败，请查看日志");
                } else {
                    toast("未检测到 root，请改用「开启截屏授权」");
                }
            });
        }, "njocr-root-probe").start();
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

        if (index == 0) {
            buildDocPage(contentContainer);
        } else {
            buildDebugPage(contentContainer);
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

    private void refreshStatus() {
        boolean ready = OCRService.isRunning;
        if (tvStatusDot != null) {
            tvStatusDot.setBackgroundResource(ready ? R.drawable.dot_ok : R.drawable.dot_warn);
        }
        if (tvStatusText != null) {
            tvStatusText.setText(ready ? "服务已就绪" : "正在加载模型…");
            tvStatusText.setTextColor(ready ? C_TEXT : C_TEXT_SUB);
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
    //  接口文档页
    // ============================================================

    private void buildDocPage(ViewGroup parent) {
        LinearLayout card = (LinearLayout) statusCard("服务已就绪", LOCAL_URL);
        parent.addView(card, cardLp());
        tvStatusDot = (TextView) card.getChildAt(0);
        tvStatusText = (TextView) ((LinearLayout) card.getChildAt(1)).getChildAt(0);

        addCard(parent, buildParamCard());

        addSectionTitle(parent, "调用示例");

        addCodeCard(parent, "截屏识别 · 不传图片", "// 服务端自动截取当前屏幕并识别（推荐）\n"
                + "var res = http.post(\"http://127.0.0.1:1666\",\n"
                + "    { \"type\": \"2\" });\n"
                + "log(res.body.string());\n\n"
                + "// 有 root：直接可用，无需任何授权\n"
                + "// 无 root：需在 App 内点一次「开启截屏授权」");

        addCodeCard(parent, "Auto.js · 传路径", "// 截屏保存\nvar path = \"/sdcard/test.png\";\n"
                + "if (!requestScreenCapture()) { toast(\"无权限\"); exit(); }\n"
                + "sleep(1000);\ncaptureScreen(path);\n\n"
                + "// 调用 OCR\nvar res = http.post(\"http://127.0.0.1:1666\",\n"
                + "    { \"path\": path, \"type\": \"1\" });\n"
                + "toast(res.body.string());");

        addCodeCard(parent, "AutoGo", "-- AutoGo 调用示例\n"
                + "import \"http\"\nimport \"file\"\n\n"
                + "local path = \"/sdcard/test.png\"\n"
                + "snapshot(path, 0, 0, -1, -1)\n\n"
                + "local url = \"http://127.0.0.1:1666\"\n"
                + "local postData = \"path=\" .. path .. \"&type=1\"\n"
                + "local res = http.post(url, postData)\nprint(res)");

        addCodeCard(parent, "Java", "URL url = new URL(\"http://127.0.0.1:1666\");\n"
                + "HttpURLConnection conn = (HttpURLConnection) url.openConnection();\n"
                + "conn.setRequestMethod(\"POST\");\n"
                + "conn.setRequestProperty(\"Content-Type\", \"application/json\");\n"
                + "conn.setDoOutput(true);\n\n"
                + "String body = \"{\\\"path\\\":\\\"/sdcard/test.png\\\",\\\"type\\\":1}\";\n"
                + "conn.getOutputStream().write(body.getBytes(\"UTF-8\"));\n\n"
                + "// 读取 conn.getInputStream() 即为识别结果 JSON");

        addCodeCard(parent, "Python", "import requests\n\n"
                + "r = requests.post(\"http://127.0.0.1:1666\",\n"
                + "                  json={\"path\": \"/sdcard/test.png\", \"type\": 1})\n"
                + "print(r.text)");

        addSectionTitle(parent, "命令行启动服务");
        addCodeCard(parent, "adb / shell",
                "am start-foreground-service com.xiaosun.ocr/.OCRService");
    }

    private View buildParamCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setElevation(dp(1));

        addParamRow(card, "path", "图片绝对路径（需文件权限）");
        addParamRow(card, "type", "1 = 纯文本  ·  2 = Json 数组(含坐标)  ·  3 = 详细 Json(含矩形)");
        addParamRow(card, "tier", "可选：tiny（轻量，默认）/ small（高精度）/ rapid（RapidOCR）。切换后持续生效");

        TextView note = new TextView(this);
        note.setText("不传 path 时，服务会自动截取当前屏幕后识别。\n"
                + "截屏无需授权：有 root 走 screencap，否则先在「在线调试」页点一次「开启截屏授权」。\n"
                + "支持 JSON Body / form 表单 / query 三种提交方式");
        note.setTextSize(11.5f);
        note.setTextColor(C_HINT);
        note.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        note.setLayoutParams(lp);
        card.addView(note);

        return card;
    }

    private void addParamRow(LinearLayout parent, String key, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        row.setLayoutParams(rowLp);

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextSize(12.5f);
        k.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        k.setTextColor(C_BRAND);
        LinearLayout.LayoutParams kLp = new LinearLayout.LayoutParams(
                dp(66), ViewGroup.LayoutParams.WRAP_CONTENT);
        k.setLayoutParams(kLp);
        row.addView(k);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12.5f);
        d.setTextColor(C_TEXT_SUB);
        d.setLineSpacing(0f, 1.25f);
        row.addView(d, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
    }

    private void addCodeCard(ViewGroup parent, String label, String code) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CODE_BG);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setElevation(dp(1));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(12), dp(16), 0);

        TextView dot = new TextView(this);
        dot.setBackgroundResource(R.drawable.dot_warn);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.setMarginEnd(dp(8));
        head.addView(dot, dotLp);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(11.5f);
        labelView.setTextColor(0xFF8B93A6);
        head.addView(labelView);

        card.addView(head);

        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(C_CODE_TEXT);
        tv.setPadding(dp(16), dp(10), dp(16), dp(16));
        tv.setLineSpacing(dp(3), 1f);
        tv.setTextIsSelectable(true);
        tv.setHorizontallyScrolling(true);

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(tv);
        card.addView(hs);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        parent.addView(card, lp);
    }

    // ============================================================
    //  在线调试页
    // ============================================================

    private void buildDebugPage(ViewGroup parent) {
        // 服务状态
        LinearLayout card = (LinearLayout) statusCard("服务已就绪", LOCAL_URL);
        parent.addView(card, cardLp());
        tvStatusDot = (TextView) card.getChildAt(0);
        tvStatusText = (TextView) ((LinearLayout) card.getChildAt(1)).getChildAt(0);

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
        capRow2.addView(secondaryButton("开启截屏授权（无 root 时用）",
                v -> requestScreenCapture()), weightLp());
        capCard.addView(capRow2);

        TextView capNote = new TextView(this);
        capNote.setText("有 root 的设备会自动启用 root 截屏（screencap），"
                + "无需任何授权、也不会弹录屏框；\n无 root 时才需要点击「开启截屏授权」。");
        capNote.setTextSize(11);
        capNote.setTextColor(C_HINT);
        capNote.setLineSpacing(0f, 1.3f);
        capNote.setPadding(0, dp(8), 0, 0);
        capCard.addView(capNote);

        parent.addView(capCard, cardLp());
        updateCaptureInfo();

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
        boolean root = "root".equals(OCRService.captureMode);
        boolean mp = "mediaprojection".equals(OCRService.captureMode);
        if (root) {
            capInfo.setText("● root 截屏已启用，无需授权，可直接截屏识别");
            capInfo.setTextColor(C_OK);
            captureReady = true;
        } else if (captureReady || mp) {
            capInfo.setText("● 已授权（MediaProjection），可直接截屏识别");
            capInfo.setTextColor(C_OK);
        } else {
            capInfo.setText("○ 未就绪：无 root 时请点击“开启截屏授权”\n"
                    + "（有 root 的设备会自动启用 root 截屏，免授权）");
            capInfo.setTextColor(C_WARN);
        }
    }

    /** 通过服务暴露的静态标志确认截屏是否真的就绪。 */
    private void probeCaptureReady() {
        captureReady = OCRService.isScreenCaptureReady;
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

    /** 截屏后直接走本地接口识别。 */
    private void captureAndOcr() {
        // 先按服务的真实状态判断（root 可用时 captureReady 可能还没同步过来）
        boolean readyNow = OCRService.isScreenCaptureReady;
        if (!readyNow && !captureReady) {
            // 有 root 但还没探测完时，只提示等待，不要弹授权框
            if (RootScreenCaptureManager.isRootAvailable()) {
                toast("正在初始化 root 截屏，请稍候再试");
                refreshCaptureMode();
            } else {
                toast("请先点击“开启截屏授权”");
                requestScreenCapture();
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
            String resp = null;
            try {
                // action=setTier：只切档位、不做识别
                resp = postJson("{\"tier\":\"" + tierName + "\",\"action\":\"setTier\"}");
            } catch (Exception ignored) {
            }

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
        }, "njocr-tier").start();
    }

    /** 发送一个 JSON body 到本地接口，返回响应文本（失败返回 null）。 */
    private String postJson(String json) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(LOCAL_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            out.write(json.getBytes("UTF-8"));
            out.flush();
            out.close();
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            is.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 刷新档位说明文字。 */
    private void updateTierInfo() {
        if (tierInfo == null) return;
        String cur = OCRService.tierName(OCRService.currentTier);
        if (tierSwitching) {
            tierInfo.setText("正在切换模型档位…（RapidOCR/small 首次加载需 1~3 秒）");
            return;
        }
        String detail;
        switch (OCRService.currentTier) {
            case OCRService.TIER_RAPID:
                detail = "RapidOCR：标准 DB 后处理（clipper）+ AngleNet 方向分类，"
                        + "能纠正倒置文字，并输出每块置信度。";
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
        HttpURLConnection conn = null;
        try {
            // path 为空时不传任何图片参数，由服务端自行截屏识别
            String json;
            if (path == null || path.length() == 0) {
                json = "{\"type\":" + type + "}";
            } else {
                json = "{\"path\":\"" + path.replace("\\", "\\\\") + "\",\"type\":" + type + "}";
            }

            conn = (HttpURLConnection) new URL(LOCAL_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            out.write(json.getBytes("UTF-8"));
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();

            StringBuilder sb = new StringBuilder();
            if (is != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
                is.close();
            }
            if (sb.length() == 0) {
                return "{\"status\":" + code + ",\"data\":\"无返回内容，服务可能仍在加载模型\"}";
            }
            return sb.toString();
        } catch (Exception e) {
            return "请求失败: " + e.getMessage()
                    + "\n\n服务可能仍在加载模型，请稍等几秒后重试。";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
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