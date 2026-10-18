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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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
    /**
     * 菜单项每行的最小高度（dp）。
     *
     * 拖动排序用它当「步长」的一部分：跨界数 = 位移 ÷ (行高 + 行间距)。
     * 行高若随内容浮动（名字长短、有没有副标题），步长就跟着变，
     * 拖三行可能只换了两次位 —— 所以这里给死下限。
     */
    private static final int ROW_HEIGHT_DP = 66;

    /** 待回填图片来源的那个输入框（选完图后写回去）。 */
    private EditText pendingSourceInput;

    private LinearLayout content;

    /**
     * 外层滚动容器。
     *
     * 提成成员是因为 rebuild() 会整页重画，而重画之后 ScrollView 的滚动
     * 位置会回到顶部 —— 拖完一个菜单项，视线「啪」地跳回页首，
     * 完全不知道刚才拖的那项去了哪儿。所以要记一下位置、重建后放回去。
     */
    private ScrollView scroller;

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
        scroller = sv;

        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    /**
     * 页面销毁时掐掉自动滚动。
     *
     * <p>拖动排序用的边缘自动滚动是一个「postOnAnimation 自己排自己」的
     * Runnable —— 只要 dragView 不为 null 就一帧接一帧地滚。它持有 Activity
     * 的引用，若退出页面时不停，轻则继续滚一个已经不存在的 ScrollView，
     * 重则把整个 Activity 泄漏掉（并可能在已 detach 的视图上抛异常）。
     */
    @Override
    protected void onDestroy() {
        stopAutoScroll();
        dragView = null;
        dragBox = null;
        scroller = null;
        super.onDestroy();
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
        sub.setText("开：滑过距离弹出菜单，抬手选中。关：立即触发宏。\n"
                + "这是总开关。某个方向不想弹菜单（想滑一下直接触发），"
                + "到「滑动触发 → 手柄 → 可触发方向」把那个方向的开关切成「直触」。");
        LinearLayout.LayoutParams sl = wide();
        sl.topMargin = dp(3);
        col.addView(sub, sl);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(EdgeGesturePrefs.isMenuEnabled(this));
        sw.setOnCheckedChangeListener((b, on) -> {
            // 一项都没配的时候不给开：开了也是白开（滑动什么都不会弹）。
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
        card.addView(note("斜排：从手指位置起，沿 45° 斜线从左下往右上逐项排开；\n"
                + "一排放不下时，在它上方再加一排。"), topMargin(10));

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
        card.addView(note("十六进制 #RRGGBB 或 #AARRGGBB，前两位是透明度（FF = 不透明）。"),
                topMargin(8));
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
        card.addView(note("「图标间距」：两个图标边缘之间的距离，0 = 紧挨着（最紧凑）。\n"
                + "「文字间距」：文字顶边离图标底边的距离，0 = 紧贴图标。"), topMargin(6));
        card.addView(note("感应范围相对图标的倍率。100% = 与图标一样大；"
                + "调大后手指不用精确碰到图标也能选中。"), topMargin(6));

        // ---- 热区预览 ----
        // 光看数字体会不到「感应范围到底多大」，尤其热区一大、
        // 相邻两项的判定圈就会互相重叠 —— 这里直接把两者一起画出来。
        card.addView(label("热区预览"), topMargin(18));
        hitPreview = new HitPreview();
        card.addView(hitPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));
        card.addView(note("橙圆 = 第 1 项（压在触发点上）；蓝圈 = 手指能选中它的范围，"
                + "相叠时取最近的那一项。"), topMargin(6));
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
            card.addView(note("点一下改内容，长按并直接拖动即可调整顺序。\n"
                    + "左上角数字是序号，也就是宏里收到的「菜单索引」（从 0 开始，取消是 -1）。"),
                    topMargin(8));
            /*
             * 【为什么从双列网格改回竖排】
             * 网格两列是为了「项一多不用滚太久」，但拖拽重排必须是竖排：
             * 两列的话，「往下拖」到底是插到左边那列的下一格还是右边那列，
             * 没有唯一答案，手感会很怪。
             *
             * 单列一行 60dp 出头、一屏还能放五六项，为此换来「拖着排」
             * 这个比「长按 → 上移」直观得多的操作，是划算的。
             */
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < items.size(); i++) {
                // 每行都留同样的上间距：拖动换位时要靠「行高 + 间距」来
                // 补偿位移，间距统一了补偿量才是准的（见 swapRows）。
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                box.addView(buildItemRow(i, box), lp);
            }
            card.addView(box, topMargin(2));
        }
        content.addView(card, cardLp());
    }

    /**
     * 列表里的一行：序号 + 图标 + 名字/来源 + 「⋯」+「≡」。
     *
     * <p>早先一行一项、行尾还挂着上移/下移/编辑/删除四个小按钮 ——
     * 项一多，整页就是一条长长的按钮带，既占地方又难点。后来收进长按菜单，
     * 再后来长按又被「拖动排序」占用了，于是三个操作重新分工：
     *
     *   点一下     → 改内容（最常用）
     *   长按并拖   → 调整顺序（本次新增，比反复点「上移」直观得多）
     *   点尾部「⋯」→ 编辑 / 删除（少用，但必须有入口）
     *
     * <p>【行高为什么至少是 ROW_HEIGHT_DP】
     * 拖动换位靠「一行 + 间距」当步长来算目标位置：位移除以步长四舍五入
     * 就是跨了几行。所以 {@link #buildItemsCard} 给每行都挂了同样的
     * {@code topMargin}，这里再保证同一下限高度 —— 两者都恒定，跨行数才是准的。
     * 名字太长会自动截断（单行 ellipsize），不会把行撑高。
     */
    private View buildItemRow(final int index, final LinearLayout box) {
        // 列表与数据源之间隔着一次 rebuild()，理论上不会错位；
        // 但真错位了只该少画一行，不该闪退。
        if (index < 0 || index >= items.size()) {
            AppLog.w("XsOcrMenu", "菜单项下标越界，跳过该行: " + index + "/" + items.size());
            return new View(this);
        }
        final EdgeGesturePrefs.MenuItem it = items.get(index);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(borderedBg(C_SURFACE, C_DIVIDER, 12));
        row.setPadding(dp(12), dp(8), dp(10), dp(8));
        // 固定高度：拖动步长靠它，见本方法注释
        row.setMinimumHeight(dp(ROW_HEIGHT_DP));

        // ---- 序号角标 ----
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
        row.addView(no, nl);

        // ---- 图标缩略图 ----
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
            // 缩略图只有 32dp，两个字得把字号收一点才装得下。
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
        row.addView(thumb, new LinearLayout.LayoutParams(dp(32), dp(32)));

        // ---- 名字 + 来源（竖列，占满剩余宽度）----
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(it.text);
        name.setTextSize(13.5f);
        name.setTextColor(C_TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name, wide());

        TextView meta = new TextView(this);
        meta.setText(MenuIcons.describeSource(it.source));
        meta.setTextSize(11);
        meta.setTextColor(C_HINT);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(meta, topMargin(3));

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.leftMargin = dp(9);
        row.addView(col, cl);

        // ---- 「⋯」：编辑 / 删除。长按已被拖动占用，删除得另找入口 ----
        TextView more = new TextView(this);
        more.setText("⋯");
        more.setTextSize(17);
        more.setTextColor(C_HINT);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("更多操作");
        more.setPadding(dp(8), dp(4), dp(8), dp(4));
        more.setOnClickListener(v -> showItemMenu(index));
        row.addView(more, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 「≡」：拖动手柄（只是视觉提示，整行都能拖）----
        TextView handle = new TextView(this);
        handle.setText("≡");
        handle.setTextSize(17);
        handle.setTextColor(C_HINT);
        handle.setGravity(Gravity.CENTER);
        handle.setPadding(dp(6), dp(4), dp(2), dp(4));
        row.addView(handle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(v -> editItem(index));
        // 长按进入拖动态；此时还不能确定松手后会不会换位，所以这里只置状态，
        // 真正的「有没有移动」在 MOVE / UP 里判。
        row.setOnLongClickListener(v -> beginDrag(v, index, box));
        row.setOnTouchListener((v, e) -> onRowTouch(e));
        return row;
    }

    // ============================================================
    //  长按拖动排序
    // ============================================================
    /*
     * 【为什么不用 RecyclerView + ItemTouchHelper】
     * 本工程没有任何 androidx.recyclerview 依赖（全部界面都是 new LinearLayout
     * 手搭的），为了一个排序功能引入一个库不划算；而且 ItemTouchHelper 也只
     * 服务 RecyclerView，得把这一整块改写成 Adapter 才行。
     *
     * 【这套实现的思路】
     * 拖动过程中**不改视图的次序**，只给视图设置 translationY：
     *   - 被拖的那行跟着手指走；
     *   - 其余行按「源 → 目标」的区间整体平移一格，看起来就是「让位」。
     * 松手时才真正改动 items 的顺序并 rebuild() 一次。
     *
     * 这样做的好处是拖动态里视图与下标的对应关系始终成立（闭包捕获的
     * index 一直有效），不必处理「视图换了位置后 tag 要重算」这类麻烦。
     */

    /** 正在拖动的容器（null 表示当前没有拖动）。 */
    private LinearLayout dragBox;
    /** 正在被拖动的那一行（null 表示当前没有拖动）。 */
    private View dragView;
    /** 拖动源下标（拖动期间不变）。 */
    private int dragFrom = -1;
    /** 当前目标下标（随手指更新）。 */
    private int dragTo = -1;
    /** 长按触发那一刻的手指纵坐标（拖动位移的基准）。 */
    private float dragDownRawY;
    /** 一行的步长（行高 + 行间距），像素。 */
    private int dragStep;
    /** 最近一次 ACTION_DOWN 的 rawY：长按回调里拿不到事件坐标，得靠它记。 */
    private float lastDownRawY;
    /** 最近一次 ACTION_MOVE 的 rawY：自动滚动重算位移时要用。 */
    private float lastMoveRawY;
    /**
     * 起拖时的 ScrollView 滚动位置。
     *
     * <p>视图的 translationY 是**相对内容**的，而手指位置是**相对屏幕**的。
     * 内容一旦滚动，同一个屏幕坐标对应的内容位置就变了。所以被拖行的位移
     * 必须是：
     *
     * <pre>dy = (rawY - downRawY) + (scrollY - startScrollY)</pre>
     *
     * 两个括号分别代表「手指自己走了多远」和「内容替手指走了多远」。
     * 漏掉第二项，拖到屏幕边缘自动滚动时就再也对不上了。
     */
    private int dragStartScrollY;
    /**
     * 拖动时的边缘自动滚动。
     *
     * <p>项一多，列表比屏幕长，「拖到看不见的位置」就是刚需 —— 没有自动
     * 滚动的话，手指顶到屏幕边缘就没辙了，还得松手、滚动、再拖一次。
     * 这跟 SortableListView / ItemTouchHelper 的做法一致：进入上下
     * {@link #AUTO_SCROLL_EDGE_DP} 以内就按距离比例持续滚。
     */
    private Runnable autoScrollTask;
    /** 每帧的滚动量（像素，正数往下），0 表示不需要滚。 */
    private int autoScrollStep;
    /** 边缘感应带宽度。 */
    private static final int AUTO_SCROLL_EDGE_DP = 48;

    /** 取消正在排队的自动滚动（松手 / 取消时调）。 */
    private void stopAutoScroll() {
        autoScrollStep = 0;
        if (autoScrollTask != null && scroller != null) {
            scroller.removeCallbacks(autoScrollTask);
        }
        autoScrollTask = null;
    }

    /**
     * 按手指纵向位置决定要不要自动滚、滚多快。
     *
     * <p>滚动会改变「内容 ↔ 屏幕」的对应关系，而位移公式里含
     * {@code scrollY - startScrollY} 一项（见 {@link #dragStartScrollY}），
     * 所以滚完之后必须重算一次位移与目标下标，否则滚动了却不换位。
     */
    private void updateAutoScroll(float rawY) {
        if (scroller == null) {
            return;
        }
        int[] loc = new int[2];
        scroller.getLocationOnScreen(loc);
        int top = loc[1];
        int bottom = top + scroller.getHeight();
        int edge = dp(AUTO_SCROLL_EDGE_DP);

        int step = 0;
        if (rawY < top + edge) {
            // 越靠近上沿越快，最多一帧 24px
            float k = Math.min(1f, (top + edge - rawY) / (float) edge);
            step = -(int) (24 * k);
        } else if (rawY > bottom - edge) {
            float k = Math.min(1f, (rawY - (bottom - edge)) / (float) edge);
            step = (int) (24 * k);
        }
        if (step == 0 || scroller.getChildCount() == 0) {
            stopAutoScroll();
            return;
        }
        autoScrollStep = step;
        if (autoScrollTask == null) {
            autoScrollTask = new Runnable() {
                @Override
                public void run() {
                    if (dragView == null || autoScrollStep == 0 || scroller == null) {
                        autoScrollTask = null;
                        return;
                    }
                    int before = scroller.getScrollY();
                    scroller.scrollBy(0, autoScrollStep);
                    int moved = scroller.getScrollY() - before;
                    if (moved == 0) {
                        // 已经到顶 / 到底，再排下去也没用
                        stopAutoScroll();
                        return;
                    }
                    // 内容动了，被拖行得跟着「补」回来才继续贴着手指；
                    // 位移公式里滚动项已经含在 dragShift() 中，无需手工修正。
                    float dy = dragShift(lastMoveRawY);
                    dragView.setTranslationY(dy);
                    updateDragTarget(dy);
                    scroller.postOnAnimation(this);
                }
            };
            scroller.postOnAnimation(autoScrollTask);
        }
    }

    /**
     * 当前手指位置对应的「被拖行应处的位移」。
     *
     * <p>手指位移与内容滚动量相加 —— 详见 {@link #dragStartScrollY} 的推导。
     */
    private float dragShift(float rawY) {
        float finger = rawY - dragDownRawY;
        float content = (scroller == null ? 0 : scroller.getScrollY() - dragStartScrollY);
        return finger + content;
    }

    /**
     * 行上的触摸分派。
     *
     * <p>未进入拖动态时返回 false，把事件交还给 View 自己 ——
     * 点击、长按检测都由 View 原生的那套逻辑负责（长按阈值约 500ms、
     * 移动超过 touchSlop 就取消），这比自己算更准。
     */
    private boolean onRowTouch(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastDownRawY = e.getRawY();
            return false;
        }
        if (dragView == null) {
            // 没在拖，什么都不拦：MOVE 要留给 ScrollView 判断能否滚动
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                lastMoveRawY = e.getRawY();
                float dy = dragShift(lastMoveRawY);
                dragView.setTranslationY(dy);
                updateDragTarget(dy);
                updateAutoScroll(lastMoveRawY);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endDrag();
                return true;
            default:
                return true;
        }
    }

    /**
     * 长按到点：进入拖动态。
     *
     * @return 恒为 true —— 长按既然被用来起拖，就不该再触发点击（编辑）。
     */
    private boolean beginDrag(View v, int index, LinearLayout box) {
        if (index < 0 || index >= items.size()) {
            return false;
        }
        int h = v.getHeight();
        if (h <= 0) {
            // 还没量过（极端情况下长按发生在首次布局之前），退回不可拖
            return false;
        }
        dragBox = box;
        dragView = v;
        dragFrom = index;
        dragTo = index;
        dragDownRawY = lastDownRawY;
        lastMoveRawY = lastDownRawY;
        // 记住起拖时的滚动位置：位移公式要把「内容自己滚了多少」算进去
        dragStartScrollY = (scroller == null ? 0 : scroller.getScrollY());
        dragStep = measureStep(box, index, h);

        // 被拖的行要「浮」起来：抬 elevation 让它绘制在其它行之上，
        // 同时关掉裁剪，否则移出容器的那部分会被切掉。
        v.setElevation(dp(12));
        v.setAlpha(0.97f);
        v.setScaleX(1.02f);
        v.setScaleY(1.02f);
        box.setClipChildren(false);
        ViewParent p = box.getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).setClipChildren(false);
        }
        // 拖的时候不希望列表跟着滚：往父链上一路请求别拦截事件
        if (p != null) {
            p.requestDisallowInterceptTouchEvent(true);
        }
        return true;
    }

    /**
     * 量出「相邻两行的顶边距离」，也就是拖动时的步长。
     *
     * <p>【为什么不用「行高 + 固定间距」】
     * 那是纸面推算，只要有一行比别人高一点（名字换行、图标尺寸不同），
     * 步长就偏了，拖三格可能只走两格。直接问布局要真实坐标最可靠：
     * 优先取本行与下一行的 top 差，其次上一行，都没有（只有一行）才退回估算。
     */
    private int measureStep(LinearLayout box, int index, int rowHeight) {
        int childCount = box.getChildCount();
        if (index >= 0 && index + 1 < childCount) {
            int d = box.getChildAt(index + 1).getTop() - box.getChildAt(index).getTop();
            if (d > 0) {
                return d;
            }
        }
        if (index - 1 >= 0 && index - 1 < childCount) {
            int d = box.getChildAt(index).getTop() - box.getChildAt(index - 1).getTop();
            if (d > 0) {
                return d;
            }
        }
        // 只有一行：拖也换不了位，步长给个非零值即可，避免下面除零
        return Math.max(1, rowHeight + dp(8));
    }

    /**
     * 按当前位移算出「跨了几行」，跨行数变了才重排其它行的位置。
     *
     * <p>位移除以步长四舍五入：拖过半行就算换一格，手感最跟手。
     */
    private void updateDragTarget(float dy) {
        int to = dragFrom + Math.round(dy / dragStep);
        if (to < 0) to = 0;
        if (to > items.size() - 1) to = items.size() - 1;
        if (to == dragTo) {
            return;
        }
        dragTo = to;
        applyDragShifts();
    }

    /**
     * 让其它行「让位」。
     *
     * <p>源在目标之上（往下拖）时，中间那些行整体上移一格；反之整体下移。
     * 用 animate() 而不是直接 setTranslationY，是为了让让位有个短过渡 ——
     * 否则看起来像卡片突然瞬移，眼睛跟不上哪个换到哪去了。
     */
    private void applyDragShifts() {
        if (dragBox == null) {
            return;
        }
        int n = dragBox.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = dragBox.getChildAt(i);
            if (child == null || child == dragView) {
                continue;
            }
            float ty = 0f;
            if (dragFrom < dragTo) {
                // 往下拖：中间那些行各自让出「源那一行」的位置
                if (i > dragFrom && i <= dragTo) {
                    ty = -rowSpan(dragBox, i - 1, i);
                }
            } else if (dragFrom > dragTo) {
                // 往上拖：中间那些行各自让出「源那一行」的位置（向下）
                if (i >= dragTo && i < dragFrom) {
                    ty = rowSpan(dragBox, i, i + 1);
                }
            }
            child.animate().translationY(ty).setDuration(120).start();
        }
    }

    /**
     * 第 a 行与第 b 行的顶边距离（a 在 b 上面）。
     *
     * <p>让位量按**真实**相邻距离算，而不是统一的 dragStep：
     * 只有各行等高时两者才相等。差一两像素看不出来，差多了会出现
     * 「让位了但没对齐」，松手重建的一瞬间会看到卡片跳一下。
     * 拿不到有效值（未布局）时退回 dragStep。
     */
    private int rowSpan(LinearLayout box, int a, int b) {
        if (a >= 0 && b < box.getChildCount()) {
            int d = box.getChildAt(b).getTop() - box.getChildAt(a).getTop();
            if (d > 0) {
                return d;
            }
        }
        return Math.max(1, dragStep);
    }

    /**
     * 松手：真的把数据换个位置，然后整页重建。
     *
     * <p>重建会按新顺序重画序号，也顺手把拖动期间设的 translation /
     * elevation 全部丢掉 —— 不必再手工复位。
     */
    private void endDrag() {
        final int from = dragFrom;
        final int to = dragTo;
        final View v = dragView;
        final LinearLayout box = dragBox;

        // 先清状态：rebuild() 里有 removeAllViews，若这里不清，
        // 万一 rebuild 抛异常会留下「dragView 指向已移除的视图」的坏状态。
        stopAutoScroll();
        dragView = null;
        dragBox = null;
        dragFrom = -1;
        dragTo = -1;

        if (v != null) {
            v.setElevation(0f);
            v.setAlpha(1f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
        if (box != null) {
            box.setClipChildren(true);
            ViewParent p = box.getParent();
            if (p instanceof ViewGroup) {
                ((ViewGroup) p).setClipChildren(true);
            }
            if (p != null) {
                p.requestDisallowInterceptTouchEvent(false);
            }
            // 保险：把让位时留下的 translation 清掉（马上重建，一般用不上）
            for (int i = 0; i < box.getChildCount(); i++) {
                View c = box.getChildAt(i);
                if (c != null) c.setTranslationY(0f);
            }
        }

        if (from < 0 || to < 0 || from == to
                || from >= items.size() || to >= items.size()) {
            // 没换位置：视觉上被拖起来过，得重画一次把它放回原处
            rebuild();
            return;
        }
        EdgeGesturePrefs.MenuItem moved = items.remove(from);
        items.add(to, moved);
        persist();
        rebuild();
    }

    /**
     * 行尾「⋯」弹出的操作表（上移 / 下移 / 编辑 / 删除）。
     *
     * <p>【为什么还留着上移 / 下移】
     * 拖动已经能排序，理论上不需要它们了。但项数多到需要滚动时，
     * 「拖到屏幕外的位置」得一边拖一边滚，很容易失手；这时候点两下
     * 「上移」反而更稳。两个入口并存，按场合挑。
     */
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
            // 预览画布关闭失败无语义影响，页面即将销毁。
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
                "宏 → 触发器 →「小孙OCR · 边缘滑动事件」，再自己建变量接这三个值：\n"
                        + "  菜单索引   0 / 1 / 2 …（-1 = 取消）\n"
                        + "  菜单文字   选中项的名字\n"
                        + "  菜单动作   选中项的动作值\n"
                        + "变量名你自己定，本应用不限定。抬手那一刻才触发宏，"
                        + "宏里可以直接执行动作。"
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

    /**
     * 整页重建：配置有一处变了就整体重画。
     *
     * <p>【为什么要保住滚动位置】
     * 这一页很长（开关 → 外观 → 菜单项 → 说明），用户改菜单项时基本都在
     * 页面下半部分。整页重画会把 ScrollView 归零，回到页首 —— 拖完一项
     * 就跳回顶部，得重新往下滚才能看见结果，等于白拖。
     *
     * <p>滚动位置用 post 恢复：重建后的内容还没测量，立刻 scrollTo 会被
     * 夹到 0（此时内容高度还是旧的、甚至是 0）。排到下一帧，布局已经完成，
     * 此时再滚才定位得准。
     */
    private void rebuild() {
        int keepY = 0;
        if (scroller != null) {
            keepY = scroller.getScrollY();
        }
        content.removeAllViews();
        buildSwitchCard();
        buildAppearanceCard();
        buildItemsCard();
        buildHowToCard();
        // 视图换了一整批，任何还指向旧视图的拖动状态都必须清掉 ——
        // 尤其是自动滚动那个自循环 Runnable，否则它会一直拿着已移除的
        // dragView 往下滚。
        stopAutoScroll();
        dragView = null;
        dragBox = null;
        if (keepY > 0 && scroller != null) {
            final int y = keepY;
            scroller.post(() -> {
                if (scroller != null) {
                    scroller.scrollTo(0, y);
                }
            });
        }
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
                // onDraw 里抛异常会让整个界面白屏，宁可这张预览不画也不能崩。
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