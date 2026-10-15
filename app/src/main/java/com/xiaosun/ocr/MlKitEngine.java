package com.xiaosun.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ML Kit 文字识别引擎（**捆绑版**，替代原 rapid 档位）。
 *
 * ============================================================================
 * 一、为什么可以完全不依赖 Google Play 服务
 * ============================================================================
 * 本类用的是 **bundled（捆绑）** 版本的依赖：
 *     com.google.mlkit:text-recognition:16.0.1          ← 拉丁（英文/数字标点）
 *     com.google.mlkit:text-recognition-chinese:16.0.1  ← 中文（简繁，含 Han）
 * 这两者会传递引入 com.google.mlkit:text-recognition-bundled-common，
 * 后者 AAR 里实际装着**推理引擎本体**：
 *     jni/arm64-v8a/libmlkit_google_ocr_pipeline.so   (约 11MB)
 *     jni/armeabi-v7a/libmlkit_google_ocr_pipeline.so (约 6.8MB)
 * 而文本模型以 assets 形式打包：
 *     assets/mlkit-google-ocr-models/gocr/.../Latn_ctc/...  ← 拉丁
 *     assets/mlkit-google-ocr-models/gocr/.../Hani_ctc/...  ← 汉字
 *
 * ⚠️ 依赖树里会看到 com.google.android.gms:play-services-mlkit-text-recognition，
 *    名称里带 "gms" 容易让人误以为"依赖 Google Play 服务"。**事实并非如此**：
 *    这些 AAR 的 classes.jar 只有约 2.8KB（纯接口适配壳），
 *    **既不含任何模型、也不含 jni、更没有 <uses-library> 声明**。
 *    真正的模型与 .so 全在 bundled-common 里。
 *    所以运行期不需要安装/连接 Google Play 服务，也不需要联网下载模型。
 *    （对比 play-services-mlkit-text-recognition 单独使用的那种"瘦客户端"方案：
 *      模型靠 GMS 动态下发，无 GMS 设备上直接不可用 —— 本项目刻意不用那套。）
 *
 * ============================================================================
 * 二、为什么只保留英文 + 中文
 * ============================================================================
 * 按需求只做英文与中文，因此**只引入 latin + chinese 两个脚本包**，
 * 不引入 devanagari / japanese / korean 等，避免 APK 无谓膨胀。
 * （latin 包本身就同时带 Latn 模型，正好覆盖英文与数字标点。）
 *
 * ============================================================================
 * 三、输出契约：必须与 native 引擎**完全一致**
 * ============================================================================
 * 原 rapid 档位由 libnjocr.so 的 nativeProcessBitmap 产出 JSON。本类必须
 * 产出**逐字段一致**的结构，否则下游会静默错乱：
 *   · MainActivity 画检测框读 x / y / w / h
 *   · PluginFireReceiver 读 data[].text 与 x / y，并读顶层 status
 * 具体结构见下面 recognize() 的注释。
 *
 * ============================================================================
 * 四、线程安全
 * ============================================================================
 * ML Kit 的 TextRecognizer **不是线程安全的**，同一个实例不可并发 process()。
 * 本类的调用方 OCRService 已用 engineLock 串行化（识别与切档位共用），
 * 因此这里不再自己加锁，避免出现两把锁、徒增死锁风险。
 */
final class MlKitEngine {

    private static final String TAG = "XiaoSunOCR";

    /**
     * 单次识别的等待上限（毫秒）。
     *
     * 为什么给 60 秒这么宽松：Tasks.await 阻塞的是我们自己那一条请求线程
     * （NanoHTTPD 每请求一线程），不会影响其它请求；而调用方（HTTP 客户端、
     * 插件）都有自己的超时。这里长一点是为了**绝不在正常大图上误超时**。
     */
    private static final long TIMEOUT_MS = 60_000L;

    /**
     * 选用的识别器：中文（含 Han 与 Latn）。
     *
     * 【为什么中文识别器也能认英文】ML Kit 的中文模型包含 Han 与 Latn 两套
     * 识别网络，中英混排是它的常规场景，因此**不需要**在运行期轮流试两个识别器
     * （那会让耗时翻倍）。只用一个中文识别器即可覆盖"英文 + 中文"。
     */
    private TextRecognizer recognizer;

    /** 初始化失败原因（成功时为空串），供 /last 的诊断字段展示。 */
    private String initError = "";

    /**
     * 创建识别器。
     *
     * 【注意】建立识别器本身很快（不加载模型），真正耗时的模型加载发生在
     * 首次 process() 时，由 ML Kit 内部在后台完成。所以本方法可以在初始化
     * 阶段直接调用，不必再另开线程。
     *
     * 【两步策略】
     *   ① 首选**中文**识别器 —— 它的模型同时含 Han 与 Latn 两套网络，
     *      中英混排正是常规场景，因此**一个识别器即可覆盖"英文 + 中文"**，
     *      无需在每次识别时轮流试两个（那会让耗时直接翻倍）。
     *   ② 万一中文包不可用（依赖被改坏、ABI 不匹配等），**降级到拉丁**识别器，
     *      保证"至少英文还能用"，而不是整个档位直接报废。
     *
     * @return true 表示识别器创建成功
     */
    boolean init() {
        if (recognizer != null) {
            return true;
        }

        // ① 首选中文（Han + Latn，覆盖中英混排）
        try {
            recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
            initError = "";
            Log.i(TAG, "ML Kit 文字识别器已创建（捆绑版，中文+拉丁，无需 Play 服务）");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "中文识别器创建失败，尝试降级到拉丁（仅英文）: " + t);
        }

        // ② 降级：拉丁识别器（仅英文/数字标点）
        try {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            // 降级成功——不算失败，但要把"中文能力缺失"如实记下来，
            // 否则用户识别中文会一直得到空结果却查不到原因。
            initError = "中文识别器不可用，已降级为拉丁（仅英文）";
            Log.w(TAG, initError);
            return true;
        } catch (Throwable t2) {
            // 常见原因：依赖未正确打包、ABI 不匹配导致 .so 加载失败
            initError = "ML Kit 初始化失败: " + t2;
            recognizer = null;
            Log.e(TAG, initError);
            return false;
        }
    }

    /** @return 初始化失败原因；成功或尚未初始化时为空串。 */
    String getInitError() {
        return initError == null ? "" : initError;
    }

    /**
     * 是否为「运行时缺模型」类错误。
     *
     * 【为什么要判断】如果将来有人误把 bundled 依赖换成非 bundled 的瘦客户端，
     * 运行时才会暴露"模型未下载"。此时给一句能直接指明原因的话，
     * 比抛一个通用异常有用得多。
     */
    private static boolean isModelMissing(Throwable t) {
        String m = t == null ? null : t.getMessage();
        if (m == null) {
            return false;
        }
        String s = m.toLowerCase(java.util.Locale.ROOT);
        return s.contains("not available") || s.contains("not downloaded")
                || s.contains("model") && s.contains("missing");
    }

    /**
     * 识别一张位图，返回与 native 引擎**格式一致**的 JSON 文本。
     *
     * 输出结构（与 nativeProcessBitmap 对齐）：
     * <pre>
     * {
     *   "status": 200,
     *   "data": [
     *     {"text": "Hello", "x": 10, "y": 20, "w": 100, "h": 24, "conf": 0.95},
     *     ...
     *   ],
     *   "tier": "rapid",
     *   "time": 123
     * }
     * </pre>
     * - type == 1（纯文本）：每项只给 text，**不给坐标**（与 native 的 type=1 一致）
     * - type >= 2：附带 x / y / w / h（MainActivity 画框、插件取坐标都靠它）
     *
     * @return JSON 文本；失败时返回带非 200 status 的 JSON（绝不返回 null）
     */
    String recognize(Bitmap bitmap, int type) {
        if (recognizer == null) {
            return errorJson(503, initError.isEmpty()
                    ? "ML Kit 未初始化" : initError);
        }
        long t0 = System.currentTimeMillis();
        try {
            Text result = Tasks.await(
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    TIMEOUT_MS, TimeUnit.MILLISECONDS);

            JSONArray data = new JSONArray();
            if (result != null) {
                List<Text.TextBlock> blocks = result.getTextBlocks();
                if (blocks != null) {
                    for (Text.TextBlock block : blocks) {
                        if (block == null) {
                            continue;
                        }
                        List<Text.Line> lines = block.getLines();
                        if (lines == null) {
                            continue;
                        }
                        for (Text.Line line : lines) {
                            if (line == null) {
                                continue;
                            }
                            String text = line.getText();
                            if (text == null || text.trim().isEmpty()) {
                                continue;
                            }
                            data.put(lineToJson(line, text, type));
                        }
                    }
                }
            }

            JSONObject out = new JSONObject();
            out.put("status", 200);
            out.put("data", data);
            out.put("tier", OCRService.tierName(OCRService.TIER_RAPID));
            out.put("time", System.currentTimeMillis() - t0);
            return out.toString();

        } catch (Throwable t) {
            Log.e(TAG, "ML Kit 识别失败: " + t);
            if (isModelMissing(t)) {
                return errorJson(503, "ML Kit 模型不可用（请确认使用的是 bundled 版本依赖）: " + t);
            }
            return errorJson(500, "ML Kit 识别失败: " + t);
        }
    }

    /**
     * 把一行识别结果转成 JSON 项。
     *
     * 用「行」而不是「块」作为粒度：PP-OCR 的 native 输出就是按文本行给的，
     * 保持同一粒度，行数、坐标、文本拼接（%xsocr_text）才会与切档位前一致。
     */
    private static JSONObject lineToJson(Text.Line line, String text, int type)
            throws org.json.JSONException {
        JSONObject item = new JSONObject();
        item.put("text", text);

        if (type >= 2) {
            // 坐标统一成 native 的 x/y/w/h 形式。
            // ML Kit 给的是 Rect（left/top/right/bottom），需要转一下。
            Rect r = line.getBoundingBox();
            if (r != null) {
                item.put("x", r.left);
                item.put("y", r.top);
                item.put("w", r.width());
                item.put("h", r.height());
            } else {
                // 没有包围盒时给 -1，与 native 的"坐标不可用"约定一致；
                // 下游（MainActivity 画框）见到 <0 会自行跳过，不会画歪。
                item.put("x", -1);
                item.put("y", -1);
                item.put("w", -1);
                item.put("h", -1);
            }
        }

        // conf 是可选增强字段：native 侧没有，但多给不破坏既有解析。
        // 用 try 包住是因为个别行可能没有置信度（返回 null）。
        try {
            Float conf = line.getConfidence();
            if (conf != null) {
                item.put("conf", (double) conf);
            }
        } catch (Throwable ignored) {
        }
        return item;
    }

    /** 构造错误响应 JSON（保持与 native 一致的 {status,data} 结构）。 */
    private static String errorJson(int status, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", status);
            o.put("data", "Error: " + msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"status\":" + status + ",\"data\":\"Error: ML Kit\"}";
        }
    }

    /** 释放识别器。服务销毁时调用，避免泄漏 native 侧资源。 */
    void close() {
        try {
            if (recognizer != null) {
                recognizer.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "关闭 ML Kit 识别器失败: " + t);
        } finally {
            recognizer = null;
        }
    }
}