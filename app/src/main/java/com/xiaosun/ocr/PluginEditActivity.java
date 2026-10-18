package com.xiaosun.ocr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
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
    /** 图片路径组的容器引用，模式切换时要用它整体隐藏。 */
    private LinearLayout imageGroup;
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
    private static final String[] TYPES = {"纯文本", "含坐标（Json）"};
    /** 匹配方式：索引对应 MODE_CONTAINS / MODE_EXACT。 */
    private static final String[] MATCH_MODES = {"包含文本", "匹配文本（完全一致）"};
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

    private int C_TEXT, C_SUB, C_HINT, C_BRAND, C_DIVIDER, C_SURFACE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        C_TEXT = 0xFF1A1D26;
        C_SUB = 0xFF6B7280;
        C_HINT = 0xFF9CA3AF;
        C_BRAND = 0xFF4F6BED;
        C_DIVIDER = 0xFFEAECF2;
        C_SURFACE = 0xFFFFFFFF;

        Bundle old = null;
        Intent it = getIntent();
        String action = it == null ? null : it.getAction();
        // 判定宿主想让我们配什么：
        //   ACTION_EDIT_EVENT → 手势触发器（Tasker 事件，带防伪）
        //   其它（含 EDIT_SETTING / action 为空）→ 当作动作处理
        //
        // ★ 这里不再处理 EDIT_CONDITION：条件身份已从 Manifest 撤除，
        //   插件在宿主的触发器列表里只有「小孙OCR · 边缘滑动事件」一个入口。
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
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = new TextView(this);
        // 标题跟着模式走：用户点的是「触发器」，界面却说「插件配置」会很迷惑。
        title.setText(triggerMode ? "小孙OCR · 手势触发器" : "小孙OCR · 插件配置");
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
            root.addView(sub, wideTop(6));
        }

        // ---------- 图片来源 ----------
        markActionOnly(sectionLabel("图片来源"), wideTop(16));
        sourceSpinner = new Spinner(this);
        sourceSpinner.setAdapter(spinnerAdapter(SOURCES));
        markActionOnly(sourceSpinner, wide());

        // 路径组（默认隐藏）
        pathGroup = new LinearLayout(this);
        pathGroup.setOrientation(LinearLayout.VERTICAL);
        pathGroup.addView(sectionLabel("图片路径"), wideTop(16));

        pathInput = new EditText(this);
        pathInput.setHint("/sdcard/test.png");
        pathInput.setTextSize(13);
        pathInput.setSingleLine(true);
        pathInput.setInputType(InputType.TYPE_CLASS_TEXT);
        pathInput.setBackground(roundedBorder());
        pathInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        pathGroup.addView(pathInput, wide());

        TextView pathHint = new TextView(this);
        pathHint.setText("也可填 MacroDroid 变量，如 {v=图片路径}");
        pathHint.setTextSize(11);
        pathHint.setTextColor(C_HINT);
        LinearLayout.LayoutParams hintLp = wide();
        hintLp.topMargin = dp(4);
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
                    return;
                }
                pathGroup.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // ---------- 模型档位 ----------
        markActionOnly(sectionLabel("模型档位"), wideTop(16));
        tierSpinner = new Spinner(this);
        tierSpinner.setAdapter(spinnerAdapter(TIERS));
        markActionOnly(tierSpinner, wide());
        // ---------- 输出格式 ----------
        markActionOnly(sectionLabel("输出格式"), wideTop(16));
        typeSpinner = new Spinner(this);
        typeSpinner.setAdapter(spinnerAdapter(TYPES));
        markActionOnly(typeSpinner, wide());

        joinNewline = new CheckBox(this);
        joinNewline.setText("多行结果用换行拼接");
        joinNewline.setTextSize(13);
        joinNewline.setTextColor(C_TEXT);
        joinNewline.setChecked(true);
        LinearLayout.LayoutParams cbLp = wide();
        cbLp.topMargin = dp(10);
        markActionOnly(joinNewline, cbLp);
        // ---------- 手势事件配置（只有触发器模式才显示） ----------
        //
        // 这一段是给宏的**触发器**用的：宿主是拿 ACTION_EDIT_EVENT
        // 打开本界面的，此时用户要配的是「什么手势算触发」，
        // 跟上面那些「识别什么内容」无关，所以整块隐藏起来。
        gestureGroup = new LinearLayout(this);
        gestureGroup.setOrientation(LinearLayout.VERTICAL);
        gestureGroup.addView(sectionLabel("监听方向"), wideTop(16));
        gsDirSpinner = new Spinner(this);
        gsDirSpinner.setAdapter(spinnerAdapter(GS_DIR_ITEMS));
        gestureGroup.addView(gsDirSpinner, wide());
        TextView gsDirHint = new TextView(this);
        gsDirHint.setText("选「任意方向」则 8 个方向都会触发这一个宏；"
                + "也可以只认某一个方向，给每个方向配不同的宏。");
        gsDirHint.setTextSize(11);
        gsDirHint.setTextColor(C_HINT);
        gsDirHint.setLineSpacing(0f, 1.3f);
        gestureGroup.addView(gsDirHint, wideTop(4));

        // ---------- 滑过之后干什么 ----------
        //
        // 这个下拉改的是**手柄上该方向的设置**，不是仅存进宏的私有值。
        // 原因见 GS_MODES 的注释：菜单是在插件被查询之前弹的，
        // 服务必须能在滑动当场读到「这个方向要不要弹菜单」。
        gestureGroup.addView(sectionLabel("滑过之后"), wideTop(14));
        gsModeSpinner = new Spinner(this);
        gsModeSpinner.setAdapter(spinnerAdapter(GS_MODES));
        gestureGroup.addView(gsModeSpinner, wide());
        gsModeHint = new TextView(this);
        gsModeHint.setTextSize(11);
        gsModeHint.setTextColor(C_HINT);
        gsModeHint.setLineSpacing(0f, 1.3f);
        gestureGroup.addView(gsModeHint, wideTop(4));
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
        gestureGroup.addView(gsStrict, wideTop(10));
        TextView gsStrictHint = new TextView(this);
        gsStrictHint.setText("开启后只认 60 秒内由本应用签发的滑动事件，"
                + "其它 App 伪造的同名广播会被忽略。");
        gsStrictHint.setTextSize(11);
        gsStrictHint.setTextColor(C_HINT);
        gsStrictHint.setLineSpacing(0f, 1.3f);
        gestureGroup.addView(gsStrictHint, wideTop(2));
        gestureGroup.setVisibility(View.GONE);
        root.addView(gestureGroup, wide());

        // ---------- 指定文本（可选）----------
        // 填了之后会在识别结果里查找匹配的文本块，
        // 并通过变量回传「是否存在 + 坐标」。
        // 整块放进 matchGroup：触发器模式下这些跟「什么手势算触发」无关。
        matchGroup = new LinearLayout(this);
        matchGroup.setOrientation(LinearLayout.VERTICAL);
        matchGroup.addView(sectionLabel("指定文本（可选）"), wideTop(16));
        matchInput = new EditText(this);
        matchInput.setHint("例如：确定");
        matchInput.setTextSize(13);
        matchInput.setSingleLine(true);
        matchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        matchInput.setBackground(roundedBorder());
        matchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        matchGroup.addView(matchInput, wide());
        // 匹配方式：包含 / 完全一致
        matchModeSpinner = new Spinner(this);
        matchModeSpinner.setAdapter(spinnerAdapter(MATCH_MODES));
        LinearLayout.LayoutParams modeLp = wide();
        modeLp.topMargin = dp(8);
        matchGroup.addView(matchModeSpinner, modeLp);
        TextView matchHint = new TextView(this);
        matchHint.setText("「包含文本」：含这几个字就算找到。\n"
                + "「匹配文本」：必须完全一致。\n"
                + "不区分大小写；命中后坐标取该文本块左上角。");
        matchHint.setTextSize(11);
        matchHint.setTextColor(C_HINT);
        LinearLayout.LayoutParams matchHintLp = wide();
        matchHintLp.topMargin = dp(4);
        matchGroup.addView(matchHint, matchHintLp);
        markActionOnly(matchGroup, wide());

        // ---------- 按钮 ----------
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowLp = wide();
        btnRowLp.topMargin = dp(22);
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
        okLp.leftMargin = dp(10);
        btns.addView(ok, okLp);

        // ---------- 变量说明 ----------
        // 文案随模式切换（见 applyMode）：动作讲识别变量，触发器讲手势变量。
        TextView note = new TextView(this);
        varsNote = note;
        note.setTextSize(11);
        note.setTextColor(C_SUB);
        note.setLineSpacing(0f, 1.35f);
        LinearLayout.LayoutParams noteLp = wide();
        noteLp.topMargin = dp(20);
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

        // pathGroup 不归 actionOnlyViews 管（它的显隐由「图片来源」下拉框决定），
        // 但触发器模式下要强制藏起来 —— 那边根本没有"图片来源"这个概念。
        if (pathGroup != null) {
            if (triggerMode) {
                pathGroup.setVisibility(View.GONE);
            } else {
                boolean byPath = sourceSpinner != null
                        && sourceSpinner.getSelectedItemPosition() == 1;
                pathGroup.setVisibility(byPath ? View.VISIBLE : View.GONE);
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

        typeSpinner.setSelection(b.getInt(PluginContract.KEY_TYPE, 1) >= 2 ? 1 : 0, false);
        joinNewline.setChecked(b.getBoolean(PluginContract.KEY_JOIN_NEWLINE, true));
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

            int type = typeSpinner.getSelectedItemPosition() == 1 ? 2 : 1;
            cfg.putInt(PluginContract.KEY_TYPE, type);
            cfg.putBoolean(PluginContract.KEY_JOIN_NEWLINE, joinNewline.isChecked());

            // 指定文本（可选）：留空表示不做匹配查询
            String matchText = matchInput.getText() == null
                    ? "" : matchInput.getText().toString().trim();
            cfg.putString(PluginContract.KEY_MATCH_TEXT, matchText);

            // 匹配方式：0=包含，1=完全一致
            int matchMode = matchModeSpinner.getSelectedItemPosition() == 1
                    ? PluginContract.MODE_EXACT : PluginContract.MODE_CONTAINS;
            cfg.putInt(PluginContract.KEY_MATCH_MODE, matchMode);

            String modeName = matchMode == PluginContract.MODE_EXACT ? "匹配" : "包含";

            blurb = (srcPos == 1 ? "路径" : "截屏")
                    + " · " + OCRService.tierName(tier)
                    + " · " + (type == 1 ? "纯文本" : "含坐标")
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

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(C_SUB);
        return tv;
    }

    /** 宽 MATCH_PARENT、高 WRAP_CONTENT —— 最常用。 */
    private LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 同上，但带一个上边距（用于小节标题）。 */
    private LinearLayout.LayoutParams wideTop(int topDp) {
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(44), 1f);
    }

    private GradientDrawable roundedBorder() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(C_SURFACE);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(1), C_DIVIDER);
        return d;
    }

    private GradientDrawable primaryBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(C_BRAND);
        d.setCornerRadius(dp(10));
        return d;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}