package com.xiaosun.ocr;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍服务的管理核心：**用 root 直接读写系统的「已启用无障碍服务」清单**。
 *
 * <h3>为什么非得用 root</h3>
 * Android 没有给第三方应用留任何「打开别的应用的无障碍服务」的 API。
 * {@code Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} 这个值虽然存在，
 * 但普通应用往系统 settings 里写会直接抛
 * {@code SecurityException: You do not have android.permission.WRITE_SECURE_SETTINGS}。
 * 唯一的正规途径是 {@code Settings.ACTION_ACCESSIBILITY_SETTINGS} 让用户手点 ——
 * 而「一次给几十个应用逐个点一长串确认弹窗」正是用户想避开的场景。
 *
 * <p>所以本类走 root：
 * <pre>
 *   # 读
 *   settings get secure enabled_accessibility_services
 *   # 写（整串覆盖）
 *   settings put secure enabled_accessibility_services "a.b/.Svc1:a.c/.Svc2"
 *   settings put secure accessibility_enabled 1
 * </pre>
 *
 * <h3>这个值到底长什么样</h3>
 * 是一串用 <b>冒号分隔</b> 的 {@code 包名/服务类全名}：
 * <pre>
 *   com.foo/.FooService:com.bar/com.bar.BarService
 * </pre>
 * 两处要注意：
 * <ul>
 *   <li>类名可能写成 {@code .FooService}（相对包名），也可能写全名
 *       {@code com.foo.FooService}。读出来统一展开成全名再比较，
 *       否则「看见已开启、判定成没开」。</li>
 *   <li>值可能是 {@code null}（一个都没开时 settings 里这个键根本不存在），
 *       也可能是一个单独的 {@code null} 字符串字面量 —— 两种都当空集处理。</li>
 * </ul>
 *
 * <h3>为什么写完还要改 accessibility_enabled</h3>
 * 系统读的是「enabled_accessibility_services 非空」<b>且</b>
 * 「accessibility_enabled = 1」两个条件。有些 ROM 上删空清单后
 * accessibility_enabled 会停在 1，有些系统版本又会把它自动置 0。
 * 所以这里明确地把两者一起写，避免出现「清单里有值但服务不生效」。
 *
 * <h3>安全性</h3>
 * 所有方法都不抛异常：root 不可用、命令超时、su 被拒时返回
 * {@link Result#ok} = false 并带上可读的原因。调用方（Activity）只负责展示。
 */
public final class A11yManager {

    private static final String TAG = "XSOCR-A11yMgmt";

    /** su 命令的默认超时。首次调用 Magisk 会弹授权框，给足时间让用户点。 */
    private static final long SU_TIMEOUT_MS = 20_000L;

    private static final String KEY_SERVICES = "enabled_accessibility_services";
    private static final String KEY_A11Y_ON = "accessibility_enabled";

    private A11yManager() {
    }

    // =====================================================================
    //  数据结构
    // =====================================================================

    /** 某个应用暴露出来的一个无障碍服务。 */
    public static final class ServiceInfo {
        /** 所属应用包名。 */
        public final String pkg;
        /** 服务类全名。 */
        public final String cls;
        /** 在系统设置里显示的名字（应用的 label + 服务名）。 */
        public final String label;

        ServiceInfo(String pkg, String cls, String label) {
            this.pkg = pkg;
            this.cls = cls;
            this.label = label;
        }

        /** 系统 settings 里代表这个服务的字符串：{@code 包名/类全名}。 */
        public String flatten() {
            return pkg + "/" + cls;
        }

        @Override
        public String toString() {
            return label + " (" + pkg + ")";
        }
    }

    /** 一次 root 操作的返回值。{@code ok=false} 时 {@code message} 说明原因。 */
    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        static Result ok() {
            return new Result(true, "");
        }

        static Result fail(String msg) {
            return new Result(false, msg);
        }
    }

    // =====================================================================
    //  root 探测
    // =====================================================================

    /** root 探测结果缓存：探测本身很便宜，但会起 su 进程，没必要每次刷新页面都做。 */
    private static volatile Boolean cachedRoot;
    private static volatile String cachedRootDetail = "";

    /**
     * 是否有可用的 root。
     *
     * <p>判据是 {@code su -c id} 能跑通且输出里有 {@code uid=0}。
     * 只看退出码不够 —— 有的 su 实现（比如某些旧版 SuperSU）在拒绝授权时
     * 依然返回 0，只是不输出任何东西。
     *
     * <p>结果会缓存。用户在 Magisk 里重新授权后可以调 {@link #resetRootCache()}。
     */
    public static boolean isRootAvailable() {
        Boolean c = cachedRoot;
        if (c != null) {
            return c;
        }
        ShellOut out = runSu("id", SU_TIMEOUT_MS);
        boolean ok = out != null && out.exit == 0 && out.text.contains("uid=0");
        cachedRootDetail = ok ? "已获得 root" : (out == null ? "su 不可用或超时" : "su 未授权");
        cachedRoot = ok;
        if (!ok) {
            AppLog.w(TAG, "root 探测失败: " + cachedRootDetail);
        }
        return ok;
    }

    /** root 探测结果的文字说明，用于界面展示。 */
    public static String rootDetail() {
        isRootAvailable();
        return cachedRootDetail;
    }

    /** 清掉 root 探测缓存（用户刚在 Magisk 里点了授权时用）。 */
    public static void resetRootCache() {
        cachedRoot = null;
        cachedRootDetail = "";
    }

    // =====================================================================
    //  读：系统当前启用了哪些无障碍服务
    // =====================================================================

    /**
     * 读出系统当前启用的无障碍服务集合，元素是展开后的
     * {@code 包名/类全名}。
     *
     * <p>root 不可用时返回空集 —— 调用方要先看 {@link #isRootAvailable()}。
     * 这里不抛异常是刻意的：界面在 onResume 里无条件调它，
     * 一个「没 root」不该把整页炸掉。
     */
    public static Set<String> getEnabled() {
        Set<String> out = new LinkedHashSet<String>();
        if (!isRootAvailable()) {
            return out;
        }
        ShellOut r = runSu("settings get secure " + KEY_SERVICES, SU_TIMEOUT_MS);
        if (r == null || r.exit != 0) {
            AppLog.w(TAG, "读取已启用无障碍服务失败: " + (r == null ? "无输出" : r.text));
            return out;
        }
        parseFlattened(r.text, out);
        return out;
    }

    /**
     * 把 settings 里那串 {@code a/.B:c/d.E} 解析成展开后的全名集合。
     *
     * <p>单独抽出来是因为预览/解析要能单测，也方便处理各种脏值。
     */
    static void parseFlattened(String raw, Set<String> out) {
        if (raw == null) {
            return;
        }
        String s = raw.trim();
        // settings 里没有任何无障碍服务时，读出来是字面量 "null"
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return;
        }
        for (String part : s.split(":")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int slash = p.indexOf('/');
            if (slash <= 0 || slash >= p.length() - 1) {
                // 格式不对的条目直接跳过：宁可漏判，也不能让一个脏条目
                // 把整串解析搞崩。
                continue;
            }
            String pkg = p.substring(0, slash).trim();
            String cls = p.substring(slash + 1).trim();
            out.add(expand(pkg, cls));
        }
    }

    /**
     * 把 {@code .Foo} 这种简写展开成 {@code com.foo.Foo}。
     *
     * <p>系统写进去时用哪种形式取决于谁写的：本类写的是全名，
     * 但系统设置界面和部分 ROM 会写简写。不展开就比不出「已开启」。
     */
    static String expand(String pkg, String cls) {
        if (cls.startsWith(".")) {
            return pkg + "/" + pkg + cls;
        }
        return pkg + "/" + cls;
    }

    // =====================================================================
    //  写：开 / 关
    // =====================================================================

    /**
     * 把某个无障碍服务打开。
     *
     * <p>做法是「读当前 → 加入 → 整串写回」，而不是追加：
     * settings put 本身就是覆盖语义，必须先合并。
     */
    public static Result enable(ServiceInfo svc) {
        if (svc == null) {
            return Result.fail("服务信息为空");
        }
        Set<String> cur = getEnabled();
        cur.add(svc.flatten());
        return writeEnabled(cur);
    }

    /** 关掉某个无障碍服务。 */
    public static Result disable(ServiceInfo svc) {
        if (svc == null) {
            return Result.fail("服务信息为空");
        }
        Set<String> cur = getEnabled();
        cur.remove(svc.flatten());
        // 系统里可能存的是简写形式，两种都删一遍才干净。
        cur.remove(svc.pkg + "/." + shortName(svc.cls, svc.pkg));
        return writeEnabled(cur);
    }

    /** 一次性把一批服务设成指定的开关状态（批量操作时避免反复读写）。 */
    public static Result setAll(List<ServiceInfo> services, boolean enable) {
        Set<String> cur = getEnabled();
        for (ServiceInfo s : services) {
            if (s == null) {
                continue;
            }
            if (enable) {
                cur.add(s.flatten());
            } else {
                cur.remove(s.flatten());
                cur.remove(s.pkg + "/." + shortName(s.cls, s.pkg));
            }
        }
        return writeEnabled(cur);
    }

    /**
     * 把整个清单写回系统。
     *
     * <p>清单为空时：把值写成 {@code null}（系统约定的「空」），
     * 而不是写空字符串 —— 后者在部分 ROM 上会被当成一个非法条目。
     * 同时把 accessibility_enabled 置 0。
     */
    private static Result writeEnabled(Set<String> set) {
        if (!isRootAvailable()) {
            return Result.fail("没有 root 权限");
        }
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(s);
        }
        String value = sb.length() == 0 ? "null" : sb.toString();
        boolean empty = sb.length() == 0;

        // 两件事要一起做，所以合成一条命令：
        // ① 覆盖清单；② 有服务就确保总开关是 1，没服务就置 0。
        String cmd = "settings put secure " + KEY_SERVICES + " " + quote(value)
                + "; settings put secure " + KEY_A11Y_ON + (empty ? " 0" : " 1");

        ShellOut r = runSu(cmd, SU_TIMEOUT_MS);
        if (r == null) {
            return Result.fail("执行 su 命令失败");
        }
        if (r.exit != 0) {
            String msg = r.text == null ? "" : r.text.trim();
            if (msg.length() > 200) {
                msg = msg.substring(0, 200);
            }
            AppLog.e(TAG, "写入无障碍服务清单失败: exit=" + r.exit + " " + msg, null);
            return Result.fail("写入失败" + (msg.isEmpty() ? "" : "：" + msg));
        }
        AppLog.i(TAG, "无障碍清单已更新: " + (empty ? "(空)" : value));
        return Result.ok();
    }

    /** 关掉全部无障碍服务。 */
    public static Result disableAll() {
        return writeEnabled(new LinkedHashSet<String>());
    }

    // =====================================================================
    //  枚举：设备上装了哪些无障碍服务
    // =====================================================================

    /**
     * 枚举设备上所有应用暴露的无障碍服务。
     *
     * <p>用 {@link PackageManager#queryIntentServices} 查
     * {@code android.accessibilityservice.AccessibilityService} 这个 action。
     * 本项目已经申请了 {@code QUERY_ALL_PACKAGES}，所以能看全所有应用，
     * 不会因为 Android 11 的包可见性限制只看到一部分。
     *
     * <p>列表按「应用名」排序，并且默认跳过系统框架自身的服务
     * （{@code com.android.*} 里那几个对话框/开关服务）—— 它们关掉会让
     * 手机基本没法用，不该出现在随手可点的列表里。
     */
    public static List<ServiceInfo> listServices(Context ctx, boolean includeSystem) {
        List<ServiceInfo> out = new ArrayList<ServiceInfo>();
        if (ctx == null) {
            return out;
        }
        PackageManager pm = ctx.getPackageManager();
        try {
            android.content.Intent probe = new android.content.Intent(
                    "android.accessibilityservice.AccessibilityService");
            List<ResolveInfo> list = pm.queryIntentServices(probe, 0);
            if (list == null) {
                return out;
            }
            Set<String> seen = new HashSet<String>();
            for (ResolveInfo ri : list) {
                if (ri == null || ri.serviceInfo == null) {
                    continue;
                }
                String pkg = ri.serviceInfo.packageName;
                String cls = ri.serviceInfo.name;
                if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(cls)) {
                    continue;
                }
                // 本应用自己的服务也列出来：用户可能想用 root 一键开关它，
                // 免得每次手动去系统设置里点。
                boolean isSystem = isSystemPkg(pkg) && !pkg.equals(ctx.getPackageName());
                if (isSystem && !includeSystem) {
                    continue;
                }
                String flat = pkg + "/" + cls;
                if (!seen.add(flat)) {
                    continue;
                }
                CharSequence svcLabel = ri.serviceInfo.loadLabel(pm);
                CharSequence appLabel = null;
                try {
                    appLabel = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                } catch (Throwable ignored) {
                    // 应用被卸载/被隐藏时拿不到 label，退回用包名。
                }
                String name = svcLabel == null ? cls : svcLabel.toString();
                String app = appLabel == null ? pkg : appLabel.toString();
                // 「应用名 · 服务名」能一眼看出这是谁的服务；
                // 两者相同时（很常见）只显示一次，避免「Grace · Grace」这种。
                String label = name.equals(app) || name.startsWith(app) ? name : app + " · " + name;
                out.add(new ServiceInfo(pkg, cls, label));
            }
        } catch (Throwable t) {
            AppLog.e(TAG, "枚举无障碍服务失败", t);
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** 粗略判断是不是系统包。 */
    private static boolean isSystemPkg(String pkg) {
        if (pkg == null) {
            return false;
        }
        // 只挡最核心的那几个前缀：com.android.* 里也有 TalkBack 之类的
        // 正常服务，全挡掉反而少了有用的项。
        return pkg.equals("android")
                || pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.android.settings")
                || pkg.equals("com.google.android.marvin.talkback");
    }

    // =====================================================================
    //  按应用聚合（界面按「一个应用一行」展示）
    // =====================================================================

    /** 一个应用及其名下所有无障碍服务。 */
    public static final class AppGroup {
        public final String pkg;
        public final String appLabel;
        public final List<ServiceInfo> services = new ArrayList<ServiceInfo>();

        AppGroup(String pkg, String appLabel) {
            this.pkg = pkg;
            this.appLabel = appLabel;
        }

        /** 名下服务是否全部已启用。 */
        public boolean allEnabled(Set<String> enabled) {
            if (services.isEmpty()) {
                return false;
            }
            for (ServiceInfo s : services) {
                if (!enabled.contains(s.flatten())) {
                    return false;
                }
            }
            return true;
        }

        /** 名下是否至少有一个已启用。 */
        public boolean anyEnabled(Set<String> enabled) {
            for (ServiceInfo s : services) {
                if (enabled.contains(s.flatten())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 把服务列表按应用归并。
     *
     * <p>界面上一行代表一个应用，比「一个服务一行」更好用：
     * 多数应用只暴露一个无障碍服务，但它会出现在几十个应用里，
     * 按应用聚合能让「我想开微信的」这种需求一眼定位。
     */
    public static List<AppGroup> groupByApp(Context ctx, List<ServiceInfo> services) {
        List<AppGroup> out = new ArrayList<AppGroup>();
        if (services == null || services.isEmpty()) {
            return out;
        }
        java.util.LinkedHashMap<String, AppGroup> map =
                new java.util.LinkedHashMap<String, AppGroup>();
        PackageManager pm = ctx == null ? null : ctx.getPackageManager();
        for (ServiceInfo s : services) {
            AppGroup g = map.get(s.pkg);
            if (g == null) {
                String app = s.pkg;
                if (pm != null) {
                    try {
                        app = pm.getApplicationLabel(
                                pm.getApplicationInfo(s.pkg, 0)).toString();
                    } catch (Throwable ignored) {
                        // 拿不到就用包名，不影响功能。
                    }
                }
                g = new AppGroup(s.pkg, app);
                map.put(s.pkg, g);
                out.add(g);
            }
            g.services.add(s);
        }
        // 按应用名重排（map 的插入顺序取决于服务列表顺序，看着会乱）
        Collections.sort(out, (a, b) -> a.appLabel.compareToIgnoreCase(b.appLabel));
        return out;
    }

    // =====================================================================
    //  shell 执行
    // =====================================================================

    private static final class ShellOut {
        final int exit;
        final String text;

        ShellOut(int exit, String text) {
            this.exit = exit;
            this.text = text;
        }
    }

    /**
     * 执行一条 root 命令。
     *
     * <p>必须用 {@code sh -c} 包一层并把整条 su 命令当成一个字符串传进去 —
     * 否则 ProcessBuilder 会把 {@code "su -c settings put ..."} 按空格拆成
     * 多个参数，su 收到一堆零碎参数后报错。这是个很容易踩的坑
     * （{@link RootScreenCaptureManager} 里也踩过同样的）。
     */
    private static ShellOut runSu(String cmd, final long timeoutMs) {
        Process p = null;
        try {
            // 命令里可能带引号（写入清单时 value 需要引起来），
            // 外层用双引号包住 su -c 的内容，所以内部的双引号要转义一层。
            String full = "su -c \"" + cmd.replace("\"", "\\\"") + "\"";
            p = new ProcessBuilder("sh", "-c", full)
                    .redirectErrorStream(true)
                    .start();

            // 读输出必须放到独立线程：
            // 如果先在当前线程把流读到 EOF 再 waitFor，一旦进程输出超过
            // 管道缓冲区（64KB）就会互相等死 —— 我们要 EOF 才继续，
            // 而进程要我们读走才能继续写。虽然 settings / id 的输出很小，
            // 但命令是拼接出来的，不该把正确性押在「输出一定很短」上。
            final StringBuilder sb = new StringBuilder();
            final Process proc = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        // 加个上限：万一某条命令疯狂输出，不该把内存吃满。
                        if (sb.length() < 8192) {
                            sb.append(line).append('\n');
                        }
                    }
                } catch (Throwable ignored) {
                    // 进程被 destroy 时读线程会抛，属正常收尾。
                }
            }, "a11y-su-read");
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                AppLog.w(TAG, "su 命令超时: " + cmd);
                return new ShellOut(-1, "命令超时");
            }
            // 给读线程一点时间把剩余输出刷完（通常已经完成）。
            reader.join(500);
            return new ShellOut(p.exitValue(), sb.toString());
        } catch (Throwable t) {
            AppLog.e(TAG, "执行 su 命令异常: " + cmd, t);
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                    // 已退出时 destroy 会抛，忽略即可。
                }
            }
        }
    }

    /** 把 {@code FooService} + {@code com.foo} 缩成 {@code FooService}（已是短名则原样）。 */
    private static String shortName(String cls, String pkg) {
        if (cls == null) {
            return "";
        }
        String prefix = pkg + ".";
        return cls.startsWith(prefix) ? cls.substring(prefix.length()) : cls;
    }

    /**
     * 给 settings 命令的值加引号。
     *
     * <p>清单值里全是包名和类名，字符集安全，但仍做一次转义：
     * 万一某个包名带奇怪字符，不至于把整条命令拆坏。
     */
    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}