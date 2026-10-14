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

    private static final String[] SOURCES = {"截取当前屏幕", "指定图片路径"};
    private static final String[] TIERS = {"tiny · 轻量（快）", "small · 高精度", "rapid · RapidOCR"};
    private static final String[] TYPES = {"纯文本", "含坐标（Json）"};
    /** 匹配方式：索引对应 MODE_CONTAINS / MODE_EXACT。 */
    private static final String[] MATCH_MODES = {"包含文本", "匹配文本（完全一致）"};

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
        if (it != null) {
            try {
                old = it.getBundleExtra(PluginContract.EXTRA_BUNDLE);
            } catch (Throwable ignored) {
            }
        }

        // ---------- 根容器 ----------
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("小孙OCR · 插件配置");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(C_TEXT);
        root.addView(title, wide());

        // ---------- 图片来源 ----------
        root.addView(sectionLabel("图片来源"), wideTop(16));
        sourceSpinner = new Spinner(this);
        sourceSpinner.setAdapter(spinnerAdapter(SOURCES));
        root.addView(sourceSpinner, wide());

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
        root.addView(pathGroup, wide());

        sourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                pathGroup.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // ---------- 模型档位 ----------
        root.addView(sectionLabel("模型档位"), wideTop(16));
        tierSpinner = new Spinner(this);
        tierSpinner.setAdapter(spinnerAdapter(TIERS));
        root.addView(tierSpinner, wide());

        // ---------- 输出格式 ----------
        root.addView(sectionLabel("输出格式"), wideTop(16));
        typeSpinner = new Spinner(this);
        typeSpinner.setAdapter(spinnerAdapter(TYPES));
        root.addView(typeSpinner, wide());

        joinNewline = new CheckBox(this);
        joinNewline.setText("多行结果用换行拼接");
        joinNewline.setTextSize(13);
        joinNewline.setTextColor(C_TEXT);
        joinNewline.setChecked(true);
        LinearLayout.LayoutParams cbLp = wide();
        cbLp.topMargin = dp(10);
        root.addView(joinNewline, cbLp);

        // ---------- 指定文本（可选）----------
        // 填了之后会在识别结果里查找匹配的文本块，
        // 并通过 %xsocr_match / %xsocr_x / %xsocr_y 回传「是否存在 + 坐标」。
        root.addView(sectionLabel("指定文本（可选）"), wideTop(16));

        matchInput = new EditText(this);
        matchInput.setHint("例如：确定");
        matchInput.setTextSize(13);
        matchInput.setSingleLine(true);
        matchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        matchInput.setBackground(roundedBorder());
        matchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(matchInput, wide());

        // 匹配方式：包含 / 完全一致
        matchModeSpinner = new Spinner(this);
        matchModeSpinner.setAdapter(spinnerAdapter(MATCH_MODES));
        LinearLayout.LayoutParams modeLp = wide();
        modeLp.topMargin = dp(8);
        root.addView(matchModeSpinner, modeLp);

        TextView matchHint = new TextView(this);
        matchHint.setText("「包含文本」：文本块里含这几个字就算找到（如「确定」能匹配「确定提交」）。\n"
                + "「匹配文本」：整个文本块必须与它完全一致。\n"
                + "不区分大小写；命中后 %xsocr_x / %xsocr_y 为该文本块左上角坐标。");
        matchHint.setTextSize(11);
        matchHint.setTextColor(C_HINT);
        LinearLayout.LayoutParams matchHintLp = wide();
        matchHintLp.topMargin = dp(4);
        root.addView(matchHint, matchHintLp);

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
        TextView note = new TextView(this);
        note.setTextSize(11);
        note.setTextColor(C_SUB);
        note.setLineSpacing(0f, 1.35f);
        note.setText("识别结果会通过变量回传给宏：\n"
                + "  %xsocr_text     识别出的全部文本\n"
                + "  %xsocr_match    指定文本是否找到（true/false）\n"
                + "  %xsocr_x        命中处的 x 坐标（整数）\n"
                + "  %xsocr_y        命中处的 y 坐标（整数）\n"
                + "  %xsocr_error    出错信息（失败时）\n"
                + "  %xsocr_time     耗时(ms)\n"
                + "  %xsocr_tier     使用的档位\n"
                + "\n坐标基于截图原始像素，可直接用于点击坐标。\n"
                + "未找到时 x / y 为 -1。");
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
    }

    private void saveConfig() {
        int srcPos = sourceSpinner.getSelectedItemPosition();
        String path = pathInput.getText() == null ? "" : pathInput.getText().toString().trim();

        if (srcPos == 1 && path.isEmpty()) {
            Toast.makeText(this, "请填写图片路径", Toast.LENGTH_SHORT).show();
            return;
        }

        Bundle cfg = new Bundle();
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

        String blurb = (srcPos == 1 ? "路径" : "截屏")
                + " · " + OCRService.tierName(tier)
                + " · " + (type == 1 ? "纯文本" : "含坐标")
                + (matchText.isEmpty() ? "" : " · " + modeName + "「" + matchText + "」");

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
        PluginContract.addRelevantVariableList(result);

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