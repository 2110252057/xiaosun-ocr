package com.xiaosun.ocr;

import android.app.Activity;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.List;

/**
 * 日志的入口 —— 顶栏右上角「⋮」。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么用户会需要它】
 * ════════════════════════════════════════════════════════════════
 * 有些故障只在别人的机型上出现（比如「点滑动菜单闪退」），开发者手里
 * 复现不出来。这时候唯一的路是拿到对方设备上的现场记录 —— 但要求
 * 普通用户去开 USB 调试、装 adb 抓 logcat 是不现实的。
 *
 * 所以这里把「抓日志」这件事压缩成两步：点「⋮」→ 点「分享日志」。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么从卡片改成了顶栏菜单】
 * ════════════════════════════════════════════════════════════════
 * 原先是一整张日志卡片压在页面最底部：平时用不上，却要先滚过大半页
 * 才看得到；真出问题时它又离手指最远。改成顶栏一个「⋮」之后，
 * 位置固定、不用滚动，页面本身也短了一截。
 *
 * 【为什么是 static 工具类】
 * 本工程没有 XML 布局也没有 ViewBinding，界面全是 new TextView(this)
 * 手搭的。「OCR 设置页」和「边缘滑动设置页」是两个互不继承的 Activity，
 * 没法共用成员方法，与其把菜单代码抄两遍（迟早改漏一处），不如都调
 * {@link #buildMenuButton(Activity)}。
 */
final class LogCard {

    private LogCard() {
    }

    /**
     * 顶栏右上角的「⋮」按钮 —— 日志、隐藏任务、保活这些杂项的统一入口。
     *
     * 原先它们是页面里的两三张卡片，压在最底部：平时用不上，却要先滚过
     * 大半页才看得到；出问题时又最难找。收进顶栏之后，一个位置就能找到，
     * 页面本身也短了一截。
     *
     * @param extras 额外菜单项（可为 null）。宿主可以把自己特有的
     *               开关塞进来，例如 OCR 页的「从最近任务隐藏」「后台保活」。
     *               本类不关心它们是什么，只负责把它们排在日志四项之前
     *               —— 宿主的常用开关在上面，日志这种排障功能垫底。
     */
    static View buildMenuButton(final Activity a, final MenuItem... extras) {
        TextView more = new TextView(a);
        more.setText("⋮");
        more.setTextSize(22);
        more.setTextColor(Color.WHITE);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("更多");
        more.setOnClickListener(v -> showMoreMenu(a, extras));
        return more;
    }

    /**
     * 三点菜单里的一项：标题 + 点击回调。
     *
     * 【为什么标题有两种形态】
     * 宿主菜单项要显示当前状态（「从最近任务隐藏：已开」）。如果标题在
     * 建顶栏时就固化成 String，用户点一下切换之后，再打开菜单看到的还是
     * 旧状态 —— 除非整页重建。所以留一个 {@link Title} 重载，每次弹菜单现算。
     * （没用 java.util.function.Supplier：本工程 minSdk 24，自己定义一个
     * 两行接口比赌脱糖配置更稳。）
     */
    static final class MenuItem {
        /** 标题来源：固定字符串，或每次弹菜单现算。 */
        interface Title {
            String get();
        }

        private final String fixed;
        private final Title dynamic;
        final Runnable action;

        MenuItem(String title, Runnable action) {
            this.fixed = title;
            this.dynamic = null;
            this.action = action;
        }

        MenuItem(Title title, Runnable action) {
            this.fixed = null;
            this.dynamic = title;
            this.action = action;
        }

        /** 每次弹菜单都调一次，保证显示的是当前状态。 */
        String title() {
            return dynamic != null ? dynamic.get() : fixed;
        }
    }

    /** 三点菜单本体：宿主塞进来的附加项在前，日志四项在后。 */
    private static void showMoreMenu(final Activity a, final MenuItem[] extras) {
        int extraCount = extras == null ? 0 : extras.length;

        final String[] ops = new String[extraCount + 4];
        for (int i = 0; i < extraCount; i++) {
            // 每次弹菜单现取标题：开关状态可能在两次打开之间变过
            ops[i] = extras[i].title();
        }
        ops[extraCount] = "查看日志";
        ops[extraCount + 1] = "分享日志";
        ops[extraCount + 2] = "导出到下载";
        ops[extraCount + 3] = "清空日志";

        new AlertDialog.Builder(a)
                .setTitle("更多")
                .setItems(ops, (d, which) -> {
                    if (which < extraCount) {
                        extras[which].action.run();
                        return;
                    }
                    switch (which - extraCount) {
                        case 0:
                            // 传入 null：菜单模式下没有卡片里的那行「当前记录」要刷新
                            showLog(a, null);
                            break;
                        case 1: {
                            // 带上设备信息一起发 —— 排障第一问永远是「什么机型、什么系统」
                            String extra = "小孙OCR 日志\n\n" + AppLog.deviceInfo(a);
                            if (AppLog.share(a, extra)) {
                                toast(a, "选择要发送到的应用");
                            } else {
                                toast(a, "还没有日志可发，先复现一次问题");
                            }
                            break;
                        }
                        case 2: {
                            File f = AppLog.exportToDownloads(a);
                            if (f != null) {
                                toast(a, "已导出：Download/" + f.getName());
                            } else {
                                toast(a, "导出失败（没有日志或存储不可用）");
                            }
                            break;
                        }
                        default:
                            AppLog.clear(a);
                            toast(a, "日志已清空");
                            break;
                    }
                })
                .show();
    }

    /**
     * 弹窗看最近若干条。
     *
     * 为什么不读文件：内存里那 300 条正好是「最近发生了什么」，
     * 而且不用处理大文件的读取与编码问题。要看全量就用「分享/导出」。
     *
     * @param info 卡片里那行「当前记录：…」；从三点菜单进来时传 null。
     */
    private static void showLog(Activity a, TextView info) {
        try {
            List<String> lines = AppLog.recent();
            if (info != null) info.setText("当前记录：" + AppLog.stat(a));
            if (lines.isEmpty()) {
                toast(a, "还没有日志");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            TextView tv = new TextView(a);
            tv.setTextSize(10f);
            tv.setTextColor(0xFF333333);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);   // 允许长按复制片段，方便只发关键几行
            tv.setText(sb.toString());
            tv.setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 12));

            ScrollView sv = new ScrollView(a);
            sv.addView(tv);

            new AlertDialog.Builder(a)
                    .setTitle("最近日志（" + lines.size() + " 条）")
                    .setView(sv)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            toast(a, "打开日志失败：" + t);
        }
    }

    // ============================================================
    //  小控件
    // ============================================================

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    private static void toast(Activity a, String msg) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }
}