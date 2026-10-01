package com.fkp2.keyboard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

public class MouseAccessibilityService extends AccessibilityService {
    private static MouseAccessibilityService instance;
    private WindowManager wm;
    private TextView cursor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float x = -1, y = -1;
    private int screenW, screenH;
    private int cursorSize;
    private boolean screenshotBusy = false;

    public static MouseAccessibilityService getInstance() { return instance; }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        cursorSize = Math.max(32, Math.round(42 * dm.density));
        showCursor();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    private void showCursor() {
        if (cursor != null || wm == null) return;
        cursor = new TextView(this);
        cursor.setText("➤");
        cursor.setTextSize(28);
        cursor.setGravity(Gravity.CENTER);
        cursor.setTextColor(Color.WHITE);
        cursor.setBackground(background(Color.BLACK, Color.WHITE));
        cursor.setElevation(20f);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            cursorSize, cursorSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        x = Math.max(0, screenW / 2f - cursorSize / 2f);
        y = Math.max(0, screenH / 2f - cursorSize / 2f);
        lp.x = Math.round(x);
        lp.y = Math.round(y);
        cursor.setTag(lp);
        wm.addView(cursor, lp);
        updateCursorAppearance(Color.BLACK);
    }

    private GradientDrawable background(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setStroke(Math.max(2, Math.round(2 * getResources().getDisplayMetrics().density)), stroke);
        g.setCornerRadius(8f);
        return g;
    }

    private void updateCursorAppearance(int bg) {
        if (cursor == null) return;
        int c = AdaptiveMouseCursor.chooseCursorColor(bg);
        int outline = AdaptiveMouseCursor.chooseOutlineColor(c);
        cursor.setTextColor(c);
        cursor.setBackground(background(c, outline));
    }

    public static void moveRelativeFromKeyboard(float dx, float dy) {
        MouseAccessibilityService s = instance;
        if (s != null) s.moveRelative(dx, dy);
    }

    public static void clickFromKeyboard(boolean right) {
        MouseAccessibilityService s = instance;
        if (s != null) s.click(right);
    }

    public static void resetFromKeyboard() {
        MouseAccessibilityService s = instance;
        if (s != null) s.resetCursor();
    }

    private void moveRelative(float dx, float dy) {
        if (cursor == null) showCursor();
        float maxX = Math.max(0, screenW - cursorSize);
        float maxY = Math.max(0, screenH - cursorSize);
        x = Math.max(0, Math.min(maxX, x + dx));
        y = Math.max(0, Math.min(maxY, y + dy));
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams)cursor.getTag();
        lp.x = Math.round(x);
        lp.y = Math.round(y);
        wm.updateViewLayout(cursor, lp);
        sampleUnderCursor();
    }

    private void resetCursor() {
        x = Math.max(0, screenW / 2f - cursorSize / 2f);
        y = Math.max(0, screenH / 2f - cursorSize / 2f);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams)cursor.getTag();
        lp.x = Math.round(x); lp.y = Math.round(y);
        wm.updateViewLayout(cursor, lp);
        sampleUnderCursor();
    }

    private void click(boolean right) {
        if (Build.VERSION.SDK_INT < 24) return;
        float cx = x + cursorSize / 2f;
        float cy = y + cursorSize / 2f;
        Path p = new Path();
        p.moveTo(cx, cy);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(p, 0, 45);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        // Android accessibility gesture dispatch is a screen tap; the right/left
        // distinction is not exposed by dispatchGesture. The right flag is retained
        // so the keyboard UI can expose both controls without pretending to inject
        // an unsupported mouse-button event.
    }

    private void sampleUnderCursor() {
        if (Build.VERSION.SDK_INT < 30 || screenshotBusy) return;
        screenshotBusy = true;
        takeScreenshot(0, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                try {
                    android.hardware.HardwareBuffer hb = result.getHardwareBuffer();
                    android.graphics.ColorSpace cs = result.getColorSpace();
                    Bitmap b = null;
                    if (hb != null) {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                        if (hw != null) {
                            b = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                        }
                        hb.close();
                    }
                    if (b != null) {
                        int px = Math.max(0, Math.min(b.getWidth()-1, Math.round(x + cursorSize/2f)));
                        int py = Math.max(0, Math.min(b.getHeight()-1, Math.round(y + cursorSize/2f)));
                        updateCursorAppearance(b.getPixel(px, py));
                        b.recycle();
                    }
                } finally {
                    screenshotBusy = false;
                }
            }
            @Override public void onFailure(int errorCode) {
                screenshotBusy = false;
            }
        });
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        instance = null;
        if (cursor != null && wm != null) {
            try { wm.removeView(cursor); } catch (Exception ignored) {}
        }
        cursor = null;
        super.onDestroy();
    }
}
