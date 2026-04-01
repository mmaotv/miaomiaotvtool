package com.miaomiao.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

/**
 * 遥控器光标视图
 * 在屏幕上显示一个箭头光标，通过 D-pad 方向键移动，ENTER 模拟点击
 */
public class CursorView extends View {

    public static final int STEP = 30; // 每次移动像素

    private float cursorX;
    private float cursorY;
    private final Paint arrowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CursorView(Context context) {
        super(context);
        init();
    }

    public CursorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // 箭头白色填充
        arrowPaint.setColor(Color.WHITE);
        arrowPaint.setStyle(Paint.Style.FILL);

        // 阴影（黑色轮廓）
        shadowPaint.setColor(Color.BLACK);
        shadowPaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setStrokeWidth(3f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 初始位置：屏幕中央
        cursorX = w / 2f;
        cursorY = h / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawArrow(canvas, cursorX, cursorY);
    }

    /**
     * 绘制箭头光标
     */
    private void drawArrow(Canvas canvas, float x, float y) {
        float size = 36f;
        float[] arrowPath = {
            x,        y,               // 顶点
            x,        y + size * 0.75f, // 左下
            x + size * 0.27f, y + size * 0.54f, // 内收口
            x + size * 0.45f, y + size,          // 底部右
            x + size * 0.55f, y + size,          // 底部右边
            x + size * 0.37f, y + size * 0.54f, // 内收口
            x + size * 0.65f, y + size * 0.54f, // 右肩
        };

        // 简单三角形箭头
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);                      // 尖端
        path.lineTo(x + size * 0.6f, y + size * 0.9f); // 右下
        path.lineTo(x + size * 0.25f, y + size * 0.65f); // 内凹
        path.lineTo(x, y + size);               // 底端
        path.close();

        canvas.drawPath(path, shadowPaint);
        canvas.drawPath(path, arrowPaint);
    }

    /**
     * 移动光标
     */
    public void moveCursor(int dx, int dy) {
        cursorX = Math.max(0, Math.min(getWidth(), cursorX + dx));
        cursorY = Math.max(0, Math.min(getHeight(), cursorY + dy));
        invalidate();
    }

    /**
     * 在光标当前位置执行点击（注入 JavaScript）
     */
    public void performClick(WebView webView) {
        // 将屏幕坐标转换为 WebView 内部坐标（考虑缩放和滚动）
        float scale   = webView.getScale();
        float scrollX = webView.getScrollX();
        float scrollY = webView.getScrollY();

        // CursorView 和 WebView 的相对偏移
        int[] cursorLoc  = new int[2];
        int[] webViewLoc = new int[2];
        getLocationOnScreen(cursorLoc);
        webView.getLocationOnScreen(webViewLoc);

        float relX = cursorX + cursorLoc[0] - webViewLoc[0];
        float relY = cursorY + cursorLoc[1] - webViewLoc[1];

        // 计算 CSS 坐标
        float cssX = (relX + scrollX) / scale;
        float cssY = (relY + scrollY) / scale;

        // 注入 JS 点击最近的元素
        String js = String.format(
            "(function() {" +
            "  var el = document.elementFromPoint(%f, %f);" +
            "  if (el) {" +
            "    el.click();" +
            "    el.focus();" +
            "  }" +
            "})();",
            cssX, cssY
        );
        webView.evaluateJavascript(js, null);

        // 同时模拟 touch 事件（增强兼容性）
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(
            now, now, android.view.MotionEvent.ACTION_DOWN, relX, relY, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(
            now, now + 100, android.view.MotionEvent.ACTION_UP, relX, relY, 0);
        webView.dispatchTouchEvent(down);
        webView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    /**
     * 获取当前光标屏幕位置
     */
    public PointF getCursorPosition() {
        return new PointF(cursorX, cursorY);
    }
}
