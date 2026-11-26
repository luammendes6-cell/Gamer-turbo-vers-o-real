package com.gamerturbo.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Reversible, per-game Android tuning. Every write is read back by the system command result. */
public final class GameOptimizer {
    private static final String PREFS = "gamer_turbo_native";
    private static final String ACTIVE = "gt_profile_active";
    private static final String PACKAGE = "gt_profile_package";
    private static final String PREV_LOW_POWER = "gt_prev_low_power";
    private static final String PREV_PEAK = "gt_prev_peak_refresh";
    private static final String PREV_MIN = "gt_prev_min_refresh";
    private static final String PREV_GOVERNORS = "gt_prev_governors";
    private static final String SNAPSHOT = "gt_profile_snapshot";
    private static final String PREV_GAME_MODE = "gt_prev_game_mode";
    private static final String GAME_MODE_CAPTURED = "gt_game_mode_captured";
    private static final String GAME_MODE_CHANGED = "gt_game_mode_changed";
    private static final String GAME_CUSTOM_OVERRIDE = "gt_game_custom_override";

    private GameOptimizer() { }

    private static final String PKG_REGEX = "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+";

    /** Aceita os ids antigos (normal/balanced/performance) e devolve eco/balance/rise/diablo. */
    public static String normalizeMode(String mode) {
        if (mode == null) return "balance";
        switch (mode) {
            case "eco": case "balance": case "rise": case "diablo": return mode;
            case "normal": return "balance";
            case "balanced": return "rise";
            case "performance": return "diablo";
            default: return "";
        }
    }

    public static String modeLabel(String mode) {
        switch (normalizeMode(mode)) {
            case "eco": return "ECO";
            case "rise": return "RISE";
            case "diablo": return "DIABLO";
            default: return "BALANCE";
        }
    }

    public static synchronized String apply(Context context, String packageName, String modeInput, float maxRefreshRate, boolean thermalSafeForGovernor) {
        String mode = normalizeMode(modeInput);
        if (mode.isEmpty()) return "Perfil desconhecido.";
        String pkg = packageName == null ? "" : packageName;
        boolean hasGame = pkg.matches(PKG_REGEX);
        if (!pkg.isEmpty() && !hasGame) {
            return "Pacote de jogo inválido. Nenhuma alteração foi aplicada.";
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if ("balance".equals(mode)) {
            return restore(context);
        }
        if (!ShizukuShell.isAuthorized()) {
            return "O modo " + modeLabel(mode) + " precisa do Shizuku conectado e autorizado para alterar o sistema. Nada foi alterado.";
        }

        String oldPackage = prefs.getString(PACKAGE, "");
        boolean wasActive = prefs.getBoolean(ACTIVE, false);
        String oldMode = normalizeMode(prefs.getString("gt_profile_mode", ""));
        if (wasActive && pkg.equals(oldPackage) && mode.equals(oldMode)
                && prefs.getBoolean(SNAPSHOT, false)) {
            return prefs.getString("gt_last_profile_report", "Este perfil já está aplicado nesta sessão.");
        }
        if (wasActive && (!pkg.equals(oldPackage) || !mode.equals(oldMode))) {
            String restored = restore(context);
            if (prefs.getBoolean(SNAPSHOT, false)) {
                return "O perfil anterior ainda não pôde ser totalmente restaurado. "
                        + "O novo perfil não foi aplicado para evitar misturar configurações.\n" + restored;
            }
        }

        String governorTarget = "eco".equals(mode) ? "powersave" : "performance";
        boolean snapshotExists = prefs.getBoolean(SNAPSHOT, false);
        if (!snapshotExists) {
            Snapshot snapshot = readSnapshot(context);
            if (!snapshot.valid) return "Não foi possível ler e guardar as configurações originais. O perfil não será aplicado para evitar deixar alterações sem restauração.";
            String governors = readWritableGovernorSnapshot(context, governorTarget);
            GameModeSnapshot gameSnapshot = hasGame ? readGameModeSnapshot(context, pkg) : new GameModeSnapshot("", false, true);
            boolean snapshotSaved = prefs.edit()
                    .putString(PREV_LOW_POWER, snapshot.lowPower)
                    .putString(PREV_PEAK, snapshot.peak)
                    .putString(PREV_MIN, snapshot.min)
                    .putString(PREV_GOVERNORS, governors)
                    .putString(PREV_GAME_MODE, gameSnapshot.mode)
                    .putBoolean(GAME_MODE_CAPTURED, gameSnapshot.valid)
                    .putBoolean(GAME_MODE_CHANGED, false)
                    .putBoolean(GAME_CUSTOM_OVERRIDE, false)
                    .putString(PACKAGE, pkg)
                    .putBoolean(ACTIVE, true)
                    .putString("gt_profile_mode", mode)
                    .putBoolean(SNAPSHOT, true)
                    .commit();
            if (!snapshotSaved) {
                return "Não foi possível salvar o estado original da sessão. Nenhuma otimização foi aplicada.";
            }
        }

        float hz = normalizeRefreshRate(maxRefreshRate);
        int fps = chooseFps(hz);
        StringBuilder report = new StringBuilder();
        report.append("MODO ").append(modeLabel(mode)).append(hasGame ? "" : "  (sem jogo ativo: só ajustes do sistema)").append('\n');

        // ---------- Game Mode do Android (por jogo) ----------
        boolean gameModeMayHaveChanged = false;
        boolean customOverrideApplied = false;
        if (!hasGame) {
            report.append("• Game Mode: será aplicado quando você abrir um jogo\n");
        } else {
            boolean gameModeApplied = false;
            boolean gameModeCaptured = prefs.getBoolean(GAME_MODE_CAPTURED, false);
            String previousGameMode = prefs.getString(PREV_GAME_MODE, "");
            if (gameModeCaptured && !"unsupported".equals(previousGameMode)) {
                if ("diablo".equals(mode)) {
                    ShizukuShell.Result help = ShizukuShell.execute(context, "cmd game help 2>&1");
                    boolean supportsSet = help.output != null
                            && help.output.toLowerCase(Locale.ROOT).contains("--downscale");
                    if (supportsSet) {
                        GameModeSnapshot existing = readGameModeSnapshot(context, pkg);
                        if (existing.valid && !existing.hasExistingConfig) {
                            gameModeMayHaveChanged = true;
                            prefs.edit().putBoolean(GAME_MODE_CHANGED, true)
                                    .putBoolean(GAME_CUSTOM_OVERRIDE, true).commit();
                            ShizukuShell.Result custom = ShizukuShell.execute(context,
                                    "cmd game set --downscale 0.9 --fps " + fps + " " + pkg);
                            if (custom.success) {
                                customOverrideApplied = true;
                                ShizukuShell.Result selected = setGameModeAndVerify(context, "custom", pkg);
                                if (selected.success) {
                                    gameModeApplied = true;
                                    report.append("• Game Mode PERSONALIZADO ativo: escala 0,90 e alvo ").append(fps).append(" FPS ✔\n");
                                } else {
                                    ShizukuShell.Result reset = ShizukuShell.execute(context, "cmd game reset " + pkg);
                                    customOverrideApplied = !reset.success;
                                    report.append("• Modo personalizado não pôde ser selecionado; tentando Performance nativo\n");
                                }
                            } else {
                                ShizukuShell.Result reset = ShizukuShell.execute(context, "cmd game reset " + pkg);
                                customOverrideApplied = !reset.success;
                                report.append("• Intervenção personalizada recusada pela ROM\n");
                            }
                        } else if (existing.valid) {
                            report.append("• Intervenções existentes do jogo preservadas; não foram sobrescritas\n");
                        }
                    }
                }
                if (!gameModeApplied) {
                    String wanted = "eco".equals(mode) ? "battery" : "performance";
                    gameModeMayHaveChanged = true;
                    prefs.edit().putBoolean(GAME_MODE_CHANGED, true).commit();
                    ShizukuShell.Result requested = setGameModeAndVerify(context, wanted, pkg);
                    report.append(requested.success
                            ? "• Game Mode " + ("battery".equals(wanted) ? "ECONOMIA" : "PERFORMANCE") + " ativado e conferido pelo Android ✔\n"
                            : "• Game Mode " + wanted + " não disponível para este jogo/ROM\n");
                }
            } else {
                report.append("• Game Mode não foi alterado: o modo anterior não pôde ser consultado com segurança\n");
            }
        }

        // ---------- Ajustes de sistema ----------
        if ("eco".equals(mode)) {
            ShizukuShell.Result battery = putAndVerifySetting(context, "global", "low_power", "1");
            report.append(battery.success ? "• Economia de bateria LIGADA ✔\n" : "• Não foi possível ligar a economia de bateria\n");
            ShizukuShell.Result peak = putAndVerifySetting(context, "system", "peak_refresh_rate", "60.0");
            ShizukuShell.Result min = putAndVerifySetting(context, "system", "min_refresh_rate", "60.0");
            report.append(peak.success && min.success ? "• Tela limitada a 60 Hz ✔\n" : "• A ROM não aceitou limitar a tela a 60 Hz\n");
            int governors = applyGovernors(context, prefs.getString(PREV_GOVERNORS, ""), "powersave");
            report.append(governors > 0
                    ? "• Governador powersave aplicado em " + governors + " interface(s) CPU/GPU ✔\n"
                    : "• Kernel não liberou governador powersave; limite de CPU/GPU fica por conta do sistema\n");
        } else if ("rise".equals(mode)) {
            ShizukuShell.Result battery = putAndVerifySetting(context, "global", "low_power", "0");
            report.append(battery.success ? "• Economia de bateria DESLIGADA ✔\n" : "• Não foi possível alterar a economia de bateria\n");
            ShizukuShell.Result peak = putAndVerifySetting(context, "system", "peak_refresh_rate", Float.toString(hz));
            report.append(peak.success ? "• Taxa máxima da tela liberada: " + hz + " Hz ✔\n" : "• Taxa de atualização controlada pela ROM\n");
            if (hasGame) report.append(boostBucket(context, pkg));
            report.append("• CPU/GPU: o sistema sobe as frequências até o máximo quando o jogo exige (sem trava conservadora)\n");
        } else { // diablo
            ShizukuShell.Result battery = putAndVerifySetting(context, "global", "low_power", "0");
            report.append(battery.success ? "• Economia de bateria DESLIGADA ✔\n" : "• Não foi possível alterar a economia de bateria\n");
            ShizukuShell.Result peak = putAndVerifySetting(context, "system", "peak_refresh_rate", Float.toString(hz));
            report.append(peak.success ? "• Taxa máxima da tela: " + hz + " Hz ✔\n" : "• Taxa de atualização máxima não pôde ser ajustada\n");
            if (thermalSafeForGovernor) {
                ShizukuShell.Result min = putAndVerifySetting(context, "system", "min_refresh_rate", Float.toString(hz));
                report.append(min.success ? "• Taxa mínima travada no máximo (fluidez total) ✔\n" : "• Taxa mínima mantida pela ROM\n");
                int governors = applyGovernors(context, prefs.getString(PREV_GOVERNORS, ""), "performance");
                report.append(governors > 0
                        ? "• Governador PERFORMANCE em " + governors + " interface(s) CPU/GPU ✔\n"
                        : "• Kernel não liberou governador performance para escrita (sem root, isso é normal)\n");
            } else {
                report.append("• Temperatura elevada: taxa mínima e governador CPU/GPU NÃO foram forçados\n");
            }
            if (hasGame) report.append(boostBucket(context, pkg));
            // Hiberna somente aplicativos de terceiros; GameTools mantém sistema, launcher,
            // teclado, Shizuku, Gamer Turbo, jogo e exceções do usuário fora da lista.
            String hibernate = GameTools.closeApps(context, pkg);
            report.append("• Hibernação de apps de terceiros (force-stop conferido):\n");
            for (String line : hibernate.split("\\n")) {
                if (line.startsWith("• Fechados e conferidos") || line.startsWith("• Mantidos:")
                        || line.startsWith("• Não fecharam:") || line.startsWith("• Nenhum app")
                        || line.startsWith("• RAM livre")) report.append(line).append('\n');
            }
            String clean = GameTools.cleanRam(context, pkg, 700);
            for (String line : clean.split("\\n")) {
                if (line.startsWith("• Apps com processo") || line.startsWith("• RAM livre")) report.append(line).append('\n');
            }
            if (prefs.getBoolean("cpu_custom_enabled", false) && thermalSafeForGovernor) {
                int cpuMin = prefs.getInt("cpu_min_percent", 0);
                int cpuMax = prefs.getInt("cpu_max_percent", 100);
                String cpuReport = CpuController.applyRange(context, cpuMin, cpuMax);
                report.append("• Controle de frequência da CPU: ").append(cpuReport.contains("APLICADO") ? "limites personalizados ativos ✔" : "mantido pelo kernel/automático").append('\n');
            } else if (!thermalSafeForGovernor) {
                report.append("• Controle manual da CPU adiado por segurança térmica.\n");
            }
            report.append("• Overclock acima do limite de fábrica não é aplicado; esta função regula apenas a faixa CPUFreq permitida pelo kernel.\n");
        }

        prefs.edit().putBoolean(GAME_MODE_CHANGED, gameModeMayHaveChanged || customOverrideApplied)
                .putBoolean(GAME_CUSTOM_OVERRIDE, customOverrideApplied).commit();
        prefs.edit().putBoolean(ACTIVE, true).putString(PACKAGE, pkg)
                .putString("gt_profile_mode", mode).apply();
        String finalReport = report.toString().trim();
        prefs.edit().putString("gt_last_profile_report", finalReport).apply();
        return finalReport;
    }

    private static String boostBucket(Context context, String pkg) {
        ShizukuShell.Result r = ShizukuShell.execute(context, "am set-standby-bucket " + pkg + " active");
        return r.success ? "• Jogo marcado como ATIVO para o sistema ✔\n" : "";
    }

    public static synchronized String restore(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cpuRestore = CpuController.restore(context);
        if (!prefs.getBoolean(SNAPSHOT, false)) {
            prefs.edit().putBoolean(ACTIVE, false).remove(PACKAGE).remove("gt_profile_mode").apply();
            if (cpuRestore.startsWith("• Nenhum limite")) return "Nenhum ajuste de sessão precisa ser restaurado.";
            return "RESTAURAÇÃO DA SESSÃO\n" + cpuRestore;
        }
        if (!ShizukuShell.isAuthorized()) {
            return "Shizuku não está autorizado. As configurações anteriores continuam salvas para restauração quando reconectar.\n" + cpuRestore;
        }
        String packageName = prefs.getString(PACKAGE, "");
        StringBuilder report = new StringBuilder("RESTAURAÇÃO DA SESSÃO\n");
        boolean gameModeRestored = true;
        if (prefs.getBoolean(GAME_MODE_CHANGED, false)
                && packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            if (prefs.getBoolean(GAME_CUSTOM_OVERRIDE, false)) {
                ShizukuShell.Result reset = ShizukuShell.execute(context, "cmd game reset " + packageName);
                gameModeRestored = reset.success;
            }
            String previousMode = prefs.getString(PREV_GAME_MODE, "");
            if (previousMode.matches("standard|performance|battery|custom")) {
                ShizukuShell.Result modeResult = setGameModeAndVerify(context, previousMode, packageName);
                gameModeRestored = gameModeRestored && modeResult.success;
                report.append(modeResult.success ? "• Game Mode anterior restaurado: " + previousMode + "\n"
                        : "• Não foi possível restaurar o Game Mode anterior\n");
            } else {
                gameModeRestored = false;
                report.append("• O modo original do Game Mode não foi identificado\n");
            }
        }
        boolean lowRestored = restoreSetting(context, "global", "low_power", prefs.getString(PREV_LOW_POWER, "null"), report);
        boolean peakRestored = restoreSetting(context, "system", "peak_refresh_rate", prefs.getString(PREV_PEAK, "null"), report);
        boolean minRestored = restoreSetting(context, "system", "min_refresh_rate", prefs.getString(PREV_MIN, "null"), report);
        String savedGovernors = prefs.getString(PREV_GOVERNORS, "");
        int expectedGovernors = countSavedGovernors(savedGovernors);
        int restoredGovernors = restoreGovernors(context, savedGovernors);
        boolean governorsRestored = restoredGovernors >= expectedGovernors;
        report.append(restoredGovernors > 0 ? "• " + restoredGovernors + " governador(es) de CPU/GPU restaurado(s)\n"
                : "• Nenhum governador de CPU/GPU gravável precisou ser restaurado\n");
        report.append(cpuRestore).append('\n');
        boolean cpuRestored = !cpuRestore.contains("pendente") && !cpuRestore.contains("parcial")
                && !cpuRestore.contains("reconecte");
        boolean complete = lowRestored && peakRestored && minRestored && governorsRestored && gameModeRestored && cpuRestored;
        if (complete) {
            prefs.edit().putBoolean(ACTIVE, false).putBoolean(SNAPSHOT, false)
                    .remove(PACKAGE).remove(PREV_LOW_POWER).remove(PREV_PEAK).remove(PREV_MIN).remove(PREV_GOVERNORS)
                    .remove("gt_profile_mode").remove(PREV_GAME_MODE)
                    .remove(GAME_MODE_CAPTURED).remove(GAME_MODE_CHANGED).remove(GAME_CUSTOM_OVERRIDE).apply();
            report.append("• Restauração concluída. Valores originais conferidos.\n");
        } else {
            prefs.edit().putBoolean(ACTIVE, true).putBoolean(SNAPSHOT, true).apply();
            report.append("• Restauração incompleta. Os valores originais continuam salvos para nova tentativa.\n");
        }
        String finalReport = report.toString().trim();
        prefs.edit().putString("gt_last_profile_report", finalReport).apply();
        return finalReport;
    }

    private static GameModeSnapshot readGameModeSnapshot(Context context, String packageName) {
        ShizukuShell.Result modes = ShizukuShell.execute(context, "cmd game list-modes " + packageName);
        if (!modes.success || modes.output == null) return new GameModeSnapshot("", false, true);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("current mode:\\s*(standard|performance|battery|custom|unsupported)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(modes.output);
        if (!matcher.find()) return new GameModeSnapshot("", false, true);
        String current = matcher.group(1).toLowerCase(Locale.ROOT);
        ShizukuShell.Result configs = ShizukuShell.execute(context, "cmd game list-configs " + packageName);
        if (!configs.success || configs.output == null) return new GameModeSnapshot(current, true, true);
        String text = configs.output.toLowerCase(Locale.ROOT);
        boolean noConfig = text.contains("no intervention found") || text.contains("no interventions found");
        return new GameModeSnapshot(current, true, !noConfig);
    }

    private static ShizukuShell.Result setGameModeAndVerify(Context context, String mode, String packageName) {
        if (!mode.matches("standard|performance|battery|custom")
                || packageName == null || !packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            return new ShizukuShell.Result(false, "Modo ou pacote inválido.", 2);
        }
        ShizukuShell.Result set = ShizukuShell.execute(context, "cmd game mode " + mode + " " + packageName);
        if (!set.success) return set;
        ShizukuShell.Result verify = ShizukuShell.execute(context, "cmd game list-modes " + packageName);
        if (verify.output == null || !verify.output.toLowerCase(Locale.ROOT).contains("current mode: " + mode)) {
            return new ShizukuShell.Result(false, "O Android não confirmou o modo " + mode + ".", -1);
        }
        return new ShizukuShell.Result(true, verify.output, 0);
    }

    private static final class GameModeSnapshot {
        final String mode;
        final boolean valid;
        final boolean hasExistingConfig;
        GameModeSnapshot(String mode, boolean valid, boolean hasExistingConfig) {
            this.mode = mode;
            this.valid = valid;
            this.hasExistingConfig = hasExistingConfig;
        }
    }

    private static boolean restoreSetting(Context context, String namespace, String key,
                                          String value, StringBuilder report) {
        boolean delete = value == null || value.isEmpty() || "null".equalsIgnoreCase(value)
                || !value.matches("[A-Za-z0-9._-]+");
        String command;
        if (delete) {
            command = "settings delete " + namespace + " " + key
                    + " && [ \"$(settings get " + namespace + " " + key + ")\" = null ]";
        } else {
            command = "settings put " + namespace + " " + key + " " + value
                    + " && [ \"$(settings get " + namespace + " " + key + ")\" = \"" + value + "\" ]";
        }
        ShizukuShell.Result result = ShizukuShell.execute(context, command);
        report.append(result.success ? "• " + key + " restaurado e conferido\n"
                : "• " + key + " não pôde ser restaurado/conferido\n");
        return result.success;
    }

    private static ShizukuShell.Result putAndVerifySetting(Context context, String namespace, String key, String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            return new ShizukuShell.Result(false, "Valor inválido.", 2);
        }
        String command = "settings put " + namespace + " " + key + " " + value
                + " && [ \"$(settings get " + namespace + " " + key + ")\" = \"" + value + "\" ]";
        return ShizukuShell.execute(context, command);
    }

    /** Captures only sysfs governor nodes that the Shizuku identity can really write. */
    private static String readWritableGovernorSnapshot(Context context, String target) {
        String command = "for f in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor /sys/class/devfreq/*/governor; do "
                + "[ -r \"$f\" ] && [ -w \"$f\" ] || continue; d=\"${f%/*}\"; a=\"$d/available_governors\"; "
                + "[ -r \"$a\" ] || continue; grep -qw " + target + " \"$a\" || continue; "
                + "printf 'GTNODE|%s|%s\\n' \"$f\" \"$(cat \"$f\")\"; done";
        ShizukuShell.Result result = ShizukuShell.execute(context, command);
        if (!result.success || result.output == null) return "";
        StringBuilder saved = new StringBuilder();
        for (String line : result.output.split("\\r?\\n")) {
            String[] parts = line.split("\\|", 3);
            if (parts.length != 3 || !"GTNODE".equals(parts[0])) continue;
            if (validGovernorPath(parts[1]) && parts[2].matches("[A-Za-z0-9_-]+")) {
                if (saved.length() > 0) saved.append('\n');
                saved.append(parts[1]).append('|').append(parts[2]);
            }
        }
        return saved.toString();
    }

    private static int applyGovernors(Context context, String savedNodes, String target) {
        int count = 0;
        if (savedNodes == null || savedNodes.isEmpty()) return count;
        for (String line : savedNodes.split("\\n")) {
            String[] parts = line.split("\\|", 2);
            if (parts.length != 2 || !validGovernorPath(parts[0])) continue;
            ShizukuShell.Result result = ShizukuShell.execute(context,
                    "[ -w \"" + parts[0] + "\" ] && printf '" + target + "' > \"" + parts[0]
                            + "\" && [ \"$(cat \"" + parts[0] + "\")\" = " + target + " ]");
            if (result.success) count++;
        }
        return count;
    }

    private static int restoreGovernors(Context context, String savedNodes) {
        int count = 0;
        if (savedNodes == null || savedNodes.isEmpty()) return count;
        for (String line : savedNodes.split("\\n")) {
            String[] parts = line.split("\\|", 2);
            if (parts.length != 2 || !validGovernorPath(parts[0]) || !parts[1].matches("[A-Za-z0-9_-]+")) continue;
            ShizukuShell.Result result = ShizukuShell.execute(context,
                    "[ -w \"" + parts[0] + "\" ] && printf '%s' '" + parts[1] + "' > \"" + parts[0]
                            + "\" && [ \"$(cat \"" + parts[0] + "\")\" = '" + parts[1] + "' ]");
            if (result.success) count++;
        }
        return count;
    }

    private static int countSavedGovernors(String savedNodes) {
        int count = 0;
        if (savedNodes == null || savedNodes.isEmpty()) return 0;
        for (String line : savedNodes.split("\\n")) {
            String[] parts = line.split("\\|", 2);
            if (parts.length == 2 && validGovernorPath(parts[0]) && parts[1].matches("[A-Za-z0-9_-]+")) count++;
        }
        return count;
    }

    private static boolean validGovernorPath(String path) {
        if (path == null) return false;
        if (path.matches("/sys/devices/system/cpu/cpufreq/policy[0-9]+/scaling_governor")) return true;
        if (!path.matches("/sys/class/devfreq/[A-Za-z0-9_.-]+/governor")) return false;
        String device = path.substring("/sys/class/devfreq/".length());
        int slash = device.indexOf('/');
        if (slash >= 0) device = device.substring(0, slash);
        device = device.toLowerCase(Locale.ROOT);
        // Do not pin unrelated devfreq domains (memory bus, interconnect, etc.).
        return device.contains("gpu") || device.contains("mali")
                || device.contains("kgsl") || device.contains("3d");
    }

    private static Snapshot readSnapshot(Context context) {
        ShizukuShell.Result result = ShizukuShell.execute(context,
                "printf 'LOW='; settings get global low_power; "
                + "printf 'PEAK='; settings get system peak_refresh_rate; "
                + "printf 'MIN='; settings get system min_refresh_rate");
        String low = "null", peak = "null", min = "null";
        if (result.output != null) {
            for (String line : result.output.split("\\r?\\n")) {
                if (line.startsWith("LOW=")) low = cleanValue(line.substring(4));
                if (line.startsWith("PEAK=")) peak = cleanValue(line.substring(5));
                if (line.startsWith("MIN=")) min = cleanValue(line.substring(4));
            }
        }
        boolean valid = result.success && result.output != null
                && result.output.contains("LOW=") && result.output.contains("PEAK=") && result.output.contains("MIN=");
        return new Snapshot(low, peak, min, valid);
    }

    private static String cleanValue(String value) {
        value = value == null ? "null" : value.trim();
        return value.matches("[A-Za-z0-9._-]+") ? value : "null";
    }

    private static float normalizeRefreshRate(float rate) {
        if (Float.isNaN(rate) || Float.isInfinite(rate) || rate < 30f) return 60f;
        return Math.min(240f, Math.max(30f, Math.round(rate * 10f) / 10f));
    }

    private static int chooseFps(float hz) {
        int[] values = {120, 90, 60, 45, 40, 30};
        for (int value : values) if (value <= hz + 0.1f) return value;
        return 30;
    }

    private static final class Snapshot {
        final String lowPower, peak, min;
        final boolean valid;
        Snapshot(String lowPower, String peak, String min, boolean valid) {
            this.lowPower = lowPower; this.peak = peak; this.min = min; this.valid = valid;
        }
    }
}
