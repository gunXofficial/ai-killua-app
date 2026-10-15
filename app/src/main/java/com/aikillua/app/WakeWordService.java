package com.aikillua.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

public class WakeWordService extends Service {
    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private Handler handler;
    private boolean isListening = false;
    private boolean isCapturingCommand = false;
    private long lastTrigger = 0;
    private long captureStartTime = 0;

    // Cukup 1 wake word — kurang trigger false
    private static final String WAKE_WORD = "killua";
    private static final String CHANNEL_ID = "killua_wake";
    private static final int RESTART_DELAY_MS = 300;      // 300ms (jangan 50ms)
    private static final long CAPTURE_TIMEOUT_MS = 10000; // 10 detik max capture

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground();
        handler = new Handler(Looper.getMainLooper());
        setupRecognizer();
        startListening();
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
                    .setContentText("Mendengar 'Killua' terus...")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .build();
        } else {
            notif = new Notification.Builder(this)
                    .setContentTitle("AI Killua Aktif")
                    .setContentText("Mendengar 'Killua' terus...")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build();
        }
        startForeground(1, notif);
    }

    private void setupRecognizer() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
                getPackageName());

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override
            public void onError(int error) {
                if (isCapturingCommand
                        && System.currentTimeMillis() - captureStartTime > 1500) {
                    isCapturingCommand = false;
                }
                restartListening(RESTART_DELAY_MS);
            }

            @Override
            public void onResults(Bundle results) {
                processResults(results);
                restartListening(RESTART_DELAY_MS);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                // Partial hanya dipakai untuk capture (deteksi selesai ngomong lebih cepat)
                if (isCapturingCommand) {
                    processPartial(partialResults);
                }
            }
        });
    }

    private void applyMode(boolean captureMode) {
        if (captureMode) {
            // Capture: tunggu 2.5 detik diam → user dianggap selesai ngomong
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L);
        } else {
            // Wake: tunggu 2 detik diam → baru onResults
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L);
        }
    }

    private void processPartial(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;
        // Simpan partial text terakhir untuk dikirim kalau final gagal
        String partial = matches.get(0);
        if (partial != null && partial.length() > 1) {
            Log.d("WakeWord", "Partial: " + partial);
        }
    }

    private void processResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;

        String bestText = matches.get(0);
        String lower = bestText.toLowerCase(Locale.ROOT);
        Log.d("WakeWord", "Heard: " + bestText);

        // ==== MODE CAPTURE: kirim perintah ====
        if (isCapturingCommand) {
            if (System.currentTimeMillis() - captureStartTime > CAPTURE_TIMEOUT_MS) {
                isCapturingCommand = false;
                return;
            }

            String cleaned = bestText;
            // Buang kata "killua" dari awal
            if (cleaned.toLowerCase().startsWith(WAKE_WORD)) {
                cleaned = cleaned.substring(WAKE_WORD.length()).trim();
            }

            if (cleaned.length() > 1) {
                sendToServer("/voice", cleaned);
                isCapturingCommand = false;
            }
            return;
        }

        // ==== MODE WAKE: cari "killua" ====
        if (lower.contains(WAKE_WORD)) {
            long now = System.currentTimeMillis();
            if (now - lastTrigger < 2000) return;
            lastTrigger = now;

            // Cek: wake + perintah dalam 1 kalimat?
            int idx = lower.indexOf(WAKE_WORD);
            String afterWake = bestText.substring(idx + WAKE_WORD.length()).trim();

            if (afterWake.length() > 2) {
                // Langsung eksekusi
                sendToServer("/voice", afterWake);
            } else {
                // Cuma wake word → mode capture
                isCapturingCommand = true;
                captureStartTime = System.currentTimeMillis();
                applyMode(true);
                sendToServer("/wake", bestText);
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
                Log.e("WakeWord", "send error", e);
            }
        }).start();
    }

    private void startListening() {
        if (isListening) return;
        try {
            applyMode(isCapturingCommand);
            isListening = true;
            recognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            isListening = false;
            handler.postDelayed(this::startListening, 1000);
        }
    }

    private void restartListening(long delayMs) {
        isListening = false;
        if (isCapturingCommand
                && System.currentTimeMillis() - captureStartTime > CAPTURE_TIMEOUT_MS) {
            isCapturingCommand = false;
        }
        handler.postDelayed(this::startListening, delayMs);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (recognizer != null) {
            recognizer.stopListening();
            recognizer.destroy();
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}
