package com.xiaosun.ocr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机 / 应用被更新后自动拉起 OCR 服务，保证长时间无人值守时接口始终可用。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "XiaoSunOCR";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "BootReceiver 收到: " + action);
        try {
            Intent service = new Intent(context, OCRService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            Log.e(TAG, "开机自启失败: " + e.getMessage());
        }
    }
}