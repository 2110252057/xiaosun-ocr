package com.xiaosun.ocr;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import static com.xiaosun.ocr.Palette.*;
import static com.xiaosun.ocr.Ui.*;

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
 *   宏 → 触发器：「Grace · 边缘滑动事件」
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
        content.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 40));
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
        // 与首页统一的浅灰顶栏（原来是蓝底白字）。右上角「⋮」已移除，只留首页。
        bar.setBackgroundColor(C_BG);
        bar.setPadding(dp(this, 8), dp(this, 14), dp(this, 16), dp(this, 14));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(24);
        back.setTextColor(C_TEXT);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(this, 48), dp(this, 40)));

        TextView title = new TextView(this);
        title.setText("边缘手势");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(C_TEXT);
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
        // 手柄滑过之后弹什么：滑动菜单是「手柄方向行为」的一部分，
        // 放在手柄列表下面，顺序与用户的心理模型一致（先有手柄，才谈菜单）。
        buildMenuCard();
        buildBlacklistCard();
        buildBurnInCard();
        // 日志已收进顶栏「⋮」菜单（LogCard.buildMenuButton），不再占页面底部。
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

        // 原先这里还有一行「手指按在手柄上朝任意方向滑动，滑过设定距离即触发。」
        // —— 纯操作说明，看一眼手柄就知道怎么用，页面不必再教一遍。
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
        card.setBackground(borderedBg(this, C_SURFACE, 0x33F79009, 14));

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
        d.setText("手柄是悬浮窗，必须授予此权限才能显示。");
        LinearLayout.LayoutParams dl = wide();
        dl.topMargin = dp(this, 6);
        card.addView(d, dl);

        Button go = primaryButton("去开启");
        go.setOnClickListener(v -> requestOverlay());
        LinearLayout.LayoutParams gl = wide();
        gl.topMargin = dp(this, 12);
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
        add.setPadding(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 6));
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(C_BRAND_SOFT);
        ab.setCornerRadius(dp(this, 8));
        add.setBackground(ab);
        add.setOnClickListener(v -> addHandle());
        head.addView(add);

        card.addView(head, wide());

        List<EdgeGesturePrefs.Handle> handles = EdgeGesturePrefs.getHandles(this);

        for (final EdgeGesturePrefs.Handle h : handles) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(this, 11), 0, dp(this, 11));

            // 左侧小色条，直观反映手柄在屏幕上的样子
            View swatch = new View(this);
            GradientDrawable sg = new GradientDrawable();
            sg.setColor(withAlpha(h.color, h.alpha));
            sg.setCornerRadius(dp(this, Math.max(2, Math.min(6, h.radius))));
            swatch.setBackground(sg);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                    dp(this, 10), dp(this, 36));
            sl.rightMargin = dp(this, 12);
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
            arrow.setPadding(dp(this, 8), 0, 0, 0);
            row.addView(arrow);

            row.setOnClickListener(v ->
                    EdgeGestureEditorActivity.open(this, h.id));

            card.addView(row, wide());

            View line = new View(this);
            line.setBackgroundColor(C_DIVIDER);
            card.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 1)));
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
        // 直触方向单独攒起来写在末尾：列表页一眼能看出「这个方向滑了
        // 不会弹菜单、直接发事件」，不用点进编辑页才知道。
        StringBuilder direct = new StringBuilder();
        for (int d = 0; d < 8; d++) {
            if (!h.enabled[d]) continue;
            if (!first) sb.append(" ");
            sb.append(EdgeGesturePrefs.DIR_ARROWS[d]);
            first = false;
            if (!h.menuOf(d)) direct.append(EdgeGesturePrefs.DIR_ARROWS[d]);
        }
        if (first) sb.append("无方向");
        sb.append(" · ").append(h.threshold).append("dp 触发");
        if (direct.length() > 0) sb.append(" · 直触 ").append(direct);
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
    //  滑动菜单入口
    // ============================================================

    /**
     * 「滑过手柄之后弹出的一排按钮」的入口卡。
     *
     * 菜单的外观 / 布局 / 配色 / 菜单项都在
     * {@link EdgeGestureMenuActivity} 里调 —— 那一页本身就有十几个
     * 分组控件，塞进本页会让「手柄列表」被挤到看不见。
     * 这里只给一行状态 + 跳转，和下面的黑名单卡保持同一套视觉。
     */
    private void buildMenuCard() {
        LinearLayout card = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText("滑动菜单");
        t.setTextSize(14.5f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(C_TEXT);
        col.addView(t);

        TextView s = new TextView(this);
        s.setTextSize(11.5f);
        s.setTextColor(C_HINT);
        s.setLineSpacing(0f, 1.3f);
        boolean on = EdgeGesturePrefs.isMenuEnabled(this);
        int n = EdgeGesturePrefs.getMenuItems(this).size();
        if (!on) {
            s.setText("未启用 · 滑过手柄即直接触发宏");
        } else if (n == 0) {
            s.setText("已启用 · 还没有菜单项，滑过去不会弹");
            s.setTextColor(C_WARN);
        } else {
            s.setText("已启用 · " + n + " 项，滑到某项上抬手才触发");
        }
        col.addView(s);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(C_HINT);
        arrow.setPadding(dp(this, 8), 0, 0, 0);
        row.addView(arrow);

        row.setOnClickListener(v -> EdgeGestureMenuActivity.open(this));

        card.addView(row, wide());
        content.addView(card, cardLp());
    }

    // ============================================================
    //  黑名单入口
    // ============================================================

    /**
     * 「哪些应用里不显示手柄」的入口卡。
     *
     * 这里只做一句话状态 + 一个跳转，名单的编辑都在
     * {@link EdgeGestureBlacklistActivity} 里 —— 应用列表要滚动、要搜索，
     * 塞进本页会把这一页撑得又长又乱。
     */
    private void buildBlacklistCard() {
        LinearLayout card = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText("手柄黑名单");
        t.setTextSize(14.5f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(C_TEXT);
        col.addView(t);

        TextView s = new TextView(this);
        s.setTextSize(11.5f);
        s.setTextColor(C_HINT);
        s.setLineSpacing(0f, 1.3f);
        boolean on = EdgeGesturePrefs.isBlacklistEnabled(this);
        int n = EdgeGesturePrefs.getBlacklist(this).size();
        boolean white = EdgeGesturePrefs.isBlacklistWhitelistMode(this);
        if (!on) {
            s.setText("未开启 · 手柄在所有应用里都显示");
        } else if (n == 0) {
            s.setText("已开启 · 名单为空，暂时不会隐藏");
        } else {
            s.setText((white ? "白名单" : "黑名单") + " · " + n + " 个应用"
                    + (white ? "显示手柄，其余不显示" : "不显示手柄"));
        }
        col.addView(s);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(C_HINT);
        arrow.setPadding(dp(this, 8), 0, 0, 0);
        row.addView(arrow);

        row.setOnClickListener(v -> EdgeGestureBlacklistActivity.open(this));

        card.addView(row, wide());

        // 开启了但侦测通道没就绪时，这里直接点出来 —— 否则用户配完名单
        // 发现没效果，会以为是自己操作错了。
        if (on && !ForegroundAppDetector.anyChannel(this)) {
            TextView warn = new TextView(this);
            warn.setTextSize(11.5f);
            warn.setTextColor(C_WARN);
            warn.setLineSpacing(0f, 1.35f);
            warn.setText("⚠ 前台应用侦测未就绪（没开无障碍），"
                    + "黑名单暂时不会生效。点进去开启。");
            LinearLayout.LayoutParams wl = wide();
            wl.topMargin = dp(this, 8);
            card.addView(warn, wl);
        }

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
        g.setStroke(dp(this, 2), on ? C_BRAND : C_HINT);
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
        c.setBackground(borderedBg(this, C_SURFACE, C_DIVIDER, 14));
        c.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        c.setElevation(dp(this, 1));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = wide();
        lp.bottomMargin = dp(this, 12);
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
        g.setCornerRadius(dp(this, 10));
        b.setBackground(g);
        return b;
    }

        // ============================================================
    //  防烧屏
    // ============================================================

    private void buildBurnInCard() {
        LinearLayout card = card();
        card.addView(label("防烧屏"), wide());

        // 原先这里有一段长说明（OLED 残影原理 + 漂移怎么起作用），
        // 以及卡片底部一行「幅度越大越保险，越小越不显眼。0 = 不漂移。」。
        // 都是功能介绍，不是操作，已删；滑块本身标着单位和范围，调一下就明白。

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
        row.addView(val, new LinearLayout.LayoutParams(dp(this, 64),
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
        lp.topMargin = dp(this, dpValue);
        return lp;
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