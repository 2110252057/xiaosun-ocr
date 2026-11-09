package com.xiaosun.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * DeviceParsers 的单元测试。
 *
 * <p>这些用例不是「为了覆盖率」，每一条都对应一个<b>真实发生过</b>或
 * 极易发生的错误。样本尽量照抄真实 {@code top} / sysfs 输出的形状，
 * 包括那些看起来别扭的地方（进程名带整条命令行、PR 列是 {@code -2}、
 * RES 是 {@code 12G}）。
 *
 * <p>纯 JVM 运行，不需要 root、不需要真机：
 * <pre>./gradlew :app:testDebugUnitTest</pre>
 */
public class DeviceParsersTest {

    // ---------------------------------------------------------------
    //  top 帧切分
    // ---------------------------------------------------------------

    /**
     * 一段两帧的真实结构（照抄真机 top -b -n 2 的骨架）。
     * 第二帧的 CPU 占比与第一帧不同，用来验证「取最后一帧」确实生效。
     */
    private static final String TOP_TWO_FRAMES =
            "Tasks: 980 total,   2 running\n"
                    + "Mem:   7.4G used\n"
                    + "  800%cpu  78%user   0%nice  40%sys 644%idle\n"
                    + "  PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS\n"
                    + " 1000 u0_a10       -2   0 5.2G 687M 300M S 11.0   9.1   1:00.00 com.old.app\n"
                    + "\n"
                    + "Tasks: 980 total,   2 running\n"
                    + "Mem:   7.4G used\n"
                    + "  800%cpu  60%user   0%nice  30%sys 700%idle\n"
                    + "  PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS\n"
                    + " 2000 u0_a20       -2   0 6.2G  12G 300M S 42.5   8.0   2:00.00 com.new.app\n"
                    + " 2001 root         20   0 100M  1M  1M R  3.0   0.1   0:01.00 top -b -n 2 -d 0.15 -m 12\n";

    @Test
    public void lastTopFrame_取第二帧而不是第一帧() {
        String body = DeviceParsers.lastTopFrame(TOP_TWO_FRAMES);
        // 第二帧才有 com.new.app；第一帧的 com.old.app 不应该出现
        assertTrue("应取到第二帧", body.contains("com.new.app"));
        assertFalse("不应包含第一帧的进程", body.contains("com.old.app"));
    }

    @Test
    public void lastTopFrame_只有一帧时原样返回() {
        String one = "  PID USER PR NI VIRT RES SHR S[%CPU] %MEM TIME+ ARGS\n"
                + "  1 root 20 0 1M 1M 1M S 1.0 0.1 0:01.00 init\n";
        String body = DeviceParsers.lastTopFrame(one);
        assertTrue(body.contains("init"));
    }

    @Test
    public void lastTopFrame_null安全() {
        assertNull(DeviceParsers.lastTopFrame(null));
    }

    // ---------------------------------------------------------------
    //  top 进程行解析
    // ---------------------------------------------------------------

    @Test
    public void topProcRows_能解析常规行() {
        List<String[]> rows = DeviceParsers.topProcRows(
                " 10314 u0_a290      -2   0  47G  687M  359M S 58.0   6.1   5:13.87 com.dragon.read\n");
        assertEquals(1, rows.size());
        assertEquals("58.0", rows.get(0)[0]);
        assertEquals("com.dragon.read", rows.get(0)[1]);
    }

    /**
     * PR 列可以是 {@code rt}、RES 列可以是 {@code 12G} ——
     * 这正是「不能按固定列位切」的原因。
     */
    @Test
    public void topProcRows_PR列是rt且RES是G也能解析() {
        List<String[]> rows = DeviceParsers.topProcRows(
                "  555 root          rt   0  100M   12G  1M R 12.3   0.2   0:10.00 kworker/u16:1\n");
        assertEquals(1, rows.size());
        assertEquals("12.3", rows.get(0)[0]);
        assertEquals("kworker/u16:1", rows.get(0)[1]);
    }

    @Test
    public void topProcRows_表头与空行被忽略() {
        List<String[]> rows = DeviceParsers.topProcRows(
                "  PID USER PR NI VIRT RES SHR S[%CPU] %MEM TIME+ ARGS\n"
                        + "\n"
                        + "  123 u0_a1 20 0 1M 1M 1M S 5.0 0.1 0:01.00 com.x\n");
        assertEquals(1, rows.size());
    }

    // ---------------------------------------------------------------
    //  跳过规则（真实 bug 所在）
    // ---------------------------------------------------------------

    /**
     * <b>这是本项目真实踩过的坑</b>：top 自己那行的进程名是整条命令行，
     * 最初写成 {@code equals("top")} 匹配不上，于是它一直留在列表里。
     */
    @Test
    public void shouldSkipProc_top行是整条命令行也要跳过() {
        assertTrue(DeviceParsers.shouldSkipProc("top -b -n 2 -d 0.15 -m 12", null));
        assertTrue(DeviceParsers.shouldSkipProc("top", null));
    }

    @Test
    public void shouldSkipProc_本应用自己要跳过() {
        assertTrue(DeviceParsers.shouldSkipProc("com.xiaosun.ocr", "com.xiaosun.ocr"));
        assertTrue(DeviceParsers.shouldSkipProc("com.xiaosun.ocr:push", "com.xiaosun.ocr"));
    }

    @Test
    public void shouldSkipProc_别的应用不跳过() {
        assertFalse(DeviceParsers.shouldSkipProc("com.dragon.read", "com.xiaosun.ocr"));
        // 名字里含 top 但不在行首，不该被误杀
        assertFalse(DeviceParsers.shouldSkipProc("com.example.laptop", "com.xiaosun.ocr"));
    }

    @Test
    public void shouldSkipProc_null安全() {
        assertTrue(DeviceParsers.shouldSkipProc(null, "com.xiaosun.ocr"));
    }

    // ---------------------------------------------------------------
    //  CPU 列表
    // ---------------------------------------------------------------

    @Test
    public void parseCpuList_区间写法() {
        List<Integer> l = DeviceParsers.parseCpuList("0-3", 8);
        assertEquals(4, l.size());
        assertEquals(Integer.valueOf(0), l.get(0));
        assertEquals(Integer.valueOf(3), l.get(3));
    }

    @Test
    public void parseCpuList_枚举写法() {
        List<Integer> l = DeviceParsers.parseCpuList("0,1,2,3", 8);
        assertEquals(4, l.size());
    }

    @Test
    public void parseCpuList_混写() {
        List<Integer> l = DeviceParsers.parseCpuList("0-3,6-7", 8);
        assertEquals(6, l.size());
        assertEquals(Integer.valueOf(6), l.get(4));
        assertEquals(Integer.valueOf(7), l.get(5));
    }

    /** 越界与倒序区间必须丢弃，否则下标会飞出数组。 */
    @Test
    public void parseCpuList_越界与倒序被丢弃() {
        assertTrue(DeviceParsers.parseCpuList("4-9", 4).isEmpty());
        assertTrue(DeviceParsers.parseCpuList("3-1", 8).isEmpty());
        assertTrue(DeviceParsers.parseCpuList("99", 8).isEmpty());
    }

    @Test
    public void parseCpuList_null与空串() {
        assertTrue(DeviceParsers.parseCpuList(null, 8).isEmpty());
        assertTrue(DeviceParsers.parseCpuList("", 8).isEmpty());
    }

    // ---------------------------------------------------------------
    //  GPU 忙闲
    // ---------------------------------------------------------------

    /** 「读不到」必须是 -1，不能是 0 —— 界面上这两者一个显示「—」一个显示 0%。 */
    @Test
    public void parseGpuBusy_读不到返回负一() {
        assertEquals(-1, DeviceParsers.parseGpuBusy(null));
        assertEquals(-1, DeviceParsers.parseGpuBusy(""));
        assertEquals(-1, DeviceParsers.parseGpuBusy("   "));
        assertEquals(-1, DeviceParsers.parseGpuBusy("unavailable"));
    }

    @Test
    public void parseGpuBusy_单个百分比() {
        assertEquals(37, DeviceParsers.parseGpuBusy("37"));
        assertEquals(37, DeviceParsers.parseGpuBusy("37 %"));
    }

    /** 两个累计数的形态：busy / (busy+idle)。 */
    @Test
    public void parseGpuBusy_忙闲累计数按比例算() {
        // 121629 / (121629+1006864) ≈ 10.78% → 11
        assertEquals(11, DeviceParsers.parseGpuBusy("121629 1006864"));
    }

    /** 总和为 0 说明还没开始统计：此时 GPU 确实闲着，0% 是准确值而非「读不到」。 */
    @Test
    public void parseGpuBusy_总和为零报零而不是负一() {
        assertEquals(0, DeviceParsers.parseGpuBusy("0 0"));
    }

    /** 单个大数没有 idle 配对，算不出比例，只能放弃而不是瞎猜。 */
    @Test
    public void parseGpuBusy_单个大数放弃() {
        assertEquals(-1, DeviceParsers.parseGpuBusy("121629"));
    }

    // ---------------------------------------------------------------
    //  温度
    // ---------------------------------------------------------------

    @Test
    public void parseTempC_毫摄氏度() {
        assertEquals(45f, DeviceParsers.parseTempC("45000"), 0.01f);
    }

    @Test
    public void parseTempC_整度() {
        assertEquals(45f, DeviceParsers.parseTempC("45"), 0.01f);
    }

    /** 不合理值一律 NaN，宁可显示「—」也不显示一个吓人的假温度。 */
    @Test
    public void parseTempC_超范围返回NaN() {
        assertTrue(Float.isNaN(DeviceParsers.parseTempC("0")));
        assertTrue(Float.isNaN(DeviceParsers.parseTempC("200000")));
    }

    @Test
    public void parseTempC_非数字返回NaN() {
        assertTrue(Float.isNaN(DeviceParsers.parseTempC("n/a")));
        assertTrue(Float.isNaN(DeviceParsers.parseTempC("")));
        assertTrue(Float.isNaN(DeviceParsers.parseTempC(null)));
    }

    // ---------------------------------------------------------------
    //  小工具
    // ---------------------------------------------------------------

    @Test
    public void digitsOnly_取开头数字() {
        assertEquals("45", DeviceParsers.digitsOnly("45\n"));
        assertEquals("45", DeviceParsers.digitsOnly("45000"));
        assertNull(DeviceParsers.digitsOnly("abc"));
        assertNull(DeviceParsers.digitsOnly(null));
    }

    @Test
    public void parseLong_失败返回默认值() {
        assertEquals(7L, DeviceParsers.parseLong("7", -1L));
        assertEquals(-1L, DeviceParsers.parseLong("x", -1L));
        assertEquals(-1L, DeviceParsers.parseLong(null, -1L));
        // 带空白的也要能解析，sysfs 读出来常带换行
        assertEquals(7L, DeviceParsers.parseLong(" 7\n", -1L));
    }
}