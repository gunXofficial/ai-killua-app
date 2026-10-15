package com.aikillua.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class WakeWordService extends Service {
    private static final String CHANNEL_ID = "killua_wake";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_SECONDS = 2;
    private static final int CHUNK_SIZE = SAMPLE_RATE * CHUNK_SECONDS * 2;

    private AudioRecord audioRecord;
    private AutomaticGainControl agc;
    private NoiseSuppressor ns;
    private Thread recordThread;
    private volatile boolean isRunning = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundSafely();
        startRecording();
    }

    private void startForegroundSafely() {
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
                    .setContentText("Mendengar terus — bicara bebas")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .build();
        } else {
            notif = new Notification.Builder(this)
                    .setContentTitle("AI Killua Aktif")
                    .setContentText("Mendengar terus — bicara bebas")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build();
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(1, notif);
            }
        } catch (Exception e) {
            Log.e("Killua", "startForeground err", e);
        }
    }

    private void startRecording() {
        if (isRunning) return;
        isRunning = true;

        recordThread = new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                    Log.e("Killua", "No mic permission");
                    return;
                }

                int minBuf = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                int bufferSize = Math.max(minBuf, CHUNK_SIZE * 2);

                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.w("Killua", "VOICE_RECOGNITION failed, trying MIC");
                    if (audioRecord != null) audioRecord.release();
                    audioRecord = new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize);
                }

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e("Killua", "AudioRecord init failed");
                    return;
                }

                audioRecord.startRecording();
                Log.d("Killua", "Recording started");

                try {
                    if (AutomaticGainControl.isAvailable()) {
                        agc = AutomaticGainControl.create(audioRecord.getAudioSessionId());
                        if (agc != null) agc.setEnabled(true);
                    }
                } catch (Exception e) {}

                try {
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(audioRecord.getAudioSessionId());
                        if (ns != null) ns.setEnabled(true);
                    }
                } catch (Exception e) {}

                byte[] chunk = new byte[CHUNK_SIZE];
                int offset = 0;

                while (isRunning) {
                    int read = audioRecord.read(chunk, offset, CHUNK_SIZE - offset);
                    if (read <= 0) {
                        Thread.sleep(30);
                        continue;
                    }
                    offset += read;

                    if (offset >= CHUNK_SIZE) {
                        double rms = calcRms(chunk, offset);
                        final byte[] toSend = chunk.clone();
                        final int rmsValue = (int) rms;
                        Log.d("Killua", "SEND RMS=" + rmsValue);
                        new Thread(() -> sendChunk(toSend, rmsValue)).start();

                        offset = 0;
                        chunk = new byte[CHUNK_SIZE];
                    }
                }

                if (agc != null) agc.release();
                if (ns != null) ns.release();
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e("Killua", "rec err", e);
            }
        }, "KilluaRecorder");
        recordThread.start();
    }

    private double calcRms(byte[] pcm, int len) {
        long sum = 0;
        int count = len / 2;
        for (int i = 0; i < len - 1; i += 2) {
            short s = (short) ((pcm[i+1] << 8) | (pcm[i] & 0xFF));
            sum += (long) s * s;
        }
        if (count == 0) return 0;
        return Math.sqrt(sum / (double) count);
    }

    private void sendChunk(byte[] pcmData, int rms) {
        try {
            URL url = new URL("http://127.0.0.1:8080/transcribe");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setRequestProperty("X-Audio-RMS", String.valueOf(rms));
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(30000);
            OutputStream os = c.getOutputStream();
            os.write(pcmData);
            os.close();
            int code = c.getResponseCode();
            Log.d("Killua", "Response: " + code);
        } catch (Exception e) {
            Log.e("Killua", "send err", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (agc != null) agc.release();
        if (ns != null) ns.release();
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {}
        }
        if (recordThread != null) recordThread.interrupt();
    }
                            }
