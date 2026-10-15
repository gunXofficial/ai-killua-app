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

    private static final String[] WAKE_WORDS = {
        "killua", "hey killua", "halo killua", "hei killua"
    };

    private static final String CHANNEL_ID = "killua_wake";
    private static final int RESTART_DELAY_MS = 50;      // super cepat
    private static final long CAPTURE_TIMEOUT_MS = 8000; // 8 detik max capture

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
                    CHANNEL_ID, "AI Killua Wake Word",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification notif;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notif = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("AI Killua Aktif")
                    .setContentText("Mendengar terus — ucapkan 'Hey Killua'")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .build();
        } else {
            notif = new Notification.Builder(this)
                    .setContentTitle("AI Killua Aktif")
                    .setContentText("Mendengar terus — ucapkan 'Hey Killua'")
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
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
                getPackageName());

        // Mode WAKE: silence pendek (cepat mendeteksi "killua")
        // Mode CAPTURE: silence panjang (tunggu user selesai ngomong)

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override
            public void onError(int error) {
                // Error → langsung restart
                if (isCapturingCommand) {
                    // Kalau error di mode capture & sudah lewat 1 detik, anggap selesai
                    if (System.currentTimeMillis() - captureStartTime > 1000) {
                        isCapturingCommand = false;
                    }
                }
                restartListening(RESTART_DELAY_MS);
            }

            @Override
            public void onResults(Bundle results) {
                processResults(results, true);
                restartListening(RESTART_DELAY_MS);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                processResults(partialResults, false);
            }
        });
    }

    private void applyMode(boolean captureMode) {
        if (captureMode) {
            // Mode CAPTURE: tunggu sampai user selesai ngomong (5 detik diam)
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L);
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L);
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L);
        } else {
            // Mode WAKE: silence pendek, biar cepat detect "killua"
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L);
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L);
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L);
        }
    }

    private void processResults(Bundle results, boolean isFinal) {
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return;

        String bestText = matches.get(0);
        String lower = bestText.toLowerCase(Locale.ROOT);

        // ==== MODE CAPTURE ====
        if (isCapturingCommand) {
            // Cek timeout total
            if (System.currentTimeMillis() - captureStartTime > CAPTURE_TIMEOUT_MS) {
                isCapturingCommand = false;
                return;
            }

            if (isFinal) {
                // Kirim ke server
                String cleaned = bestText;

                // Buang kata wake word dari awal kalimat (kalau ada)
                for (String wake : WAKE_WORDS) {
                    if (cleaned.toLowerCase().startsWith(wake)) {
                        cleaned = cleaned.substring(wake.length()).trim();
                        break;
                    }
                }

                if (cleaned.length() > 1) {
                    sendToServer("/voice", cleaned);
                }
                isCapturingCommand = false;
            }
            return;
        }

        // ==== MODE WAKE ====
        for (String wake : WAKE_WORDS) {
            if (lower.contains(wake)) {
                long now = System.currentTimeMillis();
                if (now - lastTrigger < 1500) return;
                lastTrigger = now;

                Log.d("WakeWord", "Wake: " + bestText);

                // Kalau wake word berdampingan dengan perintah → langsung proses
                String afterWake = "";
                int idx = lower.indexOf(wake);
                if (idx >= 0) {
                    afterWake = bestText.substring(idx + wake.length()).trim();
                }

                if (afterWake.length() > 2) {
                    // User langsung ucapkan perintah: "Hey Killua buka WhatsApp"
                    sendToServer("/voice", afterWake);
                } else {
                    // User hanya ucapkan wake word → masuk mode capture
                    isCapturingCommand = true;
                    captureStartTime = System.currentTimeMillis();
                    applyMode(true);

                    // Bunyi feedback singkat
                    sendToServer("/wake", bestText);
                }
                return;
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
            // Set mode sesuai state
            applyMode(isCapturingCommand);
            isListening = true;
            recognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            isListening = false;
            handler.postDelayed(this::startListening, 500);
        }
    }

    private void restartListening(long delayMs) {
        isListening = false;
        // Cek: kalau mode capture sudah lewat timeout → balik ke mode wake
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
