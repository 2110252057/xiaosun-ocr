package com.xiaosun.ocr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 本地 HTTP 接口服务。
 *
 * 监听 127.0.0.1:{@link OCRService#HTTP_PORT}，提供 OCR 识别能力给 App 内部与外部脚本/自动化工具。
 *
 * 路由：
 *   POST /                          识别（path 可选，不传则自动截屏）
 *   GET  /last                      读取上一次识别结果
 *   GET  /last?format=text          同上，返回纯文本
 *   GET  /ocr?format=text           一步完成「取图 → 识别 → 返回文本」
 *
 * 参数支持 JSON Body / 表单 / Query 三种形式：path、type、tier、action
 *   type: 1=纯文本  2=Json数组(含坐标)  3=详细Json(含矩形)
 *   响应体直接使用 native 返回的 JSON 文本
 */
public class OcrHttpServer extends NanoHTTPD {

    private static final String TAG = "XiaoSunOCR";

    private final OCRService service;

    public OcrHttpServer(OCRService service) {
        // 端口统一引用 OCRService.HTTP_PORT。
        // 原来这里又硬写了一遍 1666，一旦改端口就会出现
        // "通知栏写着 A、实际监听 B" 的分裂，且 OcrHttpServer 与 MainActivity 各写各的。
        super("127.0.0.1", OCRService.HTTP_PORT);
        this.service = service;
    }

    @Override
    public Response serve(IHTTPSession session) {
        // 持锁期间保证 CPU 不睡，避免息屏时推理被挂起导致调用方超时
        service.acquireCpuLock();
        try {
            return handle(session);
        } finally {
            service.releaseCpuLock();
        }
    }

    /**
     * body 内 status 与真实 HTTP 状态码的映射表。
     *
     * 【修复⑨】原来 json() / err() 无论内容如何都回 Response.Status.OK，
     * 真正的错误码只写在 body 的 "status" 字段里。于是：
     *   · curl -f / curl -sS 不会报错，脚本必须自己解析 JSON 才知道失败
     *   · 多数 HTTP 组件（Auto.js、Tasker「HTTP 请求」、OkHttp 拦截器）
     *     默认只看状态码，会把「图片不存在」「档位非法」全都当成功
     * 现在两者一致：body 里写什么码，HTTP 状态码就是什么。
     * 注意 body 里的 "status" 字段**必须保留** —— 插件路径
     * （PluginFireReceiver）读的就是它，删掉会直接打断既有宏。
     */
    private static final Map<Integer, Response.Status> STATUS_MAP = new HashMap<>();

    static {
        STATUS_MAP.put(200, Response.Status.OK);
        STATUS_MAP.put(400, Response.Status.BAD_REQUEST);
        STATUS_MAP.put(403, Response.Status.FORBIDDEN);
        STATUS_MAP.put(404, Response.Status.NOT_FOUND);
        STATUS_MAP.put(405, Response.Status.METHOD_NOT_ALLOWED);
        STATUS_MAP.put(500, Response.Status.INTERNAL_ERROR);
        STATUS_MAP.put(503, Response.Status.SERVICE_UNAVAILABLE);
    }

    /**
     * 规范化请求路径：只削掉结尾多余的 '/'（根路径 "/" 本身保留）。
     *
     * 这样 /ocr 与 /ocr/ 会走同一分支，不会因为调用方多打一个斜杠就 404。
     */
    private static String normalizePath(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        if (uri.length() > 1 && uri.endsWith("/")) {
            return uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    private Response handle(IHTTPSession session) {
        try {
            // ---------- 兜底路由：读取「上一次识别结果」 ----------
            // 给 MacroDroid 的「HTTP 请求」动作用。
            // 万一宿主的变量回传不生效，宏可以改成：
            //   1) 调插件触发识别
            //   2) HTTP GET http://127.0.0.1:{HTTP_PORT}/last   （端口 = OCRService.HTTP_PORT，此处仅示意）
            //   3) 从响应里取文字
            // 【修复⑩：路由精确匹配】原来用 uri.startsWith("/last") /
            // startsWith("/ocr") 做前缀匹配，于是 /lastxxx、/ocrfoo、/ocr123
            // 全都会被误命中并返回 200，调用方把路径写错了却完全看不出来。
            // 现在先规范化（去掉结尾多余 '/'），再按白名单精确比较。
            String uri = normalizePath(session.getUri());
            if ("/last".equals(uri)) {
                // 支持 /last?format=text 直接返回纯文本，
                // 方便 MacroDroid 的「HTTP 请求」动作把响应体整个塞进变量，
                // 不用再做 JSON 解析。
                //
                // ★ 注意：NanoHTTPD 的 getUri() **不含 query string**，
                //   所以不能在 uri 上找 "format=text"，必须读 session.getParms()。
                //
                // ★ 注意（修复⑨的例外）：/last 一律保持 HTTP 200。
                //   原因：Http.ping() 就是靠 GET /last 是否返回 200 来判断
                //   "服务是否在监听"。而"还没有任何识别记录"时 body 里是
                //   status 404 —— 如果状态码也跟着变成 404，ping 就会把
                //   "服务好好的、只是还没识别过" 误判成 "服务未就绪"，
                //   插件将永远等不到就绪。所以这里用 jsonOk()，
                //   真正的状态仍然如实写在 body 的 status 字段里。
                if (isFormatText(session)) {
                    return newFixedLengthResponse(Response.Status.OK,
                            "text/plain;charset=utf-8", readLastText());
                }
                return jsonOk(readLastResult());
            }

            // ---------- 便捷路由：一步完成「截屏 + 识别 + 返回文本」 ----------
            // 专为 MacroDroid / Tasker 的「HTTP 请求」动作用。
            //
            //   GET http://127.0.0.1:{HTTP_PORT}/ocr?format=text
            //     → 自动截屏识别，直接返回纯文本
            //
            //   GET http://127.0.0.1:{HTTP_PORT}/ocr?format=text&tier=rapid
            //     → 指定档位
            //
            //   GET http://127.0.0.1:{HTTP_PORT}/ocr?format=text&path=/sdcard/a.png
            //     → 识别指定图片
            //
            // （{HTTP_PORT} 代指 OCRService.HTTP_PORT，此处仅为文档示意）
            // 这样宏里只要一个 HTTP 动作，不用装插件、不用管变量回传。
            if ("/ocr".equals(uri)) {
                return handleOcrShortcut(session);
            }
            // ---------- 只截屏、不识别 ----------
            // 供「区域识别」用：界面需要先看到整屏、才能让用户框选一块。
            // 若走 POST / 拿图，就白白多识别了一次整屏（大图 + 大模型，几百毫秒起）。
            // 对插件也有用：想自己裁一块再调识别时，先拿一张原图更灵活。
            if ("/shot".equals(uri)) {
                // 支持 ?capture=accessibility|root|projection 指定截屏方式；
                // 不传则维持原来的「自动挑一条能用的」。
                Map<String, String> q = session.getParms();
                String capMode = q == null ? null : q.get("capture");
                return handleShotOnly(capMode);
            }

            // 【修复⑩：404 兜底】精确匹配后，其余任何路径都不是合法路由。
            // 原来没有这一段，未知路径会掉进下面的 POST 分支：
            // GET /hello 会拿到 405（而不是 404），GET /lastxxx 会拿到 200，
            // 调用方拼错路径时完全看不出问题。现在明确回 404。
            if (!"/".equals(uri)) {
                return json("{\"status\": 404, \"data\": \"Error: no such route '" + uri
                        + "', available: POST /, GET /last, GET /ocr, GET /shot\"}");
            }

            if (session.getMethod() != Method.POST) {
                return json("{\"status\": 405, \"data\": \"Error: method not allowed\"}");
            }

            String path = null;
            String typeStr = null;
            String tierStr = null;
            /**
             * 区域识别范围，格式 "left,top,right,bottom"，取值 0~1 的归一化比例。
             *
             * 【为什么用比例而不是像素】
             * 调用方（界面）手里的预览图是降采样过的，与截屏原图尺寸不同；
             * 若传像素，就得先知道原图尺寸，多一轮往返。传比例则与分辨率无关，
             * 服务端拿到图后自己乘真实宽高，怎么缩放都不会偏。
             */
            String regionStr = null;
            /** 仅切换档位、不做识别的标识（action=setTier）。 */
            String actionStr = null;
            /**
             * 截屏方式：auto / accessibility / root / projection。
             *
             * <p>不传或传 auto = 老的按序回退；传具体值就只用那一条，
             * 该方式不可用时明确报错，**不跨方式回退**。
             * 理由见 {@code OCRService.captureByMode} 的注释。
             */
            String captureStr = null;

            // 1) 必须先解析 body：
            //    - application/x-www-form-urlencoded -> NanoHTTPD 会把字段并入 session.getParms()
            //    - multipart/form-data               -> 字段进入 files
            //    - 其它 (JSON / raw)                 -> 原始文本进入 files 的 "postData"
            Map<String, String> files = new HashMap<>();
            try {
                session.parseBody(files);
            } catch (Exception e) {
                Log.e(TAG, "[Body] 读取失败: " + e.getMessage());
            }

            // 2) 再读参数：query string 与 urlencoded 表单都在这里
            //    （一定要在 parseBody 之后读，否则表单参数还没被写进来）
            Map<String, String> parms = session.getParms();
            if (parms != null) {
                path = parms.get("path");
                typeStr = parms.get("type");
                tierStr = parms.get("tier");
                regionStr = parms.get("region");
                captureStr = parms.get("capture");
            }
            // 3) multipart 表单字段
            if (files.containsKey("path")) path = files.get("path");
            if (files.containsKey("type")) typeStr = files.get("type");
            if (files.containsKey("tier")) tierStr = files.get("tier");
            if (files.containsKey("region")) regionStr = files.get("region");
            if (files.containsKey("capture")) captureStr = files.get("capture");

            // 4) 原始 body：先按 JSON 解，再按表单解
            String postData = files.get("postData");
            if (postData != null && postData.trim().length() > 0) {
                String trimmed = postData.trim();
                Map<String, String> parsed = parseJson(postData);
                if (parsed == null) {
                    Log.i(TAG, "[Body] 非 JSON，尝试按表单解析, 长度=" + postData.length());
                    parsed = parseForm(postData);
                } else {
                    Log.i(TAG, "[Body] JSON 解析成功, 长度=" + trimmed.length());
                }
                if (parsed != null) {
                    if (parsed.containsKey("path")) path = parsed.get("path");
                    if (parsed.containsKey("type")) typeStr = parsed.get("type");
                    if (parsed.containsKey("tier")) tierStr = parsed.get("tier");
                    if (parsed.containsKey("region")) regionStr = parsed.get("region");
                    if (parsed.containsKey("capture")) captureStr = parsed.get("capture");
                    // 必须一起取 action，否则 action=setTier 不生效，
                    // 会继续走到「没传 path 就截屏」的分支，导致切档位请求被截屏失败打断。
                    if (parsed.containsKey("action")) actionStr = parsed.get("action");
                }
            }

            // action 也可能出现在 query / 表单里
            if (parms != null && parms.containsKey("action")) actionStr = parms.get("action");
            if (files.containsKey("action")) actionStr = files.get("action");

            int type = 1;
            if (typeStr != null && typeStr.trim().length() > 0) {
                try {
                    type = Integer.parseInt(typeStr.trim());
                } catch (NumberFormatException ignored) {
                    type = 1;
                }
            }

            // ---- 档位（可选）----
            // 传了就在本次识别前切过去。
            // 档位是全局状态，切一次持续生效——这正是"外部调用也跟着切换"的实现方式。
            if (tierStr != null && tierStr.trim().length() > 0) {
                int want = OCRService.tierFromName(tierStr);
                if (want < 0) {
                    return json("{\"status\": 400, \"data\": \"Error: invalid tier '"
                            + tierStr + "', expected 'tiny' or 'small'\"}");
                }
                if (want != OCRService.currentTier || service.nativeGetTier() != want) {
                    Log.i(TAG, "[Tier] 请求切换档位 -> " + OCRService.tierName(want));
                    if (!service.switchTier(want)) {
                        return json("{\"status\": 500, \"data\": \"Error: failed to switch tier to "
                                + OCRService.tierName(want) + "\"}");
                    }
                }
            }

            // action=setTier：只切档位，不识别。
            // 给「只想切档位、不想顺带识别一张图」的调用方用。
            if ("setTier".equalsIgnoreCase(actionStr)) {
                return json("{\"status\": 200, \"data\": \"tier="
                        + OCRService.tierName(OCRService.currentTier) + "\"}");
            }

            // ① 模型还没加载好时明确回 503，而不是让调用方拿到一个
            // 莫名其妙的错误（例如"截屏失败"，其实根因是模型没就绪）。
            // 注意：仍走 json()，body 里的 status 字段保留，插件路径不受影响。
            if (!OCRService.modelReady) {
                String why = OCRService.modelError == null || OCRService.modelError.isEmpty()
                        ? "模型仍在加载中，请稍候重试" : OCRService.modelError;
                return json("{\"status\": 503, \"data\": \"Error: " + why + "\"}");
            }

            Bitmap bitmap;
            if (path != null && path.length() > 0) {
                File file = new File(path);
                if (!file.exists()) {
                    return json("{\"status\": 404, \"data\": \"Error: File not found at " + path + "\"}");
                }
                bitmap = decodeFileSafe(path);
                if (bitmap == null) {
                    return json("{\"status\": 500, \"data\": \"Error: Failed to decode image\"}");
                }
                Log.i(TAG, "图片加载成功: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            } else {
                // 不传图片：自动截取当前屏幕后识别
                Log.i(TAG, "[Screen] 未传图片，开始截屏识别"
                        + (captureStr == null ? "" : "，方式=" + captureStr));
                // 指定了方式就只查那一条；否则维持「任一条可用即可」的老判断。
                // 不能一律用 isScreenCaptureReady()：那样用户选了 root
                // 而只有无障碍可用时会被放行，最后截出另一条路的结果。
                boolean modeSpecified = captureStr != null
                        && captureStr.length() > 0
                        && !OCRService.CAPTURE_AUTO.equals(captureStr);
                boolean captureReady = modeSpecified
                        ? service.isCaptureModeReady(captureStr)
                        : service.isScreenCaptureReady();
                if (!captureReady) {
                    String why = modeSpecified
                            ? "requested capture mode '" + captureStr
                                    + "' is not ready. Please enable it in Grace first."
                            : "Device has no root; open the app and tap "
                                    + "\\\"onScreenCapture\\\" once to authorize MediaProjection.";
                    return json("{\"status\": 403, \"data\": \"Error: Screen capture not available. "
                            + why + "\"}");
                }
                try {
                    bitmap = service.captureByMode(captureStr);
                } catch (Throwable t) {
                    Log.e(TAG, "[Screen] 截屏失败: " + t.getMessage());
                    return json("{\"status\": 500, \"data\": \"Error: Screen capture failed: "
                            + (t.getMessage() == null ? "unknown" : t.getMessage()) + "\"}");
                }
                if (bitmap == null) {
                    return json("{\"status\": 500, \"data\": \"Error: Screen capture returned null\"}");
                }
                Log.i(TAG, "[Screen] 截屏成功: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                // 把截屏画面存一份，供界面显示预览（否则界面画的还是上一次选的图，框必然对不上）
                try {
                    File shot = new File(service.getCacheDir(), "ocr_capture.png");
                    FileOutputStream fos = new FileOutputStream(shot);
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                    fos.flush();
                    fos.close();
                    OCRService.lastCapturePath = shot.getAbsolutePath();
                } catch (Exception e) {
                    Log.w(TAG, "[Screen] 缓存截屏失败: " + e.getMessage());
                    OCRService.lastCapturePath = null;
                }
            }

            // 记录本次识别的实际像素尺寸。
            // 返回的 x/y/w/h 都基于这张位图，界面必须凭这两个值做坐标换算。
            OCRService.lastOcrImageWidth = bitmap.getWidth();
            OCRService.lastOcrImageHeight = bitmap.getHeight();

            // ---------- 区域识别：只把框出来的那一块送去引擎 ----------
            // 【顺序很关键】必须在记录 lastOcrImageWidth 之后再裁剪，
            // 且全图尺寸要单独存一份。原因见 OCRService.lastFullImageWidth 的注释：
            // 引擎返回的坐标是相对「裁剪块」的，而界面与插件要的是全屏坐标，
            // 少了偏移量这一层，框会整体往左上偏。
            OCRService.lastFullImageWidth = bitmap.getWidth();
            OCRService.lastFullImageHeight = bitmap.getHeight();
            OCRService.lastRegionLeft = 0;
            OCRService.lastRegionTop = 0;
            String regionError = null;
            float[] frac = parseRegion(regionStr);
            if (frac != null) {
                String[] err = new String[1];
                Bitmap cropped = cropByRegion(bitmap, frac, err);
                if (cropped == null) {
                    regionError = err[0];
                } else {
                    bitmap = cropped;
                }
            }
            if (regionError != null) {
                // 参数本身不合法时按 400 明确回报，而不是静默退回全屏识别 ——
                // 后者会让调用方以为「区域生效了」，实际拿到的是满屏结果，
                // 坐标还带着不该有的偏移，排查起来极费劲。
                bitmap.recycle();
                return json("{\"status\": 400, \"data\": \"Error: " + regionError + "\"}");
            }

            Log.i(TAG, "[OCR] type=" + type + ", size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + ", tier=" + OCRService.tierName(OCRService.currentTier));
            String result;
            try {
                // 统一入口：内部按当前档位分流到 native 或 ML Kit（rapid）
                result = service.processBitmap(bitmap, type);
            } finally {
                bitmap.recycle();
            }

            if (result == null) {
                result = "{\"status\": 500, \"data\": \"Error: OCR failed\"}";
            }
            // 区域识别：把坐标从「裁剪块」平移回「全屏」。
            // 放在这里而不是在 processBitmap 内部做，是因为换算本来属于
            // 「这次请求用了多大一块图」，是 HTTP 层的事 —— 引擎只管自己那张图。
            // type=1 是纯文本、没有坐标，shiftCoords 内部会因 data 不是数组而直接返回。
            result = shiftCoords(result, OCRService.lastRegionLeft, OCRService.lastRegionTop);
            return json(result);

        } catch (Exception e) {
            Log.e(TAG, "处理请求异常: " + e.getMessage());
            String msg = e.getMessage() == null ? "unknown" : e.getMessage();
            return json("{\"status\": 500, \"data\": \"Error: " + msg + "\"}");
        }
    }

    /**
     * 解析 JSON 请求体，失败返回 null。
     *
     * <p>【为什么用一个字段名清单，而不是逐个 if】
     * 原来这里是四个 `if (obj.has("xxx"))` 抄四遍。2.11 加 `region` 参数时
     * 改了三处（query / multipart / 表单）却**漏了这一处**，症状极隐蔽：
     * 界面明明传了 region，请求也 200，结果却是整屏识别 ——
     * 因为 JSON body 里的 region 在这一步就被丢掉了，后面所有逻辑都没见过它。
     * 更麻烦的是此时返回体里连 fullWidth/regionLeft 都没有，只能靠对比返回字段才发现。
     * 现在改成遍历清单，加字段只需在清单里添一个名字。
     */
    private Map<String, String> parseJson(String postData) {
        try {
            JsonElement el = JsonParser.parseString(postData);
            if (!el.isJsonObject()) {
                return null;
            }
            JsonObject obj = el.getAsJsonObject();
            Map<String, String> map = new HashMap<>();
            // ★ 这份清单必须与 handle() 里的取值保持一致，
            //   少一个名字就等于那个参数在 JSON 传参下静默失效。
            for (String k : new String[]{"path", "type", "tier", "action", "region", "capture"}) {
                if (obj.has(k) && !obj.get(k).isJsonNull()) {
                    map.put(k, obj.get(k).getAsString());
                }
            }
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 application/x-www-form-urlencoded 请求体，失败返回 null。 */
    private Map<String, String> parseForm(String postData) {
        if (postData == null || postData.length() == 0) {
            return null;
        }
        try {
            Map<String, String> map = new HashMap<>();
            for (String pair : postData.split("&")) {
                if (pair.length() == 0) {
                    continue;
                }
                int idx = pair.indexOf('=');
                if (idx < 0) {
                    continue;
                }
                String k = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                String v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                map.put(k, v);
            }
            return map;
        } catch (Exception e) {
            Log.e(TAG, "[Form] 解析失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 按归一化比例裁剪位图，并把「裁剪块 → 全屏」的换算参数写进静态字段。
     *
     * <p>【为什么抽成方法】POST / 与 GET /ocr 两条路由都要做同一件事。
     * 分开写两遍的话，日后调边界规则必然漏改一处 ——
     * 本项目已经吃过「dp() 合并时漏掉 wideTop 调用点」的教训。
     *
     * <p>【副作用】会 recycle 传入的 {@code src}。调用方拿到返回值后
     * 不要再碰旧引用，也不要再 recycle 它一次。
     *
     * @param src  全屏位图（所有权转移给本方法）
     * @param frac {l, t, r, b} 归一化比例
     * @param err  长度 ≥1 的数组，失败原因写入 err[0]
     * @return 裁剪后的位图；失败返回 null（此时 src 未被 recycle，仍归调用方）
     */
    private Bitmap cropByRegion(Bitmap src, float[] frac, String[] err) {
        int fullW = src.getWidth();
        int fullH = src.getHeight();
        int l = Math.round(frac[0] * fullW);
        int t = Math.round(frac[1] * fullH);
        int r = Math.round(frac[2] * fullW);
        int b = Math.round(frac[3] * fullH);
        // 夹在图的范围内。不做夹取的话，调用方传 1.0000001 之类的浮点误差
        // 就会让 createBitmap 抛 IllegalArgumentException。
        l = Math.max(0, Math.min(fullW - 1, l));
        t = Math.max(0, Math.min(fullH - 1, t));
        r = Math.max(l + 1, Math.min(fullW, r));
        b = Math.max(t + 1, Math.min(fullH, b));
        // 宽或高不足 8 像素时明确报错：这么小的块裁出来必然识别为空，
        // 静默返回空结果会让调用方以为「图里就是没字」。
        if (r - l < 8 || b - t < 8) {
            err[0] = "region too small after scaling: " + (r - l) + "x" + (b - t);
            return null;
        }
        Bitmap cropped;
        try {
            cropped = Bitmap.createBitmap(src, l, t, r - l, b - t);
        } catch (Throwable th) {
            err[0] = "crop failed: " + th.getMessage();
            return null;
        }
        if (cropped == null || cropped == src) {
            // createBitmap 在「裁剪范围等于整图」时可能直接返回原对象，
            // 此时不能再 recycle 它，否则就把要用的图弄没了。
            err[0] = "crop returned same or null bitmap";
            return null;
        }
        // 全图尺寸在调用方已经存过，这里只更新「引擎视角」与偏移量。
        OCRService.lastOcrImageWidth = cropped.getWidth();
        OCRService.lastOcrImageHeight = cropped.getHeight();
        OCRService.lastRegionLeft = l;
        OCRService.lastRegionTop = t;
        Log.i(TAG, "[Region] 裁剪 " + l + "," + t + " " + fullW + "x" + fullH
                + " -> " + cropped.getWidth() + "x" + cropped.getHeight());
        // 原图若已落盘给预览用就不必再留内存副本，立刻释放。
        src.recycle();
        return cropped;
    }

    /**
     * 解析 {@code region} 参数为 0~1 的比例值 {left, top, right, bottom}。
     *
     * <p>同时接受两种写法：
     * <ul>
     *   <li>{@code 0.1,0.2,0.8,0.9} —— 比例（界面走这条）</li>
     *   <li>{@code 10,20,80,90}    —— 百分数（手写插件时更顺手）</li>
     * </ul>
     * 判据是「四个数里有任何一个大于 1」，此时整体按百分数除以 100。
     *
     * @param raw 原始字符串；null / 空表示不做区域识别
     * @return 比例数组；为空或格式非法时返回 null（等价于「不指定区域」）
     */
    private float[] parseRegion(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String[] parts = s.split(",");
        if (parts.length != 4) {
            Log.w(TAG, "[Region] 段数不是 4，忽略: " + s);
            return null;
        }
        float[] v = new float[4];
        try {
            for (int i = 0; i < 4; i++) {
                v[i] = Float.parseFloat(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "[Region] 存在非数字，忽略: " + s);
            return null;
        }
        boolean percent = false;
        for (float f : v) {
            if (f > 1.0f) {
                percent = true;
                break;
            }
        }
        for (int i = 0; i < 4; i++) {
            if (percent) {
                v[i] = v[i] / 100f;
            }
            if (v[i] < 0f) v[i] = 0f;
            if (v[i] > 1f) v[i] = 1f;
        }
        if (v[2] <= v[0] || v[3] <= v[1]) {
            Log.w(TAG, "[Region] right/bottom 不大于 left/top，忽略: " + s);
            return null;
        }
        return v;
    }

    /**
     * 把识别结果里的坐标从「裁剪块坐标系」平移成「全屏坐标系」。
     *
     * <p>【为什么必须做这一步】区域识别时送进引擎的只是框内那一块，
     * 引擎不知道自己在整屏的哪个位置，返回的 x/y 都相对裁剪块左上角。
     * 若原样返回，插件会把「屏幕右下角的字」报成「屏幕左上角附近的字」，
     * 差一个选区偏移，而且肉眼很难发现。
     *
     * <p>【为什么不动 w/h】裁剪是 1:1 取原图像素、没有缩放，
     * 所以宽高无需换算。需要缩放的只有「界面把全屏坐标画到降采样预览图上」
     * 那一步，由界面根据 fullWidth/Height 自行完成。
     *
     * @param result 引擎返回的 JSON 文本
     * @param dx     选区左上角在全屏图里的 x
     * @param dy     选区左上角在全屏图里的 y
     * @return 改写后的 JSON（必定含 fullWidth/fullHeight/regionLeft/regionTop）；
     *         解析失败时原样返回
     */
    private String shiftCoords(String result, int dx, int dy) {
        if (result == null) {
            return result;
        }
        // ★ 不能因为「偏移为 0」就提前返回。
        // 2.12 首次真机验证就栽在这里：偏移为 0 时直接 return，于是
        // fullWidth / fullHeight / regionLeft / regionTop 四个字段也跟着没写 ——
        // 而调用方正是靠这四个字段判断「这次请求到底有没有做区域裁剪」。
        // 现在无论有没有偏移，都先把换算依据写进去，再决定要不要平移坐标。
        try {
            JsonElement el = JsonParser.parseString(result);
            if (el == null || !el.isJsonObject()) {
                return result;
            }
            JsonObject obj = el.getAsJsonObject();
            JsonElement dataEl = obj.get("data");
            if (dataEl == null || !dataEl.isJsonArray()) {
                return result;
            }
            // 先把「画布多大、选区在哪」写进去：这是调用方判断区域是否生效的唯一依据。
            obj.addProperty("fullWidth", OCRService.lastFullImageWidth);
            obj.addProperty("fullHeight", OCRService.lastFullImageHeight);
            obj.addProperty("regionLeft", dx);
            obj.addProperty("regionTop", dy);
            if (dx == 0 && dy == 0) {
                // 整屏识别：坐标本来就是全屏坐标，不必平移，但上面的字段要留下。
                return obj.toString();
            }
            for (JsonElement item : dataEl.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject o = item.getAsJsonObject();
                // x/y 形式：native type=2 与 ML Kit 都用这套字段。
                // 只平移「非负」的值：引擎用 -1 表示「这一项没有坐标」，
                // 加上偏移会把它变成一个看似合法的正数。
                if (o.has("x") && o.get("x").isJsonPrimitive()) {
                    int x = o.get("x").getAsInt();
                    if (x >= 0) {
                        o.addProperty("x", x + dx);
                    }
                }
                if (o.has("y") && o.get("y").isJsonPrimitive()) {
                    int y = o.get("y").getAsInt();
                    if (y >= 0) {
                        o.addProperty("y", y + dy);
                    }
                }
                // left/top/right/bottom 形式（部分档位输出）
                if (o.has("left") && o.get("left").isJsonPrimitive()) {
                    o.addProperty("left", o.get("left").getAsInt() + dx);
                }
                if (o.has("top") && o.get("top").isJsonPrimitive()) {
                    o.addProperty("top", o.get("top").getAsInt() + dy);
                }
                if (o.has("right") && o.get("right").isJsonPrimitive()) {
                    o.addProperty("right", o.get("right").getAsInt() + dx);
                }
                if (o.has("bottom") && o.get("bottom").isJsonPrimitive()) {
                    o.addProperty("bottom", o.get("bottom").getAsInt() + dy);
                }
                // type=3 的多边形点集
                JsonElement pts = o.get("points");
                if (pts != null && pts.isJsonArray()) {
                    shiftPoints(pts.getAsJsonArray(), dx, dy);
                }
            }
            return obj.toString();
        } catch (Throwable t) {
            // 改写失败绝不能影响识别结果本身：宁可退回未平移的坐标 + 一条日志，
            // 也好过让一次本来成功的识别变成 500。
            Log.w(TAG, "[Region] 坐标换算失败，原样返回: " + t.getMessage());
            return result;
        }
    }

    /** 平移多边形点集，兼容扁平 [{@code x1,y1,x2,y2}] 与嵌套 [{@code [x,y]}] 两种形式。 */
    private void shiftPoints(JsonArray arr, int dx, int dy) {
        if (arr.size() == 0) {
            return;
        }
        if (arr.get(0).isJsonPrimitive()) {
            // 扁平形式：偶数下标是 x，奇数下标是 y
            for (int i = 0; i < arr.size(); i++) {
                JsonElement e = arr.get(i);
                if (!e.isJsonPrimitive()) {
                    continue;
                }
                arr.set(i, new com.google.gson.JsonPrimitive(
                        e.getAsInt() + ((i % 2 == 0) ? dx : dy)));
            }
            return;
        }
        // 嵌套形式 [[x,y],[x,y]]
        for (JsonElement e : arr) {
            if (!e.isJsonArray()) {
                continue;
            }
            JsonArray p = e.getAsJsonArray();
            if (p.size() >= 2 && p.get(0).isJsonPrimitive() && p.get(1).isJsonPrimitive()) {
                p.set(0, new com.google.gson.JsonPrimitive(p.get(0).getAsInt() + dx));
                p.set(1, new com.google.gson.JsonPrimitive(p.get(1).getAsInt() + dy));
            }
        }
    }

    /**
     * 安全解码图片：先探测尺寸，过大则按 2 的幂降采样。
     *
     * 直接 decodeFile 一张 4K 图会分配 4000×3000×4 ≈ 48MB，
     * 低内存机型容易 OOM 崩掉整个服务。
     *
     * 采样上限取 4096，与引擎内部的 max_side_len 对齐：
     * 超过它引擎自己也只会缩到 4096，降采样不损失有效信息。
     */
    private static final int MAX_DECODE_SIDE = 4096;

    private Bitmap decodeFileSafe(String path) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                return null;
            }

            int sample = 1;
            int longer = Math.max(probe.outWidth, probe.outHeight);
            while (longer / sample > MAX_DECODE_SIDE) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(path, opts);
            if (bmp != null && sample > 1) {
                Log.i(TAG, "[Decode] 原图 " + probe.outWidth + "x" + probe.outHeight
                        + " 降采样 1/" + sample + " -> " + bmp.getWidth() + "x" + bmp.getHeight());
            }
            return bmp;
        } catch (Throwable t) {
            Log.e(TAG, "[Decode] 解码失败: " + t.getMessage());
            return null;
        }
    }

    /**
     * 构造 JSON 响应。
     *
     * 【修复⑨】状态码不再恒为 200：从 body 的 "status" 字段里取出真实码，
     * 映射成对应的 HTTP 状态码。取不到（或不是数字）时按 200 处理，
     * 保证 native 直接返回的识别结果 JSON（status 可能就是 200）行为不变。
     *
     * 注意：body **原样返回**，不做任何改写 —— 插件路径读的就是 body 里的 status。
     */
    private Response json(String body) {
        return newFixedLengthResponse(statusOf(body), "application/json;charset=utf-8", body);
    }

    /**
     * 构造"HTTP 层面一定成功"的 JSON 响应，body 里的 status 原样保留。
     *
     * 目前只给 /last 用：它是**探测型**接口，只要 HTTP 通了就算通，
     * "有没有历史记录"属于业务状态，写在 body 里即可。
     */
    private Response jsonOk(String body) {
        return newFixedLengthResponse(Response.Status.OK, "application/json;charset=utf-8", body);
    }

    /** 从 body 里解析 "status" 字段并映射为 HTTP 状态；解析不出时视为 200。 */
    private static Response.Status statusOf(String body) {
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el != null && el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("status") && o.get("status").isJsonPrimitive()) {
                    int code = o.get("status").getAsInt();
                    Response.Status mapped = STATUS_MAP.get(code);
                    if (mapped != null) {
                        return mapped;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 返回体不是合法 JSON 就当作成功：调用方以 HTTP 层面通不通作为探测依据。
        }
        return Response.Status.OK;
    }

    /** 只取上一次识别的纯文本（给 /last?format=text 用）。 */
    private String readLastText() {
        try {
            android.content.SharedPreferences sp =
                    service.getSharedPreferences("xsocr_last", android.content.Context.MODE_PRIVATE);
            return sp.getString(PluginContract.VAR_TEXT, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * GET /shot：只截一张全屏图并落盘，不跑识别。
     *
     * <p>【为什么单独开一条路由】「区域识别」的交互是：先看到整屏 →
     * 框选一块 → 只识别那一块。若拿 POST / 去取这张预览图，
     * 就会白白先识别一次整屏 —— 一张 4K 图跑一遍检测 + 识别，
     * 几百毫秒到几秒、几百 MB 峰值内存，全为了拿一张图，很亏。
     *
     * <p>返回的 JSON 结构与识别接口保持同一套惯例
     * （{@code status} + {@code data}），方便调用方统一处理：
     * <pre>
     * {"status":200,"data":{"path":"/data/.../ocr_capture.png","width":1080,"height":2400}}
     * </pre>
     */
    private Response handleShotOnly(String captureMode) {
        try {
            // 指定了方式就只查那一条，否则维持「任一条可用即可」的老判断。
            // 不这样分的话，用户选了 root 而只有无障碍可用时，
            // 这里会放行，然后在下面截出另一条路的结果 —— 与他选的不是一回事。
            boolean modeSpecified = captureMode != null
                    && captureMode.length() > 0
                    && !OCRService.CAPTURE_AUTO.equals(captureMode);
            boolean ready = modeSpecified
                    ? service.isCaptureModeReady(captureMode)
                    : service.isScreenCaptureReady();
            if (!ready) {
                String why = modeSpecified
                        ? "requested capture mode '" + captureMode
                                + "' is not ready. Please enable it in Grace first."
                        : "Device has no root; open the app and tap onScreenCapture once "
                                + "to authorize MediaProjection.";
                return json("{\"status\": 403, \"data\": \"Error: Screen capture not available. "
                        + why + "\"}");
            }
            Bitmap bitmap;
            try {
                bitmap = service.captureByMode(captureMode);
            } catch (Throwable t) {
                return json("{\"status\": 500, \"data\": \"Error: Screen capture failed: "
                        + (t.getMessage() == null ? "unknown" : t.getMessage()) + "\"}");
            }
            if (bitmap == null) {
                return json("{\"status\": 500, \"data\": \"Error: Screen capture returned null\"}");
            }
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            File shot = new File(service.getCacheDir(), "ocr_capture.png");
            try {
                FileOutputStream fos = new FileOutputStream(shot);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                fos.close();
            } catch (Exception e) {
                bitmap.recycle();
                return json("{\"status\": 500, \"data\": \"Error: cache screenshot failed: "
                        + e.getMessage() + "\"}");
            } finally {
                bitmap.recycle();
            }
            // 记下路径与尺寸：界面框选完要按全屏尺寸算比例，
            // 走这条路比让界面自己去 decode 一张 4K 图取宽高便宜得多。
            OCRService.lastCapturePath = shot.getAbsolutePath();
            OCRService.lastFullImageWidth = w;
            OCRService.lastFullImageHeight = h;
            Log.i(TAG, "[Shot] 截屏 " + w + "x" + h + " -> " + shot.getAbsolutePath());
            return json("{\"status\": 200, \"data\": {\"path\":\""
                    + shot.getAbsolutePath().replace("\\", "\\\\") + "\",\"width\":" + w
                    + ",\"height\":" + h + "}}");
        } catch (Throwable t) {
            Log.e(TAG, "[Shot] 处理失败: " + t.getMessage());
            return json("{\"status\": 500, \"data\": \"Error: " + t.getMessage() + "\"}");
        }
    }

    /**
     * 便捷接口：一步完成「取图 → 识别 → 返回」。
     *
     * GET /ocr?format=text[&tier=rapid][&path=/sdcard/a.png][&type=1][&region=l,t,r,b]
     *
     * 为什么要做这个：
     *   MacroDroid 触发 Locale 插件用的是**普通广播**，
     *   而 Tasker 的变量回传机制**依赖有序广播**——
     *   所以插件那条路在 MacroDroid 上走不通。
     *   改成「HTTP 请求」动作后，一步拿到结果，不依赖任何宿主特性。
     */
    private Response handleOcrShortcut(IHTTPSession session) {
        try {
            Map<String, String> q = session.getParms();
            String path = q == null ? null : q.get("path");
            String tier = q == null ? null : q.get("tier");
            String typeStr = q == null ? null : q.get("type");
            boolean wantText = isFormatText(session);

            int type = 1;
            if (typeStr != null && !typeStr.trim().isEmpty()) {
                try {
                    type = Integer.parseInt(typeStr.trim());
                } catch (NumberFormatException ignored) {
                    // type 参数不是数字时保持默认值（1 = 纯文本），不因一个脏参数直接报错。
                }
            }

            // 1) 可选：切换档位
            if (tier != null && !tier.trim().isEmpty()) {
                int want = OCRService.tierFromName(tier);
                if (want >= 0 && want != OCRService.currentTier) {
                    if (!service.switchTier(want)) {
                        return err(wantText, "切换档位失败: " + tier);
                    }
                }
            }

            // 2) 取图
            Bitmap bitmap;
            if (path != null && !path.trim().isEmpty()) {
                File f = new File(path.trim());
                if (!f.exists()) return err(wantText, "图片不存在: " + path);
                bitmap = decodeFileSafe(f.getAbsolutePath());
                if (bitmap == null) return err(wantText, "图片解码失败");
            } else {
                // 不传 path：自动截屏
                if (!service.isScreenCaptureReady()) {
                    return err(wantText, "截屏未就绪（App 内点一次「开启截屏授权」）");
                }
                bitmap = service.captureScreen();
                if (bitmap == null) return err(wantText, "截屏失败");
            }

            // 3) 识别
            OCRService.lastOcrImageWidth = bitmap.getWidth();
            OCRService.lastOcrImageHeight = bitmap.getHeight();
            OCRService.lastFullImageWidth = bitmap.getWidth();
            OCRService.lastFullImageHeight = bitmap.getHeight();
            OCRService.lastRegionLeft = 0;
            OCRService.lastRegionTop = 0;
            // 与 POST / 走同一份裁剪逻辑：只框一块时，别把整屏送进引擎。
            String regionStr2 = q == null ? null : q.get("region");
            float[] frac = parseRegion(regionStr2);
            if (frac != null) {
                String[] errBox = new String[1];
                Bitmap cropped = cropByRegion(bitmap, frac, errBox);
                if (cropped == null) {
                    bitmap.recycle();
                    return err(wantText, errBox[0] == null ? "识别区域无效" : errBox[0]);
                }
                bitmap = cropped;
            }
            String result;
            try {
                // 统一入口：内部按当前档位分流到 native 或 ML Kit（rapid）
                result = service.processBitmap(bitmap, type);
            } finally {
                bitmap.recycle();
            }
            if (result == null) return err(wantText, "识别失败");
            // 坐标从「裁剪块」平移回「全屏」，与 POST / 保持一致。
            result = shiftCoords(result, OCRService.lastRegionLeft, OCRService.lastRegionTop);

            // 4) 顺便存一份（供 /last 用）
            stashFromResult(result);

            // 5) 按格式返回
            if (wantText) {
                return newFixedLengthResponse(Response.Status.OK,
                        "text/plain;charset=utf-8", extractText(result));
            }
            return json(result);

        } catch (Throwable t) {
            Log.e(TAG, "/ocr 处理失败: " + t);
            String msg = t.getMessage() == null ? "unknown" : t.getMessage();
            return err(true, msg);
        }
    }

    /**
     * 判断调用方是否要「纯文本」响应（?format=text）。
     *
     * 为什么不看 uri：NanoHTTPD 的 getUri() 返回的是**去掉 query 的路径**
     * （例如请求 /last?format=text 时 getUri() 只有 "/last"）。
     * 所以只能在 parms / queryString 里判断。
     */
    private boolean isFormatText(IHTTPSession session) {
        try {
            Map<String, String> q = session.getParms();
            if (q != null && "text".equals(q.get("format"))) {
                return true;
            }
            String qs = session.getQueryParameterString();
            if (qs != null && qs.contains("format=text")) {
                return true;
            }
        } catch (Throwable ignored) {
            // 查询串解析失败视为「没有 format=text」，按 JSON 返回：这是更保守的默认。
        }
        return false;
    }

    /**
     * 出错响应。
     *
     * 【修复⑨】text 分支原来也回 200，导致 MacroDroid 的「HTTP 请求」动作
     * 拿到的 HTTP 码永远是成功，脚本没法用状态码判断失败。
     * 现在统一回 500（并保留 body 里的 "status": 500 供解析）。
     */
    private Response err(boolean plainText, String msg) {
        if (plainText) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain;charset=utf-8", "ERROR: " + msg);
        }
        return json("{\"status\": 500, \"data\": \"Error: " + msg + "\"}");
    }

    /** 从识别结果 JSON 里抽出纯文本（多行用换行拼）。 */
    private String extractText(String resultJson) {
        try {
            JsonElement el = JsonParser.parseString(resultJson);
            JsonObject o = el.getAsJsonObject();
            JsonArray arr = o.getAsJsonArray("data");
            if (arr == null) return "";
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : arr) {
                JsonObject it = e.getAsJsonObject();
                String t = it.has("text") ? it.get("text").getAsString() : "";
                if (t.isEmpty()) continue;
                if (sb.length() > 0) sb.append("\n");
                sb.append(t);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 把识别结果 JSON 存进 SharedPreferences（供 /last 用）。 */
    private void stashFromResult(String resultJson) {
        try {
            JsonObject o = JsonParser.parseString(resultJson).getAsJsonObject();
            String text = extractText(resultJson);
            JsonArray arr = o.getAsJsonArray("data");
            int count = arr == null ? 0 : arr.size();

            android.content.SharedPreferences.Editor e =
                    service.getSharedPreferences("xsocr_last", android.content.Context.MODE_PRIVATE)
                            .edit();
            e.putString(PluginContract.VAR_TEXT, text);
            // 注意：/last 这条路径不参与「指定文本」匹配（没有配置上下文），
            // 所以 match/x/y 只给默认值，保持字段结构一致。
            e.putString(PluginContract.VAR_MATCH, "false");
            e.putString(PluginContract.VAR_X, "-1");
            e.putString(PluginContract.VAR_Y, "-1");
            e.putString(PluginContract.VAR_ERROR, "");
            e.putString(PluginContract.VAR_TIME,
                    String.valueOf(o.has("time") ? o.get("time").getAsInt() : 0));
            e.putString(PluginContract.VAR_TIER,
                    o.has("tier") ? o.get("tier").getAsString() : "");
            // count 只用于 HTTP 接口展示，不是回传变量
            e.putString("count", String.valueOf(count));
            e.putLong("ts", System.currentTimeMillis());
            e.apply();
        } catch (Throwable t) {
            Log.w(TAG, "存结果失败: " + t);
        }
    }

    /**
     * 读取「上一次识别结果」（插件执行后写入的）。
     *
     * 用途：变量回传依赖宿主的私有扩展，不保证生效。
     * 宏里若拿不到变量，可以用 HTTP 动作访问 /last 兜底。
     */
    private String readLastResult() {
        try {
            android.content.SharedPreferences sp =
                    service.getSharedPreferences("xsocr_last", android.content.Context.MODE_PRIVATE);
            if (sp.getAll().isEmpty()) {
                // 【修复】空记录分支也要带上模型诊断字段。
                // 否则"冷启动后模型还没加载好、又恰好还没识别过"时，
                // 调用方（尤其是插件的就绪等待逻辑）根本读不到 modelReady，
                // 只能盲等。这里让 /last 在任何情况下都能回答"引擎好了没"。
                com.google.gson.JsonObject empty = new com.google.gson.JsonObject();
                empty.addProperty("status", 404);
                empty.addProperty("data", "还没有识别记录");
                empty.addProperty("modelReady", OCRService.modelReady);
                empty.addProperty("modelError",
                        OCRService.modelError == null ? "" : OCRService.modelError);
                return empty.toString();
            }
            String text = sp.getString(PluginContract.VAR_TEXT, "");
            String match = sp.getString(PluginContract.VAR_MATCH, "false");
            String x = sp.getString(PluginContract.VAR_X, "-1");
            String y = sp.getString(PluginContract.VAR_Y, "-1");
            String err = sp.getString(PluginContract.VAR_ERROR, "");
            String time = sp.getString(PluginContract.VAR_TIME, "0");
            String tier = sp.getString(PluginContract.VAR_TIER, "");
            long ts = sp.getLong("ts", 0);

            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("status", 200);
            o.addProperty("text", text);
            o.addProperty("match", match);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("error", err);
            o.addProperty("time", time);
            o.addProperty("tier", tier);
            o.addProperty("timestamp", ts);
            // 【修复】诊断字段：模型加载失败时接口原来只回空结果，
            // 调用方完全看不出是"没文字"还是"引擎坏了"。现在一眼可判。
            o.addProperty("modelReady", OCRService.modelReady);
            o.addProperty("modelError", OCRService.modelError == null ? "" : OCRService.modelError);
            return o.toString();
        } catch (Throwable t) {
            return "{\"status\":500,\"data\":\"读取失败: " + t.getMessage() + "\"}";
        }
    }
}