package com.aikillua.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class OverlayService extends Service {
    private WindowManager wm;
    private WebView webView;
    private WindowManager.LayoutParams charParams;
    private Handler handler;
    private long lastTap = 0;
    private long downTime = 0;
    private boolean dragArmed = false;
    private Runnable longPressRunnable;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        addCharacter();
        autoStartWakeWord();
    }

    private void addCharacter() {
        charParams = new WindowManager.LayoutParams(
                300, 500,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        charParams.gravity = Gravity.BOTTOM | Gravity.END;
        charParams.x = 0;
        charParams.y = 0;

        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        webView.loadUrl("http://127.0.0.1:8080/karakter");

        webView.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy;
            float tx, ty;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        ix = charParams.x;
                        iy = charParams.y;
                        tx = e.getRawX();
                        ty = e.getRawY();
                        downTime = System.currentTimeMillis();
                        dragArmed = false;

                        longPressRunnable = new Runnable() {
                            @Override
                            public void run() {
                                dragArmed = true;
                                webView.setAlpha(0.7f);
                            }
                        };
                        handler.postDelayed(longPressRunnable, 500);
                        return true;
                    }

                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - tx;
                        float dy = e.getRawY() - ty;
                        float dist = (float) Math.sqrt(dx*dx + dy*dy);

                        if (!dragArmed && dist > 15) {
                            handler.removeCallbacks(longPressRunnable);
                            return true;
                        }

                        if (dragArmed) {
                            charParams.x = Math.max(-100, ix - (int)dx);
                            charParams.y = Math.max(-100, iy - (int)dy);
                            wm.updateViewLayout(webView, charParams);
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        handler.removeCallbacks(longPressRunnable);
                        webView.setAlpha(1.0f);

                        long dur = System.currentTimeMillis() - downTime;
                        float dx = e.getRawX() - tx;
                        float dy = e.getRawY() - ty;
                        float dist = (float) Math.sqrt(dx*dx + dy*dy);

                        if (dragArmed) {
                            dragArmed = false;
                            return true;
                        }

                        if (dist < 20 && dur < 500) {
                            long now = System.currentTimeMillis();
                            if (now - lastTap < 450) {
                                Intent i = new Intent(OverlayService.this, MainActivity.class);
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                                lastTap = 0;
                            } else {
                                lastTap = now;
                            }
                        }
                        return true;
                    }
                }
                return false;
            }
        });

        wm.addView(webView, charParams);
    }

    private void autoStartWakeWord() {
        handler.postDelayed(() -> {
            try {
                Intent i = new Intent(OverlayService.this, WakeWordService.class);
                startForegroundService(i);
            } catch (Exception e) {}
        }, 2000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (webView != null) wm.removeView(webView);
        try {
            stopService(new Intent(this, WakeWordService.class));
        } catch (Exception e) {}
    }
}
