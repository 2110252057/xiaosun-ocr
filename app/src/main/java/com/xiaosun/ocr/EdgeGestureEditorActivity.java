package com.xiaosun.ocr;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个滑动手柄的「编辑」页。
 *
 * 顶部是名称与删除；中间是**一张实时预览图**加一列滑杆
 * （宽度 / 高度 / 垂直偏移量 / 不透明度 / 圆角半径 / 滑动距离）；
 * 颜色是可点的色板；底部是 8 个方向的开关与各自的「触发距离」。
 *
 * ════════════════════════════════════════════════════════════════
 * 【宽度只看一个数，所见即所触】
 * ════════════════════════════════════════════════════════════════
 * 早期版本把「好按」和「不挡内容」拆成两个参数：
 *   · 宽度      —— 窗口能接收触摸的范围
 *   · 可见宽度  —— 真正画出来的那一条
 * 结果是可以做到「看着 8dp、实际 56dp 能摸到」，但代价是
 * **用户对不上账**：调了宽度却看不出变化（变化在看不见的热区里），
 * 屏幕上那条条子更是改完切出去才知道多粗。
 *
 * 现在只留「宽度」一个参数，两者在内部强制相等：
 * 条子画多粗，手指能摸到的就是多粗。少一个选项，也少一处困惑。
 *
 * ════════════════════════════════════════════════════════════════
 * 【拖滑杆就能看到多宽】
 * ════════════════════════════════════════════════════════════════
 * 数字（"56dp"）本身没有体感，而手柄是悬浮窗，改完得切出去才生效。
 * 所以尺寸区顶部放了一张等比缩略图（PreviewStrip）：
 * 屏幕、手柄条、离顶部的位置全按真实比例画，拖滑杆时即时刷新。
 *
 * ════════════════════════════════════════════════════════════════
 * 【改一下就写盘】
 * ════════════════════════════════════════════════════════════════
 * 所有调整都会立刻写盘并让悬浮窗重建，用户能在真实屏幕上看到效果。
 * 重建走广播、并做了 260ms 防抖 —— 拖动滑杆时每帧重建窗口会很卡。
 */
public class EdgeGestureEditorActivity extends AppCompatActivity {

    public static final String EXTRA_HANDLE_ID = "handle_id";

    private static final int C_TEXT = 0xFF1A1D26;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_HINT = 0xFF9CA3AF;
    private static final int C_BRAND = 0xFF4F6BED;
    private static final int C_DIVIDER = 0xFFEAECF2;
    private static final int C_SURFACE = 0xFFFFFFFF;
    private static final int C_DANGER = 0xFFF04438;

    /** 9 个预置色 —— 从冷淡到鲜艳，覆盖常见选择。 */
    private static final int[] PALETTE = {
            0xFF9AA0A6, 0xFF5F6368, 0xFF1A1D26,
            0xFF4F6BED, 0xFF12B76A, 0xFFF79009,
            0xFFF04438, 0xFF9B51E0, 0xFFFFFFFF
    };

    private int handleId;
    private EdgeGesturePrefs.Handle handle;

    private TextView nameView;
    private LinearLayout colorRow;

    /** 实时预览：画在编辑页里，改一下数字就能看到实际比例（见本文件末尾的 PreviewStrip。） */
    private PreviewStrip preview;
    private static final long RELOAD_DEBOUNCE_MS = 260L;
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable reloadTask = () -> EdgeGestureService.reload(this);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.install(this);

        handleId = getIntent() == null
                ? -1 : getIntent().getIntExtra(EXTRA_HANDLE_ID, -1);

        handle = EdgeGesturePrefs.find(this, handleId);
        if (handle == null) {
            handle = EdgeGesturePrefs.defaultHandle(this);
            handleId = handle.id;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_SURFACE);

        root.addView(buildTopBar());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(14), dp(20), dp(110));   // 底部留白给浮动按钮

        buildNameSection(content);
        buildEdgeSection(content);
        buildSizeSection(content);
        buildAppearanceSection(content);
        buildGestureSection(content);
        buildMenuNote(content);
        buildSystemGestureSection(content);
        buildDirectionSection(content);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 浮动「完成」按钮：盖在内容之上，和截图一致
        FrameLayout shell = new FrameLayout(this);
        shell.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shell.addView(buildFab(), fabParams());

        setContentView(shell);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(reloadTask);
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

        // 返回
        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(24);
        back.setTextColor(Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(40)));

        TextView title = new TextView(this);
        title.setText("编辑滑动手柄");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 删除
        TextView del = new TextView(this);
        del.setText("🗑");
        del.setTextSize(19);
        del.setTextColor(Color.WHITE);
        del.setGravity(Gravity.CENTER);
        del.setOnClickListener(v -> confirmDelete());
        bar.addView(del, new LinearLayout.LayoutParams(dp(48), dp(40)));

        return bar;
    }

    private View buildFab() {
        TextView fab = new TextView(this);
        fab.setText("✓");
        fab.setTextSize(26);
        fab.setTypeface(null, android.graphics.Typeface.BOLD);
        fab.setTextColor(C_BRAND);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.WHITE);
        fab.setBackground(bg);
        fab.setElevation(dp(8));
        fab.setOnClickListener(v -> finish());
        // 让点击热区比视觉圆形更大一点，符合 Material 的习惯
        fab.setPadding(dp(4), dp(4), dp(4), dp(4));
        return fab;
    }

    private FrameLayout.LayoutParams fabParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(60), dp(60));
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.rightMargin = dp(22);
        lp.bottomMargin = dp(28);
        return lp;
    }

    // ============================================================
    //  名称
    // ============================================================

    private void buildNameSection(LinearLayout parent) {
        parent.addView(label("名称"), topMargin(0));

        nameView = new EditText(this);
        nameView.setText(handle.name);
        nameView.setTextSize(15);
        nameView.setSingleLine(true);
        nameView.setTextColor(C_TEXT);
        nameView.setHint("例如：右上角（经典）");
        nameView.setInputType(InputType.TYPE_CLASS_TEXT);
        // 只保留下划线：xamarin/miu 风格没意义，Material 的 TextInput 又太重
        nameView.setBackground(new GradientDrawable() {{
            setColor(Color.TRANSPARENT);
        }});
        nameView.setPadding(0, dp(6), 0, dp(6));
        nameView.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                handle.name = s == null ? "" : s.toString().trim();
                persist(true);
            }
        });
        parent.addView(nameView, wide());

        View line = new View(this);
        line.setBackgroundColor(C_DIVIDER);
        parent.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    // ============================================================
    //  边缘位置
    // ============================================================

    private void buildEdgeSection(LinearLayout parent) {
        parent.addView(label("边缘位置"), topMargin(18));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView[] marks = new TextView[2];
        for (int i = 0; i < 2; i++) {
            final int side = i;   // 0 = 左，1 = 右
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(0, dp(8), dp(10), dp(8));

            TextView radio = new TextView(this);
            radio.setTextSize(11);
            radio.setGravity(Gravity.CENTER);
            marks[i] = radio;
            item.addView(radio, new LinearLayout.LayoutParams(dp(24), dp(24)));

            TextView txt = new TextView(this);
            txt.setText(side == 0 ? "左" : "右");
            txt.setTextSize(15);
            txt.setTextColor(C_TEXT);
            txt.setPadding(dp(6), 0, 0, 0);
            item.addView(txt);

            item.setOnClickListener(v -> {
                handle.edge = (side == 0) ? 0 : 1;
                refreshRadioMarks(marks);
                persist(true);
            });

            row.addView(item);
        }
        parent.addView(row, wide());
        refreshRadioMarks(marks);
    }

    /** 单选圆圈：用纯文字画，避免引入 RadioButton 的默认样式。 */
    private void refreshRadioMarks(TextView[] marks) {
        for (int i = 0; i < marks.length; i++) {
            boolean on = (handle.edge == (i == 0 ? 0 : 1));
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(2), on ? C_BRAND : C_HINT);
            marks[i].setBackground(g);
            marks[i].setText(on ? "●" : "");
            marks[i].setTextColor(C_BRAND);
        }
    }

    // ============================================================
    //  尺寸
    // ============================================================

    private void buildSizeSection(LinearLayout parent) {
        // ---------- 实时预览 ----------
        // 放在最前面：用户拖下面的滑杆时，视线不用来回跑就能看到变化。
        parent.addView(label("预览"), topMargin(16));
        preview = new PreviewStrip(this);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        pl.topMargin = dp(6);
        parent.addView(preview, pl);

        PreviewLegend legend = new PreviewLegend(this);
        parent.addView(legend, topMargin(4));

        // ---------- 可触摸宽度 ----------
        // ★ 这一项决定「好不好按」，必须和下面的手柄条同宽（不再单独暴露"可视宽度"，
        //   否则两个宽度会互相矛盾、让人对不上）。
        LinearLayout.LayoutParams wlp = wide();
        wlp.topMargin = dp(18);
        parent.addView(label("宽度（手柄条）"), wlp);

        buildWidthSlider(parent);

        parent.addView(note("手柄贴在屏幕边缘时所占的宽度，同时决定好不好按。"
                + "拖动上面的滑杆，预览会实时跟着变。"), topMargin(2));

        addSlider(parent, "高度", handle.height, 40, 600, "dp",
                v -> {
                    handle.height = v;
                    refreshPreview();
                }, true);

        addSlider(parent, "垂直偏移量", handle.offsetPercent, 0, 100, "%",
                v -> {
                    handle.offsetPercent = v;
                    refreshPreview();
                }, true);
    }

    /**
     * 「宽度」滑杆单独搭一条，因为要在回调里同时写两个字段。
     *
     * 不用 addSlider 是因为它只处理单个参数；这里每次变动都要
     * 顺手把 visibleWidth 拉齐（两者始终相等：所见即所触），
     * 否则悬浮窗画出来的条子和实际触摸热区就会不一致。
     */
    private void buildWidthSlider(LinearLayout parent) {
        final int lo = 0, max = 200, span = max - lo;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView val = new TextView(this);
        val.setText(handle.width + "dp");
        val.setTextSize(13.5f);
        val.setTextColor(C_BRAND);
        val.setTypeface(null, android.graphics.Typeface.BOLD);
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(dp(64),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar sb = new SeekBar(this);
        int clamped = Math.max(lo, Math.min(max, handle.width));
        sb.setMax(span);
        sb.setProgress(clamped - lo);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int v = lo + progress;
                val.setText(v + "dp");
                handle.width = v;
                // ★ 两者始终相等：所见即所触。
                handle.visibleWidth = v;
                refreshPreview();
                if (fromUser) persist(true);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                persist(true);
            }
        });

        row.addView(sb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, wide());
    }

    /** 参数变了就重画预览（拖滑杆时高频调用，内部只 invalidate，开销极小）。 */
    private void refreshPreview() {
        if (preview != null) preview.invalidate();
    }

    // ============================================================
    //  外观（颜色 / 不透明度 / 圆角）
    // ============================================================

    private void buildAppearanceSection(LinearLayout parent) {
        parent.addView(label("颜色"), topMargin(18));

        colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.START);
        buildPalette();
        parent.addView(colorRow, wide());

        addSlider(parent, "不透明度", handle.alpha, 10, 100, "%",
                v -> {
                    handle.alpha = v;
                    refreshPreview();
                }, true);

        addSlider(parent, "圆角半径", handle.radius, 0, 60, "dp",
                v -> {
                    handle.radius = v;
                    refreshPreview();
                }, true);
    }

    /** 色板：一行色块 + 右侧「当前色」大圆点。 */
    private void buildPalette() {
        colorRow.removeAllViews();

        for (final int c : PALETTE) {
            TextView dot = new TextView(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            // 当前选中的加一圈描边
            if ((c & 0x00FFFFFF) == (handle.color & 0x00FFFFFF)) {
                g.setStroke(dp(2), C_BRAND);
            }
            dot.setBackground(g);
            dot.setOnClickListener(v -> {
                handle.color = 0xFF000000 | (c & 0x00FFFFFF);
                buildPalette();
                refreshPreview();
                persist(true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.setMarginEnd(dp(10));
            colorRow.addView(dot, lp);
        }

        // 当前色预览（大一点，单独放最右边）
        View spacer = new View(this);
        colorRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView cur = new TextView(this);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        cg.setColor(handle.color);
        cg.setStroke(dp(2), C_DIVIDER);
        cur.setBackground(cg);
        colorRow.addView(cur, new LinearLayout.LayoutParams(dp(44), dp(44)));
    }

    // ============================================================
    //  手势（触发时机 + 距离）
    // ============================================================

    private void buildGestureSection(LinearLayout parent) {
        // 「触发时机」单选已下线。
        //
        // 原先有三个选项：「滑过距离时触发」「按下手柄时触发」
        // 「滑动未达距离后抬手时触发」。后两个实际用起来问题更多：
        // 按下即触发会和滑动菜单、误触搅在一起；抬手触发场景很少，
        // 还容易和「滑了但该方向没启用」混淆。现在只剩一种时机，
        // 没有可选项，这一段就只留说明文字了。
        parent.addView(label("触发方式"), topMargin(22));

        parent.addView(note("滑动超过下面设定的距离时立即触发，不用抬手；"
                + "抬手只是复位，方便连续操作。"), topMargin(4));

        addSlider(parent, "滑动距离", handle.threshold, 10, 400, "dp",
                v -> handle.threshold = v, true);
        parent.addView(note("手指位移超过这个距离就算触发。"
                + "90dp 大约是一根手指宽度的两倍，适合大多数场景；"
                + "握持不稳时可调到 120dp 以上。"), topMargin(2));
    }

    // ============================================================
    //  8 个方向
    // ============================================================

    /**
     * 「系统手势」小节：是否申请把手柄覆盖的区域从系统边缘手势里排除。
     *
     * 这一项必须解释清楚，否则用户看到「屏蔽系统手势」会以为是万能开关 ——
     * 它其实只是往系统报一块矩形，能不能被采纳取决于系统版本与 ROM。
     */
    /**
     * 菜单模式下的一个行为差异提示。
     *
     * 开启滑动菜单后，「触发距离」含义其实没变（还是「滑多远开始响应」），
     * 但响应内容从「立即触发宏」变成了「弹出菜单」。
     * 不写出来很容易让人以为在菜单模式下距离失灵了。
     */
    private void buildMenuNote(LinearLayout parent) {
        if (!EdgeGesturePrefs.isMenuEnabled(this)) return;
        if (EdgeGesturePrefs.getMenuItems(this).isEmpty()) return;

        parent.addView(label("滑动菜单"), topMargin(22));

        TextView n = note("本机已开启「滑动菜单」：滑过上面的触发距离后"
                + "不再立即触发宏，而是弹出菜单，"
                + "滑到某一项抬手才会触发。\n"
                + "菜单项在「边缘滑动触发」主页里配。");
        parent.addView(n, topMargin(6));
    }

    private void buildSystemGestureSection(LinearLayout parent) {
        parent.addView(label("系统手势"), topMargin(22));
        parent.addView(note("手势导航下，系统是从屏幕边缘往内判定「返回」的，"
                + "优先级比应用窗口还高 —— 手柄贴着边缘时，手指刚碰上去就被系统"
                + "当成返回吃掉了，表现为「条子在那儿但按不动」。"), topMargin(2));

        // ---- 平台能力提示：低于 Android 10 的机器根本没有这个 API ----
        //
        // setSystemGestureExclusionRects 是 API 29（Android 10）才加入的。
        // 在 Android 9 及更早的机型上，**没有任何原生手段**能让应用
        // 从系统手里抢回边缘手势 —— 这不是配置问题，怎么调都没用。
        // 用户在不同手机上测试时最容易踩到这个坑（本机是 Android 17，
        // 一切正常；换到旧机器上就「屏蔽失效」），所以必须写在界面上，
        // 而不是等他来反馈。
        if (Build.VERSION.SDK_INT < 29) {
            TextView warn = new TextView(this);
            warn.setTextSize(11.5f);
            warn.setTextColor(0xFFB45309);   // 琥珀色，与「警告」语义一致
            warn.setLineSpacing(0f, 1.35f);
            warn.setText("⚠ 当前系统 Android " + Build.VERSION.RELEASE
                    + "（API " + Build.VERSION.SDK_INT + "），低于 Android 10。\n"
                    + "该版本没有提供「屏蔽系统手势」的平台接口，本开关在本机不会生效 —— "
                    + "这是系统限制，不是配置错误。\n"
                    + "临时办法：把手柄挪到屏幕偏中间的位置（避开系统边缘返回区），"
                    + "或改用「三键导航」。");
            LinearLayout.LayoutParams wl = wide();
            wl.topMargin = dp(6);
            parent.addView(warn, wl);
        }


        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        final CheckBox cb = new CheckBox(this);
        cb.setChecked(handle.blockGesture);
        cb.setOnCheckedChangeListener((b, checked) -> {
            handle.blockGesture = checked;
            persist(true);
        });
        row.addView(cb, new LinearLayout.LayoutParams(dp(44),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView txt = new TextView(this);
        txt.setText("屏蔽本条手柄区域的系统手势");
        txt.setTextSize(14f);
        txt.setTextColor(C_TEXT);
        txt.setPadding(dp(6), 0, 0, 0);
        txt.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
        row.addView(txt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row, wide());

        String extra = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? "只排除手柄那一段，屏幕其余部分的系统手势照常。"
                : "⚠ 当前系统低于 Android 10，本机不支持，这一项不会生效。";

        parent.addView(note("开启后，手柄占的那块矩形会报给系统，"
                + "那一带就不再判系统手势，触摸完整交给手柄。\n"
                + extra + "\n"
                + "系统对每条边可排除的高度有 200dp 上限，"
                + "手柄高于 200dp 时超出部分仍会被系统手势抢走。"), topMargin(4));
    }

    private void buildDirectionSection(LinearLayout parent) {
        parent.addView(label("可触发方向"), topMargin(22));
        parent.addView(note("每个方向可单独开关；点右侧的距离数字可单独设置该方向的触发距离（0 = 跟随上面的滑动距离）。"),
                topMargin(2));

        for (int d = 0; d < 8; d++) {
            final int dir = d;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            CheckBox cb = new CheckBox(this);
            cb.setChecked(handle.enabled[dir]);
            cb.setOnCheckedChangeListener((b, checked) -> {
                handle.enabled[dir] = checked;
                persist(true);
            });
            row.addView(cb, new LinearLayout.LayoutParams(dp(44),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView arrow = new TextView(this);
            arrow.setText(EdgeGesturePrefs.DIR_ARROWS[dir]);
            arrow.setTextSize(18);
            arrow.setTextColor(C_BRAND);
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(30),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView name = new TextView(this);
            name.setText(EdgeGesturePrefs.DIR_LABELS[dir]);
            name.setTextSize(14.5f);
            name.setTextColor(C_TEXT);
            name.setPadding(dp(8), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 距离数字：点一下弹输入框
            final TextView dist = new TextView(this);
            updateDistText(dist, dir);
            dist.setTextSize(13);
            dist.setTextColor(handle.distance[dir] > 0 ? C_BRAND : C_HINT);
            dist.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable db = new GradientDrawable();
            db.setColor(0x0F4F6BED);
            db.setCornerRadius(dp(8));
            dist.setBackground(db);
            dist.setOnClickListener(v -> promptDistance(dir, dist));
            row.addView(dist);

            parent.addView(row, wide());
        }
    }

    private void updateDistText(TextView tv, int dir) {
        int d = handle.distance[dir];
        tv.setText(d > 0 ? (d + "dp") : "默认");
    }

    /** 单独设置某个方向的触发距离；留空或填 0 = 跟随全局。 */
    private void promptDistance(final int dir, final TextView tv) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint(String.valueOf(handle.threshold));
        et.setText(handle.distance[dir] > 0 ? String.valueOf(handle.distance[dir]) : "");
        et.setTextSize(15);
        et.setPadding(dp(16), dp(10), dp(16), dp(10));

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("「" + EdgeGesturePrefs.DIR_LABELS[dir] + "」触发距离")
                .setMessage("单位 dp。留空或填 0 = 跟随全局的滑动距离（"
                        + handle.threshold + "dp）。")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String s = et.getText() == null ? "" : et.getText().toString().trim();
                    int v = 0;
                    try {
                        v = s.isEmpty() ? 0 : Integer.parseInt(s);
                    } catch (Throwable ignored) {
                    }
                    handle.distance[dir] = Math.max(0, Math.min(600, v));
                    updateDistText(tv, dir);
                    tv.setTextColor(handle.distance[dir] > 0 ? C_BRAND : C_HINT);
                    persist(true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============================================================
    //  删除
    // ============================================================

    private void confirmDelete() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("删除这个手柄？")
                .setMessage("它的方向设置会一并移除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    List<EdgeGesturePrefs.Handle> all =
                            new ArrayList<>(EdgeGesturePrefs.getHandles(this));
                    for (int i = 0; i < all.size(); i++) {
                        if (all.get(i).id == handleId) {
                            all.remove(i);
                            break;
                        }
                    }
                    EdgeGesturePrefs.saveHandles(this, all);
                    EdgeGestureService.reload(this);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .show();
    }

    // ============================================================
    //  落盘 / 预览
    // ============================================================

    /**
     * 保存当前手柄并刷新悬浮窗。
     *
     * @param preview 是否立刻让悬浮窗按新参数重建（拖动滑杆时 true）
     */
    private void persist(boolean preview) {
        try {
            List<EdgeGesturePrefs.Handle> all =
                    new ArrayList<>(EdgeGesturePrefs.getHandles(this));
            boolean replaced = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id == handleId) {
                    all.set(i, handle);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) all.add(handle);
            EdgeGesturePrefs.saveHandles(this, all);
        } catch (Throwable ignored) {
        }

        if (preview) {
            // 防抖：拖动滑杆会连续回调，每次都重建窗口会明显掉帧
            ui.removeCallbacks(reloadTask);
            ui.postDelayed(reloadTask, RELOAD_DEBOUNCE_MS);
        }
    }

    // ============================================================
    //  通用控件
    // ============================================================

    private interface OnIntValue {
        void on(int v);
    }

    /**
     * 一行「标签 + 数值 + 滑杆」。
     *
     * 注意 minSdk 是 24，而 SeekBar.setMin() 是 API 26 才有的，
     * 所以这里用「0..(max-min) 的内部进度 + min 偏移」的老办法，保证 24 上也能跑。
     */
    private void addSlider(LinearLayout parent, String name, int value,
                           int min, int max, String unit,
                           OnIntValue onChange, boolean preview) {
        if (max <= min) max = min + 1;
        final int lo = min, span = max - min;

        parent.addView(label(name), topMargin(18));

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
                onChange.on(v);
                if (fromUser) persist(preview);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                // 松手时再补一次，确保最终值一定落盘
                persist(true);
            }
        });

        row.addView(sb, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, wide());
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

    /** 供列表页复用：启动编辑器。 */
    public static void open(android.content.Context ctx, int handleId) {
        Intent i = new Intent(ctx, EdgeGestureEditorActivity.class);
        i.putExtra(EXTRA_HANDLE_ID, handleId);
        if (!(ctx instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }

    // ============================================================
    //  实时预览
    // ============================================================

    /**
     * 手机屏幕缩略图 + 按真实比例画出来的手柄条。
     *
     * ════════════════════════════════════════════════════════════════
     * 【为什么需要它】
     * ════════════════════════════════════════════════════════════════
     * 「宽度 56dp」这种数字，单看是没有任何体感的 —— 用户不知道
     * 它相对整块屏幕有多粗、会不会挡到内容。而手柄是悬浮窗，
     * 改完得切出去才看得到效果，来回试非常折磨。
     *
     * 所以这里在编辑页里画一张等比缩略图：屏幕有多宽、手柄占多少、
     * 离顶部多远，全部按真实比例缩放后可视 —— 拖滑杆时即时更新。
     *
     * ════════════════════════════════════════════════════════════════
     * 【算法必须和悬浮窗一致】
     * ════════════════════════════════════════════════════════════════
     * 比例口径照抄 EdgeGestureService：
     *   · 手柄窗口高度   = dp(handle.height)
     *   · 可滚动余量 maxY = 屏高 - 手柄高          （负数取 0）
     *   · 实际 y 偏移    = maxY * (offsetPercent/100)
     *   · 手柄条紧贴所选边缘，宽度 = dp(handle.width)
     * 用的是同一个 DisplayMetrics，所以画出来的比例是"真实"的，
     * 不是示意 —— 这也是这个控件唯一的价值所在。
     */
    private class PreviewStrip extends View {

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final RectF screen = new RectF();

        private final DisplayMetrics dm = getResources().getDisplayMetrics();

        PreviewStrip(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // ---------- 对外层留一点内边距，别贴着卡片边 ----------
            float pad = dp(10);
            float availW = getWidth() - pad * 2;
            float availH = getHeight() - pad * 2;
            if (availW <= 0 || availH <= 0) return;

            // ---------- 等比缩放到可用区域（保持屏幕比例）----------
            float sx = availW / dm.widthPixels;
            float sy = availH / dm.heightPixels;
            float s = Math.min(sx, sy);
            float sw = dm.widthPixels * s;
            float sh = dm.heightPixels * s;
            float ox = pad + (availW - sw) / 2f;
            float oy = pad + (availH - sh) / 2f;

            // 屏幕底：白底 + 描边
            screen.set(ox, oy, ox + sw, oy + sh);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(screen, dp(8), dp(8), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(C_DIVIDER);
            canvas.drawRoundRect(screen, dp(8), dp(8), p);

            // 顶部状态栏示意线，帮助判断"离顶部多远"
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0xFFD8DCE6);
            canvas.drawLine(ox, oy + sh * (24f / dm.heightPixels),
                    ox + sw, oy + sh * (24f / dm.heightPixels), p);

            // ---------- 手柄条（严格按真实比例换算）----------
            float barW = Math.max(1.5f, handle.width * dp(1) * s);
            float barH = Math.max(3f, handle.height * dp(1) * s);
            float maxY = Math.max(0, dm.heightPixels - dp(handle.height));
            float barY = oy + (maxY * (handle.offsetPercent / 100f)) * s;

            // 顶部对齐时不能被状态栏示意线盖住视觉判断，故 clamp 进屏幕即可
            if (barY < oy) barY = oy;
            if (barY + barH > oy + sh) barH = oy + sh - barY;

            float left, right;
            if (handle.isEdgeLeft()) {
                left = ox;
                right = ox + barW;
            } else {
                right = ox + sw;
                left = right - barW;
            }

            // 颜色 + 透明度，和悬浮窗同一套算法
            int alpha = Math.round(255 * (handle.alpha / 100f));
            if (alpha < 8) alpha = 8;
            p.setStyle(Paint.Style.FILL);
            p.setColor((alpha << 24) | (handle.color & 0x00FFFFFF));

            float rad = Math.min(handle.radius * dp(1) * s,
                    Math.max(2f, (right - left) / 2f));
            r.set(left, barY, right, barY + barH);
            canvas.drawRoundRect(r, rad, rad, p);

            // 手柄太高会把条子叠成一大块，加一层描边保证边界看得清
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0x33000000);
            canvas.drawRoundRect(r, rad, rad, p);

            // ---------- 尺寸标注 ----------
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(10));
            p.setColor(C_SUB);
            String dim = handle.width + " × " + handle.height + " dp";
            canvas.drawText(dim, ox + dp(6), oy + sh - dp(8), p);
        }
    }

    /**
     * 预览图下方的图例说明。
     *
     * 必须写清楚「条子有多粗 = 能摸到的范围就有多粗」——
     * 否则用户会以为看到的只是装饰，不知道它同时是热区。
     */
    private class PreviewLegend extends LinearLayout {

        PreviewLegend(Context c) {
            super(c);
            setOrientation(VERTICAL);

            TextView t = new TextView(c);
            t.setTextSize(11);
            t.setTextColor(C_HINT);
            t.setLineSpacing(0f, 1.35f);
            t.setText("白色是屏幕，彩色条就是你屏幕上会看到的那个手柄。\n"
                    + "按真实比例画的：条子多宽，手指能摸到的范围就有多宽。");
            addView(t);
        }
    }
}