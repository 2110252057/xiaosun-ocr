package com.xiaosun.ocr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

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

        // ---------- 1. 确保服务在跑 ----------
        // 服务承载 HTTP 接口和 native 识别能力，必须先就绪。
        //
        // 注意两点：
        //   a) Android 12+ 不允许后台应用 startForegroundService()，
        //      会抛 ForegroundServiceStartNotAllowedException。
        //   b) 【踩过的坑】探测必须用轻量请求。
        //      早先用 POST {} 探测，服务端会走去「截屏」分支、
        //      耗时可达 15 秒，导致误判"服务未就绪"而白等到超时。
        //      现在 ping() 走 GET /last，是纯读操作，毫秒级返回。
        if (!Http.ping(PING_QUICK_MS)) {
            try {
                Intent svc = new Intent(ctx, OCRService.class);
                ctx.startForegroundService(svc);
            } catch (Throwable t) {
                Log.w(TAG, "启动服务失败: " + t);
            }
        }
        if (!waitServerReadyUntil(deadline)) {
            fail(vars, "OCR 服务未就绪（预算 " + HOST_BUDGET_MS + "ms 用尽）。"
                    + "请先打开一次「小孙OCR」App，让服务保持运行。");
            return;
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
            //   原因：坐标变量 %xsocr_x/%xsocr_y 需要引擎返回 x/y 字段，
            //   而 type=1 的响应里根本没有坐标。如果跟着用户选的
            //   「纯文本」走，坐标就永远是 -1。
            //   "纯文本 vs 含坐标" 只影响 %xsocr_text 的拼接样式，
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
                String wantLower = want.toLowerCase();
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
                        String textLower = text.toLowerCase();
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