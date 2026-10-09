package com.ridiitols.gyrolock;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private static final int REQ = 41;
    private PreviewView previewView;
    private TextView status;
    private Button recordButton;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ExecutorService cameraExecutor;
    private SensorManager sensorManager;
    private Sensor gyro;
    private final List<GyroSample> gyroSamples = new ArrayList<>();
    private long gyroStartNs = 0L;
    private long lastSavedNs = 0L;
    private File sessionDir;
    private File pendingRawFile;
    private File pendingCsvFile;

    static class GyroSample {
        final double t, x, y, z;
        GyroSample(double t, double x, double y, double z) {
            this.t=t; this.x=x; this.y=y; this.z=z;
        }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF171717);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(previewView, new LinearLayout.LayoutParams(-1, 0, 1));

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(16, 14, 16, 14);
        status.setText("GyroLock • Siap merekam");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        recordButton = new Button(this);
        recordButton.setText("MULAI REKAM");
        recordButton.setTextSize(16);
        recordButton.setOnClickListener(v -> toggleRecording());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(24, 8, 24, 20);
        root.addView(recordButton, bp);
        setContentView(root);

        if (hasPermissions()) startCamera();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ);
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture);
                status.setText(gyro == null ? "Kamera siap • Sensor gyro tidak tersedia" : "Kamera siap • Gyro tersedia");
            } catch (Exception e) {
                status.setText("Kamera gagal dibuka: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void toggleRecording() {
        // A second tap after recording has finished starts processing the saved video.
        if (recording == null && pendingRawFile != null && pendingCsvFile != null) {
            File raw = pendingRawFile;
            File csv = pendingCsvFile;
            pendingRawFile = null;
            pendingCsvFile = null;
            recordButton.setEnabled(false);
            recordButton.setText("MEMPROSES...");
            stabilize(raw, csv);
            return;
        }

        if (recording != null) {
            recordButton.setEnabled(false);
            recordButton.setText("MENYIMPAN...");
            status.setText("Menghentikan rekaman dan menyimpan video asli...");
            try {
                recording.stop();
            } catch (Exception e) {
                recordButton.setEnabled(true);
                recordButton.setText("BERHENTI & STABILKAN");
                status.setText("Gagal menghentikan rekaman: " + e.getMessage());
            }
            return;
        }

        if (videoCapture == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }

        sessionDir = new File(getExternalFilesDir(null), "GyroLock");
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            status.setText("Tidak bisa membuat folder penyimpanan.");
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File rawFile = new File(sessionDir, "raw_" + stamp + ".mp4");
        gyroSamples.clear();
        gyroStartNs = SystemClock.elapsedRealtimeNanos();
        lastSavedNs = 0L;
        if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);

        FileOutputOptions output = new FileOutputOptions.Builder(rawFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }

        try {
            recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    recordButton.setEnabled(true);
                    recordButton.setText("BERHENTI & STABILKAN");
                    status.setText("Merekam video + data gyro...");
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    recording = null;
                    if (sensorManager != null) sensorManager.unregisterListener(this);
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;

                    if (fin.hasError() || !rawFile.exists() || rawFile.length() == 0) {
                        status.setText("Rekaman gagal disimpan (kode " + fin.getError() + ").");
                        recordButton.setEnabled(true);
                        recordButton.setText("MULAI REKAM");
                        gyroStartNs = 0L;
                        return;
                    }

                    try {
                        File csv = new File(sessionDir, rawFile.getName().replace(".mp4", "_gyro.csv"));
                        writeGyroCsv(csv);
                        pendingRawFile = rawFile;
                        pendingCsvFile = csv;
                        status.setText("Video asli tersimpan. Tekan STABILKAN VIDEO untuk memprosesnya.");
                        recordButton.setEnabled(true);
                        recordButton.setText("STABILKAN VIDEO");
                        gyroStartNs = 0L;
                    } catch (Exception e) {
                        status.setText("Video asli tersimpan, tetapi data gyro gagal: " + e.getMessage());
                        recordButton.setEnabled(true);
                        recordButton.setText("MULAI REKAM");
                        gyroStartNs = 0L;
                    }
                }
            });
        } catch (Exception e) {
            recording = null;
            if (sensorManager != null) sensorManager.unregisterListener(this);
            recordButton.setEnabled(true);
            recordButton.setText("MULAI REKAM");
            status.setText("Gagal memulai rekaman: " + e.getMessage());
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (gyroStartNs == 0L) return;
        long now = event.timestamp;
        if (lastSavedNs != 0L && now - lastSavedNs < 50_000_000L) return; // max ~20 samples/s
        lastSavedNs = now;
        double t = (now - gyroStartNs) / 1_000_000_000.0;
        synchronized (gyroSamples) {
            gyroSamples.add(new GyroSample(t, event.values[0], event.values[1], event.values[2]));
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void writeGyroCsv(File csv) throws IOException {
        try (FileWriter w = new FileWriter(csv)) {
            w.write("t,gyro_x_rad_s,gyro_y_rad_s,gyro_z_rad_s\n");
            synchronized (gyroSamples) {
                for (GyroSample s : gyroSamples) {
                    w.write(String.format(Locale.US, "%.5f,%.7f,%.7f,%.7f\n", s.t, s.x, s.y, s.z));
                }
            }
        }
    }

    // Prototype stabilization: integrate gyro Z (roll-like rotation), smooth the path,
    // then render inverse rotation per timestamp. It is not full 3-axis stabilization.
    private void stabilize(File input, File csv) {
        status.setText("Menyiapkan stabilisasi... video asli tetap disimpan.");
        new Thread(() -> {
            try {
                List<GyroSample> samples;
                synchronized (gyroSamples) {
                    samples = new ArrayList<>(gyroSamples);
                }

                if (samples.size() < 5) {
                    // Devices without a gyroscope (or with too few samples) still get
                    // a saved output using FFmpeg's video-based deshake filter.
                    File out = new File(sessionDir, input.getName().replace("raw_", "stabilized_"));
                    String fallbackCmd = "-y -i " + quote(input.getAbsolutePath())
                            + " -vf \"deshake=rx=16:ry=16:edge=mirror\""
                            + " -c:v mpeg4 -q:v 5 -threads 2 -c:a aac -b:a 96k -movflags +faststart "
                            + quote(out.getAbsolutePath());
                    runOnUiThread(() -> status.setText("Gyro tidak tersedia/cukup; mencoba stabilisasi video otomatis..."));
                    FFmpegKit.executeAsync(fallbackCmd, session -> runOnUiThread(() -> {
                        if (ReturnCode.isSuccess(session.getReturnCode()) && out.exists() && out.length() > 0) {
                            try {
                                publishVideo(out);
                                status.setText("Selesai dengan stabilisasi otomatis! Video tersimpan di Movies/GyroLock dan bisa dibuka dari Galeri/Files. Video asli tetap aman.");
                                Toast.makeText(this, "Video stabil disimpan ke Movies/GyroLock", Toast.LENGTH_LONG).show();
                            } catch (Exception copyError) {
                                status.setText("Stabilisasi selesai, tetapi gagal menyalin video ke Movies/GyroLock: " + copyError.getMessage()
                                        + ". File hasil sementara: " + out.getAbsolutePath());
                            }
                        } else {
                            String detail = session.getReturnCode() == null
                                    ? "FFmpeg tidak mengembalikan kode hasil"
                                    : "Kode FFmpeg: " + session.getReturnCode();
                            status.setText("Stabilisasi otomatis gagal; video asli tetap aman. " + detail);
                        }
                        recordButton.setEnabled(true);
                        recordButton.setText("MULAI REKAM");
                    }));
                    return;
                }

                int n = samples.size();
                double[] angle = new double[n];
                for (int i = 1; i < n; i++) {
                    double dt = Math.max(0, Math.min(0.2, samples.get(i).t - samples.get(i - 1).t));
                    angle[i] = angle[i - 1] + 0.5 * (samples.get(i - 1).z + samples.get(i).z) * dt;
                }

                int radius = 8;
                double[] smooth = new double[n];
                for (int i = 0; i < n; i++) {
                    int a = Math.max(0, i - radius);
                    int b = Math.min(n - 1, i + radius);
                    double sum = 0;
                    for (int j = a; j <= b; j++) sum += angle[j];
                    smooth[i] = sum / (b - a + 1);
                }

                // Keep FFmpeg's expression small to reduce parser/memory pressure on phones.
                List<Integer> picked = new ArrayList<>();
                int maxPoints = Math.min(24, n);
                for (int k = 0; k < maxPoints; k++) {
                    int index = maxPoints == 1 ? 0 : (int) Math.round(k * (n - 1.0) / (maxPoints - 1.0));
                    if (picked.isEmpty() || picked.get(picked.size() - 1) != index) picked.add(index);
                }

                String expr = buildExpression(samples, angle, smooth, picked);
                File out = new File(sessionDir, input.getName().replace("raw_", "stabilized_"));
                String filter = "rotate=angle='" + expr + "':ow=iw:oh=ih:c=black,crop=iw/1.10:ih/1.10,scale=iw*1.10:ih*1.10";
                String cmd = "-y -i " + quote(input.getAbsolutePath())
                        + " -vf \"" + filter + "\" -c:v mpeg4 -q:v 5 -threads 2"
                        + " -c:a aac -b:a 96k -movflags +faststart " + quote(out.getAbsolutePath());

                runOnUiThread(() -> status.setText("Stabilisasi berjalan. Jangan tutup aplikasi..."));
                FFmpegKit.executeAsync(cmd, session -> runOnUiThread(() -> {
                    if (ReturnCode.isSuccess(session.getReturnCode()) && out.exists() && out.length() > 0) {
                        try {
                            publishVideo(out);
                            status.setText("Stabilisasi selesai! Video disimpan di Movies/GyroLock dan tersedia di Galeri/Files. Video asli tetap aman.");
                            Toast.makeText(this, "Video stabil disimpan ke Movies/GyroLock", Toast.LENGTH_LONG).show();
                        } catch (Exception copyError) {
                            status.setText("Stabilisasi selesai, tetapi gagal menyalin video ke Movies/GyroLock: " + copyError.getMessage()
                                    + ". File hasil sementara: " + out.getAbsolutePath());
                        }
                    } else {
                        String detail = session.getFailStackTrace();
                        if (detail == null || detail.trim().isEmpty()) {
                            detail = session.getReturnCode() == null
                                    ? "FFmpeg tidak mengembalikan kode hasil"
                                    : "Kode FFmpeg: " + session.getReturnCode();
                        }
                        status.setText("Stabilisasi gagal; video asli tetap aman. " + detail);
                    }
                    recordButton.setEnabled(true);
                    recordButton.setText("MULAI REKAM");
                }));
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    status.setText("Stabilisasi gagal; video asli tetap aman: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " — " + e.getMessage()));
                    recordButton.setEnabled(true);
                    recordButton.setText("MULAI REKAM");
                });
            }
        }, "GyroLock-Stabilizer").start();
    }

    /**
     * Publishes the processed MP4 into the public Movies/GyroLock folder so it
     * appears in common file managers and gallery apps. The working/original
     * files stay in the app's private external-files directory.
     */
    private Uri publishVideo(File source) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GyroLock");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("Gagal membuat file video di Movies/GyroLock");
            boolean success = false;
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Tidak bisa menulis file video");
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
                success = true;
            } finally {
                if (success) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            }
            return uri;
        } else {
            File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File folder = new File(movies, "GyroLock");
            if (!folder.exists() && !folder.mkdirs()) throw new IOException("Gagal membuat folder Movies/GyroLock");
            File dest = new File(folder, source.getName());
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
            }
            return Uri.fromFile(dest);
        }
    }

    private String buildExpression(List<GyroSample> samples, double[] raw, double[] smooth, List<Integer> idx) {
        if (idx == null || idx.isEmpty()) return "0";
        int last = idx.get(idx.size() - 1);
        String expression = String.format(Locale.US, "%.7f", smooth[last] - raw[last]);

        // Build: if(t<t1, interpolation0, if(t<t2, interpolation1, ... lastValue))
        for (int k = idx.size() - 2; k >= 0; k--) {
            int ia = idx.get(k);
            int ib = idx.get(k + 1);
            double ta = samples.get(ia).t;
            double tb = samples.get(ib).t;
            double ca = smooth[ia] - raw[ia];
            double cb = smooth[ib] - raw[ib];
            double dt = tb - ta;
            if (dt <= 0.0001) continue;
            String interpolation = String.format(Locale.US,
                    "(%.7f+(%.7f)*(t-%.4f)/%.7f)", ca, cb - ca, ta, dt);
            expression = String.format(Locale.US,
                    "if(lt(t\\,%.4f)\\,%s\\,%s)", tb, interpolation, expression);
        }
        return expression;
    }

    private String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ && hasPermissions()) startCamera();
        else if (requestCode == REQ) status.setText("Izin kamera dan mikrofon diperlukan.");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
