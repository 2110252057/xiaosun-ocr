package com.xiaosun.ocr;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 滑动菜单 —— 配置页。
 *
 * ════════════════════════════════════════════════════════════════
 * 【这个功能是什么】
 * ════════════════════════════════════════════════════════════════
 * 手指按住边缘手柄朝有效方向滑，滑过触发距离后**不抬手**，
 * 直接弹出一排按钮；继续滑到某个按钮上（跟手高亮），
 * 抬手就选中它，并把选中项回传给宏。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么菜单必须由本应用（悬浮窗）弹，而不是宏】
 * ════════════════════════════════════════════════════════════════
 * Android 在手指按下（ACTION_DOWN）的那一刻，就把**这一整条触摸序列**
 * 绑给了当时命中的那个窗口；此后 MOVE / UP 只投递给它。
 * 中途新建的窗口收不到 MOVE（无法跟手高亮），也收不到 UP（无法判定选中）。
 * 官方没有任何 API 能把进行中的触摸序列转交给别的窗口/进程。
 *
 * 更致命的是：宏的 Java 动作是**一次性脚本**，跑完即退，
 * 根本没法常驻持有窗口。所以「宏自己弹菜单」这条路走不通。
 *
 * 结论：菜单画在已经拿住触摸的那个悬浮窗里（{@link EdgeGestureService}），
 * 本页只负责配置它长什么样。
 *
 * ════════════════════════════════════════════════════════════════
 * 【和宏的分工】
 * ════════════════════════════════════════════════════════════════
 *   本应用  —— 弹菜单、跟手高亮、抬手判定，把选中项写成变量
 *   宏      —— 被触发后读变量分支执行
 *
 * 关键点：宏被唤起的时刻，正是手指抬起的时刻。
 * 所以宏那边不需要常驻、也不需要处理「手指还按着」的中间态。
 *
 *   宏 → 触发器 →「小孙OCR · 边缘滑动事件」，然后：
 *     菜单索引   选中项序号（从 0 开始；-1 = 取消或没启用菜单）
 *     菜单文字   选中项的名字
 *     菜单动作   选中项的动作值
 *
 *   这三项会作为变量交给宏。变量叫什么是宿主（MacroDroid）那边
 *   由用户自己定的，本应用不预设变量名 —— 界面里也只描述
 *   「有这个值」，不写死 %xsocr_xxx 这种名字。
 *
 * 保存策略与手柄编辑页一致：**改完即存**，不设「保存」按钮。
 */
public class EdgeGestureMenuActivity extends AppCompatActivity {

    private static final int C_TEXT = 0xFF1A1D26;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_HINT = 0xFF9CA3AF;
    private static final int C_BRAND = 0xFF4F6BED;
    private static final int C_BRAND_SOFT = 0xFFEDF0FE;
    private static final int C_DIVIDER = 0xFFEAECF2;
    private static final int C_SURFACE = 0xFFFFFFFF;

    private static final int REQ_PICK_IMAGE = 0x7A01;

    /** 待回填图片来源的那个输入框（选完图后写回去）。 */
    private EditText pendingSourceInput;

    private LinearLayout content;

    /** 「热区预览」小视图，随滑块实时重画。 */
    private HitPreview hitPreview;

    /** 本页编辑中的菜单项（内存副本，改完即写回 Prefs）。 */
    private final List<EdgeGesturePrefs.MenuItem> items = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.install(this);
        reloadItems();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_SURFACE);
        root.addView(buildTopBar());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(14), dp(20), dp(40));

        buildSwitchCard();
        buildAppearanceCard();
        buildItemsCard();
        buildHowToCard();

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    // ============================================================
    //  顶部栏
    // ============================================================

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(C_BRAND);
        bar.setPadding(dp(8), dp(14), dp(8), dp(14));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(24);
        back.setTextColor(Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(40)));

        TextView title = new TextView(this);
        title.setText("滑动菜单");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧留一个等宽占位，保证标题真正居中
        bar.addView(new View(this), new LinearLayout.LayoutParams(dp(48), dp(40)));

        return bar;
    }

    // ============================================================
    //  开关
    // ============================================================

    private void buildSwitchCard() {
        LinearLayout card = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText("启用滑动菜单");
        t.setTextSize(14.5f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(C_TEXT);
        col.addView(t);

        TextView sub = new TextView(this);
        sub.setTextSize(11.5f);
        sub.setTextColor(C_HINT);
        sub.setLineSpacing(0f, 1.35f);
        sub.setText("开：滑过距离后弹出菜单，抬手选中。\n"
                + "关：滑过距离立即触发宏（原来的行为）。");
        LinearLayout.LayoutParams sl = wide();
        sl.topMargin = dp(3);
        col.addView(sub, sl);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(EdgeGesturePrefs.isMenuEnabled(this));
        sw.setOnCheckedChangeListener((b, on) -> {
            // 一项都没配的时候不给开：开了也是白开（滑动什么都不会弹），
            // 还会把「按下即触发」的 EVENT_START 一起停掉，反而更让人困惑。
            if (on && items.isEmpty()) {
                b.setChecked(false);
                toast("先添加至少一个菜单项");
                return;
            }
            EdgeGesturePrefs.setMenuEnabled(this, on);
            EdgeGesturePrefs.refreshRuntime(this);
        });
        row.addView(sw);

        card.addView(row, wide());
        content.addView(card, cardLp());
    }

    // ============================================================
    //  外观
    // ============================================================

    private void buildAppearanceCard() {
        LinearLayout card = card();
        card.addView(label("外观"), wide());

        // ---- 图标大小 ----
        addSlider(card, "图标大小",
                EdgeGesturePrefs.getMenuIconDp(this), 48, 240, "dp",
                v -> { EdgeGesturePrefs.setMenuIconDp(this, v); touchPreview(); });

        card.addView(note("图标直径。太小不好点，太大一屏放不下几项。"), topMargin(6));

        // ---- 显示文字 ----
        LinearLayout textRow = new LinearLayout(this);
        textRow.setOrientation(LinearLayout.HORIZONTAL);
        textRow.setGravity(Gravity.CENTER_VERTICAL);
        textRow.addView(label("显示文字"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch textSw = new Switch(this);
        textSw.setChecked(EdgeGesturePrefs.isMenuShowText(this));
        textSw.setOnCheckedChangeListener((b, on) -> {
            EdgeGesturePrefs.setMenuShowText(this, on);
            touchPreview();
        });
        textRow.addView(textSw);
        card.addView(textRow, topMargin(18));
        // ---- 文字大小 ----
        addSlider(card, "文字大小",
                EdgeGesturePrefs.getMenuTextDp(this), 8, 32, "dp",
                v -> { EdgeGesturePrefs.setMenuTextDp(this, v); touchPreview(); });

        // ---- 布局（只剩「斜排」一种，横排/环形已下线）----
        card.addView(label("布局"), topMargin(18));
        final TextView arcBtn = new TextView(this);
        arcBtn.setText("斜排");
        final TextView[] marks = {arcBtn};
        arcBtn.setOnClickListener(v -> {
            EdgeGesturePrefs.setMenuLayout(this, EdgeGesturePrefs.MENU_LAYOUT_ARC);
            refreshLayoutMarks(marks);
        });
        LinearLayout layoutRow = new LinearLayout(this);
        layoutRow.setOrientation(LinearLayout.HORIZONTAL);
        layoutRow.addView(arcBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(layoutRow, topMargin(8));
        refreshLayoutMarks(marks);
        card.addView(note("斜排：从手指位置起，沿 45° 斜线**从左下往右上**逐项排开；\n"
                + "一排放不下时，在它的上方再加一排。"), topMargin(10));

        // ---- 每排数量 ----
        card.addView(label("每排数量"), topMargin(18));
        addSlider(card, "每排放几项", EdgeGesturePrefs.getMenuArcPerRing(this),
                2, 12, " 项",
                v -> { EdgeGesturePrefs.setMenuArcPerRing(this, v); touchPreview(); });
        card.addView(note("每排默认放几项。超过就换一排，"
                + "第 1 排离触发点最近，越上面的排越远。"), topMargin(6));

        // ---- 逐排单独设置 ----
        // 有些排想长、有些想短，一个数不够用，所以再给一个逐排输入框。
        card.addView(label("逐排数量（可选）"), topMargin(16));
        final EditText rowEt = new EditText(this);
        rowEt.setText(rowCountsToText(EdgeGesturePrefs.getMenuRowCounts(this)));
        rowEt.setHint("留空 = 每排都用上面的值");
        rowEt.setTextSize(14);
        rowEt.setSingleLine(true);
        rowEt.setInputType(InputType.TYPE_CLASS_TEXT);
        rowEt.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 9));
        rowEt.setPadding(dp(12), dp(10), dp(12), dp(10));
        rowEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int a2, int b2, int c2) {}

            @Override public void onTextChanged(CharSequence s2, int a2, int b2, int c2) {}

            @Override public void afterTextChanged(Editable e2) {
                EdgeGesturePrefs.setMenuRowCounts(
                        EdgeGestureMenuActivity.this, parseRowCounts(e2.toString()));
                touchPreview();
            }
        });
        card.addView(rowEt, topMargin(8));
        card.addView(note("按排填写，逗号分隔。例如 \"3,5\" = 第 1 排 3 项、"
                + "第 2 排 5 项；排数不够时会自动接着补（都用上面的默认值）。"),
                topMargin(6));
        // ---- 配色 ----
        // 早先只有一个「浅色/深色」二选一开关，不够用；现在三种颜色
        // 都能直接填。描边色不用填 —— 它按圆底亮度自动取反，
        // 否则用户换个深色底，圆和背景就糊在一起了。
        card.addView(label("配色"), topMargin(18));
        addColorRow(card, "圆底颜色", EdgeGesturePrefs.getMenuBgColor(this),
                v -> EdgeGesturePrefs.setMenuBgColor(this, v));
        addColorRow(card, "文字颜色", EdgeGesturePrefs.getMenuTextColor(this),
                v -> EdgeGesturePrefs.setMenuTextColor(this, v));
        addColorRow(card, "选中颜色", EdgeGesturePrefs.getMenuSelColor(this),
                v -> EdgeGesturePrefs.setMenuSelColor(this, v));
        card.addView(note("填十六进制：#RRGGBB 或 #AARRGGBB（前两位是透明度，"
                + "FF = 不透明）。\n例如 #FFFFFFFF 白、#CC222222 半透明深灰、"
                + "#FF00E5FF 青。\n描边颜色会按圆底深浅自动调整。"), topMargin(8));
        // ---- 间距与热区 ----
        addSlider(card, "文字间距",
                EdgeGesturePrefs.getMenuTextGap(this), 0, 40, "dp",
                v -> { EdgeGesturePrefs.setMenuTextGap(this, v); touchPreview(); });
        addSlider(card, "图标间距",
                EdgeGesturePrefs.getMenuGap(this), 0, 60, "dp",
                v -> { EdgeGesturePrefs.setMenuGap(this, v); touchPreview(); });
        addSlider(card, "每排间距",
                EdgeGesturePrefs.getMenuRingGap(this), 0, 80, "dp",
                v -> { EdgeGesturePrefs.setMenuRingGap(this, v); touchPreview(); });
        addSlider(card, "热区大小",
                EdgeGesturePrefs.getMenuHitScale(this), 100, 300, "%",
                v -> { EdgeGesturePrefs.setMenuHitScale(this, v); touchPreview(); });
        card.addView(note("「图标间距」按**图标边缘之间的距离**算：0 = 两个图标紧挨着"
                + "（最紧凑），调大就把它们拉开。\n"
                + "「文字间距」是**文字顶边离图标底边**的距离，0 = 紧贴图标。"),
                topMargin(6));
        card.addView(note("感应范围相对图标的倍率。100% = 与图标一样大；"
                + "调大后手指不用精确碰到图标也能选中。"), topMargin(6));

        // ---- 热区预览 ----
        // 光看数字体会不到「感应范围到底多大」，尤其热区一大、
        // 相邻两项的判定圈就会互相重叠 —— 这里直接把两者一起画出来。
        card.addView(label("热区预览"), topMargin(18));
        hitPreview = new HitPreview();
        card.addView(hitPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));
        card.addView(note("整盘菜单以**触发点**为中心轴对称展开；\n"
                + "橙圆 = 第 1 项（第 1 排的中间那个就压在触发点上）；\n"
                + "蓝圈 = 手指能选中它的范围，相叠时取最近的那一项。"), topMargin(6));
        content.addView(card, cardLp());
    }

    private void refreshLayoutMarks(TextView[] marks) {
        // 只剩「斜排」一种布局，恒为选中态
        for (TextView tv : marks) paintChoice(tv, true);
    }

    private void paintChoice(TextView tv, boolean on) {
        tv.setTextSize(13.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(4), dp(10), dp(4), dp(10));
        tv.setTextColor(on ? C_BRAND : C_SUB);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        g.setColor(on ? C_BRAND_SOFT : C_SURFACE);
        g.setStroke(dp(1), on ? C_BRAND : C_DIVIDER);
        tv.setBackground(g);
    }

    // ============================================================
    //  菜单项列表
    // ============================================================

    private void buildItemsCard() {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(items.isEmpty() ? "菜单项" : "菜单项 · " + items.size() + " 项");
        t.setTextSize(14.5f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(C_TEXT);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = new TextView(this);
        add.setText("＋ 添加");
        add.setTextSize(13);
        add.setTextColor(C_BRAND);
        add.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(C_BRAND_SOFT);
        ab.setCornerRadius(dp(8));
        add.setBackground(ab);
        add.setOnClickListener(v -> editItem(-1));
        head.addView(add);
        card.addView(head, wide());

        if (items.isEmpty()) {
            card.addView(note("还没有菜单项。点右上「添加」，"
                    + "建议按顺序放：截图 / OCR / 翻译 之类。"), topMargin(10));
        } else {
            card.addView(note("点一下改内容，**长按**可以上移 / 下移 / 删除。\n"
                    + "左上角数字就是序号 —— 也是宏里收到的「菜单索引」"
                    + "（从 0 开始，取消是 -1）。"), topMargin(8));
            // 双列网格：项数一多，一行一项要滚很久；两列能省掉一半高度，
            // 每格也更大、更好点。
            final int cols = 2;
            for (int i = 0; i < items.size(); i += cols) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int j = 0; j < cols; j++) {
                    final int idx = i + j;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (j > 0) lp.leftMargin = dp(10);
                    if (idx < items.size()) {
                        row.addView(buildItemCell(idx), lp);
                    } else {
                        // 末行只有一个时补个空位，免得它被拉成整行宽
                        row.addView(new View(this), lp);
                    }
                }
                card.addView(row, topMargin(10));
            }
        }
        content.addView(card, cardLp());
    }

    /**
     * 网格里的一格：序号 + 图标 + 名字。
     *
     * <p>早先一行一项、行尾还挂着上移/下移/编辑/删除四个小按钮 ——
     * 项一多，整页就是一条长长的按钮带，既占地方又难点。
     * 现在四个操作收进长按菜单，格子里只留「看一眼就知道是什么」的信息。
     */
    private View buildItemCell(final int index) {
        // 网格与数据源之间隔着一次 rebuild()，理论上不会错位；
        // 但真错位了只该少画一格，不该闪退。
        if (index < 0 || index >= items.size()) {
            AppLog.w("XsOcrMenu", "菜单项下标越界，跳过该格: " + index + "/" + items.size());
            return new View(this);
        }
        final EdgeGesturePrefs.MenuItem it = items.get(index);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 12));
        cell.setPadding(dp(12), dp(11), dp(12), dp(11));

        // ---- 上排：序号角标 + 图标 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView no = new TextView(this);
        no.setText(String.valueOf(index));
        no.setTextSize(11);
        no.setTypeface(null, android.graphics.Typeface.BOLD);
        no.setTextColor(C_BRAND);
        no.setGravity(Gravity.CENTER);
        GradientDrawable nb = new GradientDrawable();
        nb.setColor(C_BRAND_SOFT);
        nb.setCornerRadius(dp(6));
        no.setBackground(nb);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(20), dp(20));
        nl.rightMargin = dp(9);
        top.addView(no, nl);

        FrameLayout thumb = new FrameLayout(this);
        Drawable icon = MenuIcons.load(this, it.source);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setCornerRadius(dp(8));
        tbg.setColor(C_BRAND_SOFT);
        thumb.setBackground(tbg);
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int pad = dp(5);
            iv.setPadding(pad, pad, pad, pad);
            thumb.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            TextView ch = new TextView(this);
            // 与悬浮窗保持同一套规则：没图标时显示前两个字（只有一个就显示一个）。
            // 缩略图格子只有 32dp，两个字得把字号收一点才装得下。
            String label = it.text.length() > 2 ? it.text.substring(0, 2) : it.text;
            ch.setText(label.length() > 0 ? label : "?");
            ch.setTextSize(label.length() > 1 ? 11f : 14);
            ch.setTypeface(null, android.graphics.Typeface.BOLD);
            ch.setTextColor(C_BRAND);
            ch.setGravity(Gravity.CENTER);
            thumb.addView(ch, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        top.addView(thumb, new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView name = new TextView(this);
        name.setText(it.text);
        name.setTextSize(13.5f);
        name.setTextColor(C_TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nml = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nml.leftMargin = dp(9);
        top.addView(name, nml);
        cell.addView(top, wide());

        // ---- 下排：来源（图标从哪来）----
        TextView meta = new TextView(this);
        meta.setText(MenuIcons.describeSource(it.source));
        meta.setTextSize(11);
        meta.setTextColor(C_HINT);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(meta, topMargin(7));

        cell.setOnClickListener(v -> editItem(index));
        cell.setOnLongClickListener(v -> {
            showItemMenu(index);
            return true;
        });
        return cell;
    }

    /** 长按菜单项弹出的操作表（上移 / 下移 / 编辑 / 删除）。 */
    private void showItemMenu(final int index) {
        if (index < 0 || index >= items.size()) {
            AppLog.w("XsOcrMenu", "长按菜单项下标越界: " + index + "/" + items.size());
            return;
        }
        final EdgeGesturePrefs.MenuItem it = items.get(index);
        final String[] ops = {"上移", "下移", "编辑内容", "删除"};
        new AlertDialog.Builder(this)
                .setTitle("「" + it.text + "」")
                .setItems(ops, (d, w) -> {
                    if (w == 0) {
                        moveItem(index, -1);
                    } else if (w == 1) {
                        moveItem(index, 1);
                    } else if (w == 2) {
                        editItem(index);
                    } else {
                        removeItem(index);
                    }
                })
                .show();
    }

    private void moveItem(int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= items.size()) {
            // 长按菜单里「上移/下移」在首尾是点不动的，
            // 静默失败会让人以为菜单坏了，给一句说明。
            toast(index == 0 && delta < 0 ? "已经是第一项了" : "已经是最后一项了");
            return;
        }
        EdgeGesturePrefs.MenuItem it = items.remove(index);
        items.add(to, it);
        persist();
        rebuild();
    }

    private void removeItem(int index) {
        if (index < 0 || index >= items.size()) return;
        final String name = items.get(index).text;
        new AlertDialog.Builder(this)
                .setTitle("删除菜单项")
                .setMessage("确定要删除「" + name + "」吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    items.remove(index);
                    persist();
                    rebuild();
                })
                .show();
    }

    // ============================================================
    //  菜单项编辑对话框
    // ============================================================

    private void editItem(final int index) {
        final boolean isNew = (index < 0);
        EdgeGesturePrefs.MenuItem cur;
        if (isNew) {
            cur = new EdgeGesturePrefs.MenuItem();
        } else {
            cur = items.get(index);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);

        final EditText etName = new EditText(this);
        final EditText etAction = new EditText(this);
        final EditText etSource = new EditText(this);

        box.addView(label("名字"), wide());
        etName.setHint("例如：截图");
        etName.setSingleLine(true);
        etName.setText(cur.text);
        etName.setTextSize(15);
        etName.setTextColor(C_TEXT);
        etName.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(etName, topMargin(4));

        box.addView(note("显示在菜单里的文字，必填。"), topMargin(4));

        box.addView(label("动作值（回传给宏）"), topMargin(16));
        etAction.setHint("留空就和名字一样");
        etAction.setSingleLine(true);
        etAction.setText(cur.action);
        etAction.setTextSize(15);
        etAction.setTextColor(C_TEXT);
        etAction.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(etAction, topMargin(4));

        box.addView(note("这个值会交给宏，用来区分用户点了哪一项。\n"
                + "建议填英文短名（如 shot / ocr / trans），比序号好读。"), topMargin(4));

        box.addView(label("图标"), topMargin(16));

        LinearLayout srcRow = new LinearLayout(this);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.setGravity(Gravity.CENTER_VERTICAL);

        etSource.setHint("app:包名 / file:路径 / 资源名");
        etSource.setSingleLine(true);
        etSource.setText(cur.source);
        etSource.setTextSize(14);
        etSource.setTextColor(C_TEXT);
        etSource.setInputType(InputType.TYPE_CLASS_TEXT);
        srcRow.addView(etSource, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(40), dp(40));
        pl.leftMargin = dp(10);
        srcRow.addView(preview, pl);

        box.addView(srcRow, topMargin(6));

        final TextView srcHint = new TextView(this);
        srcHint.setTextSize(11.5f);
        srcHint.setTextColor(C_HINT);
        srcHint.setLineSpacing(0f, 1.35f);
        box.addView(srcHint, topMargin(6));

        // 预览随输入实时刷新 —— 包名打错一个字母立刻看得出来
        final Runnable refreshPreview = new Runnable() {
            @Override
            public void run() {
                String src = etSource.getText().toString();
                Drawable d = MenuIcons.load(EdgeGestureMenuActivity.this, src);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(9));
                if (d != null) {
                    preview.setImageDrawable(d);
                    preview.setBackground(null);
                } else {
                    preview.setImageDrawable(null);
                    g.setColor(src.trim().length() == 0 ? C_BRAND_SOFT : 0x22F04438);
                    preview.setBackground(g);
                }
                srcHint.setText(MenuIcons.describeSource(src)
                        + "\n\napp:com.tencent.mm   取应用图标（最省事）\n"
                        + "file:/sdcard/a.png   用图片文件\n"
                        + "ic_launcher          本应用内置图片");
            }
        };
        etSource.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                refreshPreview.run();
            }
        });
        refreshPreview.run();

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(smallButton("选图片…", v -> {
            pendingSourceInput = etSource;
            pickImage();
        }));
        tools.addView(smallButton("清空图标", v -> etSource.setText("")));
        box.addView(tools, topMargin(10));

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(isNew ? "添加菜单项" : "编辑菜单项");
        b.setView(box);
        b.setNegativeButton("取消", null);
        if (!isNew) {
            b.setNeutralButton("删除", (d, w) -> removeItem(index));
        }
        b.setPositiveButton(isNew ? "添加" : "确定", (d, w) -> {
            String name = etName.getText().toString().trim();
            if (name.length() == 0) {
                toast("名字不能为空");
                return;
            }
            EdgeGesturePrefs.MenuItem it = isNew
                    ? new EdgeGesturePrefs.MenuItem() : items.get(index);

            it.text = name;
            String act = etAction.getText().toString().trim();
            it.action = act.length() == 0 ? name : act;
            it.source = etSource.getText().toString().trim();

            if (isNew) items.add(it);
            persist();
            rebuild();
        });
        b.show();
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(C_BRAND);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(C_BRAND_SOFT);
        b.setBackground(g);
        b.setPadding(dp(14), dp(4), dp(14), dp(4));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * 选一张本地图片当图标。
     *
     * 选完会**复制进应用私有目录**（而不是直接引用所选位置）：
     *  · SAF 给回来的是 content:// URI，本应用没有长期读取权限，
     *    悬浮窗那边过一会儿再读就可能失败；
     *  · 用户删掉原图 / 拔掉 SD 卡，图标就没了；
     *  · file: 形式需要的是**文件路径**，content:// 给不出来。
     * 复制到 getExternalFilesDir 之后路径稳定、随应用卸载一起清理。
     */
    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_PICK_IMAGE);
        } catch (Throwable t) {
            toast("没有可用的图片选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_IMAGE) return;
        if (res != RESULT_OK || data == null || data.getData() == null) return;

        EditText target = pendingSourceInput;
        pendingSourceInput = null;
        if (target == null) return;

        String path = importImage(data.getData());
        if (path == null) {
            toast("图片读取失败");
            return;
        }
        target.setText("file:" + path);
    }

    /** 把选中的图片复制到私有目录，返回绝对路径。 */
    private String importImage(Uri uri) {
        InputStream in = null;
        OutputStream out = null;
        try {
            File dir = new File(getExternalFilesDir(null), "menu_icons");
            if (!dir.exists() && !dir.mkdirs()) return null;

            File f = new File(dir, "icon_" + System.currentTimeMillis() + ".png");
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;

            out = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return f.getAbsolutePath();
        } catch (Throwable t) {
            android.util.Log.w("XsOcrMenuIcon", "导入图片失败: " + t);
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    // ============================================================
    //  说明卡片
    // ============================================================

    private void buildHowToCard() {
        LinearLayout card = card();
        card.addView(label("怎么和宏配合"), wide());

        TextView t = new TextView(this);
        t.setTextSize(12);
        t.setTextColor(C_SUB);
        t.setLineSpacing(0f, 1.45f);
        t.setText(
                "宏 → 触发器 → 「小孙OCR · 边缘滑动事件」\n"
                        + "  给这三个值各配一个你自己的宏变量：\n"
                        + "    菜单索引   0 / 1 / 2 …（-1 = 取消，什么都没选）\n"
                        + "    菜单文字   选中项的名字（截图 / OCR …）\n"
                        + "    菜单动作   选中项的动作值（shot / ocr …）\n\n"
                        + "  变量名由你在宿主里自己取，本应用不限定名字。\n"
                        + "  动作列表里加「如果」判断即可，例如：\n"
                        + "    菜单索引 = -1      → 取消，什么都不做\n"
                        + "    菜单索引 = 0       → 第 1 项\n"
                        + "    菜单动作 = shot    → 按动作值判断（比序号直观）\n\n"
                        + "抬手那一刻才会触发宏，所以宏里可以直接执行动作，\n"
                        + "不需要等、也没有「手指还按着」的问题。"
        );
        LinearLayout.LayoutParams tl = wide();
        tl.topMargin = dp(8);
        card.addView(t, tl);

        TextView tip = new TextView(this);
        tip.setTextSize(11.5f);
        tip.setTextColor(C_HINT);
        tip.setLineSpacing(0f, 1.35f);
        tip.setText("\n小提示：菜单弹出后，手指滑到按钮上会高亮；"
                + "滑到空白处松手＝取消（index = -1）。");
        card.addView(tip, topMargin(8));

        content.addView(card, cardLp());
    }

    // ============================================================
    //  存取
    // ============================================================

    private void reloadItems() {
        // 这里的解析结果直接决定菜单长什么样，一旦遇到脏数据
        // （手改过的配置、旧版本遗留格式）不能让整个配置页打不开 ——
        // 页面都进不去的话，用户连「改回来」的机会都没有。
        try {
            items.clear();
            items.addAll(EdgeGesturePrefs.getMenuItems(this));
        } catch (Throwable t) {
            AppLog.e("XsOcrMenu", "读取菜单配置失败，按空菜单处理", t);
            items.clear();
        }
    }

    private void persist() {
        EdgeGesturePrefs.setMenuItemsRaw(this, EdgeGesturePrefs.joinMenuItems(items));
        EdgeGesturePrefs.refreshRuntime(this);
        touchPreview();   // 项数变了，预览的排数也跟着变
        // 清空所有项时顺手把关掉，避免留下「开着菜单却没有项」的状态：
        // 那种状态下滑过阈值什么都不会弹，还会把「按下即触发」一并停掉。
        if (items.isEmpty() && EdgeGesturePrefs.isMenuEnabled(this)) {
            EdgeGesturePrefs.setMenuEnabled(this, false);
        }
    }

    private void rebuild() {
        content.removeAllViews();
        buildSwitchCard();
        buildAppearanceCard();
        buildItemsCard();
        buildHowToCard();
    }

    // ============================================================
    //  通用控件
    // ============================================================

    private interface OnIntValue {
        void on(int v);
    }

    private interface OnColor {
        void on(int argb);
    }

    /**
     * 一行「标签 + 色块 + 十六进制输入框」。
     *
     * <p>输入过程中（比如刚敲了 {@code #FF}）不落盘，否则每敲一个字符
     * 都会把配置改成错的值、颜色跟着乱闪。
     */
    private void addColorRow(LinearLayout parent, String name, int cur, OnColor onSet) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(label(name), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final View swatch = new View(this);
        paintSwatch(swatch, cur);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(24), dp(24));
        sl.rightMargin = dp(8);
        row.addView(swatch, sl);

        final EditText et = new EditText(this);
        et.setText(EdgeGesturePrefs.colorToText(cur));
        et.setTextSize(12.5f);
        et.setSingleLine(true);
        et.setGravity(Gravity.CENTER);
        et.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 9));
        et.setPadding(dp(4), dp(8), dp(4), dp(8));
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int a2, int b2, int c2) {}

            @Override public void onTextChanged(CharSequence s2, int a2, int b2, int c2) {}

            @Override public void afterTextChanged(Editable e2) {
                String txt = e2.toString().trim();
                if (txt.length() < 6) return;   // 还在输入中，别落盘
                int v = EdgeGesturePrefs.parseColor(txt, Integer.MIN_VALUE);
                if (v == Integer.MIN_VALUE) return;   // 解析不了，等用户改完
                paintSwatch(swatch, v);
                onSet.on(v);
                touchPreview();
            }
        });
        row.addView(et, new LinearLayout.LayoutParams(dp(116),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(row, topMargin(12));
    }

    /** 画色块（预览当前颜色）。 */
    private void paintSwatch(View v, int argb) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        g.setColor(argb);
        g.setStroke(dp(1), C_DIVIDER);
        v.setBackground(g);
    }

    /**
     * 一行「标签 + 数值 + 滑杆」。
     *
     * 注意 minSdk 是 24，而 SeekBar.setMin() 是 API 26 才有的，
     * 所以用「0..(max-min) 的内部进度 + min 偏移」的老办法。
     */
    private void addSlider(LinearLayout parent, String name, int value,
                           int min, int max, String unit, OnIntValue onChange) {
        if (max <= min) max = min + 1;
        final int lo = min, span = max - min;

        parent.addView(label(name), topMargin(16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView val = new TextView(this);
        val.setText(value + unit);
        val.setTextSize(13.5f);
        val.setTextColor(C_BRAND);
        val.setTypeface(null, android.graphics.Typeface.BOLD);
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(dp(64),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar sb = new SeekBar(this);
        int clamped = Math.max(min, Math.min(max, value));
        sb.setMax(span);
        sb.setProgress(clamped - lo);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int v = lo + progress;
                val.setText(v + unit);
                if (fromUser) onChange.on(v);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                onChange.on(lo + bar.getProgress());   // 松手再补一次，确保落盘
            }
        });

        row.addView(sb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, wide());
    }

    /** 逐排数量数组 → 输入框文本。 */
    private static String rowCountsToText(int[] counts) {
        if (counts == null || counts.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int v : counts) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        return sb.toString();
    }

    /** 输入框文本 → 逐排数量数组。容忍中文逗号、空格等写法。 */
    private static int[] parseRowCounts(String raw) {
        if (raw == null) return new int[0];
        String t = raw.trim().replace('，', ',').replace('、', ',')
                .replace(' ', ',');
        if (t.length() == 0) return new int[0];
        String[] parts = t.split(",");
        List<Integer> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p == null) continue;
            String q = p.trim();
            if (q.length() == 0) continue;
            try {
                out.add(Math.max(1, Math.min(20, Integer.parseInt(q))));
            } catch (NumberFormatException ignored) {
                // 手输的脏数据跳过，不影响其它排
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** 参数变了就重画一次预览。 */
    private void touchPreview() {
        if (hitPreview != null) hitPreview.invalidate();
    }

    /**
     * 热区预览。
     *
     * 用与 {@link EdgeGestureService#layoutMenuLine} 同一套斜排算法算出
     * 各图标的位置，再把「感应范围」叠着画上去 —— 这样在设置页调滑块
     * 就能立刻看到范围，不必来回装机试。
     *
     * 之所以不复用悬浮窗那份代码：那边要处理窗口尺寸、出屏退让、
     * 触摸序列等一堆运行时状态，搬到设置页只会把两边都搞复杂。
     * 预览只关心「排布长什么样」，所以这里用最直白的写法重算一遍。
     */
    private class HitPreview extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        HitPreview() {
            super(EdgeGestureMenuActivity.this);
        }

        /** 菜单项为空时的提示 —— 空菜单是正常状态，说明一下比留白强。 */
        private void drawEmptyHint(Canvas cv) {
            try {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(0xFF9CA3AF);
                p.setTextSize(dp(12));
                p.setTextAlign(Paint.Align.CENTER);
                String s = "菜单还是空的，先在下面「菜单项」里添加";
                cv.drawText(s, getWidth() / 2f, getHeight() / 2f, p);
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            // 预览是「边拖滑块边重绘」的视图，频率很高。
            // 它只是配置页里的一张示意图 —— 画不出来只该是「图没了」，
            // 绝不能把整个设置页带崩（onDraw 抛异常会一路冒到
            // ViewRootImpl，直接杀进程）。
            try {
                onDrawInner(cv);
            } catch (Throwable t) {
                AppLog.e("XsOcrMenu", "预览绘制异常（已跳过本帧） 项数=" + items.size(), t);
            }
        }

        private void onDrawInner(Canvas cv) {
            Context c = EdgeGestureMenuActivity.this;

            // ★【崩溃修复】这里原来写的是 Math.max(1, items.size())：
            //   意图大概是「空菜单时也画点东西」，但那样会把 n 抬成 1，
            //   而后面的 items.get(i) 就会在 items 为空时越界：
            //       IndexOutOfBoundsException: Index 0 out of bounds for length 0
            //   菜单项为空是**完全正常的初始状态**（新装用户、或刚删完
            //   最后一项），而进这一页必然触发预览绘制 —— 于是必崩。
            //   正确做法：用真实项数，空的时候画一句提示就够了。
            int n = items.size();
            if (n == 0) {
                drawEmptyHint(cv);
                return;
            }

            float iconPx = dp(EdgeGesturePrefs.getMenuIconDp(c));
            if (iconPx <= 0f) return;

            float gap = dp(EdgeGesturePrefs.getMenuGap(c));
            int defPerRow = Math.max(1, EdgeGesturePrefs.getMenuArcPerRing(c));
            int[] perRow = EdgeGesturePrefs.getMenuRowCounts(c);
            // 与悬浮窗一致：基准是图标直径，0 = 图标紧挨
            float rowGap = iconPx + dp(EdgeGesturePrefs.getMenuRingGap(c));
            float sp = iconPx + gap;
            final float dirX = 0.70710678f, dirY = -0.70710678f;   // 左下 → 右上
            final float offX = -0.70710678f, offY = -0.70710678f;  // 新排往左上

            // 按逐排数量切开（与悬浮窗同一套规则）
            int[] cnt = new int[n + 1];
            int rows = 0, left = n;
            while (left > 0 && rows <= n) {
                int take = (rows < perRow.length) ? perRow[rows] : defPerRow;
                if (take < 1) take = 1;
                if (take > left) take = left;
                cnt[rows] = take;
                left -= take;
                rows++;
            }

            // ★ 中心轴过触发点，每排沿 45° 方向居中（与悬浮窗同一套规则）
            float[] rx = new float[n], ry = new float[n];
            float minX = 0f, minY = 0f, maxX = 0f, maxY = 0f;
            int idx = 0;
            for (int r = 0; r < rows && idx < n; r++) {
                for (int col = 0; col < cnt[r] && idx < n; col++) {
                    // ★ 每排沿 45° 方向**居中**（与悬浮窗同一套规则）：
                    //   以「过触发点、沿 45° 的中心轴」为基准，第 col 个
                    //   相对轴线的偏移 = (col − 本排中点) × 间距。
                    //   早先每排左端对齐，逐排数量不同就歪成斜三角。
                    float u = (col - (cnt[r] - 1) * 0.5f) * sp;
                    float x = u * dirX + r * rowGap * offX;
                    float y = u * dirY + r * rowGap * offY;
                    rx[idx] = x;
                    ry[idx] = y;
                    float rad = iconPx / 2f;
                    if (idx == 0) {
                        minX = x - rad; maxX = x + rad;
                        minY = y - rad; maxY = y + rad;
                    } else {
                        minX = Math.min(minX, x - rad);
                        maxX = Math.max(maxX, x + rad);
                        minY = Math.min(minY, y - rad);
                        maxY = Math.max(maxY, y + rad);
                    }
                    idx++;
                }
            }

            // 等比缩放到预览框里，再居中
            float pad = dp(10);
            float availW = Math.max(dp(4), getWidth() - pad * 2f);
            float availH = Math.max(dp(4), getHeight() - pad * 2f);
            float envW = Math.max(1f, maxX - minX);
            float envH = Math.max(1f, maxY - minY);
            float k = Math.min(1f, Math.min(availW / envW, availH / envH));
            float ox = pad - minX * k + Math.max(0f, (availW - envW * k) / 2f);
            float oy = pad - minY * k + Math.max(0f, (availH - envH * k) / 2f);

            float iconR = iconPx / 2f * k;
            float hitR = iconR * EdgeGesturePrefs.getMenuHitScale(c) / 100f;

            for (int i = 0; i < n; i++) {
                float x = ox + rx[i] * k;
                float y = oy + ry[i] * k;

                // 感应范围：先画，免得盖住图标
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x334F6BED);
                cv.drawCircle(x, y, hitR, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1));
                p.setColor(0x884F6BED);
                cv.drawCircle(x, y, hitR, p);

                // 图标本身：填充用配置的圆底色，预览才和实机一致。
                // 第 1 项就是**触发点**所在，用橙色描边区分出来，
                // 用户一眼能看出菜单是从哪儿开始铺的。
                p.setStyle(Paint.Style.FILL);
                p.setColor(EdgeGesturePrefs.getMenuBgColor(c));
                cv.drawCircle(x, y, iconR, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(i == 0 ? 0xFFF57C00 : 0xFFAAB2C8);
                cv.drawCircle(x, y, iconR, p);
                // 文字标签：字号与间距都按预览的缩放比缩过，
                // 这样调「文字大小 / 文字间距」能当场看到效果。
                if (EdgeGesturePrefs.isMenuShowText(c)) {
                    // 双保险：n 现在取自 items.size()，正常不会越界；
                    // 但重绘可能发生在「改项」的中间态，多一次长度检查
                    // 的代价远小于再崩一次。
                    if (i >= items.size()) continue;
                    String txt = items.get(i).text;
                    if (txt != null && txt.length() > 0) {
                        float tx = dp(EdgeGesturePrefs.getMenuTextDp(c)) * k;
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(EdgeGesturePrefs.getMenuTextColor(c));
                        p.setTextSize(tx);
                        p.setTextAlign(Paint.Align.CENTER);
                        Paint.FontMetrics fm = p.getFontMetrics();
                        float ty = y + iconR
                                + dp(EdgeGesturePrefs.getMenuTextGap(c)) * k
                                - fm.ascent;
                        cv.drawText(txt, x, ty, p);
                    }
                }
            }
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 14));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setElevation(dp(1));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = wide();
        lp.bottomMargin = dp(12);
        return lp;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(C_SUB);
        return tv;
    }

    private TextView note(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setTextColor(C_HINT);
        tv.setLineSpacing(0f, 1.35f);
        return tv;
    }

    private GradientDrawable borderedBg(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(dp);
        return lp;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 供列表页复用。 */
    public static void open(Context ctx) {
        Intent i = new Intent(ctx, EdgeGestureMenuActivity.class);
        if (!(ctx instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }
}