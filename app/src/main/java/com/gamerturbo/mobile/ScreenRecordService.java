package com.gamerturbo.mobile;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.Display;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenRecordService extends Service {
    public static final String ACTION_STOP = "com.gamerturbo.mobile.STOP_SCREEN_RECORDING";
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final String PREFS = "gamer_turbo_native";
    private static final String CHANNEL_ID = "gamer_turbo_recording";
    private static final int NOTIFICATION_ID = 8105;

    private MediaProjection projection;
    private MediaRecorder recorder;
    private VirtualDisplay virtualDisplay;
    private ParcelFileDescriptor outputDescriptor;
    private Uri outputUri;
    private File outputFile;
    private boolean stopping = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRecordingInternal();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (recorder == null) startRecording(intent);
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Gravação Gamer Turbo", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mostra quando a captura de tela está ativa.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPending = PendingIntent.getActivity(this, 8110, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ScreenRecordService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 8111, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Gamer Turbo está gravando")
                .setContentText("Toque em Parar para encerrar e salvar o vídeo.")
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Parar gravação", stopPending).build())
                .build();
    }

    private void startRecording(Intent intent) {
        if (intent == null) {
            stopSelf();
            return;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        else resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf();
            return;
        }

        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("MediaProjection indisponível");
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("Permissão de captura inválida");

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            display.getRealMetrics(metrics);
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            float scale = Math.min(1f, 1920f / Math.max(width, height));
            width = even((int) (width * scale));
            height = even((int) (height * scale));
            int density = metrics.densityDpi;

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String name = "GamerTurbo_" + stamp + ".mp4";
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(5_000_000);
            recorder.setOrientationHint(0);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GamerTurbo");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                outputUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (outputUri == null) throw new IllegalStateException("Não foi possível criar o arquivo de vídeo");
                outputDescriptor = getContentResolver().openFileDescriptor(outputUri, "w");
                if (outputDescriptor == null) throw new IllegalStateException("Não foi possível abrir o arquivo de vídeo");
                recorder.setOutputFile(outputDescriptor.getFileDescriptor());
            } else {
                File folder = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (folder == null) throw new IllegalStateException("Pasta de vídeo indisponível");
                if (!folder.exists()) folder.mkdirs();
                outputFile = new File(folder, name);
                recorder.setOutputFile(outputFile.getAbsolutePath());
            }

            recorder.prepare();
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    stopRecordingInternal();
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));
            virtualDisplay = projection.createVirtualDisplay("GamerTurboScreenCapture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
            recorder.start();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("screen_recording", true)
                    .putString("last_recording_uri", outputUri == null ? "" : outputUri.toString())
                    .apply();
        } catch (Exception e) {
            stopRecordingInternal();
            Toast.makeText(this, "Falha ao iniciar a gravação da tela.", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private int even(int value) {
        int safe = Math.max(2, value);
        return safe - (safe % 2);
    }

    private void stopRecordingInternal() {
        if (stopping) return;
        stopping = true;
        boolean saved = false;
        try {
            if (recorder != null) {
                try { recorder.stop(); saved = true; } catch (RuntimeException ignored) { }
                try { recorder.reset(); } catch (Exception ignored) { }
                try { recorder.release(); } catch (Exception ignored) { }
                recorder = null;
            }
            if (virtualDisplay != null) {
                try { virtualDisplay.release(); } catch (Exception ignored) { }
                virtualDisplay = null;
            }
            if (projection != null) {
                try { projection.stop(); } catch (Exception ignored) { }
                projection = null;
            }
            if (outputDescriptor != null) {
                try { outputDescriptor.close(); } catch (Exception ignored) { }
                outputDescriptor = null;
            }
            if (outputUri != null) {
                if (saved && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outputUri, values, null, null);
                } else if (!saved) {
                    try { getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) { }
                }
            }
            if (outputFile != null && !saved) outputFile.delete();
        } catch (Exception ignored) {
        } finally {
            SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("screen_recording", false);
            if (saved) editor.putString("last_recording_uri", outputUri != null ? outputUri.toString() : "");
            editor.apply();
            try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onDestroy() {
        stopRecordingInternal();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
