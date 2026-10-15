package com.xiaosun.ocr;

import android.content.Context;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 极简 HTTP 客户端，用于访问本机 OCR 接口。
 *
 * 【修复⑪：URL 唯一来源】地址不再硬编码 1666，而是由
 * {@link OCRService#HTTP_PORT} 拼出来。此前 MainActivity、Http、
 * OcrHttpServer 三处各写了一遍端口字面量，改端口必然漏改。
 *
 * 为什么还保留这个类（而不是全并进 MainActivity）：
 *   MainActivity 里的同名方法是 Activity 私有，BroadcastReceiver 拿不到。
 */
final class Http {

    private Http() {}

    /** 本机接口根地址，端口取自 OCRService.HTTP_PORT（唯一来源）。 */
    static final String URL_BASE = "http://127.0.0.1:" + OCRService.HTTP_PORT;

    /** POST 一段 JSON，返回响应文本；失败返回 null。 */
    static String post(Context ctx, String json, int timeoutMs) {
        // 默认连接超时与原来一致（5s）
        return post(ctx, json, 5000, timeoutMs);
    }

    /**
     * POST 一段 JSON，可分别指定连接/读取超时；返回响应文本，失败返回 null。
     *
     * 【修复⑪】把连接超时也参数化，MainActivity 的识别路径（连接 10s 读取 60s）
     * 就能直接复用这里，不必再维护第二份几乎一样的实现。
     */
    static String post(Context ctx, String json, int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(URL_BASE).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);

            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;

            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            is.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 探测服务是否已在监听。
     *
     * 用 GET /last 来判断：
     * 这是服务端新加的路由，无论有没有历史结果都会返回 JSON，
     * 能稳定反映"HTTP 服务已起来"。
     *
     * （之前用 POST {} 探测，服务端会走去截屏分支、耗时很久，
     *  导致误判为"服务未就绪"。）
     *
     * ★ 注意语义边界：ping() 只代表 **HTTP 端口通了**，
     *   不代表模型已加载完（服务现在是"HTTP 先上线、模型后台加载"）。
     *   要判断"能不能识别"，得看 /last 里的 modelReady 字段，
     *   见 PluginFireReceiver.waitModelReadyUntil()。
     */
    static boolean ping() {
        return ping(1500);
    }

    static boolean ping(int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(URL_BASE + "/last").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            int code = conn.getResponseCode();
            return code == 200;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** GET 一个路径，返回响应文本；失败返回 null。 */
    static String get(String path, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(URL_BASE + path).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(timeoutMs);
            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            is.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}