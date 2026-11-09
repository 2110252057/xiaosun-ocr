package com.xiaosun.ocr;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.xiaosun.ocr.Palette.*;
import static com.xiaosun.ocr.Ui.*;

/**
 * 设备概览 —— 首页的第四个大功能。
 *
 * <h3>这一页解决什么问题</h3>
 * 手机上「现在到底是谁在吃 CPU、内存还剩多少、多少度、GPU 忙不忙」这几件事，
 * 系统设置里散落在三四个页面，而且没有一个页面会把它们放在一起。
 * 这一页把它们压成一屏：内存 / GPU / CPU（含每核频率与负载）/ 电池 / 系统。
 *
 * <h3>为什么是「自绘 + 每秒一次」</h3>
 * <ul>
 *   <li>环形图和柱状图都是 {@link View#onDraw} 里画几根弧线和矩形，
 *       为这点图形引一个图表库并不划算，也不值得让 APK 再大几 MB。</li>
 *   <li>数值本身就是每秒变的，用 1 秒的采样间隔看起来才像「实时」，
 *       再快既没有意义（内核自己也是按节拍统计的），又更费电。</li>
 * </ul>
 *
 * <h3>为什么采样放在子线程</h3>
 * 一次采样要读几十个 sysfs 文件，选开「进程占用」时还要起一次 su。
 * 这些一旦放到主线程，每秒都会有一次可见的掉帧。所以：
 * 子线程 {@link DeviceStats#sample()} → 回主线程 {@link #render} → 再排下一次。
 *
 * <p>同一时刻只允许一个采样在跑（{@link #busy}），否则慢机型上
 * 采样耗时超过 1 秒时，任务会越堆越多，最后界面反而卡住。
 *
 * <h3>写不出来的字段怎么显示</h3>
 * 一律显示「—」，而不是 0。0 MHz 和「读不到」在用户眼里是两件事：
 * 前者像是核心被关到死，后者只是这台机型不给读。本页从不假装知道。
 */
public class DeviceOverviewActivity extends AppCompatActivity {


    /** 大小核配色：小核蓝、大核橙、超大核红。按频率分组轮换，与截图一致。 */
    private static final int[] CORE_COLORS = {0xFF7FA8F5, 0xFFF0B45A, 0xFFF08C7A};

    /**
     * 每核小卡一行放几个。
     *
     * <p>2.9 起是 4（原来 2）。8 核机从 4 行压到 2 行，整块高度少一半，
     * 「温度 / 功耗 / 系统」不必滚动就能看见。
     *
     * <p>改这个数之前先算宽度：页面左右各 12dp、卡片左右各 14dp，
     * 360dp 屏上是 {@code 360 - 24 - 28 = 308dp} 可用；
     * 4 格 × 3 个缝（4dp）= 12dp，每格约 74dp，减去格内左右各 7dp
     * 还有约 60dp 放文字。所以「2745MHz」要用 12.5sp 才放得下 ——
     * 想再缩小格子，得同时改字号，否则会被截断。
     */
    private static final int CORES_PER_ROW = 4;

    /** 采样间隔。1 秒是「像实时」与「不费电」之间的那个点。 */
    private static final long TICK_MS = 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private DeviceStats stats;

    /** 页面是否可见。不可见时停止排下一次，避免退到后台还在读 /proc。 */
    private volatile boolean visible;
    /** 是否已有一次采样在跑。 */
    private volatile boolean busy;

    /**
     * 是否已经渲染过至少一帧。
     *
     * <p>首帧为 false —— 此时不去跑 {@code top} 取进程列表，让内存 / GPU / CPU
     * 这些便宜的数据先上屏。否则「点进页面空白一两秒」，等的是左下角那一小块。
     * 主线程读写，不需要 volatile。
     *
     * <p>只在 {@link #onResume} 之后第一次采样用到；页面常驻期间一直是 true。
     */
    private boolean firstFrameDone;

    // ---- 需要实时改写的控件 ----
    private DonutView memDonut;
    private BarView memBarPhys;
    private TextView memPhysVal;
    private BarView memBarSwap;
    private TextView memSwapVal;
    private TextView memFoot;

    private DonutView gpuDonut;
    private TextView gpuClock;
    private TextView gpuLoad;
    private TextView gpuDetail;

    private DonutView cpuDonut;
    private TextView cpuTemp;
    private TextView cpuSoc;
    private TextView cpuLoad;
    private LinearLayout coreGrid;
    private LinearLayout procBox;
    private TextView procHead;

    private TextView powerW;
    private TextView batLevel;
    private TextView batTemp;
    private TextView sysVer;
    private TextView sysUptime;
    private TextView sysDevice;

    /** 每核小卡里那些「数字会变」的控件，按核心号排队。 */
    private final java.util.List<CoreCell> coreCells = new java.util.ArrayList<CoreCell>();

    /** 抽样计数器：SoC 名字这类不变的信息不必每秒重画。 */
    private int tickCount;

    private static final class CoreCell {
        /** 整个小卡的容器（要放进两列布局里，所以得存下来）。 */
        LinearLayout root;
        TextView load;
        TextView clock;
        TextView range;
        BarView bar;
        int color;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        stats = new DeviceStats(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.addView(buildTopBar());

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 32));
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        buildMemoryCard();
        buildGpuCard();
        buildCpuCard();
        buildBottomRow();
    }

    private LinearLayout content;

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        // 立即采一次：进入页面就有数，不用等第一个 1 秒。
        tickNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 停掉续期。正在跑的那一次会自然结束，render 里看到 visible=false 就不再续。
        visible = false;
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        visible = false;
        pool.shutdownNow();
        // 常驻 root shell 必须显式关掉：线程池停下不代表 su 进程会自己退，
        // 留着就是一个挂在后台的 root 进程。
        stats.close();
    }

    // ============================================================
    //  采样循环
    // ============================================================

    private void tickNow() {
        if (busy) {
            // 上一次还没跑完（慢机型 + 开了进程列表时可能出现），
            // 这次直接跳过，别排队。
            scheduleNext();
            return;
        }
        busy = true;
        // 首帧不跑 top：进程列表要多等 0.4~0.5 秒，为它拖慢整页不值得。
        // 看到第一帧渲染出来之后，后续每帧都正常带进程列表。
        final boolean withProcs = firstFrameDone;
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    final DeviceStats.Snapshot snap;
                    try {
                        snap = stats.sample(withProcs);
                    } catch (Throwable t) {
                        AppLog.e(TAG, "采样失败", t);
                        busy = false;
                        return;
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            firstFrameDone = true;
                            if (!isFinishing()) {
                                render(snap);
                            }
                            scheduleNext();
                        }
                    });
                }
            });
        } catch (Throwable t) {
            // 页面刚被销毁时线程池可能已经 shutdown，此时丢弃这次采样即可。
            busy = false;
        }
    }

    private static final String TAG = "GsOverview";

    private void scheduleNext() {
        ui.removeCallbacks(nextTick);
        if (visible && !isFinishing()) {
            ui.postDelayed(nextTick, TICK_MS);
        }
    }

    private final Runnable nextTick = new Runnable() {
        @Override
        public void run() {
            if (visible) {
                tickNow();
            }
        }
    };

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
        title.setText("设备概览");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(C_TEXT);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return bar;
    }

    // ============================================================
    //  内存
    // ============================================================

    private void buildMemoryCard() {
        LinearLayout card = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        memDonut = new DonutView(this);
        memDonut.setCenter("Memory", "");
        row.addView(memDonut, new LinearLayout.LayoutParams(dp(this, 88), dp(this, 88)));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = dp(this, 14);
        row.addView(right, rlp);

        // 物理内存
        memPhysVal = new TextView(this);
        memBarPhys = new BarView(this);
        right.addView(memLine("物理内存", memPhysVal, memBarPhys));
        right.addView(spacerV(10));
        // 交换分区
        memSwapVal = new TextView(this);
        memBarSwap = new BarView(this);
        right.addView(memLine("交换分区", memSwapVal, memBarSwap));

        memFoot = new TextView(this);
        memFoot.setTextSize(11.5f);
        memFoot.setTextColor(C_SUB);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(this, 10);

        // 先把「环形 + 两条进度」那一行加进去，再加脚下这行说明 ——
        // 顺序反了的话说明会跑到环形图上方。
        card.addView(row, wide());
        card.addView(memFoot, flp);

        content.addView(card, cardLp());
    }

    /** 一行「标题 + 数值」+ 下面一条进度条。 */
    private View memLine(String label, TextView value, BarView bar) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(12.5f);
        name.setTextColor(C_TEXT);
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        value.setTextSize(12.5f);
        value.setTextColor(C_SUB);
        head.addView(value);

        box.addView(head, wide());

        bar.setTrackColor(C_TRACK);
        bar.setFillColor(0xFF8FB4F7);
        bar.setHeight(dp(this, 7));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 7));
        blp.topMargin = dp(this, 6);
        box.addView(bar, blp);

        return box;
    }

    // ============================================================
    //  GPU
    // ============================================================

    private void buildGpuCard() {
        LinearLayout card = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        gpuDonut = new DonutView(this);
        gpuDonut.setCenter("GPU", "");
        gpuDonut.setFillColor(0xFF8FB4F7);
        row.addView(gpuDonut, new LinearLayout.LayoutParams(dp(this, 96), dp(this, 96)));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = dp(this, 14);
        row.addView(right, rlp);

        gpuClock = new TextView(this);
        gpuClock.setTextSize(23);
        gpuClock.setTypeface(null, Typeface.BOLD);
        gpuClock.setTextColor(C_TEXT);
        right.addView(gpuClock, wide());

        gpuLoad = new TextView(this);
        gpuLoad.setTextSize(12.5f);
        gpuLoad.setTextColor(C_TEXT);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(this, 2);
        right.addView(gpuLoad, llp);

        gpuDetail = new TextView(this);
        gpuDetail.setTextSize(10.5f);
        gpuDetail.setTextColor(C_HINT);
        gpuDetail.setLineSpacing(dp(this, 1), 1.1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(this, 8);
        right.addView(gpuDetail, dlp);

        card.addView(row, wide());
        content.addView(card, cardLp());
    }

    // ============================================================
    //  CPU
    // ============================================================

    private void buildCpuCard() {
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);

        // ---- 左：进程占用列表 ----
        // 既然整页数据都走 root，进程列表也就不必再藏在一个按钮后面：
        // 进页面就有数，跟其它区块一致。
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.rightMargin = dp(this, 10);
        top.addView(left, leftLp);

        procHead = new TextView(this);
        procHead.setTextSize(11f);
        procHead.setTextColor(C_HINT);
        procHead.setText("进程占用");
        left.addView(procHead, wide());

        procBox = new LinearLayout(this);
        procBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pblp.topMargin = dp(this, 8);
        left.addView(procBox, pblp);

        // ---- 右：CPU 环形 ----
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        top.addView(right, new LinearLayout.LayoutParams(dp(this, 132),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        cpuTemp = new TextView(this);
        cpuTemp.setTextSize(12.5f);
        cpuTemp.setTextColor(C_TEXT);
        cpuTemp.setGravity(Gravity.END);
        right.addView(cpuTemp, wide());

        cpuDonut = new DonutView(this);
        cpuDonut.setCenter("CPU", "");
        right.addView(cpuDonut, new LinearLayout.LayoutParams(dp(this, 96), dp(this, 96)));

        cpuSoc = new TextView(this);
        cpuSoc.setTextSize(12);
        cpuSoc.setTypeface(null, Typeface.BOLD);
        cpuSoc.setTextColor(C_TEXT);
        cpuSoc.setGravity(Gravity.END);
        right.addView(cpuSoc, wide());

        cpuLoad = new TextView(this);
        cpuLoad.setTextSize(11.5f);
        cpuLoad.setTextColor(C_SUB);
        cpuLoad.setGravity(Gravity.END);
        right.addView(cpuLoad, wide());

        card.addView(top, wide());

        // 分隔线
        View div = new View(this);
        div.setBackgroundColor(C_DIVIDER);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        divLp.topMargin = dp(this, 12);
        divLp.bottomMargin = dp(this, 12);
        card.addView(div, divLp);

        // ---- 每核网格 ----
        coreGrid = new LinearLayout(this);
        coreGrid.setOrientation(LinearLayout.VERTICAL);
        card.addView(coreGrid, wide());

        content.addView(card, cardLp());
    }

    /**
     * 按核心数重建网格。
     *
     * <p>【2.9 起改成一行四个】原来一行两个（半宽卡），8 核要占 4 行 ——
     * 在 8 核机上这一块就有四百多 dp 高，把「温度 / 功耗 / 系统」挤到屏幕外，
     * 想看全得往下滚。改成一行四个后 8 核只用 2 行，一屏就装得下。
     *
     * <p>代价是每格的可用宽度从约 152dp 掉到约 74dp（格内内容区约 62dp），
     * 所以 {@link #buildCoreCell} 里的字号与文案必须跟着压缩，
     * 否则「3532MHz」会被截断成「3532M…」。
     *
     * <p>宽度算式（以 360dp 宽的屏为例）：360 − 24（页面左右各 12）
     * − 28（卡片左右各 14）= 308dp，再减去 3 个 4dp 的缝，
     * 每格 (308 − 12) / 4 = 74dp；格内 padding 左右各 6dp，内容区 62dp。
     *
     * <p>核心数在运行中不会变，但首次采样前不知道几核，所以按 coreCells.size() 判重。
     */
    private void buildCoreGrid(final DeviceStats.Snapshot s) {
        int cores = s.coreCurMHz.length;
        if (coreCells.size() == cores) {
            return;
        }
        coreGrid.removeAllViews();
        coreCells.clear();

        int[] cluster = clusterIndex(s.coreMaxMHz);
        // 行内间距 4dp：每格约 74dp，4 格共 3 个缝 = 12dp。
        // 缝要小，因为每多 1dp × 3 个缝就是每格少 0.75dp。
        final int gap = dp(this, 4);
        LinearLayout rowBox = null;
        for (int i = 0; i < cores; i++) {
            if (i % CORES_PER_ROW == 0) {
                rowBox = new LinearLayout(this);
                rowBox.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = i == 0 ? 0 : gap;
                coreGrid.addView(rowBox, rlp);
            }
            CoreCell cell = buildCoreCell(i, CORE_COLORS[cluster[i] % CORE_COLORS.length]);
            coreCells.add(cell);

            // 左右各留一半间距，视觉上等于「相邻两格共享一个缝」。
            // 首尾两格把外侧那半份去掉，避免整行比别的卡窄一截。
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            boolean first = i % CORES_PER_ROW == 0;
            boolean last = i % CORES_PER_ROW == CORES_PER_ROW - 1 || i == cores - 1;
            clp.setMargins(first ? 0 : gap / 2, 0, last ? 0 : gap / 2, 0);
            rowBox.addView(cell.root, clp);
        }
        // 【为什么要补占位】weight=1 是按「本行有几个子 View」平分宽度的。
        // 6 核机最后一行只有 2 格，那两格会被拉到 152dp，和上一行的 74dp 对不齐，
        // 看起来像两行不同的网格。补几个不可见的占位把行填满，宽度就一致了。
        if (rowBox != null) {
            int filled = cores % CORES_PER_ROW;
            if (filled != 0) {
                // 占位永远出现在行中间或行末，左边一定有格子，所以左侧统一留半份缝。
                for (int k = filled; k < CORES_PER_ROW; k++) {
                    View hole = new View(this);
                    LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    boolean lastHole = k == CORES_PER_ROW - 1;
                    hlp.setMargins(gap / 2, 0, lastHole ? 0 : gap / 2, 0);
                    rowBox.addView(hole, hlp);
                }
            }
        }
    }

    private CoreCell buildCoreCell(int index, int color) {
        CoreCell cell = new CoreCell();
        cell.color = color;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFAFBFE);
        bg.setCornerRadius(dp(this, 8));
        // 一行四个后每格只有约 74dp 宽，内边距从 9dp 收到 6dp，
        // 否则留给「2745MHz」的宽度不够，会被截断成「2745M…」。
        box.setPadding(dp(this, 6), dp(this, 7), dp(this, 6), dp(this, 7));
        cell.root = box;

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText("cpu" + index);
        name.setTextSize(9f);
        name.setTextColor(C_HINT);
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        cell.load = new TextView(this);
        cell.load.setTextSize(9.5f);
        cell.load.setTextColor(color);
        cell.load.setTypeface(null, Typeface.BOLD);
        head.addView(cell.load);

        box.addView(head, wide());

        cell.bar = new BarView(this);
        cell.bar.setTrackColor(C_TRACK);
        cell.bar.setFillColor(color);
        cell.bar.setHeight(dp(this, 4));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 4));
        blp.topMargin = dp(this, 3);
        box.addView(cell.bar, blp);

        cell.clock = new TextView(this);
        // 【为什么是 13sp 而不是原来的 15sp】
        // 格内容宽度约 62dp。「3532MHz」按粗体数字约 0.62em/字估算，
        // 13sp 时约 55dp，放得下；15sp 要 63dp，会顶出去被截断。
        // 这是「一行四个」的必要代价 —— 想看大字号就把 CORES_PER_ROW 改回 2。
        cell.clock.setTextSize(13f);
        cell.clock.setTypeface(null, Typeface.BOLD);
        cell.clock.setTextColor(C_TEXT);
        // 单行 + 省略号：宁可缩字号也绝不折成两行，否则那一格比邻格高、整行被撑开。
        // 一定要设 ellipsize，只设单行的话超宽是**硬裁**，
        // 「3532MHz」会被切成「3532MH」这种看着像真值的残句。
        cell.clock.setSingleLine(true);
        cell.clock.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(this, 4);
        box.addView(cell.clock, klp);

        cell.range = new TextView(this);
        cell.range.setTextSize(8.5f);
        cell.range.setTextColor(C_HINT);
        // 区间比频率更长（`400~3532MHz` 10 个字符），62dp 里 8.5sp 勉强够；
        // 万一某机型标签更长，要的也是「400~3532…」而不是被硬切成「400~353」。
        cell.range.setSingleLine(true);
        cell.range.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams rglp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rglp.topMargin = dp(this, 1);
        box.addView(cell.range, rglp);

        return cell;
    }

    /**
     * 按最大频率给每个核心分「簇」。
     *
     * <p>大小核的最大频率天然不同（2.0GHz / 2.8GHz / 3.5GHz），所以拿
     * {@code cpuinfo_max_freq} 当分组键就够了，不需要去读
     * {@code cpu_capacity} 之类的调度参数。
     */
    private static int[] clusterIndex(long[] maxMHz) {
        int[] out = new int[maxMHz.length];
        if (maxMHz.length == 0) {
            return out;
        }
        java.util.LinkedHashMap<Long, Integer> order = new java.util.LinkedHashMap<Long, Integer>();
        for (long m : maxMHz) {
            if (!order.containsKey(m)) {
                order.put(m, order.size());
            }
        }
        for (int i = 0; i < maxMHz.length; i++) {
            Integer idx = order.get(maxMHz[i]);
            out[i] = idx == null ? 0 : idx;
        }
        return out;
    }

    // ============================================================
    //  底部两列：功耗 / 系统
    // ============================================================

    private void buildBottomRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout powerCard = card();
        powerW = addIconLine(powerCard, "功", "功耗");
        batLevel = addIconLine(powerCard, "电", "电量");
        batTemp = addIconLine(powerCard, "温", "温度");

        LinearLayout sysCard = card();
        sysVer = addIconLine(sysCard, "系", "系统");
        sysUptime = addIconLine(sysCard, "时", "开机");
        sysDevice = addIconLine(sysCard, "机", "机型");

        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.rightMargin = dp(this, 5);
        row.addView(powerCard, llp);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = dp(this, 5);
        row.addView(sysCard, rlp);

        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.topMargin = dp(this, 10);
        content.addView(row, wrap);
    }

    /** 一行「小图标方块 + 名字」，返回右侧那个值控件。 */
    private TextView addIconLine(LinearLayout parent, String icon, String label) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(11);
        ic.setTextColor(C_BRAND);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_BRAND_SOFT);
        bg.setCornerRadius(dp(this, 7));
        ic.setBackground(bg);
        line.addView(ic, new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(11.5f);
        name.setTextColor(C_SUB);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(this, 8);
        line.addView(name, nlp);

        TextView value = new TextView(this);
        value.setTextSize(13);
        value.setTextColor(C_TEXT);
        value.setTypeface(null, Typeface.BOLD);
        value.setGravity(Gravity.END);
        line.addView(value);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(this, 10);
        parent.addView(line, lp);
        return value;
    }
    // ============================================================
    //  渲染
    // ============================================================

    private void render(DeviceStats.Snapshot s) {
        tickCount++;

        renderMemory(s);
        renderGpu(s);
        renderCpu(s);
        renderBottom(s);
    }

    private void renderMemory(DeviceStats.Snapshot s) {
        long total = s.memTotal;
        long used = Math.max(0, s.memTotal - s.memAvail);
        float p = total > 0 ? (float) used / total : 0f;
        memDonut.setProgress(p);
        memDonut.setCenter("Memory", total > 0 ? Math.round(p * 100) + "%" : "");

        memBarPhys.setFillColor(0xFF8FB4F7);
        memBarPhys.setProgress(p);
        memPhysVal.setText(total > 0
                ? (Math.round(p * 100) + "% (" + gb(used) + "/" + gb(total) + ")")
                : "—");

        // 交换分区：很多机型根本没开，这时显示「未启用」比显示 0% 诚实。
        if (s.swapTotal > 0) {
            long swapUsed = Math.max(0, s.swapTotal - s.swapFree);
            float sp = (float) swapUsed / s.swapTotal;
            memBarSwap.setProgress(sp);
            memSwapVal.setText(Math.round(sp * 100) + "% (" + gb(swapUsed) + "/" + gb(s.swapTotal) + ")");
        } else {
            memBarSwap.setProgress(0f);
            memSwapVal.setText("未启用");
        }

        StringBuilder foot = new StringBuilder();
        foot.append("已用 ").append(total > 0 ? Math.round(p * 100) + "%" : "—");
        if (s.memAvail > 0) {
            foot.append(" · 可用 ").append(gb(s.memAvail));
        }
        if (s.swapCached > 0) {
            foot.append(" · SwapCached ").append(mb(s.swapCached));
        }
        memFoot.setText(foot.toString());
        // 内存吃紧时把环形换成警示色，一眼就能看出来。
        memDonut.setFillColor(p >= 0.9f ? C_DANGER : (p >= 0.75f ? C_WARN : 0xFF8FB4F7));
    }

    private void renderGpu(DeviceStats.Snapshot s) {
        boolean hasClock = s.gpuCurMHz > 0;
        gpuClock.setText(hasClock ? (s.gpuCurMHz + "MHz") : "—");

        if (s.gpuBusy >= 0) {
            gpuLoad.setText("负载：" + s.gpuBusy + "%");
            gpuDonut.setProgress(s.gpuBusy / 100f);
            gpuDonut.setCenter("GPU", s.gpuBusy + "%");
            gpuDonut.setFillColor(s.gpuBusy >= 90 ? C_DANGER
                    : (s.gpuBusy >= 60 ? C_WARN : 0xFF8FB4F7));
        } else if (hasClock && s.gpuMaxMHz > 0) {
            // 拿不到忙闲百分比时，用「当前频率 / 最大频率」当近似占用 ——
            // 这比空着有意义，但要在下面注明是推算值，不能冒充真实负载。
            float r = Math.min(1f, (float) s.gpuCurMHz / s.gpuMaxMHz);
            gpuLoad.setText("负载：— （按频率推算 " + Math.round(r * 100) + "%）");
            gpuDonut.setProgress(r);
            gpuDonut.setCenter("GPU", Math.round(r * 100) + "%");
            gpuDonut.setFillColor(0xFF8FB4F7);
        } else {
            gpuLoad.setText("负载：—");
            gpuDonut.setProgress(0f);
            gpuDonut.setCenter("GPU", "");
        }

        StringBuilder d = new StringBuilder();
        if (s.gpuName.length() > 0) {
            d.append(s.gpuName).append('\n');
        }
        if (s.gpuMaxMHz > 0) {
            d.append("最高频率: ").append(s.gpuMaxMHz).append("MHz\n");
        }
        d.append(s.gpuBusy >= 0 ? "数据源: sysfs/kgsl" : "数据源: sysfs（部分字段不可读）");
        gpuDetail.setText(d.toString());
    }

    private void renderCpu(DeviceStats.Snapshot s) {
        cpuTemp.setText(Float.isNaN(s.cpuTemp)
                ? "温度 —"
                : String.format(Locale.US, "%.1f°C", s.cpuTemp));
        cpuTemp.setTextColor(Float.isNaN(s.cpuTemp) ? C_HINT
                : (s.cpuTemp >= 75f ? C_DANGER : (s.cpuTemp >= 60f ? C_WARN : C_TEXT)));

        // 首帧 / 读不到时算不出负载 —— 那种时候必须显示「—」。
        // 显示 0% 会让人以为 CPU 真的空着，而这恰恰是最容易误导的一种「看起来正常」。
        if (s.hasCpuLoad) {
            cpuDonut.setProgress(s.cpuLoad / 100f);
            cpuDonut.setCenter("CPU", s.cpuLoad + "%");
            cpuDonut.setFillColor(s.cpuLoad >= 85 ? C_DANGER
                    : (s.cpuLoad >= 60 ? C_WARN : 0xFF8FB4F7));
            cpuLoad.setText("负载：" + s.cpuLoad + "%");
        } else {
            cpuDonut.setProgress(0f);
            cpuDonut.setCenter("CPU", "");
            cpuDonut.setFillColor(0xFF8FB4F7);
            cpuLoad.setText(s.rootOk ? "负载：测量中…" : "负载：—（需要 root）");
        }

        if (tickCount == 1 || cpuSoc.getText().length() == 0) {
            String soc = s.soc.length() > 0 ? s.soc : "未知 SoC";
            // 只有一个分组时「(8)」没有信息量，省略括号。
            String layout = s.layout.indexOf('+') >= 0 ? (" (" + s.layout + ")") : "";
            cpuSoc.setText(soc + layout);
        }

        buildCoreGrid(s);
        renderCoreCells(s);
        renderProcs(s);
    }

    private void renderCoreCells(DeviceStats.Snapshot s) {
        int n = Math.min(coreCells.size(), s.coreCurMHz.length);
        for (int i = 0; i < n; i++) {
            CoreCell c = coreCells.get(i);
            long cur = s.coreCurMHz[i];
            long min = s.coreMinMHz[i];
            long max = s.coreMaxMHz[i];
            // coreLoad 在「/proc/stat 读不到」时是空数组，
            // 此时不能取 0 当负载 —— 那等于编了一个「这个核闲着」。
            boolean hasLoad = i < s.coreLoad.length && s.hasCpuLoad;
            int load = hasLoad ? s.coreLoad[i] : 0;

            c.clock.setText(cur > 0 ? (cur + "MHz") : "—");
            c.clock.setTextColor(cur > 0 ? C_TEXT : C_HINT);
            c.load.setText(hasLoad ? (load + "%") : "—");
            c.bar.setProgress(load / 100f);

            if (min > 0 && max > 0) {
                c.range.setText(min + "~" + max + "MHz");
            } else if (max > 0) {
                c.range.setText("最高 " + max + "MHz");
            } else {
                c.range.setText("频率不可读");
            }

            // 核心跑到最高频附近时把数字染成该簇的颜色，一眼看出谁在扛。
            boolean hot = max > 0 && cur > 0 && cur >= max * 0.98f;
            c.clock.setTextColor(hot ? c.color : (cur > 0 ? C_TEXT : C_HINT));
        }
    }

    private void renderProcs(DeviceStats.Snapshot s) {
        if (s.procs.isEmpty()) {
            // 首帧还没跑 top，procHint 是「读取中…」。
            // 这时保留「进程占用」这个标题，只在下面挂一行灰字 ——
            // 标题突然变成一个短句，会让人以为这一块坏了。
            if (s.procHint != null) {
                procHead.setText("进程占用");
                procBox.removeAllViews();
                TextView loading = new TextView(this);
                loading.setText(s.procHint);
                loading.setTextSize(11f);
                loading.setTextColor(C_HINT);
                procBox.addView(loading, wide());
            } else {
                procBox.removeAllViews();
            }
            return;
        }
        // 「合计」= 下面列出的这几条之和（不是整机）。多核上限 100%×核数，
        // 所以看到「合计 218%」别当成 bug —— 一个进程能同时吃满好几个核。
        procHead.setText("进程占用" + (s.procTotal != null ? "（合计 " + s.procTotal + "）" : ""));
        procBox.removeAllViews();
        int max = Math.min(5, s.procs.size());
        for (int i = 0; i < max; i++) {
            DeviceStats.ProcInfo p = s.procs.get(i);

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            TextView t = new TextView(this);
            // 已经是应用名（能查到的话），不必再截断包名 —— 截了反而认不出。
            t.setText(p.label);
            t.setTextSize(10.5f);
            // 系统进程用灰字：一眼能分辨「这是系统在跑」还是「某个 App 在跑」。
            t.setTextColor(p.system ? C_HINT : C_TEXT);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView v = new TextView(this);
            v.setText(p.pct);
            v.setTextSize(10.5f);
            v.setTextColor(C_SUB);
            line.addView(v);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = i == 0 ? 0 : dp(this, 5);
            procBox.addView(line, lp);
        }
    }

    private void renderBottom(DeviceStats.Snapshot s) {
        // ★ 功率的方向由「是否在充电」决定，不看电流符号。
        // 各家 ROM 对 CURRENT_NOW 的符号约定是反的（这台小米就是放电为正），
        // 直接打符号会出现「没插充电器却显示 +2.02W」这种自相矛盾的读数。
        //
        // 所以符号由充电状态决定：充电 +、放电 −。
        // 读数本身仍用绝对值，正负号只是「在充 / 在放」的视觉表达。
        if (Float.isNaN(s.batteryPowerW)) {
            powerW.setText("—");
            powerW.setTextColor(C_HINT);
        } else {
            String sign = s.charging ? "+" : "-";
            powerW.setText(sign + String.format(Locale.US, "%.2fW", s.batteryPowerW));
            // 充电绿、放电常规色（放电本身不是坏事，不该报警）
            powerW.setTextColor(s.charging ? C_OK : C_TEXT);
        }

        StringBuilder bat = new StringBuilder();
        bat.append(s.batteryLevel >= 0 ? s.batteryLevel + "%" : "—");
        if (!Float.isNaN(s.batteryVolt)) {
            bat.append(String.format(Locale.US, "  %.2fv", s.batteryVolt));
        }
        batLevel.setText(bat.toString());
        batLevel.setTextColor(s.batteryLevel >= 0 && s.batteryLevel <= 15 ? C_DANGER : C_TEXT);

        batTemp.setText(Float.isNaN(s.batteryTemp)
                ? "—"
                : String.format(Locale.US, "%.1f°C", s.batteryTemp));
        batTemp.setTextColor(Float.isNaN(s.batteryTemp) ? C_HINT
                : (s.batteryTemp >= 42f ? C_DANGER : C_TEXT));

        sysVer.setText(s.androidVer.length() > 0 ? s.androidVer : "—");
        sysUptime.setText(uptime(s.uptimeMs));
        sysDevice.setText(s.device.length() > 0 ? s.device : "—");
    }

    /** 毫秒 →「3天 05:12:33」/「05:12:33」。 */
    private static String uptime(long ms) {
        if (ms <= 0) {
            return "—";
        }
        long sec = ms / 1000L;
        long d = sec / 86400L;
        long h = (sec % 86400L) / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        String hms = String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
        return d > 0 ? (d + "天 " + hms) : hms;
    }

    private static String gb(long bytes) {
        if (bytes <= 0) {
            return "0GB";
        }
        return String.format(Locale.US, "%.1fGB", bytes / 1024.0 / 1024 / 1024);
    }

    private static String mb(long bytes) {
        if (bytes <= 0) {
            return "0MB";
        }
        return String.format(Locale.US, "%.0fMB", bytes / 1024.0 / 1024);
    }

    // ============================================================
    //  通用 UI
    // ============================================================

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(borderedBg(this, C_SURFACE, C_DIVIDER, 14));
        c.setPadding(dp(this, 14), dp(this, 13), dp(this, 14), dp(this, 13));
        c.setElevation(dp(this, 1));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = wide();
        lp.topMargin = dp(this, 10);
        return lp;
    }

    private View spacerV(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, h)));
        return v;
    }

                private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ============================================================
    //  自绘控件
    // ============================================================

    /**
     * 环形进度图（中心一行标题 + 一行数值）。
     *
     * <p>从正上方（-90°）顺时针画，与截图里的观感一致。
     * 底色轨道画一整圈，前景按比例盖上去；比例大于 0 时圆头收尾，
     * 0 时什么都不画 —— 否则会留一个圆点，看着像「有一点点占用」。
     */
    static final class DonutView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float progress;
        private String title = "";
        private String value = "";

        DonutView(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(0xFFEDF0F7);
            bar.setStyle(Paint.Style.STROKE);
            bar.setColor(0xFF8FB4F7);
            bar.setStrokeCap(Paint.Cap.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
        }

        void setProgress(float p) {
            float v = Math.max(0f, Math.min(1f, p));
            if (Math.abs(v - progress) < 0.002f) {
                return;
            }
            progress = v;
            invalidate();
        }

        void setFillColor(int color) {
            if (bar.getColor() != color) {
                bar.setColor(color);
                invalidate();
            }
        }

        void setCenter(String title, String value) {
            this.title = title == null ? "" : title;
            this.value = value == null ? "" : value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            float size = Math.min(w, h);
            float stroke = size * 0.13f;
            track.setStrokeWidth(stroke);
            bar.setStrokeWidth(stroke);

            float pad = stroke / 2f + 1f;
            oval.set(pad, pad, w - pad, h - pad);

            c.drawArc(oval, 0f, 360f, false, track);
            if (progress > 0.001f) {
                float sweep = 360f * progress;
                // 圆头会把两端各多画半个笔宽，进度很小时看起来比实际多。
                // 这里把圆头关掉换成 butt，只在小进度时使用，避免「0.5% 看着像 5%」。
                bar.setStrokeCap(sweep < 12f ? Paint.Cap.BUTT : Paint.Cap.ROUND);
                c.drawArc(oval, -90f, sweep, false, bar);
            }

            float cx = w / 2f;
            float cy = h / 2f;
            if (title.length() > 0) {
                text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
                text.setColor(0xFF8A93A6);
                text.setTextSize(size * 0.155f);
                c.drawText(title, cx, cy + (value.length() > 0 ? -size * 0.02f : size * 0.06f), text);
            }
            if (value.length() > 0) {
                text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                text.setColor(0xFF3D4557);
                text.setTextSize(size * 0.16f);
                c.drawText(value, cx, cy + size * 0.21f, text);
            }
        }
    }

    /**
     * 极简水平/柱状条 —— 只画一个圆角轨道 + 一段圆角前景。
     *
     * <p>名字叫 Bar 但布局上是水平的：高度由 {@link #setHeight} 指定，
     * 内部的柱子高度随 {@link #progress} 变化，正好也能当「迷你柱状图」用。
     */
    static final class BarView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float progress;
        private int trackColor = 0xFFE8EBF4;
        private int fillColor = 0xFF8FB4F7;
        private int barHeight;

        BarView(Context c) {
            super(c);
            p.setStyle(Paint.Style.FILL);
        }

        void setTrackColor(int color) {
            trackColor = color;
            invalidate();
        }

        void setFillColor(int color) {
            if (fillColor != color) {
                fillColor = color;
                invalidate();
            }
        }

        void setHeight(int px) {
            barHeight = px;
            requestLayout();
        }

        void setProgress(float v) {
            float n = Math.max(0f, Math.min(1f, v));
            if (Math.abs(n - progress) < 0.002f) {
                return;
            }
            progress = n;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            float radius = Math.min(h, barHeight > 0 ? barHeight : h) / 2f;

            r.set(0, 0, w, h);
            p.setColor(trackColor);
            c.drawRoundRect(r, radius, radius, p);

            if (progress <= 0f) {
                return;
            }
            float fw = Math.max(radius * 2f, w * progress);
            r.set(0, 0, Math.min(fw, w), h);
            p.setColor(fillColor);
            c.drawRoundRect(r, radius, radius, p);
        }
    }

    /** 供 MainActivity 复用。 */
    public static void open(Context ctx) {
        Intent i = new Intent(ctx, DeviceOverviewActivity.class);
        if (!(ctx instanceof android.app.Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }
}