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
 * 复刻原版 NJOCR 的本地 HTTP 接口。
 *
 * 与原版（Ktor + Netty）保持一致的对外协议：
 *   监听 127.0.0.1:1666，路由 POST "/"
 *   参数支持 JSON Body / 表单 / Query 三种形式：path、type
 *   type: 1=纯文本  2=Json数组(含坐标)  3=详细Json(含矩形)
 *   不传 path 时自动截屏识别
 *   响应体直接使用 native 返回的 JSON 文本
 */
public class OcrHttpServer extends NanoHTTPD {

    private static final String TAG = "NJOCR";

    private final OCRService service;

    public OcrHttpServer(OCRService service) {
        super("127.0.0.1", 1666);
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

    private Response handle(IHTTPSession session) {
        try {
            // ---------- 兜底路由：读取「上一次识别结果」 ----------
            // 给 MacroDroid 的「HTTP 请求」动作用。
            // 万一宿主的变量回传不生效，宏可以改成：
            //   1) 调插件触发识别
            //   2) HTTP GET http://127.0.0.1:1666/last
            //   3) 从响应里取文字
            String uri = session.getUri();
            if (uri != null && uri.startsWith("/last")) {
                // 支持 /last?format=text 直接返回纯文本，
                // 方便 MacroDroid 的「HTTP 请求」动作把响应体整个塞进变量，
                // 不用再做 JSON 解析。
                //
                // ★ 注意：NanoHTTPD 的 getUri() **不含 query string**，
                //   所以不能在 uri 上找 "format=text"，必须读 session.getParms()。
                if (isFormatText(session)) {
                    return newFixedLengthResponse(Response.Status.OK,
                            "text/plain;charset=utf-8", readLastText());
                }
                return json(readLastResult());
            }

            // ---------- 便捷路由：一步完成「截屏 + 识别 + 返回文本」 ----------
            // 专为 MacroDroid / Tasker 的「HTTP 请求」动作用。
            //
            //   GET http://127.0.0.1:1666/ocr?format=text
            //     → 自动截屏识别，直接返回纯文本
            //
            //   GET http://127.0.0.1:1666/ocr?format=text&tier=rapid
            //     → 指定档位
            //
            //   GET http://127.0.0.1:1666/ocr?format=text&path=/sdcard/a.png
            //     → 识别指定图片
            //
            // 这样宏里只要一个 HTTP 动作，不用装插件、不用管变量回传。
            if (uri != null && uri.startsWith("/ocr")) {
                return handleOcrShortcut(session);
            }

            if (session.getMethod() != Method.POST) {
                return json("{\"status\": 405, \"data\": \"Error: method not allowed\"}");
            }

            String path = null;
            String typeStr = null;
            String tierStr = null;
            /** 仅切换档位、不做识别的标识（action=setTier）。 */
            String actionStr = null;

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
            }

            // 3) multipart 表单字段
            if (files.containsKey("path")) path = files.get("path");
            if (files.containsKey("type")) typeStr = files.get("type");
            if (files.containsKey("tier")) tierStr = files.get("tier");

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
                Log.i(TAG, "[Screen] 未传图片，开始截屏识别");
                if (!service.isScreenCaptureReady()) {
                    return json("{\"status\": 403, \"data\": \"Error: Screen capture not available. "
                            + "Device has no root; open the app and tap "
                            + "\\\"onScreenCapture\\\" once to authorize MediaProjection.\"}");
                }
                try {
                    bitmap = service.captureScreen();
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

            Log.i(TAG, "[OCR] type=" + type + ", size=" + bitmap.getWidth() + "x" + bitmap.getHeight());
            String result;
            try {
                result = service.nativeProcessBitmap(bitmap, type);
            } finally {
                bitmap.recycle();
            }

            if (result == null) {
                result = "{\"status\": 500, \"data\": \"Error: OCR failed\"}";
            }
            return json(result);

        } catch (Exception e) {
            Log.e(TAG, "处理请求异常: " + e.getMessage());
            String msg = e.getMessage() == null ? "unknown" : e.getMessage();
            return json("{\"status\": 500, \"data\": \"Error: " + msg + "\"}");
        }
    }

    /** 解析 JSON 请求体，失败返回 null。 */
    private Map<String, String> parseJson(String postData) {
        try {
            JsonElement el = JsonParser.parseString(postData);
            if (!el.isJsonObject()) {
                return null;
            }
            JsonObject obj = el.getAsJsonObject();
            Map<String, String> map = new HashMap<>();
            if (obj.has("path") && !obj.get("path").isJsonNull()) {
                map.put("path", obj.get("path").getAsString());
            }
            if (obj.has("type") && !obj.get("type").isJsonNull()) {
                map.put("type", obj.get("type").getAsString());
            }
            if (obj.has("tier") && !obj.get("tier").isJsonNull()) {
                map.put("tier", obj.get("tier").getAsString());
            }
            if (obj.has("action") && !obj.get("action").isJsonNull()) {
                map.put("action", obj.get("action").getAsString());
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

    private Response json(String body) {
        return newFixedLengthResponse(Response.Status.OK, "application/json;charset=utf-8", body);
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
     * 便捷接口：一步完成「取图 → 识别 → 返回」。
     *
     * GET /ocr?format=text[&tier=rapid][&path=/sdcard/a.png][&type=1]
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
            String result;
            try {
                result = service.nativeProcessBitmap(bitmap, type);
            } finally {
                bitmap.recycle();
            }
            if (result == null) return err(wantText, "识别失败");

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
        }
        return false;
    }

    /** 出错响应。 */
    private Response err(boolean plainText, String msg) {
        if (plainText) {
            return newFixedLengthResponse(Response.Status.OK,
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
                return "{\"status\":404,\"data\":\"还没有识别记录\"}";
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
            return o.toString();
        } catch (Throwable t) {
            return "{\"status\":500,\"data\":\"读取失败: " + t.getMessage() + "\"}";
        }
    }
}