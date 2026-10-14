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
    private long lastTrigger = 0;

    // Kata pemicu — bisa ditambah
    private static final String[] WAKE_WORDS = {
        "killua", "hey killua", "halo killua", "hei killua"
    };

    private static final String CHANNEL_ID = "killua_wake";

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
        Notification notif = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notif = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("AI Killua")
                    .setContentText("Mendengar 'Hey Killua'...")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build();
        } else {
            notif = new Notification.Builder(this)
                    .setContentTitle("AI Killua")
                    .setContentText("Mendengar 'Hey Killua'...")
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

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int eventType, Bundle params) {}

            @Override
            public void onError(int error) {
                restartListening(800);
            }

            @Override
            public void onResults(Bundle results) {
                processResults(results);
                restartListening(400);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                processResults(partialResults);
            }
        });
    }

    private void processResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) return;
        for (String text : matches) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String wake : WAKE_WORDS) {
                if (lower.contains(wake)) {
                    triggerWakeWord(text);
                    return;
                }
            }
        }
    }

    private void triggerWakeWord(String heard) {
        long now = System.currentTimeMillis();
        if (now - lastTrigger < 3000) return; // debounce 3 detik
        lastTrigger = now;
        Log.d("WakeWord", "Triggered: " + heard);

        new Thread(() -> {
            try {
                URL url = new URL("http://127.0.0.1:8080/wake");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(3000);
                OutputStream os = c.getOutputStream();
                os.write(("{\"heard\":\"" + heard.replace("\"","\\\"") + "\"}")
                        .getBytes("UTF-8"));
                os.close();
                c.getResponseCode();
            } catch (Exception e) {
                Log.e("WakeWord", "trigger error", e);
            }
        }).start();
    }

    private void startListening() {
        if (isListening) return;
        try {
            isListening = true;
            recognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            isListening = false;
            handler.postDelayed(this::startListening, 2000);
        }
    }

    private void restartListening(long delayMs) {
        isListening = false;
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
