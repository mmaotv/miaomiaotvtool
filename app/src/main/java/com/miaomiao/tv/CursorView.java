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

    /** 点击回调，外部可监听是否点中了输入框 */
    public interface OnClickCallback {
        /** @param cssX  被点击元素的 CSS x 坐标
         *  @param cssY  被点击元素的 CSS y 坐标
         *  @param webView 当前 WebView */
        void onClicked(float cssX, float cssY, WebView webView);
    }

    private OnClickCallback onClickCallback;

    public void setOnClickCallback(OnClickCallback cb) {
        this.onClickCallback = cb;
    }

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
     * 在光标当前位置执行点击（注入 JavaScript + 模拟 Touch 事件）
     *
     * 坐标说明：
     *   cursorX/Y      —— 相对于 CursorView 自身左上角的像素坐标
     *   屏幕坐标        —— cursorX + cursorLoc[0]（MotionEvent 需要屏幕坐标）
     *   relX/Y         —— 光标相对于 WebView 左上角的像素偏移（用于 touch dispatch）
     *   cssX/Y         —— WebView 视口内的 CSS 坐标（= relX / scale，不加 scrollX）
     *                     elementFromPoint() 接受视口坐标，不需要加滚动量
     */
    public void performClick(WebView webView) {
        if (webView == null) return;
        
        float scale = webView.getScale();
        // 防止除零，确保 scale 有效
        if (scale <= 0) scale = 1.0f;

        // 获取 CursorView 和 WebView 在屏幕上的绝对位置
        int[] cursorLoc  = new int[2];
        int[] webViewLoc = new int[2];
        try {
            getLocationOnScreen(cursorLoc);
            webView.getLocationOnScreen(webViewLoc);
        } catch (Exception e) {
            // 在某些情况下 getLocationOnScreen 可能失败（如视图未attached）
            return;
        }

        // 光标在屏幕上的绝对坐标
        float screenX = cursorX + cursorLoc[0];
        float screenY = cursorY + cursorLoc[1];

        // 光标相对于 WebView 左上角的偏移（用于 touch 事件 dispatch）
        float relX = screenX - webViewLoc[0];
        float relY = screenY - webViewLoc[1];

        // CSS 视口坐标（elementFromPoint 接受视口坐标，不加 scrollX/Y）
        float cssX = relX / scale;
        float cssY = relY / scale;

        // 先通过 touch 事件触发（优先级最高，兼容性最好）
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(
            now, now, android.view.MotionEvent.ACTION_DOWN, screenX, screenY, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(
            now, now + 100, android.view.MotionEvent.ACTION_UP, screenX, screenY, 0);
        webView.dispatchTouchEvent(down);
        webView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();

        // 再注入 JS：触发完整事件链（mousedown → mouseup → click → focus）
        // 同时检测是否是输入框并回调
        // Android 6 兼容：使用 loadUrl("javascript:...") 替代 evaluateJavascript 回调
        final float finalCssX = cssX;
        final float finalCssY = cssY;
        String js = String.format(
            "(function() {" +
            "  var x = %f, y = %f;" +
            "  var el = document.elementFromPoint(x, y);" +
            "  if (!el) return;" +
            "  function fire(el, type) {" +
            "    var ev = new MouseEvent(type, {bubbles:true, cancelable:true, clientX:x, clientY:y});" +
            "    el.dispatchEvent(ev);" +
            "  }" +
            "  fire(el, 'mousedown');" +
            "  fire(el, 'mouseup');" +
            "  fire(el, 'click');" +
            "  el.focus();" +
            "})();",
            cssX, cssY
        );

        // Android 6 兼容：检测输入框使用独立的 JS 调用
        String detectJs = String.format(
            "(function() {" +
            "  var el = document.elementFromPoint(%f, %f);" +
            "  if (!el) return 'none';" +
            "  var tag = el.tagName ? el.tagName.toLowerCase() : '';" +
            "  if (tag === 'input' || tag === 'textarea' || el.isContentEditable) {" +
            "    return 'input';" +
            "  }" +
            "  return 'other';" +
            "})();",
            cssX, cssY
        );

        try {
            // 执行点击事件 JS
            webView.loadUrl("javascript:" + js);
            // 检测输入框
            webView.evaluateJavascript(detectJs, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String result) {
                    if ("\"input\"".equals(result) && onClickCallback != null) {
                        webView.post(() -> {
                            if (onClickCallback != null) {
                                onClickCallback.onClicked(finalCssX, finalCssY, webView);
                            }
                        });
                    }
                }
            });
        } catch (Exception e) {
            // Android 6 兼容：忽略 JS 执行异常
        }
    }

    /**
     * 判断当前光标位置是否悬停在输入框上，并通过回调通知（带 JS 检测结果）
     */
    public void checkAndNotifyInput(WebView webView) {
        float scale = webView.getScale();
        int[] cursorLoc  = new int[2];
        int[] webViewLoc = new int[2];
        try {
            getLocationOnScreen(cursorLoc);
            webView.getLocationOnScreen(webViewLoc);
        } catch (Exception e) {
            return;
        }
        float relX = (cursorX + cursorLoc[0]) - webViewLoc[0];
        float relY = (cursorY + cursorLoc[1]) - webViewLoc[1];
        float cssX = relX / scale;
        float cssY = relY / scale;

        final float finalCssX = cssX;
        final float finalCssY = cssY;
        String js = String.format(
            "(function(){" +
            "  var el=document.elementFromPoint(%f,%f);" +
            "  if(!el) return 'none';" +
            "  var t=el.tagName?el.tagName.toLowerCase():'';" +
            "  if(t==='input'||t==='textarea'||el.isContentEditable) return 'input';" +
            "  return 'other';" +
            "})();",
            cssX, cssY
        );
        try {
            webView.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String result) {
                    if ("\"input\"".equals(result) && onClickCallback != null) {
                        onClickCallback.onClicked(finalCssX, finalCssY, webView);
                    }
                }
            });
        } catch (Exception e) {
            // Android 6 兼容
        }
    }

    /**
     * 获取当前光标屏幕位置
     */
    public PointF getCursorPosition() {
        return new PointF(cursorX, cursorY);
    }

    /**
     * 获取当前光标Y坐标（相对于CursorView左上角）
     */
    public float getCursorY() {
        return cursorY;
    }

    /**
     * 判断光标当前位置是否在指定视图区域内
     * @param view 目标视图
     * @return true 如果光标在视图区域内
     */
    public boolean isOverView(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        try {
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            int[] cursorLoc = new int[2];
            getLocationOnScreen(cursorLoc);
            float screenX = cursorX + cursorLoc[0];
            float screenY = cursorY + cursorLoc[1];
            return screenX >= loc[0] && screenX <= loc[0] + view.getWidth()
                && screenY >= loc[1] && screenY <= loc[1] + view.getHeight();
        } catch (Exception e) {
            // getLocationOnScreen 可能在窗口状态异常时失败，此时保守返回 true
            // 让调用方尝试执行工具栏按钮点击
            return true;
        }
    }

    /**
     * 重置光标到屏幕中心
     */
    public void resetPosition() {
        cursorX = getWidth() > 0 ? getWidth() / 2f : 960f;
        cursorY = getHeight() > 0 ? getHeight() / 2f : 540f;
        invalidate();
    }
}
