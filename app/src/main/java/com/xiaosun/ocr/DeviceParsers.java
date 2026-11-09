package com.xiaosun.ocr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /proc}、{@code /sys} 与 {@code top} 输出的解析器。
 *
 * <h3>为什么从 DeviceStats 里搬出来</h3>
 * 这个工程过去踩过的 bug 里，最容易复发、最难靠肉眼发现的一类就是**解析错误**：
 * <ul>
 *   <li>{@code top} 自己那行的进程名是整条命令行（{@code top -b -n 2 -d 0.5 -m 12}），
 *       最初写成 {@code proc.equals("top")} 匹配不上，于是它一直挤在进程列表里；</li>
 *   <li>按固定列位切 {@code top} 的行，遇到 {@code PR} 列是 {@code -2} / {@code rt}、
 *       {@code RES} 是 {@code 12G} 的机型就错位，只能改成「单字母状态列」当锚点；</li>
 *   <li>温度节点有的报毫摄氏度（{@code 45000}）、有的报整数（{@code 45}）。</li>
 * </ul>
 * 这些逻辑原本混在 {@code DeviceStats} 里，而那个类需要 {@code Context}、要跑
 * {@code su}，导致「想验证一个正则」得先有 root 手机。搬到这里之后，它们全是
 * <b>静态纯函数</b>，丢一段真实输出进去就能在 JVM 上跑，不需要设备、不需要 root。
 *
 * <p>另外两条搬过来的规则，都是实测结论，不是猜的：
 * <ul>
 *   <li>{@code top} 要取<b>最后一帧</b>：{@code -n 2} 的第二帧才是瞬时值，
 *       第一帧是进程生命周期平均值；</li>
 *   <li>{@code cpuN} 列表既可能是 {@code 0-3} 这样的区间，也可能是 {@code 0,1,2,3}
 *       这样的枚举，还可能混着写。</li>
 * </ul>
 */
final class DeviceParsers {

    private DeviceParsers() {
    }

    /**
     * {@code top} 表头里「进程列表」起始行的特征。每帧出现一次，用它切帧最稳。
     */
    static final String TOP_HEADER_MARK = "%CPU]";

    /**
     * 匹配 {@code top} 的一行进程条目。
     *
     * <p>不能按固定列位切：{@code PR} 列可能是 {@code -2} / {@code 20} / {@code rt}，
     * {@code RES} 列可能是 {@code 687M} 或 {@code 12G}，中间字段宽度随数值变化。
     * 唯一稳定的是「8 个字段之后紧跟一个单字母状态列，再跟 CPU%、MEM%」这一段。
     * <ul>
     *   <li>group(1) = CPU 百分比数字（不含 %）</li>
     *   <li>group(2) = 进程名（可能是整条命令行）</li>
     * </ul>
     */
    static final Pattern P_TOP_PROC = Pattern.compile(
            "^\\s*\\d+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+[A-Z]\\s+([\\d.]+)\\s+"
                    + "[\\d.]+\\s+\\S+\\s+(\\S.*)$");

    /**
     * 把 {@code top -b -n 2} 的整段输出切成若干帧，返回<b>最后一帧</b>。
     *
     * <p>取最后一帧而不是第一帧，是因为 {@code -n 2} 的第一帧是进程生命周期平均值，
     * 第二帧才是「最近一个采样间隔内的瞬时值」—— 界面要的是后者。
     *
     * <p>万一机型把两帧合成一帧（输出里只有一次表头），这里自然只有一帧，
     * 退化成平均值，也不会比「冻结的旧快照」更差。
     */
    static String lastTopFrame(String out) {
        if (out == null) {
            return null;
        }
        List<String> frames = new ArrayList<String>();
        StringBuilder cur = null;
        for (String raw : out.split("\n")) {
            if (raw.contains(TOP_HEADER_MARK)) {
                if (cur != null) {
                    frames.add(cur.toString());
                }
                cur = new StringBuilder();
            }
            if (cur != null) {
                cur.append(raw).append('\n');
            }
        }
        if (cur != null) {
            frames.add(cur.toString());
        }
        return frames.isEmpty() ? out : frames.get(frames.size() - 1);
    }

    /**
     * 判断这一行进程是否应该跳过（不入列表）。
     *
     * <p>两种情况要跳过：
     * <ol>
     *   <li><b>本应用自己</b> —— 它一进页面就在采样，会稳稳占住榜首，
     *       反而把用户真正想找的那个挤掉；</li>
     *   <li><b>{@code top} 自己</b> —— 它是本次测量起的子进程。
     *       注意它的进程名是<b>整条命令行</b>（{@code top -b -n 2 -d 0.15 -m 12}），
     *       不是光秃秃的 {@code top}，所以只能用前缀判，不能用 {@code equals}。
     *       这个 bug 真实发生过。</li>
     * </ol>
     */
    static boolean shouldSkipProc(String proc, String selfPackage) {
        if (proc == null) {
            return true;
        }
        if (selfPackage != null && proc.startsWith(selfPackage)) {
            return true;
        }
        // 两种形态都挡：`top`（裸名，某些 ROM 的 top 就是这样报的）
        // 与 `top -b -n 2 ...`（整条命令行，本机实测）。只用 startsWith("top ")
        // 会漏掉前者，只用 equals("top") 会漏掉后者 —— 两个真实的坑都在这行里。
        return "top".equals(proc) || proc.startsWith("top ");
    }

    /**
     * 解析 {@code affected_cpus} / {@code related_cpus} 这类 CPU 列表。
     *
     * <p>两种写法都出现过：枚举 {@code 0,1,2,3} 与区间 {@code 0-3}，也可能混写
     * （{@code 0-3,6-7}）。越界与倒序区间一律丢弃 —— 宁可少一个核，
     * 也不要让下标飞出数组。
     *
     * @param cores 核数上限，用来丢弃越界下标
     */
    static List<Integer> parseCpuList(String raw, int cores) {
        List<Integer> out = new ArrayList<Integer>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.trim().split("[,\\s]+")) {
            if (part.length() == 0) {
                continue;
            }
            int dash = part.indexOf('-');
            if (dash > 0) {
                int a = (int) parseLong(part.substring(0, dash), -1L);
                int b = (int) parseLong(part.substring(dash + 1), -1L);
                if (a >= 0 && b >= a && b < cores) {
                    for (int i = a; i <= b; i++) {
                        out.add(i);
                    }
                }
            } else {
                int n = (int) parseLong(part, -1L);
                if (n >= 0 && n < cores) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /**
     * 解析 GPU 忙闲百分比。返回 {@code -1} 表示「读不到」，而不是 0。
     *
     * <p>这一点很重要：「读不到」与「确实闲着」在界面上必须分开显示
     * （前者是「—」，后者是 0%），否则用户会以为 GPU 真的空着。
     *
     * <p>支持两种 sysfs 形态：
     * <ul>
     *   <li>直接给百分比：{@code "37" } 或 {@code "37 %"}</li>
     *   <li>给「忙 / 闲」两个累计数：{@code "121629 1006864"}，按比例算</li>
     * </ul>
     */
    static int parseGpuBusy(String raw) {
        if (raw == null) {
            return -1;
        }
        String s = raw.trim();
        if (s.length() == 0) {
            return -1;
        }
        List<String> nums = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                cur.append(c);
            } else if (cur.length() > 0) {
                nums.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            nums.add(cur.toString());
        }
        if (nums.isEmpty()) {
            return -1;
        }
        if (nums.size() >= 2) {
            long busy = parseLong(nums.get(0), -1L);
            long idle = parseLong(nums.get(1), -1L);
            if (busy < 0 || idle < 0) {
                return -1;
            }
            long sum = busy + idle;
            if (sum <= 0) {
                // 还没开始统计：此时 GPU 确实闲着，报 0% 是准确的，
                // 不是「读不到」—— 两者在界面上要区分开。
                return 0;
            }
            // 封顶 100：某些驱动的忙闲计数会在频率切换瞬间短暂超过总和，
            // 不封顶会显示「103%」这种不可能的值。
            return (int) Math.min(100, Math.round(busy * 100.0 / sum));
        }
        // 单个数字：<=100 就当它已是百分比；再大说明是原始计数，
        // 没有 idle 配对算不出比例，只能放弃而不是瞎猜。
        long v = parseLong(nums.get(0), -1L);
        if (v < 0 || v > 100) {
            return -1;
        }
        return (int) v;
    }

    /**
     * 解析温度节点。返回 {@code NaN} 表示读不到或不合理。
     *
     * <p>有的内核报毫摄氏度（{@code 45000}），有的报整度（{@code 45}）。
     * 大于 1000 一律当毫度处理。范围外的值（&lt;1 或 &gt;120°C）判为无效 ——
     * 宁可显示「—」，也不要显示一个吓人的假温度。
     */
    static float parseTempC(String raw) {
        String digits = digitsOnly(raw);
        if (digits == null) {
            return Float.NaN;
        }
        long v = parseLong(digits, Long.MIN_VALUE);
        if (v == Long.MIN_VALUE) {
            return Float.NaN;
        }
        float c = v > 1000L ? v / 1000f : v;
        if (c < 1f || c > 120f) {
            return Float.NaN;
        }
        return c;
    }

    /** 取出开头的连续数字（遇到第一个非数字就停），没有则返回 null。 */
    static String digitsOnly(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 宽松解析 long：失败返回 def，不抛异常。 */
    static long parseLong(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    /**
     * 把列表里各项的 CPU 百分比加起来，作为「合计」。
     *
     * <p>只在这批进程内部求和，不是整机 —— 所以界面写「合计」而不是「总占用」。
     * 超过 100% 是正常的：多核机型上一个进程能同时吃满多个核
     * （8 核机器的上限是 800%），这与 {@code top} 自己那行 {@code 800%cpu} 同口径。
     */
    static String sumPct(List<DeviceStats.ProcInfo> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (DeviceStats.ProcInfo p : list) {
            try {
                sum += Double.parseDouble(p.pct.replace("%", ""));
            } catch (NumberFormatException ignored) {
                // 单个坏值不影响整体
            }
        }
        return String.format(Locale.US, "%.0f%%", sum);
    }

    /** 在文本里找 {@code top} 的进程行，逐行交给调用方决定是否收下。 */
    static List<String[]> topProcRows(String body) {
        List<String[]> out = new ArrayList<String[]>();
        if (body == null) {
            return out;
        }
        for (String raw : body.split("\n")) {
            String t = raw.trim();
            if (t.length() == 0) {
                continue;
            }
            Matcher m = P_TOP_PROC.matcher(raw);
            if (!m.find()) {
                continue;
            }
            out.add(new String[]{m.group(1), m.group(2).trim()});
        }
        return out;
    }
}