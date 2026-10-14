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

    /** 插件把要回传的变量放在这个 Bundle 里。 */
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
     *   %xsocr_match  → true / false
     *   %xsocr_x/y    → 命中的文本块坐标（左上角，整数）
     * 没填则 %xsocr_match 为 false，坐标固定 -1。
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

    // ==================== 工具方法 ====================

    /** 宿主是否声明支持「返回变量」。 */
    public static boolean hostSupportsVariableReturn(android.os.Bundle extrasFromHost) {
        if (extrasFromHost == null) return false;
        Object flags = extrasFromHost.get(EXTRA_HOST_CAPABILITIES);
        if (!(flags instanceof Integer)) return false;
        return ((Integer) flags & CAPABILITY_SETTING_RETURN_VARIABLES) > 0;
    }

    /** 把变量 Bundle 放进广播结果里，宿主会去读它。 */
    public static void addVariableBundle(android.os.Bundle resultExtras,
                                         android.os.Bundle variables) {
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
     * 以「整数」形式回传的变量：%xsocr_x、%xsocr_y。
     *
     * 实现方式是在 Bundle 里用 putInt() 而不是 putString()。
     * 宿主侧（MacroDroid 的 t6g.b）会用 toString() 统一转成字符串，
     * 所以最终存进宏变量都是 "523" 这种文本；
     * 但**类型正确的 Bundle 更规范**：Tasker 会据此区分数字/文本变量，
     * 且避免下游再解析一次。
     *
     * 注意：读取这类 key 时不能调 Bundle.getString()，会抛 ClassCastException，
     *      必须用 get() / getInt()。见 PluginFireReceiver.stashResult()。
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
                completion.putExtra(EXTRA_VARIABLES_BUNDLE, variables);
            }

            // 宿主可能指定用 Service 接收，并且可能要求前台服务
            String pkg = fromHost.getStringExtra(EXTRA_CALL_SERVICE_PACKAGE);
            String cls = fromHost.getStringExtra(EXTRA_CALL_SERVICE);
            Boolean fg = null;
            try {
                Object o = fromHost.getExtras() == null
                        ? null : fromHost.getExtras().get(EXTRA_CALL_SERVICE_FOREGROUND);
                if (o instanceof Boolean) fg = (Boolean) o;
            } catch (Throwable ignored) {
            }

            if (pkg != null && cls != null) {
                completion.setComponent(new android.content.ComponentName(pkg, cls));
                if (Boolean.TRUE.equals(fg)) {
                    if (android.os.Build.VERSION.SDK_INT
                            >= android.os.Build.VERSION_CODES.O) {
                        ctx.startForegroundService(completion);
                    } else {
                        ctx.startService(completion);
                    }
                } else {
                    ctx.startService(completion);
                }
                return true;
            }

            // 默认：发广播（MacroDroid 走的就是这条）
            ctx.sendBroadcast(completion);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("XSOCR-Plugin", "signalFinish 失败: " + t);
            return false;
        }
    }
}