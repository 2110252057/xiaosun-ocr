package com.xiaosun.ocr;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityManager;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final Set<String> IGNORED_PACKAGES = new HashSet<String>(Arrays.asList(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "android",
            // 权限 / 安装器在各家 ROM 上的其它别名
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.lbe.security.miui"
    ));

    /** 前台判定的日志标签。窗口事件不频繁，逐条记下来才能复盘「手柄怎么冒出来的」。 */
    private static final String TAG_FG = "XiaoSunOCR-FG";

    /** 应用上下文（{@link #bind} 时保存），只用来查包信息。 */
    private static volatile Context appCtx;

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
        if (c != null) appCtx = c.getApplicationContext();
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
     *
     * ★ 为什么不能直接用事件里那个包名
     * 窗口状态事件不只在「Activity 切换」时来 —— ROM 的浮层、弹窗、
     * 游戏加速提示只要抢到焦点，也会报一条，包名却是那个浮层所属的应用。
     * 实测：进「穿越火线」时 cf 刚把包名报上来 0.4 秒，MIUI 游戏加速
     * （com.miui.securitycenter）就报一条浮层事件，前台被改判成安全中心，
     * 黑名单判定随之翻成「不在名单里」，手柄被重新挂回屏幕 ——
     * 用户看到的就是「拉黑了却还是显示」。
     *
     * 所以这里把事件包名当成「候选前台」，再用 {@link #looksLikeUserApp(String)}
     * 把它挡掉：ROM 的浮层多半是系统预装应用，而用户真正打开的应用几乎都有启动图标。
     */
    static void onWindowChanged(CharSequence pkgName) {
        if (pkgName == null) return;
        String reported = pkgName.toString().trim();
        if (reported.length() == 0) return;

        // 走到这里说明通道确实在报事件 —— 哪怕这次不采纳它的包名，
        // 「收到过窗口事件」这件事本身是成立的，界面上的就绪提示据此收敛。
        sawWindowEvent = true;

        String fg = resolveForeground(reported);
        // 被过滤掉（系统浮层）时前台保持上一次的值不动。
        // 若此前还没有任何值，a11yPkg 就继续留空，isSuppressed 对空串一律
        // 返回「不藏」，效果等同「显示手柄」，不会因一条浮层事件就误藏。
        if (fg == null || fg.length() == 0) return;

        if (fg.equals(a11yPkg)) return;   // 前台没变，不必重算
        AppLog.i(TAG_FG, "前台应用: " + (a11yPkg.length() == 0 ? "(未知)" : a11yPkg)
                + " → " + fg);
        a11yPkg = fg;
        // 叫醒手柄服务，让它立刻复核一次要不要藏。
        // 放在这里而不是调用方，是为了让「刷新快照」和「通知消费方」
        // 永远绑在一起 —— 将来多一个调用者也不会漏掉这一步。
        EdgeGestureService.onForegroundMaybeChanged();
    }

    /**
     * 这次窗口事件该不该被采纳，采纳的话前台算谁。
     *
     * @return 新的前台包名；{@code null} 表示这次事件不足以改变判断。
     */
    private static String resolveForeground(String reported) {
        // ① 用户显式写进名单的包名，一律采纳 —— 优先级最高。
        // 用户在名单里点了名，说明它就「该被当作一个应用」；不能因为它是
        // 系统预装、没有启动图标，就把它的窗口事件当浮层丢掉。
        Context c = appCtx;
        if (c != null && EdgeGesturePrefs.isBlacklisted(c, reported)) {
            return reported;
        }
        if (IGNORED_PACKAGES.contains(reported)) {
            // 通知栏、权限弹窗这类：保持上一次的前台不动，
            // 否则手柄会在浮层后面闪一下再消失。
            AppLog.d(TAG_FG, "忽略窗口事件（系统浮层）: " + reported
                    + "，前台仍为 " + (a11yPkg.length() == 0 ? "(未知)" : a11yPkg));
            return null;
        }
        // ② 系统预装应用（除桌面）不可能是「用户打开的那个 App」，
        // 它们的窗口事件都是浮层：MIUI 游戏加速、侧边栏、流量悬浮窗……
        // 实测就是 com.miui.securitycenter 把 cf 的前台判定顶掉的。
        if (!looksLikeUserApp(reported)) {
            AppLog.i(TAG_FG, "忽略窗口事件（系统应用/浮层）: " + reported
                    + "，前台仍为 " + (a11yPkg.length() == 0 ? "(未知)" : a11yPkg));
            return null;
        }
        return reported;
    }

    /** 包名 → 「像不像用户主动打开的应用」的缓存（同一包只查一次）。
     *  无障碍回调与主线程都可能走到这里，用并发容器避免并发读写。 */
    private static final Map<String, Boolean> USER_APP_CACHE =
            new ConcurrentHashMap<String, Boolean>();

    /**
     * 这个包名像不像「用户主动打开的应用」。
     *
     * 判据（依次，命中即返回）：
     *   ① 是桌面 → 是。桌面必须最先判断：MIUI 的 {@code com.miui.home}
     *      只有 HOME 没有 LAUNCHER，走「有无启动图标」会被误判成浮层，
     *      结果就是「从被拉黑的应用退回桌面，手柄不回来」；
     *   ② 有启动图标（能响应 MAIN/LAUNCHER）→ 是；
     *   ③ 是系统预装应用（FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP）→ 不是。
     *      这类多半是 ROM 的浮层（游戏加速、侧边栏、安全中心弹窗）；
     *      实测 com.miui.securitycenter 就是 SYSTEM + 无启动图标；
     *   ④ 其余（普通第三方应用）→ 是。
     */
    private static boolean looksLikeUserApp(String pkg) {
        Boolean cached = USER_APP_CACHE.get(pkg);
        if (cached != null) return cached;
        boolean ok = true;
        Context c = appCtx;
        if (c != null) {
            try {
                PackageManager pm = c.getPackageManager();
                if (isHomePackage(pm, pkg)) {
                    ok = true;
                } else if (hasLauncherIcon(pm, pkg)) {
                    ok = true;
                } else {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    boolean system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                            | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    ok = !system;
                }
            } catch (Throwable t) {
                // 查不到包信息就当作「是」—— 宁可恢复手柄，也不要它被永久藏住
                ok = true;
            }
        }
        USER_APP_CACHE.put(pkg, Boolean.valueOf(ok));
        return ok;
    }

    /**
     * 这个包是不是桌面。
     *
     * 用 {@code queryIntentActivities}（不限「默认」）而不是
     * {@code resolveActivity}：用户装了第三方桌面、或系统里有多个
     * Launcher 时，只要这个包能当桌面就应该被当作用户可见界面。
     */
    private static boolean isHomePackage(PackageManager pm, String pkg) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> rs = pm.queryIntentActivities(home, 0);
            if (rs != null) {
                for (ResolveInfo ri : rs) {
                    if (ri != null && ri.activityInfo != null
                            && pkg.equals(ri.activityInfo.packageName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 查询失败当作「不是桌面」，继续往下走其它判据
        }
        return false;
    }

    /** 这个包有没有启动图标（能响应 MAIN + LAUNCHER）。 */
    private static boolean hasLauncherIcon(PackageManager pm, String pkg) {
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            launcher.setPackage(pkg);
            List<ResolveInfo> rs = pm.queryIntentActivities(launcher, 0);
            return rs != null && !rs.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
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