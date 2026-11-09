package com.xiaosun.ocr;

/**
 * 全应用唯一的色板。
 *
 * <h3>为什么要有这个类</h3>
 * 2.9 之前，同一套颜色在 8~9 个 Activity 里各写一遍，累计 79 个常量定义。
 * 后果不是「看着啰嗦」，而是真的出过故障：
 * <ul>
 *   <li>2.6 统一顶栏时，照着 {@code A11yActivity} 的命名写 {@code C_TEXT_SUB}，
 *       但 {@code EdgeGesture*} 三个页面的副标题灰实际叫 {@code C_SUB}，
 *       真实构建直接 {@code cannot find symbol} 失败；</li>
 *   <li>同一时刻 {@code C_DANGER} 在两处是 {@code 0xFFEF4444}、
 *       另三处是 {@code 0xFFF04438}，两个不同的红，谁也没发现；</li>
 *   <li>同一时刻 {@code borderedBg} 在 {@code EdgeGestureActivity} 用
 *       {@code dp(1)}、在别处用 {@code dp(1f)}，描边宽度靠 float→int 的隐式截断兜着。</li>
 * </ul>
 * 这些都是「同一件事写了多份」的自然结果，不是谁手滑。所以现在只剩一份。
 *
 * <h3>怎么用</h3>
 * 各页直接 {@code import static com.xiaosun.ocr.Palette.*;}，
 * 然后在代码里照旧写 {@code C_BG} —— 调用点一个字都不用改。
 *
 * <h3>颜色为什么是这些</h3>
 * 全部取值来自 2.6 定稿的浅色方案，未做任何视觉调整。唯一的变化是
 * {@code C_DANGER} 统一取 {@code 0xFFEF4444}：原先 {@code EdgeGestureEditorActivity}
 * 用的是 {@code 0xFFF04438}，两者仅差一点蓝通道（0x44 vs 0x38），
 * 肉眼无法分辨，统一后不再存在「两处红不同」的隐患。
 */
final class Palette {

    private Palette() {
    }

    /** 主色：图标、选中态、强调数字。 */
    static final int C_BRAND = 0xFF4F6BED;

    /** 主色的浅底：图标方块背景、软标签底色。 */
    static final int C_BRAND_SOFT = 0xFFEDF0FE;

    /** 页面底色。所有顶栏与状态栏也用这个色，两者同色才看不出分界。 */
    static final int C_BG = 0xFFF5F6FA;

    /** 卡片底色。 */
    static final int C_SURFACE = 0xFFFFFFFF;

    /** 分隔线 / 卡片描边。 */
    static final int C_DIVIDER = 0xFFEAECF2;

    /** 正文主色。 */
    static final int C_TEXT = 0xFF1A1D26;

    /**
     * 副标题灰。
     *
     * <p>这个名字曾经分裂成 {@code C_SUB}（7 个文件）与 {@code C_TEXT_SUB}（2 个文件），
     * 是 2.6 构建失败的根因。现在统一为 {@code C_SUB}。
     */
    static final int C_SUB = 0xFF6B7280;

    /** 提示灰：比副标题更弱，用于占位符与次要说明。 */
    static final int C_HINT = 0xFF9CA3AF;

    /** 正常 / 就绪。 */
    static final int C_OK = 0xFF12B76A;

    /** 警告。进度条达到 60%~85% 时用它。 */
    static final int C_WARN = 0xFFF79009;

    /** 危险。进度条接近满、温度过高、电量过低时用它。 */
    static final int C_DANGER = 0xFFEF4444;

    /** 进度条的未填充轨道。 */
    static final int C_TRACK = 0xFFE8EBF4;

    /**
     * 深色代码块底色。
     *
     * <p>文字识别页用它显示「坐标 / JSON」那类需要等宽阅读的结果 ——
     * 深底浅字比浅底深字更容易分辨 `{` `}` `,` 这类符号。
     */
    static final int C_CODE_BG = 0xFF1E2029;

    /** 深色代码块里的文字色（与 {@link #C_CODE_BG} 配对使用）。 */
    static final int C_CODE_TEXT = 0xFFC8D0E0;
}