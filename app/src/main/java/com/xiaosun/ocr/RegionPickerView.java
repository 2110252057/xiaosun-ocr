package com.xiaosun.ocr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import static com.xiaosun.ocr.Palette.*;
import static com.xiaosun.ocr.Ui.dp;

/**
 * 区域框选浮层：在截屏预览上拖出一个矩形，只把这一块送去识别。
 *
 * ════════════════════════════════════════════════════════════════
 * 【它只画遮罩，不画图片】
 * ════════════════════════════════════════════════════════════════
 * 图片仍由下面那层 ImageView 显示。本类只叠在上面画「选区以外压暗」的
 * 四块遮罩 + 边框 + 四角手柄。这样不必为了框选再存一份 Bitmap
 * （一张 1080×2400 的全屏图就占 10 MB），也不会与 ImageView 的
 * 生命周期、recycle 时机互相踩到。
 *
 * 【为什么要自己算 FIT_CENTER】
 * 「手指落在哪」必须换算成「位图上的哪个像素」，而这件事取决于图片在
 * 控件里实际占了多大一块。ImageView 的布局结果只有它自己知道，
 * 叠在外面去 measure/layout 反推，一旦 padding、圆角、最小高度变了就会偏。
 * 这里用与 ImageView 完全相同的 FIT_CENTER 规则自己算一遍，
 * 容器尺寸、缩放模式都一致，所以两层严丝合缝。
 *
 * 【对外给比例，不给像素】
 * {@link #getRegionNormalized()} 返回 0~1 的比例值，调用方再乘全屏真实尺寸。
 * 因为预览图是 decodeSampled 降采样过的，与截屏原图尺寸不同 ——
 * 直接回传控件像素，就会把选框送偏，这正是「框画对了、识别出来却是别处」
 * 这类问题的来源。比例值天然与分辨率无关。
 */
class RegionPickerView extends View {

    /** 选区变化回调：让「识别选区」按钮跟着可用 / 不可用。 */
    interface OnSelectionChanged {
        /** @param hasSelection 当前是否已有有效选区 */
        void onSelectionChanged(boolean hasSelection);
    }

    /** 遮罩色：把选区以外压暗，用户一眼看出「亮的这块才会被识别」。 */
    private static final int MASK_COLOR = 0x99000000;
    /** 文字阴影色，仅为在亮色截图上也能读清。 */
    private static final int HINT_SHADOW = 0xFF000000;
    /** 四角手柄边长（dp）。 */
    private static final float HANDLE_DP = 18f;
    /**
     * 判定「开始拖拽」的最小位移（dp）。
     *
     * 没有它的话，手指按下的第一帧就会生成一个 0×0 的选区，
     * 表现为「一点就把原来的框弄没了」。
     */
    private static final float SLOP_DP = 7f;
    /**
     * 选区允许的最小边长（dp）。
     *
     * 拖角调整时如果一路拖过头，right 会跑到 left 左边去，
     * 框就"翻面"成一个负数宽度的矩形，裁剪必然出错。
     * 留出这一格宽度既可以防翻面，也避免存下一个只有几像素、
     * 识别必然空结果的框。
     */
    private static final float MIN_SIDE_DP = 7f;
    /**
     * 四角手柄的**命中半径**（dp），比视觉尺寸大。
     *
     * 视觉上那四个方块边长 {@link #HANDLE_DP} = 18dp，但手指的接触点
     * 有十几 dp 宽，按视觉大小做命中区会"看得见、点不中"。
     * 放大到 28dp 半径后，手指落在手柄附近都算命中该角。
     * 命中区用**方形**（比较 max(|dx|,|dy|) 而不是欧氏距离），
     * 覆盖面积更大、更贴合"点在角上"的直觉。
     */
    private static final float HANDLE_HIT_DP = 28f;

    /** 没有手势进行中。 */
    private static final int MODE_NONE = 0;
    /** 从空白处拉出新的一框（只在没有选区时可用）。 */
    private static final int MODE_NEW = 1;
    /** 按住框内部整体平移。 */
    private static final int MODE_MOVE = 2;
    /** 拖左上角。 */
    private static final int MODE_TOP_LEFT = 3;
    /** 拖右上角。 */
    private static final int MODE_TOP_RIGHT = 4;
    /** 拖左下角。 */
    private static final int MODE_BOTTOM_LEFT = 5;
    /** 拖右下角。 */
    private static final int MODE_BOTTOM_RIGHT = 6;
    /**
     * 未框选时的提示语。
     *
     * <p>两句分开是因为可做的操作不同：没有框时只能"拖出一块"，
     * 有框之后拖角是调边、拖内部是整块挪、想重画得先点「清空」。
     * 一直显示同一句话会让用户以为"再拖一次就是重画"。
     */
    private static final String HINT_TEXT = "在截图上拖动，框出要识别的一块";

    /** 图片区域在控件里的实际位置（FIT_CENTER 结果）。 */
    private final RectF imageRect = new RectF();
    /** 当前选区（控件坐标）。 */
    private final RectF selection = new RectF();

    private int imageWidth;
    private int imageHeight;
    private boolean hasSelection;
    private boolean dragging;
    private float downX;
    private float downY;

    /**
     * 本次手势的作用（见 MODE_* 常量）。
     *
     * <p>【为什么要先判一次、再全程沿用】手势类型只能在 ACTION_DOWN 那一刻
     * 定下来，之后整个拖动过程都按它走。若每帧重新判断，用户把角拖到
     * 另一处后可能被重新识别成别的模式（比如角拖过中心后突然变成"拖对角"），
     * 框会"跳"一下。
     */
    private int touchMode = MODE_NONE;
    /** 按下瞬间的选区快照。拖动过程中一律基于它算，避免多帧误差累积。 */
    private final RectF downSelection = new RectF();

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private OnSelectionChanged listener;

    RegionPickerView(Context c) {
        super(c);

        maskPaint.setStyle(Paint.Style.FILL);
        maskPaint.setColor(MASK_COLOR);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(c, 2));
        borderPaint.setColor(C_BRAND);

        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(C_BRAND);

        hintPaint.setStyle(Paint.Style.FILL);
        hintPaint.setColor(C_SURFACE);
        hintPaint.setTextSize(dp(c, 12));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setShadowLayer(dp(c, 2), 0, 0, HINT_SHADOW);
    }

    void setOnSelectionChanged(OnSelectionChanged l) {
        this.listener = l;
    }

    /**
     * 告诉本控件「下面那张图是多少像素」。
     *
     * 传 0 或负数表示当前没有图，此时不响应触摸、不画遮罩。
     * 每次换图都会清掉旧选区 —— 旧框是在旧图上画的，换图后继续用毫无意义。
     */
    void setImageSize(int w, int h) {
        imageWidth = Math.max(0, w);
        imageHeight = Math.max(0, h);
        selection.setEmpty();
        hasSelection = false;
        dragging = false;
        updateImageRect();
        notifyChanged();
        invalidate();
    }

    /** 清掉选区（退出框选模式、或重新截屏时调用）。 */
    void clearSelection() {
        selection.setEmpty();
        hasSelection = false;
        dragging = false;
        notifyChanged();
        invalidate();
    }

    /**
     * 把一块已有区域摆出来（用于「再次框选时先显示上次的框」）。
     *
     * <p>【为什么要这个】区域是「配一次长期用」的，用户再进来调它时，
     * 需要先看到现在框在哪，才谈得上「微调两个像素」。每次从空白开始，
     * 等于强迫他把整块区域重新量一遍。
     *
     * @param r {left, top, right, bottom} 归一化比例；null 或非法时清空
     */
    void setRegionNormalized(float[] r) {
        if (r == null || r.length != 4 || imageRect.isEmpty()) {
            clearSelection();
            return;
        }
        float w = imageRect.width();
        float h = imageRect.height();
        if (w <= 0f || h <= 0f) {
            return;
        }
        float l = imageRect.left + r[0] * w;
        float t = imageRect.top + r[1] * h;
        float rr = imageRect.left + r[2] * w;
        float b = imageRect.top + r[3] * h;
        // 夹回图片区域内：存下来的比例理论上合法，但若这次图片换了
        // 长宽比（横竖屏切换），浮点往返仍可能多出零点几像素。
        l = Math.max(imageRect.left, Math.min(imageRect.right, l));
        t = Math.max(imageRect.top, Math.min(imageRect.bottom, t));
        rr = Math.max(imageRect.left, Math.min(imageRect.right, rr));
        b = Math.max(imageRect.top, Math.min(imageRect.bottom, b));
        if (rr - l < 2f || b - t < 2f) {
            clearSelection();
            return;
        }
        selection.set(l, t, rr, b);
        hasSelection = true;
        notifyChanged();
        invalidate();
    }

    boolean hasSelection() {
        return hasSelection;
    }

    /**
     * 取当前选区，返回 {left, top, right, bottom} 的**归一化比例**（0~1）。
     *
     * 没有有效选区时返回 null。调用方乘上截屏原图的宽高即得像素坐标。
     */
    float[] getRegionNormalized() {
        if (!hasSelection || imageRect.isEmpty()) {
            return null;
        }
        float w = imageRect.width();
        float h = imageRect.height();
        if (w <= 0f || h <= 0f) {
            return null;
        }
        float[] r = new float[4];
        r[0] = (selection.left - imageRect.left) / w;
        r[1] = (selection.top - imageRect.top) / h;
        r[2] = (selection.right - imageRect.left) / w;
        r[3] = (selection.bottom - imageRect.top) / h;
        for (int i = 0; i < 4; i++) {
            // 触摸点已经 clamp 过，这里再兜一次：反算除法的浮点误差
            // 有可能让比例略微越界，越界会让服务端裁剪落到图外。
            if (r[i] < 0f) r[i] = 0f;
            if (r[i] > 1f) r[i] = 1f;
        }
        return r;
    }

    // ===================== 布局 =====================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateImageRect();
    }

    /** 按 FIT_CENTER 规则算出图片在控件里的矩形，与 ImageView 的行为一致。 */
    private void updateImageRect() {
        imageRect.setEmpty();
        if (imageWidth <= 0 || imageHeight <= 0) {
            return;
        }
        float cw = getWidth();
        float ch = getHeight();
        if (cw <= 0f || ch <= 0f) {
            return;
        }
        float scale = Math.min(cw / imageWidth, ch / imageHeight);
        float w = imageWidth * scale;
        float h = imageHeight * scale;
        float left = (cw - w) / 2f;
        float top = (ch - h) / 2f;
        imageRect.set(left, top, left + w, top + h);
    }

    // ===================== 绘制 =====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageRect.isEmpty()) {
            return;
        }
        if (!hasSelection) {
            canvas.drawText(HINT_TEXT,
                    imageRect.centerX(),
                    imageRect.centerY(),
                    hintPaint);
            return;
        }

        float l = selection.left;
        float t = selection.top;
        float r = selection.right;
        float b = selection.bottom;

        // 四块遮罩而不是「整片遮罩 + 挖洞」：
        // 挖洞要靠 clipRect 的 DIFFERENCE 模式，该 API 在较新版本已废弃，
        // 四块矩形没有兼容性问题，代价只是多两次 drawRect。
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, t, maskPaint);
        canvas.drawRect(imageRect.left, b, imageRect.right, imageRect.bottom, maskPaint);
        canvas.drawRect(imageRect.left, t, l, b, maskPaint);
        canvas.drawRect(r, t, imageRect.right, b, maskPaint);

        canvas.drawRect(selection, borderPaint);

        // 四角手柄：让「框的边界在哪」在亮色背景上也一眼可见。
        float s = dp(getContext(), HANDLE_DP) / 2f;
        canvas.drawRect(l - s, t - s, l + s, t + s, handlePaint);
        canvas.drawRect(r - s, t - s, r + s, t + s, handlePaint);
        canvas.drawRect(l - s, b - s, l + s, b + s, handlePaint);
        canvas.drawRect(r - s, b - s, r + s, b + s, handlePaint);
    }

    // ===================== 触摸 =====================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (imageRect.isEmpty()) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // 本页内容装在 ScrollView 里：不声明「这次手势归我」的话，
                // 手指一往上拖，父容器就会把事件抢去滚屏，选区永远画不出来
                // （表现为「一拖页面就上下滑，框一点没动」）。
                // 这也顺带挡住 ViewPager / DrawerLayout 这类父容器的横滑。
                android.view.ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                downX = clampX(e.getX());
                downY = clampY(e.getY());
                dragging = false;

                // ★ 进来第一件事是"这次想干什么" —— 拖某个角？挪整块？还是新画一块？
                //   以前这里不判，一律当成"重新画"，于是每次碰框都会把原来的框
                //   从按下点重画一遍，用户没法微调。
                touchMode = hitTest(downX, downY);
                // ★ 无条件快照当前框：拖角和整块平移都以它为基准。
                //   只在 MODE_MOVE 里存是个陷阱 —— 拖角的代码同样读它，
                //   漏存的话读到的是上一次的残留（首次为空矩形），
                //   表现为"刚按下去框就跳到 0,0"。这一条是测试抓出来的。
                downSelection.set(selection);
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (touchMode == MODE_NONE) {
                    return true;
                }
                float x = clampX(e.getX());
                float y = clampY(e.getY());
                float slop = dp(getContext(), SLOP_DP);
                if (!dragging) {
                    if (Math.abs(x - downX) < slop && Math.abs(y - downY) < slop) {
                        return true;
                    }
                    dragging = true;
                }

                switch (touchMode) {
                    case MODE_NEW: {
                        // 从空白处拉新框：以按下点为锚点，和对角构成矩形。
                        // 拖到哪边都行，所以用 min/max 归一。
                        float l = Math.min(downX, x);
                        float r = Math.max(downX, x);
                        float t = Math.min(downY, y);
                        float b = Math.max(downY, y);
                        // 宽或高不够一格手柄时不算选区：这种框裁出来只有几个像素，
                        // 识别必然空结果，与其让用户困惑，不如保持「未框选」。
                        if (r - l < slop || b - t < slop) {
                            return true;
                        }
                        selection.set(l, t, r, b);
                        if (!hasSelection) {
                            hasSelection = true;
                            notifyChanged();
                        }
                        break;
                    }

                    case MODE_MOVE: {
                        // 整块平移：位移量按"按下点 → 当前点"算，
                        // 再夹回图片范围内（不许把框推出画面）。
                        float dx = x - downX;
                        float dy = y - downY;
                        float w = downSelection.width();
                        float h = downSelection.height();
                        float nl = clamp(downSelection.left + dx,
                                imageRect.left, imageRect.right - w);
                        float nt = clamp(downSelection.top + dy,
                                imageRect.top, imageRect.bottom - h);
                        selection.set(nl, nt, nl + w, nt + h);
                        break;
                    }

                    default: {
                        // 拖某个角。基准是"按下瞬间的框"，只动被拖的那两条边。
                        float minSide = dp(getContext(), MIN_SIDE_DP);
                        float l = downSelection.left;
                        float t = downSelection.top;
                        float r = downSelection.right;
                        float b = downSelection.bottom;

                        if (touchMode == MODE_TOP_LEFT || touchMode == MODE_BOTTOM_LEFT) {
                            // 动左边：不能越过右边，至少留一格
                            l = Math.min(x, r - minSide);
                        }
                        if (touchMode == MODE_TOP_RIGHT || touchMode == MODE_BOTTOM_RIGHT) {
                            // 动右边：不能越过左边
                            r = Math.max(x, l + minSide);
                        }
                        if (touchMode == MODE_TOP_LEFT || touchMode == MODE_TOP_RIGHT) {
                            // 动上边：不能越过下边
                            t = Math.min(y, b - minSide);
                        }
                        if (touchMode == MODE_BOTTOM_LEFT || touchMode == MODE_BOTTOM_RIGHT) {
                            // 动下边：不能越过上边
                            b = Math.max(y, t + minSide);
                        }
                        selection.set(l, t, r, b);
                        break;
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                touchMode = MODE_NONE;
                // 抬手就把「拦截权」还给父容器，否则这一次框选之后
                // 再也滚不动页面 —— 那个 bug 比不能框选更难察觉。
                android.view.ViewParent up = getParent();
                if (up != null) {
                    up.requestDisallowInterceptTouchEvent(false);
                }
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    /**
     * 判断按下点落在哪：四角手柄 → 拖角；框内部 → 整体平移；其余 → 什么都不做。
     *
     * <p>【命中顺序为什么是"角 → 边内 → 外部"】
     * 角优先于内部，是因为角的手柄区域有一半在框外：先判内部的话，
     * 按在角上靠外那一半会被当成"外面"，变成新画一块 —— 用户会觉得
     * 「明明按在角上，框却整个重画了」。
     *
     * <p>【有框时点空白处为什么什么都不做】
     * 这是刻意的：框一旦存在，只有「拖角 / 拖内部 / 点清空」三种操作。
     * 如果点空白处就重画，那想微调时手指落偏一两毫米，整块框就被
     * 从别处重画了 —— 恰恰是用户明确要求别发生的事。
     * 要重画只有一个入口：**先点「清空」**，清空后 hasSelection=false，
     * 本方法才会返回 {@link #MODE_NEW}。
     */
    private int hitTest(float x, float y) {
        if (!hasSelection || selection.isEmpty()) {
            // 没有框：这是唯一允许"从头画"的状态。
            return MODE_NEW;
        }
        // 命中区是方形：max(|dx|,|dy|) <= 半径，比圆形更好按。
        float hit = dp(getContext(), HANDLE_HIT_DP);
        if (nearCorner(x, y, selection.left, selection.top, hit)) return MODE_TOP_LEFT;
        if (nearCorner(x, y, selection.right, selection.top, hit)) return MODE_TOP_RIGHT;
        if (nearCorner(x, y, selection.left, selection.bottom, hit)) return MODE_BOTTOM_LEFT;
        if (nearCorner(x, y, selection.right, selection.bottom, hit)) return MODE_BOTTOM_RIGHT;
        if (selection.contains(x, y)) {
            return MODE_MOVE;
        }
        // 框外：不接受。想重画请先点「清空」。
        return MODE_NONE;
    }

    private static boolean nearCorner(float x, float y, float cx, float cy, float r) {
        return Math.abs(x - cx) <= r && Math.abs(y - cy) <= r;
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    private float clampX(float x) {
        return Math.max(imageRect.left, Math.min(imageRect.right, x));
    }

    private float clampY(float y) {
        return Math.max(imageRect.top, Math.min(imageRect.bottom, y));
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onSelectionChanged(hasSelection);
        }
    }
}