package com.gamerturbo.mobile;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQUEST_SCREEN_CAPTURE = 7401;
    private static final int REQUEST_SHIZUKU = 7410;
    private static final String PREFS = "gamer_turbo_native";
    private static final String APP_VERSION = "2.3.5";
    private static final String KEY_PENDING_OVERLAY = "pending_overlay_permission";
    private static final int REQUEST_INSTALLED_APPS = 7420;
    private static final int REQUEST_NOTIFICATIONS = 7421;
    private static final String PERM_GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS";
    private volatile String scanError = "";
    private volatile int scanFailed, scanLauncherCount, scanInstalledCount, scanTotal;
    private WebView webView;
    private volatile boolean gameLaunchedInThisTask = false;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (requestCode, grantResult) -> {
        if (requestCode == REQUEST_SHIZUKU) runOnUiThread(() -> {
            Toast.makeText(this, grantResult == PackageManager.PERMISSION_GRANTED ? "Shizuku autorizado para o Gamer Turbo." : "Permissão Shizuku negada.", Toast.LENGTH_LONG).show();
            if (webView != null) webView.postDelayed(() -> webView.evaluateJavascript("if(window.refreshNativeState){window.refreshNativeState();}", null), 250);
        });
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        refreshPermissionPromptStateForUpgrade();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        try { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) { }

        Window window = getWindow();
        window.setStatusBarColor(0xFF090A0E);
        window.setNavigationBarColor(0xFF090A0E);
        window.getDecorView().setBackgroundColor(0xFF090A0E);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF090A0E);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(webView);
        // Android 15+ (targetSdk 35) desenha o app sob as barras e o recorte da câmera: aplica as margens aqui.
        webView.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setBlockNetworkLoads(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // The WebView is intentionally restricted to local app assets.
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Espera o HTML registrar suas funções antes de atualizar os dados nativos.
                view.postDelayed(() -> {
                    view.evaluateJavascript(
                            "if(window.refreshNativeState){window.refreshNativeState();}", null);
                    requestInitialPermissionsOnce();
                }, 350);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new GamerTurboBridge(), "GamerTurboAndroid");
        webView.loadUrl("file:///android_asset/index.html");
    }

    /** Reabre a solicitação de notificações ao atualizar o fluxo de permissões. */
    private void refreshPermissionPromptStateForUpgrade() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String previous = prefs.getString("permission_flow_version", "");
        if (!APP_VERSION.equals(previous)) {
            prefs.edit().remove("asked_notifications_permission")
                    .putString("permission_flow_version", APP_VERSION).apply();
        }
    }

    private boolean areAppNotificationsEnabled(NotificationManager manager) {
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { return manager.areNotificationsEnabled(); }
            catch (Throwable ignored) { return false; }
        }
        return true;
    }

    private boolean installedAppsPermissionDefined() {
        try {
            getPackageManager().getPermissionInfo(PERM_GET_INSTALLED_APPS, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** QUERY_ALL_PACKAGES é declarativa; não é uma permissão runtime normal.
     * Evita solicitar GET_INSTALLED_APPS, que é uma permissão OEM não padronizada.
     */
    private boolean requestInstalledAppsPermissionOnce() { return false; }

    /** Solicita notificações diretamente, sem uma permissão OEM bloquear o fluxo. */
    private void requestInitialPermissionsOnce() {
        requestNotificationPermissionOnce();
    }

    /** Android 13+: sem isso as notificações dos serviços podem ficar ocultas. Não libera RAM. */
    private void requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("asked_notifications_permission", false)) return;
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("asked_notifications_permission", true).apply();
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_NOTIFICATIONS);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_INSTALLED_APPS || requestCode == REQUEST_NOTIFICATIONS) {
            if (webView != null) {
                webView.postDelayed(() -> webView.evaluateJavascript(
                        "if(window.refreshNativeState){window.refreshNativeState();}"
                                + "if(window.refreshInstalledGames){window.refreshInstalledGames();}", null), 350);
            }
            if (requestCode == REQUEST_INSTALLED_APPS) requestNotificationPermissionOnce();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean shouldRestoreGameProfile = gameLaunchedInThisTask || prefs.getBoolean("gt_profile_active", false);
        gameLaunchedInThisTask = false;
        if (shouldRestoreGameProfile) {
            new Thread(() -> {
                String result = GameOptimizer.restore(MainActivity.this);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        result.startsWith("Nenhum ajuste") ? "Sessão encerrada." : "Perfil do jogo restaurado ou aguardando Shizuku.",
                        Toast.LENGTH_SHORT).show());
            }, "GamerTurbo-RestoreProfile").start();
        }
        if (prefs.getBoolean("immersive_mode", false)) applyImmersiveMode(true);
        if (prefs.getBoolean(KEY_PENDING_OVERLAY, false)) {
            if (Settings.canDrawOverlays(this)) {
                prefs.edit().putBoolean(KEY_PENDING_OVERLAY, false).putBoolean("overlay_enabled", true).apply();
                startOverlayService();
            } else {
                prefs.edit().putBoolean(KEY_PENDING_OVERLAY, false).apply();
            }
        }
        if (getIntent() != null && getIntent().getBooleanExtra("request_shizuku", false)) {
            getIntent().removeExtra("request_shizuku");
            requestShizukuPermissionInternal();
        }
        if (webView != null) {
            webView.postDelayed(() -> webView.evaluateJavascript(
                    "if(window.refreshNativeState){window.refreshNativeState();}if(window.refreshInstalledGames){window.refreshInstalledGames();}", null), 250);
        }
    }

    /** Oculta ou restaura as barras do sistema apenas na central Gamer Turbo. */
    private void applyImmersiveMode(boolean enabled) {
        try {
            Window window = getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller == null) return;
                int bars = android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.navigationBars();
                if (enabled) {
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(bars);
                } else {
                    controller.show(bars);
                }
            } else {
                int flags = enabled
                        ? (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN)
                        : View.SYSTEM_UI_FLAG_VISIBLE;
                window.getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("immersive_mode", false)) {
            applyImmersiveMode(true);
        }
    }

    private void startOverlayService() {
        Intent service = new Intent(this, GameOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
    }

    private void requestOverlayPermissionInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_PENDING_OVERLAY, true).apply();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("request_shizuku", false)) {
            intent.removeExtra("request_shizuku");
            requestShizukuPermissionInternal();
        }
    }

    private void requestShizukuPermissionInternal() {
        try {
            if (!ShizukuShell.isRunning()) {
                Intent manager = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (manager != null) startActivity(manager);
                else Toast.makeText(this, "Instale e inicie o Shizuku primeiro. Abra a opção de depuração sem fio no próprio Shizuku.", Toast.LENGTH_LONG).show();
                return;
            }
            if (ShizukuShell.isAuthorized()) {
                Toast.makeText(this, "Shizuku já está autorizado.", Toast.LENGTH_SHORT).show();
                return;
            }
            Shizuku.requestPermission(REQUEST_SHIZUKU);
        } catch (Throwable e) {
            Toast.makeText(this, "Não foi possível solicitar acesso ao Shizuku: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, ScreenRecordService.class);
                service.putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode);
                service.putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
                else startService(service);
                Toast.makeText(this, "Preparando gravação da tela...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Gravação cancelada.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public final class GamerTurboBridge {
        /** Identifies the native API contract expected by the bundled WebView UI. */
        @JavascriptInterface
        public String getBridgeVersion() {
            return "2.3.5";
        }

        @JavascriptInterface
        public String getBridgeDiagnostics() {
            try {
                JSONObject info = new JSONObject();
                info.put("version", APP_VERSION);
                info.put("sdk", Build.VERSION.SDK_INT);
                info.put("release", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
                info.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
                info.put("package", getPackageName());
                info.put("shizuku", ShizukuShell.state());
                info.put("overlayPermission", Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(MainActivity.this));
                info.put("writeSettings", Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(MainActivity.this));
                NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                info.put("dndAccess", notificationManager != null && notificationManager.isNotificationPolicyAccessGranted());
                info.put("notificationsEnabled", areAppNotificationsEnabled(notificationManager));
                info.put("notificationsPermission", Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED);
                info.put("queryAllPackagesGranted", checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED);
                info.put("getInstalledAppsPermissionDefined", installedAppsPermissionDefined());
                info.put("getInstalledAppsPermissionGranted", installedAppsPermissionDefined() && checkSelfPermission(PERM_GET_INSTALLED_APPS) == PackageManager.PERMISSION_GRANTED);
                info.put("ramPermissionRequired", false);
                info.put("appsFound", scanTotal);
                info.put("scanError", scanError == null ? "" : scanError.trim());
                return info.toString();
            } catch (Throwable error) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String getPermissionStatus() {
            try {
                JSONObject status = new JSONObject();
                NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                status.put("sdk", Build.VERSION.SDK_INT);
                status.put("ramPermissionRequired", false);
                status.put("queryAllPackagesGranted", checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED);
                status.put("oemAppsPermissionDefined", installedAppsPermissionDefined());
                status.put("oemAppsPermissionGranted", installedAppsPermissionDefined()
                        && checkSelfPermission(PERM_GET_INSTALLED_APPS) == PackageManager.PERMISSION_GRANTED);
                status.put("notificationsPermission", Build.VERSION.SDK_INT < 33
                        || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED);
                status.put("notificationsEnabled", areAppNotificationsEnabled(notifications));
                status.put("overlay", Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(MainActivity.this));
                status.put("writeSettings", Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(MainActivity.this));
                status.put("dnd", notifications != null && notifications.isNotificationPolicyAccessGranted());
                status.put("shizuku", ShizukuShell.state());
                status.put("appsFound", scanTotal);
                status.put("scanError", scanError == null ? "" : scanError.trim());
                return status.toString();
            } catch (Throwable error) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void requestPostNotificationsPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_NOTIFICATIONS);
                } else {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                        startActivity(intent);
                    } catch (Throwable error) {
                        openAppSettings();
                    }
                }
            });
        }

        @JavascriptInterface
        public String getInstalledApps() {
            scanError = "";
            scanFailed = 0;
            scanLauncherCount = 0;
            scanInstalledCount = 0;
            scanTotal = 0;
            Map<String, JSONObject> unique = new HashMap<>();
            try {
                PackageManager pm = getPackageManager();

                // Pacotes que se declaram jogo pela categoria da atividade (quando o app informa).
                Set<String> gamePackages = new HashSet<>();
                try {
                    Intent gameQuery = new Intent(Intent.ACTION_MAIN);
                    gameQuery.addCategory("android.intent.category.GAME");
                    for (ResolveInfo item : pm.queryIntentActivities(gameQuery, 0)) {
                        if (item.activityInfo != null) gamePackages.add(item.activityInfo.packageName);
                    }
                } catch (Throwable ignored) { }

                // Fonte 1: atividades de tela inicial (launcher).
                try {
                    Intent launcher = new Intent(Intent.ACTION_MAIN);
                    launcher.addCategory(Intent.CATEGORY_LAUNCHER);
                    for (ResolveInfo item : pm.queryIntentActivities(launcher, 0)) {
                        if (item.activityInfo == null || item.activityInfo.applicationInfo == null) continue;
                        if (addAppRow(pm, unique, item.activityInfo.applicationInfo, item.loadLabel(pm), gamePackages)) {
                            scanLauncherCount++;
                        }
                    }
                } catch (Throwable e) {
                    scanError += "launcher-query:" + e.getClass().getSimpleName() + " ";
                }

                // Fonte 2: API LauncherApps, usada também por launchers do próprio Android.
                try {
                    LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
                    if (launcherApps != null) {
                        List<LauncherActivityInfo> launcherItems = launcherApps.getActivityList(null, android.os.Process.myUserHandle());
                        for (LauncherActivityInfo item : launcherItems) {
                            if (item == null) continue;
                            ApplicationInfo app = item.getApplicationInfo();
                            if (app == null) continue;
                            if (addAppRow(pm, unique, app, item.getLabel(), gamePackages)) scanLauncherCount++;
                        }
                    }
                } catch (Throwable e) {
                    scanError += "launcher-apps:" + e.getClass().getSimpleName() + " ";
                }

                // Fonte 3: pacotes instalados que tenham uma intent de abertura.
                // Cobre aparelhos em que a consulta acima volta incompleta.
                try {
                    @SuppressWarnings("deprecation")
                    List<ApplicationInfo> installed = pm.getInstalledApplications(
                            PackageManager.GET_META_DATA | PackageManager.MATCH_ALL);
                    for (ApplicationInfo app : installed) {
                        if (app == null || app.packageName == null || !app.enabled) continue;
                        if (unique.containsKey(app.packageName)) continue;
                        if (pm.getLaunchIntentForPackage(app.packageName) == null) continue;
                        if (addAppRow(pm, unique, app, app.loadLabel(pm), gamePackages)) {
                            scanInstalledCount++;
                        }
                    }
                } catch (Throwable e) {
                    scanError += "installed-apps:" + e.getClass().getSimpleName() + " ";
                }
            } catch (Throwable e) {
                scanError += "scan:" + e.getClass().getSimpleName() + " ";
            }

            try {
                List<JSONObject> rows = new ArrayList<>(unique.values());
                Collections.sort(rows, (a, b) -> {
                    boolean gameA = a.optBoolean("isGame");
                    boolean gameB = b.optBoolean("isGame");
                    if (gameA != gameB) return gameA ? -1 : 1;
                    return a.optString("label").compareToIgnoreCase(b.optString("label"));
                });
                JSONArray result = new JSONArray();
                for (JSONObject row : rows) result.put(row);
                scanTotal = rows.size();
                return result.toString();
            } catch (Throwable e) {
                scanError += "result:" + e.getClass().getSimpleName() + " ";
                return "[]";
            }
        }

        @JavascriptInterface
        public String getScanDiagnostics() {
            try {
                JSONObject info = new JSONObject();
                info.put("sdk", Build.VERSION.SDK_INT);
                info.put("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
                info.put("total", scanTotal);
                info.put("launcher", scanLauncherCount);
                info.put("installed", scanInstalledCount);
                info.put("failed", scanFailed);
                info.put("error", scanError);
                info.put("permissionDefined", installedAppsPermissionDefined());
                info.put("permissionGranted", installedAppsPermissionDefined()
                        && checkSelfPermission(PERM_GET_INSTALLED_APPS) == PackageManager.PERMISSION_GRANTED);
                info.put("queryAllPackagesGranted", checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED);
                return info.toString();
            } catch (Throwable e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    showMessage("Não foi possível abrir os ajustes do aplicativo.");
                }
            });
        }

        @JavascriptInterface
        public void requestInstalledAppsPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && installedAppsPermissionDefined()) {
                    try {
                        if (checkSelfPermission(PERM_GET_INSTALLED_APPS) == PackageManager.PERMISSION_GRANTED) {
                            showMessage("A permissão de lista de apps já está concedida. Atualizando a biblioteca.");
                            if (webView != null) webView.postDelayed(() -> webView.evaluateJavascript(
                                    "if(window.refreshInstalledGames){window.refreshInstalledGames();}", null), 250);
                        } else {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putBoolean("asked_installed_apps_permission", false).apply();
                            MainActivity.this.requestPermissions(new String[]{PERM_GET_INSTALLED_APPS}, REQUEST_INSTALLED_APPS);
                        }
                        return;
                    } catch (Throwable ignored) { }
                }
                showMessage("Este Android não oferece uma permissão separada para a lista. O Gamer Turbo usa QUERY_ALL_PACKAGES declarada no APK; não existe janela de autorização para essa permissão.");
            });
        }

        @JavascriptInterface
        public boolean launchGame(String packageName) {
            if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
                showMessage("Identificador do aplicativo inválido.");
                return false;
            }
            PackageManager pm = getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                showMessage("Aplicativo não encontrado. Atualize a biblioteca.");
                return false;
            }
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putString("active_game_package", packageName)
                    .putLong("game_session_started", System.currentTimeMillis()).apply();
            if (prefs.getBoolean("overlay_enabled", false) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(MainActivity.this))) {
                runOnUiThread(MainActivity.this::startOverlayService);
            }
            String selectedMode = prefs.getString("game_mode", "normal");
            float maximumRefreshRate = getMaximumRefreshRate();
            new Thread(() -> {
                String profileReport = GameOptimizer.apply(MainActivity.this, packageName, selectedMode, maximumRefreshRate, isThermalSafeForGovernor());
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("gt_last_profile_report", profileReport).apply();
                runOnUiThread(() -> {
                    try {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        gameLaunchedInThisTask = true;
                        startActivity(launchIntent);
                        if (!"normal".equals(selectedMode)) {
                            String brief = profileReport == null ? "Perfil solicitado." : profileReport.split("\\n")[0];
                            Toast.makeText(MainActivity.this, brief, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        gameLaunchedInThisTask = false;
                        new Thread(() -> GameOptimizer.restore(MainActivity.this), "GamerTurbo-LaunchRollback").start();
                        Toast.makeText(MainActivity.this, "Não foi possível abrir esse aplicativo.", Toast.LENGTH_LONG).show();
                    }
                });
            }, "GamerTurbo-ApplyProfile").start();
            return true;
        }

        private boolean isThermalSafeForGovernor() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
            try {
                PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                return power == null || power.getCurrentThermalStatus() <= PowerManager.THERMAL_STATUS_LIGHT;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private float getMaximumRefreshRate() {
            try {
                android.view.Display display = getWindowManager().getDefaultDisplay();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    float maximum = 0f;
                    for (android.view.Display.Mode displayMode : display.getSupportedModes()) {
                        maximum = Math.max(maximum, displayMode.getRefreshRate());
                    }
                    if (maximum > 0f) return maximum;
                }
                return display.getRefreshRate();
            } catch (Throwable ignored) {
                return 60f;
            }
        }

        @JavascriptInterface
        public String getShizukuState() { return ShizukuShell.state(); }

        @JavascriptInterface
        public String getLastProfileReport() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("gt_last_profile_report", "Nenhum perfil de jogo foi aplicado nesta instalação.");
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            runOnUiThread(MainActivity.this::requestShizukuPermissionInternal);
        }

        @JavascriptInterface
        public String inspectKernel() {
            if (!ShizukuShell.isAuthorized()) return "Shizuku não autorizado.";
            // Read-only capability probe. It reports writable nodes, but never writes blindly to sysfs.
            String command = "echo '=== IDENTIDADE / ANDROID ==='; id; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; "
                    + "echo '=== CPU ==='; for d in /sys/devices/system/cpu/cpufreq/policy* /sys/devices/system/cpu/cpu*/cpufreq; do [ -d \"$d\" ] || continue; "
                    + "for n in scaling_cur_freq scaling_max_freq scaling_governor scaling_available_governors; do f=\"$d/$n\"; [ -r \"$f\" ] && echo \"$f: $(cat \"$f\") [$( [ -w \"$f\" ] && echo WRITE || echo READ_ONLY )]\"; done; done; "
                    + "echo '=== GPU / DEVFREQ ==='; for d in /sys/class/devfreq/* /sys/class/kgsl/kgsl-3d0; do [ -d \"$d\" ] || continue; "
                    + "for n in cur_freq max_freq governor available_governors gpuclk; do f=\"$d/$n\"; [ -r \"$f\" ] && echo \"$f: $(cat \"$f\") [$( [ -w \"$f\" ] && echo WRITE || echo READ_ONLY )]\"; done; done; "
                    + "echo '=== THERMAL ==='; for f in /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/temp; do [ -r \"$f\" ] && echo \"$f: $(cat \"$f\")\"; done; "
                    + "echo '=== GAME MODE ==='; cmd game help 2>&1 | head -40; "
                    + "echo '=== FREQUENCY SETTINGS ==='; settings get system peak_refresh_rate; settings get system min_refresh_rate; settings get global low_power";
            ShizukuShell.Result r = ShizukuShell.execute(MainActivity.this, command);
            return r.output.isEmpty() ? (r.success ? "Nenhuma interface de leitura exposta." : "Falha: " + r.output) : r.output;
        }

        @JavascriptInterface
        public boolean cleanRam() {
            try {
                ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                if (manager != null) manager.getMemoryInfo(memory);
                long used = Math.max(0L, memory.totalMem - memory.availMem);
                long total = memory.totalMem;
                int percent = total > 0 ? Math.round(used * 100f / total) : 0;
                String message = "RAM em uso: " + Math.round(used / 1048576f) + " MB / "
                        + Math.round(total / 1048576f) + " MB (" + percent + "%). O Android gerencia a memória; nenhum processo foi encerrado à força.";
                runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
                return true;
            } catch (Throwable error) {
                return false;
            }
        }

        @JavascriptInterface
        public String getDeviceStats() {
            JSONObject result = new JSONObject();
            try {
                result.put("sdk", Build.VERSION.SDK_INT);
                result.put("ramPermissionRequired", false);

                // Bateria: leitura do broadcast do sistema, sem permissão em tempo de execução.
                try {
                    Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (battery != null) {
                        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        int tempTenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        int percentage = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
                        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                                || status == BatteryManager.BATTERY_STATUS_FULL;
                        result.put("batteryLevel", percentage);
                        result.put("charging", charging);
                        result.put("batteryTempC", tempTenths >= 0 ? tempTenths / 10.0 : -1);
                    } else {
                        result.put("batteryError", "Broadcast de bateria indisponível");
                    }
                } catch (Throwable batteryError) {
                    result.put("batteryError", batteryError.getClass().getSimpleName());
                }

                // RAM do sistema: ActivityManager.MemoryInfo não exige permissão especial.
                try {
                    ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    if (activityManager != null) {
                        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                        activityManager.getMemoryInfo(memory);
                        long total = Math.max(0L, memory.totalMem);
                        long available = Math.max(0L, Math.min(memory.availMem, total));
                        long used = Math.max(0L, total - available);
                        int percent = total > 0 ? Math.max(0, Math.min(100, Math.round(used * 100f / total))) : -1;
                        result.put("ramAvailable", total > 0);
                        result.put("ramUsedMB", Math.round(used / 1048576f));
                        result.put("ramTotalMB", Math.round(total / 1048576f));
                        result.put("ramPercent", percent);
                        result.put("ramAvailableMB", Math.round(available / 1048576f));
                        result.put("lowMemory", memory.lowMemory);
                        result.put("ramDataSource", "ActivityManager.MemoryInfo");
                    } else {
                        result.put("ramAvailable", false);
                        result.put("ramError", "ActivityManager indisponível");
                    }
                } catch (Throwable memoryError) {
                    result.put("ramAvailable", false);
                    result.put("ramError", memoryError.getClass().getSimpleName() + ": "
                            + (memoryError.getMessage() == null ? "falha de leitura" : memoryError.getMessage()));
                }

                // Estado térmico público do Android (API 29+), sem Shizuku.
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                        if (power != null) result.put("thermalStatus", thermalLabel(power.getCurrentThermalStatus()));
                        else result.put("thermalStatus", "indisponível");
                    } else {
                        result.put("thermalStatus", "indisponível");
                    }
                } catch (Throwable thermalError) {
                    result.put("thermalStatus", "indisponível");
                    result.put("thermalError", thermalError.getClass().getSimpleName());
                }
                result.put("success", true);
            } catch (Throwable error) {
                try {
                    result.put("success", false);
                    result.put("error", error.getClass().getSimpleName() + ": "
                            + (error.getMessage() == null ? "falha desconhecida" : error.getMessage()));
                } catch (Throwable ignored) { }
            }
            return result.toString();
        }

        @JavascriptInterface
        public boolean hasNotificationPolicyAccess() {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return manager != null && manager.isNotificationPolicyAccessGranted();
        }

        @JavascriptInterface
        public void requestNotificationPolicyAccess() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ajuste Não Perturbe não disponível.", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public boolean isGameDndEnabled() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("game_dnd_active", false);
        }

        @JavascriptInterface
        public boolean setGameDnd(boolean enabled) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null || !manager.isNotificationPolicyAccessGranted()) return false;
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            try {
                if (enabled) {
                    if (!prefs.getBoolean("game_dnd_active", false)) {
                        prefs.edit().putInt("previous_dnd_filter", manager.getCurrentInterruptionFilter()).apply();
                    }
                    // ALARMS (só alarmes): "silêncio total" (NONE) também silencia música e jogos.
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
                    prefs.edit().putBoolean("game_dnd_active", true).apply();
                } else {
                    int previous = prefs.getInt("previous_dnd_filter", NotificationManager.INTERRUPTION_FILTER_ALL);
                    manager.setInterruptionFilter(previous);
                    prefs.edit().putBoolean("game_dnd_active", false).remove("previous_dnd_filter").apply();
                }
                return true;
            } catch (SecurityException e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean canWriteSettings() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(MainActivity.this);
        }

        @JavascriptInterface
        public void requestWriteSettings() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Permissão de brilho não disponível.", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public int getSystemBrightness() {
            try {
                return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 166);
            } catch (Exception e) {
                return 166;
            }
        }

        @JavascriptInterface
        public boolean setSystemBrightness(int value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(MainActivity.this)) return false;
            int brightness = Math.max(5, Math.min(255, value));
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                return Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, brightness);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void openWifiSettings() {
            runOnUiThread(() -> {
                try {
                    Intent intent;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        intent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                    } else {
                        intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                    }
                    startActivity(intent);
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
                    catch (Exception ignored) { showMessage("Configurações de Wi-Fi indisponíveis."); }
                }
            });
        }

        @JavascriptInterface
        public void vibrate() {
            try {
                Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(14, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(14);
                    }
                }
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public boolean canDrawOverlays() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void requestOverlayPermission() {
            runOnUiThread(MainActivity.this::requestOverlayPermissionInternal);
        }

        @JavascriptInterface
        public boolean isOverlayEnabled() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("overlay_enabled", false)
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(MainActivity.this));
        }

        @JavascriptInterface
        public boolean setOverlayEnabled(boolean enabled) {
            if (!enabled) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("overlay_enabled", false).putBoolean(KEY_PENDING_OVERLAY, false).apply();
                runOnUiThread(() -> stopService(new Intent(MainActivity.this, GameOverlayService.class)));
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                requestOverlayPermission();
                return false;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("overlay_enabled", true).apply();
            runOnUiThread(MainActivity.this::startOverlayService);
            return true;
        }

        @JavascriptInterface
        public boolean isImmersiveModeEnabled() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("immersive_mode", false);
        }

        @JavascriptInterface
        public boolean setImmersiveMode(boolean enabled) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("immersive_mode", enabled).apply();
            runOnUiThread(() -> applyImmersiveMode(enabled));
            return true;
        }

        @JavascriptInterface
        public String getMode() {
            String mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("game_mode", "normal");
            if (!"normal".equals(mode) && !"balanced".equals(mode) && !"performance".equals(mode)) return "normal";
            return mode;
        }

        @JavascriptInterface
        public void setMode(String mode) {
            if (!("normal".equals(mode) || "balanced".equals(mode) || "performance".equals(mode))) return;
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.edit().putString("game_mode", mode).apply();
            boolean profileActive = prefs.getBoolean("gt_profile_active", false);
            String packageName = prefs.getString("gt_profile_package", "");
            if ("normal".equals(mode)) {
                if (profileActive) new Thread(() -> {
                    String report = GameOptimizer.restore(MainActivity.this);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Modo Básico: ajustes da sessão restaurados.", Toast.LENGTH_SHORT).show());
                }, "GamerTurbo-RestoreMode").start();
            } else if (profileActive && packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
                float maximumRefreshRate = getMaximumRefreshRate();
                new Thread(() -> {
                    String report = GameOptimizer.apply(MainActivity.this, packageName, mode, maximumRefreshRate, isThermalSafeForGovernor());
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("gt_last_profile_report", report).apply();
                }, "GamerTurbo-ChangeMode").start();
            }
        }

        @JavascriptInterface
        public boolean isScreenRecording() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("screen_recording", false);
        }

        @JavascriptInterface
        public void requestScreenRecording() {
            runOnUiThread(() -> {
                if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("screen_recording", false)) return;
                try {
                    MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    if (manager == null) {
                        Toast.makeText(MainActivity.this, "Gravação de tela indisponível neste aparelho.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Não foi possível solicitar a captura de tela.", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void stopScreenRecording() {
            runOnUiThread(() -> {
                Intent stop = new Intent(MainActivity.this, ScreenRecordService.class);
                stop.setAction(ScreenRecordService.ACTION_STOP);
                startService(stop);
            });
        }
    }

    private String loadIconData(PackageManager pm, ApplicationInfo app) {
        Bitmap bitmap = null;
        try {
            Drawable drawable = pm.getApplicationIcon(app);
            int size = 88;
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // WebP reduz bastante o tamanho total da resposta da biblioteca sem perder o ícone.
            boolean encoded = bitmap.compress(Bitmap.CompressFormat.WEBP, 82, out);
            if (!encoded) {
                out.reset();
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
                return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }
            return "data:image/webp;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /** Adiciona um app à lista; um app com problema é ignorado sem derrubar a lista inteira. */
    private boolean addAppRow(PackageManager pm, Map<String, JSONObject> unique, ApplicationInfo app,
                              CharSequence labelValue, Set<String> gamePackages) {
        String packageName = app.packageName;
        if (packageName == null || packageName.equals(getPackageName()) || unique.containsKey(packageName)) return false;
        try {
            String label = labelValue == null ? "" : labelValue.toString().trim();
            if (label.isEmpty()) label = packageName;
            JSONObject row = new JSONObject();
            row.put("label", label);
            row.put("packageName", packageName);
            row.put("isGame", isGameApplication(app, label, packageName, gamePackages.contains(packageName)));
            // ApplicationInfo.category só existe a partir do Android 8; antes disso dava NoSuchFieldError.
            row.put("category", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? app.category : -1);
            row.put("iconData", loadIconData(pm, app));
            unique.put(packageName, row);
            return true;
        } catch (Throwable e) {
            scanFailed++;
            return false;
        }
    }

    // Publicadoras e prefixos de pacote muito usados em jogos.
    private static final String[] GAME_PACKAGE_PREFIXES = {
            "com.supercell.", "com.activision.", "com.garena.", "com.dts.freefire", "com.tencent.ig",
            "com.tencent.tmgp.", "com.proximabeta.", "com.levelinfinite.", "com.krafton.", "com.pubg.",
            "com.riotgames.", "com.mojang.", "com.king.", "com.ea.gp.", "com.gameloft.",
            "com.ubisoft.", "com.rovio.", "com.playrix.", "com.voodoo.", "com.zynga.", "com.moonton.",
            "com.nintendo.", "com.konami.", "com.epicgames.", "com.roblox.", "com.innersloth.",
            "com.firsttouchgames.", "com.nexon.", "com.kiloo.", "com.imangi.", "com.halfbrick.",
            "com.outfit7.", "com.ketchapp.", "com.rockstargames.", "com.scopely.", "com.nianticlabs.",
            "com.lilithgame.", "com.fingersoft.", "com.hypergryph.", "com.miHoYo.", "com.mihoyo.",
            "com.hoyoverse.", "com.hottagames.", "com.sybogames.", "com.playstack.", "com.miniclip.",
            "com.tapblaze.", "com.sega.", "com.bandainamcoent.", "com.square_enix.", "com.gamevil.",
            "com.com2us.", "com.netmarble.", "com.pearlabyss.", "com.habby.", "com.camelgames.",
            "com.blizzard.", "com.mobile.legends", "com.kitkagames.", "com.nekki.",
            "com.ea.gp.fifamobile", "jp.konami.pesam", "com.dts.", "com.hutchgames.",
            "com.netease.", "com.garena.game.", "com.tencent.tmgp.pubgmhd"
    };

    private static final String[] GAME_KEYWORDS = {
            "free fire", "freefire", "garena", "pubg", "bgmi", "call of duty", "callofduty", "cod mobile", "codm",
            "roblox", "minecraft", "genshin", "honkai", "zenless", "wuthering", "mobile legends", "mobilelegends",
            "clash of clans", "clash royale", "brawl stars", "asphalt", "stumble guys", "fortnite", "wild rift",
            "pokemon unite", "pokemon go", "efootball", "fc mobile", "fifa mobile", "dream league", "subway surfers",
            "among us", "terraria", "shadow fight", "standoff", "arena breakout", "league of legends", "valorant",
            "delta force", "blood strike", "sausage man", "geometry dash", "candy crush", "temple run",
            "hill climb", "plants vs", "8 ball pool", "carrom", "solitaire", "sudoku", "ludo", "mahjong",
            "racing", "game launcher", "gamehub", "arcade", "battle royale", "simulator game",
            "mobile legends", "fall buddies", "efootball", "need for speed", "real racing", "asphalt legends",
            "arena of valor", "warzone", "wild rift", "battlegrounds", "mortal kombat", "dragon ball legends"
    };

    @SuppressWarnings("deprecation")
    private boolean isGameApplication(ApplicationInfo app, String label, String packageName, boolean gameCategoryMatch) {
        if (gameCategoryMatch) return true;
        // Apps que se marcam como jogo no manifest (android:isGame) ou pela categoria do Android 8+.
        if ((app.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && app.category == ApplicationInfo.CATEGORY_GAME) return true;
        String pkg = packageName.toLowerCase(Locale.ROOT);
        for (String prefix : GAME_PACKAGE_PREFIXES) if (pkg.startsWith(prefix.toLowerCase(Locale.ROOT))) return true;
        // Muitos jogos independentes usam pacotes com segmentos game/games sem informar CATEGORY_GAME.
        if (pkg.matches(".*[.](game|games|gaming)[.].*") || pkg.endsWith(".game") || pkg.endsWith(".games")) return true;
        String value = (label + " " + pkg).toLowerCase(Locale.ROOT)
                .replace("_", " ").replace("-", " ").replace(".", " ");
        for (String term : GAME_KEYWORDS) if (value.contains(term)) return true;
        return false;
    }

    private String thermalLabel(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_LIGHT: return "leve";
            case PowerManager.THERMAL_STATUS_MODERATE: return "moderado";
            case PowerManager.THERMAL_STATUS_SEVERE: return "alto";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "crítico";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "emergência";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "desligamento";
            default: return "normal";
        }
    }

    private void showMessage(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("(function(){return window.handleAndroidBack?window.handleAndroidBack():false})()", value -> {
                if ("false".equals(value) || value == null) MainActivity.super.onBackPressed();
            });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) { }
        if (webView != null) {
            webView.removeJavascriptInterface("GamerTurboAndroid");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
