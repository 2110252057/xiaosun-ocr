package com.xiaosun.ocr;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

import static com.xiaosun.ocr.Palette.*;
import static com.xiaosun.ocr.Ui.*;

/**
 * 手柄黑名单 —— 「哪些应用里不显示手柄」。
 *
 * ════════════════════════════════════════════════════════════════
 * 【这一页负责什么】
 * ════════════════════════════════════════════════════════════════
 *   · 总开关（关掉 = 完全不按应用隐藏，和加这个功能之前一样）
 *   · 名单（添加 / 移除应用）
 *   · 黑白两种语义（名单内不显示 / 只有名单内显示）
 *   · 侦测通道的状态与授权入口
 *
 * 真正干活的地方是 {@link EdgeGestureService#rebuild()}：它在加窗之前
 * 问一次「当前前台在不在名单里」，在就不加窗。本页只改配置 + 喊一声 reload。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么要有「名单语义」这个开关】
 * ════════════════════════════════════════════════════════════════
 * 实际用起来有两种截然相反的诉求：
 *   · 打游戏 / 看视频时嫌手柄碍事 → 把那些应用拉黑（黑名单，默认）；
 *   · 平时不想要手柄，只在少数几个应用里用 → 把那几个应用设为白名单。
 * 两种诉求用的是同一份包名列表，只是判定取反。做成开关比做两套列表省事，
 * 用户想反过来也不必把名单重新录一遍。
 */
public class EdgeGestureBlacklistActivity extends AppCompatActivity {


    private LinearLayout content;

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
        bar.setBackgroundColor(C_BG);
        bar.setPadding(dp(this, 8), dp(this, 14), dp(this, 16), dp(this, 14));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(24);
        back.setTextColor(C_TEXT);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(this, 48), dp(this, 40)));

        // 两级标题：标明本页属于「边缘手势」，避免被当成顶层功能。
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("手柄黑名单");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(C_TEXT);
        titles.addView(title);

        TextView sub = new TextView(this);
        sub.setText("边缘手势 · 指定应用里隐藏手柄");
        sub.setTextSize(11f);
        sub.setTextColor(C_SUB);
        titles.addView(sub);

        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return bar;
    }

    // ============================================================
    //  页面组装
    // ============================================================

    private void rebuild() {
        content.removeAllViews();
        buildSwitchCard();
        buildListCard();
    }

    private void buildSwitchCard() {
        LinearLayout card = card();

        // 没开无障碍就判断不出「你在哪个应用里」，黑名单无从生效，
        // 因此总开关直接置灰 —— 比让用户配完名单发现没效果要好懂得多。
        boolean ready = ForegroundAppDetector.anyChannel(this);

        // ---- 总开关 ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText("按应用隐藏手柄");
        t.setTextSize(14.5f);
        t.setTextColor(ready ? C_TEXT : C_HINT);
        col.addView(t);

        TextView s = new TextView(this);
        s.setTextSize(11.5f);
        s.setTextColor(C_HINT);
        s.setLineSpacing(0f, 1.3f);
        if (!ready) {
            s.setText("需先开启无障碍服务");
        } else {
            s.setText(EdgeGesturePrefs.isBlacklistEnabled(this)
                    ? "已开启" : "已关闭 · 手柄在任何应用里都显示");
        }
        col.addView(s);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(EdgeGesturePrefs.isBlacklistEnabled(this));
        sw.setEnabled(ready);
        sw.setOnCheckedChangeListener((b, checked) -> {
            EdgeGesturePrefs.setBlacklistEnabled(this, checked);
            EdgeGestureService.reload(this);
            content.postDelayed(this::rebuild, 180);
        });
        row.addView(sw);

        card.addView(row, wide());

        // ---- 名单语义 ----
        // ★ 分隔线的高度必须写死 dp(this, 1)。
        //   这里曾经用 wide()（高度 WRAP_CONTENT）—— 而一个没有内容的
        //   裸 View 在 MeasureSpec.AT_MOST 下的默认尺寸是「铺满可用空间」
        //   （View.getDefaultSize 的 AT_MOST 分支返回 specSize），于是这条
        //   「1 像素的分隔线」实际被量成 1600 多像素高，把下面的白名单开关
        //   和整张名单卡全部顶出屏幕 —— 表现为「打开黑名单页面里是空白」。
        View line = new View(this);
        line.setBackgroundColor(C_DIVIDER);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 1));
        ll.topMargin = dp(this, 12);
        card.addView(line, ll);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2l = wide();
        r2l.topMargin = dp(this, 12);
        row2.setPadding(0, dp(this, 10), 0, 0);

        LinearLayout col2 = new LinearLayout(this);
        col2.setOrientation(LinearLayout.VERTICAL);

        TextView t2 = new TextView(this);
        t2.setText("白名单模式");
        t2.setTextSize(14.5f);
        t2.setTextColor(ready ? C_TEXT : C_HINT);
        col2.addView(t2);

        TextView s2 = new TextView(this);
        s2.setTextSize(11.5f);
        s2.setTextColor(C_HINT);
        s2.setLineSpacing(0f, 1.3f);
        s2.setText(EdgeGesturePrefs.isBlacklistWhitelistMode(this)
                ? "只在这些应用里显示手柄"
                : "在这些应用里不显示手柄");
        col2.addView(s2);

        row2.addView(col2, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw2 = new Switch(this);
        sw2.setChecked(EdgeGesturePrefs.isBlacklistWhitelistMode(this));
        sw2.setEnabled(ready && EdgeGesturePrefs.isBlacklistEnabled(this));
        sw2.setOnCheckedChangeListener((b, checked) -> {
            EdgeGesturePrefs.setBlacklistWhitelistMode(this, checked);
            EdgeGestureService.reload(this);
            content.postDelayed(this::rebuild, 180);
        });
        row2.addView(sw2);

        card.addView(row2, r2l);

        // ---- 未就绪时：给出唯一的自救入口，而不是一堆说明 ----
        if (!ready) {
            Button b1 = primaryButton("开启无障碍服务");
            b1.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    toast("在列表里开启「Grace 截图」");
                } catch (Throwable t2ex) {
                    toast("请到系统设置 → 无障碍 里开启");
                }
            });
            LinearLayout.LayoutParams b1l = wide();
            b1l.topMargin = dp(this, 12);
            card.addView(b1, b1l);
        }

        content.addView(card, cardLp());
    }

    // ============================================================
    //  名单
    // ============================================================

    private void buildListCard() {
        LinearLayout card = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        List<String> pkgs = EdgeGesturePrefs.getBlacklist(this);

        TextView t = new TextView(this);
        t.setText(EdgeGesturePrefs.isBlacklistWhitelistMode(this)
                ? "白名单（" + pkgs.size() + "）" : "名单（" + pkgs.size() + "）");
        t.setTextSize(14.5f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(C_TEXT);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView add = new TextView(this);
        add.setText("＋ 添加应用");
        add.setTextSize(13);
        add.setTextColor(C_BRAND);
        add.setPadding(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 6));
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(C_BRAND_SOFT);
        ab.setCornerRadius(dp(this, 8));
        add.setBackground(ab);
        add.setOnClickListener(v -> showAddDialog());
        head.addView(add);

        card.addView(head, wide());

        if (pkgs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setTextSize(12.5f);
            empty.setTextColor(C_HINT);
            empty.setLineSpacing(0f, 1.35f);
            empty.setText("名单是空的。点右上角「添加应用」把想屏蔽的应用加进来。");
            LinearLayout.LayoutParams el = wide();
            el.topMargin = dp(this, 12);
            card.addView(empty, el);
        }

        for (String pkg : pkgs) {
            card.addView(buildRow(pkg), wide());

            View line = new View(this);
            line.setBackgroundColor(C_DIVIDER);
            card.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 1)));
        }

        content.addView(card, cardLp());
    }

    private View buildRow(final String pkg) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(this, 11), 0, dp(this, 11));

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36));
        il.rightMargin = dp(this, 12);
        row.addView(icon, il);
        Drawable d = MenuIcons.load(this, "app:" + pkg);
        if (d != null) {
            icon.setImageDrawable(d);
        } else {
            // 图标取不到（应用已卸载）就画一个灰方块，别留空洞
            GradientDrawable g = new GradientDrawable();
            g.setColor(C_DIVIDER);
            g.setCornerRadius(dp(this, 8));
            icon.setBackground(g);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(appLabel(pkg));
        name.setTextSize(14);
        name.setTextColor(C_TEXT);
        col.addView(name);

        TextView sub = new TextView(this);
        sub.setText(pkg);
        sub.setTextSize(11);
        sub.setTextColor(C_HINT);
        col.addView(sub);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView del = new TextView(this);
        del.setText("移除");
        del.setTextSize(12.5f);
        del.setTextColor(0xFFE5484D);
        del.setPadding(dp(this, 10), dp(this, 6), dp(this, 4), dp(this, 6));
        del.setOnClickListener(v -> {
            EdgeGesturePrefs.removeBlacklist(this, pkg);
            // 立刻让手柄按新名单重新判定，不用等用户返回上一页
            EdgeGestureService.reload(this);
            content.postDelayed(this::rebuild, 150);
        });
        row.addView(del);

        return row;
    }

    /** 应用显示名；应用已卸载时退回包名。 */
    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            CharSequence cs = pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0));
            return cs == null ? pkg : cs.toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    /**
     * 「添加应用」对话框：搜索框 + 应用列表。
     *
     * 【为什么一次性枚举完整列表再本地过滤】
     * 每敲一个字就重新 queryIntentActivities 一次，在中低端机上会明显卡顿
     * （这个调用本身就要遍历所有包）。一次列全、内存里过滤，输入跟手得多。
     */
    private void showAddDialog() {
        final List<EdgeGesturePrefs.AppEntry> all =
                EdgeGesturePrefs.searchInstalledApps(this, "");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(this, 12);
        box.setPadding(pad, dp(this, 6), pad, 0);

        final EditText search = new EditText(this);
        search.setHint("搜索应用名 / 包名");
        search.setTextSize(14);
        search.setSingleLine(true);
        box.addView(search, wide());

        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);

        ScrollView sv = new ScrollView(this);
        sv.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 360));
        box.addView(sv, sl);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加应用到名单")
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();

        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            listBox.removeAllViews();
            String q = search.getText() == null ? "" : search.getText().toString().trim();
            String ql = q.toLowerCase();
            int n = 0;
            for (EdgeGesturePrefs.AppEntry e : all) {
                if (ql.length() > 0
                        && !e.label.toLowerCase().contains(ql)
                        && !e.pkg.toLowerCase().contains(ql)) {
                    continue;
                }
                listBox.addView(addDialogRow(e, dialog));
                n++;
                if (n >= 200) break;     // 兜底：极端机型上别把对话框撑爆
            }
            if (n == 0) {
                TextView empty = new TextView(this);
                empty.setTextSize(13);
                empty.setTextColor(C_HINT);
                empty.setPadding(dp(this, 4), dp(this, 20), dp(this, 4), dp(this, 20));
                empty.setGravity(Gravity.CENTER);
                empty.setText(all.isEmpty() ? "没有可见的应用（检查包可见性权限）" : "没有匹配的应用");
                listBox.addView(empty, wide());
            }
        };
        fill[0].run();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                fill[0].run();
            }
        });

        dialog.show();
    }

    private View addDialogRow(final EdgeGesturePrefs.AppEntry e, final AlertDialog dialog) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(this, 2), dp(this, 9), dp(this, 2), dp(this, 9));

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(this, 32), dp(this, 32));
        il.rightMargin = dp(this, 12);
        row.addView(icon, il);
        Drawable d = MenuIcons.load(this, "app:" + e.pkg);
        if (d != null) icon.setImageDrawable(d);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextSize(14);
        name.setTextColor(C_TEXT);
        col.addView(name);

        TextView sub = new TextView(this);
        sub.setText(e.pkg);
        sub.setTextSize(11);
        sub.setTextColor(C_HINT);
        col.addView(sub);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean already = EdgeGesturePrefs.isBlacklisted(this, e.pkg);
        TextView mark = new TextView(this);
        mark.setText(already ? "已在名单" : "添加");
        mark.setTextSize(12.5f);
        mark.setTextColor(already ? C_HINT : C_BRAND);
        mark.setPadding(dp(this, 10), dp(this, 6), dp(this, 4), dp(this, 6));
        row.addView(mark);

        if (!already) {
            row.setOnClickListener(v -> {
                EdgeGesturePrefs.addBlacklist(this, e.pkg);
                EdgeGestureService.reload(this);
                toast("已添加：" + e.label);
                dialog.dismiss();
                content.postDelayed(this::rebuild, 150);
            });
        }

        return row;
    }

    // ============================================================
    //  控件工具
    // ============================================================

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
        b.setTextSize(13.5f);
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_BRAND);
        g.setCornerRadius(dp(this, 10));
        b.setBackground(g);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(C_BRAND);
        b.setTextSize(13f);
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_BRAND_SOFT);
        g.setCornerRadius(dp(this, 10));
        b.setBackground(g);
        return b;
    }

                private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 供 {@link EdgeGestureActivity} 调用。 */
    public static void open(android.content.Context ctx) {
        Intent i = new Intent(ctx, EdgeGestureBlacklistActivity.class);
        if (!(ctx instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }
}