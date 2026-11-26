package com.gamerturbo.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Controle reversível dos limites CPUFreq expostos pelo kernel.
 * Só escreve scaling_min_freq/scaling_max_freq quando a identidade do Shizuku
 * realmente tem permissão. Nunca ultrapassa cpuinfo_min_freq/cpuinfo_max_freq.
 */
public final class CpuController {
    private static final String PREFS = "gamer_turbo_native";
    private static final String SNAPSHOT = "gt_cpu_limits_snapshot";
    private static final String SNAPSHOT_ACTIVE = "gt_cpu_limits_snapshot_active";
    private static final String MIN_PERCENT = "cpu_min_percent";
    private static final String MAX_PERCENT = "cpu_max_percent";
    private static final String CUSTOM_ENABLED = "cpu_custom_enabled";

    private CpuController() { }

    public static final class Policy {
        public final String id;
        public final long hardwareMinKhz;
        public final long hardwareMaxKhz;
        public final long currentMinKhz;
        public final long currentMaxKhz;
        public final long currentKhz;
        public final boolean writable;

        Policy(String id, long hardwareMinKhz, long hardwareMaxKhz,
               long currentMinKhz, long currentMaxKhz, long currentKhz, boolean writable) {
            this.id = id;
            this.hardwareMinKhz = hardwareMinKhz;
            this.hardwareMaxKhz = hardwareMaxKhz;
            this.currentMinKhz = currentMinKhz;
            this.currentMaxKhz = currentMaxKhz;
            this.currentKhz = currentKhz;
            this.writable = writable;
        }

        public int currentMinPercent() { return toPercent(currentMinKhz, hardwareMinKhz, hardwareMaxKhz); }
        public int currentMaxPercent() { return toPercent(currentMaxKhz, hardwareMinKhz, hardwareMaxKhz); }
    }

    public static List<Policy> inspect(Context context) {
        List<Policy> policies = new ArrayList<>();
        if (!ShizukuShell.isAuthorized()) return policies;
        String command = "for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do "
                + "[ -d \"$d\" ] || continue; id=\"${d##*/}\"; "
                + "lo=$(cat \"$d/cpuinfo_min_freq\" 2>/dev/null); hi=$(cat \"$d/cpuinfo_max_freq\" 2>/dev/null); "
                + "mn=$(cat \"$d/scaling_min_freq\" 2>/dev/null); mx=$(cat \"$d/scaling_max_freq\" 2>/dev/null); "
                + "cu=$(cat \"$d/cpuinfo_avg_freq\" 2>/dev/null); [ -n \"$cu\" ] || cu=$(cat \"$d/scaling_cur_freq\" 2>/dev/null); "
                + "case \"$lo:$hi:$mn:$mx\" in *[!0-9:]*|:*|*::*|*:) continue;; esac; "
                + "w=0; [ -w \"$d/scaling_min_freq\" ] && [ -w \"$d/scaling_max_freq\" ] && w=1; "
                + "printf 'GTCPU|%s|%s|%s|%s|%s|%s|%s\\n' \"$id\" \"$lo\" \"$hi\" \"$mn\" \"$mx\" \"$cu\" \"$w\"; done";
        ShizukuShell.Result result = ShizukuShell.execute(context, command);
        if (!result.success || result.output == null) return policies;
        for (String line : result.output.split("\\r?\\n")) {
            String[] fields = line.trim().split("\\|", -1);
            if (fields.length != 8 || !"GTCPU".equals(fields[0])
                    || !fields[1].matches("policy[0-9]+")) continue;
            long lo = number(fields[2]), hi = number(fields[3]);
            long mn = number(fields[4]), mx = number(fields[5]), cu = number(fields[6]);
            if (lo <= 0 || hi < lo || mn <= 0 || mx < mn) continue;
            policies.add(new Policy(fields[1], lo, hi, mn, mx, cu, "1".equals(fields[7])));
        }
        return policies;
    }

    public static synchronized String applyRange(Context context, int minPercent, int maxPercent) {
        if (!ShizukuShell.isAuthorized()) return "CONTROLE DA CPU\n• Conecte e autorize o Shizuku. Nenhuma frequência foi alterada.";
        minPercent = clamp(minPercent, 0, 100);
        maxPercent = clamp(maxPercent, 0, 100);
        if (minPercent > maxPercent) return "CONTROLE DA CPU\n• O limite mínimo não pode ultrapassar o limite máximo.";

        List<Policy> policies = inspect(context);
        if (policies.isEmpty()) return "CONTROLE DA CPU\n• O kernel não expôs interfaces CPUFreq compatíveis. A CPU continua sob controle automático do Android.";

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String snapshot = prefs.getString(SNAPSHOT, "");
        if (!prefs.getBoolean(SNAPSHOT_ACTIVE, false) || snapshot.isEmpty()) {
            StringBuilder original = new StringBuilder();
            for (Policy policy : policies) {
                if (!policy.writable) continue;
                if (original.length() > 0) original.append('\n');
                original.append(policy.id).append('|').append(policy.currentMinKhz).append('|').append(policy.currentMaxKhz);
            }
            if (original.length() == 0) return "CONTROLE DA CPU\n• O kernel não permite gravar os limites de frequência via Shizuku. Nada foi alterado.";
            snapshot = original.toString();
            prefs.edit().putString(SNAPSHOT, snapshot).putBoolean(SNAPSHOT_ACTIVE, true).commit();
        }

        int applied = 0;
        int blocked = 0;
        StringBuilder details = new StringBuilder();
        for (Policy policy : policies) {
            if (!policy.writable) { blocked++; continue; }
            long targetMin = fromPercent(minPercent, policy.hardwareMinKhz, policy.hardwareMaxKhz);
            long targetMax = fromPercent(maxPercent, policy.hardwareMinKhz, policy.hardwareMaxKhz);
            if (targetMin > targetMax) { blocked++; continue; }
            String dir = "/sys/devices/system/cpu/cpufreq/" + policy.id;
            String command = "d='" + dir + "'; [ -w \"$d/scaling_min_freq\" ] && [ -w \"$d/scaling_max_freq\" ] || exit 20; "
                    + "oldmin=$(cat \"$d/scaling_min_freq\"); oldmax=$(cat \"$d/scaling_max_freq\"); "
                    + "nmin='" + targetMin + "'; nmax='" + targetMax + "'; "
                    + "if [ \"$nmax\" -lt \"$oldmin\" ]; then "
                    + "printf '%s' \"$nmin\" > \"$d/scaling_min_freq\" || exit 21; "
                    + "printf '%s' \"$nmax\" > \"$d/scaling_max_freq\" || exit 22; "
                    + "else printf '%s' \"$nmax\" > \"$d/scaling_max_freq\" || exit 23; "
                    + "printf '%s' \"$nmin\" > \"$d/scaling_min_freq\" || exit 24; fi; "
                    + "actualmin=$(cat \"$d/scaling_min_freq\"); actualmax=$(cat \"$d/scaling_max_freq\"); "
                    + "case \"$actualmin:$actualmax\" in *[!0-9:]*|:*|*::*|*:) exit 25;; esac; "
                    + "[ \"$actualmin\" -le \"$actualmax\" ] || exit 26; "
                    + "printf 'GTAPPLIED|%s|%s|%s\\n' '" + policy.id + "' \"$actualmin\" \"$actualmax\"";
            ShizukuShell.Result write = ShizukuShell.execute(context, command);
            if (write.success && write.output != null && write.output.contains("GTAPPLIED|" + policy.id + "|")) {
                applied++;
                long actualMin = targetMin;
                long actualMax = targetMax;
                for (String line : write.output.split("\\r?\\n")) {
                    String[] parts = line.trim().split("\\|", -1);
                    if (parts.length == 4 && ("GTAPPLIED".equals(parts[0]) && policy.id.equals(parts[1]))) {
                        actualMin = number(parts[2]);
                        actualMax = number(parts[3]);
                    }
                }
                details.append("• ").append(policy.id).append(": ")
                        .append(ghz(actualMin)).append("–").append(ghz(actualMax)).append(" GHz confirmados\n");
            } else {
                blocked++;
                details.append("• ").append(policy.id).append(": o kernel recusou a gravação\n");
            }
        }

        if (applied > 0) {
            prefs.edit().putInt(MIN_PERCENT, minPercent).putInt(MAX_PERCENT, maxPercent)
                    .putBoolean(CUSTOM_ENABLED, true).commit();
            return "CONTROLE DA CPU APLICADO\n• Clusters ajustados: " + applied + "\n"
                    + (blocked > 0 ? "• Clusters bloqueados: " + blocked + "\n" : "")
                    + details + "• Limites mantidos dentro da faixa informada pelo hardware. Não é overclock.";
        }
        return "CONTROLE DA CPU NÃO APLICADO\n• O Shizuku não conseguiu gravar os limites no kernel. A frequência permanece automática.\n"
                + "• Se existir suporte, talvez seja necessário root ou um kernel que libere escrita em CPUFreq.";
    }

    public static synchronized String restore(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(SNAPSHOT_ACTIVE, false)) return "• Nenhum limite manual de CPU precisa ser restaurado.";
        if (!ShizukuShell.isAuthorized()) return "• Limites originais da CPU salvos; reconecte o Shizuku para restaurá-los.";
        String snapshot = prefs.getString(SNAPSHOT, "");
        int restored = 0;
        int failed = 0;
        for (String line : snapshot.split("\\r?\\n")) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 3 || !fields[0].matches("policy[0-9]+")) continue;
            long min = number(fields[1]), max = number(fields[2]);
            if (min <= 0 || max < min) { failed++; continue; }
            String dir = "/sys/devices/system/cpu/cpufreq/" + fields[0];
            String command = "d='" + dir + "'; [ -w \"$d/scaling_min_freq\" ] && [ -w \"$d/scaling_max_freq\" ] || exit 20; "
                    + "oldmin=$(cat \"$d/scaling_min_freq\"); nmin='" + min + "'; nmax='" + max + "'; "
                    + "if [ \"$nmax\" -lt \"$oldmin\" ]; then printf '%s' \"$nmin\" > \"$d/scaling_min_freq\" || exit 21; printf '%s' \"$nmax\" > \"$d/scaling_max_freq\" || exit 22; "
                    + "else printf '%s' \"$nmax\" > \"$d/scaling_max_freq\" || exit 23; printf '%s' \"$nmin\" > \"$d/scaling_min_freq\" || exit 24; fi; "
                    + "a=$(cat \"$d/scaling_min_freq\"); b=$(cat \"$d/scaling_max_freq\"); "
                    + "[ \"$a\" -le \"$b\" ] && [ \"$a\" -eq '" + min + "' ] && [ \"$b\" -eq '" + max + "' ]";
            ShizukuShell.Result result = ShizukuShell.execute(context, command);
            if (result.success) restored++; else failed++;
        }
        if (failed == 0) {
            prefs.edit().remove(SNAPSHOT).putBoolean(SNAPSHOT_ACTIVE, false).commit();
            return "• Limites originais da CPU restaurados: " + restored + " cluster(s).";
        }
        return "• CPU restaurada parcialmente: " + restored + " cluster(s); " + failed
                + " pendente(s). O snapshot foi mantido para nova tentativa.";
    }

    public static int defaultMinPercent(List<Policy> policies) {
        if (policies == null || policies.isEmpty()) return 0;
        int total = 0;
        for (Policy p : policies) total += p.currentMinPercent();
        return clamp(Math.round(total / (float) policies.size()), 0, 100);
    }

    public static int defaultMaxPercent(List<Policy> policies) {
        if (policies == null || policies.isEmpty()) return 100;
        int total = 0;
        for (Policy p : policies) total += p.currentMaxPercent();
        return clamp(Math.round(total / (float) policies.size()), 0, 100);
    }

    public static String describePercent(List<Policy> policies, int percent) {
        if (policies == null || policies.isEmpty()) return "-- GHz";
        double total = 0d;
        int count = 0;
        for (Policy p : policies) {
            total += fromPercent(percent, p.hardwareMinKhz, p.hardwareMaxKhz);
            count++;
        }
        return count == 0 ? "-- GHz" : String.format(Locale.US, "%.2f GHz", (total / count) / 1_000_000d);
    }

    public static String summarize(List<Policy> policies) {
        if (policies == null || policies.isEmpty()) return "CPUFreq não disponível";
        StringBuilder text = new StringBuilder();
        for (Policy p : policies) {
            if (text.length() > 0) text.append("  •  ");
            text.append(p.id).append(": ").append(ghz(p.hardwareMinKhz)).append("–")
                    .append(ghz(p.hardwareMaxKhz)).append(" GHz")
                    .append(p.writable ? " (gravável)" : " (somente leitura)");
        }
        return text.toString();
    }

    private static int toPercent(long value, long low, long high) {
        if (high <= low) return 0;
        return clamp((int) Math.round((value - low) * 100.0 / (high - low)), 0, 100);
    }

    private static long fromPercent(int percent, long low, long high) {
        if (high <= low) return low;
        return low + Math.round((high - low) * (clamp(percent, 0, 100) / 100.0));
    }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
    private static long number(String value) {
        try { return value != null && value.matches("[0-9]+") ? Long.parseLong(value) : -1L; }
        catch (NumberFormatException ignored) { return -1L; }
    }
    private static String ghz(long khz) { return String.format(Locale.US, "%.2f", khz / 1_000_000d); }
}
