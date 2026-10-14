package com.xiaosun.ocr;

import android.content.Context;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 极简 HTTP 客户端，用于访问本机 OCR 接口（127.0.0.1:1666）。
 *
 * 为什么不复用 MainActivity 里的 doHttpRequest：
 *   那是 Activity 的私有方法，BroadcastReceiver 拿不到。
 *   单独抽出来，两边都能用。
 */
final class Http {

    private Http() {}

    static final String URL_BASE = "http://127.0.0.1:1666";

    /** POST 一段 JSON，返回响应文本；失败返回 null。 */
    static String post(Context ctx, String json, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(URL_BASE).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(timeoutMs);

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