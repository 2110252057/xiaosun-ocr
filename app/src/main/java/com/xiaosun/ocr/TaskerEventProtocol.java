package com.xiaosun.ocr;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tasker「事件」协议的防伪层。
 *
 * ── 它解决什么问题 ────────────────────────────────────────
 * 事件型插件的触发方式很反直觉：不是宿主来问，而是**插件主动喊**。
 * 手势发生的那一刻，我们广播一条
 *     com.twofortyfouram.locale.intent.action.REQUEST_QUERY
 * 告诉宿主「我这边有事了，快来问我」，宿主再回头发 QUERY_CONDITION 问细节。
 *
 * 这里有个洞：REQUEST_QUERY 是**隐式广播**，谁都能发。
 * 于是任何 App 只要照着抄一条，就能让用户的宏凭空跑起来 ——
 * 而宏里往往跟着的是「点击屏幕」「发短信」这种有副作用的动作。
 *
 * 解法是给每次上报签一个**随机号**，并记下签发时刻：
 *   上报时：新随机号 → 存进表 → 塞进 PASS_THROUGH_DATA 一起发给宿主
 *   查询时：宿主把原样的 Bundle 递回来 → 我们核对
 *             ① 号在表里（是我们签发的，不是伪造的）
 *             ② 距今不超过 60 秒（不是重放很久以前的旧广播）
 *   两条都过才认。
 *
 * 表用 {@link LinkedHashMap} + 超时剔除，最多留 100 条，
 * 所以内存开销可忽略，也不会无限增长。
 *
 * ── 为什么时间基准用 elapsedRealtime ──────────────────────
 * 不用 currentTimeMillis：系统时间可能被 NTP 校正或被用户手动改，
 * 那样"60 秒有效期"会算错；elapsedRealtime 是自开机起的单调时钟，
 * 只受休眠影响，正适合做有效期判断。
 * （这一点和原版 com.dxam.presencedetector 的实现一致。）
 *
 * 参照实现：com.dxam.presencedetector.TaskerEventProtocol
 */
public final class TaskerEventProtocol {

    private static final String TAG = "XsocrTaskerEvent";

    /** 业务数据（含 message id）在 Intent 里的 key。 */
    private static final String EXTRA_PASS_THROUGH_DATA =
            PluginContract.EXTRA_PASS_THROUGH_DATA;
    private static final String KEY_MESSAGE_ID = PluginContract.EXTRA_MESSAGE_ID;

    /**
     * 我们自己塞进 PASS_THROUGH_DATA 的业务字段：这次手势是哪个方向。
     *
     * 原版放的是 presence 状态（PRESENT/ABSENT），
     * 我们换成方向名（"right" / 空串=任意），语义等价。
     */
    public static final String KEY_EVENT_STATE = "com.xiaosun.ocr.event.STATE";

    /** 消息有效期 60 秒。 */
    private static final long MESSAGE_VALID_MS = PluginContract.MESSAGE_VALID_MS;
    /** 表容量上限。 */
    private static final int MAX_RECENT_MESSAGES = PluginContract.MAX_RECENT_MESSAGES;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 已签发但尚在有效期内的消息号 → 签发时刻(elapsedRealtime)。 */
    private static final LinkedHashMap<Integer, Long> RECENT_MESSAGES = new LinkedHashMap<>();

    private TaskerEventProtocol() {
    }

    /**
     * 给一条要发出去的 REQUEST_QUERY 附上防伪数据。
     *
     * @param intent 即将 sendBroadcast 的 Intent（会被就地修改）
     * @param state  本次事件的业务值，这里传手势方向（空串 = 任意方向）
     */
    public static void addEventData(Intent intent, String state) {
        if (intent == null) return;
        try {
            int id = newMessageId();

            Bundle data = new Bundle();
            data.putInt(KEY_MESSAGE_ID, id);
            data.putString(KEY_EVENT_STATE, state == null ? "" : state);

            intent.putExtra(EXTRA_PASS_THROUGH_DATA, data);
        } catch (Throwable t) {
            Log.w(TAG, "附加防伪数据失败: " + t);
        }
    }

    /**
     * 校验宿主递回来的 Intent 是不是我们刚签发的。
     *
     * @return true = 号在表内且未过期，可以信
     */
    public static boolean hasValidMessage(Intent intent) {
        if (intent == null) return false;
        Bundle data;
        try {
            data = intent.getBundleExtra(EXTRA_PASS_THROUGH_DATA);
        } catch (Throwable t) {
            // 宿主可能塞了个类型不对的东西进去
            return false;
        }
        if (data == null) return false;
        if (!data.containsKey(KEY_MESSAGE_ID)) return false;

        int id;
        try {
            id = data.getInt(KEY_MESSAGE_ID, -1);
        } catch (Throwable t) {
            return false;
        }

        long now = SystemClock.elapsedRealtime();
        synchronized (RECENT_MESSAGES) {
            pruneLocked(now);
            Long signedAt = RECENT_MESSAGES.get(id);
            if (signedAt == null) return false;
            // 允许等于 60 秒（原版是 <=）
            return now - signedAt <= MESSAGE_VALID_MS;
        }
    }

    /**
     * 从宿主的查询 Intent 里取出本次事件的业务值（手势方向）。
     *
     * @return 方向名；取不到时返回空串（由调用方按「任意方向」处理）
     */
    public static String getEventState(Intent intent) {
        if (intent == null) return "";
        try {
            Bundle data = intent.getBundleExtra(EXTRA_PASS_THROUGH_DATA);
            if (data == null) return "";
            String s = data.getString(KEY_EVENT_STATE);
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 生成一个没用过的随机消息号，并登记签发时刻。内部自带同步。 */
    private static int newMessageId() {
        long now = SystemClock.elapsedRealtime();
        synchronized (RECENT_MESSAGES) {
            pruneLocked(now);

            int id;
            do {
                // 取正数，避免和 Bundle 里"缺省值 -1"撞车
                id = RANDOM.nextInt(Integer.MAX_VALUE);
            } while (RECENT_MESSAGES.containsKey(id));

            RECENT_MESSAGES.put(id, now);

            // 超容量就从头（最旧的）删，直到不超
            while (RECENT_MESSAGES.size() > MAX_RECENT_MESSAGES) {
                Iterator<Integer> it = RECENT_MESSAGES.keySet().iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
            return id;
        }
    }

    /** 剔除过期项。调用方**必须**已持有 RECENT_MESSAGES 的锁。 */
    private static void pruneLocked(long now) {
        Iterator<Map.Entry<Integer, Long>> it = RECENT_MESSAGES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            if (now - e.getValue() > MESSAGE_VALID_MS) {
                it.remove();
            }
        }
    }

    /** 供调试：当前表里还有几条有效消息。 */
    public static int pendingCount() {
        synchronized (RECENT_MESSAGES) {
            pruneLocked(SystemClock.elapsedRealtime());
            return RECENT_MESSAGES.size();
        }
    }
}