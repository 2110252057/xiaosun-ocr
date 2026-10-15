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
        // OCR 服务：**尊重「启用 OCR 服务」开关**。
        // 只想用手势触发宏的人关掉这个服务后，重启不该又把它拉回来 ——
        // 否则开关只在本次开机内有效，用户会以为开关坏了。
        try {
            if (OCRService.isEnabled(context)) {
                Intent service = new Intent(context, OCRService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(service);
                } else {
                    context.startService(service);
                }
            } else {
                Log.i(TAG, "OCR 服务已关闭（用户设置），开机不自启");
            }
        } catch (Exception e) {
            Log.e(TAG, "开机自启失败: " + e.getMessage());
        }
        // 【新增】边缘手势引擎同样在开机后恢复。
        //
        // 为什么必须在这里恢复：手势窗口是常驻悬浮窗，
        // 用户开着总开关却因为重启而"手柄消失"，属于难以自查的故障。
        //
        // sync() 内部会先读总开关：没开就什么都不做，
        // 所以这里不需要额外判断，也不会在用户没启用时白拉起一个前台服务。
        // 注意它必须放在 OCRService 之后 —— 手势触发后往往紧接着要调 OCR。
        try {
            EdgeGestureService.sync(context);
        } catch (Throwable t) {
            Log.e(TAG, "恢复手势服务失败: " + t);
        }
    }
}