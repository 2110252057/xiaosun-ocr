package com.xiaosun.ocr;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import static com.xiaosun.ocr.Palette.*;
import static com.xiaosun.ocr.Ui.*;

/**
 * 首页 —— 功能入口列表。
 *
 * 页面结构：顶部标题栏 + 单列功能卡片。
 * 每张卡只有「图标 + 功能名 + ›」，没有状态小字、没有介绍文字。
 * 点进去是独立的二级页，返回回到这里。
 */
public class MainActivity extends AppCompatActivity {

    private static final String APP_VERSION = "v" + BuildConfig.VERSION_NAME;


    /** 首页内容容器。 */
    private LinearLayout content;

    /*
     * 【整块轮询已删除】原先这里有 featureSubs[]（每张卡的状态文字控件）
     * 和一个 1.5 秒的 Handler 轮询，用来在用户停留在首页时把「模型加载中 →
     * 服务就绪」这类跳变刷出来。
     *
     * 卡片上不再显示状态文字之后，屏幕里没有任何东西会变 —— 轮询纯属白跑
     * （首页是常开页面，一直挂着就是一直醒）。所以 ticker / tick / refreshAll
     * / featureSubs 以及四个 xxxState() 取值器全部删除。
     */

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 日志最先装：后面任何一步崩了都能留下堆栈。
        AppLog.install(this);
        // 把「从最近任务隐藏」对齐到设置值（就地生效，不重建界面）。
        OCRService.applyRecents(this);

        requestPermissionsIfNeeded();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        root.addView(buildHeader());

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(this, 16), dp(this, 4), dp(this, 16), dp(this, 32));
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        applyStatusBar();

        buildFeatureGrid();

        // OCR 服务按总开关自动拉起（关掉的人不该被强行载入模型）。
        ensureOcrService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 「从最近任务隐藏」可能被别的入口改过，重设一遍最稳（值相同是空操作）。
        OCRService.applyRecents(this);
        ensureOcrService();
        // 手势服务被系统重启过之后就再没人拉过它，这里按总开关幂等地同步一次。
        EdgeGestureService.sync(this);
    }

    // ============================================================
    //  顶部栏
    // ============================================================

    /**
     * 首页的状态栏与顶栏保持同一个底色 C_BG。
     *
     * <p>【2.6 起这里只剩一件事：关掉对比度遮罩】
     * 状态栏颜色与图标明暗已统一写在主题（{@code @color/bg} +
     * {@code windowLightStatusBar=true}），所有页面共用一套，不必每页各设一遍。
     *
     * <p>但主题管不到 {@code setStatusBarContrastEnforced} —— Android 10+
     * 会给浅色状态栏自动压一层灰色蒙版，让状态栏比下面的顶栏暗一档，
     * 又出现一道「缝」。这个只能在运行时关。
     */
    private void applyStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
        }
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        /*
         * 顶栏不再是品牌蓝，直接用页面底色 C_BG —— 与下面内容区同色。
         *
         * 原先这里是 C_BRAND → C_BRAND_DARK 的竖向渐变，靠一块深色块把标题压住。
         * 现在整页只剩「标题 + 四行功能」，蓝色块反而把第一张卡往下推、
         * 又和浅灰内容分成两截；同样的底色让标题看起来是「印在页面上」的，
         * 而不是另一块贴上去的条。
         *
         * 【连带改动】底色一变浅，原先为蓝底准备的白色文字全部改用深色：
         * 标题 C_TEXT、副标题 C_SUB、Logo 反过来改成蓝底白字、
         * 「⋮」也换成深灰（见下面 LogCard.buildMenuButton 的颜色参数）。
         * 状态栏颜色在 applyStatusBar() 里单独对齐，见那里的说明。
         */
        bar.setBackgroundColor(C_BG);
        bar.setPadding(dp(this, 20), dp(this, 20), dp(this, 16), dp(this, 14));

        // 圆形 Logo：一个字母足够，避免再塞一张位图资源。
        // 蓝底白字（原来在蓝顶栏上是白底蓝字，反过来了）。
        TextView logo = new TextView(this);
        logo.setText("G");
        logo.setTextSize(21);
        logo.setTypeface(null, Typeface.BOLD);
        logo.setTextColor(Color.WHITE);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setColor(C_BRAND);
        logoBg.setShape(GradientDrawable.OVAL);
        logo.setBackground(logoBg);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(this, 46), dp(this, 46));
        logoLp.setMarginEnd(dp(this, 14));
        bar.addView(logo, logoLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Grace");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(C_TEXT);
        title.setLetterSpacing(0.02f);
        column.addView(title);

        TextView sub = new TextView(this);
        sub.setText("本地文字识别 · 手势自动化  " + APP_VERSION);
        sub.setTextSize(11.5f);
        sub.setTextColor(C_SUB);
        column.addView(sub);

        bar.addView(column, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右上角「⋮」：日志 + 两个「配一次就不动」的开关
        // 第三个参数是图标颜色：浅底上必须给深灰，默认的白色会完全看不见。
        View more = LogCard.buildMenuButton(this, C_SUB,
                new LogCard.MenuItem(this::recentsMenuTitle, this::toggleHideFromRecents),
                new LogCard.MenuItem(this::keepAliveMenuTitle, this::openBatteryWhitelist));
        bar.addView(more, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));

        return bar;
    }

// ============================================================
    //  功能卡片（一列，一行一个）
    // ============================================================

    private void buildFeatureGrid() {
        addSectionTitle(content, "功能");

        Feature[] list = features();
        /*
         * 一列排布：一个功能占满一整行。
         *
         * 原先是两列网格（每行两张半宽卡）。改成单列的原因：
         * 卡上的说明文字（「截屏或选图，本地识别中英文」这类）在半宽卡里
         * 只能排两行就被省略号截断，而状态文字（「服务就绪 · http://127.0.0.1:1666」）
         * 比半张卡还宽；整行宽度下这两行文字都能一次读全，不用点进去才知道。
         *
         * 卡片内部也随之改成横向：图标在左、文字在右（见 buildFeatureCard）。
         * 竖向堆叠在整行宽度下会拉得很高，一屏只装得下一张半，反而更难扫。
         */
        for (int i = 0; i < list.length; i++) {
            View card = buildFeatureCard(list[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 第一张卡已经与上面的「功能」标题留了间距，这里只隔开卡片之间。
            lp.topMargin = dp(this, 10);
            content.addView(card, lp);
        }
    }

    /**
     * 一张功能卡片：左侧图标 + 功能名 + 尾部箭头。
     *
     * <p>【卡片上只留图标和名字】原来还有第三行的灰色介绍、以及一行会变的
     * 状态文字（「服务就绪 · http://127.0.0.1:1666」「内存 62% · 电量 87%」）。
     * 两行小字都去掉了：名字已经说明这是什么，而状态是进去看一眼才知道准的信息 ——
     * 停在这一页盯着轮询等它变，本来也不是该做的事。
     *
     * <p>没有小字之后，卡片高度只由图标决定，四张天然等高，
     * 不再需要「固定两行 + 最小高度」那套对齐手段。
     */
    private View buildFeatureCard(final Feature f) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(borderedBg(this, C_SURFACE, C_DIVIDER, 16));
        card.setPadding(dp(this, 16), dp(this, 16), dp(this, 14), dp(this, 16));
        card.setElevation(dp(this, 1));
        card.setClickable(true);
        card.setFocusable(true);

        // 图标：浅色方块 + 一个字。比引入图标资源更省事，也不依赖矢量资源生成。
        TextView icon = new TextView(this);
        icon.setText(f.icon);
        icon.setTextSize(17);
        icon.setTypeface(null, Typeface.BOLD);
        icon.setTextColor(C_BRAND);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(C_BRAND_SOFT);
        iconBg.setCornerRadius(dp(this, 12));
        icon.setBackground(iconBg);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44));
        ilp.setMarginEnd(dp(this, 14));
        card.addView(icon, ilp);

        TextView name = new TextView(this);
        name.setText(f.name);
        name.setTextSize(15.5f);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(C_TEXT);
        // weight=1：名字吃掉图标与箭头之外的全部宽度。
        card.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 尾部「›」：明示这一行可以点进去。不画 V 形矢量图，一个字符足够。
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(C_HINT);
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(this, 24),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMarginStart(dp(this, 6));
        card.addView(arrow, alp);

        // 整张卡可点，水波纹覆盖全卡：命中区域比只点文字大得多。
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(this, 16));
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x224F6BED), card.getBackground(), mask);
        card.setBackground(ripple);
        card.setOnClickListener(v -> f.action.run());

        return card;
    }

    // ============================================================
    //  顶栏「⋮」里的两项设置
    // ============================================================

    private String recentsMenuTitle() {
        return "从最近任务隐藏：" + (OCRService.isHiddenFromRecents(this) ? "已开" : "关");
    }

    private String keepAliveMenuTitle() {
        return "后台保活：" + (isIgnoringBatteryOptimizations() ? "已在白名单" : "未加入");
    }

    private void toggleHideFromRecents() {
        boolean next = !OCRService.isHiddenFromRecents(this);
        OCRService.setHiddenFromRecents(this, next);
        OCRService.applyRecents(this);
        toast(next ? "已从最近任务隐藏" : "已恢复显示在最近任务");
    }

    private void openBatteryWhitelist() {
        if (isIgnoringBatteryOptimizations()) {
            toast("已在电池优化白名单里");
            return;
        }
        requestIgnoreBatteryOptimizations();
    }

    // ============================================================
    //  服务拉起 / 权限 / 保活
    // ============================================================

    /** 按总开关决定要不要拉起 OCR 服务（关着就什么都不做）。 */
    private void ensureOcrService() {
        if (!OCRService.isEnabled(this) || OCRService.isRunning) {
            return;
        }
        try {
            Intent intent = new Intent(this, OCRService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable ignored) {
            // 后台限制较严时可能抛 ForegroundServiceStartNotAllowedException。
            // 静默失败即可：用户点进「文字识别」页时会再拉一次并给出提示。
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("当前系统版本无需设置");
            return;
        }
        try {
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                toast("请找到 Grace 并设为「不限制」");
            } catch (Exception e2) {
                toast("请手动到 设置-电池 关闭本应用限制");
            }
        }
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {
                    // 个别 ROM 没有这个设置页，失败就停在当前页。
                }
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    // ============================================================
    //  通用 UI
    // ============================================================

    private void addSectionTitle(ViewGroup parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(C_SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 18);
        lp.bottomMargin = dp(this, 2);
        parent.addView(tv, lp);
    }
            private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ============================================================
    //  功能项定义
    // ============================================================

    /**
     * 首页的功能列表。
     *
     * 新增功能只需：写一个二级页 Activity + 提供 static open(Context)，
     * 然后在这里追加一项。首页布局与点击路由都不需要改。
     *
     * <p>2.6 起不再有「状态取值器」这一项 —— 卡片上不显示状态文字了。
     */
    private Feature[] features() {
        return new Feature[]{
                new Feature("文", "文字识别", () -> OcrActivity.open(this)),
                new Feature("手", "边缘手势", () -> EdgeGestureActivity.open(this)),
                // 第三个大功能：用 root 直接开关任意应用的无障碍服务。
                // 它和上面两项同性质 —— 一个独立的能力，不是谁的子设置。
                new Feature("障", "无障碍管理", () -> A11yActivity.open(this)),
                // 第四个大功能：CPU / 内存 / GPU / 温度 / 电池一屏看完。
                new Feature("览", "设备概览", () -> DeviceOverviewActivity.open(this)),
        };
    }

    /** 首页上的一个功能入口：图标里的字、名字、点进去做什么。 */
    private static final class Feature {
        final String icon;
        final String name;
        final Runnable action;

        Feature(String icon, String name, Runnable action) {
            this.icon = icon;
            this.name = name;
            this.action = action;
        }
    }
}