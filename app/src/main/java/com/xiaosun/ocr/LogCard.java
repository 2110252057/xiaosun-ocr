package com.xiaosun.ocr;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.List;

/**
 * 「日志」卡片 —— 设置页里给用户的操作入口。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么用户会需要它】
 * ════════════════════════════════════════════════════════════════
 * 有些故障只在别人的机型上出现（比如「点滑动菜单闪退」），开发者手里
 * 复现不出来。这时候唯一的路是拿到对方设备上的现场记录 —— 但要求
 * 普通用户去开 USB 调试、装 adb 抓 logcat 是不现实的。
 *
 * 所以这一页要把「抓日志」这件事压缩成**一次点击**：
 *   分享日志  → 直接拉起微信/QQ/邮件，选人发出去
 *   导出到下载 → 落到公共下载目录（微信聊天里选「文件」也能拿到）
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么这个类是 static 的一坨 UI 代码】
 * ════════════════════════════════════════════════════════════════
 * 本工程没有任何 XML 布局、也没有 ViewBinding，界面全是
 * new TextView(this) 手搭出来的。而「边缘滑动设置页」和「OCR 设置页」
 * 是两个互不继承的 Activity，没法共用成员方法。
 *
 * 与其把同一段卡片代码抄两遍（迟早会改漏一处），不如抽成这个
 * 静态工具类 —— 两边都调 {@link #build(Activity)} 即可。
 */
final class LogCard {

    // 配色与本应用其它卡片保持一致
    private static final int C_TEXT = 0xFF1A1D26;
    private static final int C_SUB = 0xFF6B7280;
    private static final int C_HINT = 0xFF9CA3AF;
    private static final int C_BRAND = 0xFF4F6BED;
    private static final int C_DIVIDER = 0xFFEAECF2;
    private static final int C_SURFACE = 0xFFFFFFFF;

    private LogCard() {
    }

    /** 造一张「日志」卡片。调用方把它加进自己的页面即可。 */
    static View build(final Activity a) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(C_SURFACE, C_DIVIDER, 14, a));
        card.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        card.setElevation(dp(a, 1));

        TextView title = new TextView(a);
        title.setText("日志");
        title.setTextSize(14.5f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(C_TEXT);
        card.addView(title);

        final TextView info = new TextView(a);
        info.setTextSize(11.5f);
        info.setTextColor(C_HINT);
        info.setPadding(0, dp(a, 4), 0, 0);
        info.setText("当前记录：" + AppLog.stat(a));
        card.addView(info);

        TextView hint = new TextView(a);
        hint.setTextSize(11.5f);
        hint.setTextColor(C_SUB);
        hint.setLineSpacing(0f, 1.35f);
        hint.setText("遇到崩溃或功能异常时，点「分享日志」把文件发过来即可 —— "
                + "崩溃会自动记录，不需要你复现时做任何操作。");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(a, 8);
        card.addView(hint, hl);

        // ---------- 第一行：分享 / 导出 ----------
        LinearLayout row1 = new LinearLayout(a);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1lp.topMargin = dp(a, 12);
        row1.setLayoutParams(r1lp);

        Button share = primary(a, "分享日志");
        share.setOnClickListener(v -> {
            // 带上设备信息一起发 —— 排障第一问永远是「什么机型、什么系统」
            String extra = "小孙OCR 日志\n\n" + AppLog.deviceInfo(a);
            if (AppLog.share(a, extra)) {
                toast(a, "选择要发送到的应用");
            } else {
                toast(a, "还没有日志可发，先复现一次问题");
            }
        });
        row1.addView(share, weight());
        row1.addView(secondary(a, "导出到下载", v -> {
            File f = AppLog.exportToDownloads(a);
            if (f != null) {
                toast(a, "已导出：Download/" + f.getName());
            } else {
                toast(a, "导出失败（没有日志或存储不可用）");
            }
        }), weight());
        card.addView(row1);

        // ---------- 第二行：查看 / 清空 ----------
        LinearLayout row2 = new LinearLayout(a);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.topMargin = dp(a, 4);
        row2.setLayoutParams(r2lp);

        row2.addView(secondary(a, "查看日志", v -> showLog(a, info)), weight());
        row2.addView(secondary(a, "清空日志", v -> {
            AppLog.clear(a);
            info.setText("当前记录：" + AppLog.stat(a));
            toast(a, "日志已清空");
        }), weight());
        card.addView(row2);

        return card;
    }

    /**
     * 弹窗看最近若干条。
     *
     * 为什么不读文件：内存里那 300 条正好是「最近发生了什么」，
     * 而且不用处理大文件的读取与编码问题。要看全量就用「分享/导出」。
     */
    private static void showLog(Activity a, TextView info) {
        try {
            List<String> lines = AppLog.recent();
            info.setText("当前记录：" + AppLog.stat(a));
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

    private static Button primary(Activity a, String text) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(C_BRAND, 0, 10, a));
        return b;
    }

    private static Button secondary(Activity a, String text, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(C_TEXT);
        b.setBackground(rounded(0xFFF1F2F6, C_DIVIDER, 10, a));
        b.setOnClickListener(l);
        return b;
    }

    private static GradientDrawable rounded(int fill, int stroke, float radiusDp, Activity a) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(a, radiusDp));
        if (stroke != 0) g.setStroke(dp(a, 1), stroke);
        return g;
    }

    private static LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(0);
        return lp;
    }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    private static void toast(Activity a, String msg) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }
}