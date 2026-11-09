package com.xiaosun.ocr;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设备性能采样器 —— 「设备概览」页的唯一数据来源。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么全部走 root，不再自己读文件】
 * ════════════════════════════════════════════════════════════════
 * 早先的版本是「先用普通文件读取去读 /proc 与 /sys」。实机验证下来
 * 这条路不可靠，而且不可靠的方式还很隐蔽：
 *
 *   · /proc/stat 在部分 ROM（含这台小米）对普通应用返回空，
 *     于是 CPU 负载永远是 0%、每核频率与 SoC 名字跟着一起不显示 ——
 *     用户看到的是「CPU 0%、未知 SoC、没有核心列表」的空壳，
 *     却完全不知道这是权限问题。
 *   · /sys/class/kgsl/* 更糟：文件存在、也有名字，但内容读出来是空的，
 *     表现成「GPU 频率 —、负载 —」，看着像功能没做。
 *
 * 与其到处写「读不到就显示 —」，不如把通道收敛成一条：全部走 su。
 * root 能读的东西是确定的，读不到的才是真的不存在，而不是被权限挡住。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么是常驻 shell，而不是每帧起一个 su】
 * ════════════════════════════════════════════════════════════════
 * 一秒一帧，每帧都 fork 一个新的 su 进程（Magisk 还要过一遍授权检查）
 * 在低端机上可以量出延迟，root 管理器日志也会被刷屏。
 *
 * 所以这里保持**一个**常驻 su shell（{@link RootShell}）：
 * 启动时授权一次，之后每帧只是往它的 stdin 写一段脚本，
 * 读到约定的结束标记为止。一帧脚本把本轮要的全部数据一次取回
 * （见 {@link #SCRIPT}），少一次往返就少一次延迟。
 *
 * ════════════════════════════════════════════════════════════════
 * 【哪些数据不走 root】
 * ════════════════════════════════════════════════════════════════
 * 内存总量、电池、系统版本这些有正规 API 的，仍然用 API ——
 * 不是因为它们「更全」，而是因为它们**更准**：
 *   · ActivityManager.MemoryInfo 已扣掉内核与硬件占用，
 *     而 /proc/meminfo 的 MemTotal 是物理颗粒大小；
 *   · 电池状态只能从 sticky 广播拿，没有文件替代品。
 */
final class DeviceStats {

    private static final String TAG = "GsDeviceStats";

    /**
     * 一帧采样脚本。
     *
     * <p>每段以 {@code ##名字} 开头，Java 侧按段解析。用「行首标记」而不是
     * 固定行号：各机型的核数、thermal zone 数量都不同，按位置解析必然错位。
     *
     * <p>所有读取都带 {@code 2>/dev/null}：不存在的节点会往 stderr 吐错，
     * 而 su 的 stdout/stderr 是合并的（redirectErrorStream），
     * 不屏蔽就会有一堆 "No such file" 混进数据里。
     */
    private static final String SCRIPT = String.join("\n",
            "echo '##STAT'",
            "grep '^cpu' /proc/stat 2>/dev/null",
            "echo '##MEM'",
            "grep -E '^(MemTotal|SwapTotal|SwapFree|SwapCached):' /proc/meminfo 2>/dev/null",
            "echo '##SOC'",
            "getprop ro.soc.manufacturer 2>/dev/null",
            "getprop ro.soc.model 2>/dev/null",
            "getprop ro.hardware 2>/dev/null",
            "getprop ro.board.platform 2>/dev/null",
            "grep -i -m2 -E '^(Hardware|model name)[ \t]*:' /proc/cpuinfo 2>/dev/null",
            "echo '##CORES'",
            // 用 shell 内建 read 而不是 cat：cat 每次都要 fork+exec 一个新进程
            // （约 8ms），8 核 × 3 个文件 = 24 次 ≈ 193ms；read 是内建命令，
            // 不创建进程，同样 24 次读取实测降到 40ms 上下。
            // 输出格式保持 `C 核号 cur min max` 不变，Java 侧不用改。
            "for d in /sys/devices/system/cpu/cpu[0-9]*; do "
                    + "[ -d \"$d\" ] || continue; n=${d##*/cpu}; "
                    + "c=x; mn=x; mx=x; "
                    + "read c < $d/cpufreq/scaling_cur_freq 2>/dev/null; "
                    + "read mn < $d/cpufreq/cpuinfo_min_freq 2>/dev/null; "
                    + "read mx < $d/cpufreq/cpuinfo_max_freq 2>/dev/null; "
                    + "echo \"C $n ${c:-x} ${mn:-x} ${mx:-x}\"; done",
            // policy 那一组（新内核按簇给，一个 policy 覆盖多个核）同样改成 read。
            "for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do "
                    + "[ -d \"$d\" ] || continue; "
                    + "a=x; c=x; mn=x; mx=x; "
                    + "read a < $d/affected_cpus 2>/dev/null; "
                    + "read c < $d/scaling_cur_freq 2>/dev/null; "
                    + "read mn < $d/cpuinfo_min_freq 2>/dev/null; "
                    + "read mx < $d/cpuinfo_max_freq 2>/dev/null; "
                    + "echo \"P ${a:-x} ${c:-x} ${mn:-x} ${mx:-x}\"; done",
            "echo '##GPU'",
            // 同 CORES：改用 shell 内建 read，省掉 11 次 fork+exec（94ms → 8ms）。
            // [ -n "$v" ] 仍然保留：不存在的节点读出来是空，和原来 cat 的语义一致。
            "for f in gpu_model model gpu_busy_percentage gpubusy gpuclk gpu_clock clock "
                    + "max_gpuclk devfreq/cur_freq devfreq/min_freq devfreq/max_freq; do "
                    + "v=; read v < /sys/class/kgsl/kgsl-3d0/$f 2>/dev/null; "
                    + "[ -n \"$v\" ] && echo \"G $f $v\"; done",
            "echo '##DEVFREQ'",
            "for d in /sys/class/devfreq/*; do "
                    + "[ -d \"$d\" ] || continue; "
                    + "nm=; c=; mx=; "
                    + "read nm < $d/name 2>/dev/null; "
                    + "read c < $d/cur_freq 2>/dev/null; "
                    + "read mx < $d/max_freq 2>/dev/null; "
                    + "echo \"D ${nm:-?} ${c:-x} ${mx:-x}\"; done",
            "echo '##THERM'",
            // 【为什么整段换成「一次 grep + 只对入选项读 temp」】
            // 这台机上有 83 个 thermal_zone，原来每个 zone 要 cat 两次
            // （type + temp）＝ 166 次进程级文件打开，实测 1240ms，
            // 是首帧白屏的最大一块。
            //
            // 现在：type 一次 grep 全读进来（13ms）→ 先筛掉明显无关的
            // （电池 / 皮肤 / usb 口…）→ 只剩 30 个左右 → 对这 30 个各读一次 temp。
            // 这里也用内建 read，不再 fork cat —— 原来「30 次 cat」实测 477ms 里
            // 有一大半是启动进程的开销，改 read 后这段降到 60ms 上下。
            //
            // 不把 temp 也一次性 grep 全读：那样能到 146ms，但要额外一个临时文件
            // 或第二遍配对逻辑，为几十毫秒引入磁盘写不值得。
            //
            // -i 是有意的：各机型命名习惯不同（ap_ / AP / TSENS / cpu-0-0-0）。
            "for z in $(grep -H . /sys/class/thermal/thermal_zone*/type 2>/dev/null"
                    + " | grep -iE 'cpu|ap_|tsens|cluster|core|gpu|soc|mtktscpu'"
                    + " | cut -d: -f1); do "
                    + "n=${z%/*}; n=${n##*zone}; "
                    + "t=; v=; "
                    + "read t < $z 2>/dev/null; "
                    + "read v < /sys/class/thermal/thermal_zone$n/temp 2>/dev/null; "
                    + "echo \"T ${t:-?} ${v:-x}\"; done",
            "echo '##END'");

    // =====================================================================
    //  快照
    // =====================================================================

    /** 一个进程的 CPU 占用。 */
    static final class ProcInfo {
        /** 应用名（如「番茄小说」）；查不到应用名时是进程名。 */
        final String label;
        /** 百分比文本，如 "13%"。 */
        final String pct;
        /** true = 系统进程 / 查不到应用名，界面用灰字区分。 */
        final boolean system;

        ProcInfo(String label, String pct, boolean system) {
            this.label = label;
            this.pct = pct;
            this.system = system;
        }
    }

    /** 一次采样的全部结果。界面只读它，不再自己去读 /proc。 */
    static final class Snapshot {
        /** false = root 不可用，本轮除内存 / 电池之外都没数据。 */
        boolean rootOk;

        // ---- 内存（字节）----
        long memTotal;
        long memAvail;
        long swapTotal;
        long swapFree;
        long swapCached;

        // ---- CPU ----
        int cpuLoad;                 // 整机负载（%）
        boolean hasCpuLoad;          // false = 还算不出来（首帧 / 读不到），显示「—」
        int cores;                   // 核数
        String soc = "";             // SoC 名字
        String layout = "";          // 大小核分组，如 "6+2"
        long[] coreCurMHz = new long[0];
        long[] coreMinMHz = new long[0];
        long[] coreMaxMHz = new long[0];
        int[] coreLoad = new int[0];

        // ---- GPU ----
        String gpuName = "";
        long gpuCurMHz = -1;
        long gpuMaxMHz = -1;
        int gpuBusy = -1;            // -1 = 这台机型不给读

        // ---- 温度（摄氏度）----
        float cpuTemp = Float.NaN;

        // ---- 电池 ----
        int batteryLevel = -1;
        boolean charging;
        boolean plugged;
        float batteryTemp = Float.NaN;
        float batteryVolt = Float.NaN;
        /** 功率**绝对值**（瓦）。方向不看符号，看 {@link #charging}，理由见 readBattery。 */
        float batteryPowerW = Float.NaN;

        // ---- 系统 ----
        long uptimeMs;
        String androidVer = "";
        String device = "";

        // ---- 进程 ----
        String procTotal;
        String procHint;
        final List<ProcInfo> procs = new ArrayList<ProcInfo>();
    }

    // =====================================================================
    //  状态
    // =====================================================================

    private final Context app;
    private final ActivityManager am;
    private final RootShell shell = new RootShell();

    /** 上一次 /proc/stat 的累计节拍：[0] 整机，[i+1] 第 i 核。 */
    private long[] prevTotal;
    private long[] prevIdle;
    /** SoC 名字读一次就够。 */
    private String cachedSoc;

    // 注：原先进程列表有一套 3 秒缓存（PROC_TTL_MS + cachedProcs/cachedProcTotal/
    // cachedProcHint/cachedProcAt）。2.7 起删掉了 —— 它让进程区每 3 秒才动一次，
    // 而旁边每个数字都是 1 秒一变，看起来就像「进程卡住不刷新」。
    // 现在是每帧都跑一次 top（约 30ms），和其它数据同频。


    DeviceStats(Context context) {
        this.app = context.getApplicationContext();
        this.am = (ActivityManager) this.app.getSystemService(Context.ACTIVITY_SERVICE);
    }

    /** 退出页面时把常驻 shell 关掉，别让一个 root 进程一直挂着。 */
    void close() {
        shell.closeQuietly();
    }

    // =====================================================================
    //  采样入口
    // =====================================================================

    /**
     * 采一次样。
     *
     * <p>会跑 root 命令，**必须在子线程调用**。
     *
     * <p>【为什么首帧不带进程列表】{@code top -b -n 2} 至少要等一个采样间隔
     * （即使压到 0.15s，加上遍历 980 个进程本身也要 0.4s 上下），而它只是页面
     * 左下角一小块。首帧为了它多等半秒，会让整页（内存、GPU、CPU 温度）一起晚出现 ——
     * 用户看到的就是「点进去空白一两秒」。所以首帧先不跑 top，把便宜的部分
     * 立刻画出来，进程列表从第二帧（1 秒后）起正常带。
     *
     * @param withProcs false = 本帧不采集进程列表（首帧用）
     */
    Snapshot sample(boolean withProcs) {
        Snapshot s = new Snapshot();
        readSystem(s);
        readBattery(s);
        readMemoryApi(s);

        String dump = shell.exec(SCRIPT, 5000);
        if (dump == null) {
            // root 不可用（没授权 / su 不存在 / 超时）。
            // 有 API 的那部分照常显示，sysfs 的那部分如实标记为无数据。
            s.rootOk = false;
            readCpu(null, s);
            readCoreFreqs(null, s);
            readSoc(null, s);
            readGpu(null, null, s);
            readTemp(null, s);
            if (withProcs) {
                readProcs(s, false);
            }
            return s;
        }

        s.rootOk = true;
        Map<String, List<String>> sec = splitSections(dump);

        readMemoryRoot(sec.get("MEM"), s);
        readCpu(sec.get("STAT"), s);
        readCoreFreqs(sec.get("CORES"), s);
        readGpu(sec.get("GPU"), sec.get("DEVFREQ"), s);
        readTemp(sec.get("THERM"), s);
        readSoc(sec.get("SOC"), s);
        if (withProcs) {
            readProcs(s, true);
        } else {
            // 不给列表，但要给一句「正在读」而不是让标题留空 —— 否则看起来像坏了。
            s.procHint = "读取中…";
        }
        return s;
    }

    /** 兼容旧调用：默认都带进程列表。 */
    Snapshot sample() {
        return sample(true);
    }

    /**
     * 把 {@code ##X} 分段的输出切成 Map。
     *
     * <p>段名重复出现时追加到同一段 —— 脚本里每段只出现一次，
     * 真出现重复说明有异常输出混进来了，追加比覆盖安全。
     */
    private static Map<String, List<String>> splitSections(String dump) {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        List<String> cur = null;
        for (String raw : dump.split("\n")) {
            String line = raw.replace("\r", "");
            if (line.startsWith("##")) {
                String name = line.substring(2).trim();
                cur = out.get(name);
                if (cur == null) {
                    cur = new ArrayList<String>();
                    out.put(name, cur);
                }
                continue;
            }
            if (cur != null && line.trim().length() > 0) {
                cur.add(line);
            }
        }
        return out;
    }

    // =====================================================================
    //  内存
    // =====================================================================

    /** 总量 / 可用走 ActivityManager：它已扣掉内核与硬件占用，比 MemTotal 准。 */
    private void readMemoryApi(Snapshot s) {
        if (am == null) {
            return;
        }
        try {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s.memTotal = mi.totalMem;
            s.memAvail = mi.availMem;
        } catch (Throwable t) {
            AppLog.w(TAG, "读取内存信息失败: " + t);
        }
    }

    /** 交换分区没有 API，只能读 /proc/meminfo —— 这段走 root。 */
    private void readMemoryRoot(List<String> lines, Snapshot s) {
        if (lines == null) {
            return;
        }
        for (String line : lines) {
            if (line.startsWith("SwapTotal:")) {
                s.swapTotal = kbOf(line);
            } else if (line.startsWith("SwapFree:")) {
                s.swapFree = kbOf(line);
            } else if (line.startsWith("SwapCached:")) {
                s.swapCached = kbOf(line);
            } else if (line.startsWith("MemTotal:") && s.memTotal <= 0) {
                s.memTotal = kbOf(line);
            }
        }
    }

    /** "SwapTotal:  12345 kB" → 字节数。 */
    private static long kbOf(String line) {
        int i = line.indexOf(':');
        if (i < 0) {
            return 0L;
        }
        String v = line.substring(i + 1).trim();
        int sp = v.indexOf(' ');
        if (sp > 0) {
            v = v.substring(0, sp);
        }
        return parseLong(v, 0L) * 1024L;
    }

    // =====================================================================
    //  CPU
    // =====================================================================

    /** 解析 {@code /proc/stat} 的 cpu 行，算整机与每核负载。{@code lines} 为 null 表示读不到。 */
    private void readCpu(List<String> lines, Snapshot s) {
        List<long[]> ticks = new ArrayList<long[]>();
        if (lines != null) {
            for (String line : lines) {
                if (!line.startsWith("cpu")) {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                long total = 0L;
                long idle = 0L;
                // p[0] 是 "cpu" / "cpu0" 标签，从 1 开始才是数值
                for (int i = 1; i < p.length; i++) {
                    long v = parseLong(p[i], 0L);
                    total += v;
                    // 第 4、5 列是 idle 与 iowait，两者都不算「忙」
                    if (i == 4 || i == 5) {
                        idle += v;
                    }
                }
                ticks.add(new long[]{total, idle});
            }
        }

        if (ticks.isEmpty()) {
            // 读不到 /proc/stat：负载标记为不可用。cores 留给 CORES 段去数。
            s.hasCpuLoad = false;
            s.coreLoad = new int[0];
            prevTotal = null;
            prevIdle = null;
            return;
        }

        int slots = ticks.size();   // 0 = 整机，1..n = 各核
        int cores = Math.max(slots - 1, 1);
        if (s.cores <= 0) {
            s.cores = cores;
        }

        long[] total = new long[slots];
        long[] idle = new long[slots];
        for (int i = 0; i < slots; i++) {
            total[i] = ticks.get(i)[0];
            idle[i] = ticks.get(i)[1];
        }

        boolean hasPrev = prevTotal != null && prevTotal.length == slots;
        int[] load = new int[slots];
        if (hasPrev) {
            for (int i = 0; i < slots; i++) {
                long dt = total[i] - prevTotal[i];
                long di = idle[i] - prevIdle[i];
                if (dt <= 0) {
                    continue;   // 计数器回绕 / 无变化：留 0，别算出除零
                }
                long busy = dt - di;
                load[i] = (int) Math.max(0, Math.min(100, busy * 100L / dt));
            }
        }
        prevTotal = total;
        prevIdle = idle;

        // 首帧没有上一次的值可比，算出来必然是 0 —— 那是「还不知道」，
        // 不是「真的空闲」。用 hasCpuLoad 把两者区分开，
        // 界面才不会刚进来就显示一个会成为误解的 0%。
        s.hasCpuLoad = hasPrev;

        s.cpuLoad = load[0];
        s.coreLoad = new int[cores];
        int sum = 0;
        int counted = 0;
        for (int i = 0; i < cores && i + 1 < slots; i++) {
            s.coreLoad[i] = load[i + 1];
            sum += s.coreLoad[i];
            counted++;
        }
        // 整机那一行在关核 / 开核时会短暂滞后，用各核平均兜一下。
        if (s.cpuLoad <= 0 && counted > 0 && hasPrev) {
            s.cpuLoad = sum / counted;
        }
    }

    /**
     * 解析每核频率。
     *
     * <p>脚本输出了两种结构：
     * <ul>
     *   <li>{@code C 核号 cur min max} —— {@code cpuN/cpufreq/}，一核一份，首选；</li>
     *   <li>{@code P 受影响核 cur min max} —— {@code cpufreq/policyN/}，
     *       新内核里若干核共用一组频率。</li>
     * </ul>
     * 先用 C 填，剩下还空着的核再用 P 按 {@code affected_cpus} 补 ——
     * 有些机型只导出 policy，有些只导出 cpuN，两种都要能出数。
     */
    private void readCoreFreqs(List<String> lines, Snapshot s) {
        // 先按实际存在的 cpuN 目录定核数：它是所有取值方式里最可靠的，
        // 且 /proc/stat 读不到时也要能知道有几核。
        int cores = Math.max(s.cores, 1);

        long[] cur = new long[cores];
        long[] min = new long[cores];
        long[] max = new long[cores];
        Arrays.fill(cur, -1L);
        Arrays.fill(min, -1L);
        Arrays.fill(max, -1L);
        boolean[] filled = new boolean[cores];

        List<String> policyLines = new ArrayList<String>();
        if (lines != null) {
            for (String line : lines) {
                String[] p = line.trim().split("\\s+");
                if (p.length == 0) {
                    continue;
                }
                if ("P".equals(p[0])) {
                    if (p.length >= 5) {
                        policyLines.add(line);
                    }
                    continue;
                }
                if (!"C".equals(p[0]) || p.length < 5) {
                    continue;
                }
                int n = (int) parseLong(p[1], -1L);
                if (n < 0 || n >= cores) {
                    continue;
                }
                cur[n] = toMHz(p[2]);
                min[n] = toMHz(p[3]);
                max[n] = toMHz(p[4]);
                filled[n] = true;
            }
        }

        for (String line : policyLines) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 5 || "x".equals(p[1])) {
                continue;
            }
            long c = toMHz(p[2]);
            long mn = toMHz(p[3]);
            long mx = toMHz(p[4]);
            if (c <= 0 && mn <= 0 && mx <= 0) {
                continue;
            }
            // affected_cpus 形如 "0 1 2 3" 或 "4-7"
            for (int n : parseCpuList(p[1], cores)) {
                if (filled[n]) {
                    continue;
                }
                cur[n] = c;
                min[n] = mn;
                max[n] = mx;
                filled[n] = true;
            }
        }

        s.coreCurMHz = cur;
        s.coreMinMHz = min;
        s.coreMaxMHz = max;
        s.layout = layoutOf(max);
    }

    /** 把 "0 1 2 3" / "4-7" 这类 affected_cpus 展开成核号列表。 */
    /** 委托给 {@link DeviceParsers#parseCpuList(String, int)}，本类不再重复实现。 */
    private static List<Integer> parseCpuList(String raw, int cores) {
        return DeviceParsers.parseCpuList(raw, cores);
    }

    /**
     * 按「最大频率分组」得出大小核布局，例如 6 小核 + 2 大核 → "6+2"。
     *
     * <p>只要有一个核心读不到最大频率，或全部同频，就不给分组 ——
     * 硬分下去会得到 "1+1+1+…" 这种噪声，比不显示更糟。
     */
    private static String layoutOf(long[] maxMHz) {
        if (maxMHz == null || maxMHz.length == 0) {
            return "";
        }
        for (long m : maxMHz) {
            if (m <= 0) {
                return "";
            }
        }
        boolean mixed = false;
        for (long m : maxMHz) {
            if (m != maxMHz[0]) {
                mixed = true;
                break;
            }
        }
        if (!mixed) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int run = 1;
        long last = maxMHz[0];
        for (int i = 1; i < maxMHz.length; i++) {
            if (maxMHz[i] == last) {
                run++;
            } else {
                if (sb.length() > 0) {
                    sb.append('+');
                }
                sb.append(run);
                run = 1;
                last = maxMHz[i];
            }
        }
        if (sb.length() > 0) {
            sb.append('+');
        }
        sb.append(run);
        return sb.toString();
    }

    /**
     * SoC 名字。
     *
     * <p>取值顺序：{@code ro.soc.manufacturer/model}（Android 12+ 的标准属性，
     * 厂商填得最规范）→ {@code /proc/cpuinfo} 的 Hardware / model name →
     * {@code ro.hardware} / {@code ro.board.platform}。
     *
     * <p>都拿不到时才退回 {@link Build}：部分 ROM 的 SOC_MODEL 是空的，
     * 而 getprop 反而有值，所以不能只信 Build。
     *
     * <p>最后还有一层兜底：用「核数 + 最高主频」拼一个能认出来的描述，
     * 比固定写「未知 SoC」信息量大得多。
     */
    private void readSoc(List<String> lines, Snapshot s) {
        if (cachedSoc == null) {
            cachedSoc = resolveSoc(lines);
        }
        String soc = cachedSoc;
        if (soc.length() == 0) {
            long peak = 0;
            if (s.coreMaxMHz != null) {
                for (long m : s.coreMaxMHz) {
                    if (m > peak) {
                        peak = m;
                    }
                }
            }
            if (peak > 0) {
                soc = (s.cores > 0 ? s.cores + " 核" : "SoC")
                        + " · 最高 " + String.format(Locale.US, "%.2f", peak / 1000f) + "GHz";
            } else {
                soc = "未知 SoC";
            }
        }
        s.soc = soc;
    }

    private static String resolveSoc(List<String> lines) {
        String mfr = "";
        String model = "";
        List<String> props = new ArrayList<String>();
        String hardware = "";
        String board = "";
        String cpuInfo = "";

        if (lines != null) {
            // SOC 段顺序固定：manufacturer / model / hardware / board / cpuinfo 两行
            if (lines.size() >= 1) {
                mfr = trim(lines.get(0));
            }
            if (lines.size() >= 2) {
                model = trim(lines.get(1));
            }
            if (lines.size() >= 3) {
                hardware = trim(lines.get(2));
            }
            if (lines.size() >= 4) {
                board = trim(lines.get(3));
            }
            for (int i = 4; i < lines.size(); i++) {
                String line = lines.get(i);
                int c = line.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String key = line.substring(0, c).trim().toLowerCase(Locale.US);
                if (!"hardware".equals(key) && !"model name".equals(key)) {
                    continue;
                }
                String v = trim(line.substring(c + 1));
                // "Generic DT based system" / "Qualcomm Technologies, Inc SMxxxx"
                // 这类值没有信息量，跳过。
                String lower = v.toLowerCase(Locale.US);
                if (v.length() == 0 || lower.indexOf("generic") >= 0
                        || lower.indexOf("dt based") >= 0) {
                    continue;
                }
                cpuInfo = v;
                break;
            }
        }

        // 应用名不要出现「Qualcomm Technologies, Inc」这种全称，
        // 取第一段即可（"Qualcomm"）。
        if (model.length() > 0) {
            String m = mfr;
            int comma = m.indexOf(',');
            if (comma > 0) {
                m = m.substring(0, comma);
            }
            return m.length() > 0 ? (trim(m) + " " + model) : model;
        }
        if (hardware.length() > 0) {
            props.add(hardware);
        }
        if (board.length() > 0) {
            props.add(board);
        }
        if (cpuInfo.length() > 0) {
            props.add(cpuInfo);
        }

        if (!props.isEmpty()) {
            return props.get(0);
        }
        // 最后退回 Build（SOC_MODEL 在不少 ROM 上是空的）
        String b = (trim(Build.SOC_MANUFACTURER) + " " + trim(Build.SOC_MODEL)).trim();
        return b;
    }

    // =====================================================================
    //  GPU
    // =====================================================================

    /**
     * GPU 频率与负载。
     *
     * <p>高通的 kgsl 有三代不同写法，本方法都认：
     * <ul>
     *   <li>{@code gpu_busy_percentage} —— 老版 kgsl，直接是百分比；</li>
     *   <li>{@code gpubusy} —— 新版 kgsl，内容是「忙计数 闲计数」两个数，
     *       要自己算 {@code busy / (busy + idle)}；</li>
     *   <li>{@code gpuclk} / {@code devfreq/cur_freq} —— 当前频率。</li>
     * </ul>
     *
     * <p>kgsl 什么都没给时，去 devfreq 里找名字带 gpu / mali 的节点
     * （Mali 与部分骁龙走这条路）。两处都找不到就保持 -1，
     * 界面显示「—」而不猜。
     */
    private void readGpu(List<String> gpu, List<String> devfreq, Snapshot s) {
        Map<String, String> g = new LinkedHashMap<String, String>();
        if (gpu != null) {
            for (String line : gpu) {
                // 格式 "G 键 值"；值本身可能带空格（gpubusy 是两个数）
                int sp1 = line.indexOf(' ');
                if (sp1 < 0) {
                    continue;
                }
                int sp2 = line.indexOf(' ', sp1 + 1);
                if (sp2 < 0) {
                    continue;
                }
                g.put(line.substring(sp1 + 1, sp2).trim(), line.substring(sp2 + 1).trim());
            }
        }

        String name = firstNonEmpty(g.get("gpu_model"), g.get("model"));
        String busyRaw = firstNonEmpty(g.get("gpu_busy_percentage"), g.get("gpubusy"));
        String curRaw = firstNonEmpty(g.get("gpuclk"), g.get("gpu_clock"),
                g.get("clock"), g.get("devfreq/cur_freq"));
        String maxRaw = firstNonEmpty(g.get("devfreq/max_freq"), g.get("max_gpuclk"));

        // kgsl 没给出频率，就翻 devfreq
        if (curRaw == null && devfreq != null) {
            for (String line : devfreq) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 3) {
                    continue;
                }
                String nm = p[1].toLowerCase(Locale.US);
                if (nm.indexOf("gpu") < 0 && nm.indexOf("mali") < 0) {
                    continue;
                }
                if (name == null || name.length() == 0) {
                    name = p[1];
                }
                if (!"x".equals(p[2])) {
                    curRaw = p[2];
                }
                if (p.length >= 4 && !"x".equals(p[3])) {
                    maxRaw = p[3];
                }
                break;
            }
        }

        s.gpuName = name == null ? "" : name;
        s.gpuCurMHz = toMHz(curRaw);
        s.gpuMaxMHz = toMHz(maxRaw);
        s.gpuBusy = parseGpuBusy(busyRaw);
    }

    /**
     * 把各种 GPU 忙闲写法归一到 0~100。
     *
     * <p>实测遇到的三种值形态：
     * <ul>
     *   <li>{@code "0 %"} —— 老 kgsl 的百分比，**带一个百分号**；</li>
     *   <li>{@code "121629 1006864"} —— 新 kgsl 的「忙 闲」两个累计计数；</li>
     *   <li>{@code "0      0"} —— 同上，但还没开始统计。</li>
     * </ul>
     *
     * <p>★ 不能简单地「按空格切成几段再数」。
     * {@code "0 %"} 按空白切是两段，第二段是 {@code "%"} 而不是数字 ——
     * 若照「两段 = 忙闲计数」处理，会把百分比误判成不可解析而丢掉，
     * 实测结果就是 GPU 负载一直是「—」。
     *
     * <p>所以先把所有非数字字符去掉，再只看数字个数：
     * 一个数字 → 百分比；两个数字 → 忙闲计数，算比例。
     */
    /** 委托给 {@link DeviceParsers#parseGpuBusy(String)}，本类不再重复实现。 */
    private static int parseGpuBusy(String raw) {
        return DeviceParsers.parseGpuBusy(raw);
    }

    // =====================================================================
    //  温度
    // =====================================================================

    /**
     * CPU 温度。
     *
     * <p>thermal_zone 的编号在各机型上完全是乱的：0 号可能是电池、可能是屏幕。
     * 所以只能按 zone 的 {@code type} 名字筛，而且分优先级：
     * <ol>
     *   <li>名字里带 cpu / ap / tsens / cluster / core / big / little —— 真正的 CPU 传感器；</li>
     *   <li>带 soc / gpu 的 —— 次选，至少比电池温度接近；</li>
     *   <li>带 bat / chg / usb / skin 的一律排除，那不是 CPU 温度。</li>
     * </ol>
     *
     * <p>同一优先级里取最大值：多核机型每个大核各有一个传感器，
     * 用户想看的显然是「最热的那颗」。
     */
    private void readTemp(List<String> lines, Snapshot s) {
        if (lines == null) {
            return;
        }
        float bestCpu = Float.NaN;
        float bestSoc = Float.NaN;

        for (String line : lines) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 3) {
                continue;
            }
            String type = p[1].toLowerCase(Locale.US);
            float c = parseTempC(p[2]);
            if (Float.isNaN(c)) {
                continue;
            }
            if (type.indexOf("bat") >= 0 || type.indexOf("chg") >= 0
                    || type.indexOf("usb") >= 0 || type.indexOf("pa_") >= 0
                    || type.indexOf("skin") >= 0 || type.indexOf("shell") >= 0
                    || type.indexOf("quiet") >= 0) {
                continue;
            }
            boolean cpuLike = type.indexOf("cpu") >= 0 || type.indexOf("ap_") >= 0
                    || type.indexOf("tsens") >= 0 || type.indexOf("cluster") >= 0
                    || type.indexOf("core") >= 0 || type.indexOf("big") >= 0
                    || type.indexOf("little") >= 0 || type.indexOf("mtktscpu") >= 0;
            boolean socLike = type.indexOf("soc") >= 0 || type.indexOf("gpu") >= 0;
            if (cpuLike) {
                bestCpu = maxOf(bestCpu, c);
            } else if (socLike) {
                bestSoc = maxOf(bestSoc, c);
            }
        }

        s.cpuTemp = !Float.isNaN(bestCpu) ? bestCpu : bestSoc;
    }

    /** thermal 的原始值：多数是毫摄氏度，少数直接是摄氏度，还有 -40 这类哨兵值。 */
    /** 委托给 {@link DeviceParsers#parseTempC(String)}，本类不再重复实现。 */
    private static float parseTempC(String raw) {
        return DeviceParsers.parseTempC(raw);
    }

    private static float maxOf(float a, float b) {
        return Float.isNaN(a) ? b : Math.max(a, b);
    }

    // =====================================================================
    //  电池
    // =====================================================================

    /**
     * 电池状态。
     *
     * <p>★ 功率的方向不看电流符号。
     * 各家 ROM 对 {@code BATTERY_PROPERTY_CURRENT_NOW} 的符号约定是反的：
     * 主流约定「放电为负」，但也有一大批机型（这台小米就是）「放电为正」。
     * 直接取符号的结果是：明明没插充电器，界面却显示 {@code +2.02W}。
     *
     * <p>可靠的判据只有 sticky 广播里的 {@code STATUS} / {@code PLUGGED}：
     * 充电状态是驱动上报的枚举值，不是有歧义的符号。所以这里只保存
     * **功率绝对值**，方向由 {@link Snapshot#charging} 决定，交给界面表达。
     */
    private void readBattery(Snapshot s) {
        Intent it = null;
        try {
            // receiver 传 null = 只读那条 sticky 广播（登记即返回上一次的值），
            // 不注册真正的接收器，因此没有生命周期负担、也不额外耗电。
            it = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable t) {
            AppLog.w(TAG, "读取电池广播失败: " + t);
        }

        if (it != null) {
            int level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                s.batteryLevel = Math.round(level * 100f / scale);
            }
            int mv = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (mv > 0) {
                s.batteryVolt = mv / 1000f;
            }
            int tenth = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tenth != Integer.MIN_VALUE) {
                s.batteryTemp = tenth / 10f;
            }
            int status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            s.charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            s.plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }

        try {
            BatteryManager bm = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
            if (bm == null) {
                return;
            }
            if (s.batteryLevel < 0) {
                int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (cap >= 0) {
                    s.batteryLevel = cap;
                }
            }
            long raw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            float amp = toAmp(raw);
            if (!Float.isNaN(amp) && !Float.isNaN(s.batteryVolt)) {
                s.batteryPowerW = Math.abs(s.batteryVolt * amp);
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "读取电池电流失败: " + t);
        }
    }

    /**
     * CURRENT_NOW 的单位在不同机型上不一致：主流是微安，少数 ROM 给毫安，
     * 还有的值直接是 Long.MIN_VALUE（表示「不支持」）。
     *
     * <p>按量级判：手机电流不可能超过 20A，超过就是单位搞错了。
     */
    private static float toAmp(long raw) {
        if (raw == Long.MIN_VALUE || raw == 0) {
            return Float.NaN;
        }
        long abs = Math.abs(raw);
        if (abs > 20_000_000L) {
            return Float.NaN;
        }
        if (abs > 20_000L) {
            return raw / 1_000_000f;   // 微安
        }
        return raw / 1_000f;           // 毫安
    }

    // =====================================================================
    //  系统
    // =====================================================================

    private void readSystem(Snapshot s) {
        s.uptimeMs = SystemClock.elapsedRealtime();
        s.androidVer = trim(Build.VERSION.RELEASE) + " · API " + Build.VERSION.SDK_INT;
        s.device = (trim(Build.MANUFACTURER) + " " + trim(Build.MODEL)).trim();
    }

    // =====================================================================
    //  进程
    // =====================================================================

    /**
     * 进程占用列表。
     *
     * <p>【为什么从 {@code dumpsys cpuinfo} 改成 {@code top -b -n 2}】
     * 实测（Android 14 / 小米）{@code dumpsys cpuinfo} 的统计区间会被**钉住**：
     * 表头写着 {@code CPU usage from 260637ms to 47046ms ago (… to 02:25:46)}，
     * 等了半分钟，右端点还是 02:25:46 —— 只有「左端点」在变宽。
     * 也就是说它复用了一份冻结的旧快照，**百分比和 faults 都不再更新**。
     * 用户看到的「进程占用一直不变」就是这么来的，不是界面没刷新。
     *
     * <p>{@code top -b -n 2}：第一帧的 %CPU 是进程**生命周期平均值**
     * （长期进程会显示成 5%~20%，看着像卡住了），第二帧才按两帧之间的
     * 真实节拍重算，是**瞬时占用**。所以固定取第二帧，并且带 {@code -d 0.5}
     * 把两帧间隔压到半秒 —— 反正每 1 秒要采一次，不能超过刷新周期。
     *
     * <p>★ 显示「应用名」而不是「进程名」：
     * top 给的是包名 / 进程名（{@code com.dragon.read}、{@code surfaceflinger}），
     * 用户认不出这些。所以拿到名字后先当包名去 PackageManager 查一次，
     * 查得到就用应用名，查不到说明是系统进程，原样显示并在界面标灰。
     */
    private void readProcs(Snapshot s, boolean rootOk) {
        if (!rootOk) {
            s.procHint = "需要 root 才能读取其它应用的 CPU 占用";
            return;
        }
        // 不再做跨帧缓存：进程列表必须和本帧其它数字同时刻才有意义。
        // 代价是每秒多一次 top（约 30ms），换来的是「真的在动」。
        // -m 只取前 12 行：界面只列前 5 条，多抓的 30 行纯属浪费（还要逐行做正则）。
        // -d 0.15：两帧间隔。实测 0.5→824ms、0.3→630ms、0.15→约 480ms，
        //   中间那约 315ms 是遍历 980 个进程的固有开销，跟间隔无关。
        //   0.15 秒对「一秒刷一次」的界面已经足够算出有意义的瞬时值，
        //   而 0.5 秒白等 0.35 秒没有收益。
        String out = shell.exec("top -b -n 2 -d 0.15 -m 12", 6000);
        if (out == null) {
            s.procHint = "读取失败（su 无响应）";
            return;
        }
        // 恒取最后一帧：-n 2 的第二帧才是瞬时值，第一帧是进程生命周期平均值；
        // 万一机型把两帧合成一帧，就只有它，退化成平均值也不会比冻结的快照更差。
        String body = DeviceParsers.lastTopFrame(out);

        List<ProcInfo> list = new ArrayList<ProcInfo>();
        int skippedSelf = 0;
        for (String[] row : DeviceParsers.topProcRows(body)) {
            String pct = row[0];
            String proc = row[1];
            // 本应用自己与 top 自己都不该出现在列表里 —— 判断规则与理由见
            // DeviceParsers.shouldSkipProc（那里记着「top 行是整条命令行」这个坑）。
            if (DeviceParsers.shouldSkipProc(proc, app.getPackageName())) {
                skippedSelf++;
                continue;
            }
            list.add(toProcInfo(proc, pct));
        }
        s.procs.addAll(list);
        s.procTotal = sumPct(list);
        s.procHint = list.isEmpty()
                ? (skippedSelf > 0 ? "当前只有本应用在占 CPU" : "top 没有返回进程条目")
                : null;
    }

    /**
     * 把列表里各项的 CPU 百分比加起来，作为「合计」。
     *
     * <p>只在这批进程内部求和，不是整机 —— 所以表头写「合计」而不是「总占用」。
     * 超过 100% 是正常的：多核机型上一个进程能同时吃满多个核
     * （8 核机器的上限是 800%），这与 top 自己那行 {@code 800%cpu} 同口径。
     */
    private String sumPct(List<ProcInfo> list) {
        return DeviceParsers.sumPct(list);
    }

    /**
     * 进程名 → 显示项。
     *
     * <p>先剥掉 {@code :remote} 这类子进程后缀再查包名：
     * {@code com.foo:push} 的应用名应该和 {@code com.foo} 一样。
     */
    private ProcInfo toProcInfo(String proc, String pct) {
        String pkg = proc;
        int colon = pkg.indexOf(':');
        if (colon > 0) {
            pkg = pkg.substring(0, colon);
        }
        String label = appLabel(pkg);
        if (label != null) {
            return new ProcInfo(label, pct + "%", false);
        }
        // 查不到包名 → 系统进程，用原进程名（这些名字本身就可读）
        return new ProcInfo(proc, pct + "%", true);
    }

    /** 包名 → 应用名；没装 / 不可见返回 null。 */
    private String appLabel(String pkg) {
        if (pkg.length() == 0 || pkg.indexOf('.') < 0) {
            return null;
        }
        try {
            PackageManager pm = app.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null) {
                String s = label.toString().trim();
                if (s.length() > 0) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
            // 不是包名 / 已卸载 / 不在 <queries> 可见范围内
        }
        return null;
    }

    // =====================================================================
    //  首页用的轻量摘要
    // =====================================================================

    /**
     * 首页卡片那一行副标题。
     *
     * <p>首页的 refreshAll() 每 1.5 秒跑一次，而且跑在**主线程**上 ——
     * 绝不能在这里起 su 或读 /proc。只做两件最便宜的事：
     * 一次内存 binder 调用、读一条 sticky 电池广播。
     */
    static String[] quickSummary(Context ctx) {
        int mem = quickMemoryPercent(ctx);
        int bat = quickBatteryLevel(ctx);

        StringBuilder sb = new StringBuilder();
        if (mem >= 0) {
            sb.append("内存 ").append(mem).append('%');
        }
        if (bat >= 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("电量 ").append(bat).append('%');
        }
        if (sb.length() == 0) {
            sb.append("点进去查看 CPU / 内存 / 温度");
        }

        // 内存吃紧或电量见底时换成警示色：这两件事值得在首页就看见。
        String color = (mem >= 85 || (bat >= 0 && bat <= 15)) ? "warn" : "ok";
        return new String[]{sb.toString(), color};
    }

    /** 内存占用百分比；读不到返回 -1。 */
    static int quickMemoryPercent(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return -1;
            }
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem <= 0) {
                return -1;
            }
            return (int) Math.round((mi.totalMem - mi.availMem) * 100.0 / mi.totalMem);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 电量百分比；读不到返回 -1。 */
    static int quickBatteryLevel(Context ctx) {
        try {
            Intent it = ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (it == null) {
                return -1;
            }
            int level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) {
                return -1;
            }
            return Math.round(level * 100f / scale);
        } catch (Throwable t) {
            return -1;
        }
    }

    // =====================================================================
    //  常驻 root shell
    // =====================================================================

    /**
     * 一个常驻的 {@code su} shell。
     *
     * <h3>为什么要常驻</h3>
     * 一秒一帧，每帧 fork 一个新的 su 进程（Magisk 还要过授权检查）
     * 在低端机上可以量出延迟，root 管理器日志也会被刷屏。
     * 保持一个进程、启动时授权一次即可。
     *
     * <h3>怎么判断一帧结束</h3>
     * 每帧在命令后追加一行 {@code echo <唯一标记>}，读到这行为止。
     * 不用「读到空行」或「readLine 返回 null」判断，因为：
     * <ul>
     *   <li>命令输出里本身就可能有空行；</li>
     *   <li>readLine 返回 null 表示**进程死了**，那不是「这帧结束」，
     *       而是「下一帧得重连」—— 必须分开处理，否则会把异常当正常。</li>
     * </ul>
     *
     * <h3>超时怎么办</h3>
     * {@code readLine()} 会无限阻塞，而 su 完全可能卡住（等授权）。
     * 所以先用 {@code ready()} 轮询可读性，超过 deadline 就销毁进程，
     * 本次返回 null；下次调用时 {@link #ensure()} 会重连。
     */
    private static final class RootShell {

        private Process proc;
        private BufferedWriter stdin;
        private BufferedReader stdout;
        private int seq;

        /** 阻塞执行一帧，成功返回输出，失败 / 超时返回 null。必须子线程调用。 */
        synchronized String exec(String cmd, long timeoutMs) {
            if (!ensure()) {
                return null;
            }
            final String marker = "__GS_EOF_" + (++seq) + "__";
            try {
                stdin.write(cmd);
                stdin.write("\n");
                stdin.write("echo ");
                stdin.write(marker);
                stdin.write("\n");
                stdin.flush();
            } catch (Throwable t) {
                closeQuietly();
                return null;
            }

            StringBuilder sb = new StringBuilder();
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            try {
                while (true) {
                    if (stdout.ready()) {
                        String line = stdout.readLine();
                        if (line == null) {
                            // 进程已退出（su 被杀 / 授权被撤）
                            closeQuietly();
                            return null;
                        }
                        if (marker.equals(line.trim())) {
                            return sb.toString();
                        }
                        // 上限只是防御：真到这一步说明输出异常，多读也没意义
                        if (sb.length() < 262144) {
                            sb.append(line).append('\n');
                        }
                        continue;
                    }
                    if (SystemClock.elapsedRealtime() > deadline) {
                        AppLog.w(TAG, "root 命令超时(" + timeoutMs + "ms)");
                        closeQuietly();
                        return null;
                    }
                    try {
                        Thread.sleep(8L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } catch (Throwable t) {
                AppLog.w(TAG, "读取 root 输出失败: " + t);
                closeQuietly();
                return null;
            }
        }

        private boolean ensure() {
            if (proc != null && proc.isAlive()) {
                return true;
            }
            closeQuietly();
            try {
                proc = new ProcessBuilder("su").redirectErrorStream(true).start();
                stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), "UTF-8"));
                stdout = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
                return true;
            } catch (Throwable t) {
                AppLog.w(TAG, "启动 su 失败: " + t);
                closeQuietly();
                return false;
            }
        }

        synchronized void closeQuietly() {
            if (stdin != null) {
                try {
                    stdin.close();
                } catch (Throwable ignored) {
                    // 进程已经死了，关不关都无所谓
                }
                stdin = null;
            }
            if (stdout != null) {
                try {
                    stdout.close();
                } catch (Throwable ignored) {
                    // 同上
                }
                stdout = null;
            }
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                    // 同上
                }
                proc = null;
            }
        }
    }

    // =====================================================================
    //  小工具
    // =====================================================================

    /** 委托给 {@link DeviceParsers#parseLong(String, long)}，本类不再重复实现。 */
    private static long parseLong(String s, long def) {
        return DeviceParsers.parseLong(s, def);
    }

    /** 只留数字（"21 %" / "1804800\n" 这类都要能吃）。无法解析返回 null。 */
    /** 委托给 {@link DeviceParsers#digitsOnly(String)}，本类不再重复实现。 */
    private static String digitsOnly(String raw) {
        return DeviceParsers.digitsOnly(raw);
    }

    /**
     * 频率原始值 → MHz。
     *
     * <p>sysfs 的单位没有任何约定，同一个文件名的单位在不同机型上都能不一样：
     * <ul>
     *   <li>CPU 的 {@code scaling_cur_freq} 绝大多数是 <b>kHz</b>（如 1804800）；</li>
     *   <li>kgsl 的 {@code gpuclk}、devfreq 的 {@code cur_freq} 多数是 <b>Hz</b>
     *       （如 257000000），但个别 ROM 会给 kHz。</li>
     * </ul>
     *
     * <p>所以按量级判，分界线取 100 MHz：
     * 手机 CPU 频率最高也就 4~5 GHz（写成 kHz 是 4000000~5000000），
     * 而写成 Hz 的 GPU 频率最低也有 100 MHz 以上（100000000）。
     * 两者差 20 倍以上，用 1 亿当界不会误判。
     *
     * <p>（这里踩过一次坑：原先写成「≥ 1000000 就按 Hz 算」，
     * 结果 1804800 kHz 被当成 1804800 Hz = 1MHz，所有核心都显示成 1MHz。）
     */
    private static long toMHz(String raw) {
        long v = parseLong(digitsOnly(raw), Long.MIN_VALUE);
        if (v == Long.MIN_VALUE || v <= 0) {
            return -1L;
        }
        if (v >= 100_000_000L) {
            return v / 1_000_000L;   // Hz
        }
        if (v >= 1_000L) {
            return v / 1_000L;       // kHz
        }
        return v;                    // 已经是 MHz
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && v.trim().length() > 0) {
                return v.trim();
            }
        }
        return null;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}