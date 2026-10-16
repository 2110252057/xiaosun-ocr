package com.xiaosun.ocr;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 边缘手势触发器 —— 配置持久化 + 运行时状态。
 *
 * ════════════════════════════════════════════════════════════════
 * 【这个功能是干什么的】
 * ════════════════════════════════════════════════════════════════
 * 在屏幕边缘放一个「滑动条」（和 MacroDroid 的“边缘手势 / 浮动按钮”一样），
 * 手指按上去朝 8 个方向滑动，滑过设定距离就立刻触发 —— **不用抬手**。
 *
 * 触发后干两件事：
 *   1. 立刻拉起本 App 的 OCRService（保证后续 OCR 不用等冷启动）；
 *   2. 把「方向 / 手柄名 / 事件类型 / 序号」写进静态字段，
 *      插件（PluginFireReceiver）触发时就能读到，回传给宏。
 *
 * 也就是说：手势 → 宏 → 小孙OCR 插件 → OCR → 变量，全程闭环。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么配置存 JSON 字符串】
 * ════════════════════════════════════════════════════════════════
 * 手柄是「一组可变数量的对象」，SharedPreferences 只支持基本类型。
 * 与其把每个字段拆成 pref_0_name / pref_0_width … 这种平铺 key
 * （改字段就要改一堆解析代码），不如整体序列化成一个 JSON 数组，
 * 加字段时旧数据也能自然兼容（缺字段走默认值）。
 */
public final class EdgeGesturePrefs {

    private static final String TAG = "XSOCR-Gesture";

    private static final String PREFS = "edge_gesture";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HANDLES = "handles";
    private static final String KEY_AREA = "area";
    private static final String KEY_DEF_DIST = "def_dist";
    private static final String KEY_HAPTIC = "haptic";
    private static final String KEY_MENU_ENABLED = "menu_enabled";
    private static final String KEY_MENU_ITEMS = "menu_items";
    private static final String KEY_MENU_ICON_DP = "menu_icon_dp";
    private static final String KEY_MENU_SHOW_TEXT = "menu_show_text";
    private static final String KEY_MENU_LAYOUT = "menu_layout";
    /** 防烧屏漂移：总开关 / 幅度(dp) / 间隔(秒)。 */
    private static final String KEY_BURNIN = "burnin";
    private static final String KEY_BURNIN_RANGE = "burnin_range";
    private static final String KEY_BURNIN_INTERVAL = "burnin_interval";
    /**
     * 斜排布局「每排最多几项」的**默认值**（放不下就在上方再加一排）。
     *
     * 逐排单独设置的覆盖值存在 {@link #KEY_MENU_ROW_COUNTS} 里，
     * 这个键只作为「某一排没单独设过」时的兜底。
     */
    private static final String KEY_MENU_ARC_PER_RING = "menu_arc_per_ring";
    /**
     * 逐排数量，逗号分隔，例如 {@code "3,5,4"} = 第 1 排 3 项、第 2 排 5 项、
     * 第 3 排 4 项。留空 = 全部用 {@link #KEY_MENU_ARC_PER_RING} 的默认值。
     */
    private static final String KEY_MENU_ROW_COUNTS = "menu_row_counts";
    /** 图标下方文字标签的字号（dp）。默认 12。 */
    private static final String KEY_MENU_TEXT_DP = "menu_text_dp";
    /** 文字与图标底边之间的距离（dp）。默认 4。 */
    private static final String KEY_MENU_TEXT_GAP = "menu_text_gap";
    /** 图标圆底的填充色（ARGB）。默认白 = 原来的浅色圆底。 */
    private static final String KEY_MENU_BG_COLOR = "menu_bg_color";
    /** 图标下方文字标签的颜色（ARGB）。默认纯黑。 */
    private static final String KEY_MENU_TEXT_COLOR = "menu_text_color";
    /** 选中（高亮）时的颜色（ARGB）。默认青色。 */
    private static final String KEY_MENU_SEL_COLOR = "menu_sel_color";
    /**
     * 菜单圆底用浅色还是深色。
     *
     * **默认浅色 = 实心白圆 + 彩色图标 + 深色文字**，也就是「一排白色
     * 圆按钮」的观感：
     *   · 白圆不吸光，圆里放的是应用的原色图标，微信还是绿的、QQ 还是蓝的；
     *   · 底下的文字标签也改成深色，在浅色背景上才看得见；
     *   · 整屏不再压暗 —— 白圆本身对比就够，没必要把用户的界面搞暗。
     *
     * 深色（半透明黑圆 + 白字）留作可选项，适合「背景深色、白圆显得
     * 扎眼」的场合。
     */
    private static final String KEY_MENU_LIGHT_BUBBLE = "menu_light_bubble";
    /** 命中热区相对图标大小的倍率（%）。100 = 与图标一样大。 */
    private static final String KEY_MENU_HIT_SCALE = "menu_hit_scale";
    /** 同一环内相邻图标的间距（dp）。 */
    private static final String KEY_MENU_GAP = "menu_gap";
    /** 环与环之间的间距（dp）。 */
    private static final String KEY_MENU_RING_GAP = "menu_ring_gap";

    private EdgeGesturePrefs() {
    }

    // ============================================================
    //  8 个方向
    // ============================================================

    public static final int DIR_UP = 0;
    public static final int DIR_UP_RIGHT = 1;
    public static final int DIR_RIGHT = 2;
    public static final int DIR_DOWN_RIGHT = 3;
    public static final int DIR_DOWN = 4;
    public static final int DIR_DOWN_LEFT = 5;
    public static final int DIR_LEFT = 6;
    public static final int DIR_UP_LEFT = 7;

    /** 方向名（存进 Bundle / JSON 用英文，避免编码问题）。 */
    public static final String[] DIR_KEYS = {
            "up", "up_right", "right", "down_right",
            "down", "down_left", "left", "up_left"
    };

    /** 方向中文名 —— 顺序和图二的对话框一致。 */
    public static final String[] DIR_LABELS = {
            "向上", "向右上", "向右", "向右下",
            "向下", "向左下", "向左", "向左上"
    };

    /** 方向箭头字形，直接当文字用，省一套 drawable。 */
    public static final String[] DIR_ARROWS = {
            "↑", "↗", "→", "↘", "↓", "↙", "←", "↖"
    };

    // ============================================================
    //  触发时机
    // ============================================================

    /**
     * 触发时机：滑过阈值的那一刻就触发（「不用抬手」指的就是这个）。
     *
     * ★ 这里原本还有两个时机，现已**移除**：
     *   · EVENT_START(1)  「按下手柄时触发」—— 手指一碰手柄宏就跑，
     *     和滑动菜单 / 误触搅在一起，想滑一下却先把宏触发了；
     *   · EVENT_CANCEL(2)「滑动未达距离后抬手时触发」—— 实际场景很少，
     *     还容易和「滑了但该方向没启用」混淆，表现为「手柄像失灵了」。
     *
     * 现在只保留最直觉的一种：滑过距离即触发。
     * 编号仍为 0 —— 老配置里存的 event=1/2 会在读取时被归零，
     * 升级后行为自动收敛，不会出现两种行为并存。
     */
    public static final int EVENT_TRIGGER = 0;

    public static final String[] EVENT_KEYS = {"trigger"};
    public static final String[] EVENT_LABELS = {
            "滑动超过距离时触发（不用抬手）"
    };

    // ============================================================
    //  触控区域
    // ============================================================

    // 「仅手柄条 / 整个边缘」两选一已下线，只剩手柄条。理由：
    //   · 「整个边缘」把整条边都变成触摸区，用户从边缘侧滑返回就失效了；
    //   · 它也让触摸目标变得「到处都是」，滑一下不知该触发哪个手柄；
    //   · 手柄条本身能拖动、能调宽，要「好按」把宽度调大就够了。

    // ============================================================
    //  手柄数据模型
    // ============================================================

    public static class Handle {
        public int id = 1;
        public String name = "右侧手柄";

        /** 0 = 左边缘，1 = 右边缘。 */
        public int edge = 1;

        /** 手柄总宽度（dp）—— 含「看不见但能摸到」的部分，决定可拖拽/可滑动的横向范围。 */
        public int width = 56;
        /** 画面上真正能看到的宽度（dp）。默认很细，追求“不挡内容”。 */
        public int visibleWidth = 8;
        /** 手柄高度（dp）。 */
        public int height = 150;
        /** 垂直位置偏移，0~100（%），50 = 屏幕正中。 */
        public int offsetPercent = 0;

        /** 颜色（ARGB）。 */
        public int color = 0xFF9AA0A6;
        /** 不透明度 0~100。 */
        public int alpha = 100;
        /** 圆角半径（dp）。 */
        public int radius = 12;

        /** 滑动距离阈值（dp）—— 超过它就触发（或进入“已触发”状态）。 */
        public int threshold = 90;

        /**
         * 触发时机，恒为 {@link #EVENT_TRIGGER}。
         *
         * 字段保留只是为了兼容老配置 JSON（那里可能存着 1/2），
         * 读取时一律归零 —— 另外两种时机已下线。
         */
        public int event = EVENT_TRIGGER;

        /** 每个方向是否启用。 */
        public boolean[] enabled = new boolean[8];
        /** 每个方向独立的距离阈值（dp）；<=0 表示沿用 {@link #threshold}。 */
        public int[] distance = new int[8];
        /** 是否震动反馈。 */
        public boolean haptic = true;

        /**
         * 是否为这条手柄覆盖的区域申请「系统手势排除」。
         *
         * ══ 为什么需要它 ══════════════════════════════════════════
         * 手柄贴在屏幕边缘时，系统的侧滑返回（手势导航）是在**系统层**
         * 先判定的，比应用窗口拿到的还早：手指刚落到边缘那一带，
         * 系统就把它当成「返回」吞掉了，手柄等于按不到。
         * 这正是「可见的条子在那儿、手按上去却没反应」的根因。
         *
         * 开启后，把这条手柄占的矩形用
         * {@link View#setSystemGestureExclusionRects} 报给系统，
         * 那一带就不再判系统手势，触摸完整交给本应用。
         *
         * ══ 限制（别指望它万能）══════════════════════════════
         *  · 仅 API 29+ 有效；三键导航下本来就没有边缘手势，开了也无害。
         *  · 系统对**每条边**可排除的总高度有 200dp 上限，
         *    手柄高于 200dp 的部分会被系统悄悄裁掉。
         *  · 这是「申请」不是「命令」，个别定制 ROM 可能不完全遵守。
         */
        public boolean blockGesture = true;

        public Handle() {
            // 默认只开「向左 / 向右」两个方向 —— 手柄在边缘，这两个最顺手。
            enabled[DIR_LEFT] = true;
            enabled[DIR_RIGHT] = true;
        }

        /** 该方向生效的距离阈值（dp）。 */
        public int distanceOf(int dir) {
            int d = (dir >= 0 && dir < 8) ? distance[dir] : 0;
            return d > 0 ? d : threshold;
        }

        public boolean isEdgeLeft() {
            return edge == 0;
        }
    }

    // ============================================================
    //  读 / 写
    // ============================================================

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    /** 全局默认距离阈值（dp），新增手柄时用它。 */
    public static int getDefaultDistance(Context c) {
        return sp(c).getInt(KEY_DEF_DIST, 90);
    }

    public static void setDefaultDistance(Context c, int dp) {
        sp(c).edit().putInt(KEY_DEF_DIST, dp).apply();
    }
    // ============================================================
    //  手势菜单配置
    // ============================================================
    //
    // 【为什么菜单定义存在本应用，而不是宏每次传】
    // 宏是一条一条执行的动作序列，它没法「常驻等事件」。所以：
    //   · 菜单长什么样 → 在本应用的设置页里配（EdgeGestureMenuActivity），
    //     存进 SharedPreferences，之后每次滑动手势直接取用，
    //     不需要宏在滑动那一刻做任何事。
    //   · 选中了哪一项 → 抬手时回传菜单变量，宏再分支执行动作。
    //
    // 这样宏侧只做一件事：读菜单变量分支。
    // 也正因为「配置」和「触发」彻底分开，宏完全不需要处理
    // 「手指还按在屏幕上」的中间态。

    /** 菜单项文本里的列分隔符。 */
    private static final char BAR = '|';

    /** 菜单项。 */
    public static class MenuItem {
        /** 显示文字（也是唯一必填项）。 */
        public String text = "";
        /**
         * 图标来源，三种写法：
         *   app:包名      取该应用图标
         *   file:/路径    读图片文件
         *   资源名        取本应用 drawable（drawable 或 mipmap）
         * 空串 = 只显示文字，会拿 text 的第一个字顶着。
         */
        public String source = "";
        /**
         * 单选这项时要回传给宏的动作值。
         *
         * 【为什么要和 source 分开】
         * 之前两者挤在同一个字段里：既能当图标来源、又被当成动作串。
         * 一旦配置搬到本应用自己的界面上，这就很别扭 ——
         * 想让按钮显示微信图标、但动作值叫 "wechat"，就没法表达了。
         * 现在拆开：source 只管画什么，action 只管回传什么。
         *
         * 留空时自动回退：新格式回退到 text（名字就是动作值，最直观）；
         * 老的两段式 `文字|source` 则回退到 source（保持升级前配好的宏可用）。
         */
        public String action = "";

    }

    /**
     * 解析菜单项文本：每行一项，格式 {@code 文字|图标|动作}。
     *
     * 【为什么用纯文本而不是 JSON】
     * 宏那边（Java 动作里拼 JSON）要转义引号，非常容易出错；
     * 纯文本用 \n 分行、| 分列，拼起来最省事。
     *
     * 【向后兼容】
     * 列数不足时按段数回退，老数据不用迁移：
     *   三段 `文字|图标|动作` → 全取（本应用界面写出的就是这种）
     *   两段 `文字|图标`     → action 回退为 source，保持升级前的行为
     *   一段 `文字`         → action 回退为 text
     *
     * 文字里含 "|" 的按**前两个**竖线切分，多余的都并进动作值。
     * 没有名字（首列为空）的行直接丢弃 —— 点了也不可能知道是哪项。
     */
    public static List<MenuItem> parseMenuItems(String raw) {
        List<MenuItem> out = new ArrayList<>();
        if (raw == null || raw.length() == 0) return out;

        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.length() == 0) continue;

            // 用 indexOf 手动切分而不是 split：
            // split 的参数是**正则**，竖线在正则里是特殊字符，
            // 想让它按字面切还得再转义一层，极易写错。
            // 这里只要「最多切两刀」，手写反而更直白，也不吃正则的亏。
            int b1 = s.indexOf(BAR);
            String text = (b1 < 0) ? s : s.substring(0, b1).trim();
            if (text.length() == 0) continue;   // 没名字的项没法用，直接丢

            int b2 = (b1 < 0) ? -1 : s.indexOf(BAR, b1 + 1);

            MenuItem it = new MenuItem();
            it.text = text;
            it.source = (b1 < 0) ? ""
                    : (b2 < 0 ? s.substring(b1 + 1) : s.substring(b1 + 1, b2)).trim();
            // 第二刀之后的东西全都算动作值（动作值里允许再出现竖线）
            it.action = (b2 < 0) ? "" : s.substring(b2 + 1).trim();

            if (it.action.length() == 0) {
                // 老格式（两段）里 source 身兼二职，回退给它才算保持原样；
                // 新格式（一段）没有别的信息，就用名字。
                it.action = (b1 >= 0 && b2 < 0) ? it.source : it.text;
            }
            out.add(it);
        }
        return out;
    }

    /** 反向序列化（编辑界面保存时用，总是写出三段）。 */
    public static String joinMenuItems(List<MenuItem> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (MenuItem it : items) {
            if (it == null) continue;
            String t = it.text == null ? "" : it.text.trim();
            if (t.length() == 0) continue;      // 与解析端一致：没名字的项不落盘
            if (sb.length() > 0) sb.append("\n");
            sb.append(t).append('|');
            sb.append(it.source == null ? "" : it.source.trim());
            sb.append('|');
            sb.append(it.action == null ? "" : it.action.trim());
        }
        return sb.toString();
    }

    /** 是否启用滑动菜单（false = 滑过阈值直接触发宏，老行为）。 */
    public static boolean isMenuEnabled(Context c) {
        return sp(c).getBoolean(KEY_MENU_ENABLED, false);
    }

    public static void setMenuEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_MENU_ENABLED, on).apply();
    }

    /** 菜单项的原始文本（每行 `文字|source`）。 */
    public static String getMenuItemsRaw(Context c) {
        return sp(c).getString(KEY_MENU_ITEMS, "");
    }

    public static void setMenuItemsRaw(Context c, String raw) {
        sp(c).edit().putString(KEY_MENU_ITEMS, raw == null ? "" : raw).apply();
    }

    /** 菜单项的解析结果（每次现解，量很小，不值得缓存）。 */
    public static List<MenuItem> getMenuItems(Context c) {
        return parseMenuItems(getMenuItemsRaw(c));
    }

    /** 菜单图标直径（dp）。 */
    public static int getMenuIconDp(Context c) {
        // 默认 48dp：图标一多（5 项以上）时 48dp 的半圆能整个落在屏内，
        // 不会被自动等比缩小，看起来也更接近「一圈小圆按钮」。
        // 想要大图标自己在设置里往上调（上限 240）。
        int v = sp(c).getInt(KEY_MENU_ICON_DP, 48);
        return clamp(v, 48, 240);
    }

    public static void setMenuIconDp(Context c, int dp) {
        sp(c).edit().putInt(KEY_MENU_ICON_DP, clamp(dp, 48, 240)).apply();
    }

    /** 是否在图标下方显示文字。 */
    public static boolean isMenuShowText(Context c) {
        return sp(c).getBoolean(KEY_MENU_SHOW_TEXT, true);
    }

    public static void setMenuShowText(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_MENU_SHOW_TEXT, on).apply();
    }

    /**
     * 布局：斜排 —— 沿 45° 斜线从左下往右上排开，放不下时在上方再加一排。
     *
     * 早期还有「底部横排」与「环形」两种，已废弃；老配置里读到 bar/circle 一律按斜排处理。
     */
    public static final String MENU_LAYOUT_ARC = "arc";
    public static String getMenuLayout(Context c) {
        return MENU_LAYOUT_ARC;
    }
    public static void setMenuLayout(Context c, String layout) {
        sp(c).edit().putString(KEY_MENU_LAYOUT, MENU_LAYOUT_ARC).apply();
    }
    /**
     * 斜排「每排最多放几项」。
     *
     * 放不下的项不是丢弃、而是在**上方再加一排**
     * （见 EdgeGestureService.layoutMenuLine），
     * 所以这里本质上是「一排的长度」而不是容量上限。
     */
    public static int getMenuArcPerRing(Context c) {
        return clamp(sp(c).getInt(KEY_MENU_ARC_PER_RING, 5), 2, 12);
    }
    public static void setMenuArcPerRing(Context c, int n) {
        sp(c).edit().putInt(KEY_MENU_ARC_PER_RING, clamp(n, 2, 12)).apply();
    }

    /**
     * 逐排数量（第 1 排、第 2 排 …）。空数组 = 没单独设过，用默认值。
     *
     * <p>容错到底：格式不对、数字超范围都只是**忽略这一项**，
     * 绝不抛异常 —— 这串文本是用户手输的，写坏了不该让菜单弹不出来。
     */
    public static int[] getMenuRowCounts(Context c) {
        String raw = sp(c).getString(KEY_MENU_ROW_COUNTS, "");
        if (raw == null || raw.trim().length() == 0) return new int[0];
        String[] parts = raw.split(",");
        List<Integer> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.length() == 0) continue;
            try {
                out.add(clamp(Integer.parseInt(t), 1, 20));
            } catch (NumberFormatException ignored) {
                // 手输的脏数据直接跳过，不影响其它排
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    public static void setMenuRowCounts(Context c, int[] counts) {
        StringBuilder sb = new StringBuilder();
        if (counts != null) {
            for (int v : counts) {
                if (sb.length() > 0) sb.append(',');
                sb.append(clamp(v, 1, 20));
            }
        }
        sp(c).edit().putString(KEY_MENU_ROW_COUNTS, sb.toString()).apply();
    }

    /** 圆底填充色。默认 0xFFFFFFFF（实心白圆）。 */
    public static int getMenuBgColor(Context c) {
        return sp(c).getInt(KEY_MENU_BG_COLOR, 0xFFFFFFFF);
    }

    public static void setMenuBgColor(Context c, int argb) {
        sp(c).edit().putInt(KEY_MENU_BG_COLOR, argb).apply();
    }

    /** 文字标签颜色。默认 0xFF000000（纯黑，配白圆底）。 */
    public static int getMenuTextColor(Context c) {
        return sp(c).getInt(KEY_MENU_TEXT_COLOR, 0xFF000000);
    }

    public static void setMenuTextColor(Context c, int argb) {
        sp(c).edit().putInt(KEY_MENU_TEXT_COLOR, argb).apply();
    }

    /** 选中高亮色。默认 0xFF00E5FF（青色）。 */
    public static int getMenuSelColor(Context c) {
        return sp(c).getInt(KEY_MENU_SEL_COLOR, 0xFF00E5FF);
    }

    public static void setMenuSelColor(Context c, int argb) {
        sp(c).edit().putInt(KEY_MENU_SEL_COLOR, argb).apply();
    }

    // ============================================================
    //  防烧屏（像素漂移）
    // ============================================================

    /**
     * 手柄是常驻悬浮窗，长期钉在同一位置，OLED 屏会沿它的形状留下残影。
     *
     * 开启后让窗口每隔一段时间在**垂直方向**随机挪几个像素：幅度很小、
     * 几乎看不出来，但发光像素的位置错开了，残影就不容易堆积。
     *
     * 默认开 —— 这是保护性功能，且幅度小到无感；不想要可以关掉，
     * 或把幅度设成 0。
     */
    public static boolean isBurnInEnabled(Context c) {
        return sp(c).getBoolean(KEY_BURNIN, true);
    }

    public static void setBurnInEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_BURNIN, on).apply();
    }

    /** 漂移幅度（dp），0 = 原地不动。 */
    public static int getBurnInRangeDp(Context c) {
        return clamp(sp(c).getInt(KEY_BURNIN_RANGE, 8), 0, 30);
    }

    public static void setBurnInRangeDp(Context c, int dp) {
        sp(c).edit().putInt(KEY_BURNIN_RANGE, clamp(dp, 0, 30)).apply();
    }

    /** 漂移间隔（秒）。 */
    public static int getBurnInIntervalSec(Context c) {
        return clamp(sp(c).getInt(KEY_BURNIN_INTERVAL, 60), 15, 300);
    }

    public static void setBurnInIntervalSec(Context c, int sec) {
        sp(c).edit().putInt(KEY_BURNIN_INTERVAL, clamp(sec, 15, 300)).apply();
    }

    /**
     * 解析用户输入的颜色文本，支持这些写法：
     * <pre>
     *   #RRGGBB / RRGGBB      → 不透明
     *   #AARRGGBB             → 带透明度
     *   #RGB / #ARGB          → 缩写，每位翻倍（f0a → ff00aa）
     * </pre>
     *
     * @param def 解析失败时返回的值
     * @return 解析出的 ARGB，失败返回 {@code def}
     */
    public static int parseColor(String raw, int def) {
        if (raw == null) return def;
        String t = raw.trim();
        if (t.length() == 0) return def;
        if (t.charAt(0) == '#') t = t.substring(1);
        t = t.toUpperCase();
        try {
            if (t.length() == 3 || t.length() == 4) {
                // 缩写：每位翻倍
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < t.length(); i++) {
                    sb.append(t.charAt(i)).append(t.charAt(i));
                }
                t = sb.toString();
                if (t.length() == 6) t = "FF" + t;
            } else if (t.length() == 6) {
                t = "FF" + t;
            } else if (t.length() != 8) {
                return def;
            }
            return (int) Long.parseLong(t, 16);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 颜色 → 用户可编辑的文本（{@code #AARRGGBB}）。 */
    public static String colorToText(int argb) {
        return String.format("#%08X", argb);
    }

    /** 菜单圆底是否用浅色（实心白圆 + 深色内容）。默认 true。 */
    public static boolean isMenuLightBubble(Context c) {
        return sp(c).getBoolean(KEY_MENU_LIGHT_BUBBLE, true);
    }

    public static void setMenuLightBubble(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_MENU_LIGHT_BUBBLE, on).apply();
    }

    public static int getMenuHitScale(Context c) {
        return sp(c).getInt(KEY_MENU_HIT_SCALE, 100);
    }

    public static int getMenuGap(Context c) {
        return sp(c).getInt(KEY_MENU_GAP, 0);
    }

    public static void setMenuGap(Context c, int v) {
        sp(c).edit().putInt(KEY_MENU_GAP, Math.max(0, Math.min(60, v))).apply();
    }

    public static int getMenuRingGap(Context c) {
        return sp(c).getInt(KEY_MENU_RING_GAP, 0);
    }

    public static void setMenuRingGap(Context c, int v) {
        sp(c).edit().putInt(KEY_MENU_RING_GAP, Math.max(0, Math.min(80, v))).apply();
    }

    /** 文字标签字号（dp）。默认 12。 */
    public static int getMenuTextDp(Context c) {
        return sp(c).getInt(KEY_MENU_TEXT_DP, 12);
    }

    public static void setMenuTextDp(Context c, int v) {
        sp(c).edit().putInt(KEY_MENU_TEXT_DP, clamp(v, 8, 32)).apply();
    }

    /** 文字离图标底边的距离（dp）。默认 4，0 = 紧贴图标。 */
    public static int getMenuTextGap(Context c) {
        return sp(c).getInt(KEY_MENU_TEXT_GAP, 4);
    }

    public static void setMenuTextGap(Context c, int v) {
        sp(c).edit().putInt(KEY_MENU_TEXT_GAP, clamp(v, 0, 40)).apply();
    }

    public static void setMenuHitScale(Context c, int v) {
        sp(c).edit().putInt(KEY_MENU_HIT_SCALE, Math.max(100, Math.min(300, v))).apply();
    }

    // ---------------- 手柄列表 ----------------

    public static List<Handle> getHandles(Context c) {
        List<Handle> out = new ArrayList<>();
        String raw = sp(c).getString(KEY_HANDLES, null);
        if (raw != null && raw.length() > 0) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    out.add(fromJson(arr.getJSONObject(i)));
                }
            } catch (Throwable t) {
                Log.w(TAG, "手柄配置解析失败，回退默认值: " + t);
            }
        }
        if (out.isEmpty()) {
            out.add(defaultHandle(c));
        }
        return out;
    }

    public static void saveHandles(Context c, List<Handle> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (Handle h : list) {
                try {
                    arr.put(toJson(h));
                } catch (Throwable ignored) {
                }
            }
        }
        sp(c).edit().putString(KEY_HANDLES, arr.toString()).apply();
        refreshRuntime(c);
    }

    public static Handle defaultHandle(Context c) {
        Handle h = new Handle();
        h.id = 1;
        h.name = "右上角（经典）";
        h.edge = 1;
        h.threshold = getDefaultDistance(c);
        return h;
    }

    public static Handle find(Context c, int id) {
        for (Handle h : getHandles(c)) {
            if (h.id == id) return h;
        }
        return null;
    }

    /** 下一个可用的手柄 id。 */
    public static int nextId(Context c) {
        int max = 0;
        for (Handle h : getHandles(c)) {
            if (h.id > max) max = h.id;
        }
        return max + 1;
    }

    // ---------------- JSON 序列化 ----------------

    private static JSONObject toJson(Handle h) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", h.id);
        o.put("name", h.name);
        o.put("edge", h.edge);
        o.put("width", h.width);
        o.put("visibleWidth", h.visibleWidth);
        o.put("height", h.height);
        o.put("offsetPercent", h.offsetPercent);
        o.put("color", h.color);
        o.put("alpha", h.alpha);
        o.put("radius", h.radius);
        o.put("threshold", h.threshold);
        // 只写 0：另外两种时机已下线（见 EVENT_TRIGGER 的注释）
        o.put("event", EVENT_TRIGGER);
        o.put("haptic", h.haptic);
        o.put("blockGesture", h.blockGesture);

        JSONArray en = new JSONArray();
        JSONArray di = new JSONArray();
        for (int i = 0; i < 8; i++) {
            en.put(h.enabled[i]);
            di.put(h.distance[i]);
        }
        o.put("enabled", en);
        o.put("distance", di);
        return o;
    }

    private static Handle fromJson(JSONObject o) {
        Handle h = new Handle();
        h.id = o.optInt("id", 1);
        h.name = o.optString("name", "手柄");
        h.edge = o.optInt("edge", 1);
        h.width = clamp(o.optInt("width", 56), 0, 200);
        h.visibleWidth = clamp(o.optInt("visibleWidth", 8), 2, 80);
        h.height = clamp(o.optInt("height", 150), 40, 600);
        h.offsetPercent = clamp(o.optInt("offsetPercent", 0), 0, 100);
        h.color = o.optInt("color", 0xFF9AA0A6);
        h.alpha = clamp(o.optInt("alpha", 100), 0, 100);
        h.radius = clamp(o.optInt("radius", 12), 0, 100);
        h.threshold = clamp(o.optInt("threshold", 90), 10, 600);
        // ★ 不读回原值：老配置里可能存着 1（按下触发）/ 2（抬手触发），
        //   这里一律归零，升级后行为收敛到「滑过触发」。
        h.event = EVENT_TRIGGER;
        h.haptic = o.optBoolean("haptic", true);
        h.blockGesture = o.optBoolean("blockGesture", true);

        // 可见宽度不能比总宽度还大，否则手柄会横着糊出屏幕。
        if (h.visibleWidth > h.width) h.visibleWidth = h.width;

        JSONArray en = o.optJSONArray("enabled");
        JSONArray di = o.optJSONArray("distance");
        boolean any = false;
        for (int i = 0; i < 8; i++) {
            boolean v = en != null && en.optBoolean(i, false);
            h.enabled[i] = v;
            if (v) any = true;
            h.distance[i] = di == null ? 0 : clamp(di.optInt(i, 0), 0, 600);
        }
        // 一个方向都没开的手柄等于摆设，兜底打开左右。
        if (!any) {
            h.enabled[DIR_LEFT] = true;
            h.enabled[DIR_RIGHT] = true;
        }
        return h;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ============================================================
    //  运行时状态（进程内静态字段，插件直接读，不走 IPC）
    // ============================================================

    /**
     * 最近一次手势。
     *
     * 这些字段是 volatile 的：悬浮窗服务在主线程写，插件的广播接收器
     * 可能在别的线程读；虽然实践上几乎同时刻，但保守起见仍然保证可见性。
     */
    public static volatile String gsLastDirection = "";
    public static volatile String gsLastDirectionLabel = "";
    public static volatile String gsLastHandle = "";
    public static volatile String gsLastEvent = "";
    public static volatile long gsLastAt = 0L;

    /** 服务是否在跑 / 当前有几个手柄。 */
    public static volatile boolean gsRunning = false;
    public static volatile int gsHandleCount = 0;

    /**
     * 手势序号与「事件脉冲」。
     *
     * 用途：让宏能判断“这次触发是不是刚刚发生的”。
     * 序号每触发一次 +1。
     *
     * ⚠ 这个序号**不再作为变量回传**。
     * 它现在只在进程内用于「事件脉冲」判定（gsPulseEvent / gsPulseDir），
     * 也就是插件自己决定要不要响应某次查询。
     */
    public static volatile int gsSeq = 0;
    public static volatile int gsPulseEvent = -1;
    public static volatile int gsPulseDir = -1;

    /**
     * 最近一次菜单选择结果。
     *
     * 与 gsLast* 同样放在进程内静态字段，理由也一样：
     * 悬浮窗服务在主线程写，插件广播接收器可能在别的线程读，
     * 走静态字段零成本、不受广播异步时序影响。
     *
     * menuIndex 为 -1 表示「滑到空白处松手 / 选了设置项」，
     * 宏侧应当把它当作「取消」，不要执行任何动作。
     */
    public static volatile int menuIndex = -1;
    public static volatile String menuText = "";
    public static volatile String menuAction = "";

    /** 记一次菜单选择（-1 表示取消）。 */
    public static void recordMenuChoice(int index, String text, String action) {
        menuIndex = index;
        menuText = text == null ? "" : text;
        menuAction = action == null ? "" : action;
    }

    /**
     * 把「要监听的方向」同步到**所有手柄**的可触发方向里。
     *
     * ── 为什么需要它 ─────────────────────────────────────
     * MacroDroid / Tasker 的触发器里用户已经选过一次方向了（"向上"、"向右"…）。
     * 但手势的生效判定在服务里是 {@code handle.enabled[dir]} ——
     * 方向没在手柄里打开，滑了也不触发。于是用户配完触发器还得回到
     * 手柄编辑页把同一个方向再勾一遍，两处配置割裂、极易漏掉。
     * 本方法就是把触发器选的那个方向直接落到手柄上。
     *
     * ── 为什么是「只打开、不关闭」 ───────────────────────
     * 触发器表达的只是"我要监听 X"，它**没有资格**关掉用户另外开的方向。
     * 否则配第二个宏（比如监听"向右"）就会把第一个宏依赖的"向上"关掉，
     * 表现为"先配的宏突然不灵了"。
     * 想收窄方向请到手柄编辑页手动关 —— 那里能看到每个方向的开关状态。
     *
     * @param dirKey 方向名（{@link #DIR_KEYS} 里的值）；
     *               空串或 null = 任意方向，八个方向全部打开
     * @return 真正被改动的方向个数（0 = 无需改动）
     */
    public static int enableDirectionsForAllHandles(Context c, String dirKey) {
        try {
            List<Handle> all = getHandles(c);
            if (all == null || all.isEmpty()) return 0;

            // 算出这次要打开哪些方向
            boolean[] want = new boolean[DIR_KEYS.length];
            boolean any = (dirKey == null || dirKey.isEmpty());
            if (any) {
                for (int i = 0; i < want.length; i++) want[i] = true;
            } else {
                for (int i = 0; i < DIR_KEYS.length; i++) {
                    if (DIR_KEYS[i].equals(dirKey)) {
                        want[i] = true;
                        break;
                    }
                }
                // 方向名不认识 —— 不动任何东西，避免误开全方向
                boolean matched = false;
                for (boolean b : want) if (b) matched = true;
                if (!matched) return 0;
            }

            int changed = 0;
            for (Handle h : all) {
                for (int i = 0; i < DIR_KEYS.length; i++) {
                    if (want[i] && !h.enabled[i]) {
                        h.enabled[i] = true;
                        changed++;
                    }
                }
            }
            if (changed > 0) {
                saveHandles(c, all);
            }
            return changed;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 每触发一次就刷新一次运行时快照。 */
    public static void refreshRuntime(Context c) {
        try {
            gsHandleCount = getHandles(c).size();
        } catch (Throwable ignored) {
        }
    }

    /** 记一次手势，并返回自增后的序号。 */
    public static int recordGesture(Context c, Handle h, int dir, int event) {
        gsSeq++;
        gsPulseDir = dir;
        gsPulseEvent = event;
        gsLastDirection = (dir >= 0 && dir < 8) ? DIR_KEYS[dir] : "";
        gsLastDirectionLabel = (dir >= 0 && dir < 8) ? DIR_LABELS[dir] : "";
        gsLastHandle = h == null ? "" : h.name;
        gsLastEvent = (event >= 0 && event < EVENT_KEYS.length) ? EVENT_KEYS[event] : "";
        gsLastAt = System.currentTimeMillis();
        return gsSeq;
    }

    // ============================================================
    //  小工具
    // ============================================================

    /** 短震动反馈（没权限 / 没马达就静默跳过）。 */
    public static void buzz(Context c, boolean strong) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            int ms = strong ? 45 : 18;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Throwable ignored) {
        }
    }
}