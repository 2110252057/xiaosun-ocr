package com.xiaosun.ocr;

import android.content.Context;

/**
 * 「识别区域」：屏幕上一块固定位置，之后每次区域识别都只取这一块。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么用 0~1 的比例，而不是像素】
 * ════════════════════════════════════════════════════════════════
 * 用户框选时看到的是「屏幕的某个位置」，他要的是「这个位置一直有效」。
 * 位置一旦存成像素，换分辨率（旋转屏幕、换机、改显示大小）就全部作废 ——
 * 而这块区域的本意正是「配一次，长期用」。
 *
 * 存比例则天然与分辨率无关：左上角 10%、右下角 80% 在任何屏幕上都是
 * 同一个视觉位置。服务端拿到比例后按当次截屏的真实尺寸换算成像素。
 *
 * 【为什么不存屏幕宽高做校验】
 * 试过：存了宽高就得在每次读取时比对，不一致就丢弃区域。
 * 但「屏幕变了」并不意味着「用户想框的位置变了」—— 旋转成横屏后
 * 上半屏还是上半屏。丢掉区域反而逼用户重画一遍，与「一直这个区域」矛盾。
 * 所以只存比例，不做这类校验。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么单独一个类】
 * ════════════════════════════════════════════════════════════════
 * 读写方有两处：{@link RegionSelectActivity}（选完保存）、
 * {@link OcrActivity}（取出后发请求 + 显示当前区域）。
 * 若各写一遍 prefs 键名与格式化，键名拼错就会变成「存了读不到」，
 * 而且这种错不会编译报错，只表现为「明明设过区域，却按整屏识别」。
 */
final class OcrRegion {

    /** 与应用其它设置同一个 prefs 文件，避免多份设置散落。 */
    private static final String PREFS = "xsocr_prefs";
    /** 键名唯一来源。 */
    private static final String KEY = "ocr_region";

    /** 归一化边界，均在 0~1。 */
    final float left;
    final float top;
    final float right;
    final float bottom;

    private OcrRegion(float l, float t, float r, float b) {
        this.left = l;
        this.top = t;
        this.right = r;
        this.bottom = b;
    }

    /**
     * 读取已保存的区域。
     *
     * @return 未设置、或存的内容解析不出来时返回 null（按「整屏识别」处理）
     */
    static OcrRegion load(Context c) {
        try {
            String raw = c.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, null);
            if (raw == null) {
                return null;
            }
            String[] parts = raw.split(",");
            if (parts.length != 4) {
                return null;
            }
            float[] v = new float[4];
            for (int i = 0; i < 4; i++) {
                v[i] = Float.parseFloat(parts[i].trim());
            }
            // 存进来的值一定是合法的，但 prefs 可能被外部工具改坏、
            // 或来自旧版本的别的格式。与其让一个反序的矩形一路传到服务端
            // 被当成「区域太小」拒掉，不如这里就当作没设置。
            if (v[2] <= v[0] || v[3] <= v[1]) {
                return null;
            }
            for (int i = 0; i < 4; i++) {
                if (v[i] < 0f || v[i] > 1f) {
                    return null;
                }
            }
            return new OcrRegion(v[0], v[1], v[2], v[3]);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 保存区域并**持久化到下次启动**。
     *
     * @param r {left, top, right, bottom}，0~1；为 null 或非法时清掉已存区域
     */
    static void save(Context c, float[] r) {
        if (r == null || r.length != 4) {
            clear(c);
            return;
        }
        float l = clamp(r[0]);
        float t = clamp(r[1]);
        float rr = clamp(r[2]);
        float b = clamp(r[3]);
        if (rr <= l || b <= t) {
            clear(c);
            return;
        }
        c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, l + "," + t + "," + rr + "," + b)
                .apply();
    }

    /** 忘掉这块区域，回到整屏识别。 */
    static void clear(Context c) {
        c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply();
    }

    private static float clamp(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /** 转成框选层要的数组形式。 */
    float[] toArray() {
        return new float[]{left, top, right, bottom};
    }

    /**
     * 请求参数形式：{@code "0.1,0.2,0.8,0.9"}。
     *
     * 用完整精度的 float 拼接，不做四舍五入 —— 少一位小数在 4K 屏上
     * 就是几个像素的偏移，而这几像素会体现在裁剪边界上。
     */
    String request() {
        return left + "," + top + "," + right + "," + bottom;
    }

    /**
     * 给用户看的百分比描述，如 {@code "10,20,80,90%"}。
     *
     * 不用「上半屏」「左三分之一」这类说法：它们只在区域恰好对齐时才准确，
     * 用户随手拖出来的框往往是 37%~62% 这种，硬套成「中部」反而误导。
     * 直接给数字，和接口参数是同一套顺序（左、上、右、下），看过一次就懂。
     */
    String percentText() {
        return Math.round(left * 100) + "," + Math.round(top * 100) + ","
                + Math.round(right * 100) + "," + Math.round(bottom * 100) + "%";
    }

    /** 覆盖面积占比（0~1），用于判断「这是不是等于整屏」。 */
    float area() {
        return (right - left) * (bottom - top);
    }
}