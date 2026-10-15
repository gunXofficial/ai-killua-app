package com.aikillua.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;

public class OverlayService extends Service {
    private WindowManager wm;
    private WebView webView;
    private ImageView bubble;
    private WindowManager.LayoutParams charParams, bubbleParams;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addCharacter();
        addBubble();
    }

    private void addCharacter() {
        // Karakter: NOT_TOUCHABLE → tap tembus ke app belakang
        charParams = new WindowManager.LayoutParams(
                280, 400,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        charParams.gravity = Gravity.BOTTOM | Gravity.END;
        charParams.x = 0;
        charParams.y = 0;

        webView = new WebView(this);
        // Transparan penuh
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        webView.loadUrl("http://127.0.0.1:8080/karakter");

        wm.addView(webView, charParams);
    }

    private void addBubble() {
        bubbleParams = new WindowManager.LayoutParams(
                120, 120,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 20;
        bubbleParams.y = 400;

        bubble = new ImageView(this);
        bubble.setImageResource(android.R.drawable.ic_btn_speak_now);
        bubble.setBackgroundColor(0xCC1a0d3a);
        bubble.setPadding(28, 28, 28, 28);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy;
            float tx, ty;
            boolean moved;
            long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = bubbleParams.x;
                        iy = bubbleParams.y;
                        tx = e.getRawX();
                        ty = e.getRawY();
                        moved = false;
                        downTime = System.currentTimeMillis();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(e.getRawX() - tx);
                        int dy = (int)(e.getRawY() - ty);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                        bubbleParams.x = ix + dx;
                        bubbleParams.y = iy + dy;
                        wm.updateViewLayout(bubble, bubbleParams);
                        return true;

                    case MotionEvent.ACTION_UP:
                        long dur = System.currentTimeMillis() - downTime;
                        if (!moved) {
                            if (dur > 600) {
                                // Long press → toggle karakter
                                toggleCharacter();
                            } else {
                                // Tap → buka app
                                Intent i = new Intent(OverlayService.this, MainActivity.class);
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(i);
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        wm.addView(bubble, bubbleParams);
    }

    private void toggleCharacter() {
        if (webView.getVisibility() == View.VISIBLE) {
            webView.setVisibility(View.GONE);
        } else {
            webView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webView != null) wm.removeView(webView);
        if (bubble != null) wm.removeView(bubble);
    }
}
