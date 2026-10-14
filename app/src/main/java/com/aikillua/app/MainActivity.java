package com.aikillua.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_VOICE = 100;
    private EditText inputTask;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        inputTask = findViewById(R.id.inputTask);
        statusText = findViewById(R.id.statusText);

        findViewById(R.id.btnStartOverlay).setOnClickListener(v -> startOverlay());
        findViewById(R.id.btnStopOverlay).setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            statusText.setText("Killua OFF");
        });
        findViewById(R.id.btnRunTask).setOnClickListener(v -> runTask());
        findViewById(R.id.btnVoice).setOnClickListener(v -> startVoice());
        findViewById(R.id.btnOpenDashboard).setOnClickListener(v ->
            startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("http://127.0.0.1:8080"))));

        findViewById(R.id.btnStartWake).setOnClickListener(v -> startWakeWord());
        findViewById(R.id.btnStopWake).setOnClickListener(v -> {
            stopService(new Intent(this, WakeWordService.class));
            statusText.setText("Wake word OFF");
        });
    }

    private void startOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Izinkan overlay dulu", Toast.LENGTH_LONG).show();
            return;
        }
        startService(new Intent(this, OverlayService.class));
        statusText.setText("Killua ON");
    }

    private void startWakeWord() {
        try {
            startForegroundService(new Intent(this, WakeWordService.class));
            statusText.setText("Mendengar 'Hey Killua'...");
            Toast.makeText(this, "Ucapkan 'Hey Killua'", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            statusText.setText("Error: " + e.getMessage());
        }
    }

    private void runTask() {
        String t = inputTask.getText().toString().trim();
        if (!t.isEmpty()) sendTask(t);
    }

    private void sendTask(final String task) {
        new Thread(() -> {
            try {
                URL url = new URL("http://127.0.0.1:8080/start");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(5000);
                OutputStream os = c.getOutputStream();
                os.write(("{\"task\":\"" + task.replace("\"", "\\\"") + "\"}")
                        .getBytes("UTF-8"));
                os.close();
                final int code = c.getResponseCode();
                runOnUiThread(() -> statusText.setText("OK " + code + ": " + task));
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> statusText.setText("Error: " + msg));
            }
        }).start();
    }

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ucapkan perintah...");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "Voice tidak tersedia", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOICE && res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) {
                inputTask.setText(r.get(0));
                sendTask(r.get(0));
            }
        }
    }
}
