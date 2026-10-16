package com.xiaosun.ocr;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用内日志 —— 给用户「导出并发送」用。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么要有这个东西】
 * ════════════════════════════════════════════════════════════════
 * 有些问题只在别人的手机上出现（例如「点滑动菜单闪退」「系统手势没屏蔽」），
 * 本机怎么复现都正常。这时候能拿到的唯一线索就是对方设备上的现场记录。
 *
 * 但让测试用户去开 USB 调试、抓 logcat 是不现实的。所以：
 *   崩溃 → 自动记（不需要用户做任何事）
 *   其它 → 关键路径主动记
 * 用户只需要点一下「分享日志」，把文件发过来即可。
 *
 * ════════════════════════════════════════════════════════════════
 * 【落在哪、怎么拿】
 * ════════════════════════════════════════════════════════════════
 * 文件：{@code Android/data/com.xiaosun.ocr/files/logs/xsocr_log.txt}
 *
 * 放在**应用自己的外部私有目录**（getExternalFilesDir），好处：
 *   · 不需要任何存储权限
 *   · 卸载应用时跟着一起清掉，不留垃圾
 *   · 通过 FileProvider 分享给微信 / QQ / 邮件都合法
 *
 * Android 11+ 的系统文件管理器不让进 Android/data，所以**不要**指望
 * 用户自己去翻文件：设置页里给了「分享日志」（走 ACTION_SEND）
 * 和「导出到下载目录」（落到公共 Download，微信能直接选）两条路。
 *
 * ════════════════════════════════════════════════════════════════
 * 【两个刻意的设计】
 * ════════════════════════════════════════════════════════════════
 * 1. **绝不影响主流程。** 所有写文件操作都在单线程池里，
 *    任何 IO 异常都被吞掉（日志记不上事小，把功能搞崩事大）。
 *    唯一例外是崩溃处理：那时候进程马上就要死了，
 *    必须**同步**写完再交给系统，所以有 logSync()。
 *
 * 2. **文件不会无限长大。** 超过 {@link #MAX_BYTES} 就把当前文件
 *    改名成 xsocr_log.old.txt（上一份被覆盖），另起一份新的。
 *    这样最多占两份的空间，用户随时发过来都是「最近的一段」。
 */
public final class AppLog {

    /** 日志文件名（分享出去的名字也是它）。 */
    public static final String FILE_NAME = "xsocr_log.txt";

    /** FileProvider 的 authority，必须和 AndroidManifest 里声明的一致。 */
    public static final String AUTHORITY = "com.xiaosun.ocr.fileprovider";

    private static final String DIR_NAME = "logs";
    private static final String OLD_NAME = "xsocr_log.old.txt";
    private static final String TAG = "XsOcrAppLog";

    /** 单份日志的上限，超过就轮转。512KB 够记几万行，微信发送毫无压力。 */
    private static final long MAX_BYTES = 512 * 1024;

    /** 内存里保留的最近条数，「查看日志」弹窗直接用它，不读文件。 */
    private static final int MEM_MAX = 300;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> MEM = new ArrayDeque<>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /** 单线程池：保证写入顺序，也不占主线程。 */
    private static ExecutorService io;
    private static volatile File logFile;
    private static volatile boolean installed;

    private AppLog() {
    }

    // ============================================================
    //  安装：崩溃自动记录
    // ============================================================

    /**
     * 装好日志系统。**幂等**，重复调用无副作用，
     * 所以 MainActivity 与两个 Service 的 onCreate 里都可以放心调 ——
     * 崩在哪个进程，日志就在哪个进程里留下。
     */
    public static void install(Context c) {
        if (c == null) return;
        try {
            Context app = c.getApplicationContext();
            File f = file(app);
            if (f == null) return;
            synchronized (LOCK) {
                if (installed) return;
                installed = true;
                io = Executors.newSingleThreadExecutor();
            }
            // 抢在系统前面接管未捕获异常 —— 「点菜单闪退」这类问题全靠它留证据
            installCrashHandler();
            // 打个分隔，方便看清「这次启动」从哪儿开始
            log("I", TAG, "===== 会话开始 =====");
            log("I", TAG, "设备: " + deviceInfo(app).replace("\n", " | "));
        } catch (Throwable t) {
            Log.w(TAG, "日志初始化失败: " + t);
        }
    }

    /**
     * 自己装崩溃处理器。
     *
     * 为什么要抢在系统前面：默认处理器只会往 logcat 写一行然后杀进程，
     * 用户那边什么都不会留下。这里先落盘、再交回给原来的处理器
     * （保留系统的「应用已停止」对话框等行为，不改变用户体验）。
     */
    private static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    logSync("E", "CRASH", "未捕获异常，线程=" + t.getName());
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    logSync("E", "CRASH", sw.toString());
                    logSync("E", "CRASH", "===== 崩溃记录结束 =====");
                } catch (Throwable ignored) {
                    // 连记日志都失败了就只能放弃 —— 不能再抛，否则死循环
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    // ============================================================
    //  记录
    // ============================================================

    public static void d(String tag, String msg) { log("D", tag, msg); }

    public static void i(String tag, String msg) { log("I", tag, msg); }

    public static void w(String tag, String msg) { log("W", tag, msg); }

    public static void e(String tag, String msg) { log("E", tag, msg); }

    /** 记一条异常（带完整堆栈），不中断流程。 */
    public static void e(String tag, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(msg == null ? "" : msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append('\n').append(sw);
        }
        log("E", tag, sb.toString());
    }

    /**
     * 异步记一条。
     *
     * 同时打 logcat —— 本机调试时用 Android Studio 看照样方便，
     * 不用为了看日志专门跑一遍导出流程。
     */
    public static void log(final String level, final String tag, final String msg) {
        String line = format(level, tag, msg);
        push(line);
        echoToLogcat(level, tag, msg);
        final File f = logFile;
        if (f == null) return;
        exec(() -> writeLine(f, line));
    }

    /** 同步记一条 —— 只给崩溃处理用（进程马上要没了，异步写会丢）。 */
    public static void logSync(String level, String tag, String msg) {
        String line = format(level, tag, msg);
        push(line);
        echoToLogcat(level, tag, msg);
        File f = logFile;
        if (f != null) writeLine(f, line);
    }

    private static void echoToLogcat(String level, String tag, String msg) {
        try {
            String s = msg == null ? "" : msg;
            if (s.length() > 3000) s = s.substring(0, 3000) + "…(截断)";
            switch (level) {
                case "E": Log.e(tag, s); break;
                case "W": Log.w(tag, s); break;
                case "D": Log.d(tag, s); break;
                default:  Log.i(tag, s); break;
            }
        } catch (Throwable ignored) {
        }
    }

    private static String format(String level, String tag, String msg) {
        String ts;
        synchronized (TS) {
            ts = TS.format(new Date());
        }
        return ts + " " + level + "/" + tag + " " + (msg == null ? "" : msg);
    }

    private static void push(String line) {
        synchronized (MEM) {
            MEM.addLast(line);
            while (MEM.size() > MEM_MAX) MEM.removeFirst();
        }
    }

    private static void exec(Runnable r) {
        ExecutorService e = io;
        if (e == null) {
            try { r.run(); } catch (Throwable ignored) {}
            return;
        }
        try {
            e.execute(r);
        } catch (Throwable t) {
            // 池已关（进程退出中）就直接跑
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 追加一行到文件。写完顺手检查大小，超了就轮转。
     *
     * 用 FileOutputStream(append=true) 而不是 BufferedWriter：
     * 崩溃路径要求「写完立刻在盘上」，不留缓冲。
     */
    private static void writeLine(File f, String line) {
        OutputStream os = null;
        try {
            os = new FileOutputStream(f, true);
            os.write((line + "\n").getBytes("UTF-8"));
            os.flush();
        } catch (Throwable t) {
            // 写不进去就算了，绝不能因为日志把功能搞挂
        } finally {
            close(os);
        }
        try {
            if (f.length() > MAX_BYTES) {
                File old = new File(f.getParentFile(), OLD_NAME);
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
        } catch (Throwable ignored) {
        }
    }

    // ============================================================
    //  读取 / 清理
    // ============================================================

    /** 最近若干条（内存版，用于「查看日志」弹窗）。 */
    public static List<String> recent() {
        synchronized (MEM) {
            return new ArrayList<>(MEM);
        }
    }

    /** 清空内存与文件。 */
    public static void clear(Context c) {
        synchronized (MEM) {
            MEM.clear();
        }
        try {
            File f = file(c);
            if (f != null && f.exists()) f.delete();
            if (f != null) {
                File old = new File(f.getParentFile(), OLD_NAME);
                if (old.exists()) old.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 日志文件（可能还不存在）。拿不到外部目录时回退到内部目录。 */
    public static File file(Context c) {
        File f = logFile;
        if (f != null) return f;
        try {
            Context app = c.getApplicationContext();
            File base = app.getExternalFilesDir(null);
            if (base == null) base = app.getFilesDir();
            File dir = new File(base, DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                // 建不出来就退回内部目录，至少有得记
                dir = new File(app.getFilesDir(), DIR_NAME);
                if (!dir.exists()) dir.mkdirs();
            }
            f = new File(dir, FILE_NAME);
            logFile = f;
            return f;
        } catch (Throwable t) {
            Log.w(TAG, "取日志文件失败: " + t);
            return null;
        }
    }

    // ============================================================
    //  导出 / 分享
    // ============================================================

    /** 导出到公共「下载」目录，文件名带时间戳，微信/QQ 能直接选。 */
    public static File exportToDownloads(Context c) {
        try {
            File src = file(c);
            if (src == null || !src.exists()) return null;
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            String stamp;
            synchronized (TS) {
                stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            }
            File dst = new File(dir, "小孙OCR日志_" + stamp + ".txt");
            copy(src, dst);
            return dst;
        } catch (Throwable t) {
            Log.w(TAG, "导出日志失败: " + t);
            return null;
        }
    }

    /**
     * 拉起系统分享面板把日志发出去。
     *
     * 走 FileProvider：Android 7.0 起直接传 file:// URI 会抛
     * FileUriExposedException，必须用 content:// 并显式授权读权限。
     */
    public static boolean share(Context c, String extraText) {
        try {
            File f = file(c);
            if (f == null || !f.exists() || f.length() == 0) return false;
            Uri uri = FileProvider.getUriForFile(c.getApplicationContext(), AUTHORITY, f);
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("text/plain");
            it.putExtra(Intent.EXTRA_STREAM, uri);
            it.putExtra(Intent.EXTRA_SUBJECT, "小孙OCR 日志");
            if (extraText != null) it.putExtra(Intent.EXTRA_TEXT, extraText);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(it, "发送日志");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(chooser);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "分享日志失败: " + t);
            return false;
        }
    }

    /** 日志内容摘要，给界面显示「有多少行、多大」。 */
    public static String stat(Context c) {
        try {
            File f = file(c);
            if (f == null || !f.exists()) return "暂无日志";
            long kb = Math.max(1, f.length() / 1024);
            int lines = 0;
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    for (int i = 0; i < n; i++) {
                        if (buf[i] == '\n') lines++;
                    }
                }
            }
            return lines + " 行 · " + kb + " KB";
        } catch (Throwable t) {
            return "读取失败";
        }
    }

    // ============================================================
    //  设备信息（排障必需：同一个问题在不同 ROM 上表现完全不同）
    // ============================================================

    public static String deviceInfo(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("应用: 小孙OCR ").append("v").append(versionName(c)).append('\n');
            sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" / ").append(Build.BRAND).append('\n');
            sb.append("ROM: ").append(Build.DISPLAY).append('\n');
            sb.append("ABI: ").append(join(Build.SUPPORTED_ABIS)).append('\n');
            sb.append("屏幕: ").append(screenInfo(c));
        } catch (Throwable t) {
            sb.append("(设备信息读取失败: ").append(t).append(")");
        }
        return sb.toString();
    }

    private static String screenInfo(Context c) {
        try {
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            return dm.widthPixels + "x" + dm.heightPixels + " @" + dm.densityDpi
                    + "dpi (density=" + dm.density + ")";
        } catch (Throwable t) {
            return "未知";
        }
    }

    private static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return BuildConfig.VERSION_NAME;
        }
    }

    private static String join(String[] arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    // ============================================================
    //  小工具
    // ============================================================

    private static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    private static void close(OutputStream os) {
        if (os == null) return;
        try {
            os.close();
        } catch (Throwable ignored) {
        }
    }
}