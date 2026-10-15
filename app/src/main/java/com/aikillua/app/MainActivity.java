package com.aikillua.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 99;
    private static final int REQ_PICK_IMAGE = 200;
    private WebView webView;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean needMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED;
            boolean needNotif = Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED;
            if (needMic || needNotif) {
                requestPermissions(new String[]{
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.POST_NOTIFICATIONS
                }, REQ_PERMS);
            }
        }

        FrameLayout root = findViewById(R.id.root);
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("http")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                } catch (Exception e) { return false; }
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.loadUrl("http://127.0.0.1:8080/");
        root.addView(webView);
    }

    class AndroidBridge {
        @JavascriptInterface
        public void startOverlay() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && !Settings.canDrawOverlays(MainActivity.this)) {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    toast("Izinkan overlay dulu");
                    return;
                }
                startService(new Intent(MainActivity.this, OverlayService.class));
                toast("Killua aktif");
            });
        }

        @JavascriptInterface
        public void stopOverlay() {
            runOnUiThread(() -> {
                stopService(new Intent(MainActivity.this, OverlayService.class));
                toast("Killua OFF");
            });
        }

        @JavascriptInterface
        public void startWakeWord() {
            runOnUiThread(() -> {
                try {
                    startForegroundService(new Intent(
                            MainActivity.this, WakeWordService.class));
                    toast("Mendengar terus...");
                } catch (Exception e) {
                    toast("Error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void stopWakeWord() {
            runOnUiThread(() -> {
                stopService(new Intent(MainActivity.this, WakeWordService.class));
                toast("Wake word OFF");
            });
        }

        @JavascriptInterface
        public void pickCharacter() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_PICK,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                i.setType("image/*");
                startActivityForResult(i, REQ_PICK_IMAGE);
            });
        }

        @JavascriptInterface
        public void openDashboard() {
            runOnUiThread(() -> webView.loadUrl("http://127.0.0.1:8080/"));
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> finish());
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_IMAGE && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String js = "javascript:handleApkPickedImage('" + uri.toString() + "')";
                webView.evaluateJavascript(js, null);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
                      }
