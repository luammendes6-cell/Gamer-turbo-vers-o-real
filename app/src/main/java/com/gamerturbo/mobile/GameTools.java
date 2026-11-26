package com.gamerturbo.mobile;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ações reais de desempenho: limpar RAM, fechar apps e priorizar o jogo.
 * Todas medem o resultado (antes/depois) e dizem claramente o que NÃO foi possível.
 * Os métodos bloqueiam (sleep + comandos): chame sempre fora da thread principal.
 */
public final class GameTools {
    private static final String PKG_REGEX = "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+";
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private GameTools() { }

    public static boolean validPackage(String value) {
        return value != null && value.matches(PKG_REGEX);
    }

    public static long availMb(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return -1;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return Math.round(info.availMem / 1048576f);
    }

    /** Pacotes que nunca devem ser fechados. */
    static Set<String> protectedPackages(Context context, String gamePackage) {
        Set<String> keep = new HashSet<>();
        keep.add(context.getPackageName());
        keep.add("com.android.systemui");
        keep.add(SHIZUKU_PACKAGE);
        if (validPackage(gamePackage)) keep.add(gamePackage);
        // Exceções escolhidas pelo usuário: nunca encerrar esses apps na limpeza/boost.
        try {
            java.util.Set<String> whitelist = context.getSharedPreferences("gamer_turbo_native", Context.MODE_PRIVATE)
                    .getStringSet("memory_whitelist", java.util.Collections.emptySet());
            if (whitelist != null) {
                for (String pkg : whitelist) if (validPackage(pkg)) keep.add(pkg);
            }
        } catch (Throwable ignored) { }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> launchers = context.getPackageManager().queryIntentActivities(home, 0);
            if (launchers != null) {
                for (ResolveInfo info : launchers) {
                    if (info.activityInfo != null) keep.add(info.activityInfo.packageName);
                }
            }
        } catch (Throwable ignored) { }
        try {
            String ime = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.contains("/")) keep.add(ime.substring(0, ime.indexOf('/')));
        } catch (Throwable ignored) { }
        return keep;
    }

    /** Apps instalados pelo usuário (não são do sistema) e que não estão parados. */
    static List<String> thirdPartyPackages(Context context, Set<String> keep) {
        List<String> result = new ArrayList<>();
        try {
            for (ApplicationInfo app : context.getPackageManager().getInstalledApplications(0)) {
                if ((app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                if ((app.flags & ApplicationInfo.FLAG_STOPPED) != 0) continue;
                if (!validPackage(app.packageName) || keep.contains(app.packageName)) continue;
                result.add(app.packageName);
            }
        } catch (Throwable ignored) { }
        return result;
    }

    private static String label(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            CharSequence text = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return text == null ? pkg : text.toString();
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    /** Quais pacotes da lista têm processo vivo agora (exige Shizuku). */
    private static Set<String> runningAmong(Context context, List<String> packages) {
        Set<String> running = new LinkedHashSet<>();
        for (int i = 0; i < packages.size(); i += 40) {
            List<String> batch = packages.subList(i, Math.min(packages.size(), i + 40));
            StringBuilder cmd = new StringBuilder("for p in");
            for (String p : batch) cmd.append(' ').append(p);
            cmd.append("; do pidof -s \"$p\" >/dev/null 2>&1 && echo \"GTRUN|$p\"; done");
            ShizukuShell.Result r = ShizukuShell.execute(context, cmd.toString());
            if (r.output == null) continue;
            for (String line : r.output.split("\\r?\\n")) {
                if (line.startsWith("GTRUN|")) {
                    String p = line.substring(6).trim();
                    if (validPackage(p)) running.add(p);
                }
            }
        }
        return running;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String mb(long value) {
        return value >= 1024 ? String.format(Locale.US, "%.2f GB", value / 1024f) : value + " MB";
    }

    /** LIMPAR RAM: encerra só processos de segundo plano de apps de terceiros e mede a RAM livre. */
    public static String cleanRam(Context context, String gamePackage, long settleMs) {
        long before = availMb(context);
        Set<String> keep = protectedPackages(context, gamePackage);
        List<String> targets = thirdPartyPackages(context, keep);
        if (targets.isEmpty()) return "LIMPAR RAM\n• Nenhum app de terceiros em segundo plano para encerrar.\n• RAM livre agora: " + mb(before);

        StringBuilder report = new StringBuilder("LIMPAR RAM\n");
        if (ShizukuShell.isAuthorized()) {
            Set<String> runningBefore = runningAmong(context, targets);
            for (int i = 0; i < targets.size(); i += 40) {
                List<String> batch = targets.subList(i, Math.min(targets.size(), i + 40));
                StringBuilder cmd = new StringBuilder("for p in");
                for (String p : batch) cmd.append(' ').append(p);
                cmd.append("; do am kill --user 0 \"$p\" >/dev/null 2>&1; done");
                ShizukuShell.execute(context, cmd.toString());
            }
            sleepQuietly(settleMs);
            Set<String> runningAfter = runningAmong(context, targets);
            int closed = 0;
            for (String p : runningBefore) if (!runningAfter.contains(p)) closed++;
            report.append("• Método: Shizuku (am kill, só processos em segundo plano)\n");
            report.append("• Apps com processo ativo: ").append(runningBefore.size()).append(" → ").append(runningAfter.size())
                    .append(" (").append(closed).append(" encerrado(s))\n");
        } else {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            int requested = 0;
            boolean denied = false;
            if (am != null) {
                for (String p : targets) {
                    try { am.killBackgroundProcesses(p); requested++; }
                    catch (SecurityException e) { denied = true; break; }
                    catch (Throwable ignored) { }
                }
            }
            sleepQuietly(settleMs);
            if (denied) {
                report.append("• O Android negou a permissão KILL_BACKGROUND_PROCESSES.\n");
            } else {
                report.append("• Método: Android (sem Shizuku). Pedido enviado a ").append(requested)
                        .append(requested == 1 ? " aplicativo.\n" : " aplicativos.\n");
                report.append("• O Android decide o que realmente fecha; conecte o Shizuku para limpeza mais forte e conferida.\n");
            }
        }
        long after = availMb(context);
        long delta = after - before;
        report.append("• RAM livre: ").append(mb(before)).append(" → ").append(mb(after))
                .append(" (").append(delta >= 0 ? "+" : "").append(delta).append(" MB)");
        return report.toString();
    }

    /** FECHAR APPS: force-stop dos apps de terceiros em execução, exceto os protegidos. Exige Shizuku. */
    public static String closeApps(Context context, String gamePackage) {
        if (!ShizukuShell.isAuthorized()) {
            return "FECHAR APPS\n• Precisa do Shizuku conectado e autorizado. Nada foi fechado.";
        }
        long before = availMb(context);
        Set<String> keep = protectedPackages(context, gamePackage);
        List<String> candidates = thirdPartyPackages(context, keep);
        List<String> running = new ArrayList<>(runningAmong(context, candidates));
        if (running.isEmpty()) {
            return "FECHAR APPS\n• Nenhum app de terceiros estava em execução além dos protegidos.\n• RAM livre: " + mb(before);
        }
        for (int i = 0; i < running.size(); i += 40) {
            List<String> batch = running.subList(i, Math.min(running.size(), i + 40));
            StringBuilder cmd = new StringBuilder("for p in");
            for (String p : batch) cmd.append(' ').append(p);
            cmd.append("; do am force-stop \"$p\" >/dev/null 2>&1; done");
            ShizukuShell.execute(context, cmd.toString());
        }
        sleepQuietly(900);
        Set<String> still = runningAmong(context, running);
        int closed = running.size() - still.size();
        long after = availMb(context);
        StringBuilder report = new StringBuilder("FECHAR APPS\n");
        report.append("• Fechados e conferidos: ").append(closed).append(" de ").append(running.size()).append('\n');
        int shown = 0;
        for (String p : running) {
            if (still.contains(p)) continue;
            if (shown++ >= 6) break;
            report.append("  – ").append(label(context, p)).append('\n');
        }
        if (closed > shown) report.append("  … e mais ").append(closed - shown).append('\n');
        if (!still.isEmpty()) report.append("• Não fecharam: ").append(still.size()).append(" (o sistema os reiniciou ou são protegidos)\n");
        report.append("• Mantidos: launcher, teclado, sistema, Shizuku e o jogo.\n");
        report.append("• RAM livre: ").append(mb(before)).append(" → ").append(mb(after))
                .append(" (").append(after - before >= 0 ? "+" : "").append(after - before).append(" MB)");
        return report.toString();
    }

    /** PRIORIDADE: dá ao jogo o melhor tratamento que o shell permite e confere cada passo. */
    public static String prioritizeGame(Context context, String gamePackage) {
        if (!validPackage(gamePackage)) {
            return "PRIORIDADE\n• Nenhum jogo ativo. Abra o jogo pelo botão JOGAR do Gamer Turbo e tente de novo.";
        }
        if (!ShizukuShell.isAuthorized()) {
            return "PRIORIDADE\n• Precisa do Shizuku conectado e autorizado. Nada foi alterado.";
        }
        StringBuilder report = new StringBuilder("PRIORIDADE • ").append(label(context, gamePackage)).append('\n');

        ShizukuShell.Result bucket = ShizukuShell.execute(context,
                "am set-standby-bucket " + gamePackage + " active && am get-standby-bucket " + gamePackage);
        boolean bucketOk = bucket.success && bucket.output != null
                && (bucket.output.trim().matches("(?s).*\\b10\\b.*") || bucket.output.toLowerCase(Locale.ROOT).contains("active"));
        report.append(bucketOk ? "• Jogo marcado como ATIVO para o sistema (sem restrições de segundo plano) ✔\n"
                : bucket.success ? "• Pedido de prioridade enviado, mas o Android não confirmou o valor\n"
                : "• O Android recusou a prioridade de energia para o jogo\n");

        ShizukuShell.Result nice = ShizukuShell.execute(context,
                "pid=$(pidof -s " + gamePackage + "); [ -n \"$pid\" ] || { echo GTNOPID; exit 0; }; "
                        + "renice -n -10 -p $pid >/dev/null 2>&1; "
                        + "echo \"GTNICE|$(sed 's/.*) //' /proc/$pid/stat | awk '{print $17}')\"");
        String out = nice.output == null ? "" : nice.output;
        if (out.contains("GTNOPID")) {
            report.append("• O jogo ainda não está em execução; abra-o e toque em PRIORIDADE de novo.\n");
        } else {
            int value = Integer.MAX_VALUE;
            for (String line : out.split("\\r?\\n")) {
                if (line.startsWith("GTNICE|")) {
                    try { value = Integer.parseInt(line.substring(7).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            report.append(value < 0
                    ? "• Prioridade de CPU do processo do jogo elevada (nice " + value + ") ✔"
                    : "• Prioridade de CPU NÃO alterada: o Android só permite isso com root (nice atual: "
                            + (value == Integer.MAX_VALUE ? "?" : String.valueOf(value)) + ")");
        }
        return report.toString().trim();
    }

    /** BOOST: prioridade + fechar apps + limpar RAM, em sequência. */
    public static String boost(Context context, String gamePackage) {
        StringBuilder all = new StringBuilder("BOOST COMPLETO\n\n");
        all.append(prioritizeGame(context, gamePackage)).append("\n\n");
        all.append(closeApps(context, gamePackage)).append("\n\n");
        all.append(cleanRam(context, gamePackage, 900));
        return all.toString();
    }

    /** Relatório de compatibilidade: o que este aparelho consegue fazer em cada cenário. */
    public static String compatibility(Context context) {
        StringBuilder r = new StringBuilder("COMPATIBILIDADE DESTE APARELHO\n");
        r.append("• ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" • Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        String state = ShizukuShell.state();
        r.append("• Shizuku: ").append("READY".equals(state) ? "conectado e autorizado ✔"
                : "NEED_PERMISSION".equals(state) ? "rodando, falta autorizar o Gamer Turbo" : "desligado ou não instalado").append('\n');
        boolean root = new File("/system/bin/su").exists() || new File("/system/xbin/su").exists()
                || new File("/sbin/su").exists() || new File("/su/bin/su").exists();
        r.append("• Root: ").append(root ? "su encontrado (esta versão não usa root)" : "não detectado").append('\n');
        float maxHz = 60f;
        try {
            android.view.WindowManager wm = (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            for (android.view.Display.Mode m : wm.getDefaultDisplay().getSupportedModes()) maxHz = Math.max(maxHz, m.getRefreshRate());
        } catch (Throwable ignored) { }
        r.append("• Tela: até ").append(Math.round(maxHz)).append(" Hz\n\nSEM SHIZUKU\n• LIMPAR RAM básico ✔ (o Android decide o que fecha)\n• Modos ECO/RISE/DIABLO: não alteram o sistema\n\nCOM SHIZUKU\n");
        if ("READY".equals(state)) {
            ShizukuShell.Result help = ShizukuShell.execute(context, "cmd game help 2>&1 | head -30");
            boolean gameMode = help.output != null && help.output.contains("list-modes");
            r.append(gameMode ? "• Game Mode por jogo ✔\n" : "• Game Mode por jogo: não suportado (precisa Android 12+)\n");
            ShizukuShell.Result gov = ShizukuShell.execute(context,
                    "n=0; for f in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor /sys/class/devfreq/*/governor; do [ -w \"$f\" ] && n=$((n+1)); done; echo GTW\\|$n");
            int writable = 0;
            if (gov.output != null) for (String line : gov.output.split("\\r?\\n")) {
                if (line.startsWith("GTW|")) { try { writable = Integer.parseInt(line.substring(4).trim()); } catch (NumberFormatException ignored) { } }
            }
            r.append(writable > 0 ? "• Governador CPU/GPU gravável em " + writable + " interface(s) ✔\n"
                    : "• Governador CPU/GPU: bloqueado pelo kernel (sem root, é o normal)\n");
        }
        r.append("• Economia de bateria, taxa da tela, FECHAR APPS, PRIORIDADE de energia ✔\n");
        r.append("• Overclock e prioridade de CPU (nice): só com root/kernel liberado ✘\n\nCOM ROOT\n• Não usado nesta versão (nada é executado como root).");
        return r.toString();
    }
}
