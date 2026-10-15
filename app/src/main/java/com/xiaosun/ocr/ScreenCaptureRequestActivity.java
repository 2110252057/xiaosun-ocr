package com.xiaosun.ocr;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * 截屏授权用的「透明中转 Activity」。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么需要这个类】
 * ════════════════════════════════════════════════════════════════
 * MediaProjection 授权只能由 Activity 发起
 * （createScreenCaptureIntent() 是 Activity 的 API），
 * 但插件的执行入口是 BroadcastReceiver（后台广播），无法弹授权框。
 *
 * 于是插件在需要授权时，用 startActivity 拉起本 Activity：
 *   PluginFireReceiver
 *     → startActivity(ScreenCaptureRequestActivity)
 *        → 弹出系统授权框
 *           → 用户同意 → 把 resultCode/data 交给 OCRService
 *              → 服务建立投屏会话 → 回调插件继续识别
 *
 * ⚠️ 后台启动 Activity 需要「悬浮窗权限」（SYSTEM_ALERT_WINDOW），
 *    没有它系统会直接丢弃这个启动请求（日志可见 Background activity start blocked）。
 *    所以本 Activity 会先检查该权限，没有就提示用户去开启。
 *
 * ════════════════════════════════════════════════════════════════
 * 【为什么是透明的】
 * ════════════════════════════════════════════════════════════════
 * 主题设为全透明、无动画、不进入最近任务列表，
 * 用户视觉上只会看到系统自己的「开始录制或投放」授权框，
 * 不会察觉中间还有一个 Activity 被拉起。
 */
public class ScreenCaptureRequestActivity extends Activity {

    private static final String TAG = "XiaoSunOCR-CapReq";

    /** 请求码，仅本类内部使用。 */
    private static final int REQ_CAPTURE = 0x5A01;

    /**
     * 授权完成后，把结果告知插件，让它继续识别。
     *
     * 用静态回调而不是广播：插件与 Activity 在同一进程内，
     * 直接回调最简单，也避免了广播被系统限制的风险。
     */
    public interface OnResultListener {
        /** @param ok true 表示已拿到可用的截屏能力 */
        void onScreenCaptureResult(boolean ok);
    }

    private static volatile OnResultListener sListener;

    /** 由插件在拉起本 Activity 之前注册。 */
    public static void setListener(OnResultListener l) {
        sListener = l;
    }

    /** 通知结果并清空回调，避免持有引用。 */
    private static void notifyResult(boolean ok) {
        OnResultListener l = sListener;
        sListener = null;
        if (l != null) {
            try {
                l.onScreenCaptureResult(ok);
            } catch (Throwable t) {
                Log.w(TAG, "回调插件失败: " + t);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 透明 Activity，无界面内容
        Log.i(TAG, "截屏授权中转 Activity 启动");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "系统版本过低，不支持 MediaProjection");
            notifyResult(false);
            finish();
            return;
        }

        // 若已有可用截屏能力（root 或已授权），无需再弹框
        if (OCRService.screenCaptureReady) {
            Log.i(TAG, "截屏已就绪，无需授权");
            notifyResult(true);
            finish();
            return;
        }

        // 后台启动 Activity 依赖悬浮窗权限，先检查
        if (!canStartFromBackground()) {
            Log.w(TAG, "缺少悬浮窗权限，无法在后台弹出授权框");
            Toast.makeText(this,
                    "请先在「小孙OCR」中开启「悬浮窗权限」，插件才能自动申请截屏",
                    Toast.LENGTH_LONG).show();
            notifyResult(false);
            finish();
            return;
        }

        requestCapture();
    }

    /** 是否具备「从后台启动 Activity」的条件。 */
    private boolean canStartFromBackground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            return android.provider.Settings.canDrawOverlays(this);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 弹出系统截屏授权框。 */
    private void requestCapture() {
        try {
            MediaProjectionManager mgr = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mgr == null) {
                Log.e(TAG, "无法获取 MediaProjectionManager");
                notifyResult(false);
                finish();
                return;
            }
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Throwable t) {
            Log.e(TAG, "申请截屏失败: " + t);
            notifyResult(false);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQ_CAPTURE) {
            return;
        }

        if (resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "用户取消了截屏授权");
            notifyResult(false);
            finish();
            return;
        }

        Log.i(TAG, "已获得截屏授权，正在交给服务建立投屏会话");

        // 把授权结果送给服务。
        // 服务可能还没起来（插件触发的场景），所以先确保它启动。
        try {
            Intent svc = new Intent(this, OCRService.class);
            svc.setAction(OCRService.ACTION_START_CAPTURE);
            svc.putExtra(OCRService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(OCRService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable t) {
            Log.e(TAG, "启动服务失败: " + t);
            notifyResult(false);
            finish();
            return;
        }

        // 轮询等待投屏会话真正建立，再回调插件（不要盲目标记成功）。
        // 上限 10 秒（40 × 250ms）。
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] tries = {0};
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (OCRService.screenCaptureReady) {
                    Log.i(TAG, "投屏会话已就绪，回调插件继续识别");
                    notifyResult(true);
                    finish();
                    return;
                }
                if (++tries[0] >= 40) {
                    Log.w(TAG, "等待投屏会话超时");
                    notifyResult(false);
                    finish();
                    return;
                }
                h.postDelayed(this, 250);
            }
        }, 250);
    }

    @Override
    public void finish() {
        super.finish();
        // 无切换动画，避免视觉打扰
        overridePendingTransition(0, 0);
    }
}