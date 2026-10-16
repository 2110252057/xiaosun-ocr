package com.xiaosun.ocr;

/**
 * Tasker/Locale 插件协议常量。
 *
 * 为什么手写而不是引入库：
 *   - 官方的 android-plugin-api-for-locale 已于 2021 年归档
 *   - Tasker 的 TaskerPlugin.java 需要整份拷贝进项目（~1000 行，我们只用几个常量）
 *   - 这些值本质上是**字符串常量**，内联即可，零依赖、零体积
 *
 * 协议来源：
 *   - Locale 插件规范（twofortyfouram/android-plugin-api-for-locale）
 *   - Tasker 扩展（tasker.joaoapps.com/plugins.html）
 *
 * MacroDroid 兼容这套规范：App 里只要声明了对应的 Activity / BroadcastReceiver，
 * 就会出现在「Tasker/Locale Plugin」动作的插件列表里。
 */
public final class PluginContract {

    private PluginContract() {}

    // ==================== Locale 基础协议 ====================

    /** 宿主打开插件配置界面（设置型插件）。 */
    public static final String ACTION_EDIT_SETTING =
            "com.twofortyfouram.locale.intent.action.EDIT_SETTING";

    /** 宿主触发插件执行（设置型插件）。 */
    public static final String ACTION_FIRE_SETTING =
            "com.twofortyfouram.locale.intent.action.FIRE_SETTING";

    /** 配置结果：Bundle，承载插件全部配置状态。 */
    public static final String EXTRA_BUNDLE =
            "com.twofortyfouram.locale.intent.extra.BUNDLE";

    /** 配置结果：String，显示在宏里的一行摘要。 */
    public static final String EXTRA_STRING_BLURB =
            "com.twofortyfouram.locale.intent.extra.BLURB";

    // ==================== Tasker 扩展 ====================

    /** Tasker 的 extras 命名空间。 */
    private static final String TASKER_BASE = "net.dinglisch.android.tasker";
    private static final String TASKER_EXTRAS = TASKER_BASE + ".extras.";

    /** 宿主能力位图（Integer）。 */
    public static final String EXTRA_HOST_CAPABILITIES =
            TASKER_EXTRAS + "HOST_CAPABILITIES";

    /**
     * 宿主是否支持「设置型插件返回变量」。
     *
     * 这是把识别结果回传给宏的关键能力位。
     * 值来自 TaskerPlugin.EXTRA_HOST_CAPABILITY_SETTING_RETURN_VARIABLES。
     */
    public static final int CAPABILITY_SETTING_RETURN_VARIABLES = 2;

    /**
     * 插件把要回传的变量放在这个键下的 Bundle 里。
     *
     * 【为什么同时有两个键】
     *
     * 这是「事件触发器变量为空」那个 bug 的修复点之一。
     *
     * 键名来自 Tasker 官方规范（net.dinglisch.android.tasker.extras.VARIABLES），
     * 已验证的样板是 AutoInput（com.joaomgcd.autoinput）——
     * 它在 TaskerPlugin.smali 里定义了同一个常量：
     *     EXTRA_VARIABLES_BUNDLE = "net.dinglisch.android.tasker.extras.VARIABLES"
     * 两条回传路线用的都是这一个键：
     *     · 有序广播路线：getResultExtras(true).putBundle(VARIABLES, vars)
     *       （见 BroadcastReceiverTasker.setVariableBundleAsync）
     *     · ResultReceiver 路线：receiver.send(code, bundle)
     *       （见 IntentServiceQuery.getResultCodeFromConditionSatisfied）
     *
     * ⚠ 别在这里"多写几个候选键以防万一"：
     *   变量键只有这一个，写同名的第二遍只是覆盖，写不同名的宿主也不认，
     *   徒增误解。真正的兼容点在**回传通道**上（见类注释的
     *   「变量回传」一节），不在键名上。
     */
    public static final String EXTRA_VARIABLES_BUNDLE =
            TASKER_EXTRAS + "VARIABLES";

    // ==================== 异步「补结果」机制（关键！） ====================
    //
    // 【为什么必须用它】
    // 宿主发完有序广播后只等 11 秒（见 Setting.fire 里的 latch.await(0x2af8)）。
    // 超时它就降级重发普通广播并直接 return，结果一律丢弃。
    // OCR 动辄十几秒，走「同步回传」必然赶不上。
    //
    // 【解法】
    // 宿主在触发插件时，会额外塞一个 COMPLETION_INTENT —— 本质是一个
    // 序列化成 URI 的 Intent，相当于「回调地址」。插件可以：
    //   1) 先 setResultCode(3 /* PENDING */) 告诉宿主"我在处理，别放弃"
    //   2) goAsync() 继续慢慢干活
    //   3) 干完后往 COMPLETION_INTENT 发广播，把变量补送过去
    //
    // MacroDroid 侧由 com.arlosoft.macrodroid.action.receivers
    // .LocaleTaskerSettingCompleteReceiver 接收，读 extras.VARIABLES
    // 并按 action_id 找到对应动作写入变量。
    //
    // 参考实现：PP-OCRv6 插件（本机实测可正常传变量），
    // 其 OcrActionReceiver 正是用这套流程。

    /** 宿主给的「回调地址」（URI 字符串形式的 Intent）。 */
    public static final String EXTRA_COMPLETION_INTENT =
            TASKER_EXTRAS + "COMPLETION_INTENT";

    /** 补结果时携带的执行结果码。 */
    public static final String EXTRA_RESULT_CODE =
            TASKER_EXTRAS + "RESULT_CODE";

    /** 宿主可能指定用 Service（而不是广播）来接收补结果。 */
    public static final String EXTRA_CALL_SERVICE =
            TASKER_BASE + ".EXTRA_CALL_SERVICE";
    public static final String EXTRA_CALL_SERVICE_PACKAGE =
            TASKER_BASE + ".EXTRA_CALL_SERVICE_PACKAGE";
    public static final String EXTRA_CALL_SERVICE_FOREGROUND =
            TASKER_BASE + ".EXTRA_CALL_SERVICE_FOREGROUND";

    /**
     * 宿主在查询 Intent 里塞的 {@code ResultReceiver}，用来收回变量。
     *
     * ── 这个键是「事件触发器变量为空」的关键线索 ─────────────
     *
     * 值 = net.dinglisch.android.tasker.EXTRA_RESULT_RECEIVER
     *
     * 来自已验证样板 AutoInput（com.joaomgcd.autoinput）的
     * com/joaomgcd/common/tasker/TaskerPlugin$Condition.smali：
     *     EXTRA_RESULT_RECEIVER = "net.dinglisch.android.tasker.EXTRA_RESULT_RECEIVER"
     *     getResultReceiver(intent) → intent.getParcelableExtra(该键)
     *
     * 它的 IntentServiceQuery 在应答时这样回变量：
     *     ResultReceiver rr = TaskerPlugin.Condition.getResultReceiver(intent);
     *     Bundle out = new Bundle();
     *     ... 算出结果码，并把变量写进 out ...
     *     rr.send(resultCode, out);        // ← 变量随 ResultReceiver 送回宿主
     *
     * ⚠ 这条路径与「有序广播 setResultExtras」是**两条独立通道**：
     *    · 宿主用 sendOrderedBroadcast 发的 → 走 setResultExtras
     *    · 宿主放了这个 ResultReceiver → 必须 rr.send()，setResultExtras 白写
     *   我们已经把两条都做了，所以无论 MacroDroid 用哪条都能收到变量。
     *
     * 注意 Tasker 把结果码放在 send() 的第一个参数（而不是 Bundle 里），
     * 所以 send() 的第一个参数就用 Locale 的 16/17/18。
     */
    public static final String EXTRA_RESULT_RECEIVER =
            TASKER_BASE + ".EXTRA_RESULT_RECEIVER";

    // 结果码，取自 TaskerPlugin.Setting
    /** 成功。 */
    public static final int RESULT_CODE_OK = -1;
    /** 有小的失败但整体可用。 */
    public static final int RESULT_CODE_OK_MINOR_FAILURES = 1;
    /** 失败。 */
    public static final int RESULT_CODE_FAILED = 2;
    /** ★「还在处理中」—— 回这个码，宿主就不会按超时处理。 */
    public static final int RESULT_CODE_PENDING = 3;

    /**
     * 插件「预告」自己会产出哪些变量，放在这个 extra 里。
     *
     * 注意：光在 FireReceiver 里回传变量**不够**！
     * 宿主需要先在配置阶段（EditActivity）被告知变量名，
     * 才能在宏里创建/映射这些变量。少了这一步，回传的值无处安放，
     * 表现为"变量没传回来"。
     *
     * 值 = net.dinglisch.android.tasker.RELEVANT_VARIABLES
     * （注意不带 .extras.，这是 TaskerPlugin 里的 BUNDLE_KEY_RELEVANT_VARIABLES）
     */
    public static final String EXTRA_RELEVANT_VARIABLES =
            TASKER_BASE + ".RELEVANT_VARIABLES";

    /** 宿主是否支持「相关变量预告」。能力位来自 EXTRA_HOST_CAPABILITY_RELEVANT_VARIABLES。 */
    public static final int CAPABILITY_RELEVANT_VARIABLES = 16;

    /** 变量名前缀（Tasker 用 % 开头）。 */
    public static final String VARIABLE_PREFIX = "%";

    // ==================== 我们自己的 Bundle key ====================

    /** 图片来源：截屏 / 路径 / 变量。 */
    public static final String KEY_SOURCE = "source";
    /** 图片路径（source=path 时用）。 */
    public static final String KEY_PATH = "path";
    /** 档位：tiny / small / rapid。 */
    public static final String KEY_TIER = "tier";
    /** 输出格式：1=纯文本 2=含坐标。 */
    public static final String KEY_TYPE = "type";
    /** 是否用换行连接多行文本（默认是）。 */
    public static final String KEY_JOIN_NEWLINE = "joinNewline";

    /**
     * 指定文本（可选）。
     *
     * 填了之后，会在识别结果里查找匹配的文本块：
     *   匹配文本     true / false
     *   坐标X/Y      命中的文本块坐标（左上角，整数）
     * 没填则「是否找到」为 false，坐标固定 -1。
     */
    public static final String KEY_MATCH_TEXT = "matchText";

    /**
     * 匹配方式：包含 / 完全相等。
     *
     *   MODE_CONTAINS  包含 —— 文本块内容含指定文本即算命中（默认）
     *   MODE_EXACT     完全 —— 文本块内容与指定文本完全一致才命中
     *
     * 对应界面上的「包含文本」/「匹配文本」两个选项。
     */
    public static final String KEY_MATCH_MODE = "matchMode";
    public static final int MODE_CONTAINS = 0;
    public static final int MODE_EXACT = 1;

    /** 来源取值。 */
    public static final String SOURCE_SCREENSHOT = "screenshot";
    public static final String SOURCE_PATH = "path";

    // ==================== 回传的变量名 ====================

    /** 识别出的全部文本。 */
    public static final String VAR_TEXT = VARIABLE_PREFIX + "xsocr_text";
    /** 指定文本是否存在（true/false）。 */
    public static final String VAR_MATCH = VARIABLE_PREFIX + "xsocr_match";
    /** 指定文本命中处的 x 坐标（左上角）。 */
    public static final String VAR_X = VARIABLE_PREFIX + "xsocr_x";
    /** 指定文本命中处的 y 坐标（左上角）。 */
    public static final String VAR_Y = VARIABLE_PREFIX + "xsocr_y";
    /** 出错信息（失败时才有内容）。 */
    public static final String VAR_ERROR = VARIABLE_PREFIX + "xsocr_error";
    /** 耗时（毫秒）。 */
    public static final String VAR_TIME = VARIABLE_PREFIX + "xsocr_time";
    /** 使用的档位。 */
    public static final String VAR_TIER = VARIABLE_PREFIX + "xsocr_tier";

    // ---------- 滑动菜单的选中结果（由 EdgeGestureService 提供） ----------
    //
    // 【为什么只留这三个、手势本身的变量全删了】
    //
    // 手势方向 / 序号 / 时间 / 手柄这些原先也回传，但它们有两个问题：
    //   · 用户在 MacroDroid 里本来就自己建变量接值，名字由用户定，
    //     我们这边硬编码一串名字只是"建议"，反而像是强制的；
    //   · 判断"是不是我要的那个方向"这件事，插件在收到查询时已经
    //     用配置里的方向比过了（不匹配就直接回 17、宏根本不跑），
    //     宏再自己判断一遍是重复劳动。
    //
    // 而菜单这三项不一样：它们是**用户的输入**，宏不可能自己知道
    // 用户点了哪一项，必须由插件告诉它。所以只留这三项。
    //
    // 内部的方向匹配逻辑（EdgeGesturePrefs.gsLastDirection 等）
    // 仍然保留 —— 那是插件自己用来决定"要不要触发"的，不对外。
    // ==================== 触发器（Tasker 事件）协议 ====================
    //
    // 宏的「触发器」列表要的是 **事件** 协议，跟「动作」是两套东西：
    //
    //   动作 (Setting)：EDIT_SETTING   → 配置界面
    //                   FIRE_SETTING   → 执行（见 PluginFireReceiver）
    //   事件 (Event)：   ACTION_EDIT_EVENT → 配置界面（Tasker 扩展）
    //                    插件主动发 REQUEST_QUERY 告诉宿主「刚发生了」
    //                    QUERY_CONDITION   宿主回头来问「真的吗」
    //
    // ★ 关键：宿主默认**不知道**事件什么时候发生。
    //   是插件在事情发生的那一刻，主动广播一条 REQUEST_QUERY 出去，
    //   宿主收到后回头用 QUERY_CONDITION 来问插件，插件回 16/17 表示成立与否。
    //   这就是「边缘滑动 → 立刻让宏跑起来」的全部机制。
    //
    // ★ 本插件不提供**条件（Condition / EDIT_CONDITION）身份**。
    //   条件与事件的配置界面、功能完全一样，唯一差别是事件携带防伪数据
    //   （随机 message id + 60 秒有效期），条件只能读进程内的
    //   「最近一次手势」快照、理论上可被伪造广播骗过。
    //   两个入口并列只会让人挑错，故只保留事件。
    //   （历史的 MODE_STATE 分支与 PluginEditActivity 上的
    //     EDIT_CONDITION intent-filter 已一并删除。）
    //
    // 参照实现：com.dxam.presencedetector（人在检测插件）
    //   · TaskerEventConfigActivity  —— activity-alias，label 为「Tasker 事件」名
    //   · LocaleConditionReceiver    —— 响应 QUERY_CONDITION，回 16/17/18
    //   · PresenceRepository.publish —— 状态变化时广播 REQUEST_QUERY
    /** Tasker 专用：宿主打开「事件」配置界面。 */
    public static final String ACTION_EDIT_EVENT =
            TASKER_BASE + ".ACTION_EDIT_EVENT";
    /** 宿主询问条件当前是否成立（有序广播，插件用 setResultCode 回答）。 */
    public static final String ACTION_QUERY_CONDITION =
            "com.twofortyfouram.locale.intent.action.QUERY_CONDITION";
    /**
     * ★ 插件主动上报：「我这边有事发生了，快来问我」。
     *
     * 插件在事件发生瞬间发这个隐式广播，并带上
     * {@link #EXTRA_ACTIVITY} 指明是哪个组件（即"哪个配置实例"）发生了事。
     * 宿主随后会对该组件发 QUERY_CONDITION。
     */
    public static final String ACTION_REQUEST_QUERY =
            "com.twofortyfouram.locale.intent.action.REQUEST_QUERY";
    /** 上报时用来指明「是哪个组件的实例」，值 = 组件全限定类名。 */
    public static final String EXTRA_ACTIVITY =
            "com.twofortyfouram.locale.intent.extra.ACTIVITY";
    // 条件查询结果码（沿用 Locale 规范的三个值）
    /** 条件成立。 */
    public static final int RESULT_CONDITION_SATISFIED = 16;
    /** 条件不成立。 */
    public static final int RESULT_CONDITION_UNSATISFIED = 17;
    /** 说不清（插件没初始化 / 配置缺失 / 消息过期）。 */
    public static final int RESULT_CONDITION_UNKNOWN = 18;
    // ==================== Tasker 事件防伪 ====================
    //
    // 光靠 REQUEST_QUERY 有个漏洞：任何 App 都能伪造这条广播，
    // 于是宏会在用户没滑屏的时候被触发。
    //
    // 解法（照抄原版 TaskerEventProtocol）：
    //   上报时附带一个**随机 message id** + 我们对它的签发时间；
    //   宿主来查询时把这个 id 原样带回来，我们核对：
    //     ① id 必须是我们签发过的（存在表里）
    //     ② 签发时间距今不超过 60 秒
    //   两条都满足才认，于是重放/伪造都过不了。
    /** 上报与查询之间传递业务数据（含 message id）的 Bundle。 */
    public static final String EXTRA_PASS_THROUGH_DATA =
            TASKER_EXTRAS + "PASS_THROUGH_DATA";
    /** 防伪用的随机消息号（Integer，放在 PASS_THROUGH_DATA 里）。 */
    public static final String EXTRA_MESSAGE_ID = TASKER_BASE + ".MESSAGE_ID";
    /** 消息有效期：60 秒，超时视为伪造。 */
    public static final long MESSAGE_VALID_MS = 60000L;
    /** 防重放表最多记 100 条。 */
    public static final int MAX_RECENT_MESSAGES = 100;
    // ==================== 我们自己的 Bundle key（条件/事件共用） ====================
    /** 这条配置属于「动作 / 条件 / 事件」。 */
    public static final String KEY_PLUGIN_MODE = "plugin_mode";
    /** 动作（Setting）：识别屏幕文字。 */
    public static final String MODE_SETTING = "setting";
    /** 条件（Condition）：等某个手势。★ 已废弃，仅为兼容老配置而保留字段。 */
    public static final String MODE_STATE = "state";
    /** Tasker 事件（Event）：等某个手势，额外做防伪校验。 */
    public static final String MODE_EVENT = "event";
    /**
     * 事件模式下：监听哪个方向。
     *
     * 空字符串 = 任意方向（8 个方向都认）。
     * 否则取 {@code EdgeGesturePrefs.DIR_KEYS} 里的值，如 "right"。
     */
    public static final String KEY_GS_DIR = "gs_dir";
    /** 事件模式下：是否校验消息新鲜度（默认 true，防重放）。 */
    public static final String KEY_GS_STRICT = "gs_strict";

    // ==================== 手势菜单（宏 → 插件 推配置） ====================
    //
    // 【为什么需要这条通道】
    // 「滑过阈值 → 菜单弹出 → 手指不离开 → 滑到图标上 → 抬手即选中」
    // 这个交互，**弹菜单这件事必须由插件自己做**，原因在输入分发机制：
    //
    //   手指 ACTION_DOWN 那一刻，系统就把整条触摸序列绑定给了当时命中的
    //   那个窗口。此后直到抬手，所有 MOVE / UP 只投递给它 —— 中途新弹出来
    //   的窗口一个事件都收不到。
    //
    //   所以「插件探到手势 → 广播叫宏弹窗 → 宏新建一个窗口」这条路走不通：
    //   宏新建的窗口收不到 MOVE（无法跟手高亮），也收不到 UP（无法判定选中，
    //   菜单还会永远关不掉）。而且宏侧的一次性 Java 动作根本没法常驻等事件。
    //
    // 【正确做法：同一个窗口原地长大】
    //   插件把**已经被绑定的那个手柄窗口**用 updateViewLayout 改成全屏，
    //   再自绘菜单。窗口 token 不变 → 触摸序列不中断 → MOVE/UP 照收。
    //   全程没有任何跨窗口转交，也就没有上面那个问题。
    //
    //   因此职责划分是：
    //     宏   ── 只负责「菜单长什么样」（推配置），以及「选中后干什么」
    //     插件 ── 弹菜单、跟手高亮、判定选中，最后把索引回传
    //
    // 【时序】
    //   滑过阈值 → 插件展开菜单（此时**不发**广播，宏还不知道）
    //   手指滑动 → 跟手高亮
    //   抬手     → 插件记录选中项，**此时才**发 REQUEST_QUERY 触发宏
    //   宏读菜单索引变量分支执行
    //
    //   所以宏那边感知到触发的时刻，与手指抬起的时刻是一致的 ——
    //   这就是为什么宏里可以紧接着就执行动作，不存在「手指还按着」的问题。

    // 这里曾经还有一条「向下配置」通道：
    //   ACTION_MENU_CONFIG = "com.xiaosun.ocr.action.MENU_CONFIG"
    // 以及配套的 EXTRA_MENU_ENABLED / EXTRA_MENU_ITEMS /
    // EXTRA_MENU_ICON_DP / EXTRA_MENU_SHOW_TEXT / EXTRA_MENU_LAYOUT。
    // 用途是让宏把菜单定义推给插件。
    //
    // 已整体撤除，理由：
    //   · 菜单现在在本应用自己的设置页里配（EdgeGestureMenuActivity），
    //     有图标预览、可排序、能选图片 —— 比在宏里拼字符串可靠得多；
    //   · 让宏去描述「菜单长什么样」属于职责错位：宏擅长的是
    //     「发生了就做什么」，不是配置界面；
    //   · 少一条 exported 的写配置入口，攻击面也小一些。
    // 于是协议只剩「向上回传结果」这一个方向，见下。

    // ==================== 手势菜单（插件 → 宏 回传结果） ====================

    /**
     * 选中项索引（从 0 开始）。
     *
     * ★ 「没选中」时这个变量**根本不会出现** —— 见 {@link #buildMenuVars()}：
     *   菜单弹出来了、但手指滑到空白处松手（或这批触摸被系统抢走），
     *   三个菜单变量一个都不传。宏侧判「有没有收到」即可，
     *   不必再去比 -1 这种魔法值。
     */
    public static final String VAR_MENU_INDEX = VARIABLE_PREFIX + "xsocr_menu_index";
    /** 选中项的显示文字（没选中时不会出现这个变量）。 */
    public static final String VAR_MENU_TEXT = VARIABLE_PREFIX + "xsocr_menu_text";
    /** 选中项的 source / 动作串（没选中时不会出现这个变量）。 */
    public static final String VAR_MENU_ACTION = VARIABLE_PREFIX + "xsocr_menu_action";

    // ==================== 工具方法 ====================

    /** 宿主是否声明支持「返回变量」。 */
    public static boolean hostSupportsVariableReturn(android.os.Bundle extrasFromHost) {
        if (extrasFromHost == null) return false;
        Object flags = extrasFromHost.get(EXTRA_HOST_CAPABILITIES);
        if (!(flags instanceof Integer)) return false;
        return ((Integer) flags & CAPABILITY_SETTING_RETURN_VARIABLES) > 0;
    }

    /**
     * 组装要回传给宏的变量。
     *
     * 内容只有**滑动菜单的选中结果**三项：这是用户的输入，
     * 宏不可能自己知道用户点了哪一项。
     *
     * （手势方向 / 序号 / 时间 / 手柄已不再回传：方向匹配由插件
     *   在应答查询时就做了，不匹配的宏根本不会被触发，宏侧无需再判；
     *   其余几项属于调试信息，宏里用不到。用户在 MacroDroid 里
     *   自行建变量接值，名字由用户定。）
     *
     * ── 谁在用 ──────────────────────────────────
     * 现在只有事件触发器这一条路（PluginConditionReceiver 应答
     * QUERY_CONDITION 时调用）。动作路径（PluginFireReceiver）
     * 是纯识别，不再回传任何手势/菜单上下文 —— 菜单选中项是
     * 「触发器」场景的产物，跟「宏主动调一次 OCR」无关。
     *
     * 数据源是 EdgeGesturePrefs 的静态快照（抬手那一刻记下的），
     * 不是实时状态。
     *
     * ★ 「没选中」时返回**空 Bundle** —— 三个变量一个都不传。
     *
     *   什么叫没选中：
     *     · 菜单弹出来了，但手指滑到空白处松手（menuIndex == -1）；
     *     · 这批触摸被系统抢走（ACTION_CANCEL），压根没抬手；
     *     · 菜单没启用（滑过距离时走的是直接触发，不会有菜单结果）。
     *
     *   为什么不是传 "-1/空/空"：那样宏侧必须记住"-1 是个特殊值"，
     *   而且分不清「这次没选中」和「上次选中、这次没刷新」——
     *   宿主对没出现在 Bundle 里的变量通常保留上一次的值，
     *   于是宏会拿上一次的旧结果接着跑。不传变量，宏用
     *   「有没有收到 %xsocr_menu_index」判断，语义最干净。
     *
     * 全程 try/catch：变量缺失不该让整个查询应答失败。
     */
    public static android.os.Bundle buildMenuVars() {
        android.os.Bundle vars = new android.os.Bundle();
        try {
            // 没选中 → 什么都不回（连 index 都不出现）
            if (EdgeGesturePrefs.menuIndex < 0) {
                return vars;
            }
            // ★ 用字符串回传，不能用 putInt —— 见 putIntAsString() 的注释：
            //   Tasker 的变量协议只认 String / String[] / ArrayList<String>，
            //   回 Integer 会被它丢弃，表现为「菜单索引传不过去」。
            putIntAsString(vars, VAR_MENU_INDEX, EdgeGesturePrefs.menuIndex);
            String mtext = EdgeGesturePrefs.menuText;
            vars.putString(VAR_MENU_TEXT, mtext == null ? "" : mtext);
            String mact = EdgeGesturePrefs.menuAction;
            vars.putString(VAR_MENU_ACTION, mact == null ? "" : mact);
        } catch (Throwable t) {
            android.util.Log.w("XSOCR-Plugin", "组装回传变量失败: " + t);
        }
        return vars;
    }

    /**
     * 把变量 Bundle 放进广播结果里，宿主会去读它。
     *
     * 【为什么必须 getResultExtras(true) 传 true】
     * 拿到的是宿主那一侧的**同一个实例**，往里 put 才会被读到；
     * 自己 new 一个 Bundle 是另一份对象，写了也传不回去。
     * （AutoInput 的 setVariableBundleAsync 正是这么做的。）
     */
    public static void addVariableBundle(android.os.Bundle resultExtras,
                                         android.os.Bundle variables) {
        if (resultExtras == null || variables == null) return;
        resultExtras.putBundle(EXTRA_VARIABLES_BUNDLE, variables);
    }

    /**
     * 在「配置界面」里预告本插件会产出哪些变量。
     *
     * ★ 这一步很关键：宿主要先知道变量名，才会在动作配置里
     *   显示 "Output variables" 选项供用户映射到宏变量。
     *   只在 FireReceiver 里回传变量是不够的。
     *
     * 注意：**不按能力位判断是否发送**。
     * MacroDroid 实测并不设置 RELEVANT_VARIABLES 能力位，
     * 但它的 wiki 明确说有 "Output variables" 功能。
     * 所以这里无条件把变量名塞进结果 Intent，宿主不认也无害。
     */
    public static void addRelevantVariableList(android.content.Intent intentToHost) {
        if (intentToHost == null) return;
        intentToHost.putExtra(EXTRA_RELEVANT_VARIABLES, ALL_VARIABLES);
    }

    /**
     * 我们产出的全部变量，按 Tasker 的**三段式**格式声明：
     *     变量名\n标签\n描述
     *
     * 参考 PP-OCRv6 插件（本机实测能正常传变量到 MacroDroid），
     * 它的 ActionEditActivity 正是这样拼的：
     *     sb.append("%ocr_full_text\n").append(标签).append("\n").append(描述)
     *
     * 变量名规范（TaskerPlugin.variableNameValid）：
     *   必须全小写、`%` 开头、只含字母数字下划线。
     */
    public static final String[] ALL_VARIABLES = {
            VAR_TEXT  + "\n识别文本\n识别出的全部文字（多行用换行连接）",
            VAR_MATCH + "\n是否找到\n指定的文本是否存在（true / false）",
            VAR_X     + "\n坐标X\n指定文本命中处的 x 坐标（整数）",
            VAR_Y     + "\n坐标Y\n指定文本命中处的 y 坐标（整数）",
            VAR_ERROR + "\n错误信息\n失败时才有内容",
            VAR_TIME  + "\n耗时(ms)\n本次识别耗时",
            VAR_TIER  + "\n模型档位\ntiny / small / rapid",
    };

    /**
     * 「触发器」模式下的变量声明（只含菜单结果）。
     *
     * 触发器配置界面用它预告变量，理由和 ALL_VARIABLES 一样：
     * 宿主要先知道有哪些变量，用户才能在宏里把它们映射到自己的变量上。
     *
     * 【为什么只有菜单三项】
     * 触发器场景里没有 OCR 识别，把识别文本 / 坐标那几项列出来
     * 只会让人以为配个手势还要配图片路径。
     * 而手势方向 / 序号 / 时间 / 手柄也不再列 —— 方向匹配插件已经做了，
     * 其余是调试信息；用户在 MacroDroid 里自行建变量接值，名字由用户定。
     * 留菜单三项是因为「用户点了哪一项」只有插件知道。
     *
     * ★ 没选中任何项时这三个变量**都不会被回传**（不是传空值）：
     *   宏侧判「%xsocr_menu_index 有没有值」即可知道这次是不是真的选了。
     */
    public static final String[] MENU_VARIABLES = {
            VAR_MENU_INDEX  + "\n菜单索引\n滑动菜单选中的项序号（从 0 开始）；**没选中时本变量不出现**",
            VAR_MENU_TEXT   + "\n菜单文字\n选中项的显示文字；没选中时不出现",
            VAR_MENU_ACTION + "\n菜单动作\n选中项的动作串；没选中时不出现",
    };

    /** 触发器模式下的变量预告（只含菜单结果）。 */
    public static void addMenuVariableList(android.content.Intent intentToHost) {
        if (intentToHost == null) return;
        intentToHost.putExtra(EXTRA_RELEVANT_VARIABLES, MENU_VARIABLES);
    }

    /**
     * 关于「坐标 X / 坐标 Y」这两个变量（VAR_X / VAR_Y）。
     *
     * ── 曾经的写法与它踩的坑 ─────────────────────────────
     * 原先它们（以及菜单索引）是用 putInt() 写进 Bundle 的，
     * 理由是「语意上它们是数字、更规范」。这个判断是错的：
     *
     *   Tasker 官方 TaskerPlugin.addVariableBundle 明确要求
     *       Values must be String, String [] or ArrayList<String>
     *   即宿主变量协议**只接受字符串**。回 Integer 时 Tasker 会
     *   静默丢掉该项，于是「文字变量能到、坐标和索引到不了」。
     *   MacroDroid 内部对值统一 toString()，所以一直没暴露问题。
     *
     * ── 现在的写法 ───────────────────────────────────────
     * 一律用 {@link #putIntAsString} 回传字符串（"-1" / "523"）。
     * 宏侧拿到的文本与之前完全一致，语义零变化。
     *
     * 内部快照（{@code stashResult} 存给 HTTP /last 接口用的那份）
     * 仍是数字，因为它不经过宿主协议 —— 读取方式见
     * PluginFireReceiver.stashResult()，那里对两种类型都安全。
     */

    /**
     * 申请更长的超时（复刻 TaskerPlugin.Setting.requestTimeoutMS）。
     *
     * 写入 net.dinglisch.android.tasker.extras.REQUESTED_TIMEOUT。
     * 说明：MacroDroid 实测**不读**这个字段（它的等待时长写死 11 秒），
     * 但 Tasker 等宿主会读，写上无害且更规范。
     */
    public static void requestTimeout(android.content.Intent intentToHost, int timeoutMs) {
        if (intentToHost == null) return;
        try {
            // 上限按 TaskerPlugin 的实现：3599000ms
            int t = timeoutMs;
            if (t < 0) return;
            if (t > 3599000) t = 3599000;
            intentToHost.putExtra(TASKER_EXTRAS + "REQUESTED_TIMEOUT", t);
        } catch (Throwable ignored) {
        }
    }

    /** 通用的能力位判断。 */
    public static boolean hostSupports(android.os.Bundle extrasFromHost, int capabilityFlag) {
        if (extrasFromHost == null) return false;
        Object flags = extrasFromHost.get(EXTRA_HOST_CAPABILITIES);
        if (!(flags instanceof Integer)) return false;
        return (((Integer) flags) & capabilityFlag) > 0;
    }

    // ==================== 异步补结果的实现 ====================

    /** 插件相关日志的 tag（与 PluginFireReceiver 一致，方便一起筛）。 */
    private static final String LOG_TAG = "XSOCR-Plugin";

    /**
     * 从 Bundle 里安全取 Boolean。
     *
     * ★ 返回 null 而不是 false，这点很重要：
     *   宿主「没给这个值」和「明确说要 false」是两回事，
     *   上层要据此决定走 Service 还是走广播（见 signalFinish）。
     */
    private static Boolean boolExtra(android.os.Bundle b, String key) {
        if (b == null) return null;
        try {
            Object o = b.get(key);
            return (o instanceof Boolean) ? (Boolean) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 变量个数，只给日志用。 */
    private static int sizeOf(android.os.Bundle b) {
        return b == null ? 0 : b.size();
    }

    /**
     * 把整数写进变量 Bundle —— **以字符串形式**。
     *
     * ★【为什么必须是 String，不能是 Integer】
     *
     *   Tasker 官方 TaskerPlugin.addVariableBundle 的 javadoc 写得很死：
     *
     *       Names must be valid Tasker local variable names.
     *       Values must be String, String [] or ArrayList<String>
     *
     *   即 Tasker 的变量协议只接受 String / String[] / ArrayList<String>。
     *   我们曾经用 putInt() 回传「菜单索引」与「命中坐标」，
     *   Tasker 收到 Integer 会**直接丢掉该项**（同批里 putString 的照收），
     *   现象就是「文字变量能到、索引和坐标到不了」。
     *   MacroDroid 对每个值统一 toString()、不挑类型，
     *   所以这个 bug 只在 Tasker 一侧暴露出来。
     *
     *   存成字符串后宏侧拿到的是 "3" 这种文本 —— 与之前的实际效果一致
     *   （Tasker 本来就会把值当文本用），语义零变化。
     *
     * 注意：内部快照（stashResult / HTTP /last 接口）仍可用数字类型，
     *      那些不经过宿主协议，见 PluginFireReceiver.stashResult()。
     */
    static void putIntAsString(android.os.Bundle vars, String key, int value) {
        if (vars == null) return;
        vars.putString(key, String.valueOf(value));
    }

    /**
     * 从宿主给的 Intent 里取出「回调地址」。
     *
     * @return 非 null 表示宿主支持异步补结果
     */
    public static boolean hasCompletionIntent(android.content.Intent fromHost) {
        if (fromHost == null) return false;
        try {
            String uri = fromHost.getStringExtra(EXTRA_COMPLETION_INTENT);
            return uri != null && uri.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把结果异步补送给宿主。
     *
     * 复刻 TaskerPlugin.Setting.signalFinish()：
     *   1. 取出 COMPLETION_INTENT（宿主预先塞的序列化 Intent）
     *   2. 塞入 RESULT_CODE 和 VARIABLES
     *   3. 按宿主指定的方式送回去（Service 或 广播）
     *
     * 为什么不能只 sendBroadcast：
     *   宿主可能要求用 Service 接收（EXTRA_CALL_SERVICE 系列），
     *   这时必须 startService/startForegroundService，否则结果收不到。
     *
     * @param ctx           上下文
     * @param fromHost      onReceive 收到的原始 Intent
     * @param resultCode    RESULT_CODE_OK / RESULT_CODE_FAILED ...
     * @param variables     要回传的变量 Bundle（可为 null）
     * @return true 表示已成功送出
     */
    public static boolean signalFinish(android.content.Context ctx,
                                       android.content.Intent fromHost,
                                       int resultCode,
                                       android.os.Bundle variables) {
        if (ctx == null || fromHost == null) return false;
        try {
            String uri = fromHost.getStringExtra(EXTRA_COMPLETION_INTENT);
            if (uri == null || uri.length() == 0) {
                return false;
            }

            // 解析成 Intent（flags=1 表示允许从 URI 还原）
            android.content.Intent completion = android.content.Intent.parseUri(uri, 1);

            completion.putExtra(EXTRA_RESULT_CODE, resultCode);
            if (variables != null) {
                // signalFinish 是把变量挂在一个 Intent 上（不是广播结果 Bundle），
                // 所以这里不能复用 addVariableBundle()，得 putExtra。
                completion.putExtra(EXTRA_VARIABLES_BUNDLE, variables);
            }

            // ============ 「宿主用 Service 还是广播收结果」 ============
            //
            // ★ 这里曾经是本插件「变量能回 MacroDroid、回不了 Tasker」的根因。
            //
            //   Tasker 官方 TaskerPlugin.Setting.signalFinish 读的是
            //   **解析后的 completionIntent**，不是原始的 fire intent：
            //
            //       completionIntent.putExtra(EXTRA_RESULT_CODE, resultCode);
            //       if (vars != null) completionIntent.putExtra(EXTRA_VARIABLES_BUNDLE, vars);
            //       String callServicePackage = (String) getExtraValueSafe(
            //               completionIntent, EXTRA_CALL_SERVICE_PACKAGE, String.class, ...);
            //       String callService = ...(completionIntent, EXTRA_CALL_SERVICE, ...);
            //       Boolean foreground = ...(completionIntent, EXTRA_CALL_SERVICE_FOREGROUND, ...);
            //       if (三者都非 null) { setComponent(...); startService/startForegroundService }
            //       else               { sendBroadcast(completionIntent) }
            //
            //   原因是宿主用 Host.addCompletionIntent() 把这三个值放进
            //   **completionIntent** 里，原始 fire intent 上根本没有。
            //
            //   我们原来从 fromHost 读 → 恒为 null → 一律 sendBroadcast。
            //   MacroDroid 接受广播，所以一直没暴露；而 Tasker 是按 Service
            //   收结果的，广播到不了它手里 → 变量全丢。
            //
            //   现在按官方做法从 completion 读；读不到再回退看一眼原始
            //   intent —— 只加不减，已经能用的宿主一条都不动。
            String pkg = completion.getStringExtra(EXTRA_CALL_SERVICE_PACKAGE);
            String cls = completion.getStringExtra(EXTRA_CALL_SERVICE);
            Boolean fg = boolExtra(completion.getExtras(), EXTRA_CALL_SERVICE_FOREGROUND);

            if (pkg == null) pkg = fromHost.getStringExtra(EXTRA_CALL_SERVICE_PACKAGE);
            if (cls == null) cls = fromHost.getStringExtra(EXTRA_CALL_SERVICE);
            if (fg == null) fg = boolExtra(fromHost.getExtras(), EXTRA_CALL_SERVICE_FOREGROUND);

            if (pkg != null && cls != null) {
                completion.setComponent(new android.content.ComponentName(pkg, cls));
                boolean fgService = Boolean.TRUE.equals(fg)
                        && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O;
                if (fgService) {
                    ctx.startForegroundService(completion);
                } else {
                    ctx.startService(completion);
                }
                AppLog.i(LOG_TAG, "补结果已送: 方式=Service " + pkg + "/" + cls
                        + " 前台=" + fg + " 变量=" + sizeOf(variables)
                        + " 结果码=" + resultCode);
                return true;
            }

            // 默认：发广播（MacroDroid 走的就是这条）
            ctx.sendBroadcast(completion);
            AppLog.i(LOG_TAG, "补结果已送: 方式=广播 变量=" + sizeOf(variables)
                    + " 结果码=" + resultCode);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("XSOCR-Plugin", "signalFinish 失败: " + t);
            return false;
        }
    }
}