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
            sub.setText("边缘滑动手柄被滑过时触发这个宏。"
                    + "手柄的位置、方向、触发距离等外观参数在 App 的"
                    + "「滑动触发」页里调；这里只决定监听哪个方向。");
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
        matchHint.setText("「包含文本」：文本块里含这几个字就算找到（如「确定」能匹配「确定提交」）。\n"
                + "「匹配文本」：整个文本块必须与它完全一致。\n"
                + "不区分大小写；命中后坐标变量为该文本块左上角坐标。");
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
                    ? "滑动菜单的选中结果会交给宏：\n"
                    + "  菜单索引    选中了第几项（从 0 开始；-1 = 取消）\n"
                    + "  菜单文字    选中项的名字\n"
                    + "  菜单动作    选中项的动作值\n"
                    + "\n变量叫什么名字由你在 MacroDroid 里自己定，\n"
                    + "本应用不预设名字，按你起的名接值就行。\n"
                    + "\n方向在触发器里就选好了，方向不符的宏根本不会被触发，\n"
                    + "宏里不需要再判断方向。"
                    : "识别结果会作为变量交给宏：\n"
                    + "  识别文本    识别出的全部文字\n"
                    + "  是否找到    指定文本是否存在（true / false）\n"
                    + "  坐标X       命中处的 x 坐标（整数）\n"
                    + "  坐标Y       命中处的 y 坐标（整数）\n"
                    + "  错误信息    失败时才有内容\n"
                    + "  耗时(ms)    本次识别耗时\n"
                    + "  模型档位    tiny / small / rapid\n"
                    + "\n变量叫什么名字由你在 MacroDroid 里自己定，\n"
                    + "本应用不预设名字，按你起的名接值就行。\n"
                    + "\n坐标基于截图原始像素，可直接用于点击坐标。\n"
                    + "未找到时 x / y 为 -1。");
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

            blurb = "滑动 " + dirLabel;
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