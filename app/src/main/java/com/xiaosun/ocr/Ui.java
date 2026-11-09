package com.xiaosun.ocr;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * 全应用共用的界面构造小工具。
 *
 * <h3>为什么合并</h3>
 * 和 {@link Palette} 同一个原因：这些方法原先在 7~11 个 Activity 里各写一遍，
 * 而「各写一遍」已经导致了两处真实的不一致：
 * <ul>
 *   <li>{@code borderedBg} 的描边宽度：{@code EdgeGestureActivity} 写 {@code dp(1)}，
 *       其余写 {@code dp(1f)} —— 前者靠 float 常量的隐式截断才没出错；</li>
 *   <li>{@code wide()} 有 7 份完全相同的实现，{@code MainActivity} 和
 *       {@code OcrActivity} 干脆不用它、直接 new 一遍。</li>
 * </ul>
 *
 * <h3>怎么用</h3>
 * 各页 {@code import static com.xiaosun.ocr.Ui.*;}，之后照旧写
 * {@code dp(12)}、{@code borderedBg(...)}。唯一需要注意的是这几个方法现在
 * <b>都要多传一个 Context</b>（第一个参数），因为工具类拿不到 Activity 的实例。
 *
 * <p>这里刻意<b>不</b>收 {@code cardLp()}：它虽然同名，但各页的间距与方向都不同
 * （{@code A11yActivity} 是 {@code bottomMargin=12}、{@code DeviceOverviewActivity}
 * 是 {@code topMargin=10}、{@code OcrActivity} 是 {@code topMargin=12}），
 * 语义上根本不是同一个东西，硬合并只会把差异藏起来。
 */
final class Ui {

    private Ui() {
    }

    /** dp → px。 */
    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** 圆角实底背景。 */
    static GradientDrawable solidBg(Context c, int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /**
     * 圆角 + 1dp 描边的背景。卡片基本都用它。
     *
     * <p>描边宽度固定 1dp。原先有一处写 {@code dp(1)}、其余写 {@code dp(1f)}，
     * 在 dp 密度为整数的机器上结果相同，但那是巧合，不是设计。
     */
    static GradientDrawable borderedBg(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(dp(c, 1f), stroke);
        return d;
    }

    /** 占满宽度、高度自适应。 */
    static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 占满宽度、高度自适应，并带上边距。 */
    static LinearLayout.LayoutParams wideTop(Context c, int topDp) {
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(c, topDp);
        return lp;
    }
}