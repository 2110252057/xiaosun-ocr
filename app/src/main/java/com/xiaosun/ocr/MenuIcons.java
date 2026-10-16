package com.xiaosun.ocr;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

/**
 * 菜单图标加载 —— 设置页与悬浮窗共用同一份实现。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么单独抽一个类】
 * ════════════════════════════════════════════════════════════════
 * 这段逻辑本来只在 {@link EdgeGestureService} 里（画菜单用）。
 * 但菜单配置现在搬到了本应用自己的设置界面，编辑时也要画预览图标 ——
 * 若两边各写一份，早晚会走偏：设置页显示了图标、悬浮窗却不显示，
 * 用户完全无从判断是自己填错了还是程序的问题。
 *
 * 所以 source 的三种写法在这里定义一次，两边共用。
 *
 * ════════════════════════════════════════════════════════════════
 * 【source 的三种写法】
 * ════════════════════════════════════════════════════════════════
 *   app:com.tencent.mm     取该应用的图标（没装的返回 null）
 *   file:/sdcard/x.png     读图片文件
 *   ic_launcher            取本应用资源（先 drawable 再 mipmap）
 *
 * 传空串或 null 一律返回 null，调用方按「没有图标」处理
 * （悬浮窗会用文字前两个字顶着，设置页画的是一个占位方块）。
 */
final class MenuIcons {

    private static final String TAG = "XsOcrMenuIcon";

    private MenuIcons() {
    }

    /**
     * 按 source 加载图标。
     *
     * <p>绝不抛异常：加载失败只是没图标，不该把菜单拖垮。
     */
    static Drawable load(Context c, String source) {
        if (c == null || source == null || source.length() == 0) return null;
        try {
            String s = source.trim();
            if (s.length() == 0) return null;

            if (s.startsWith("app:")) {
                String pkg = s.substring(4).trim();
                if (pkg.length() == 0) return null;
                return c.getPackageManager().getApplicationIcon(pkg);
            }

            if (s.startsWith("file:")) {
                String path = s.substring(5).trim();
                if (path.length() == 0) return null;
                Bitmap bmp = BitmapFactory.decodeFile(path);
                if (bmp == null) return null;
                return new BitmapDrawable(c.getResources(), bmp);
            }

            // 资源名
            int resId = c.getResources().getIdentifier(s, "drawable", c.getPackageName());
            if (resId == 0) {
                resId = c.getResources().getIdentifier(s, "mipmap", c.getPackageName());
            }
            if (resId != 0) return c.getResources().getDrawable(resId);

            // 只剩一种可能：用户把包名直接填进来了（漏了 app: 前缀）。
            // 这种情况很常见，顺手认一下，比让他对着空白格子发愣强。
            if (s.indexOf('.') > 0) {
                try {
                    return c.getPackageManager().getApplicationIcon(s);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "图标加载失败(" + source + "): " + t);
        }
        return null;
    }

    /**
     * 给 source 一个人类可读的说明，用在编辑页的提示里。
     */
    static String describeSource(String source) {
        if (source == null || source.trim().length() == 0) return "无图标（显示文字前两个字）";
        String s = source.trim();
        if (s.startsWith("app:")) return "应用图标：" + s.substring(4).trim();
        if (s.startsWith("file:")) return "图片文件：" + s.substring(5).trim();
        return "内置资源：" + s;
    }
}