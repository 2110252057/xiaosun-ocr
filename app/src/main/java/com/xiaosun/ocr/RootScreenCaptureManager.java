package com.xiaosun.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 基于 root 的屏幕截图管理器。
 *
 * 为什么需要它：MediaProjection 每次开机都要用户手动点一次「开启截屏」弹窗，
 * 对无人值守 / 自动化场景很不友好。若设备已有 root，直接用系统自带的
 * {@code screencap} 命令抓屏即可，**无需任何授权、开机即可用**。
 *
 * 实现要点：
 *   1. 优先在应用私有目录（/data/data/<pkg>/cache）里写 PNG，
 *      App 自己可读，不依赖存储权限；失败再退回 /sdcard；
 *   2. 用 {@code screencap -p} 输出 PNG，直接交给 BitmapFactory 解码；
 *   3. 横屏时把画面旋正，保证后续坐标与屏幕方向一致；
 *   4. 结果统一转成不透明位图（OCR 引擎对带透明通道的图表现不稳定）。
 */
public class RootScreenCaptureManager {

    private static final String TAG = "XiaoSunOCR-RootCap";

    /** 单次截屏命令超时（毫秒）。 */
    private static final long TIMEOUT_MS = 10_000;

    private final Context context;
    private final File cacheDir;

    private volatile boolean ready = false;
    private int width;
    private int height;

    public RootScreenCaptureManager(Context context) {
        this.context = context.getApplicationContext();
        this.cacheDir = context.getApplicationContext().getCacheDir();
    }

    // =====================================================================
    //  可用性探测
    // =====================================================================

    /**
     * 检测设备是否具备可用的 root 截屏能力。
     *
     * 判定条件：`su -c` 能成功执行，并且 `screencap` 真实存在。
     * 只用 `su -c id` 判断是不够的 —— 部分机型的 su 可用但 screencap 被 SELinux 限制。
     */
    public static boolean isRootAvailable() {
        // ① su 是否可用
        //    注意：首次调用时 Magisk 等会弹「授权」框，用户需要点一下，
        //    因此这里给一个较宽松的超时，避免用户还没点就被判为无 root。
        if (!execQuiet("su -c id", 20_000)) {
            return false;
        }
        // ② screencap 是否存在且可执行（只是 which，开销很小）
        return execQuiet("su -c \"command -v screencap\"", 8_000);
    }

    /** 执行一条命令，只关心退出码是否为 0。 */
    private static boolean execQuiet(String cmd, long timeoutMs) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "命令超时: " + cmd);
                p.destroy();
                return false;
            }
            int code = p.exitValue();
            if (code != 0) {
                return false;
            }
            // 成功时应有输出（id 输出 uid，command -v 输出路径）
            try (InputStream is = p.getInputStream()) {
                return is.read() != -1;
            }
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 初始化：探测 root 并记录屏幕尺寸。
     *
     * 建议在后台线程调用（会起 su 进程）。
     *
     * @return 是否可用
     */
    public boolean start() {
        ready = false;
        if (!isRootAvailable()) {
            Log.i(TAG, "未检测到可用的 root / screencap");
            return false;
        }

        // 屏幕尺寸：优先用 wm size，拿不到就用 DisplayMetrics 兜底
        int[] size = queryScreenSize();
        width = size[0];
        height = size[1];

        // 真正试截一张，确认 SELinux / 路径都放行
        try {
            Bitmap probe = capture();
            if (probe != null) {
                probe.recycle();
                ready = true;
                Log.i(TAG, "root 截屏已就绪: " + width + "x" + height);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "root 截屏试拍失败: " + t);
        }
        Log.w(TAG, "root 截屏不可用");
        return false;
    }

    public boolean isReady() {
        return ready;
    }

    public int screenWidth() {
        return width;
    }

    public int screenHeight() {
        return height;
    }

    /** 通过 `wm size` 读取真实屏幕尺寸。 */
    private int[] queryScreenSize() {
        int w = 0;
        int h = 0;
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", "su -c \"wm size\"")
                    .redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                // 形如 "Physical size: 1156x2510"
                int idx = line.indexOf(':');
                String sz = idx >= 0 ? line.substring(idx + 1).trim() : line.trim();
                String[] wh = sz.split("x");
                if (wh.length == 2) {
                    try {
                        w = Integer.parseInt(wh[0].trim());
                        h = Integer.parseInt(wh[1].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            br.close();
            p.waitFor(3, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "读取屏幕尺寸失败: " + t);
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }

        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        return new int[]{w, h};
    }

    // =====================================================================
    //  截屏
    // =====================================================================

    /** 截取当前屏幕。必须在工作线程调用。 */
    public synchronized Bitmap capture() throws Exception {
        File png = captureToFile();
        if (png == null || !png.exists() || png.length() == 0) {
            throw new IllegalStateException("root 截屏失败：未生成图片");
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(png.getAbsolutePath(), opts);
            if (bmp == null) {
                throw new IllegalStateException("root 截屏失败：PNG 解码失败");
            }
            width = bmp.getWidth();
            height = bmp.getHeight();
            return finish(bmp);
        } finally {
            // 先解码再删，避免同一路径并发覆盖
            try {
                png.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 执行 screencap 并返回图片文件；失败返回 null。 */
    private File captureToFile() {
        // ① 优先写应用私有缓存目录：App 自身必定可读，且不需要存储权限
        File f = new File(cacheDir, "root_shot.png");
        if (runScreencap(f.getAbsolutePath()) && f.exists() && f.length() > 0) {
            return f;
        }
        try {
            f.delete();
        } catch (Throwable ignored) {
        }

        // ② 退回外部目录（部分 ROM 上 su 进程对私有目录权限受限）
        File fallback = new File(context.getExternalCacheDir(), "root_shot.png");
        if (runScreencap(fallback.getAbsolutePath()) && fallback.exists()
                && fallback.length() > 0) {
            return fallback;
        }
        try {
            fallback.delete();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 执行 `su -c screencap -p <path>`。
     *
     * 注意：必须用 {@code sh -c} 包一层并把整条 su 命令作为字符串传入，
     * 否则 ProcessBuilder 会把 "su -c screencap -p /path" 里的参数拆错，
     * 变成 su 收到多个参数而报错。
     */
    private boolean runScreencap(String outPath) {
        Process p = null;
        try {
            String cmd = "su -c \"screencap -p '" + outPath + "'\"";
            p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true).start();

            // 排空输出，避免缓冲区写满导致进程阻塞
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (br.readLine() != null) {
                // 丢弃（su 的报错信息）
            }
            br.close();

            if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "screencap 超时");
                p.destroy();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            Log.w(TAG, "screencap 执行失败: " + t);
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // =====================================================================
    //  后处理
    // =====================================================================

    /** 旋正 + 去透明通道。 */
    private Bitmap finish(Bitmap src) {
        Bitmap out = src;
        try {
            // screencap 的输出已经是当前显示方向的画面，
            // 但少数 ROM（尤其带虚拟显示设备时）会给未旋转的原始帧，
            // 这里只在「宽高与屏幕方向明显不符」时才纠正，避免误伤。
            int rotation = getRotation();
            boolean landscape = width > height;
            boolean needRotate = (rotation == 1 || rotation == 3) && !landscape;
            if (needRotate) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rotation == 1 ? 90 : 270);
                Bitmap r = Bitmap.createBitmap(out, 0, 0, out.getWidth(), out.getHeight(), m, true);
                if (r != out) {
                    out.recycle();
                    out = r;
                }
            }

            Bitmap opaque = toOpaque(out);
            if (opaque != out) {
                out.recycle();
                out = opaque;
            }
        } catch (Throwable t) {
            Log.w(TAG, "后处理失败（忽略）: " + t);
        }
        return out;
    }

    private int getRotation() {
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return 0;
            }
            return wm.getDefaultDisplay().getRotation();
        } catch (Throwable t) {
            return 0;
        }
    }

    private Bitmap toOpaque(Bitmap src) {
        try {
            if (src.getConfig() == Bitmap.Config.RGB_565) {
                return src;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.getConfig() == Bitmap.Config.HARDWARE) {
                // 极少数情况下 screencap 会解出 HARDWARE 位图，需软件拷贝
                Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, false);
                return copy != null ? copy : src;
            }
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                    Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(out);
            canvas.drawColor(0xFF000000);
            canvas.drawBitmap(src, 0f, 0f, null);
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    /** 释放（当前无长驻资源，仅为语义对称保留）。 */
    public void quit() {
        ready = false;
    }
}