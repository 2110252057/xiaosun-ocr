package com.xiaosun.ocr;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityManager;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 前台应用侦测 —— 「手柄黑名单」的判据来源。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么要单独一个类】
 * ════════════════════════════════════════════════════════════════
 * 手柄是悬浮窗，它自己收不到「用户切到了哪个 App」这件事 ——
 * 悬浮窗只会拿到落在自己那一小块区域里的触摸。
 * 想知道前台是谁，只能靠无障碍服务订阅
 * {@code TYPE_WINDOW_STATE_CHANGED}：事件里直接带 packageName，
 * **同步、零延迟**，切换应用那一刻就知道。
 *
 * ════════════════════════════════════════════════════════════════
 * 【取舍：只有这一条路，没有兜底】
 * ════════════════════════════════════════════════════════════════
 * 另一条公开路子是 {@code UsageStatsManager.queryEvents}：不需要无障碍，
 * 但要单独授「使用情况访问」权限，而且系统落库有延迟 —— 表现为
 * 「切进拉黑的应用，手柄还残留一下」。既然无论如何都要开无障碍才能
 * 做到「切过去立刻消失」，那条更慢、还多要一次授权的通道就不留了：
 * 少一条通道 = 少一堆「为什么有时生效有时不生效」的问题。
 *
 * 没开无障碍时**一律当作「不在名单里」** —— 宁可手柄多显示一会儿，
 * 也不要它莫名其妙消失、用户完全不知道原因。设置页会明确提示未就绪。
 */
final class ForegroundAppDetector {

    /**
     * 本应用无障碍服务的类名（短名）。
     *
     * 用来在系统那份「已启用服务」列表里认出自己 —— 该列表里可能同时有
     * 别的应用的无障碍服务，必须按「包名 + 类名」一起匹配。
     */
    private static final String SERVICE_CLASS_SIMPLE_NAME = "OcrAccessibilityService";

    /**
     * 这些包名不代表「用户打开了某个应用」，收到就忽略（保持上一次的状态）。
     *
     * 典型场景：从被拉黑的应用里下拉通知栏 —— 系统会报一条
     * {@code com.android.systemui} 的窗口事件，若照单全收，手柄就会
     * 在通知栏后面「闪」出来，收起来又消失。忽略掉更符合预期。
     */
    private static final String[] IGNORED_PACKAGES = {
            "com.android.systemui",
            "com.android.permissioncontroller",
            "android",
    };

    /** 已连接的无障碍服务（弱引用：服务随时可能被系统解绑）。 */
    private static WeakReference<AccessibilityService> svcRef =
            new WeakReference<AccessibilityService>(null);

    /** 最近一次无障碍报上来的前台包名。 */
    private static volatile String a11yPkg = "";
    /**
     * 本次连接以来是否真的收到过窗口状态事件。
     *
     * 【为什么用「收到过」而不是去读配置】
     * 想问系统「你到底给我订了哪些事件」，最直觉的是
     * {@code getServiceInfo().getEventTypes()} —— 但那是 @hide 接口，
     * 编译期就过不去（android.jar 里没有这个方法），用它等于把自己绑死在
     * 反射上，还得处理各家 ROM 的差异。
     *
     * 而窗口事件是**必然会来**的：切一次应用就来一条。所以「收到过没有」
     * 已经是我们要的全部信息 —— 它直接反映「这条通道此刻能不能用」，
     * 比读配置更实在（配置写了但系统没生效的情况它也照样能发现）。
     */
    private static volatile boolean sawWindowEvent = false;

    private ForegroundAppDetector() {
    }

    // ============================================================
    //  无障碍通道
    // ============================================================

    /** 无障碍服务连上时由 {@link OcrAccessibilityService} 调用。 */
    static void bind(Context c, AccessibilityService s) {
        svcRef = new WeakReference<AccessibilityService>(s);
        a11yPkg = "";
        sawWindowEvent = false;
    }

    /** 无障碍服务断开时调用。 */
    static void unbind(AccessibilityService s) {
        if (s != null && svcRef.get() == s) {
            svcRef = new WeakReference<AccessibilityService>(null);
            a11yPkg = "";
            sawWindowEvent = false;
        }
    }

    /**
     * 无障碍事件到达 —— 只挑「窗口状态变化」这一种，只取包名。
     *
     * 注意：这里**不读** {@code event.getText()} / {@code getContentDescription()}
     * 之类的任何内容字段，拿到的只有「哪个应用到了前台」。
     */
    static void onWindowChanged(CharSequence pkgName) {
        if (pkgName == null) return;
        String s = pkgName.toString().trim();
        if (s.length() == 0) return;
        for (String ignored : IGNORED_PACKAGES) {
            if (ignored.equals(s)) return;
        }
        a11yPkg = s;
        sawWindowEvent = true;
        // 叫醒手柄服务，让它立刻复核一次要不要藏。
        // 放在这里而不是调用方，是为了让「刷新快照」和「通知消费方」
        // 永远绑在一起 —— 将来多一个调用者也不会漏掉这一步。
        EdgeGestureService.onForegroundMaybeChanged();
    }

    /**
     * 无障碍这条通道**此刻能不能真的报出前台应用**。
     *
     * 两个条件缺一不可：
     *   ① 服务已连接；
     *   ② 本次连接以来确实收到过窗口事件。
     *
     * 第 ② 条就是在检测「配置里订了事件，但系统没给」这种情况：
     * 用户升级应用后，系统里勾选的还是旧配置（当时没订阅事件），
     * 服务照样连得上、照样能截图，但窗口事件一条都不会来。
     * 此时若仍宣称「无障碍可用」，黑名单就永远不生效，还没有任何提示。
     *
     * 代价是：刚开启服务的头几秒（用户还没切过应用）会判成「不可用」，
     * 界面会显示「需重新开启」。为此 {@link #a11yPending()} 把
     * 「已连接但还没收到事件」单独描述成一句话，而不是谎称能用。
     */
    static boolean a11yUsable() {
        return a11yConnected() && sawWindowEvent;
    }

    /** 无障碍服务是否已连接（连上不等于能报事件，判断请用 {@link #a11yUsable()}）。 */
    static boolean a11yConnected() {
        return svcRef.get() != null;
    }

    /**
     * 系统里是否**已勾选**本应用的无障碍服务。
     *
     * 与 {@link #a11yUsable()} 的区别很重要，两者不能混用：
     *   · 本方法问的是「用户在系统设置里开了没有」—— 查的是系统那份
     *     已启用服务列表，不依赖本进程里的服务实例，**随时可问、立即准确**；
     *   · a11yUsable 问的是「这一刻能不能真的报出前台应用」—— 需要收到过
     *     窗口事件，刚开启的头几秒必然是 false。
     *
     * 「没开无障碍就把开关置灰」必须用本方法：若用 a11yUsable，用户
     * 按提示去系统设置里开了服务、切回来一看开关还是灰的（因为还没切过
     * 应用、没收到事件），会以为白开了。
     */
    static boolean a11yEnabled(Context c) {
        if (c == null) return false;
        try {
            AccessibilityManager am =
                    (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) return false;
            String self = c.getPackageName();
            for (AccessibilityServiceInfo info : list) {
                if (info == null) continue;
                ResolveInfo ri = info.getResolveInfo();
                if (ri == null || ri.serviceInfo == null) continue;
                if (!self.equals(ri.serviceInfo.packageName)) continue;
                String cls = ri.serviceInfo.name;
                if (cls != null && cls.endsWith(SERVICE_CLASS_SIMPLE_NAME)) return true;
            }
        } catch (Throwable ignored) {
            // 极少数 ROM 在无障碍未启用时会抛，当作「没开」
        }
        return false;
    }

    // ============================================================
    //  对外：当前前台是谁
    // ============================================================

    /**
     * 当前前台应用的包名；还没收到过窗口事件时返回空串。
     *
     * 这里**不必判断「新鲜度」**：用户停在同一个应用里一小时，无障碍
     * 不会一直有新事件，但上一次的值依然正确 —— 这时候若因为「太久没更新」
     * 而返回空串，黑名单就会「用着用着手柄自己冒出来」。
     * 值只在两处被清空：服务断开、以及服务重新连接（{@link #bind}）。
     */
    static String currentForeground(Context c) {
        return a11yPkg;
    }

    /**
     * 这个包名现在「该不该被黑名单藏起来」。
     *
     * 两种模式共用一套配置，只差一个取反：
     *   黑名单模式（默认）：名单里的藏起来；
     *   白名单模式：名单里的**才显示**，其余全藏。
     *
     * 包名为空（判断不出前台）时一律不藏 —— 见类注释最后那段。
     */
    static boolean isSuppressed(Context c, String pkg) {
        if (c == null || pkg == null || pkg.length() == 0) return false;
        if (!EdgeGesturePrefs.isBlacklistEnabled(c)) return false;
        boolean listed = EdgeGesturePrefs.isBlacklisted(c, pkg);
        boolean white = EdgeGesturePrefs.isBlacklistWhitelistMode(c);
        return listed != white;
    }

    /**
     * 无障碍「连上了但还没证明能用」—— 服务在连、却一条窗口事件都没收到。
     *
     * 两种可能，界面要能把它们都提示到：
     *   · 刚开启，用户还没切过应用（几秒内会出现事件，属正常）；
     *   · 系统里勾选的还是旧配置（升级前那份没订阅事件），
     *     这种情况事件永远不来，必须让用户关掉再开一次。
     */
    static boolean a11yPending() {
        return a11yConnected() && !sawWindowEvent;
    }

    /** 能不能用黑名单这件事（只要无障碍已勾选就算，不要求已收到过事件）。 */
    static boolean anyChannel(Context c) {
        return a11yEnabled(c) || a11yUsable();
    }
}