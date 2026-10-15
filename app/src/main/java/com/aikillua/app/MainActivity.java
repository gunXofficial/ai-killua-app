package com.aikillua.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.util.Base64;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_VOICE = 100;
    private static final int REQ_PERMS = 99;
    private static final int REQ_PICK_IMAGE = 200;
    private EditText inputTask;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        inputTask = findViewById(R.id.inputTask);
        statusText = findViewById(R.id.statusText);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.POST_NOTIFICATIONS
            }, REQ_PERMS);
        }

        findViewById(R.id.btnStartOverlay).setOnClickListener(v -> startOverlay());
        findViewById(R.id.btnStopOverlay).setOnClickListener(v -> stopEverything());
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

        findViewById(R.id.btnPickChar).setOnClickListener(v -> pickCharacter());
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
        statusText.setText("Killua + Wake Word aktif");
    }

    private void stopEverything() {
        stopService(new Intent(this, OverlayService.class));
        stopService(new Intent(this, WakeWordService.class));
        statusText.setText("Semua mati");
    }

    private void startWakeWord() {
        try {
            startForegroundService(new Intent(this, WakeWordService.class));
            statusText.setText("Mendengar 'Killua'...");
        } catch (Exception e) {
            statusText.setText("Error: " + e.getMessage());
        }
    }

    private void pickCharacter() {
        Intent i = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        i.setType("image/*");
        startActivityForResult(i, REQ_PICK_IMAGE);
    }

    private String getPathFromUri(Uri uri) {
        try {
            String[] proj = {MediaStore.Images.Media.DATA};
            Cursor c = getContentResolver().query(uri, proj, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(proj[0]);
                String p = c.getString(idx);
                c.close();
                return p;
            }
        } catch (Exception e) {}
        return null;
    }

    private void uploadCharacter(final Uri uri) {
        statusText.setText("Upload karakter...");
        new Thread(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                byte[] data = bos.toByteArray();
                String b64 = Base64.encodeToString(data, Base64.NO_WRAP);

                URL url = new URL("http://127.0.0.1:8080/upload_character");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setConnectTimeout(10000);
                OutputStream os = c.getOutputStream();
                os.write(("{\"image\":\"" + b64 + "\"}").getBytes("UTF-8"));
                os.close();
                final int code = c.getResponseCode();
                runOnUiThread(() -> statusText.setText("Upload OK " + code));
            } catch (Exception e) {
                final String m = e.getMessage();
                runOnUiThread(() -> statusText.setText("Upload error: " + m));
            }
        }).start();
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
        if (req == REQ_PICK_IMAGE && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) uploadCharacter(uri);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERMS) {
            boolean granted = true;
            for (int r : res) {
                if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            statusText.setText(granted ? "Izin OK" : "Izin ditolak");
        }
    }
                              }
