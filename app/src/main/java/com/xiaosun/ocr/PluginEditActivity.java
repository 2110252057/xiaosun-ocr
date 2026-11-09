package com.xiaosun.ocr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import static com.xiaosun.ocr.Palette.*;
import static com.xiaosun.ocr.Ui.*;

/**
 * Locale/Tasker 插件的「配置界面」。
 *
 * 宿主（MacroDroid / Tasker）在用户点击插件时，
 * 会用 ACTION_EDIT_SETTING 拉起这个 Activity。
 *
 * ── 关于布局的坑（第一版踩过）──────────────────────────────
 * 第一版所有 addView 都没传 LayoutParams，且主题用的是 Material3 的
 * Dialog 主题（那是给 AlertDialog 的，窗口默认极窄），结果整个界面被挤成
 * 一条竖线、每个字都竖着排。
 *
 * 这一版的三个关键修正：
 *   1. 主题里设 windowMinWidthMajor/Minor = 92%（见 themes.xml）
 *   2. 每个控件都显式给 MATCH_PARENT 宽度 + 高度
 *   3. 根布局用 LayoutParams 撑满，ScrollView 设 fillViewport
 * ────────────────────────────────────────────────────────
 */
public class PluginEditActivity extends Activity {

    private Spinner sourceSpinner;
    private EditText pathInput;
    private LinearLayout pathGroup;
    private Spinner tierSpinner;
    private Spinner typeSpinner;
    private Spinner matchModeSpinner;
    private CheckBox joinNewline;
    private EditText matchInput;
    /** 手势事件配置区（仅触发器模式可见）。 */
    private LinearLayout gestureGroup;
    private Spinner gsDirSpinner;
    /** 「滑过之后干什么」下拉：跟随手柄 / 直触 / 菜单。 */
    private Spinner gsModeSpinner;
    /** 这条提示随方向与模式联动，比如「任意方向 + 直触」无法落盘。 */
    private TextView gsModeHint;
    private CheckBox gsStrict;
    /**
     * 宿主用什么 action 打开了本界面，决定这是「动作」还是「触发器」。
     *
     *   EDIT_SETTING      → 动作（Setting）：识别屏幕文字
     *   ACTION_EDIT_EVENT → 手势触发器（Tasker 事件）：等某个手势
     *
     * ★ 曾经的 EDIT_CONDITION（条件）分支已删除：条件与事件对用户来说
     *   是完全同一件事，而事件多一层防伪校验，留着条件只会让人在
     *   宿主的触发器列表里挑错。详见 PluginContract 里的协议说明。
     */
    private String pluginMode = PluginContract.MODE_SETTING;
    /** 截屏方式容器（选「指定图片路径」时整体隐藏）。 */
    private LinearLayout captureGroup;
    /** 截屏方式下拉：无障碍 / Root / 投屏授权。 */
    private Spinner captureSpinner;
    /** 截屏方式下方的说明行，实时反映该方式当前可不可用。 */
    private TextView captureHint;
    /** 根容器，供 markActionOnly 把控件同时登记进「动作专用」列表。 */
    private LinearLayout rootView;
    /** 把控件加进根容器，并登记为「只有动作模式才显示」。 */
    private void markActionOnly(View v, LinearLayout.LayoutParams lp) {
        addToRoot(v, lp);
        actionOnlyViews.add(v);
    }

    /**
     * 只加进根容器，不登记进「动作专用」列表。
     *
     * 给那种「可见性另有其主」的控件用：比如 pathGroup 由
     * 「图片来源」下拉框控制（选了"截取当前屏幕"就该藏起来），
     * 要是也被无脑置为 VISIBLE，切回动作模式时它会凭空冒出来。
     */
    private void addToRoot(View v, LinearLayout.LayoutParams lp) {
        if (lp == null) {
            rootView.addView(v);
        } else {
            rootView.addView(v, lp);
        }
    }
    /** 「指定文本」相关控件的容器，模式切换时整体隐藏。 */
    private LinearLayout matchGroup;
    private TextView varsNote;
    /** 各区域小节标题的容器（模式切换时连标题一起隐藏）。 */
    private java.util.List<View> actionOnlyViews = new java.util.ArrayList<>();
private static final String[] SOURCES = {"截取当前屏幕", "指定图片路径"};
    private static final String[] TIERS = {"tiny · 轻量（快）", "small · 高精度", "rapid · ML Kit（最快）"};
    /**
     * 输出格式三档，与 OCR 页的下拉、接口的 {@code type} 1/2/3 一一对应。
     *
     * 原来只有「纯文本 / 含坐标（Json）」两档。加「矩形」是因为宏里
     * 常见的下一步是「点这行字」，而只给 x,y 只能点到字块的左上角 ——
     * 那是字的边缘，很容易点空。有了 w,h 才能算中心点。
     */
    private static final String[] TYPES = {"文本", "坐标", "矩形"};
    /** 匹配方式：索引对应 MODE_CONTAINS / MODE_EXACT。 */
    private static final String[] MATCH_MODES = {"包含文本", "匹配文本（完全一致）"};

    /**
     * 截屏方式。
     *
     * <p>【为什么不给"自动"这一项】这是用户明确要求的：三种方式各选一种，
     * 而不是让服务端轮着试。理由见 {@link PluginContract#KEY_CAPTURE}。
     * 数组索引与 CAPTURE_KEYS 一一对应。
     */
    private static final String[] CAPTURE_MODES = {"无障碍截图", "Root 截屏", "投屏授权"};
    /** 与 CAPTURE_MODES 对应的接口取值，下标必须严格对齐。 */
    private static final String[] CAPTURE_KEYS = {
            OCRService.CAPTURE_ACCESSIBILITY,
            OCRService.CAPTURE_ROOT,
            OCRService.CAPTURE_PROJECTION,
    };

    /**
     * 识别范围：整屏 / 只用一块区域。
     *
     * <p>【2.15 起区域也能在插件里框了】上一版只给了「整屏 / 用 App 里已设的那块」，
     * 因为怕"插件存一份、App 存一份"互相覆盖。现在的做法是：
     * 插件里框的区域**存进同一份 OcrRegion**，框的时候直接把 App 里已有的那块
     * 显示出来作为起点，所以两边看到的永远是同一块，不存在两份数据。
     * 用户也可以点「清除」把它清掉，App 那边立刻同步。
     */
    private static final String[] REGION_MODES = {"整屏", "只识别一块区域"};
    /** 区域下拉的控件引用，保存与回填都要用。 */
    private Spinner regionSpinner;
    /** 区域下拉下方的说明行，随选择变化实时改文案。 */
    private TextView regionHint;
    /** 「框选区域…」按钮：截一张当前屏幕，跳到全屏框选页。 */
    private Button regionPickBtn;
    /** 「清除区域」按钮：把已存的那块删掉，回到整屏。 */
    private Button regionClearBtn;
    /** 请求码：从 RegionSelectActivity 拿回框选结果。 */
    private static final int REQ_PICK_REGION = 2001;
    /**
     * 手势事件「监听方向」下拉项。
     *
     * 索引 0 = 任意方向（Bundle 里存空串），
     * 索引 1..8 对应 {@code EdgeGesturePrefs.DIR_KEYS} 的 8 个方向。
     */
    private static final String[] GS_DIR_ITEMS = {
            "任意方向（8 向都触发）",
            "向上 ↑", "右上 ↗", "向右 →", "右下 ↘",
            "向下 ↓", "左下 ↙", "向左 ←", "左上 ↖",
    };

    /**
     * 手势事件「滑过之后干什么」下拉项。
     *
     * 索引与 {@code GS_MODE_* } 常量一一对应：
     *   0 = 不指定（跟随手柄侧该方向自己的设置）
     *   1 = 直触（不弹菜单，抬手立刻发事件）
     *   2 = 菜单（弹菜单，选中图标才发事件）
     *
     * ── 为什么这个选择要写进手柄配置，而不是只存进宏 ──────────────
     * 菜单是在**插件被查询之前**弹的：滑过阈值那一刻服务就要决定
     * 「弹还是不弹」，此时它根本还没收到宿主的 REQUEST_QUERY，
     * 也就无从知道这次滑动属于哪个宏、那个宏想不想弹菜单。
     * 所以这个开关必须落在「手柄的某个方向」上，服务才能在滑动当场读到。
     *
     * 界面上直接改的就是那份设置 —— 所见即所存，不做影子配置，
     * 免得出现「触发器里选了直触、手柄里还是菜单」的两处打架。
     */
    private static final String[] GS_MODES = {
            "跟随手柄（默认）", "直触 · 不弹菜单", "菜单 · 选中才发事件",
    };
    private static final int GS_MODE_FOLLOW = 0;
    private static final int GS_MODE_DIRECT = 1;
    private static final int GS_MODE_MENU = 2;


    @Override
    protected void onDestroy() {
        // 悬浮球是系统级窗口，不随 Activity 消失。配置界面被关掉（宿主取消、
        // 用户返回）时它必须一起撤掉，否则会有一颗"框"永远浮在别人屏幕上。
        // 这里不调 RegionPickDot.hide() 之外的东西：hide() 本身幂等。
        RegionPickDot.hide();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle old = null;
        Intent it = getIntent();
        String action = it == null ? null : it.getAction();
        // 判定宿主想让我们配什么：
        //   ACTION_EDIT_EVENT → 手势触发器（Tasker 事件，带防伪）
        //   其它（含 EDIT_SETTING / action 为空）→ 当作动作处理
        //
        // ★ 这里不再处理 EDIT_CONDITION：条件身份已从 Manifest 撤除，
        //   插件在宿主的触发器列表里只有「Grace · 边缘滑动事件」一个入口。
        if (PluginContract.ACTION_EDIT_EVENT.equals(action)) {
            pluginMode = PluginContract.MODE_EVENT;
        } else {
            pluginMode = PluginContract.MODE_SETTING;
        }
        boolean triggerMode = !PluginContract.MODE_SETTING.equals(pluginMode);
        if (it != null) {
            try {
                old = it.getBundleExtra(PluginContract.EXTRA_BUNDLE);
            } catch (Throwable ignored) {
                // 宿主给的 bundle 反序列化失败就当作没有旧配置，降级为新建。
            }
        }
        // 老配置里带的 plugin_mode 优先（宿主回填时更权威）。
        if (old != null) {
            String m = old.getString(PluginContract.KEY_PLUGIN_MODE, null);
            if (m != null) {
                pluginMode = m;
                triggerMode = !PluginContract.MODE_SETTING.equals(pluginMode);
            }
        }
        // ---------- 根容器 ----------
        LinearLayout root = new LinearLayout(this);
        rootView = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(this, 20), dp(this, 16), dp(this, 20), dp(this, 16));
        TextView title = new TextView(this);
        // 标题跟着模式走：用户点的是「触发器」，界面却说「插件配置」会很迷惑。
        title.setText(triggerMode ? "Grace · 手势触发器" : "Grace · 插件配置");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(C_TEXT);
        root.addView(title, wide());
        if (triggerMode) {
            TextView sub = new TextView(this);
            sub.setText("滑动手柄被滑过时触发这个宏。外观参数在「滑动触发」页里调，这里只决定监听哪个方向。");
            sub.setTextSize(11.5f);
            sub.setTextColor(C_HINT);
            sub.setLineSpacing(0f, 1.35f);
            root.addView(sub, wideTop(this, 6));
        }

        // ---------- 图片来源 ----------
        markActionOnly(sectionLabel("图片来源"), wideTop(this, 16));
        sourceSpinner = new Spinner(this);
        sourceSpinner.setAdapter(spinnerAdapter(SOURCES));
        markActionOnly(sourceSpinner, wide());

        // 路径组（默认隐藏）
        pathGroup = new LinearLayout(this);
        pathGroup.setOrientation(LinearLayout.VERTICAL);
        pathGroup.addView(sectionLabel("图片路径"), wideTop(this, 16));

        pathInput = new EditText(this);
        pathInput.setHint("/sdcard/test.png");
        pathInput.setTextSize(13);
        pathInput.setSingleLine(true);
        pathInput.setInputType(InputType.TYPE_CLASS_TEXT);
        pathInput.setBackground(roundedBorder());
        pathInput.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        pathGroup.addView(pathInput, wide());

        TextView pathHint = new TextView(this);
        pathHint.setText("也可填 MacroDroid 变量，如 {v=图片路径}");
        pathHint.setTextSize(11);
        pathHint.setTextColor(C_HINT);
        LinearLayout.LayoutParams hintLp = wide();
        hintLp.topMargin = dp(this, 4);
        pathGroup.addView(pathHint, hintLp);

        pathGroup.setVisibility(View.GONE);
        // 注意这里用 addToRoot 而不是 markActionOnly：
        // pathGroup 的显隐由「图片来源」下拉框掌管（选了截屏就该藏），
        // 不能被 applyMode 无脑置为 VISIBLE。触发器模式下隐藏它
        // 由 applyMode 里单独一段负责。
        addToRoot(pathGroup, wide());

        sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!PluginContract.MODE_SETTING.equals(pluginMode)) {
                    // 触发器模式下这整块都该藏着，别让它跳出来
                    pathGroup.setVisibility(View.GONE);
                    if (captureGroup != null) captureGroup.setVisibility(View.GONE);
                    return;
                }
                pathGroup.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
                // 截屏方式只对「截取当前屏幕」有意义，选路径时一起藏起来
                if (captureGroup != null) {
                    captureGroup.setVisibility(pos == 1 ? View.GONE : View.VISIBLE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // ---------- 截屏方式 ----------
        // 与「图片来源」一起显隐：选「指定图片路径」时不该出现。
        // 自己建一个容器，而不是用废弃的 imageGroup（那个字段只有声明、从未初始化）。
        captureGroup = new LinearLayout(this);
        captureGroup.setOrientation(LinearLayout.VERTICAL);
        captureGroup.addView(sectionLabel("截屏方式"), wideTop(this, 16));

        captureSpinner = new Spinner(this);
        captureSpinner.setAdapter(spinnerAdapter(CAPTURE_MODES));
        captureGroup.addView(captureSpinner, wide());

        captureHint = new TextView(this);
        captureHint.setTextSize(11);
        captureHint.setTextColor(C_HINT);
        captureHint.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams capHintLp = wide();
        capHintLp.topMargin = dp(this, 4);
        captureGroup.addView(captureHint, capHintLp);

        // 显隐由「图片来源」下拉框掌握，所以用 addToRoot（同 pathGroup 的理由）。
        addToRoot(captureGroup, wide());

        captureSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                refreshCaptureHint();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // ---------- 模型档位 ----------
        markActionOnly(sectionLabel("模型档位"), wideTop(this, 16));
        tierSpinner = new Spinner(this);
        tierSpinner.setAdapter(spinnerAdapter(TIERS));
        markActionOnly(tierSpinner, wide());
// ---------- 输出格式 ----------
        markActionOnly(sectionLabel("输出格式"), wideTop(this, 16));
        typeSpinner = new Spinner(this);
        typeSpinner.setAdapter(spinnerAdapter(TYPES));
        markActionOnly(typeSpinner, wide());

        // ---------- 识别区域 ----------
        markActionOnly(sectionLabel("识别区域"), wideTop(this, 16));
        regionSpinner = new Spinner(this);
        regionSpinner.setAdapter(spinnerAdapter(REGION_MODES));
        markActionOnly(regionSpinner, wide());

        regionHint = new TextView(this);
        regionHint.setTextSize(11);
        regionHint.setTextColor(C_HINT);
        regionHint.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams regionHintLp = wide();
        regionHintLp.topMargin = dp(this, 4);
        markActionOnly(regionHint, regionHintLp);

        regionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                refreshRegionHint();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // 区域操作行：框选 / 清除。
        // 这里直接改的是 OcrRegion（与 App 共用同一份），不存在第二份数据。
        LinearLayout regionBtnRow = new LinearLayout(this);
        regionBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rbrLp = wide();
        rbrLp.topMargin = dp(this, 8);

        regionPickBtn = new Button(this);
        regionPickBtn.setText("框选区域…");
        regionPickBtn.setTextSize(13);
        regionPickBtn.setAllCaps(false);
        LinearLayout.LayoutParams rpbLp = weight();
        regionPickBtn.setLayoutParams(rpbLp);
        regionPickBtn.setOnClickListener(v -> pickRegionForPlugin());
        regionBtnRow.addView(regionPickBtn);

        regionClearBtn = new Button(this);
        regionClearBtn.setText("清除区域");
        regionClearBtn.setTextSize(13);
        regionClearBtn.setAllCaps(false);
        regionClearBtn.setOnClickListener(v -> {
            OcrRegion.clear(this);
            refreshRegionHint();
            Toast.makeText(this, "已清除区域，识别范围回到整屏",
                    Toast.LENGTH_SHORT).show();
        });
        regionBtnRow.addView(regionClearBtn);

        markActionOnly(regionBtnRow, rbrLp);
        refreshRegionHint();

        joinNewline = new CheckBox(this);
        joinNewline.setText("多行结果用换行拼接");
        joinNewline.setTextSize(13);
        joinNewline.setTextColor(C_TEXT);
        joinNewline.setChecked(true);
        LinearLayout.LayoutParams cbLp = wide();
        cbLp.topMargin = dp(this, 10);
        markActionOnly(joinNewline, cbLp);
        // ---------- 手势事件配置（只有触发器模式才显示） ----------
        //
        // 这一段是给宏的**触发器**用的：宿主是拿 ACTION_EDIT_EVENT
        // 打开本界面的，此时用户要配的是「什么手势算触发」，
        // 跟上面那些「识别什么内容」无关，所以整块隐藏起来。
        gestureGroup = new LinearLayout(this);
        gestureGroup.setOrientation(LinearLayout.VERTICAL);
        gestureGroup.addView(sectionLabel("监听方向"), wideTop(this, 16));
        gsDirSpinner = new Spinner(this);
        gsDirSpinner.setAdapter(spinnerAdapter(GS_DIR_ITEMS));
        gestureGroup.addView(gsDirSpinner, wide());
        TextView gsDirHint = new TextView(this);
        gsDirHint.setText("选「任意方向」则 8 个方向都会触发这一个宏；"
                + "也可以只认某一个方向，给每个方向配不同的宏。");
        gsDirHint.setTextSize(11);
        gsDirHint.setTextColor(C_HINT);
        gsDirHint.setLineSpacing(0f, 1.3f);
        gestureGroup.addView(gsDirHint, wideTop(this, 4));

        // ---------- 滑过之后干什么 ----------
        //
        // 这个下拉改的是**手柄上该方向的设置**，不是仅存进宏的私有值。
        // 原因见 GS_MODES 的注释：菜单是在插件被查询之前弹的，
        // 服务必须能在滑动当场读到「这个方向要不要弹菜单」。
        gestureGroup.addView(sectionLabel("滑过之后"), wideTop(this, 14));
        gsModeSpinner = new Spinner(this);
        gsModeSpinner.setAdapter(spinnerAdapter(GS_MODES));
        gestureGroup.addView(gsModeSpinner, wide());
        gsModeHint = new TextView(this);
        gsModeHint.setTextSize(11);
        gsModeHint.setTextColor(C_HINT);
        gsModeHint.setLineSpacing(0f, 1.3f);
        gestureGroup.addView(gsModeHint, wideTop(this, 4));
        gsModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                updateGsModeHint();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
                updateGsModeHint();
            }
        });
        // 方向一变，模式下拉的可用性也跟着变（任意方向不允许选直触）。
        gsDirSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                syncGsModeEnabled();
                updateGsModeHint();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        gsStrict = new CheckBox(this);
        gsStrict.setText("校验收到的消息（防伪造触发）");
        gsStrict.setTextSize(13);
        gsStrict.setTextColor(C_TEXT);
        gsStrict.setChecked(true);
        gestureGroup.addView(gsStrict, wideTop(this, 10));
        TextView gsStrictHint = new TextView(this);
        gsStrictHint.setText("开启后只认 60 秒内由本应用签发的滑动事件，"
                + "其它 App 伪造的同名广播会被忽略。");
        gsStrictHint.setTextSize(11);
        gsStrictHint.setTextColor(C_HINT);
        gsStrictHint.setLineSpacing(0f, 1.3f);
        gestureGroup.addView(gsStrictHint, wideTop(this, 2));
        gestureGroup.setVisibility(View.GONE);
        root.addView(gestureGroup, wide());

        // ---------- 指定文本（可选）----------
        // 填了之后会在识别结果里查找匹配的文本块，
        // 并通过变量回传「是否存在 + 坐标」。
        // 整块放进 matchGroup：触发器模式下这些跟「什么手势算触发」无关。
        matchGroup = new LinearLayout(this);
        matchGroup.setOrientation(LinearLayout.VERTICAL);
        matchGroup.addView(sectionLabel("指定文本（可选）"), wideTop(this, 16));
        matchInput = new EditText(this);
        matchInput.setHint("例如：确定");
        matchInput.setTextSize(13);
        matchInput.setSingleLine(true);
        matchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        matchInput.setBackground(roundedBorder());
        matchInput.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        matchGroup.addView(matchInput, wide());
        // 匹配方式：包含 / 完全一致
        matchModeSpinner = new Spinner(this);
        matchModeSpinner.setAdapter(spinnerAdapter(MATCH_MODES));
        LinearLayout.LayoutParams modeLp = wide();
        modeLp.topMargin = dp(this, 8);
        matchGroup.addView(matchModeSpinner, modeLp);
        TextView matchHint = new TextView(this);
        matchHint.setText("「包含文本」：含这几个字就算找到。\n"
                + "「匹配文本」：必须完全一致。\n"
                + "不区分大小写；命中后坐标取该文本块左上角。");
        matchHint.setTextSize(11);
        matchHint.setTextColor(C_HINT);
        LinearLayout.LayoutParams matchHintLp = wide();
        matchHintLp.topMargin = dp(this, 4);
        matchGroup.addView(matchHint, matchHintLp);
        markActionOnly(matchGroup, wide());

        // ---------- 按钮 ----------
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowLp = wide();
        btnRowLp.topMargin = dp(this, 22);
        root.addView(btns, btnRowLp);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        btns.addView(cancel, weight());

        Button ok = new Button(this);
        ok.setText("保存");
        ok.setAllCaps(false);
        ok.setTextColor(Color.WHITE);
        ok.setBackground(primaryBg());
        ok.setOnClickListener(v -> saveConfig());
        LinearLayout.LayoutParams okLp = weight();
        okLp.leftMargin = dp(this, 10);
        btns.addView(ok, okLp);

        // ---------- 变量说明 ----------
        // 文案随模式切换（见 applyMode）：动作讲识别变量，触发器讲手势变量。
        TextView note = new TextView(this);
        varsNote = note;
        note.setTextSize(11);
        note.setTextColor(C_SUB);
        note.setLineSpacing(0f, 1.35f);
        LinearLayout.LayoutParams noteLp = wide();
        noteLp.topMargin = dp(this, 20);
        root.addView(note, noteLp);

        // ---------- 外层滚动 ----------
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(0xFFF5F6FA);
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 关键：给 setContentView 也带上 MATCH_PARENT，否则窗口可能仍是窄条
        setContentView(sv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 兜底：不依赖主题，直接改窗口尺寸。
        // 有些 ROM 对 windowMinWidthMajor 处理不一致，这里再设一道保险，
        // 保证对话框占屏幕 92% 宽、最多 88% 高。
        try {
            android.view.Window w = getWindow();
            if (w != null) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                w.setLayout((int) (dm.widthPixels * 0.92f),
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
            // 改窗口尺寸只是兜底手段，某些 ROM 拒绝 setLayout 时仍能靠主题尺寸显示。
        }

        if (old != null) applyOld(old);
        // 最后再统一按模式刷一次可见性 ——
        // 放在 applyOld 之后：老配置可能带 plugin_mode，回填时要覆盖掉
        // 前面按 action 猜出来的模式（宿主的配置更权威）。
        applyMode();
    }

    /**
     * 按当前模式刷新界面可见性。
     *
     *   动作（Setting）→ 显示识别相关的全部控件，隐藏手势区
     *   条件 / 事件    → 反过来：隐藏识别控件，显示手势区
     *
     * 之所以要集中在这里做，而不是各自 setVisibility：
     * 一个区域可能由好几节组成（图片来源 + 模型档位 + 输出格式 + 指定文本），
     * 分散着写迟早会漏掉一两个标题，出现"控件没了但标题还在"的怪界面。
     */
    private void applyMode() {
        boolean triggerMode = !PluginContract.MODE_SETTING.equals(pluginMode);

        for (View v : actionOnlyViews) {
            v.setVisibility(triggerMode ? View.GONE : View.VISIBLE);
        }

        if (gestureGroup != null) {
            gestureGroup.setVisibility(triggerMode ? View.VISIBLE : View.GONE);
        }

        // pathGroup / captureGroup 不归 actionOnlyViews 管（它们的显隐由「图片来源」
        // 下拉框决定），但触发器模式下要强制藏起来 —— 那边根本没有"图片来源"这个概念。
        // 两者在这里一起同步：只按来源算一次，避免两个组出现"一个显示一个隐藏"。
        if (pathGroup != null || captureGroup != null) {
            boolean byPath = sourceSpinner != null
                    && sourceSpinner.getSelectedItemPosition() == 1;
            boolean settingMode = !triggerMode;
            if (pathGroup != null) {
                pathGroup.setVisibility(
                        (settingMode && byPath) ? View.VISIBLE : View.GONE);
            }
            if (captureGroup != null) {
                // 截屏方式只对「截取当前屏幕」有意义。
                captureGroup.setVisibility(
                        (settingMode && !byPath) ? View.VISIBLE : View.GONE);
            }
        }

        // 变量说明也要换一套：触发器模式下根本没有识别文本可回传。
        if (varsNote != null) {
            varsNote.setText(triggerMode
                    ? "滑动菜单的选中结果交给宏：\n"
                    + "  菜单索引    选中了第几项（从 0 开始；-1 = 取消）\n"
                    + "  菜单文字    选中项的名字\n"
                    + "  菜单动作    选中项的动作值\n"
                    + "变量名你自己在 MacroDroid 里定。方向已在触发器里选好，宏里不用再判断。"
                    : "识别结果作为变量交给宏：\n"
                    + "  识别文本    识别出的全部文字\n"
                    + "  是否找到    指定文本是否存在（true / false）\n"
                    + "  坐标X       命中处的 x 坐标\n"
                    + "  坐标Y       命中处的 y 坐标\n"
                    + "  错误信息    失败时才有内容\n"
                    + "  耗时(ms)    本次识别耗时\n"
                    + "  模型档位    tiny / small / rapid\n"
                    + "变量名你自己在 MacroDroid 里定。坐标是截图原始像素，"
                    + "可直接用于点击；未找到时 x / y 为 -1。");
        }
    }

    // ==================== 配置读写 ====================

    private void applyOld(Bundle b) {
        String src = b.getString(PluginContract.KEY_SOURCE, PluginContract.SOURCE_SCREENSHOT);
        sourceSpinner.setSelection(PluginContract.SOURCE_PATH.equals(src) ? 1 : 0, false);
        pathInput.setText(b.getString(PluginContract.KEY_PATH, ""));
        pathGroup.setVisibility(PluginContract.SOURCE_PATH.equals(src) ? View.VISIBLE : View.GONE);

        int tier = OCRService.tierFromName(b.getString(PluginContract.KEY_TIER, "tiny"));
        if (tier < 0) tier = 0;
        tierSpinner.setSelection(Math.min(tier, TIERS.length - 1), false);

        // type 1/2/3 → 索引 0/1/2。旧配置里可能是 1 或 2，直接减一即可；
        // 越界值（0 / 4 / 脏数据）夹到合法范围，不让下拉显示空白。
        int savedType = b.getInt(PluginContract.KEY_TYPE, 1);
        typeSpinner.setSelection(Math.max(0, Math.min(savedType - 1, TYPES.length - 1)), false);
        joinNewline.setChecked(b.getBoolean(PluginContract.KEY_JOIN_NEWLINE, true));

        // 识别区域回填。旧配置里没有这个键 → getInt 给 0（整屏），
        // 与升级前的行为一致（那时插件根本没有区域支持）。
        if (regionSpinner != null) {
            regionSpinner.setSelection(
                    b.getInt(PluginContract.KEY_USE_REGION, 0) >= 1 ? 1 : 0, false);
            refreshRegionHint();
        }

        // 截屏方式回填。旧配置没有这个键 → 默认无障碍（免 root、免授权的首选）。
        if (captureSpinner != null) {
            String savedCap = b.getString(PluginContract.KEY_CAPTURE,
                    OCRService.CAPTURE_ACCESSIBILITY);
            int idx = 0;
            for (int i = 0; i < CAPTURE_KEYS.length; i++) {
                if (CAPTURE_KEYS[i].equals(savedCap)) {
                    idx = i;
                    break;
                }
            }
            captureSpinner.setSelection(idx, false);
            refreshCaptureHint();
        }
        // 截屏方式的显隐跟着「图片来源」走（applyOld 上面已设过 sourceSpinner），
        // 但 setSelection(...,false) 不触发监听，所以这里手动同步一次。
        if (captureGroup != null) {
            boolean byPath = PluginContract.SOURCE_PATH.equals(src);
            captureGroup.setVisibility(
                    (!byPath && PluginContract.MODE_SETTING.equals(pluginMode))
                            ? View.VISIBLE : View.GONE);
        }
        matchInput.setText(b.getString(PluginContract.KEY_MATCH_TEXT, ""));
        int mode = b.getInt(PluginContract.KEY_MATCH_MODE, PluginContract.MODE_CONTAINS);
        matchModeSpinner.setSelection(
                mode == PluginContract.MODE_EXACT ? 1 : 0, false);

        // 手势配置（触发器模式用）
        String dir = b.getString(PluginContract.KEY_GS_DIR, "");
        gsDirSpinner.setSelection(gsDirIndex(dir), false);
        gsStrict.setChecked(b.getBoolean(PluginContract.KEY_GS_STRICT, true));

        // 「滑过之后」这一项没有自己的持久化值 —— 它显示的就是手柄上该方向的
        // 当前设置。所以重新打开配置界面时，要按真实状态回填，
        // 否则会显示成「跟随手柄」，让用户以为这里有独立配置。
        int gsMode = GS_MODE_FOLLOW;
        if (gsModeSpinner != null) {
            if (!dir.isEmpty()) {
                gsMode = EdgeGesturePrefs.isMenuForDir(this, dir)
                        ? GS_MODE_MENU : GS_MODE_DIRECT;
            }
            gsModeSpinner.setSelection(gsMode, false);
        }
        syncGsModeEnabled();
        updateGsModeHint();
    }

    /**
     * 检查「本触发器要的方向」在手柄侧是否已经开启。
     *
     * 【它只负责提示，不负责修改】
     * 插件配置与手柄方向现在各自独立：本方法只读 {@link EdgeGesturePrefs#getHandles}
     * 判断目标方向有没有被任何手柄打开，绝不写回。
     *
     * 之所以要有它，是因为解耦之后多了一个新的失败现场：
     * 插件里选了「向左下」，但手柄八个方向全关 —— 滑了不会有事件，
     * 宏永远不触发，而界面上看起来一切正常。
     * 与其让用户去猜，不如保存时说一句。
     *
     * @param dir 方向名；空串 = 任意方向（此时只要有一个方向开着就算就绪）
     * @return true = 手柄侧能滑出这个方向
     */
    private boolean checkDirReady(String dir) {
        boolean any = dir == null || dir.isEmpty();
        int idx = any ? -1 : gsDirIndex(dir) - 1;
        boolean ok = false;
        // 该方向若走菜单，就得提醒「滑了只是弹菜单，选中图标才发事件」。
        // 这是解耦后第二类新的失败现场：方向开着、宏也配了，但用户在
        // 菜单上没选图标，宿主收不到事件，宏看起来还是不触发。
        boolean menuMode = false;
        try {
            List<EdgeGesturePrefs.Handle> all = EdgeGesturePrefs.getHandles(this);
            if (all != null) {
                for (EdgeGesturePrefs.Handle h : all) {
                    if (h == null || h.enabled == null) continue;
                    if (any) {
                        for (boolean b : h.enabled) {
                            if (b) { ok = true; break; }
                        }
                    } else if (idx >= 0 && idx < h.enabled.length) {
                        ok = h.enabled[idx];
                        // 一个方向只要在某个手柄上是直触，就不算「必须走菜单」。
                        if (ok && !h.menuOf(idx)) menuMode = false;
                        else if (ok) menuMode = true;
                    }
                    if (ok && !menuMode) break;
                }
            }
        } catch (Throwable t) {
            // 读配置失败时不要拦着用户保存，当作就绪处理
            return true;
        }
        if (!ok) {
            // 提示里直接把路径说全：方向开关不在本页，在手柄编辑页的
            // 「可触发方向」那一栏（每个方向一个开关 + 一个触发距离）。
            // 光说「去打开」用户会在这页里找不到，所以写到具体位置。
            String what = (any || idx < 0 || idx >= EdgeGesturePrefs.DIR_LABELS.length)
                    ? "任何方向" : EdgeGesturePrefs.DIR_LABELS[idx];
            Toast.makeText(this,
                    "已保存。但手柄目前没开「" + what + "」，滑了不会有事件。\n"
                            + "去这里开：返回首页 →「滑动触发」→ 点一个手柄 →「可触发方向」。",
                    Toast.LENGTH_LONG).show();
        } else if (menuMode && !any) {
            // 只提示一次、不重复弹第二个 Toast（两个 Toast 会排队，前一个还没
            // 消失第二个就盖上来，看着像卡了）。
            Toast.makeText(this,
                    "已保存。注意「" + EdgeGesturePrefs.DIR_LABELS[idx]
                            + "」当前是「菜单」模式：滑了会先弹菜单，选中图标后才触发宏。\n"
                            + "想滑一下就直接触发，去这里改成「直触」：返回首页 →「滑动触发」"
                            + "→ 点一个手柄 →「可触发方向」。",
                    Toast.LENGTH_LONG).show();
        }
        return ok;
    }
    /**
     * 方向变化时决定「滑过之后」这个下拉能不能用。
     *
     * 「任意方向」+「直触」是个说不通的组合，必须挡住：
     * 菜单只有一个总开关，八个方向共用。若把八向都设成直触，
     * 菜单就再也不会出现，等于把这个人辛苦配好的菜单整个关掉。
     * 这种「一次改八向、还带副作用」的操作不该由一个下拉悄悄完成。
     *
     * 所以：选了「任意方向」时，这个下拉置灰，只能「跟随手柄」，
     * 要按方向配，就先在上面选定具体方向。
     */
    private void syncGsModeEnabled() {
        if (gsModeSpinner == null) return;
        boolean any = gsDirSpinner == null || gsDirSpinner.getSelectedItemPosition() <= 0;
        if (any && gsModeSpinner.getSelectedItemPosition() != GS_MODE_FOLLOW) {
            gsModeSpinner.setSelection(GS_MODE_FOLLOW, false);
        }
        gsModeSpinner.setEnabled(!any);
        gsModeSpinner.setAlpha(any ? 0.45f : 1f);
    }

    /** 按当前方向 + 模式刷新那行小字说明。 */
    private void updateGsModeHint() {
        if (gsModeHint == null) return;
        boolean any = gsDirSpinner == null || gsDirSpinner.getSelectedItemPosition() <= 0;
        if (any) {
            gsModeHint.setText("「任意方向」时这一项不可选：整片设置会把八个方向一起改掉，"
                    + "菜单可能因此整个消失。想按方向配，先在上面选定一个具体方向。");
            return;
        }
        int pos = gsDirSpinner.getSelectedItemPosition();
        String label = EdgeGesturePrefs.DIR_LABELS[pos - 1];
        int mode = gsModeSpinner == null ? GS_MODE_FOLLOW
                : gsModeSpinner.getSelectedItemPosition();
        if (mode == GS_MODE_DIRECT) {
            gsModeHint.setText("滑「" + label + "」不弹菜单，抬手立刻把事件发给宏。\n"
                    + "注意：这是手柄级别的设置，同一方向上别的宏也会一起变成直触。");
        } else if (mode == GS_MODE_MENU) {
            gsModeHint.setText("滑「" + label + "」会先弹出菜单，选中图标后才把事件发给宏。\n"
                    + "没选中任何图标就抬手 → 不发事件，宏不会触发。");
        } else {
            gsModeHint.setText("不改动手柄设置，按手柄里「" + label
                    + "」当前的设定走（默认是菜单）。");
        }
    }

    /** 方向名 → 下拉索引。0 = 任意方向，1..8 = DIR_KEYS 顺序。 */
    private int gsDirIndex(String dir) {
        if (dir == null || dir.isEmpty()) return 0;
        for (int i = 0; i < EdgeGesturePrefs.DIR_KEYS.length; i++) {
            if (EdgeGesturePrefs.DIR_KEYS[i].equals(dir)) return i + 1;
        }
        return 0;
    }

    /** 下拉索引 → 方向名。 */
    private String gsDirValue(int pos) {
        if (pos <= 0 || pos > EdgeGesturePrefs.DIR_KEYS.length) return "";
        return EdgeGesturePrefs.DIR_KEYS[pos - 1];
    }

    private void saveConfig() {
        Bundle cfg = new Bundle();
        cfg.putString(PluginContract.KEY_PLUGIN_MODE, pluginMode);

        boolean triggerMode = !PluginContract.MODE_SETTING.equals(pluginMode);

        String blurb;
        if (triggerMode) {
            // ---------- 触发器模式 ----------
            String dir = gsDirValue(gsDirSpinner.getSelectedItemPosition());
            boolean strict = gsStrict.isChecked();
            cfg.putString(PluginContract.KEY_GS_DIR, dir);
            cfg.putBoolean(PluginContract.KEY_GS_STRICT, strict);

            String dirLabel = dir.isEmpty()
                    ? "任意方向"
                    : EdgeGesturePrefs.DIR_LABELS[gsDirIndex(dir) - 1];

            // ★ 这里**只读不写**：绝不再去改动手柄的可用方向。
            //
            //   原先这里调 EdgeGesturePrefs.setDirectionsForAllHandles(this, dir)，
            //   语义是「排他覆盖」—— 选中的开、其余七向全关。
            //   结果是：配了第二个触发器就把第一个的方向关掉了。
            //   用户「配了左下 + 左上，只有后配的左上生效」就是这么来的：
            //   第二个触发器保存时把 down_left 关了，左下滑根本不会触发，
            //   插件连事件都收不到，宏自然不跑。
            //
            //   现在两者彻底解耦：
            //     · 插件配置 = 「哪个方向算我的」（由 dirMatches 判定）
            //     · 手柄方向 = 「能滑出哪些方向」（在「滑动触发」页里开）
            //   一份职责一个地方，配多少个触发器都不会互相踩。
            //
            //   代价：若手柄侧没开这个方向，滑了不会有事件。
            //   这正是下面 checkDirReady() 要提前告知用户的事。

            // ---------- 先应用「滑过之后」，再检查 ----------
            //
            // ★ 顺序很重要：checkDirReady() 会按手柄当前状态判断这个方向
            //   是不是「菜单模式」并据此提示。如果先检查再写入，用户刚把
            //   它选成直触，却会收到一句「注意当前是菜单模式」—— 说的还是
            //   改动前的旧状态，纯属自找困惑。所以先落盘，再按新状态检查。
            //
            // ★ 为什么这个开关要写进手柄配置、而不是只存进宏的 cfg：
            //   菜单是在插件被查询**之前**弹的。滑过阈值那一刻，服务要立刻
            //   决定弹不弹，而此时它还没收到宿主的 REQUEST_QUERY，不可能知道
            //   「这次滑动属于哪个宏、那个宏想不想弹」。所以它只能落在
            //   手柄的该方向上，服务当场才读得到。
            //
            //   「任意方向」时这个下拉是禁用的（见 syncGsModeEnabled），
            //   取到的必然是「跟随手柄」，这里什么都不会改。
            int mode = gsModeSpinner == null ? GS_MODE_FOLLOW
                    : gsModeSpinner.getSelectedItemPosition();
            // 真正落到手柄上了才把模式写进卡片副标题 —— 没改动的时候
            // 描述里不该多出一个本次操作根本没做的后缀。
            String appliedModeSuffix = "";
            if (!dir.isEmpty() && mode != GS_MODE_FOLLOW) {
                boolean wantMenu = (mode == GS_MODE_MENU);
                int changed = EdgeGesturePrefs.setMenuForDir(this, dir, wantMenu);
                AppLog.i("XSOCR-Plugin", "「滑过之后」已应用: 方向=" + dir
                        + " 模式=" + (wantMenu ? "菜单" : "直触")
                        + " 改动处数=" + changed);
                // saveHandles 只写了磁盘，服务手里还是重建时抓到的旧 Handle 对象，
                // 不重建的话这次改动要等下次重启服务才生效。有改动才重建，
                // 免得每次保存都无谓地把悬浮窗摘一遍再挂回去。
                if (changed > 0) {
                    EdgeGestureService.reload(this);
                    appliedModeSuffix = wantMenu ? " · 菜单" : " · 直触";
                }
            }
            boolean ready = checkDirReady(dir);
            AppLog.i("XSOCR-Plugin", "触发器方向已保存: 方向="
                    + (dir.isEmpty() ? "(任意)" : dir)
                    + " 手柄已开启=" + ready);

            blurb = "滑动 " + dirLabel + appliedModeSuffix;
            if (PluginContract.MODE_EVENT.equals(pluginMode) && !strict) {
                blurb += " · 不校验消息";
            }
        } else {
            // ---------- 动作模式 ----------
            int srcPos = sourceSpinner.getSelectedItemPosition();
            String path = pathInput.getText() == null
                    ? "" : pathInput.getText().toString().trim();

            if (srcPos == 1 && path.isEmpty()) {
                Toast.makeText(this, "请填写图片路径", Toast.LENGTH_SHORT).show();
                return;
            }

            cfg.putString(PluginContract.KEY_SOURCE,
                    srcPos == 1 ? PluginContract.SOURCE_PATH : PluginContract.SOURCE_SCREENSHOT);
            cfg.putString(PluginContract.KEY_PATH, path);

            int tier = tierSpinner.getSelectedItemPosition();
            cfg.putString(PluginContract.KEY_TIER, OCRService.tierName(tier));

            // 下拉索引 0/1/2 → type 1/2/3。
            // 不再写成「== 1 ? 2 : 1」：那样选「矩形」会掉回 1，
            // 用户选了却拿到没坐标的结果。
            int type = typeSpinner.getSelectedItemPosition() + 1;
            if (type < 1 || type > 3) type = 1;
            cfg.putInt(PluginContract.KEY_TYPE, type);
            cfg.putBoolean(PluginContract.KEY_JOIN_NEWLINE, joinNewline.isChecked());

            // 截屏方式：只在「截取当前屏幕」时有意义，但一并存下 ——
            // 用户可能先配了截屏、后来又切成路径，之后再切回来时选择还在。
            int capPos = captureSpinner == null ? 0 : captureSpinner.getSelectedItemPosition();
            if (capPos < 0 || capPos >= CAPTURE_KEYS.length) capPos = 0;
            cfg.putString(PluginContract.KEY_CAPTURE, CAPTURE_KEYS[capPos]);

            // 识别区域：0=整屏 1=只识别一块区域。
            int useRegion = regionSpinner != null
                    && regionSpinner.getSelectedItemPosition() > 0 ? 1 : 0;
            cfg.putInt(PluginContract.KEY_USE_REGION, useRegion);
            if (useRegion == 1) {
                // 区域只有一份（OcrRegion），这里存的是"用不用"，
                // 具体数值运行时再读 —— 这样在 App 里改了区域，宏也跟着变。
                if (OcrRegion.load(this) == null) {
                    Toast.makeText(this,
                            "还没有框过区域：请点「框选区域…」先框一块",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                cfg.putString(PluginContract.KEY_REGION, "");
            }

            // 指定文本（可选）：留空表示不做匹配查询
            String matchText = matchInput.getText() == null
                    ? "" : matchInput.getText().toString().trim();
            cfg.putString(PluginContract.KEY_MATCH_TEXT, matchText);

            // 匹配方式：0=包含，1=完全一致
            int matchMode = matchModeSpinner.getSelectedItemPosition() == 1
                    ? PluginContract.MODE_EXACT : PluginContract.MODE_CONTAINS;
            cfg.putInt(PluginContract.KEY_MATCH_MODE, matchMode);

            String modeName = matchMode == PluginContract.MODE_EXACT ? "匹配" : "包含";

            blurb = (srcPos == 1 ? "路径" : ("截屏·" + CAPTURE_MODES[capPos]))
                    + " · " + OCRService.tierName(tier)
                    + " · " + TYPES[Math.max(0, Math.min(type - 1, TYPES.length - 1))]
                    + (useRegion == 1 ? " · 区域" : "")
                    + (matchText.isEmpty() ? "" : " · " + modeName + "「" + matchText + "」");
        }

        Intent result = new Intent();
        result.putExtra(PluginContract.EXTRA_BUNDLE, cfg);
        result.putExtra(PluginContract.EXTRA_STRING_BLURB, blurb);

        // ★ 关键：必须在这里「预告」会产出哪些变量。
        //   宿主要先知道变量名，才会在动作配置里显示 "Output variables"
        //   选项供你映射到宏变量。
        //
        //   格式必须是 Tasker 的三段式（用 \n 分隔）：
        //       变量名\n标签\n描述
        //   参考 PP-OCRv6 插件（实测能正常传变量）的 ActionEditActivity，
        //   它就是这样拼的：sb.append("%ocr_full_text\n").append(标签).append("\n").append(描述)。
        //   只传裸变量名虽然也能列出，但界面可读性差、部分宿主解析不稳。
        //
        //   触发器模式只预告菜单变量：那个场景里没有 OCR 识别，
        //   混进识别文本之类只会让人以为还得配图片路径。
        if (triggerMode) {
            PluginContract.addMenuVariableList(result);
        } else {
            PluginContract.addRelevantVariableList(result);
        }
        // 「变量收不到」有两个前提，缺一不可：
        //   ① 配置时必须把变量名**预告**给宿主（就是上面这一步）
        //   ② 执行/应答时必须真的把值**回传**（PluginFireReceiver / ConditionReceiver）
        // 这里记下①做了没有、预告了哪些，省得来回猜。
        AppLog.i("XSOCR-Plugin", "插件配置已保存: "
                + (triggerMode ? "事件/触发器模式" : "动作模式")
                + "，已预告变量: " + java.util.Arrays.toString(triggerMode
                        ? PluginContract.MENU_VARIABLES
                        : PluginContract.ALL_VARIABLES));

        // 顺便申请更长的超时（部分宿主会读，MacroDroid 不读也无害）。
        // PP-OCR 也这么做了：requestTimeoutMS(intent, 120000)。
        PluginContract.requestTimeout(result, 120000);

        setResult(RESULT_OK, result);
        finish();
    }

    // ==================== 小工具 ====================

    private ArrayAdapter<String> spinnerAdapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    /**
     * 在插件里框选区域 —— 分两步，中间给用户切页面的机会。
     *
     * <p>【为什么需要两步】配置界面是宿主拉起来的普通页面。原来点按钮就
     * **立刻**截图，可那一刻前台正是 Grace 自己的配置界面，截到的必然是这一页，
     * 而用户想框的是别的 App 的页面 —— 底图永远不对。
     * 所以改成：先摆一个悬浮球（{@link RegionPickDot}），用户切到目标页面
     * 再点它，那时才截图并进入框选。
     *
     * <p>【为什么框选结果仍写同一份 OcrRegion】区域存在 OcrRegion 里，
     * App 的「设区域…」与这里读写的是同一份数据。插件不另存 ——
     * 否则用户在插件里框了、App 里还显示旧的，两边就会各说各话。
     *
     * <p>【为什么要走 /shot 而不是自己截】{@link RegionSelectActivity} 需要一张
     * 截图才能铺满屏幕给人框。插件侧（Activity）自己调 captureScreen()
     * 拿不到 Service 实例，所以借服务的 /shot。
     */
    private void pickRegionForPlugin() {
        // 必须先确认服务在跑，否则点完球以后 /shot 会失败。
        if (!OCRService.isRunning) {
            Toast.makeText(this,
                    "OCR 服务没在运行：请先打开 Grace 一次，或让宏跑一次把它拉起来",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // 已经摆着一个球了：再点一次就当作撤掉，避免重复点击摆出多个。
        if (RegionPickDot.isShowing()) {
            RegionPickDot.hide();
            Toast.makeText(this, "已收起悬浮球", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!RegionPickDot.canShow(this)) {
            Toast.makeText(this,
                    "需要「显示在其他应用上层」权限才能摆悬浮球。\n"
                            + "点「确定」去设置里给 Grace 勾上，回来再点一次「框选区域…」。",
                    Toast.LENGTH_LONG).show();
            RegionPickDot.requestPermission(this);
            return;
        }

        boolean ok = RegionPickDot.show(this, new RegionPickDot.Callback() {
            @Override
            public void onTap() {
                // 球已经撤掉了，这里只管去截图。
                captureThenPick();
            }

            @Override
            public void onCancel() {
                runOnUiThread(() -> Toast.makeText(PluginEditActivity.this,
                        "已取消框选", Toast.LENGTH_SHORT).show());
            }
        });

        if (!ok) {
            Toast.makeText(this,
                    "悬浮球没摆出来：可能是权限刚被撤销，或系统拒绝了悬浮窗。\n"
                            + "先去设置里确认「显示在其他应用上层」为允许。",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this,
                "悬浮球已就位：切到要识别的页面，点它即可框选；长按可取消。",
                Toast.LENGTH_LONG).show();
    }

    /**
     * 点了悬浮球之后：截图 → 跳全屏框选页。
     *
     * <p>【为什么要先等一下】悬浮球本身也是一扇窗口，刚撤掉时合成器可能
     * 还没刷新完，立刻截图会把那颗蓝球一起拍进去，框选底图上莫名其妙多一个圆。
     * 等两帧多一点（150ms）足够，又不会让用户觉得卡。
     *
     * <p>【为什么这里敢在后台 startActivity】点球那一刻，Grace 已经不在前台
     * （用户切到目标 App 了），Android 10+ 默认禁止后台启动 Activity。
     * 但系统对「持有 SYSTEM_ALERT_WINDOW 的应用」留了例外 ——
     * 能画悬浮窗就等于用户已经授权它"能突然出现在屏幕上"，
     * 启动界面也一并放行。这条路本身**就依赖那个权限**，
     * 没权限连球都摆不出来（{@link #pickRegionForPlugin} 已经拦在前面）。
     * 工程里 {@code ScreenCaptureRequestActivity} 能在后台弹授权框，
     * 靠的也正是同一条例外。
     */
    private void captureThenPick() {
        if (regionPickBtn != null) {
            regionPickBtn.setEnabled(false);
            regionPickBtn.setText("正在截屏…");
        }
        new Thread(() -> {
            // 让悬浮窗真正从屏幕上消失再截。
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String shotPath = null;
            String err = null;
            try {
                String resp = Http.get("/shot", 15000);
                if (resp == null || resp.length() == 0) {
                    err = "截屏没有返回内容";
                } else {
                    org.json.JSONObject o = new org.json.JSONObject(resp);
                    if (o.optInt("status", 0) != 200) {
                        err = o.optString("data", "截屏失败");
                    } else {
                        org.json.JSONObject data = o.optJSONObject("data");
                        shotPath = data == null ? null : data.optString("path", null);
                        if (shotPath == null || shotPath.length() == 0) {
                            err = "截屏接口没给路径";
                        }
                    }
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final String path = shotPath;
            final String failMsg = err;
            runOnUiThread(() -> {
                if (regionPickBtn != null) {
                    regionPickBtn.setEnabled(true);
                    regionPickBtn.setText("框选区域…");
                }
                if (path == null) {
                    Toast.makeText(this, "拿不到当前屏幕：" + failMsg
                                    + "\n\n先在 Grace 里启用一种截屏方式再试。",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // showCurrent=true：把已存的那块摆回屏幕上，用户能微调而不是重画。
                // 用 openSelfSaving：本页在用户切到别的 App 之后才被拉起，
                // 本 Activity 那时已在后台，可能被系统回收 —— 让框选页自己落盘。
                try {
                    RegionSelectActivity.openSelfSaving(this, path, true, REQ_PICK_REGION);
                } catch (Throwable t) {
                    // 极少数 ROM 连 SAW 例外都不认，后台启动直接抛异常。
                    // 这里必须说清楚，否则用户只会看到"点了球什么都没发生"。
                    Toast.makeText(this,
                            "无法打开框选界面：" + (t.getMessage() == null ? t.toString() : t.getMessage())
                                    + "\n请先切回 Grace，再点一次「框选区域…」。",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int reqCode, int resCode, Intent data) {
        super.onActivityResult(reqCode, resCode, data);
        if (reqCode != REQ_PICK_REGION) {
            return;
        }
        // 无论走哪条路，先把可能还挂着的悬浮球撤掉 —— 用户从框选页回来时
        // 球早该没了，兜一次底，避免极端情况下屏幕上留一颗球。
        RegionPickDot.hide();
        if (resCode != RESULT_OK || data == null) {
            // 取消：不动已存区域。取消只该放弃这一次操作。
            return;
        }
        float[] r = new float[]{
                data.getFloatExtra(RegionSelectActivity.EXTRA_LEFT, 0f),
                data.getFloatExtra(RegionSelectActivity.EXTRA_TOP, 0f),
                data.getFloatExtra(RegionSelectActivity.EXTRA_RIGHT, 1f),
                data.getFloatExtra(RegionSelectActivity.EXTRA_BOTTOM, 1f),
        };
        // 框选页若已经自己存过（EXTRA_SAVE_SELF），这里就不再存第二遍。
        // 写两次虽然结果一样，但会多一次 prefs 写入，也让"到底谁负责存"变得含糊。
        boolean savedByPicker = data.getBooleanExtra(
                RegionSelectActivity.EXTRA_SAVE_SELF, false);
        if (!savedByPicker) {
            OcrRegion.save(this, r);
        }
        // 框完自动把范围切到「只识别一块区域」，省用户一次操作 ——
        // 他刚框完，意图很明确。
        if (regionSpinner != null) {
            regionSpinner.setSelection(1, false);
        }
        refreshRegionHint();
        Toast.makeText(this, "区域已保存（与 Grace 里共用同一块）",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 刷新截屏方式说明行：告诉用户这条方式现在能不能用。
     *
     * <p>【为什么要在配置时就查】用户选了「Root 截屏」而设备没有 root，
     * 如果这里不提示，要等宏跑起来才从 {@code %xsocr_error} 里看到。
     * 在配置界面先亮出来，一眼就知道该换哪个。
     */
    private void refreshCaptureHint() {
        if (captureHint == null || captureSpinner == null) {
            return;
        }
        int pos = captureSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= CAPTURE_KEYS.length) {
            pos = 0;
        }
        String key = CAPTURE_KEYS[pos];

        // 无障碍截图：看服务是否已连上无障碍
        boolean ready = false;
        String how;
        if (OCRService.CAPTURE_ACCESSIBILITY.equals(key)) {
            how = "免 root、免授权、不弹框，需 Android 11+ 并在系统设置里勾选「Grace 截图」。";
            ready = OcrAccessibilityService.isReady();
        } else if (OCRService.CAPTURE_ROOT.equals(key)) {
            how = "走 su + screencap，需要设备已 root 且已授权。";
            // 插件界面拿不到 Service 实例，只能读静态标志；
            // 它由服务启动时探测、并在每次截屏后刷新，够用来给提示了。
            ready = OCRService.screenCaptureRootReady;
        } else {
            how = "走系统录屏授权，服务重启后需要重新授权一次。";
            ready = OCRService.screenCaptureProjectionReady;
        }

        if (ready) {
            captureHint.setText(how + "\n当前状态：可用。");
            captureHint.setTextColor(C_HINT);
        } else {
            captureHint.setText(how + "\n⚠ 当前状态：不可用，宏跑起来会报错。"
                    + "请先在 Grace 里启用这条方式，或换成其它方式。");
            captureHint.setTextColor(C_DANGER);
        }
    }

    /**
     * 刷新区域说明行，并控制「框选 / 清除」按钮的可用性。
     */
    private void refreshRegionHint() {
        if (regionHint == null || regionSpinner == null) {
            return;
        }
        boolean useRegion = regionSpinner.getSelectedItemPosition() > 0;
        // 两个按钮只在「只识别一块区域」时才有意义
        if (regionPickBtn != null) {
            regionPickBtn.setEnabled(useRegion);
            regionPickBtn.setAlpha(useRegion ? 1f : 0.4f);
        }
        if (regionClearBtn != null) {
            OcrRegion cur = OcrRegion.load(this);
            boolean canClear = useRegion && cur != null;
            regionClearBtn.setEnabled(canClear);
            regionClearBtn.setAlpha(canClear ? 1f : 0.4f);
        }

        if (!useRegion) {
            regionHint.setText("整块屏幕都识别。要只识别屏幕里的一块，"
                    + "选上一项然后点「框选区域…」。");
            regionHint.setTextColor(C_HINT);
            return;
        }
        OcrRegion r = OcrRegion.load(this);
        if (r == null) {
            regionHint.setText("还没有框过区域，点下面的「框选区域…」"
                    + "直接在屏幕上拖一块（会先截一张当前屏幕给你框）。");
            regionHint.setTextColor(C_HINT);
            return;
        }
        regionHint.setText("当前区域：" + r.percentText()
                + "（占全屏 " + Math.round(r.area() * 100) + "%）。"
                + "点「框选区域…」可重框。");
        regionHint.setTextColor(C_HINT);
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(C_SUB);
        return tv;
    }

    /** 宽 MATCH_PARENT、高 WRAP_CONTENT —— 最常用。 */
        /** 同上，但带一个上边距（用于小节标题）。 */
        private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(this, 44), 1f);
    }

    private GradientDrawable roundedBorder() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(C_SURFACE);
        d.setCornerRadius(dp(this, 10));
        d.setStroke(dp(this, 1), C_DIVIDER);
        return d;
    }

    private GradientDrawable primaryBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(C_BRAND);
        d.setCornerRadius(dp(this, 10));
        return d;
    }

    }