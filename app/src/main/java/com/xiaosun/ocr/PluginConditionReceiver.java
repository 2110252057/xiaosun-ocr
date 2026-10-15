package com.xiaosun.ocr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 条件（Condition）查询的应答者。
 *
 * ── 它在协议里的位置 ──────────────────────────────────────
 * 宿主（MacroDroid / Tasker）在两种时机发 QUERY_CONDITION 给本接收器：
 *
 *   1. **事件路径**：我们主动广播 REQUEST_QUERY 说「刚滑了一下」，
 *      宿主就回头发一条 QUERY_CONDITION 问「真的吗？哪个方向？」
 *      → 这条查询是一切的起点，宏因此被触发。
 *
 *   2. **条件路径**：宏执行到「条件/约束」环节时，
 *      宿主直接来问「现在这个条件成立吗？」
 *
 * 两种都通过 {@code setResultCode()} 回答，取值是 Locale 规范定的三个数：
 *      16 = SATISFIED   成立
 *      17 = UNSATISFIED 不成立
 *      18 = UNKNOWN     说不清（配置缺失 / 消息过期 / 无法判断）
 *
 * 18 很重要：宿主遇到它通常不会把宏当成"失败"，
 * 而是直接跳过这次判断 —— 于是伪造/过期的事件会被安静地忽略掉。
 *
 * ── ★ 变量回传（这是"触发器里变量都是空的"的修复点）────────
 *
 * 【之前的问题】
 * 本类原先只调 setResultCode()，一个变量都没回。
 * 于是事件触发器虽然能正常被触发（宏跑起来了），但宏里
 * 拿不到滑动菜单的选中结果，全是空的。
 *
 * 【为什么"事件"这条路必须自己回变量】
 * 动作（FireSetting）那条路是 PluginFireReceiver 在 doWork 末尾
 * 把变量塞进广播结果回传的 —— 那条路一直是好的。
 * 但事件触发器不经过 FireSetting：插件先广播 REQUEST_QUERY，
 * 宿主回问 QUERY_CONDITION，宏就靠这次查询的**应答**来拿上下文。
 * 应答里不带变量，宏自然什么都没接住。
 *
 * 【回哪去 —— 两条通道都写】
 * 参照 AutoInput（com.joaomgcd.autoinput，已验证能正常传变量的样板）：
 *
 *   通道 A：有序广播结果 extras
 *     Bundle out = getResultExtras(true);
 *     out.putBundle("net.dinglisch.android.tasker.extras.VARIABLES", vars);
 *
 *   通道 B：宿主塞进来的 ResultReceiver
 *     ResultReceiver rr = intent.getParcelableExtra(
 *             "net.dinglisch.android.tasker.EXTRA_RESULT_RECEIVER");
 *     rr.send(resultCode, vars);   // 结果码是 send 的**第一个参数**
 *
 * 我们两条都做。它们互相独立、互不干扰，
 * 但少了任何一条都可能在某些宿主/版本上表现为"变量为空"，
 * 而写两次的成本只是几行代码。宿主不认的那条它会自己忽略。
 *
 * ── 模式 ────────────────────────────────────────────────
 * 配置里带 plugin_mode：
 *   "event"（Tasker 事件，当前唯一入口）
 *                        → 必须先过 {@link TaskerEventProtocol} 的防伪校验，
 *                          再拿消息里带的方向与配置比对。
 *                          防伪失败一律回 18，绝不回 16。
 *   "state"（条件，已废弃）→ 仅为了不打断升级前配好的老宏而保留：
 *                          没有消息可校验，退化为「最近一次手势」判断。
 *                          条件身份已从 Manifest 撤除，新配置不会再产生它。
 *
 * 参照实现：com.dxam.presencedetector.LocaleConditionReceiver（只回结果码）、
 *           com.joaomgcd.autoinput（回结果码 + 变量，本类的变量部分照它写）
 */
public class PluginConditionReceiver extends BroadcastReceiver {

    private static final String TAG = "XsocrCondition";

    @Override
    public void onReceive(Context context, Intent intent) {
        // 先把要回的东西算出来，最后**统一在一个出口**送出去。
        //
        // 【为什么不各分支自己 setResultCode】
        // 因为现在除了结果码还要回变量，而变量要经两条通道送出；
        // 如果每个分支各写一遍，早晚出现"这个分支忘了回变量"——
        // 这次的 bug 就是这么来的。集中到 answer() 一个出口，
        // 新增分支时不会漏。
        int code;
        android.os.Bundle vars = null;
        try {
            code = handle(context, intent, vars = new android.os.Bundle());
        } catch (Throwable t) {
            // 接收器里绝不能抛异常出去：宿主把它看成"插件坏了"，
            // 之后可能就不再调用本插件了。
            Log.w(TAG, "处理条件查询失败: " + t);
            code = PluginContract.RESULT_CONDITION_UNKNOWN;
        }
        answer(intent, code, vars);
    }

    /**
     * 统一出口：把结果码与变量通过**两条通道**送回宿主。
     *
     * 通道 A = 有序广播结果 extras（宿主用 sendOrderedBroadcast 时有效）
     * 通道 B = 宿主塞进来的 ResultReceiver（Tasker 官方 EXTRA_RESULT_RECEIVER）
     *
     * 两条独立，各写各的；哪条宿主没在用，它自己会忽略。
     * 详见类注释里的「变量回传」一节。
     */
    private void answer(Intent intent, int code, android.os.Bundle vars) {
        // ---------- 通道 A：有序广播结果 ----------
        try {
            // getResultExtras(true) 拿到的是宿主那一侧的**同一个实例**，
            // 往里 put 才会被读到；自己 new 一个 Bundle 是白写。
            android.os.Bundle out = getResultExtras(true);
            if (out != null && vars != null && !vars.isEmpty()) {
                PluginContract.addVariableBundle(out, vars);
            }
            setResultCode(code);
        } catch (Throwable t) {
            // 极少数情况（不是有序广播）getResultExtras 会抛
            Log.w(TAG, "写广播结果失败: " + t);
            try {
                setResultCode(code);
            } catch (Throwable ignored) {
            }
        }

        // ---------- 通道 B：ResultReceiver ----------
        if (intent == null || vars == null || vars.isEmpty()) return;
        try {
            // 用 getParcelableExtra 的**旧签名**：minSdk 24 上还没有
            // 带 Class 参数的那个重载（API 33 才加）。
            Object o = intent.getParcelableExtra(PluginContract.EXTRA_RESULT_RECEIVER);
            if (o instanceof android.os.ResultReceiver) {
                // 结果码走 send() 的第一个参数，变量走第二个
                ((android.os.ResultReceiver) o).send(code, vars);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ResultReceiver 回传失败: " + t);
        }
    }

    /**
     * 判定逻辑：算出结果码，并把要回传的变量填进 {@code vars}。
     *
     * 只做判断、不发送 —— 发送统一由 {@link #answer} 负责。
     * 返回 Locale 的 16 / 17 / 18。
     */
    private int handle(Context context, Intent intent, Bundle vars) {
        // 只认 QUERY_CONDITION；别的（包括空 action）一律"说不清"
        if (intent == null
                || !PluginContract.ACTION_QUERY_CONDITION.equals(intent.getAction())) {
            return PluginContract.RESULT_CONDITION_UNKNOWN;
        }

        Bundle cfg;
        try {
            cfg = intent.getBundleExtra(PluginContract.EXTRA_BUNDLE);
        } catch (Throwable t) {
            cfg = null;
        }
        if (cfg == null) {
            // 宿主没把配置递回来 —— 没法判断
            return PluginContract.RESULT_CONDITION_UNKNOWN;
        }

        String mode = cfg.getString(PluginContract.KEY_PLUGIN_MODE,
                PluginContract.MODE_EVENT);
        String wantDir = cfg.getString(PluginContract.KEY_GS_DIR, "");
        if (wantDir == null) wantDir = "";
        boolean strict = cfg.getBoolean(PluginContract.KEY_GS_STRICT, true);

        // 现在只有事件一种触发器身份（条件身份已撤除）。
        // 但老配置里可能仍留着 plugin_mode="state"（用户升级前配的宏），
        // 那种配置没有防伪数据可校验，只能退化为「最近一次手势」判断 ——
        // 照旧应答，别让升级后老宏直接失效。
        if (PluginContract.MODE_STATE.equals(mode)) {
            return answerState(wantDir, vars);
        }

        return answerEvent(intent, wantDir, strict, vars);
    }

    /**
     * 事件模式：消息必须是我们刚签发的，且方向与配置相符。
     *
     * strict=false 时跳过防伪，只按「最近一次手势」判断 ——
     * 留给那些不把 PASS_THROUGH_DATA 递回来的宿主（各版本行为不一），
     * 代价是失去防伪造能力，用户可在配置界面自己权衡。
     *
     * ★ 只要这次判定与「最近一次手势」有关，就把手势变量一并填进 vars。
     *   宏要靠这些变量分支，回空等于白触发。
     */
    private int answerEvent(Intent intent, String wantDir, boolean strict, Bundle vars) {
        if (strict) {
            if (!TaskerEventProtocol.hasValidMessage(intent)) {
                // 伪造 / 重放 / 过期 —— 安静地当作"说不清"。
                // 这时**不回变量**：这不是一次真实手势，回变量会让宏拿到
                // 上一次的陈旧值当真。
                return PluginContract.RESULT_CONDITION_UNKNOWN;
            }
            String gotDir = TaskerEventProtocol.getEventState(intent);
            // 消息是真的：这就是刚发生的那次手势，变量照实回。
            fillVars(vars);
            if (dirMatches(wantDir, gotDir)) {
                return PluginContract.RESULT_CONDITION_SATISFIED;
            }
            // 消息是真的，但不是这个宏关心的那个方向
            return PluginContract.RESULT_CONDITION_UNSATISFIED;
        }

        // 非严格：只能看进程内的最近一次手势快照
        if (recentGestureFresh()) {
            fillVars(vars);
            if (dirMatches(wantDir, EdgeGesturePrefs.gsLastDirection)) {
                return PluginContract.RESULT_CONDITION_SATISFIED;
            }
            return PluginContract.RESULT_CONDITION_UNSATISFIED;
        }
        return PluginContract.RESULT_CONDITION_UNKNOWN;
    }

    /**
     * 条件模式：没有消息可查，只看「最近一次手势」。
     *
     * 有效窗口取 60 秒（和消息有效期一致）：
     * 太短会导致宏执行到条件时已经开始下载、早就过期；
     * 太长则会让"很久以前滑的那一下"反复满足条件。
     * 对"滑动一下 → 立刻做某事"的用法，60 秒足够宽松。
     */
    private int answerState(String wantDir, Bundle vars) {
        if (!recentGestureFresh()) {
            return PluginContract.RESULT_CONDITION_UNSATISFIED;
        }
        fillVars(vars);
        if (dirMatches(wantDir, EdgeGesturePrefs.gsLastDirection)) {
            return PluginContract.RESULT_CONDITION_SATISFIED;
        }
        return PluginContract.RESULT_CONDITION_UNSATISFIED;
    }

    /**
     * 把菜单变量填进 {@code vars}。
     *
     * 组装逻辑放在 {@link PluginContract#buildMenuVars()}。
     */
    private void fillVars(Bundle vars) {
        if (vars == null) return;
        try {
            vars.putAll(PluginContract.buildMenuVars());
        } catch (Throwable t) {
            Log.w(TAG, "填入菜单变量失败: " + t);
        }
    }

    /**
     * 配置方向与本次手势方向是否相符。
     *
     * 配置为空串 = "任意方向"，任何手势都算命中。
     */
    private boolean dirMatches(String wantDir, String gotDir) {
        if (wantDir == null || wantDir.isEmpty()) return true;
        if (gotDir == null) gotDir = "";
        return wantDir.equals(gotDir);
    }

    /**
     * 最近一次手势是否还在有效期内。
     *
     * 静态快照在进程重启后会是 0，此时自然返回 false ——
     * 这是对的：重启后没滑过，就不该认。
     */
    private boolean recentGestureFresh() {
        long at = EdgeGesturePrefs.gsLastAt;
        if (at <= 0L) return false;
        return System.currentTimeMillis() - at <= PluginContract.MESSAGE_VALID_MS;
    }
}