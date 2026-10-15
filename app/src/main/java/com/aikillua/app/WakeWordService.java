package com.aikillua.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WakeWordService extends Service {
    private static final String CHANNEL_ID = "killua_wake";
    private static final String MODEL_DIR = "vosk-model-small-id-0.22";
    private static final String WAKE_WORD = "killua";

    private Model model;
    private SpeechService speechService;
    private Handler handler;
    private boolean isCapturingCommand = false;
    private long lastTrigger = 0;
    private long captureStartTime = 0;
    private String lastCommandText = "";
    private static final long CAPTURE_TIMEOUT_MS = 8000;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground();
        handler = new Handler(Looper.getMainLooper());
        LibVosk.setLogLevel(LogLevel.WARNINGS);

        // Cek & download model kalau belum ada
        new Thread(this::ensureModel).start();
    }

    private void startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "AI Killua",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification notif;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notif = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("AI Killua Aktif")
                    .setContentText("Mendengar terus...")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .build();
        } else {
            notif = new Notification.Builder(this)
                    .setContentTitle("AI Killua Aktif")
                    .setContentText("Mendengar terus...")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build();
        }
        startForeground(1, notif);
    }

    // ============================================================
    // 📥 MODEL MANAGEMENT
    // ============================================================
    private void ensureModel() {
        File modelDir = new File(getFilesDir(), MODEL_DIR);

        if (!modelDir.exists()) {
            Log.d("Vosk", "Model belum ada, download...");
            boolean ok = downloadAndUnzipModel(modelDir);
            if (!ok) {
                Log.e("Vosk", "Download model gagal");
                return;
            }
        }
        loadModel(modelDir);
    }

    private boolean downloadAndUnzipModel(File targetDir) {
        String url = "https://alphacephei.com/vosk/models/vosk-model-small-id-0.22.zip";
        File zipFile = new File(getCacheDir(), "model.zip");
        try {
            URL u = new URL(url);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.connect();

            if (c.getResponseCode() != 200) return false;

            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(zipFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();

            // Unzip
            ZipInputStream zis = new ZipInputStream(
                    new java.io.FileInputStream(zipFile));
            ZipEntry ze;
            byte[] buffer = new byte[8192];
            while ((ze = zis.getNextEntry()) != null) {
                File f = new File(getFilesDir(), ze.getName());
                if (ze.isDirectory()) {
                    f.mkdirs();
                } else {
                    f.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(f);
                    int count;
                    while ((count = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, count);
                    }
                    fos.close();
                }
                zis.closeEntry();
            }
            zis.close();
            zipFile.delete();
            return true;
        } catch (Exception e) {
            Log.e("Vosk", "download err", e);
            return false;
        }
    }

    private void loadModel(File modelDir) {
        try {
            model = new Model(modelDir.getAbsolutePath());
            handler.post(this::startRecognition);
            Log.d("Vosk", "Model loaded");
        } catch (Exception e) {
            Log.e("Vosk", "load err", e);
        }
    }

    // ============================================================
    // 🎤 VOSK CONTINUOUS RECOGNITION
    // ============================================================
    private void startRecognition() {
        try {
            Recognizer rec = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(rec, 16000.0f);
            speechService.startListening(new RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                    try {
                        JSONObject obj = new JSONObject(hypothesis);
                        if (obj.has("partial")) {
                            String partial = obj.getString("partial")
                                    .toLowerCase().trim();
                            handlePartial(partial);
                        }
                    } catch (Exception e) {}
                }

                @Override
                public void onResult(String hypothesis) {
                    try {
                        JSONObject obj = new JSONObject(hypothesis);
                        if (obj.has("text")) {
                            String text = obj.getString("text").trim();
                            if (!text.isEmpty()) handleFinal(text);
                        }
                    } catch (Exception e) {}
                }

                @Override public void onFinalResult(String hypothesis) {}
                @Override public void onError(Exception e) {
                    Log.e("Vosk", "err", e);
                }
                @Override public void onTimeout() {
                    // Vosk terus jalan, tidak timeout
                }
            });
            Log.d("Vosk", "Listening started (continuous)");
        } catch (Exception e) {
            Log.e("Vosk", "start err", e);
        }
    }

    private void handlePartial(String partial) {
        // Update command text yang sedang didengar
        if (isCapturingCommand) {
            if (partial.length() > 2) {
                lastCommandText = partial;
                Log.d("Vosk", "Partial: " + partial);
            }
        }
    }

    private void handleFinal(String text) {
        String lower = text.toLowerCase();

        // ==== MODE CAPTURE: kirim yang diucapkan ====
        if (isCapturingCommand) {
            String cleaned = text;
            if (cleaned.toLowerCase().startsWith(WAKE_WORD)) {
                cleaned = cleaned.substring(WAKE_WORD.length()).trim();
            }
            if (cleaned.length() > 1) {
                Log.d("Vosk", "Sending: " + cleaned);
                sendToServer("/voice", cleaned);
            }
            isCapturingCommand = false;
            lastCommandText = "";
            return;
        }

        // ==== MODE WAKE: cari "killua" ====
        if (lower.contains(WAKE_WORD)) {
            long now = System.currentTimeMillis();
            if (now - lastTrigger < 2000) return;
            lastTrigger = now;

            int idx = lower.indexOf(WAKE_WORD);
            String afterWake = text.substring(idx + WAKE_WORD.length()).trim();

            if (afterWake.length() > 2) {
                sendToServer("/voice", afterWake);
            } else {
                isCapturingCommand = true;
                captureStartTime = System.currentTimeMillis();
                sendToServer("/wake", text);
                // Auto-timeout
                handler.postDelayed(() -> {
                    if (isCapturingCommand
                            && System.currentTimeMillis() - captureStartTime >= CAPTURE_TIMEOUT_MS) {
                        isCapturingCommand = false;
                    }
                }, CAPTURE_TIMEOUT_MS);
            }
        }
    }

    private void sendToServer(String endpoint, String text) {
        final String ep = endpoint;
        final String msg = text;
        new Thread(() -> {
            try {
                URL url = new URL("http://127.0.0.1:8080" + ep);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(3000);
                OutputStream os = c.getOutputStream();
                String key = ep.equals("/wake") ? "heard" : "task";
                os.write(("{\"" + key + "\":\"" + msg.replace("\"","\\\"") + "\"}")
                        .getBytes("UTF-8"));
                os.close();
                c.getResponseCode();
            } catch (Exception e) {
                Log.e("Vosk", "send err", e);
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }
        if (model != null) model.close();
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }
    }
