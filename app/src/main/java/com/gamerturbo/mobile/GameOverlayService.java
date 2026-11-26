package com.gamerturbo.mobile;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Painel flutuante estilo Game Turbo: uma barra fina na lateral que cresce ao toque.
 * Mostra estatísticas, os 4 modos (ECO / BALANCE / RISE / DIABLO), o estado do Shizuku
 * e ações reais (limpar RAM, fechar apps, prioridade, boost) com resultado conferido.
 */
public class GameOverlayService extends Service {
    private static final String PREFS = "gamer_turbo_native";
    private static final String CHANNEL_ID = "gamer_turbo_overlay";
    private static final int NOTIFICATION_ID = 8101;
    private static final String[] MODES = {"eco", "balance", "rise", "diablo"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View bubbleView, scrimView, panelView, brightnessPanelView, dotView, timerPanelView, whitelistPanelView, cpuPanelView;
    private WindowManager.LayoutParams bubbleParams, panelParams;
    private boolean panelOpen = false;
    private volatile boolean recording = false;
    private volatile boolean busy = false;
    private volatile boolean fpsBusy = false;
    private volatile int lastFps = -1;
    private volatile int lastCpuFrequencyMhz = -1;
    private volatile boolean cpuFrequencyBusy = false;
    private volatile long timerEndAt = 0L;
    private volatile boolean foregroundCheckBusy = false;
    private boolean gameSeenForeground = false;
    private int foregroundMisses = 0;
    private boolean userRequestedStop = false;
    private volatile boolean finishingSession = false;
    private volatile boolean serviceDestroyed = false;
    private TextView batteryValue, ramValue, cpuValue, cpuMetaValue, fpsValue, fpsMetaValue;
    private TextView tempValue, thermalValue, infoLine, resultView, shizukuPill, modeDesc;
    private final TextView[] modeButtons = new TextView[4];
    private String lastResult = "Escolha um modo ou toque em uma ação. O resultado real aparece aqui.";

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            if (panelOpen) {
                updateMetrics();
                requestFpsUpdate();
                handler.postDelayed(this, 1500);
            }
        }
    };
    private final Runnable timerTask = new Runnable() {
        @Override public void run() {
            if (timerEndAt <= 0L) return;
            long left = timerEndAt - System.currentTimeMillis();
            if (left <= 0L) {
                timerEndAt = 0L;
                prefs().edit().remove("floating_timer_end").apply();
                Toast.makeText(GameOverlayService.this, "Tempo de partida encerrado.", Toast.LENGTH_LONG).show();
                return;
            }
            handler.postDelayed(this, Math.min(1000L, left));
        }
    };
    private final Runnable barTask = new Runnable() {
        @Override public void run() {
            if (bubbleView != null) { refreshDot(); handler.postDelayed(this, 3000); }
        }
    };
    private final Runnable foregroundWatchTask = new Runnable() {
        @Override public void run() {
            if (userRequestedStop || serviceDestroyed || finishingSession) return;
            String game = currentGame();
            if (!GameTools.validPackage(game) || !ShizukuShell.isAuthorized() || foregroundCheckBusy) {
                handler.postDelayed(this, 2500);
                return;
            }
            foregroundCheckBusy = true;
            new Thread(() -> {
                String output = "";
                try {
                    ShizukuShell.Result result = ShizukuShell.execute(GameOverlayService.this,
                            "dump=$(dumpsys activity activities 2>/dev/null); "
                                    + "line=$(printf '%s\n' \"$dump\" | grep -m 1 'topResumedActivity='); "
                                    + "[ -n \"$line\" ] || line=$(printf '%s\n' \"$dump\" | grep -m 1 -E 'mResumedActivity:|ResumedActivity:'); "
                                    + "printf '%s\n' \"$line\"");
                    if (result.output != null) output = result.output;
                } catch (Throwable ignored) { }
                String topPackage = extractForegroundPackage(output);
                handler.post(() -> {
                    foregroundCheckBusy = false;
                    if (serviceDestroyed || finishingSession) return;
                    if (game.equals(currentGame())) {
                        if (game.equals(topPackage)) {
                            gameSeenForeground = true;
                            foregroundMisses = 0;
                        } else if (gameSeenForeground && GameTools.validPackage(topPackage)
                                && !"com.android.systemui".equals(topPackage)
                                && !"com.android.permissioncontroller".equals(topPackage)
                                && !"com.google.android.permissioncontroller".equals(topPackage)
                                && !GameTools.SHIZUKU_PACKAGE.equals(topPackage)) {
                            foregroundMisses++;
                            if (foregroundMisses >= 3) {
                                handler.removeCallbacks(this);
                                finishGameSession();
                                return;
                            }
                        }
                    }
                    handler.postDelayed(this, 2500);
                });
            }, "GamerTurbo-ForegroundWatch").start();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForegroundCompat();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) { stopSelf(); return; }
        showBubble();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("overlay_enabled", true).apply();
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getLong("game_session_started", 0L) == 0L) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("game_session_started", System.currentTimeMillis()).apply();
        }
        timerEndAt = prefs().getLong("floating_timer_end", 0L);
        if (timerEndAt > System.currentTimeMillis()) handler.post(timerTask);
        handler.postDelayed(foregroundWatchTask, 2500);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if ("STOP_OVERLAY".equals(action)) {
            userRequestedStop = true;
            prefs().edit().putBoolean("overlay_enabled", false).apply();
            finishGameSession();
            return START_NOT_STICKY;
        }
        if ("STOP_GAME_SESSION".equals(action) || "STOP_APP_EXIT".equals(action)) {
            finishGameSession();
            return START_NOT_STICKY;
        }
        if (bubbleView == null && !panelOpen) showBubble();
        return START_NOT_STICKY;
    }

    private static String extractForegroundPackage(String output) {
        if (output == null || output.isEmpty()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/")
                .matcher(output);
        return matcher.find() ? matcher.group(1) : "";
    }

    private void finishGameSession() {
        if (finishingSession) return;
        finishingSession = true;
        handler.removeCallbacks(foregroundWatchTask);
        handler.removeCallbacks(refreshTask);
        handler.removeCallbacks(barTask);
        new Thread(() -> {
            try { GameOptimizer.restore(GameOverlayService.this); } catch (Throwable ignored) { }
            handler.post(() -> {
                prefs().edit().remove("active_game_package").apply();
                stopSelf();
            });
        }, "GamerTurbo-SessionRestore").start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Painel flutuante Gamer Turbo", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Barra lateral com ferramentas e informações durante jogos.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void startForegroundCompat() {
        Intent openIntent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 8102, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, GameOverlayService.class).setAction("STOP_OVERLAY");
        PendingIntent stopPending = PendingIntent.getService(this, 8103, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Gamer Turbo ativo").setContentText("Toque na barra lateral durante a partida")
                .setContentIntent(openPending).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Desativar", stopPending).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification);
    }

    // ------------------------------------------------------------------ visual helpers

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    private static int accent(String mode) {
        switch (mode) {
            case "eco": return 0xFF3DDC84;
            case "rise": return 0xFFFF9F1C;
            case "diablo": return 0xFFFF2D43;
            default: return 0xFF3E9BFF;
        }
    }
    private static int tint(String mode) {
        switch (mode) {
            case "eco": return 0xFF133324;
            case "rise": return 0xFF3B2A10;
            case "diablo": return 0xFF3E121A;
            default: return 0xFF132A45;
        }
    }
    private static String modeName(String mode) {
        switch (mode) {
            case "eco": return "ECO";
            case "rise": return "RISE";
            case "diablo": return "DIABLO";
            default: return "BALANCE";
        }
    }
    private static String modeText(String mode) {
        switch (mode) {
            case "eco": return "Limita CPU/GPU, tela em 60 Hz e liga a economia de bateria. Menos calor, mais autonomia.";
            case "rise": return "Libera as frequências máximas quando o jogo pede, tela na taxa máxima e economia desligada.";
            case "diablo": return "O mais agressivo: Rise + governador performance, limpeza de apps e prioridade. Esquenta e gasta mais.";
            default: return "Padrão do sistema: equilíbrio entre desempenho, consumo e temperatura. Restaura tudo.";
        }
    }

    private GradientDrawable shape(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }
    private GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(color); return d;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextColor(color); v.setTextSize(size);
        v.setTypeface(Typeface.create("sans-serif-condensed", bold ? Typeface.BOLD : Typeface.NORMAL));
        if (bold && Build.VERSION.SDK_INT >= 21) v.setLetterSpacing(0.06f);
        return v;
    }
    private TextView button(String label, int accentColor, Runnable callback) {
        TextView v = text(label, 10, Color.WHITE, true); v.setGravity(Gravity.CENTER);
        v.setPadding(dp(4), dp(9), dp(4), dp(9));
        v.setBackground(shape(0xFF181D27, alpha(accentColor, 0x99), 1, 9));
        v.setOnClickListener(x -> callback.run());
        return v;
    }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private LinearLayout column() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); return c; }
    private void gap(LinearLayout parent, int h) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h))); }
    private void addWeighted(LinearLayout parent, View view, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(height), 1f); lp.setMargins(dp(3), 0, dp(3), 0); parent.addView(view, lp);
    }
    private TextView sectionLabel(String label, int color) {
        TextView v = text(label, 8, color, true); v.setPadding(dp(2), dp(8), 0, dp(5)); return v;
    }

    private WindowManager.LayoutParams newParams(int width, int height) {
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        return new WindowManager.LayoutParams(width, height, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
    }

    // ------------------------------------------------------------------ estado

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private String currentMode() {
        String m = GameOptimizer.normalizeMode(prefs().getString("game_mode", "balance"));
        return m.isEmpty() ? "balance" : m;
    }

    private String currentGame() {
        String pkg = prefs().getString("gt_profile_package", "");
        if (!GameTools.validPackage(pkg)) pkg = prefs().getString("active_game_package", "");
        return GameTools.validPackage(pkg) ? pkg : "";
    }

    private String gameLabel(String pkg) {
        if (pkg.isEmpty()) return "nenhum";
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) { return pkg; }
    }

    private int shizukuColor() {
        String st = ShizukuShell.state();
        if ("READY".equals(st)) return 0xFF3DDC84;
        if ("NEED_PERMISSION".equals(st)) return 0xFFFFB020;
        return 0xFFFF3B4E;
    }

    private float maxRefreshRate() {
        float maxHz = 60f;
        try {
            android.view.Display display = windowManager.getDefaultDisplay();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (android.view.Display.Mode supported : display.getSupportedModes()) maxHz = Math.max(maxHz, supported.getRefreshRate());
            } else maxHz = display.getRefreshRate();
        } catch (Throwable ignored) { }
        return maxHz;
    }

    private boolean thermalSafe() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) return power.getCurrentThermalStatus() <= PowerManager.THERMAL_STATUS_LIGHT;
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private void setResult(String value) {
        lastResult = value;
        if (resultView != null) resultView.setText(value);
    }

    // ------------------------------------------------------------------ barra lateral

    private void showBubble() {
        if (windowManager == null || bubbleView != null || panelView != null) return;
        String mode = currentMode();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        FrameLayout box = new FrameLayout(this);

        View bar = new View(this);
        GradientDrawable barShape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{accent(mode), alpha(accent(mode), 0x88)});
        float r = dp(4);
        barShape.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        bar.setBackground(barShape);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(dp(6), dp(54), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        box.addView(bar, barLp);

        View dot = new View(this);
        dot.setBackground(oval(shizukuColor()));
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(4), dp(4), Gravity.RIGHT | Gravity.TOP);
        dotLp.topMargin = dp(14); dotLp.rightMargin = dp(1);
        box.addView(dot, dotLp);
        dotView = dot;

        final int barHeight = dp(74);
        bubbleParams = newParams(dp(22), barHeight);
        bubbleParams.gravity = Gravity.RIGHT | Gravity.TOP;
        bubbleParams.x = 0;
        bubbleParams.y = prefs().getInt("overlay_bar_y", dm.heightPixels / 3);

        box.setOnTouchListener(new View.OnTouchListener() {
            private int startY; private float downY; private long downAt; private boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = bubbleParams.y; downY = event.getRawY(); downAt = System.currentTimeMillis(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = event.getRawY() - downY;
                        if (Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            int max = Math.max(0, getResources().getDisplayMetrics().heightPixels - barHeight);
                            bubbleParams.y = Math.max(0, Math.min(max, startY + (int) dy));
                            try { windowManager.updateViewLayout(bubbleView, bubbleParams); } catch (Exception ignored) { }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved && System.currentTimeMillis() - downAt < 600) showPanel();
                        else prefs().edit().putInt("overlay_bar_y", bubbleParams.y).apply();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
        bubbleView = box;
        try { windowManager.addView(bubbleView, bubbleParams); handler.removeCallbacks(barTask); handler.postDelayed(barTask, 3000); }
        catch (Exception e) { bubbleView = null; stopSelf(); }
    }

    private void refreshDot() {
        if (dotView != null) dotView.setBackground(oval(shizukuColor()));
    }

    // ------------------------------------------------------------------ painel expandido

    private LinearLayout metricCard(String label, TextView[] valueOut, TextView[] metaOut, int accentColor) {
        LinearLayout card = column();
        card.setPadding(dp(7), dp(6), dp(6), dp(5));
        card.setBackground(shape(0xFF141923, alpha(accentColor, 0x55), 1, 9));
        card.addView(text(label, 7, 0xFF8F9AAC, true));
        TextView value = text("--", 13, Color.WHITE, true);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(-1, -2);
        valueLp.topMargin = dp(3);
        card.addView(value, valueLp);
        TextView meta = text("", 7, 0xFF8F9AAC, false);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
        metaLp.topMargin = dp(1);
        card.addView(meta, metaLp);
        valueOut[0] = value;
        metaOut[0] = meta;
        return card;
    }

    private void showPanel() {
        if (windowManager == null || panelView != null) return;
        removeBubble();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean wide = dm.widthPixels > dm.heightPixels;
        String mode = currentMode();
        int accent = accent(mode);

        // A área fora do cartão apenas fecha o painel; ela não é o painel em si.
        View scrim = new View(this);
        scrim.setBackgroundColor(0x33000000);
        scrim.setOnClickListener(v -> collapsePanel());
        try {
            windowManager.addView(scrim, newParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT));
            scrimView = scrim;
        } catch (Exception ignored) { scrimView = null; }

        LinearLayout root = column();
        root.setPadding(dp(10), dp(9), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF41A202B, 0xF40B0E14});
        bg.setCornerRadius(dp(17));
        bg.setStroke(dp(1), alpha(accent, 0xDD));
        root.setBackground(bg);

        LinearLayout head = row();
        LinearLayout titles = column();
        titles.addView(text("GAMER TURBO", 13, Color.WHITE, true));
        titles.addView(text("GAME TOOLBOX", 7, 0xFF9AA5B6, true));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        shizukuPill = text("", 7, Color.WHITE, true);
        shizukuPill.setPadding(dp(8), dp(6), dp(8), dp(6));
        shizukuPill.setOnClickListener(v -> onShizukuPillClick());
        head.addView(shizukuPill, new LinearLayout.LayoutParams(-2, -2));
        TextView close = button("×", 0xFF8A93A3, this::collapsePanel);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        closeLp.leftMargin = dp(7);
        head.addView(close, closeLp);
        root.addView(head);

        root.addView(sectionLabel("MONITORAMENTO EM TEMPO REAL", 0xFF8F9AAC));
        TextView[][] values = new TextView[6][1];
        TextView[][] meta = new TextView[6][1];
        String[] labels = {"BATERIA", "RAM LIVRE", "CPU / GHZ", "FPS DO JOGO", "TEMP. BAT.", "TÉRMICO"};
        if (wide) {
            LinearLayout metrics = row();
            for (int i = 0; i < labels.length; i++) addWeighted(metrics, metricCard(labels[i], values[i], meta[i], accent), 57);
            root.addView(metrics);
        } else {
            for (int base = 0; base < labels.length; base += 3) {
                LinearLayout metrics = row();
                for (int i = base; i < Math.min(base + 3, labels.length); i++)
                    addWeighted(metrics, metricCard(labels[i], values[i], meta[i], accent), 57);
                root.addView(metrics);
                if (base == 0) gap(root, 5);
            }
        }
        batteryValue = values[0][0]; ramValue = values[1][0]; cpuValue = values[2][0];
        fpsValue = values[3][0]; tempValue = values[4][0]; thermalValue = values[5][0];
        cpuMetaValue = meta[2][0]; fpsMetaValue = meta[3][0];

        infoLine = text("", 7, 0xFF9AA5B6, true);
        infoLine.setPadding(dp(3), dp(6), 0, dp(1));
        root.addView(infoLine);

        LinearLayout controls = row();
        controls.setGravity(Gravity.TOP);
        LinearLayout left = column();
        left.addView(sectionLabel("MODO DE JOGO", 0xFF8F9AAC));
        LinearLayout modes = row();
        for (int i = 0; i < MODES.length; i++) {
            final String m = MODES[i];
            TextView mb = text(modeName(m), 8, Color.WHITE, true);
            mb.setGravity(Gravity.CENTER);
            mb.setOnClickListener(v -> selectMode(m));
            modeButtons[i] = mb;
            addWeighted(modes, mb, 36);
        }
        left.addView(modes);
        modeDesc = text("", 7, 0xFFB5BFCE, false);
        modeDesc.setPadding(dp(3), dp(6), dp(3), 0);
        left.addView(modeDesc);
        styleModeButtons();

        LinearLayout right = column();
        right.addView(sectionLabel("OTIMIZAÇÃO", 0xFF8F9AAC));
        LinearLayout act1 = row();
        addWeighted(act1, button("LIMPAR RAM", 0xFF3E9BFF, this::actRam), 34);
        addWeighted(act1, button("FECHAR APPS", 0xFFFF9F1C, this::actClose), 34);
        right.addView(act1);
        gap(right, 5);
        LinearLayout act2 = row();
        addWeighted(act2, button("PRIORIDADE", 0xFF3DDC84, this::actPriority), 34);
        TextView boost = button("⚡ BOOST", 0xFFFF2D43, this::actBoost);
        boost.setBackground(shape(0xFF3A1019, 0xFFFF2D43, 1, 9));
        addWeighted(act2, boost, 34);
        right.addView(act2);

        right.addView(sectionLabel("FERRAMENTAS DO JOGO", 0xFF8F9AAC));
        LinearLayout tools1 = row();
        addWeighted(tools1, button("CAPTURA", 0xFF8A93A3, this::takeScreenshot), 31);
        addWeighted(tools1, button(recording ? "PARAR REC" : "GRAVAR", 0xFF8A93A3, this::toggleRecording), 31);
        addWeighted(tools1, button("FOCO", 0xFF8A93A3, this::toggleDnd), 31);
        right.addView(tools1);
        gap(right, 5);
        LinearLayout tools2 = row();
        addWeighted(tools2, button("BRILHO", 0xFF8A93A3, this::showBrightnessPanel), 31);
        addWeighted(tools2, button("WI-FI", 0xFF8A93A3, this::openWifiPanel), 31);
        addWeighted(tools2, button("TIMER", 0xFF8A93A3, this::showTimerPanel), 31);
        right.addView(tools2);
        gap(right, 5);
        LinearLayout tools3 = row();
        addWeighted(tools3, button("ARMAZENAR", 0xFF8A93A3, this::openStoragePanel), 31);
        addWeighted(tools3, button("CAST", 0xFF8A93A3, this::openCastPanel), 31);
        addWeighted(tools3, button("EXCEÇÕES", 0xFF8A93A3, this::showWhitelistPanel), 31);
        right.addView(tools3);
        gap(right, 5);
        LinearLayout tools4 = row();
        addWeighted(tools4, button("CPU / GHZ", 0xFF3E9BFF, this::showCpuControlPanel), 31);
        addWeighted(tools4, button("HIBERNAR APPS", 0xFFFF9F1C, this::actHibernate), 31);
        addWeighted(tools4, button("FECHAR PAINEL", 0xFF8A93A3, this::collapsePanel), 31);
        right.addView(tools4);

        if (wide) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 0.78f);
            l.rightMargin = dp(8);
            LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, -2, 1.22f);
            controls.addView(left, l);
            controls.addView(right, r);
            root.addView(controls);
        } else {
            root.addView(left);
            root.addView(right);
        }

        root.addView(sectionLabel("ÚLTIMA AÇÃO", accent));
        resultView = text(lastResult, 8, 0xFFDDE3EC, false);
        resultView.setMaxLines(3);
        resultView.setPadding(dp(8), dp(6), dp(8), dp(6));
        resultView.setBackground(shape(0xFF0E1118, alpha(accent, 0x66), 1, 8));
        root.addView(resultView, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(root, new ScrollView.LayoutParams(-1, -2));
        panelView = scroller;

        int width = wide ? Math.min(dp(720), dm.widthPixels - dp(32))
                : Math.min(dp(320), dm.widthPixels - dp(24));
        int maxHeight = Math.max(dp(260), dm.heightPixels - dp(96));
        panelParams = newParams(width, Math.min(dp(470), maxHeight));
        panelParams.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        panelParams.x = dp(8);
        panelParams.y = 0;
        panelView.setAlpha(0f);
        try {
            windowManager.addView(panelView, panelParams);
            panelOpen = true;
            panelView.post(() -> {
                if (panelView == null) return;
                panelView.setPivotX(panelView.getWidth());
                panelView.setPivotY(panelView.getHeight() / 2f);
                panelView.setScaleX(0.96f);
                panelView.animate().scaleX(1f).alpha(1f).setDuration(150).start();
            });
            updateMetrics();
            requestFpsUpdate();
            handler.removeCallbacks(refreshTask);
            handler.postDelayed(refreshTask, 1500);
        } catch (Exception e) {
            panelView = null;
            panelOpen = false;
            removeScrim();
            showBubble();
        }
    }

    private void styleModeButtons() {
        String selected = currentMode();
        for (int i = 0; i < MODES.length; i++) {
            TextView mb = modeButtons[i];
            if (mb == null) continue;
            String m = MODES[i];
            boolean on = m.equals(selected);
            mb.setBackground(on ? shape(tint(m), accent(m), 2, 9) : shape(0xFF171B24, 0xFF333A47, 1, 9));
            mb.setTextColor(on ? Color.WHITE : 0xFF9AA5B6);
        }
        if (modeDesc != null) { modeDesc.setText(modeText(selected)); modeDesc.setTextColor(alpha(accent(selected), 0xFF)); }
    }

    private void onShizukuPillClick() {
        String st = ShizukuShell.state();
        if ("NEED_PERMISSION".equals(st)) openMainForShizuku();
        else if ("OFF".equals(st)) openShizukuApp();
        else setResult("Shizuku conectado e autorizado ✔\nAs otimizações reais estão liberadas.");
    }

    private void openShizukuApp() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(GameTools.SHIZUKU_PACKAGE);
            if (intent == null) {
                setResult("O aplicativo Shizuku não está instalado.\nInstale o Shizuku, inicie por depuração sem fio e autorize o Gamer Turbo.");
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, "Inicie o Shizuku e volte ao jogo.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            setResult("Não foi possível abrir o Shizuku.");
        }
    }

    // ------------------------------------------------------------------ modos e ações

    private void runAsync(String title, Callable<String> job) {
        if (busy) { Toast.makeText(this, "Aguarde a ação anterior terminar.", Toast.LENGTH_SHORT).show(); return; }
        busy = true;
        setResult("⏳ " + title + "...");
        new Thread(() -> {
            String out;
            try { out = job.call(); } catch (Throwable error) { out = "Falha: " + error.getClass().getSimpleName(); }
            final String text = out;
            busy = false;
            handler.post(() -> { setResult(text); updateMetrics(); });
        }, "GamerTurbo-Overlay").start();
    }

    private boolean requireShizuku(String what) {
        if (ShizukuShell.isAuthorized()) return true;
        setResult("⚠ " + what + " precisa do Shizuku conectado e autorizado.\nToque no selo SHIZUKU no topo do painel para conectar. Nada foi alterado.");
        return false;
    }

    private void selectMode(String mode) {
        prefs().edit().putString("game_mode", mode).apply();
        styleModeButtons();
        if ("balance".equals(mode)) {
            runAsync("BALANCE: restaurando o sistema", () -> GameOptimizer.restore(GameOverlayService.this));
            return;
        }
        if (!requireShizuku("O modo " + modeName(mode))) return;
        final String game = currentGame();
        final float hz = maxRefreshRate();
        final boolean cool = thermalSafe();
        runAsync("Aplicando " + modeName(mode), () -> {
            String report = GameOptimizer.apply(GameOverlayService.this, game, mode, hz, cool);
            prefs().edit().putString("gt_last_profile_report", report).apply();
            if (game.isEmpty()) report += "\n• Sem jogo ativo: vale para o sistema até você escolher BALANCE.";
            return report;
        });
    }

    private void actRam() {
        final String game = currentGame();
        runAsync("Limpando RAM", () -> GameTools.cleanRam(GameOverlayService.this, game, 1300));
    }

    private void actClose() {
        if (!requireShizuku("FECHAR APPS")) return;
        final String game = currentGame();
        runAsync("Fechando apps", () -> GameTools.closeApps(GameOverlayService.this, game));
    }

    private void actPriority() {
        if (!requireShizuku("PRIORIDADE")) return;
        final String game = currentGame();
        runAsync("Priorizando o jogo", () -> GameTools.prioritizeGame(GameOverlayService.this, game));
    }

    private void actBoost() {
        final String game = currentGame();
        if (!ShizukuShell.isAuthorized()) {
            runAsync("Boost básico (sem Shizuku)", () -> "BOOST BÁSICO (sem Shizuku)\n\n"
                    + GameTools.cleanRam(GameOverlayService.this, game, 1300)
                    + "\n\n⚠ Conecte o Shizuku para fechar apps e priorizar o jogo.");
            return;
        }
        runAsync("Boost completo", () -> GameTools.boost(GameOverlayService.this, game));
    }

    // ------------------------------------------------------------------ métricas

    private void updateMetrics() {
        try {
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null && batteryValue != null) {
                int level = battery.getIntExtra("level", -1), scale = battery.getIntExtra("scale", 100), temp = battery.getIntExtra("temperature", -1);
                int pct = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
                batteryValue.setText(pct >= 0 ? pct + "%" : "--");
                tempValue.setText(temp >= 0 ? String.format(java.util.Locale.US, "%.1f°", temp / 10f) : "--");
            }
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null && ramValue != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
                float freeMb = mi.availMem / 1048576f;
                ramValue.setText(freeMb >= 1024 ? String.format(java.util.Locale.US, "%.1f GB", freeMb / 1024f) : Math.round(freeMb) + " MB");
            }
            DeviceMonitor.CpuSnapshot cpu = DeviceMonitor.readCpu();
            if (cpu.frequencyMhz > 0) lastCpuFrequencyMhz = cpu.frequencyMhz;
            else requestCpuFrequencyFallback();
            if (cpuValue != null) cpuValue.setText(lastCpuFrequencyMhz > 0
                    ? String.format(java.util.Locale.US, "%.2f GHz", lastCpuFrequencyMhz / 1000f) : "-- GHz");
            if (cpuMetaValue != null) cpuMetaValue.setText(cpu.usagePercent >= 0 ? "média • uso " + cpu.usagePercent + "%" : "média • uso --");
            if (fpsValue != null) fpsValue.setText(lastFps >= 0 ? String.valueOf(lastFps) : "--");
            if (fpsMetaValue != null && lastFps < 0 && !fpsBusy) {
                String hint = !ShizukuShell.isAuthorized() ? "Shizuku necessário"
                        : currentGame().isEmpty() ? "selecione um jogo" : "SurfaceFlinger indisponível";
                fpsMetaValue.setText(hint);
            }
            if (thermalValue != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                if (power != null) thermalValue.setText(thermalLabel(power.getCurrentThermalStatus()));
            }
            if (infoLine != null) {
                long started = prefs().getLong("game_session_started", System.currentTimeMillis());
                float hz = 0f;
                try { hz = windowManager.getDefaultDisplay().getRefreshRate(); } catch (Throwable ignored) { }
                infoLine.setText("TELA " + Math.round(hz) + " Hz  •  PARTIDA " + formatDuration(Math.max(0, (System.currentTimeMillis() - started) / 1000))
                        + "  •  JOGO: " + gameLabel(currentGame()));
            }
            if (shizukuPill != null) {
                String st = ShizukuShell.state();
                int color = shizukuColor();
                shizukuPill.setText("READY".equals(st) ? "● SHIZUKU CONECTADO" : "NEED_PERMISSION".equals(st) ? "● AUTORIZAR SHIZUKU" : "● SHIZUKU DESLIGADO");
                shizukuPill.setTextColor(color);
                shizukuPill.setBackground(shape(alpha(color, 0x26), alpha(color, 0xAA), 1, 14));
            }
        } catch (Exception ignored) { }
    }

    private void requestCpuFrequencyFallback() {
        if (cpuFrequencyBusy || !ShizukuShell.isAuthorized()) return;
        cpuFrequencyBusy = true;
        new Thread(() -> {
            int frequencyMhz = -1;
            try {
                String command = "for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do [ -d \"$d\" ] || continue; "
                        + "f=\"$d/cpuinfo_avg_freq\"; [ -r \"$f\" ] || f=\"$d/scaling_cur_freq\"; "
                        + "v=$(cat \"$f\" 2>/dev/null); c=$(cat \"$d/affected_cpus\" 2>/dev/null); "
                        + "case \"$v\" in *[!0-9]*|'') continue;; esac; printf 'GTCPUFREQ|%s|%s\\n' \"$v\" \"$c\"; done";
                ShizukuShell.Result result = ShizukuShell.execute(GameOverlayService.this, command);
                if (result.output != null) {
                    long weighted = 0L;
                    int cores = 0;
                    for (String line : result.output.split("\\r?\\n")) {
                        String[] fields = line.trim().split("\\|", -1);
                        if (fields.length != 3 || !"GTCPUFREQ".equals(fields[0]) || !fields[1].matches("[0-9]+")) continue;
                        long khz;
                        try { khz = Long.parseLong(fields[1]); } catch (NumberFormatException ignored) { continue; }
                        int count = countCpuIds(fields[2]);
                        if (count <= 0) count = 1;
                        weighted += khz * count;
                        cores += count;
                    }
                    if (cores > 0) frequencyMhz = (int) ((weighted / cores) / 1000L);
                }
            } catch (Throwable ignored) { }
            final int measured = frequencyMhz;
            handler.post(() -> {
                cpuFrequencyBusy = false;
                if (measured > 0) {
                    lastCpuFrequencyMhz = measured;
                    if (cpuValue != null) cpuValue.setText(String.format(java.util.Locale.US, "%.2f GHz", measured / 1000f));
                }
            });
        }, "GamerTurbo-CPU-Frequency").start();
    }

    private static int countCpuIds(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        int count = 0;
        for (String part : value.trim().split("[,\\s]+")) {
            if (part.matches("[0-9]+-[0-9]+")) {
                String[] bounds = part.split("-");
                try { count += Math.max(0, Integer.parseInt(bounds[1]) - Integer.parseInt(bounds[0]) + 1); }
                catch (NumberFormatException ignored) { }
            } else if (part.matches("[0-9]+")) count++;
        }
        return count;
    }

    private void requestFpsUpdate() {
        if (!panelOpen || fpsBusy) return;
        final String pkg = currentGame();
        if (!ShizukuShell.isAuthorized() || !GameTools.validPackage(pkg)) {
            lastFps = -1;
            if (fpsMetaValue != null) fpsMetaValue.setText(!ShizukuShell.isAuthorized()
                    ? "Shizuku necessário" : "selecione um jogo");
            if (fpsValue != null) fpsValue.setText("--");
            return;
        }
        fpsBusy = true;
        new Thread(() -> {
            int measured = -1;
            try {
                String command = "PKG='" + pkg + "'; L=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"$PKG\" | grep -E 'SurfaceView|BLAST|BufferQueue' | head -n 1); "
                        + "[ -n \"$L\" ] || L=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"$PKG\" | head -n 1); "
                        + "if [ -n \"$L\" ]; then echo GT_LAYER_FOUND; "
                        + "dumpsys SurfaceFlinger --latency \"$L\" 2>/dev/null | tail -n 65; "
                        + "else echo GT_NO_LAYER; fi";
                ShizukuShell.Result result = ShizukuShell.execute(GameOverlayService.this, command);
                if (result.success && result.output != null) measured = parseFps(result.output);
            } catch (Throwable ignored) { }
            final int fps = measured;
            handler.post(() -> {
                lastFps = fps;
                fpsBusy = false;
                if (fpsValue != null) fpsValue.setText(fps >= 0 ? String.valueOf(fps) : "--");
                if (fpsMetaValue != null) fpsMetaValue.setText(fps >= 0 ? "SurfaceFlinger" : "sem amostras do jogo");
            });
        }, "GamerTurbo-FPS").start();
    }

    private int parseFps(String output) {
        if (output == null || output.contains("GT_NO_LAYER")) return -1;
        List<Long> stamps = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("GT_")) continue;
            String[] columns = trimmed.split("\\s+");
            if (columns.length < 3) continue;
            try {
                long desired = Long.parseLong(columns[0]);
                long actual = Long.parseLong(columns[1]);
                long ready = Long.parseLong(columns[2]);
                long present = actual > 0L ? actual : (ready > 0L ? ready : desired);
                if (present > 1_000_000_000L) stamps.add(present);
            } catch (NumberFormatException ignored) { }
        }
        if (stamps.size() < 3) return -1;
        Collections.sort(stamps);
        long end = stamps.get(stamps.size() - 1);
        long start = end;
        int frames = 1;
        for (int i = stamps.size() - 2; i >= 0; i--) {
            long stamp = stamps.get(i);
            if (end - stamp > 2_000_000_000L) break;
            if (stamp < start) { start = stamp; frames++; }
        }
        long span = end - start;
        if (span <= 0L || frames < 3) return -1;
        int fps = Math.round((frames - 1) * 1_000_000_000f / span);
        return fps >= 1 && fps <= 240 ? fps : -1;
    }

    private String thermalLabel(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_LIGHT: return "LEVE";
            case PowerManager.THERMAL_STATUS_MODERATE: return "MÉDIO";
            case PowerManager.THERMAL_STATUS_SEVERE: return "ALTO";
            case PowerManager.THERMAL_STATUS_CRITICAL: case PowerManager.THERMAL_STATUS_EMERGENCY: case PowerManager.THERMAL_STATUS_SHUTDOWN: return "CRÍTICO";
            default: return "NORMAL";
        }
    }
    private String formatDuration(long seconds) { long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60; return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s); }

    // ------------------------------------------------------------------ ferramentas

    private void showBrightnessPanel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Settings.System.canWrite(this)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this, "Permita modificar as configurações do sistema e toque em BRILHO novamente.", Toast.LENGTH_LONG).show();
            } catch (Exception e) { Toast.makeText(this, "Não foi possível abrir a permissão de brilho.", Toast.LENGTH_SHORT).show(); }
            return;
        }
        removeBrightnessPanel();
        int accent = accent(currentMode());
        LinearLayout box = column(); box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(shape(0xF40D1016, accent, 2, 13));
        box.addView(text("BRILHO DA TELA", 10, Color.WHITE, true));
        int current = 128; try { current = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128); } catch (Exception ignored) { }
        TextView value = text(Math.round(current / 255f * 100) + "%", 11, accent, true); value.setPadding(0, dp(8), 0, 0); box.addView(value);
        SeekBar seek = new SeekBar(this); seek.setMax(255); seek.setProgress(current);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { value.setText(Math.round(progress / 255f * 100) + "%"); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(38)); slp.topMargin = dp(5); box.addView(seek, slp);
        TextView apply = button("APLICAR", accent, () -> {
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, seek.getProgress());
                Toast.makeText(this, "Brilho atualizado", Toast.LENGTH_SHORT).show(); removeBrightnessPanel();
            } catch (Exception e) { Toast.makeText(this, "O Android não permitiu alterar o brilho.", Toast.LENGTH_SHORT).show(); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(36)); alp.topMargin = dp(8); box.addView(apply, alp);
        brightnessPanelView = box;
        WindowManager.LayoutParams lp = newParams(dp(260), WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER; lp.x = 0; lp.y = 0;
        try { windowManager.addView(brightnessPanelView, lp); }
        catch (Exception e) { brightnessPanelView = null; Toast.makeText(this, "Não foi possível abrir o controle de brilho.", Toast.LENGTH_SHORT).show(); }
    }

    private void removeBrightnessPanel() { if (brightnessPanelView != null && windowManager != null) { try { windowManager.removeView(brightnessPanelView); } catch (Exception ignored) { } brightnessPanelView = null; } }

    private void showTimerPanel() {
        collapsePanel();
        removeTimerPanel();
        int accent = accent(currentMode());
        LinearLayout box = column();
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(shape(0xF40D1016, accent, 1, 13));
        box.addView(text("TEMPORIZADOR DE PARTIDA", 10, Color.WHITE, true));
        TextView value = text("15 minutos", 15, accent, true);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(-1, -2);
        valueLp.topMargin = dp(8);
        box.addView(value, valueLp);
        SeekBar seek = new SeekBar(this);
        seek.setMax(119);
        seek.setProgress(14);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText((progress + 1) + " minuto(s)");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        box.addView(seek, new LinearLayout.LayoutParams(-1, dp(38)));
        LinearLayout actions = row();
        addWeighted(actions, button("INICIAR", accent, () -> {
            int minutes = seek.getProgress() + 1;
            timerEndAt = System.currentTimeMillis() + minutes * 60_000L;
            prefs().edit().putLong("floating_timer_end", timerEndAt).apply();
            handler.removeCallbacks(timerTask);
            handler.post(timerTask);
            removeTimerPanel();
            Toast.makeText(this, "Temporizador iniciado: " + minutes + " min.", Toast.LENGTH_SHORT).show();
        }), 36);
        addWeighted(actions, button("CANCELAR", 0xFF8A93A3, this::removeTimerPanel), 36);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = dp(8);
        box.addView(actions, actionsLp);
        timerPanelView = box;
        WindowManager.LayoutParams lp = newParams(dp(270), WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        try { windowManager.addView(timerPanelView, lp); }
        catch (Exception e) { timerPanelView = null; Toast.makeText(this, "Não foi possível abrir o temporizador.", Toast.LENGTH_SHORT).show(); }
    }

    private void removeTimerPanel() {
        if (timerPanelView != null && windowManager != null) {
            try { windowManager.removeView(timerPanelView); } catch (Exception ignored) { }
            timerPanelView = null;
        }
    }

    private void openStoragePanel() {
        collapsePanel();
        try {
            Intent intent = new Intent("android.settings.INTERNAL_STORAGE_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(getPackageManager()) == null)
                intent = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "As configurações de armazenamento não estão disponíveis.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openCastPanel() {
        collapsePanel();
        try {
            Intent intent = new Intent("android.settings.CAST_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(getPackageManager()) == null)
                intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "O menu de transmissão depende do sistema do aparelho.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showWhitelistPanel() {
        collapsePanel();
        removeWhitelistPanel();
        int accent = accent(currentMode());
        LinearLayout root = column();
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setBackground(shape(0xF40D1016, accent, 1, 14));
        root.addView(text("EXCEÇÕES DE MEMÓRIA", 11, Color.WHITE, true));
        TextView hint = text("Os apps marcados serão preservados ao limpar RAM ou usar BOOST.", 8, 0xFF9AA5B6, false);
        hint.setPadding(0, dp(5), 0, dp(7));
        root.addView(hint);

        Set<String> saved = new HashSet<>();
        Set<String> existing = prefs().getStringSet("memory_whitelist", Collections.emptySet());
        if (existing != null) saved.addAll(existing);
        List<ApplicationInfo> apps = new ArrayList<>();
        try {
            for (ApplicationInfo app : getPackageManager().getInstalledApplications(0)) {
                if (app == null || !GameTools.validPackage(app.packageName)) continue;
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                if (app.packageName.equals(getPackageName()) || app.packageName.equals(GameTools.SHIZUKU_PACKAGE)) continue;
                apps.add(app);
            }
        } catch (Throwable ignored) { }
        apps.sort(Comparator.comparing(app -> {
            try { return String.valueOf(getPackageManager().getApplicationLabel(app)).toLowerCase(java.util.Locale.ROOT); }
            catch (Throwable ignored) { return app.packageName; }
        }));

        LinearLayout list = column();
        List<CheckBox> checks = new ArrayList<>();
        int shown = Math.min(apps.size(), 60);
        for (int i = 0; i < shown; i++) {
            ApplicationInfo app = apps.get(i);
            CheckBox check = new CheckBox(this);
            String label;
            try { label = String.valueOf(getPackageManager().getApplicationLabel(app)); }
            catch (Throwable ignored) { label = app.packageName; }
            check.setText(label + "  ·  " + app.packageName);
            check.setTextColor(0xFFE2E6ED);
            check.setTextSize(8);
            check.setPadding(dp(2), dp(1), dp(2), dp(1));
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
            check.setChecked(saved.contains(app.packageName));
            check.setTag(app.packageName);
            checks.add(check);
            list.addView(check, new LinearLayout.LayoutParams(-1, dp(38)));
        }
        if (shown == 0) list.addView(text("Nenhum app compatível foi encontrado.", 9, 0xFF9AA5B6, false));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(270)));

        LinearLayout actions = row();
        addWeighted(actions, button("SALVAR", accent, () -> {
            Set<String> selected = new HashSet<>();
            for (CheckBox check : checks) if (check.isChecked() && check.getTag() instanceof String)
                selected.add((String) check.getTag());
            prefs().edit().putStringSet("memory_whitelist", selected).apply();
            removeWhitelistPanel();
            lastResult = "EXCEÇÕES SALVAS\n• " + selected.size() + " app(s) serão preservados na limpeza de memória.";
            Toast.makeText(this, "Lista de exceções atualizada.", Toast.LENGTH_SHORT).show();
        }), 36);
        addWeighted(actions, button("FECHAR", 0xFF8A93A3, this::removeWhitelistPanel), 36);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-1, -2);
        actionLp.topMargin = dp(8);
        root.addView(actions, actionLp);

        whitelistPanelView = root;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = newParams(Math.min(dp(320), dm.widthPixels - dp(24)),
                Math.min(dp(420), Math.max(dp(260), dm.heightPixels - dp(100))));
        lp.gravity = Gravity.CENTER;
        try { windowManager.addView(whitelistPanelView, lp); }
        catch (Exception e) { whitelistPanelView = null; Toast.makeText(this, "Não foi possível abrir as exceções.", Toast.LENGTH_SHORT).show(); }
    }

    private void removeWhitelistPanel() {
        if (whitelistPanelView != null && windowManager != null) {
            try { windowManager.removeView(whitelistPanelView); } catch (Exception ignored) { }
            whitelistPanelView = null;
        }
    }

    private void openWifiPanel() {
        collapsePanel();
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
            else intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent);
        } catch (Exception e) { Toast.makeText(this, "Não foi possível abrir os controles de Wi-Fi.", Toast.LENGTH_SHORT).show(); }
    }

    private void takeScreenshot() {
        if (!ShizukuShell.isAuthorized()) { openMainForShizuku(); return; }
        collapsePanel();
        new Thread(() -> {
            String path = "/sdcard/Pictures/GamerTurbo/GT_" + System.currentTimeMillis() + ".png";
            ShizukuShell.Result result = ShizukuShell.execute(GameOverlayService.this, "mkdir -p /sdcard/Pictures/GamerTurbo && screencap -p '" + path + "'");
            handler.post(() -> Toast.makeText(this, result.success ? "Captura salva em Pictures/GamerTurbo" : ("Falha na captura: " + result.output), Toast.LENGTH_LONG).show());
        }, "GamerTurbo-Screenshot").start();
    }

    private void toggleRecording() {
        if (!ShizukuShell.isAuthorized()) { openMainForShizuku(); return; }
        if (recording) {
            new Thread(() -> ShizukuShell.execute(GameOverlayService.this, "pkill -2 screenrecord"), "GamerTurbo-StopRecord").start();
            recording = false; Toast.makeText(this, "Parando gravação...", Toast.LENGTH_SHORT).show(); collapsePanel(); return;
        }
        recording = true; collapsePanel(); Toast.makeText(this, "Gravação iniciada • até 3 minutos • sem áudio", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            String path = "/sdcard/Movies/GamerTurbo/GT_" + System.currentTimeMillis() + ".mp4";
            ShizukuShell.Result result = ShizukuShell.execute(GameOverlayService.this, "mkdir -p /sdcard/Movies/GamerTurbo && screenrecord --time-limit 180 '" + path + "'");
            recording = false;
            handler.post(() -> Toast.makeText(this, result.success ? "Vídeo salvo em Movies/GamerTurbo" : ("Gravação encerrada: " + result.output), Toast.LENGTH_LONG).show());
        }, "GamerTurbo-ScreenRecord").start();
    }

    private void openMainForShizuku() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("request_shizuku", true);
        startActivity(intent);
        Toast.makeText(this, "Autorize o Gamer Turbo no Shizuku.", Toast.LENGTH_LONG).show();
    }

    private void actHibernate() {
        if (!requireShizuku("HIBERNAR APPS")) return;
        final String game = currentGame();
        runAsync("Hibernação de apps de terceiros", () -> GameTools.closeApps(GameOverlayService.this, game));
    }

    private void showCpuControlPanel() {
        collapsePanel();
        removeCpuPanel();
        LinearLayout loading = column();
        loading.setPadding(dp(14), dp(12), dp(14), dp(12));
        loading.setBackground(shape(0xF40D1016, accent(currentMode()), 1, 14));
        loading.addView(text("CONTROLE DA CPU", 12, Color.WHITE, true));
        loading.addView(text("Lendo os limites expostos pelo kernel via Shizuku...", 9, 0xFF9AA5B6, false));
        cpuPanelView = loading;
        addCpuPanelWindow();
        new Thread(() -> {
            List<CpuController.Policy> policies = CpuController.inspect(GameOverlayService.this);
            handler.post(() -> {
                if (serviceDestroyed || finishingSession) return;
                removeCpuPanel();
                renderCpuControlPanel(policies);
            });
        }, "GamerTurbo-CPU-Inspect").start();
    }

    private void renderCpuControlPanel(List<CpuController.Policy> policies) {
        LinearLayout root = column();
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        int color = accent(currentMode());
        root.setBackground(shape(0xF40D1016, color, 1, 14));
        root.addView(text("CONTROLE DA CPU", 12, Color.WHITE, true));
        root.addView(text("Ajuste o piso e o teto de frequência em cada cluster.", 8, 0xFF9AA5B6, false));

        boolean anyWritable = false;
        for (CpuController.Policy policy : policies) if (policy.writable) anyWritable = true;
        if (policies == null || policies.isEmpty()) {
            TextView unavailable = text(!ShizukuShell.isAuthorized()
                    ? "Shizuku não está conectado/autorizado. Conecte e abra novamente."
                    : "Este kernel não expõe interfaces CPUFreq compatíveis para o Gamer Turbo.", 9, 0xFFFFB020, false);
            unavailable.setPadding(0, dp(10), 0, dp(8));
            root.addView(unavailable);
            root.addView(button("FECHAR", 0xFF8A93A3, this::removeCpuPanel), new LinearLayout.LayoutParams(-1, dp(38)));
            cpuPanelView = root;
            addCpuPanelWindow();
            return;
        }

        int savedMin = prefs().getInt("cpu_min_percent", CpuController.defaultMinPercent(policies));
        int savedMax = prefs().getInt("cpu_max_percent", CpuController.defaultMaxPercent(policies));
        final TextView minText = text("PICO MÍNIMO · " + savedMin + "% · " + CpuController.describePercent(policies, savedMin), 9, Color.WHITE, true);
        final TextView maxText = text("PICO MÁXIMO · " + savedMax + "% · " + CpuController.describePercent(policies, savedMax), 9, Color.WHITE, true);
        root.addView(sectionLabel("FREQUÊNCIA MÍNIMA", color));
        root.addView(minText);
        SeekBar minSeek = new SeekBar(this);
        minSeek.setMax(100);
        minSeek.setProgress(savedMin);
        minSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        minSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(color));
        root.addView(minSeek, new LinearLayout.LayoutParams(-1, dp(34)));
        root.addView(sectionLabel("FREQUÊNCIA MÁXIMA", color));
        root.addView(maxText);
        SeekBar maxSeek = new SeekBar(this);
        maxSeek.setMax(100);
        maxSeek.setProgress(Math.max(savedMin, savedMax));
        maxSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        maxSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(color));
        root.addView(maxSeek, new LinearLayout.LayoutParams(-1, dp(34)));

        Runnable updateLabels = () -> {
            int min = minSeek.getProgress();
            int max = maxSeek.getProgress();
            minText.setText("PICO MÍNIMO · " + min + "% · " + CpuController.describePercent(policies, min));
            maxText.setText("PICO MÁXIMO · " + max + "% · " + CpuController.describePercent(policies, max));
        };
        minSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress > maxSeek.getProgress()) maxSeek.setProgress(progress);
                updateLabels.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        maxSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < minSeek.getProgress()) minSeek.setProgress(progress);
                updateLabels.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        TextView hardware = text(CpuController.summarize(policies), 7, 0xFF9AA5B6, false);
        hardware.setPadding(0, dp(5), 0, dp(5));
        root.addView(hardware);
        TextView warning = text("Controle dentro dos limites de fábrica. Não é overclock. Se o kernel bloquear escrita, o controle permanece automático.",
                7, 0xFFFFC36B, false);
        warning.setPadding(0, dp(3), 0, dp(7));
        root.addView(warning);
        if (!anyWritable) {
            TextView readonly = text("O Shizuku consegue ler os clusters, mas não tem permissão para alterar seus limites neste aparelho.", 8, 0xFFFFB020, false);
            root.addView(readonly);
            minSeek.setEnabled(false);
            maxSeek.setEnabled(false);
        }

        LinearLayout actions = row();
        addWeighted(actions, button("APLICAR", color, () -> {
            int min = minSeek.getProgress();
            int max = maxSeek.getProgress();
            removeCpuPanel();
            if (!thermalSafe()) {
                setResult("Proteção térmica ativa: espere o aparelho esfriar antes de elevar ou fixar limites da CPU.");
                return;
            }
            runAsync("Aplicando limites da CPU", () -> CpuController.applyRange(GameOverlayService.this, min, max));
        }), 38);
        addWeighted(actions, button("RESTAURAR", 0xFF8A93A3, () -> {
            removeCpuPanel();
            runAsync("Restaurando CPU", () -> CpuController.restore(GameOverlayService.this));
        }), 38);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(34));
        closeLp.topMargin = dp(7);
        root.addView(button("FECHAR", 0xFF8A93A3, this::removeCpuPanel), closeLp);
        cpuPanelView = root;
        addCpuPanelWindow();
    }

    private void addCpuPanelWindow() {
        if (cpuPanelView == null || windowManager == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = newParams(Math.min(dp(330), dm.widthPixels - dp(24)),
                WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        try { windowManager.addView(cpuPanelView, lp); }
        catch (Exception error) { cpuPanelView = null; Toast.makeText(this, "Não foi possível abrir o controle da CPU.", Toast.LENGTH_SHORT).show(); }
    }

    private void removeCpuPanel() {
        if (cpuPanelView != null && windowManager != null) {
            try { windowManager.removeView(cpuPanelView); } catch (Exception ignored) { }
            cpuPanelView = null;
        }
    }

    private void toggleDnd() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE); if (manager == null) return;
        if (!manager.isNotificationPolicyAccessGranted()) {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent);
            Toast.makeText(this, "Autorize Não Perturbe e toque novamente.", Toast.LENGTH_LONG).show(); return;
        }
        SharedPreferences prefs = prefs(); boolean active = prefs.getBoolean("game_dnd_active", false);
        try {
            if (!active) {
                prefs.edit().putInt("previous_dnd_filter", manager.getCurrentInterruptionFilter()).apply();
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
                prefs.edit().putBoolean("game_dnd_active", true).apply();
            } else {
                manager.setInterruptionFilter(prefs.getInt("previous_dnd_filter", NotificationManager.INTERRUPTION_FILTER_ALL));
                prefs.edit().putBoolean("game_dnd_active", false).remove("previous_dnd_filter").apply();
            }
            setResult(active ? "Não Perturbe desativado." : "Não Perturbe ATIVADO: só alarmes tocam.");
        } catch (SecurityException e) { Toast.makeText(this, "O Android bloqueou Não Perturbe.", Toast.LENGTH_SHORT).show(); }
    }

    private void openMain() { Intent i = new Intent(this, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(i); }

    // ------------------------------------------------------------------ ciclo de vida das janelas

    private void collapsePanel() { handler.removeCallbacks(refreshTask); removeBrightnessPanel(); removePanel(); removeScrim(); showBubble(); }
    private void removeBubble() { handler.removeCallbacks(barTask); if (bubbleView != null && windowManager != null) { try { windowManager.removeView(bubbleView); } catch (Exception ignored) { } bubbleView = null; dotView = null; } }
    private void removePanel() { if (panelView != null && windowManager != null) { try { windowManager.removeView(panelView); } catch (Exception ignored) { } panelView = null; panelOpen = false; } }
    private void removeScrim() { if (scrimView != null && windowManager != null) { try { windowManager.removeView(scrimView); } catch (Exception ignored) { } scrimView = null; } }

    @Override public void onDestroy() {
        serviceDestroyed = true;
        handler.removeCallbacksAndMessages(null);
        removeBrightnessPanel(); removeTimerPanel(); removeWhitelistPanel(); removeCpuPanel(); removeBubble(); removePanel(); removeScrim();
        if (userRequestedStop) prefs().edit().putBoolean("overlay_enabled", false).apply();
        else if (prefs().getBoolean("gt_profile_active", false)
                || prefs().getBoolean("gt_cpu_limits_snapshot_active", false)) {
            // Safety net: restore reversible CPU/system tuning if Android or the user
            // removes the overlay service before the normal game-return callback.
            new Thread(() -> GameOptimizer.restore(GameOverlayService.this), "GamerTurbo-DestroyRestore").start();
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
