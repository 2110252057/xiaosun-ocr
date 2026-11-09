package com.xiaosun.ocr;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;

import static com.xiaosun.ocr.Palette.*;
import static com.xiaosun.ocr.Ui.*;

/**
 * 全屏框选：把整张截屏铺满屏幕，用户直接在屏幕上拖出一块。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么是独立的全屏页面，而不是页面里的预览框】
 * ════════════════════════════════════════════════════════════════
 * 要框准，图就必须大。原来框选层叠在「文字识别」页那张 220dp 高的预览框里，
 * 一张 1080×2400 的截屏塞进去只剩一条窄带 —— 手指一按就盖掉半屏，
 * 用户只能靠猜。就算临时把框拉高到 420dp，它仍是「页面里的一块」，
 * 上下还压着别的卡片，永远拿不到满屏的面积。
 *
 * 所以改成：进来就是一张占满屏幕的截图，没有任何其它内容。
 * 屏幕多大，可框选的面积就多大。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么用 dark 主题 + 手动读真实像素】
 * ════════════════════════════════════════════════════════════════
 * 这一页要"变成那张截图"。App 的主题是浅灰底，状态栏也是浅灰、
 * 带一层浅色遮罩 —— 那样铺出来的图不是屏幕上真正看到的画面。
 * 这里用 {@link #THEME_DARK}：状态栏/导航栏变黑且图标转白，
 * 顶栏与页面底色一并压黑，视觉上就是「截图盖住了整块屏幕」。
 *
 * ════════════════════════════════════════════════════════════════
 * 【坐标怎么传回去】
 * ════════════════════════════════════════════════════════════════
 * 回传的是 0~1 的归一化比例（见 {@link OcrRegion}），不是像素。
 * 调用方（{@link OcrActivity}）存进 prefs，之后每次区域识别都用它。
 * 比例与分辨率无关，所以旋转屏幕、换机之后仍然是同一个视觉位置。
 */
public class RegionSelectActivity extends Activity {

    /**
     * 深色主题：让这一页与状态栏一起变黑，截图铺上去才像"盖住整块屏幕"。
     * 定义在 {@code themes.xml} 里，见 {@code RegionSelectTheme}。
     */
    public static final String THEME_DARK = "RegionSelectTheme";

    /** 从哪条路径读截图（{@code OCRService.lastCapturePath} 的值）。 */
    public static final String EXTRA_SHOT_PATH = "shot_path";
    /** 进来时要不要先把上次的框摆出来。 */
    public static final String EXTRA_SHOW_CURRENT = "show_current";

    /**
     * 确认后是否**由本页自己**把结果写进 {@link OcrRegion}。
     *
     * <p>【为什么需要这个开关】浮球框选这条路是在「用户切到别的 App 之后」
     * 才把本页拉起来的。拉起本页的配置界面那时候已经在后台，
     * 内存紧张时会被系统回收 —— 那样 {@code onActivityResult} 永远不会回来，
     * 用户辛苦框的那一块就白画了。
     * 所以这条路上改成「谁框谁存」：本页直接落盘，结果只用于回显提示。
     * 其它入口（App 的「设区域…」）仍由调用方自己存，行为不变。
     */
    public static final String EXTRA_SAVE_SELF = "save_self";

    /** 结果：0~1 的比例，四个 float。没选就 RESULT_CANCELED。 */
    public static final String EXTRA_LEFT = "left";
    public static final String EXTRA_TOP = "top";
    public static final String EXTRA_RIGHT = "right";
    public static final String EXTRA_BOTTOM = "bottom";

    private static final String TAG = "Grace-RegionSelect";

    private RegionPickerView picker;
    private ImageView imageView;
    private Button btnOk;
    private Button btnClear;
    private TextView tvCoords;
    /** 底部那行操作提示：文案随「有没有框」变，所以要留个引用。 */
    private TextView hintText;

    /** 还没有框时该怎么说：此时只能拖出一块。 */
    private static final String HINT_NO_SELECTION = "在截图上拖动，框出要识别的区域";
    /**
     * 已经有框时该怎么说：三条路都点明，并强调想重画要先清空。
     *
     * <p>不写「再拖一次可重画」是刻意的 —— 现在框外拖动不响应了，
     * 提示与行为必须一致，否则用户会以为功能坏了。
     */
    private static final String HINT_HAS_SELECTION =
            "拖四个角调整边界 · 拖框内整体移动 · 想重画请先点「清空」";
    /** 上次存下来的区域，用于进场时先画出来。 */
    private float[] initial;

    /**
     * 打开本页的统一入口。
     *
     * @param shotPath    要显示的截图路径（必需）
     * @param showCurrent 是否把已保存的区域先摆出来
     */
    static void open(Activity from, String shotPath, boolean showCurrent, int reqCode) {
        Intent i = new Intent(from, RegionSelectActivity.class);
        i.putExtra(EXTRA_SHOT_PATH, shotPath);
        i.putExtra(EXTRA_SHOW_CURRENT, showCurrent);
        from.startActivityForResult(i, reqCode);
    }

    /**
     * 打开本页，并让它自己负责保存结果（见 {@link #EXTRA_SAVE_SELF}）。
     *
     * <p>给「悬浮球框选」这条路用：调用方那时已经在后台，
     * 不保证能活到结果回来的那一刻，所以不能把落盘的责任压在它身上。
     *
     * <p>仍然用 {@code startActivityForResult}：调用方如果活着，
     * 就能收到结果做界面回显；如果已经被回收，本页也已经把数据存好了。
     */
    static void openSelfSaving(Activity from, String shotPath,
                               boolean showCurrent, int reqCode) {
        Intent i = new Intent(from, RegionSelectActivity.class);
        i.putExtra(EXTRA_SHOT_PATH, shotPath);
        i.putExtra(EXTRA_SHOW_CURRENT, showCurrent);
        i.putExtra(EXTRA_SAVE_SELF, true);
        from.startActivityForResult(i, reqCode);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 让内容铺到状态栏下面（真正的 edge-to-edge）。
        //
        // ★ 这里原先是 getWindow().addFlags(FLAG_LAYOUT_NO_LIMITS)，已换掉。
        // 那个 flag 的语义是「允许窗口**超出屏幕**」—— 于是 DecorView 被按
        // 「屏幕高 + 状态栏高 + 导航栏高」去布局：内容整体发生位移
        // （表现为一进来页面就滑到别的位置），底部操作条还可能被挤出可视区。
        // WindowCompat 这条路只把「内容可布局的范围」放大到状态栏/导航栏下面，
        // 窗口尺寸仍然是整屏，不会出现这种跳变。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SHOT_PATH);
        boolean showCurrent = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_SHOW_CURRENT, true);

        if (showCurrent) {
            OcrRegion saved = OcrRegion.load(this);
            if (saved != null) {
                initial = saved.toArray();
            }
        }

        if (path == null || path.length() == 0) {
            toast("没有可用的截图");
            finish();
            return;
        }

        setContentView(buildUi());

        // 图片解码放在 setContentView 之后：先用控件占住屏，再填图。
        // 一张 4K 图解码要几十毫秒，若先解码再 setContentView，
        // 用户会盯着一个全白（或全黑）屏等，看起来像卡死。
        Bitmap shot = decodeForScreen(path);
        if (shot == null) {
            toast("截图读取失败");
            finish();
            return;
        }
        picker.setImageSize(shot.getWidth(), shot.getHeight());
        imageView.setImageBitmap(shot);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // 截图铺满整屏。FIT_CENTER：不裁剪、不拉伸，长图两侧留黑
        // （截图比例与屏幕比例一致时正好铺满，绝大多数情况就是铺满的）。
        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(imageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        picker = new RegionPickerView(this);
        root.addView(picker, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------- 底部操作条 ----------
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 16));
        // 半透明黑底：压在截图上也读得清，且不遮住框的下边缘。
        bar.setBackgroundColor(0xCC000000);

        // 除了图与框选层，还要一行提示，告诉用户这一页是干嘛的、怎么操作。
        // 全屏页没有别的文案位，所以把它压在底部操作条里。
        // 提成字段是因为文案要随「有没有框」变：没框时教他拖出一块，
        // 有框之后要告诉他拖角能调边、想重画得先点「清空」。
        TextView hint = new TextView(this);
        hint.setText(HINT_NO_SELECTION);
        hint.setTextSize(13);
        hint.setTextColor(0xFFFFFFFF);
        hint.setGravity(Gravity.CENTER);
        bar.addView(hint);
        this.hintText = hint;

        tvCoords = new TextView(this);
        tvCoords.setTextSize(11.5f);
        tvCoords.setTextColor(0xFFB8BECC);
        tvCoords.setGravity(Gravity.CENTER);
        tvCoords.setPadding(0, dp(this, 4), 0, 0);
        bar.addView(tvCoords);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(this, 10);

        btnClear = darkButton("清空", 0xFF3A3F4B);
        btnClear.setVisibility(View.GONE);
        btnClear.setOnClickListener(v -> {
            picker.clearSelection();
            tvCoords.setText("");
        });
        row.addView(btnClear, weight());

        Button btnCancel = darkButton("取消", 0xFF3A3F4B);
        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        row.addView(btnCancel, weight());

        btnOk = darkButton("用这块区域", C_BRAND);
        btnOk.setOnClickListener(v -> confirm());
        row.addView(btnOk, weight());

        bar.addView(row, rowLp);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        root.addView(bar, barLp);

        // 底部操作条要躲开导航栏 / 手势条。
        // 上面 setDecorFitsSystemWindows(false) 之后，内容会一直铺到屏幕最底下，
        // 不加这段的话「用这块区域」会被手势条压住一半 —— 想点确认却先触发返回手势。
        // 用 insets 回调而不是写死一个 dp：各机型的导航栏/手势条高度都不一样。
        // 这里显式写出 View 与 WindowInsets 的类型：不依赖 lambda 的类型推断，
        // 也就不会因为 WindowCompat 版本差异而在编译期出岔子。
        final int basePadLeft = bar.getPaddingLeft();
        final int basePadTop = bar.getPaddingTop();
        final int basePadRight = bar.getPaddingRight();
        final int basePadBottom = bar.getPaddingBottom();
        bar.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                int bottom;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.graphics.Insets in = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                                    | WindowInsetsCompat.Type.displayCutout());
                    top = in.top;
                    bottom = in.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                // 顶部让开状态栏/刘海：图可以铺到状态栏下，文案不该被挡住。
                // 底部让开导航栏/手势条，否则「用这块区域」会被压住一半，
                // 想点确认却先触发返回手势。
                v.setPadding(basePadLeft, basePadTop + top, basePadRight, basePadBottom + bottom);
                return insets;
            }
        });

        // 选区变化时同步底部那行坐标，让用户看到「框到哪了」的确切数值。
        picker.setOnSelectionChanged(has -> {
            if (btnOk != null) {
                btnOk.setEnabled(has);
            }
            if (btnClear != null) {
                btnClear.setVisibility(has ? View.VISIBLE : View.GONE);
            }
            // 提示语跟着变：有框之后要告诉用户"能拖角调，想重画先清空"，
            // 否则他会照着旧文案继续拖，却发现没反应。
            if (hintText != null) {
                hintText.setText(has ? HINT_HAS_SELECTION : HINT_NO_SELECTION);
            }
            updateCoords();
        });

        // 已有区域先进场摆出来 —— 但它要等布局完成、图片尺寸到位后才画得准，
        // 所以用 post 推到下一帧。
        final float[] seed = initial;
        if (seed != null) {
            picker.post(() -> {
                picker.setRegionNormalized(seed);
                updateCoords();
            });
        } else {
            btnOk.setEnabled(false);
        }
        return root;
    }

    private void updateCoords() {
        if (tvCoords == null || picker == null) {
            return;
        }
        float[] r = picker.getRegionNormalized();
        if (r == null) {
            tvCoords.setText("");
            return;
        }
        tvCoords.setText("左 " + pct(r[0]) + " · 上 " + pct(r[1])
                + " · 右 " + pct(r[2]) + " · 下 " + pct(r[3])
                + "　（占屏幕 " + Math.round((r[2] - r[0]) * (r[3] - r[1]) * 100) + "%）");
    }

    private static String pct(float v) {
        return Math.round(v * 100) + "%";
    }

    private void confirm() {
        float[] r = picker == null ? null : picker.getRegionNormalized();
        if (r == null) {
            toast("请先拖出一个区域");
            return;
        }
        // saveSelf=true 时（悬浮球那条路）由本页直接落盘。
        // 调用方那时可能已被系统回收，把"存"寄托在它的 onActivityResult 上不可靠。
        boolean saveSelf = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_SAVE_SELF, false);
        if (saveSelf) {
            OcrRegion.save(this, r);
        }
        Intent out = new Intent();
        out.putExtra(EXTRA_LEFT, r[0]);
        out.putExtra(EXTRA_TOP, r[1]);
        out.putExtra(EXTRA_RIGHT, r[2]);
        out.putExtra(EXTRA_BOTTOM, r[3]);
        out.putExtra(EXTRA_SAVE_SELF, saveSelf);
        setResult(RESULT_OK, out);
        finish();
    }

    /**
     * 按屏幕尺寸解码截图。
     *
     * <p>【为什么不用原图】一张 1080×2400 的 ARGB_8888 全屏图是 10 MB，
     * 4K 屏上翻三倍。而这一页最多也就显示一整屏，解码到比屏幕略大即可 ——
     * 用 inSampleSize 降采样能把内存压到原来的 1/4 甚至更少，
     * 长按拖动时的绘制也不会因为位图过大而掉帧。
     *
     * <p>降采样只影响"显示用"的这张图。框选结果是 0~1 的比例，
     * 服务端会按截屏**原图**尺寸换算像素，因此降采样不会引入坐标误差。
     */
    private Bitmap decodeForScreen(String path) {
        try {
            if (!new File(path).exists()) {
                return null;
            }
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxSide = Math.max(dm.widthPixels, dm.heightPixels);

            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            int longer = Math.max(probe.outWidth, probe.outHeight);
            while (longer / sample > maxSide) {
                sample *= 2;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(path, opt);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "解码截图失败: " + t.getMessage());
            return null;
        }
    }

    /** 深色底上的按钮。浅色主题里那套白底蓝字在这里看不见。 */
    private Button darkButton(String text, int bg) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(13.5f);
        btn.setAllCaps(false);
        btn.setTextColor(0xFFFFFFFF);
        btn.setStateListAnimator(null);
        btn.setBackground(solidBg(this, bg, 12));
        return btn;
    }

    private static LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = 4;
        lp.rightMargin = 4;
        return lp;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * 让调用方直接打开本页的便捷方法（不经过 startActivityForResult 的写法）。
     *
     * <p>目前只有一个调用方（{@link OcrActivity}），这里保留静态入口只是为了
     * 让"打开全屏框选"这件事在调用方只有一行，不必关心 Intent 的键名。
     */
    static Intent intent(Context c, String shotPath, boolean showCurrent) {
        Intent i = new Intent(c, RegionSelectActivity.class);
        i.putExtra(EXTRA_SHOT_PATH, shotPath);
        i.putExtra(EXTRA_SHOW_CURRENT, showCurrent);
        return i;
    }

    /** 供静态入口以外的场景（例如将来从插件直接拉起）使用。 */
    static void openWith(Context c, String shotPath, boolean showCurrent) {
        Intent i = intent(c, shotPath, showCurrent);
        if (!(c instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        c.startActivity(i);
    }
}