package com.xiaosun.ocr;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.xiaosun.ocr.Palette.*;
import static com.xiaosun.ocr.Ui.*;

/**
 * 无障碍管理：用 root 直接开启/关闭任意应用的无障碍服务。
 *
 * <h3>为什么要有这一页</h3>
 * 系统的「无障碍」设置页有两个老问题：
 * <ol>
 *   <li>想开某个应用的服务，得在一长串列表里翻，点进去还要过好几层确认框；</li>
 *   <li>很多自动化工具（自动点击、抢单、群控）都挂无障碍服务，</li>
 *       几十个应用要逐个手动点一遍，换机或重置后得从头再来。</li>
 * </ol>
 * 有了 root 就能直接写系统的已启用清单，这一页把「翻列表 + 点确认框」
 * 压缩成「一个开关」。
 *
 * <h3>界面结构</h3>
 * <pre>
 *   状态卡    —— root 可用性 + 当前已启用数量 + 一键全关
 *   搜索框    —— 按应用名 / 包名过滤（应用多时必需）
 *   应用列表  —— 一行一个应用，右侧开关；点开可看它名下的每个服务
 *   说明卡    —— 权限边界与风险提示
 * </pre>
 *
 * <h3>为什么要按「应用」聚合而不是「服务」</h3>
 * 系统设置里是「一个服务一行」，但绝大多数应用只暴露一个服务，
 * 用户的心智模型是「我要开微信的无障碍」，不是「我要开
 * com.tencent.mm.plugin.a11y.MmService」。所以一行一个应用，
 * 多服务的应用展开后逐个开关。
 */
public class A11yActivity extends AppCompatActivity {


    private LinearLayout content;
    private LinearLayout listBox;
    private TextView statusTitle;
    private TextView statusSub;
    private EditText searchEt;

    /** 全部服务（枚举一次，切页面回来重新枚举）。 */
    private List<A11yManager.ServiceInfo> allServices = new ArrayList<A11yManager.ServiceInfo>();
    /** 系统当前已启用的集合（展开后的全名）。 */
    private Set<String> enabled;
    /** 搜索关键字，小写。 */
    private String query = "";
    /** 正在执行 root 命令，期间禁用所有开关，避免并发读写把清单写乱。 */
    private boolean busy = false;
    /** 是否把系统核心服务也列出来（默认不列）。 */
    private boolean showSystem = false;

    /** 「关闭全部」按钮。没有 root 时要跟着置灰，只读状态下不该还能点。 */
    private TextView allOffBtn;

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

        buildStatusCard();
        buildSearchCard();
        buildListCard();
        buildNoteCard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到本页都重新读一遍：用户可能刚在系统设置里手动改过，
        // 也可能在 Magisk 里刚授权了 root。
        reloadAsync();
    }

    // ============================================================
    //  顶栏
    // ============================================================

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        /*
         * 与首页同一套顶栏：页面底色 C_BG + 深色文字，不再是蓝底白字。
         *
         * 蓝色顶栏是二级页原先的做法 —— 靠一块深色把标题压住，跟浅灰内容区
         * 分成两截。现在所有页面统一成「标题印在页面上」，翻页时不再有一块
         * 蓝色突然出现/消失。
         *
         * 连带改动：返回箭头与标题由白转深色，右侧「⋮」已移除（只剩首页保留）。
         */
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
        title.setText("无障碍管理");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(C_TEXT);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右上角「⋮」已移除：日志/隐藏/保活这些全应用级的开关只留在首页，
        // 二级页是一个个具体功能，不该再挂一份重复的全局菜单。

        return bar;
    }

    // ============================================================
    //  状态卡
    // ============================================================

    private void buildStatusCard() {
        LinearLayout card = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        statusTitle = new TextView(this);
        statusTitle.setTextSize(15);
        statusTitle.setTypeface(null, Typeface.BOLD);
        row.addView(statusTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refresh = new TextView(this);
        refresh.setText("刷新");
        refresh.setTextSize(12.5f);
        refresh.setTextColor(C_BRAND);
        refresh.setTypeface(null, Typeface.BOLD);
        refresh.setPadding(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(C_BRAND_SOFT);
        rbg.setCornerRadius(dp(this, 9));
        refresh.setBackground(rbg);
        refresh.setOnClickListener(v -> {
            A11yManager.resetRootCache();
            reloadAsync();
        });
        row.addView(refresh);
        card.addView(row, wide());

        statusSub = new TextView(this);
        statusSub.setTextSize(12);
        statusSub.setTextColor(C_SUB);
        statusSub.setLineSpacing(0f, 1.35f);
        card.addView(statusSub, topMargin(6));

        // 一键全关：误配过一堆服务后想清干净时，比逐个点快得多。
        allOffBtn = new TextView(this);
        allOffBtn.setText("关闭全部无障碍服务");
        allOffBtn.setTextSize(13);
        allOffBtn.setTextColor(C_DANGER);
        allOffBtn.setTypeface(null, Typeface.BOLD);
        allOffBtn.setGravity(Gravity.CENTER);
        allOffBtn.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
        GradientDrawable obg = new GradientDrawable();
        obg.setColor(0xFFFEF2F2);
        obg.setCornerRadius(dp(this, 10));
        obg.setStroke(dp(this, 1), 0xFFFECACA);
        allOffBtn.setBackground(obg);
        allOffBtn.setOnClickListener(v -> confirmDisableAll());
        card.addView(allOffBtn, topMargin(12));

        content.addView(card, cardLp());
    }

    private void refreshStatus() {
        if (statusTitle == null) {
            return;
        }
        boolean root = A11yManager.isRootAvailable();
        // 只读状态（没有 root）时「关闭全部」也不能点 —— 它同样是一条写入。
        if (allOffBtn != null) {
            allOffBtn.setEnabled(root);
            allOffBtn.setAlpha(root ? 1f : 0.45f);
        }
        if (!root) {
            statusTitle.setText("没有 root 权限");
            statusTitle.setTextColor(C_DANGER);
            statusSub.setText("本页需要 root 才能直接开关无障碍服务。\n"
                    + A11yManager.rootDetail() + "\n"
                    + "也可以点下面的「打开系统无障碍设置」手动开启。");
            return;
        }
        int n = enabled == null ? 0 : enabled.size();
        statusTitle.setText("root 可用 · 已启用 " + n + " 个服务");
        statusTitle.setTextColor(n > 0 ? C_OK : C_SUB);
        statusSub.setText("点任意应用的开关即可直接生效，不用再跳系统设置页。");
    }

    // ============================================================
    //  搜索
    // ============================================================

    private void buildSearchCard() {
        LinearLayout card = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        searchEt = new EditText(this);
        searchEt.setHint("搜索应用名或包名…");
        searchEt.setTextSize(14);
        searchEt.setSingleLine(true);
        searchEt.setInputType(InputType.TYPE_CLASS_TEXT);
        searchEt.setBackground(null);
        searchEt.setPadding(0, dp(this, 4), 0, dp(this, 4));
        searchEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase();
                renderList();
            }
        });
        row.addView(searchEt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 「显示系统服务」：默认藏起来，避免误关掉 systemui/settings
        // 的无障碍后手机变得不好操作。
        final TextView sysBtn = new TextView(this);
        sysBtn.setText(showSystem ? "隐藏系统" : "显示系统");
        sysBtn.setTextSize(11.5f);
        sysBtn.setTextColor(C_SUB);
        sysBtn.setPadding(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(0xFFF3F4F6);
        sbg.setCornerRadius(dp(this, 9));
        sysBtn.setBackground(sbg);
        sysBtn.setOnClickListener(v -> {
            showSystem = !showSystem;
            sysBtn.setText(showSystem ? "隐藏系统" : "显示系统");
            reloadAsync();
        });
        row.addView(sysBtn);

        card.addView(row, wide());
        content.addView(card, cardLp());
    }

    // ============================================================
    //  列表
    // ============================================================

    private void buildListCard() {
        LinearLayout card = card();
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(listBox, wide());
        content.addView(card, cardLp());
    }

    /** 重新枚举 + 重读已启用集合，然后在主线程渲染。 */
    private void reloadAsync() {
        if (busy) {
            return;
        }
        busy = true;
        setEnabledAllSwitches(false);
        listBox.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("正在读取…");
        loading.setTextSize(13);
        loading.setTextColor(C_HINT);
        loading.setPadding(0, dp(this, 14), 0, dp(this, 14));
        listBox.addView(loading);

        new Thread(() -> {
            final List<A11yManager.ServiceInfo> svcs =
                    A11yManager.listServices(this, showSystem);
            final Set<String> on = A11yManager.getEnabled();
            runOnUiThread(() -> {
                allServices = svcs;
                enabled = on;
                busy = false;
                refreshStatus();
                renderList();
            });
        }, "a11y-reload").start();
    }

    /** 按当前 query 过滤并渲染列表。 */
    private void renderList() {
        if (listBox == null || enabled == null) {
            return;
        }
        listBox.removeAllViews();

        List<A11yManager.AppGroup> groups =
                A11yManager.groupByApp(this, allServices);

        int shown = 0;
        boolean first = true;
        for (A11yManager.AppGroup g : groups) {
            if (!match(g)) {
                continue;
            }
            if (!first) {
                listBox.addView(divider());
            }
            first = false;
            listBox.addView(buildAppRow(g));
            shown++;
        }

        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(allServices.isEmpty()
                    ? "没有找到任何无障碍服务。\n"
                      + "如果确定某个应用带无障碍服务，点上面的「显示系统」看看。"
                    : "没有匹配「" + query + "」的应用。");
            empty.setTextSize(12.5f);
            empty.setTextColor(C_HINT);
            empty.setLineSpacing(0f, 1.4f);
            empty.setPadding(0, dp(this, 16), 0, dp(this, 16));
            listBox.addView(empty);
        }

        // 没有 root 时一律置灰：写不进去，点了只会换来一句「操作失败」，
        // 不如让用户一眼看出「这一页现在只能看，不能改」。
        // isRootAvailable() 的结果有缓存，这里不会额外起 su 进程。
        setEnabledAllSwitches(!busy && A11yManager.isRootAvailable());
    }

    /** 一行 = 一个应用。 */
    private View buildAppRow(final A11yManager.AppGroup g) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(this, 11), 0, dp(this, 11));

        // 应用图标。拿不到就退化成「首字」方块，和首页那套视觉一致。
        ImageView icon = new ImageView(this);
        Drawable d = null;
        try {
            d = getPackageManager().getApplicationIcon(g.pkg);
        } catch (Throwable ignored) {
            // 应用被卸载或图标资源损坏，降级即可。
        }
        if (d != null) {
            icon.setImageDrawable(d);
        } else {
            TextView fallback = new TextView(this);
            String ch = g.appLabel.isEmpty() ? "?" : g.appLabel.substring(0, 1);
            fallback.setText(ch);
            fallback.setTextSize(15);
            fallback.setTypeface(null, Typeface.BOLD);
            fallback.setTextColor(C_BRAND);
            fallback.setGravity(Gravity.CENTER);
            GradientDrawable fbg = new GradientDrawable();
            fbg.setColor(C_BRAND_SOFT);
            fbg.setCornerRadius(dp(this, 10));
            fallback.setBackground(fbg);
            row.addView(fallback, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));
        }
        if (d != null) {
            row.addView(icon, new LinearLayout.LayoutParams(dp(this, 34), dp(this, 34)));
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(this, 11);

        TextView name = new TextView(this);
        name.setText(g.appLabel);
        name.setTextSize(14);
        name.setTextColor(C_TEXT);
        col.addView(name);

        TextView sub = new TextView(this);
        sub.setTextSize(11);
        sub.setTextColor(C_HINT);
        // 副标题说清楚「状态 + 有几个服务」，多服务的应用才有必要展开。
        StringBuilder sb = new StringBuilder();
        if (g.allEnabled(enabled)) {
            sb.append("已启用");
        } else if (g.anyEnabled(enabled)) {
            sb.append("部分启用");
        } else {
            sb.append("未启用");
        }
        if (g.services.size() > 1) {
            sb.append(" · ").append(g.services.size()).append(" 个服务");
        } else if (g.services.size() == 1) {
            sb.append(" · ").append(g.services.get(0).label);
        }
        sub.setText(sb.toString());
        col.addView(sub, topMargin(2));
        row.addView(col, clp);

        // 右侧开关。多服务的应用：开关操作「全部服务」，
        // 想单独控制就点这一行展开。
        final Switch sw = new Switch(this);
        sw.setChecked(g.allEnabled(enabled));
        // 部分启用时用一个特殊标记：文本提示「部分」，
        // 免得用户以为完全没开。
        if (g.anyEnabled(enabled) && !g.allEnabled(enabled)) {
            sw.setText("部分");
            sw.setTextSize(11);
        }
        sw.setEnabled(!busy);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            if (busy) {
                // 正在执行别的命令：把开关拨回去，不做任何写入。
                btn.setChecked(!checked);
                return;
            }
            applyGroup(g, checked);
        });
        row.addView(sw);

        // 多服务的应用整行可点，展开后逐个开关。
        if (g.services.size() > 1) {
            row.setClickable(true);
            row.setOnClickListener(v -> showServiceDialog(g));
        }

        return row;
    }

    /** 把整个应用名下的服务一起开/关。 */
    private void applyGroup(final A11yManager.AppGroup g, final boolean on) {
        busy = true;
        setEnabledAllSwitches(false);
        new Thread(() -> {
            final A11yManager.Result r = A11yManager.setAll(g.services, on);
            final Set<String> now = A11yManager.getEnabled();
            runOnUiThread(() -> {
                busy = false;
                enabled = now;
                refreshStatus();
                renderList();
                if (!r.ok) {
                    toast("操作失败：" + r.message);
                } else {
                    toast((on ? "已开启 " : "已关闭 ") + g.appLabel);
                }
            });
        }, "a11y-apply").start();
    }

    /** 多服务应用：弹窗逐个开关。 */
    private void showServiceDialog(final A11yManager.AppGroup g) {
        // 没有 root 时不开弹窗：弹出来每一项都点不动，比不让点更让人困惑。
        if (!A11yManager.isRootAvailable()) {
            toast("没有 root 权限，只能查看");
            return;
        }
        final List<A11yManager.ServiceInfo> list = g.services;
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            A11yManager.ServiceInfo s = list.get(i);
            boolean on = enabled.contains(s.flatten());
            items[i] = (on ? "✓ " : "○ ") + s.label;
        }
        new AlertDialog.Builder(this)
                .setTitle(g.appLabel)
                .setItems(items, (dlg, which) -> {
                    final A11yManager.ServiceInfo s = list.get(which);
                    final boolean target = !enabled.contains(s.flatten());
                    busy = true;
                    setEnabledAllSwitches(false);
                    new Thread(() -> {
                        final A11yManager.Result r =
                                target ? A11yManager.enable(s) : A11yManager.disable(s);
                        final Set<String> now = A11yManager.getEnabled();
                        runOnUiThread(() -> {
                            busy = false;
                            enabled = now;
                            refreshStatus();
                            renderList();
                            toast(r.ok
                                    ? ((target ? "已开启 " : "已关闭 ") + s.label)
                                    : ("操作失败：" + r.message));
                        });
                    }, "a11y-toggle").start();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmDisableAll() {
        new AlertDialog.Builder(this)
                .setTitle("关闭全部无障碍服务")
                .setMessage("这会清空系统的「已启用无障碍服务」清单，"
                        + "包括系统自带的开关控制、TalkBack 等。\n\n"
                        + "确定要继续吗？")
                .setPositiveButton("全部关闭", (d, w) -> {
                    busy = true;
                    setEnabledAllSwitches(false);
                    new Thread(() -> {
                        final A11yManager.Result r = A11yManager.disableAll();
                        final Set<String> now = A11yManager.getEnabled();
                        runOnUiThread(() -> {
                            busy = false;
                            enabled = now;
                            refreshStatus();
                            renderList();
                            toast(r.ok ? "已关闭全部无障碍服务" : ("失败：" + r.message));
                        });
                    }, "a11y-alloff").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============================================================
    //  跳系统设置
    // ============================================================

    /**
     * 一个「打开系统无障碍设置」的按钮。
     *
     * <p>【原先这里还有一张四条的说明卡，已删除】
     * 那四条分别是：本页在做什么、无障碍权限很高、核心服务默认隐藏、没有 root 时只读。
     * 前三条属于功能介绍与风险说明，README 的「无障碍管理」一章都有完整版；
     * 第四条在状态卡上已经用「未获得 root · 只能跳到系统设置」直接表达了。
     *
     * <p>【为什么按钮必须留下】这是本页**唯一**通往系统设置的入口。
     * 没有 root 的用户全靠它手动开服务 —— 把按钮跟着说明一起删掉，
     * 就等于把这部分人锁在外面：他们看得见列表，却一个也开不了。
     * 说明是可有可无的，出口不是。
     */
    private void buildNoteCard() {
        LinearLayout card = card();

        TextView sysBtn = new TextView(this);
        sysBtn.setText("打开系统无障碍设置");
        sysBtn.setTextSize(13);
        sysBtn.setTextColor(C_BRAND);
        sysBtn.setTypeface(null, Typeface.BOLD);
        sysBtn.setGravity(Gravity.CENTER);
        sysBtn.setPadding(dp(this, 12), dp(this, 11), dp(this, 12), dp(this, 11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_BRAND_SOFT);
        bg.setCornerRadius(dp(this, 10));
        sysBtn.setBackground(bg);
        sysBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable e) {
                toast("打不开系统无障碍设置");
            }
        });
        card.addView(sysBtn);

        content.addView(card, cardLp());
    }

    // ============================================================
    //  工具
    // ============================================================

    /** 名称或包名命中当前搜索词。 */
    private boolean match(A11yManager.AppGroup g) {
        if (query.isEmpty()) {
            return true;
        }
        if (g.appLabel.toLowerCase().contains(query)
                || g.pkg.toLowerCase().contains(query)) {
            return true;
        }
        // 服务名也参与匹配：有人记得的是「Auto.js」而不是它的包名。
        for (A11yManager.ServiceInfo s : g.services) {
            if (s.label.toLowerCase().contains(query)) {
                return true;
            }
        }
        return false;
    }

    /** 批量启用/禁用列表里所有开关（执行 root 命令期间防止并发点击）。 */
    private void setEnabledAllSwitches(boolean on) {
        if (listBox == null) {
            return;
        }
        for (int i = 0; i < listBox.getChildCount(); i++) {
            View child = listBox.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View v = row.getChildAt(j);
                    if (v instanceof Switch) {
                        v.setEnabled(on);
                    }
                }
            }
        }
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_DIVIDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
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

    private LinearLayout.LayoutParams topMargin(int dpValue) {
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(this, dpValue);
        return lp;
    }

                private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 供 MainActivity 复用。 */
    public static void open(Context ctx) {
        Intent i = new Intent(ctx, A11yActivity.class);
        if (!(ctx instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }
}