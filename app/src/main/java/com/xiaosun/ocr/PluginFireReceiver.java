package com.xiaosun.ocr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * Locale/Tasker 插件的「执行入口」。
 *
 * 宏执行到该插件时，宿主会用 ACTION_FIRE_SETTING 发一个**有序广播**，
 * 我们在这里：
 *   1. 按配置拿图（截屏 / 读文件）
 *   2. 调本地 OCR（通过 HTTP 接口，复用已有服务）
 *   3. 把结果写进变量 Bundle，塞到广播结果里回传给宿主
 *
 * 关键点：
 *   - 必须 goAsync()：onReceive 只有 ~10s 窗口，OCR 可能更久
 *   - 有序广播才有 getResultExtras()，所以 setResultCode 前要判断 isOrderedBroadcast()
 *   - 二进制/JSON 都放变量里，不写文件（按需求）
 */
public class PluginFireReceiver extends BroadcastReceiver {

    private static final String TAG = "XSOCR-Plugin";

    /**
     * 宿主等待回传结果的硬上限（毫秒）。
     *
     * 来自反编译证据：MacroDroid 内置的官方 locale-sdk 里
     * com/twofortyfouram/locale/sdk/host/api/Setting.smali 的 fire() 中：
     *     const-wide/16 v6, 0x2af8   // = 11000
     *     invoke-virtual {latch, v6, MILLISECONDS}, CountDownLatch;->await(...)
     *
     * 超过这个时间，宿主会降级重发普通广播并直接 return —— 
     * 之后无论我们再回传什么都不会被采纳。
     * 所以整个流程必须压在这个预算内。
     */
    private static final long HOST_BUDGET_MS = 11000L;

    /**
     * 安全余量：广播回传、线程调度、SharedPreferences 落盘都要时间。
     * 留 2 秒，实际可用 9 秒。
     */
    private static final long SAFETY_MS = 2000L;

    /**
     * 异步补结果路线的时间预算（毫秒）。
     *
     * 宿主其实给了 COMPLETION_INTENT（回调地址），
     * 插件可以事后补送结果 —— 但这条路**没有任何超时限制**，
     * 唯一要防的是：我们自己的线程被系统回收，或用户等得不耐烦。
     * 所以给一个宽松上限，保证 OCR 有充足时间跑完。
     */
    private static final long ASYNC_BUDGET_MS = 120000L;

    /** 快速探测超时（服务已在运行时，毫秒级就应返回）。 */
    private static final int PING_QUICK_MS = 300;

    /**
     * 「模型已就绪」判定的缓存有效期（毫秒）。
     *
     * 【为什么需要缓存】服务改成「HTTP 先上线、模型后台加载」之后，
     * ping 通过只说明端口通了，并不代表能识别（要再看 /last 的 modelReady）。
     * 但本方法每次都在宿主给的毫秒级预算里被调用，
     * 不能再无条件多加一次 /last 往返 —— 冷启动时叠加探测很容易超预算。
     * 折中：把"已确认就绪"缓存 2 秒，
     *   同一批宏触发的多次检查只付一次探测成本；
     *   超过 2 秒重新确认（那时服务早已启动完，探测是毫秒级）。
     */
    private static final long MODEL_READY_TTL_MS = 2000L;

    /** 上次确认「模型已就绪」的时间戳（0 表示尚未确认过）。 */
    private static volatile long modelReadyAt = 0L;

    /**
     * 去重窗口（毫秒）。
     *
     * 【为什么必须去重】
     * 宿主超时后（11 秒）会把同一个 Intent **降级重发**成普通广播：
     *     context.sendBroadcast(intent);   // Setting.fire() 里的兜底分支
     * 于是我们的 onReceive 会被调用第二次。
     *
     * 如果不去重，第二次会**从头再跑一遍完整识别**（再花十几秒），
     * 既浪费电、又会把 SharedPreferences 里的结果覆盖掉。
     *
     * 这里记住"最近一次执行"的指纹，窗口期内重复的直接跳过。
     * 窗口取 15 秒（略大于宿主的 11 秒预算，确保能盖住那次重发）。
     */
    private static final long DEDUP_WINDOW_MS = 15000L;

    /** 最近一次执行的指纹与时间（同一进程内有效）。 */
    private static volatile String sLastFingerprint = null;
    private static volatile long sLastFireAt = 0L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!PluginContract.ACTION_FIRE_SETTING.equals(action)) return;

        // 取宿主传来的配置
        Bundle cfg = null;
        try {
            cfg = intent.getBundleExtra(PluginContract.EXTRA_BUNDLE);
        } catch (Throwable t) {
            Log.w(TAG, "读取配置失败: " + t);
        }
        if (cfg == null) cfg = new Bundle();

        // ---------- 防御：别拿「触发器」的配置当「动作」跑 ----------
        //
        // 配置界面按 action 分两种模式，保存进 cfg 的 plugin_mode 就是标记：
        //   setting → 识别屏幕文字（本接收器该干的活）
        //   event   → 等某个手势（由 PluginConditionReceiver 应答）
        //
        // 理想情况下宿主不会把触发器配置发到 FIRE_SETTING 来，
        // 但宿主版本众多、内部缓存也可能串；真发生了就是一次完整的
        // 截屏 + OCR 白跑，白耗电还占着前台服务。
        // 这里拦一道：不是动作配置就当没收到。
        String cfgMode = cfg.getString(PluginContract.KEY_PLUGIN_MODE, null);
        if (cfgMode != null && !PluginContract.MODE_SETTING.equals(cfgMode)) {
            Log.w(TAG, "收到的是「" + cfgMode + "」配置，不是动作配置，跳过识别");
            return;
        }

        final Bundle config = cfg;
        final boolean ordered = isOrderedBroadcast();

        // ---------- 去重：只针对「宿主超时后的降级重发」 ----------
        // 宿主（MacroDroid）等待超时后，会把同一个 Intent 降级重发成**普通广播**
        // （Setting.fire() 里的兜底分支），于是 onReceive 被调用第二次。
        // 此时第一次的识别多半还在跑，必须跳过，
        // 否则会重复识别、并覆盖刚写好的结果。
        //
        // 注意：只对「非有序广播」去重。
        // 有序广播是宿主的正常触发（含用户连续手动触发），一律照常执行，
        // 不会被去重误伤。
        final String fingerprint = buildFingerprint(intent, config);
        long now = System.currentTimeMillis();
        if (!ordered
                && fingerprint != null
                && fingerprint.equals(sLastFingerprint)
                && (now - sLastFireAt) < DEDUP_WINDOW_MS) {
            Log.w(TAG, "宿主超时后的降级重发（普通广播），跳过（"
                    + (now - sLastFireAt) + "ms 前已触发过）");
            return;
        }
        sLastFingerprint = fingerprint;
        sLastFireAt = now;

        // =====================================================================
        // ★ 核心：用「异步补结果」机制，彻底绕开宿主 11 秒超时
        // =====================================================================
        // 参考实现：PP-OCRv6 插件（本机实测能正常传变量到 MacroDroid）。
        // 反汇编它的 OcrActionReceiver 得到流程：
        //
        //   1) if (isOrderedBroadcast()) setResultCode(3 /* PENDING */)
        //         ↑ 必须在 goAsync() 之前调用，且用 BroadcastReceiver 自己的方法
        //         ↑ 回 PENDING 后宿主就知道"要等异步结果"，不再按 11 秒超时处理
        //   2) goAsync()
        //   3) 慢慢干活（OCR 20 秒也没关系）
        //   4) TaskerPlugin.Setting.signalFinish(ctx, originalIntent, code, vars)
        //         ↑ 往宿主预置的 COMPLETION_INTENT 发广播，把变量补送回去
        //
        // 送达后由 MacroDroid 的 LocaleTaskerSettingCompleteReceiver 接收，
        // 读 extras.VARIABLES，按 action_id 找到对应动作写入变量。
        //
        // 【为什么不能用同步回传】
        // Setting.fire() 里 latch.await(0x2af8 = 11000ms) 写死，
        // 超时即降级重发普通广播并 return，结果一律丢弃。
        // OCR 常常超过 11 秒，同步路径必然失败。
        final boolean hasCompletion = PluginContract.hasCompletionIntent(intent);
        final boolean orderedNow = ordered;

        // ---------- 路线 A：异步补结果（首选，MacroDroid 走这条） ----------
        //
        // 关键设计：**不用 goAsync()**
        //   goAsync 要求 PendingResult 尽快 finish（约 10 秒），
        //   而 OCR 可能要 20 秒 —— 挂太久会被系统判超时并告警。
        //
        //   既然已经回了 RESULT_CODE_PENDING，宿主就不再等广播结果了，
        //   我们也没必要把广播挂住。直接让 onReceive 立即返回，
        //   把活儿交给后台线程慢慢做，做完用 signalFinish 从
        //   COMPLETION_INTENT 那条独立通道把结果送回去。
        //
        //   进程保活由 OCRService（前台服务）负责。
        if (hasCompletion) {
            if (orderedNow) {
                try {
                    // 告诉宿主「我在处理，别按 11 秒超时处理」
                    setResultCode(PluginContract.RESULT_CODE_PENDING);
                    Log.i(TAG, "已回 RESULT_CODE_PENDING(3)：走异步补结果路线");
                } catch (Throwable t) {
                    Log.w(TAG, "setResultCode(PENDING) 失败: " + t);
                }
            }

            final Intent originalIntent = intent;
            final Context appCtx = context.getApplicationContext();
            new Thread(() -> {
                Bundle vars = new Bundle();
                int resultCode = PluginContract.RESULT_CODE_OK;
                try {
                    doWork(appCtx, config, vars, true);
                    // 失败时 doWork 会写入 VAR_ERROR
                    if (isFailed(vars)) resultCode = PluginContract.RESULT_CODE_FAILED;
                } catch (Throwable t) {
                    Log.e(TAG, "执行异常: " + t);
                    vars.putString(PluginContract.VAR_ERROR, String.valueOf(t.getMessage()));
                    resultCode = PluginContract.RESULT_CODE_FAILED;
                } finally {
                    long cost = System.currentTimeMillis() - sLastFireAt;
                    try {
                        boolean ok = PluginContract.signalFinish(
                                appCtx, originalIntent, resultCode, vars);
                        Log.i(TAG, "signalFinish 补送结果: ok=" + ok
                                + "，变量 " + vars.size() + " 个，耗时 " + cost + "ms");
                    } catch (Throwable t) {
                        Log.e(TAG, "signalFinish 异常: " + t);
                    }
                }
            }, "xsocr-plugin").start();
            return;   // onReceive 立即返回
        }

        // ---------- 路线 B：同步回传（兜底，宿主没给回调地址时） ----------
        // 只能靠 11 秒内返回，所以内部有预算控制（HOST_BUDGET_MS - SAFETY_MS）。
        // 这里必须 goAsync，否则 onReceive 一返回，广播结果就没法再写。
        final boolean hostClaimsVars = orderedNow
                && PluginContract.hostSupportsVariableReturn(getResultExtras(true));

        final PendingResult pending = goAsync();

        final Context appCtx2 = context.getApplicationContext();
        new Thread(() -> {
            Bundle vars = new Bundle();
            int resultCode = PluginContract.RESULT_CODE_OK;
            try {
                doWork(appCtx2, config, vars, false);
                if (isFailed(vars)) resultCode = PluginContract.RESULT_CODE_FAILED;
            } catch (Throwable t) {
                Log.e(TAG, "执行异常: " + t);
                vars.putString(PluginContract.VAR_ERROR, String.valueOf(t.getMessage()));
                resultCode = PluginContract.RESULT_CODE_FAILED;
            } finally {
                long cost = System.currentTimeMillis() - sLastFireAt;
                try {
                    Bundle extras = pending.getResultExtras(true);
                    if (extras == null) extras = new Bundle();
                    PluginContract.addVariableBundle(extras, vars);
                    pending.setResultExtras(extras);
                    pending.setResultCode(resultCode == PluginContract.RESULT_CODE_OK
                            ? android.app.Activity.RESULT_OK
                            : android.app.Activity.RESULT_CANCELED);
                    if (orderedNow) {
                        Log.i(TAG, "已同步回传变量 " + vars.size()
                                + " 个（宿主声明支持=" + hostClaimsVars + "，耗时 " + cost + "ms）");
                    } else {
                        Log.w(TAG, "非有序广播且无回调地址，无法回传变量");
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "同步回传失败: " + t);
                } finally {
                    try {
                        pending.finish();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "xsocr-plugin-sync").start();
    }

    /**
     * 把「最近一次边缘手势」写进回传变量。
     *
     * 数据源是 {@link EdgeGesturePrefs} 里的一组 volatile 静态字段，
     * 由 {@link EdgeGestureService} 在 fire() 的那一刻写入 —— 之所以不走
     * Intent/Bundle 传递，是因为手势服务与插件可能不在同一进程状态、
     * 且静态字段读取零成本、也不受广播异步时序影响。
     *
     * 值可能为空（用户从没滑过，或手势引擎没开），此时写空串而不是跳过：
     * 让宿主侧变量有确定的值，宏里做判断时不会因为变量不存在而报错。
     */
    /** 真正的干活逻辑。 */
    private void doWork(Context ctx, Bundle config, Bundle vars, boolean hasCompletion) {
        long t0 = System.currentTimeMillis();

        // =========================================================================
        // ★ 预算策略：分两条路线
        // =========================================================================
        // 反编译 MacroDroid 的 classes7.dex 得到
        // com/twofortyfouram/locale/sdk/host/api/Setting.smali 里的 fire()：
        //
        //   sendOrderedBroadcast(intent, null, fireResultReceiver, handler, ...);
        //   latch.await(0x2af8 /* = 11000ms, 写死 */, MILLISECONDS);
        //   if (超时) {
        //       context.sendBroadcast(intent);   // 降级重发「普通广播」
        //       return;                          // 直接放弃，不取结果
        //   }
        //
        // 所以「同步回传」只有 11 秒窗口。但宿主同时会塞一个
        // COMPLETION_INTENT（回调地址），让插件可以事后补送结果。
        //
        // 【路线 A】有 COMPLETION_INTENT（正常情况）：
        //   先回 RESULT_CODE_PENDING 告诉宿主"别超时"，
        //   然后慢慢跑（这里给 120 秒），跑完用 signalFinish 补送。
        //   —— 完全不卡 11 秒。
        //
        // 【路线 B】没有 COMPLETION_INTENT（极少见）：
        //   只能压缩到 11 秒内返回，所以按 HOST_BUDGET_MS 推进。
        final long deadline = hasCompletion
                ? t0 + ASYNC_BUDGET_MS                     // 宽松：120 秒
                : t0 + HOST_BUDGET_MS - SAFETY_MS;         // 严格：9 秒

        Log.i(TAG, "开始执行："
                + (hasCompletion ? "异步补结果路线（预算 " + ASYNC_BUDGET_MS + "ms）"
                                 : "同步回传路线（预算 " + (HOST_BUDGET_MS - SAFETY_MS) + "ms）"));

        String source = config.getString(PluginContract.KEY_SOURCE,
                PluginContract.SOURCE_SCREENSHOT);
        String path = config.getString(PluginContract.KEY_PATH, "");
        String tier = config.getString(PluginContract.KEY_TIER, null);
        int type = config.getInt(PluginContract.KEY_TYPE, 1);
        boolean joinNewline = config.getBoolean(PluginContract.KEY_JOIN_NEWLINE, true);
vars.putString(PluginContract.VAR_TIER, tier == null ? "current" : tier);

        // 全部走 vars 统一出口。
        // ---------- 0. 不再带「边缘手势」上下文 ----------
        // 本插件的动作就是「识别」：给图 / 截屏 → 回传识别结果。
        // 边缘滑动那套（方向、序号、菜单选中项）是**触发器**那边的事：
        //   · 方向在触发器配置时就比过了，不匹配的宏根本不会跑；
        //   · 菜单选中项只在「滑出菜单」的场景里才有意义，
        //     跟「宏主动调一次 OCR」没有关系。
        // 所以动作路径一个手势变量都不回 —— 预告列表（ALL_VARIABLES）
        // 里也没有它们，两边保持一致。


        // ---------- 1. 确保服务在跑 ----------
        // 服务承载 HTTP 接口和 native 识别能力，必须先就绪。
        //
        // 【为什么可能没起来】
        // 插件由 MacroDroid 的广播触发，此时本应用可能处于「已死」状态
        // （用户划掉了最近任务，或系统回收了进程）。
        //
        // 【两个 Android 限制 + 破解办法】
        //   a) Android 12+ 禁止后台应用 startForegroundService()。
        //      → 但「收到广播后的约 10 秒内」属于系统给的豁免窗口，
        //        在这个窗口里调用是允许的，所以要在 onReceive 后尽快调用。
        //   b) 无 root 时截屏依赖 MediaProjection 授权，
        //      而授权只能由 Activity 发起，广播无法弹框。
        //      → 拉起 ScreenCaptureRequestActivity 代为申请（需悬浮窗权限）。
        //
        // 【踩过的坑】探测必须用轻量请求。
        //   早先用 POST {} 探测，服务端会走去「截屏」分支、耗时可达 15 秒，
        //   导致误判"服务未就绪"而白等到超时。
        //   现在 ping() 走 GET /last，是纯读操作，毫秒级返回。
        boolean serverUp = Http.ping(PING_QUICK_MS);

        if (!serverUp) {
            Log.i(TAG, "服务未运行，尝试拉起（广播豁免窗口内）");
            try {
                Intent svc = new Intent(ctx, OCRService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svc);
                } else {
                    ctx.startService(svc);
                }
            } catch (Throwable t) {
                // 常见的两种：ForegroundServiceStartNotAllowedException（后台限制）
                // 或 BackgroundServiceStartNotAllowedException。
                // 不直接放弃：服务可能已被其他途径拉起，下面照样轮询等待。
                Log.w(TAG, "拉起服务失败（可能受后台限制）: " + t);
            }
            serverUp = waitServerReadyUntil(deadline);
        }

        if (!serverUp) {
            fail(vars, "OCR 服务未能启动。"
                    + "请检查「小孙OCR」是否被系统限制了后台运行（自启动/省电策略）。");
            return;
        }

        // ---------- 1.2 模型就绪检查 ----------
        // 【修复⑥的配套，很重要】
        // 服务现在是「HTTP 先上线、模型后台加载」：ping 通过 ≠ 能识别。
        // 冷启动时（服务刚被这段代码拉起）模型往往还在加载，
        // 此时直接 POST 会拿到 503 "模型仍在加载中" —— 虽然是明确的错误，
        // 但对用户来说仍然是"宏跑失败了"。
        // 以前串行启动时模型几乎必然已好（因为 HTTP 最后才起），
        // 所以这里必须把"等模型"这一步补上，否则修复⑥会带来行为回归。
        if (!waitModelReadyUntil(deadline)) {
            fail(vars, "OCR 引擎尚未就绪："
                    + (OCRService.modelError == null || OCRService.modelError.isEmpty()
                            ? "模型仍在加载中，请稍后重试"
                            : OCRService.modelError));
            return;
        }

        // ---------- 1.5 截屏能力检查（仅「截屏」来源需要） ----------
        // 走「指定图片路径」时不涉及截屏，跳过。
        if (!PluginContract.SOURCE_PATH.equals(source)
                && !OCRService.screenCaptureReady) {
            Log.i(TAG, "尚无截屏能力，尝试申请授权");

            boolean ok = requestScreenCaptureIfNeeded(ctx, deadline);

            if (!ok) {
                fail(vars, "截屏未就绪。三选一："
                        + "① 在小孙OCR 的「OCR设置」页点「无障碍截图」，"
                        + "在系统设置里勾选一次（最省事，之后免授权）；"
                        + "② 有 root 的设备会自动走 screencap；"
                        + "③ 开启「悬浮窗权限」（插件才能自动申请截屏）"
                        + "或打开 App 手动点一次「开启截屏授权」。");
                return;
            }
            Log.i(TAG, "截屏授权完成，继续识别");
        }

        // ---------- 2. 构造请求 ----------
        JSONObject body = new JSONObject();
        try {
            if (PluginContract.SOURCE_PATH.equals(source)) {
                if (path == null || path.trim().isEmpty()) {
                    fail(vars, "'指定路径' 但路径为空");
                    return;
                }
                File f = new File(path.trim());
                if (!f.exists()) {
                    fail(vars, "图片不存在: " + path);
                    return;
                }
                body.put("path", f.getAbsolutePath());
            }
            // source=screenshot 时不传 path，服务端会自动截屏
            //
            // ★ 注意：这里**固定用 type=2**（含坐标）请求。
            //   原因：坐标变量（x/y）需要引擎返回 x/y 字段，
            //   而 type=1 的响应里根本没有坐标。如果跟着用户选的
            //   「纯文本」走，坐标就永远是 -1。
            //   "纯文本 vs 含坐标" 只影响识别文本的拼接样式，
            //   原始请求统一用 type=2，保证坐标始终可用。
            body.put("type", type >= 2 ? type : 2);
            if (tier != null && !tier.isEmpty()) body.put("tier", tier);
        } catch (Throwable t) {
            fail(vars, "构造请求失败: " + t);
            return;
        }

        // ---------- 3. 调 OCR（超时按剩余预算算） ----------
        int remain = (int) (deadline - System.currentTimeMillis());
        if (remain <= 500) {
            fail(vars, "剩余预算不足（" + remain + "ms），无法完成识别");
            return;
        }

        String resp;
        try {
            // HTTP 读超时不能超过剩余预算，否则我们等到了、宿主早就超时了
            resp = Http.post(ctx, body.toString(), remain);
        } catch (Throwable t) {
            fail(vars, "调用识别接口失败: " + t.getMessage());
            return;
        }
        if (resp == null || resp.isEmpty()) {
            fail(vars, "识别接口无响应（可能超出 " + HOST_BUDGET_MS + "ms 预算）");
            return;
        }

        // ---------- 4. 解析结果 ----------
        try {
            JSONObject o = new JSONObject(resp);
            int status = o.optInt("status", 0);
            if (status != 200) {
                fail(vars, o.optString("data", "识别失败(status=" + status + ")"));
                return;
            }

            JSONArray arr = o.optJSONArray("data");
            StringBuilder sb = new StringBuilder();
            int count = 0;
            String sep = joinNewline ? "\n" : " ";

            // ---------- 指定文本匹配 ----------
            // 用户填了「指定文本」就在这里找。
            // 匹配方式由 KEY_MATCH_MODE 决定：
            //   MODE_CONTAINS  包含 —— 文本块内容含指定文本即算命中
            //   MODE_EXACT     完全 —— 文本块内容与指定文本完全一致才命中
            // 大小写：统一转小写比较，避免「OK」/「ok」漏匹配。
            // 坐标取该文本块左上角 (x, y) —— 与识别结果同一坐标系（截图原始像素）。
            String want = config.getString(PluginContract.KEY_MATCH_TEXT, "");
            if (want == null) want = "";
            want = want.trim();
            final int matchMode = config.getInt(PluginContract.KEY_MATCH_MODE,
                    PluginContract.MODE_CONTAINS);

            boolean matched = false;
            int matchX = -1;
            int matchY = -1;

            if (arr != null) {
                // 显式 Locale.ROOT，避免土耳其语环境下 I/ı 大小写折叠错误导致匹配失败
                String wantLower = want.toLowerCase(Locale.ROOT);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item == null) continue;
                    String text = item.optString("text", "");
                    if (text.isEmpty()) continue;

                    // 拼接输出文本
                    if (count > 0) sb.append(sep);
                    if (type >= 2) {
                        // 含坐标时，把坐标也拼进去（不写文件，只能塞进变量）
                        sb.append("[").append(item.optInt("x")).append(",")
                          .append(item.optInt("y")).append("] ").append(text);
                    } else {
                        sb.append(text);
                    }
                    count++;

                    // 第一个命中的就用它，不再继续找
                    if (!matched && !wantLower.isEmpty()) {
                        String textLower = text.toLowerCase(Locale.ROOT);
                        boolean hit = (matchMode == PluginContract.MODE_EXACT)
                                ? textLower.equals(wantLower)
                                : textLower.contains(wantLower);
                        if (hit) {
                            matched = true;
                            matchX = item.optInt("x", -1);
                            matchY = item.optInt("y", -1);
                        }
                    }
                }
            }

            vars.putString(PluginContract.VAR_TEXT, sb.toString());
            vars.putString(PluginContract.VAR_MATCH, matched ? "true" : "false");
            // ★ 坐标用 putInt 写入（整数变量）
            vars.putInt(PluginContract.VAR_X, matchX);
            vars.putInt(PluginContract.VAR_Y, matchY);
            vars.putString(PluginContract.VAR_ERROR, "");
            vars.putString(PluginContract.VAR_TIME, String.valueOf(System.currentTimeMillis() - t0));
            vars.putString(PluginContract.VAR_TIER, o.optString("tier", tier == null ? "" : tier));

            Log.i(TAG, "识别完成：文本块=" + count
                    + "，指定文本=\"" + want + "\"（"
                    + (matchMode == PluginContract.MODE_EXACT ? "完全匹配" : "包含")
                    + "）命中=" + matched
                    + (matched ? "（" + matchX + "," + matchY + "）" : "")
                    + "，耗时=" + (System.currentTimeMillis() - t0) + "ms");

            // 兜底：把结果也存一份，供「读取上次结果」用。
            // 万一宿主不支持变量回传，宏里可以用 HTTP 动作拿这个值。
            stashResult(ctx, vars);

        } catch (Throwable t) {
            fail(vars, "解析结果失败: " + t);
        }
    }

    /**
     * 把本次结果存进 SharedPreferences，并记录时间戳。
     *
     * 为什么要这个：变量回传依赖宿主实现（Tasker 私有扩展），
     * 不能保证 100% 生效。存一份本地副本后，宏可以用
     * 「HTTP 请求」访问 /last 接口兜底取结果。
     */
    private void stashResult(Context ctx, Bundle vars) {
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("xsocr_last", Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor e = sp.edit();
            for (String k : vars.keySet()) {
                // ★ 不能直接用 getString()：坐标是 putInt 写入的，
                //   对 Integer 调 getString 会抛 ClassCastException。
                //   统一走 String.valueOf(Object)，两种类型都安全。
                e.putString(k, String.valueOf(vars.get(k)));
            }
            e.putLong("ts", System.currentTimeMillis());
            e.apply();
        } catch (Throwable t) {
            Log.w(TAG, "存结果失败: " + t);
        }
    }

    /**
     * 构造本次执行的指纹，用于识别"宿主超时后的重发"。
     *
     * 用 配置内容 + 宿主给的超时提示 组合：
     *   同一次宏动作 → 配置一致 → 指纹一致 → 判定为重复。
     *   用户改了配置或再次手动触发 → 指纹变化 → 正常执行。
     *
     * 注意：不用时间戳参与计算，否则每次都不一样，就失去去重意义了。
     */
    private String buildFingerprint(Intent intent, Bundle config) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(config.getString(PluginContract.KEY_SOURCE, ""));
            sb.append('|').append(config.getString(PluginContract.KEY_PATH, ""));
            sb.append('|').append(config.getString(PluginContract.KEY_TIER, ""));
            sb.append('|').append(config.getInt(PluginContract.KEY_TYPE, 1));
            sb.append('|').append(config.getBoolean(PluginContract.KEY_JOIN_NEWLINE, true));
            // 指定文本 + 匹配方式也要参与指纹：改了查找规则，就是一次新任务
            sb.append('|').append(config.getString(PluginContract.KEY_MATCH_TEXT, ""));
            sb.append('|').append(config.getInt(PluginContract.KEY_MATCH_MODE,
                    PluginContract.MODE_CONTAINS));
            // 宿主每次 fire 会带一个超时提示，可以进一步增强区分度
            if (intent != null && intent.getExtras() != null) {
                sb.append('|').append(intent.getExtras().get("net.dinglisch.android.tasker.extras.HINTS"));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 确保截屏能力可用，必要时拉起授权界面。
     *
     * 【背景】
     * 无 root 时，截屏必须依赖 MediaProjection 授权，而该授权
     * 只能由 Activity 发起（createScreenCaptureIntent 是 Activity API）。
     * 插件的入口是 BroadcastReceiver，没法自己弹框。
     *
     * 【做法】
     * 拉起 ScreenCaptureRequestActivity（透明 Activity），由它：
     *   1. 检查悬浮窗权限（后台启动 Activity 的前提）
     *   2. 弹出系统截屏授权框
     *   3. 把授权结果交给 OCRService 建立投屏会话
     *   4. 通过静态回调通知本方法
     *
     * 【为什么要同步等待】
     * 本方法运行在插件的后台线程里，需要"等用户点完授权"才能继续识别，
     * 所以用 CountDownLatch 阻塞等待（带超时）。
     * ⚠️ 绝不能在主线程这么做，否则 ANR。
     *
     * @param deadline 截止时间（毫秒时间戳），等待不会超过它
     * @return true 表示截屏已可用
     */
    private boolean requestScreenCaptureIfNeeded(Context ctx, long deadline) {
        // 再次确认（可能刚好在别的途径里授权好了）
        if (OCRService.screenCaptureReady) {
            return true;
        }

        // 先确保服务在跑：授权结果要交给服务去建立投屏会话
        try {
            Intent svc = new Intent(ctx, OCRService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "启动服务失败（继续尝试授权）: " + t);
        }

        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final boolean[] granted = {false};

        // 注册回调：Activity 拿到授权结果后会调用它
        ScreenCaptureRequestActivity.setListener(ok -> {
            granted[0] = ok;
            latch.countDown();
        });

        // 拉起透明 Activity 去弹授权框。
        // 需要 FLAG_ACTIVITY_NEW_TASK（从非 Activity 上下文启动 Activity 的硬性要求）。
        try {
            Intent req = new Intent(ctx, ScreenCaptureRequestActivity.class);
            req.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ctx.startActivity(req);
            Log.i(TAG, "已拉起截屏授权界面，等待用户操作…");
        } catch (Throwable t) {
            Log.e(TAG, "拉起授权界面失败: " + t);
            ScreenCaptureRequestActivity.setListener(null);
            return false;
        }

        // 等待用户操作（最长等到 deadline，留 1 秒余量给后续识别）
        long remain = deadline - System.currentTimeMillis() - 1000;
        if (remain <= 0) {
            Log.w(TAG, "剩余预算不足，无法等待授权");
            ScreenCaptureRequestActivity.setListener(null);
            return false;
        }

        try {
            boolean done = latch.await(remain, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                Log.w(TAG, "等待截屏授权超时（" + remain + "ms）");
                ScreenCaptureRequestActivity.setListener(null);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ScreenCaptureRequestActivity.setListener(null);
            return false;
        }

        Log.i(TAG, "截屏授权结果: " + granted[0]);
        return granted[0];
    }

    /**
     * 等 HTTP 服务就绪，但最多等到 deadline。
     *
     * 与旧版 waitServerReady(15000) 的区别：
     *   旧版固定等 15 秒，而宿主预算只有 11 秒 —— 光等待就能把预算耗光。
     *   现在按 deadline 推进，把时间全部让给真正的识别。
     *
     * @return true 表示服务已就绪
     */
    private boolean waitServerReadyUntil(long deadline) {
        while (true) {
            if (Http.ping(PING_QUICK_MS)) return true;
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                Log.w(TAG, "等待服务就绪超时（预算用尽）");
                return false;
            }
            try {
                Thread.sleep(Math.min(150L, remain));
            } catch (InterruptedException e) {
                return Http.ping(PING_QUICK_MS);
            }
        }
    }

    /**
     * 等「模型加载完成」，但最多等到 deadline。
     *
     * 【为什么需要它】服务已改成「HTTP 先上线、模型后台加载」，
     * 于是 {@link Http#ping()} 通过（端口通了）**不再等于能识别**。
     * 冷启动时若紧接着 POST，会拿到 503「模型仍在加载中」——
     * 状态码虽然准确，但用户看到的是"宏跑失败"，属于修复⑥带来的行为回归。
     * 本方法把"等模型"这一步补上。
     *
     * 【探测方式】GET /last 是纯读操作、毫秒级；响应体里带 modelReady，
     * 加载失败时还有 modelError。失败属于**终局**，再等也没用，立即返回 false，
     * 不必把预算白白耗光。
     *
     * 【成本控制】确认成功后把时间戳记进 modelReadyAt，TTL 内直接放行，
     * 同一批宏触发的多次检查只付一次探测成本。
     *
     * 【已知的极小误差】若服务刚好在本 TTL 窗口内被重启，
     * 可能出现"最多 2 秒的乐观误判"；此时 POST 会拿到 503 而被
     * 解析成明确错误信息，不会静默失败，可以接受。
     *
     * @param deadline 截止时间（毫秒时间戳），等待不会超过它
     * @return true 表示模型已就绪
     */
    private boolean waitModelReadyUntil(long deadline) {
        while (true) {
            // 1) TTL 命中：刚刚确认过，直接放行（省一次往返）
            if (modelReadyAt > 0
                    && (System.currentTimeMillis() - modelReadyAt) < MODEL_READY_TTL_MS) {
                return true;
            }

            // 2) 问服务：/last 恒回 HTTP 200，body 里有 modelReady / modelError
            String body = Http.get("/last", 800);
            if (body != null) {
                boolean ready = false;
                boolean loadFailed = false;
                String err = "";
                try {
                    JSONObject o = new JSONObject(body);
                    ready = o.optBoolean("modelReady", false);
                    err = o.optString("modelError", "");
                    loadFailed = err != null && !err.isEmpty();
                } catch (Throwable t) {
                    // body 不是合法 JSON：理论上不该发生，当作"未确认"继续轮询
                    Log.w(TAG, "解析 /last 响应失败: " + t);
                }

                if (ready) {
                    modelReadyAt = System.currentTimeMillis();
                    return true;
                }
                if (loadFailed) {
                    Log.e(TAG, "模型加载失败（终局，不再等待）: " + err);
                    return false;
                }
            }
            // body == null：HTTP 层尚未完全就绪（ping 刚过，极少见），继续轮询

            // 3) 还没好：按 deadline 轮询
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                Log.w(TAG, "等待模型就绪超时（预算用尽）");
                return false;
            }
            try {
                Thread.sleep(Math.min(200L, remain));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "等待模型就绪被中断");
                return false;
            }
        }
    }

    private void fail(Bundle vars, String msg) {
        Log.e(TAG, "失败: " + msg);
        vars.putString(PluginContract.VAR_ERROR, msg == null ? "unknown" : msg);
        vars.putString(PluginContract.VAR_TEXT, "");
        vars.putString(PluginContract.VAR_MATCH, "false");
        // 坐标是整数变量，失败时给 -1
        vars.putInt(PluginContract.VAR_X, -1);
        vars.putInt(PluginContract.VAR_Y, -1);
    }

    /**
     * 判断本次执行是否失败。
     *
     * 约定：VAR_ERROR 有内容即为失败（成功时 doWork 会把它置空）。
     * 这样就不用再加一个专门的「是否成功」回传变量了。
     */
    private boolean isFailed(Bundle vars) {
        String err = vars.getString(PluginContract.VAR_ERROR);
        return err != null && !err.isEmpty();
    }
}