package com.xiaosun.ocr;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 边缘滑动触发 —— 设置主页面。
 *
 * 这一页负责「总开关 / 触控区域 / 手柄列表」，
 * 单个手柄的细节（尺寸、颜色、8 个方向、距离）在
 * {@link EdgeGestureEditorActivity} 里调。
 *
 * ════════════════════════════════════════════════════════════════
 * 【和宏怎么配合】
 * ════════════════════════════════════════════════════════════════
 * 手势本身只负责「产生事件」，具体干什么由宏决定：
 *
 *   宏 → 触发器：「小孙OCR · 边缘滑动事件」
 *   选好方向即可；方向不符的宏根本不会被触发。
 *
 *   滑出菜单选完后，插件把结果交给宏 ——
 *   「用户点了哪一项」只有插件知道，必须由它告诉宏。
 *   手势方向本身不作为变量回传：方向在配置时就比过了，
 *   宏不需要再判断一遍。
 *
 * 只留这一条路：插件方式能直接拿到变量，不必自己去接广播。
 */
public class EdgeGestureActivity extends AppCompatActivity {

    private static final int C_TEXT = 0xFF1A1D26;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_HINT = 0xFF9CA3AF;
    private static final int C_BRAND = 0xFF4F6BED;
    private static final int C_BRAND_SOFT = 0xFFEDF0FE;
    private static final int C_DIVIDER = 0xFFEAECF2;
    private static final int C_SURFACE = 0xFFFFFFFF;
    private static final int C_BG = 0xFFF5F6FA;
    private static final int C_OK = 0xFF12B76A;
    private static final int C_WARN = 0xFFF79009;

    private LinearLayout content;
    private Switch mainSwitch;
    private TextView statusText;
    private TextView permText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.addView(buildTopBar());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(40));
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    // ============================================================
    //  顶栏
    // ============================================================

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(C_BRAND);
        bar.setPadding(dp(8), dp(14), dp(16), dp(14));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(24);
        back.setTextColor(Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(40)));

        TextView title = new TextView(this);
        title.setText("边缘滑动触发");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return bar;
    }

    // ============================================================
    //  页面组装
    // ============================================================

    private void rebuild() {
        content.removeAllViews();

        buildStatusCard();
        buildMainSwitch();
        buildPermissionCard();
        buildHandlesCard();
        buildBurnInCard();
        buildHelpCard();
        // 日志卡片放最后：正常用户不会注意到它，出问题时才往下找到
        content.addView(LogCard.build(this), cardLp());
    }

    private void buildStatusCard() {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(statusText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView meta = new TextView(this);
        meta.setTextSize(12);
        meta.setTextColor(C_HINT);
        int n = EdgeGesturePrefs.getHandles(this).size();
        meta.setText("共 " + n + " 个手柄");
        row.addView(meta);

        card.addView(row, wide());

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(C_SUB);
        hint.setLineSpacing(0f, 1.3f);
        hint.setText("手柄贴在屏幕边缘，手指按上去朝任意方向滑动即可触发，"
                + "滑过设定距离立刻响应，不需要抬手。");
        LinearLayout.LayoutParams hl = wide();
        hl.topMargin = dp(6);
        card.addView(hint, hl);

        content.addView(card, cardLp());
        refreshStatus();
    }

    private void refreshStatus() {
        if (statusText == null) return;
        boolean on = EdgeGesturePrefs.isEnabled(this);
        boolean running = EdgeGestureService.isRunning;
        if (on && running) {
            statusText.setText("已开启");
            statusText.setTextColor(C_OK);
        } else if (on) {
            statusText.setText("已开启（正在启动…）");
            statusText.setTextColor(C_WARN);
        } else {
            statusText.setText("已关闭");
            statusText.setTextColor(C_HINT);
        }
    }

    private void buildMainSwitch() {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText("启用手势手柄");
        t.setTextSize(14.5f);
        t.setTextColor(C_TEXT);
        col.addView(t);

        TextView s = new TextView(this);
        s.setText("关闭后手柄会立刻从屏幕上消失");
        s.setTextSize(11.5f);
        s.setTextColor(C_HINT);
        col.addView(s);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mainSwitch = new Switch(this);
        mainSwitch.setChecked(EdgeGesturePrefs.isEnabled(this));
        mainSwitch.setOnCheckedChangeListener((b, checked) -> {
            EdgeGesturePrefs.setEnabled(this, checked);
            EdgeGesturePrefs.refreshRuntime(this);
            if (checked) {
                if (!canOverlay()) {
                    toast("请先开启「悬浮窗权限」");
                    requestOverlay();
                } else {
                    EdgeGestureService.start(this);
                }
            } else {
                EdgeGestureService.stop(this);
            }
            content.postDelayed(this::rebuild, 220);
        });
        row.addView(mainSwitch);

        card.addView(row, wide());
        content.addView(card, cardLp());
    }

    private void buildPermissionCard() {
        if (canOverlay()) return;

        LinearLayout card = card();
        card.setBackground(borderedBg(C_SURFACE, 0x33F79009, 14));

        permText = new TextView(this);
        permText.setText("缺少「悬浮窗权限」");
        permText.setTextSize(14);
        permText.setTypeface(null, android.graphics.Typeface.BOLD);
        permText.setTextColor(C_WARN);
        card.addView(permText, wide());

        TextView d = new TextView(this);
        d.setTextSize(12);
        d.setTextColor(C_SUB);
        d.setLineSpacing(0f, 1.3f);
        d.setText("手柄是悬浮窗，必须授予该权限才能显示。\n"
                + "注意：这与「屏幕截屏识别」用的是同一个权限，开过一次就不用再开。");
        LinearLayout.LayoutParams dl = wide();
        dl.topMargin = dp(6);
        card.addView(d, dl);

        Button go = primaryButton("去开启");
        go.setOnClickListener(v -> requestOverlay());
        LinearLayout.LayoutParams gl = wide();
        gl.topMargin = dp(12);
        card.addView(go, gl);

        content.addView(card, cardLp());
    }


    // ============================================================
    //  手柄列表
    // ============================================================

    private void buildHandlesCard() {
        LinearLayout card = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText("手柄");
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
        add.setOnClickListener(v -> addHandle());
        head.addView(add);

        card.addView(head, wide());

        List<EdgeGesturePrefs.Handle> handles = EdgeGesturePrefs.getHandles(this);

        for (final EdgeGesturePrefs.Handle h : handles) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(11), 0, dp(11));

            // 左侧小色条，直观反映手柄在屏幕上的样子
            View swatch = new View(this);
            GradientDrawable sg = new GradientDrawable();
            sg.setColor(withAlpha(h.color, h.alpha));
            sg.setCornerRadius(dp(Math.max(2, Math.min(6, h.radius))));
            swatch.setBackground(sg);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                    dp(10), dp(36));
            sl.rightMargin = dp(12);
            row.addView(swatch, sl);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView name = new TextView(this);
            name.setText(h.name == null || h.name.isEmpty() ? "未命名手柄" : h.name);
            name.setTextSize(14);
            name.setTextColor(C_TEXT);
            col.addView(name);

            TextView meta = new TextView(this);
            meta.setTextSize(11.5f);
            meta.setTextColor(C_HINT);
            meta.setText(describe(h));
            col.addView(meta);

            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextSize(20);
            arrow.setTextColor(C_HINT);
            arrow.setPadding(dp(8), 0, 0, 0);
            row.addView(arrow);

            row.setOnClickListener(v ->
                    EdgeGestureEditorActivity.open(this, h.id));

            card.addView(row, wide());

            View line = new View(this);
            line.setBackgroundColor(C_DIVIDER);
            card.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }

        content.addView(card, cardLp());
    }

    /** 手柄摘要：位置 + 可用方向 + 距离。 */
    private String describe(EdgeGesturePrefs.Handle h) {
        StringBuilder sb = new StringBuilder();
        sb.append(h.isEdgeLeft() ? "左侧" : "右侧");
        sb.append(" · ").append(h.width).append("dp 宽");

        sb.append(" · ");
        boolean first = true;
        for (int d = 0; d < 8; d++) {
            if (!h.enabled[d]) continue;
            if (!first) sb.append(" ");
            sb.append(EdgeGesturePrefs.DIR_ARROWS[d]);
            first = false;
        }
        if (first) sb.append("无方向");

        sb.append(" · ").append(h.threshold).append("dp 触发");
        return sb.toString();
    }

    private void addHandle() {
        List<EdgeGesturePrefs.Handle> all =
                new ArrayList<>(EdgeGesturePrefs.getHandles(this));

        EdgeGesturePrefs.Handle h = new EdgeGesturePrefs.Handle();
        h.id = EdgeGesturePrefs.nextId(this);
        h.name = (h.edge == 1 ? "右侧" : "左侧") + "手柄 " + h.id;
        h.threshold = EdgeGesturePrefs.getDefaultDistance(this);
        all.add(h);
        EdgeGesturePrefs.saveHandles(this, all);
        EdgeGestureService.reload(this);

        content.postDelayed(this::rebuild, 200);
        EdgeGestureEditorActivity.open(this, h.id);
    }

    // ============================================================
    //  说明
    // ============================================================

    private void buildHelpCard() {
        LinearLayout card = card();
        card.addView(label("怎么和宏配合"), wide());

        TextView t = new TextView(this);
        t.setTextSize(12);
        t.setTextColor(C_SUB);
        t.setLineSpacing(0f, 1.45f);
        t.setText(
                "宏 → 触发器 → 「小孙OCR · 边缘滑动事件」\n"
                        + "  （在宿主的「事件」分类里，别选到别的插件）\n"
                        + "  再选一个方向 —— 每个宏只认这一个方向，互不干扰。\n"
                        + "  方向不符的宏根本不会被触发，宏里不用再判断方向。\n"
                        + "\n  滑出菜单选中后，插件把结果交给宏：\n"
                        + "    菜单索引    选中项序号（从 0 开始；-1 = 取消）\n"
                        + "    菜单文字    选中项的名字\n"
                        + "    菜单动作    选中项的动作值\n"
                        + "    （变量名在 MacroDroid 里自己定，本应用不预设）"
        );
        LinearLayout.LayoutParams tl = wide();
        tl.topMargin = dp(8);
        card.addView(t, tl);

        TextView tip = new TextView(this);
        tip.setTextSize(11.5f);
        tip.setTextColor(C_HINT);
        tip.setLineSpacing(0f, 1.35f);
        tip.setText("\n小技巧：手动长按手柄可以直接拖动位置，松手自动保存；"
                + "想改颜色和大小就点它进编辑页。\n"
                + "开启「滑动菜单」后，滑过距离不会立即触发，"
                + "而是弹出一排按钮，滑到哪个抬手就是哪个。");
        LinearLayout.LayoutParams til = wide();
        til.topMargin = dp(8);
        card.addView(tip, til);

        content.addView(card, cardLp());
    }

    // ============================================================
    //  权限
    // ============================================================

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable ignored) {
                toast("请到系统设置里手动授予悬浮窗权限");
            }
        }
    }

    // ============================================================
    //  控件工具
    // ============================================================

    private void paintRadio(TextView tv, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(2), on ? C_BRAND : C_HINT);
        tv.setBackground(g);
        tv.setText(on ? "●" : "");
        tv.setTextColor(C_BRAND);
    }

    private int withAlpha(int color, int alphaPercent) {
        int a = Math.round(255 * (Math.max(10, alphaPercent) / 100f));
        return (a << 24) | (color & 0x00FFFFFF);
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
        tv.setTextSize(14.5f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(C_TEXT);
        return tv;
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_BRAND);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        return b;
    }

    private GradientDrawable borderedBg(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    // ============================================================
    //  防烧屏
    // ============================================================

    private void buildBurnInCard() {
        LinearLayout card = card();
        card.addView(label("防烧屏"), wide());

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(C_SUB);
        hint.setLineSpacing(0f, 1.3f);
        hint.setText("手柄是常驻的悬浮条，位置常年不动，OLED 屏会顺着它的形状"
                + "慢慢留下残影。开启后手柄会隔一段时间在垂直方向轻微挪动几像素 —— "
                + "肉眼几乎看不出，但发光像素错开了，残影就不容易堆积。"
                + "\n只上下挪、不左右挪，所以手柄始终贴着屏幕边缘。");
        LinearLayout.LayoutParams hl = wide();
        hl.topMargin = dp(6);
        card.addView(hint, hl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText("防止烧屏漂移");
        t.setTextSize(14.5f);
        t.setTextColor(C_TEXT);
        col.addView(t);

        TextView sub = new TextView(this);
        sub.setTextSize(11.5f);
        sub.setTextColor(C_HINT);
        col.addView(sub);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setChecked(EdgeGesturePrefs.isBurnInEnabled(this));
        sub.setText(sw.isChecked() ? "已开启 · 位置会缓慢浮动" : "已关闭 · 位置固定不动");
        sw.setOnCheckedChangeListener((b, checked) -> {
            EdgeGesturePrefs.setBurnInEnabled(this, checked);
            // 让服务重新排一次漂移任务；关掉时服务会把手柄拨回原位
            EdgeGestureService.reload(this);
            content.postDelayed(this::rebuild, 200);
        });
        row.addView(sw);

        card.addView(row, wide());

        int range = EdgeGesturePrefs.getBurnInRangeDp(this);
        addSlider(card, "漂移幅度", range, 0, 30, " dp", v -> {
            EdgeGesturePrefs.setBurnInRangeDp(this, v);
            // 不用立刻 reload：下一次漂移任务会读到新值；
            // 但设成 0 等于「不漂」，得让服务把手柄拨回去。
            if (v == 0) EdgeGestureService.reload(this);
        });

        addSlider(card, "漂移间隔", EdgeGesturePrefs.getBurnInIntervalSec(this),
                15, 300, " 秒", v -> EdgeGesturePrefs.setBurnInIntervalSec(this, v));

        TextView tip = new TextView(this);
        tip.setTextSize(11.5f);
        tip.setTextColor(C_HINT);
        tip.setLineSpacing(0f, 1.35f);
        tip.setText("幅度越大越保险，越小越不显眼。幅度 0 = 不漂移。"
                + "\n间隔指的是「每隔多久换一次位置」，不需要太频繁。");
        LinearLayout.LayoutParams tl = wide();
        tl.topMargin = dp(10);
        card.addView(tip, tl);

        content.addView(card, cardLp());
    }

    // ============================================================
    //  控件工具
    // ============================================================

    private interface OnIntValue {
        void on(int v);
    }

    /**
     * 一行「标题 + 数值 + 滑杆」。
     *
     * 注意 minSdk 是 24，SeekBar.setMin() 要 API 26，
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

    private LinearLayout.LayoutParams topMargin(int dpValue) {
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(dpValue);
        return lp;
    }

    private LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 供 MainActivity 复用。 */
    public static void open(android.content.Context ctx) {
        Intent i = new Intent(ctx, EdgeGestureActivity.class);
        if (!(ctx instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }
}