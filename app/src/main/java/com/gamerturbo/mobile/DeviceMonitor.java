package com.gamerturbo.mobile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/** Leituras locais e somente de leitura do kernel. */
public final class DeviceMonitor {
    private static long previousTotal = -1L;
    private static long previousIdle = -1L;

    private DeviceMonitor() { }

    public static final class CpuSnapshot {
        public final int usagePercent;
        public final int frequencyMhz;

        CpuSnapshot(int usagePercent, int frequencyMhz) {
            this.usagePercent = usagePercent;
            this.frequencyMhz = frequencyMhz;
        }

        public String frequencyLabel() {
            return frequencyMhz > 0
                    ? String.format(Locale.US, "%.2f GHz", frequencyMhz / 1000f)
                    : "-- GHz";
        }
    }

    /** Percentual global aproximado, calculado pela diferença entre duas leituras de /proc/stat. */
    public static synchronized CpuSnapshot readCpu() {
        int usage = -1;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line != null && line.startsWith("cpu ")) {
                String[] fields = line.trim().split("\\s+");
                long total = 0L;
                long idle = 0L;
                for (int i = 1; i < fields.length; i++) {
                    if (i >= 9) continue; // guest já está incluído em user/nice.
                    long value = Long.parseLong(fields[i]);
                    total += value;
                    if (i == 4 || i == 5) idle += value;
                }
                if (previousTotal >= 0L && total > previousTotal) {
                    long totalDelta = total - previousTotal;
                    long idleDelta = Math.max(0L, idle - previousIdle);
                    usage = (int) Math.max(0L, Math.min(100L,
                            Math.round((totalDelta - idleDelta) * 100.0 / totalDelta)));
                }
                previousTotal = total;
                previousIdle = idle;
            }
        } catch (Throwable ignored) { }
        return new CpuSnapshot(usage, readCpuFrequencyMhz());
    }

    /** Média ponderada da frequência atual de todos os clusters/policies expostos pelo kernel. */
    public static int readCpuFrequencyMhz() {
        File root = new File("/sys/devices/system/cpu/cpufreq");
        File[] policies = root.listFiles((dir, name) -> name.matches("policy[0-9]+"));
        long weightedKhz = 0L;
        long totalCpus = 0L;
        if (policies != null) {
            for (File policy : policies) {
                long khz = readLong(new File(policy, "cpuinfo_avg_freq"));
                if (khz <= 0L) khz = readLong(new File(policy, "cpuinfo_cur_freq"));
                if (khz <= 0L) khz = readLong(new File(policy, "scaling_cur_freq"));
                if (khz <= 0L) continue;
                int cores = countCpuIds(readText(new File(policy, "affected_cpus")));
                if (cores <= 0) cores = countCpuIds(readText(new File(policy, "related_cpus")));
                if (cores <= 0) cores = 1;
                weightedKhz += khz * cores;
                totalCpus += cores;
            }
        }
        if (totalCpus > 0L) return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, (weightedKhz / totalCpus) / 1000L));

        // Fallback para kernels que só expõem cpuN/cpufreq, sem policyX.
        File cpuRoot = new File("/sys/devices/system/cpu");
        File[] cpus = cpuRoot.listFiles((dir, name) -> name.matches("cpu[0-9]+"));
        long sum = 0L;
        int count = 0;
        if (cpus != null) {
            for (File cpu : cpus) {
                long khz = readLong(new File(cpu, "cpufreq/scaling_cur_freq"));
                if (khz <= 0L) khz = readLong(new File(cpu, "cpufreq/cpuinfo_cur_freq"));
                if (khz > 0L) { sum += khz; count++; }
            }
        }
        if (count > 0) return (int) Math.max(1L, (sum / count) / 1000L);
        return -1;
    }

    private static int countCpuIds(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        int total = 0;
        for (String part : value.trim().split("[,\\s]+")) {
            if (part.matches("[0-9]+-[0-9]+")) {
                String[] range = part.split("-");
                try { total += Math.max(0, Integer.parseInt(range[1]) - Integer.parseInt(range[0]) + 1); }
                catch (NumberFormatException ignored) { }
            } else if (part.matches("[0-9]+")) total++;
        }
        return total;
    }

    private static String readText(File file) {
        if (file == null || !file.canRead()) return "";
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String value = reader.readLine();
            return value == null ? "" : value.trim();
        } catch (Throwable ignored) { return ""; }
    }

    private static long readLong(File file) {
        String value = readText(file);
        try { return value.matches("[0-9]+") ? Long.parseLong(value) : -1L; }
        catch (NumberFormatException ignored) { return -1L; }
    }
}
