package dev.boondock.performanceanalyzer.integration;

import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Integration with the Spark profiler plugin.
 * Provides accurate TPS, MSPT, CPU usage, and GC statistics.
 *
 * @see <a href="https://spark.lucko.me/">Spark</a>
 */
public class SparkHook {

    private final Plugin plugin;
    private Spark spark;
    private boolean available = false;

    private SparkHook(Plugin plugin) {
        this.plugin = plugin;
        try {
            this.spark = SparkProvider.get();
            this.available = true;
            plugin.getLogger().info("[Spark] Integration erfolgreich! Spark-Daten werden verwendet.");
        } catch (Exception e) {
            plugin.getLogger().warning("[Spark] Konnte Spark API nicht laden: " + e.getMessage());
            this.available = false;
        }
    }

    /**
     * Try to hook into Spark if available.
     */
    public static SparkHook tryHook(Plugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("spark") == null) {
            plugin.getLogger().info("[Spark] Spark nicht installiert. Verwende eigene TPS-Berechnung.");
            return null;
        }

        try {
            // Test if Spark API is accessible
            Class.forName("me.lucko.spark.api.SparkProvider");
            return new SparkHook(plugin);
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("[Spark] Spark API nicht gefunden. Verwende eigene TPS-Berechnung.");
            return null;
        }
    }

    /**
     * Check if Spark is available and working.
     */
    public boolean isAvailable() {
        return available && spark != null;
    }

    /**
     * Get TPS from Spark (last 10 seconds average).
     * @return TPS value or -1 if not available
     */
    public double getTps() {
        if (!isAvailable()) return -1;

        try {
            DoubleStatistic<StatisticWindow.TicksPerSecond> tps = spark.tps();
            if (tps != null) {
                return tps.poll(StatisticWindow.TicksPerSecond.SECONDS_10);
            }
        } catch (Exception e) {
            if (plugin.getConfig().getBoolean("performance.debug_mode", false)) {
                plugin.getLogger().warning("[Spark] Fehler beim Abrufen der TPS: " + e.getMessage());
            }
        }
        return -1;
    }

    /**
     * Get TPS for different time windows.
     * @param window The time window (SECONDS_5, SECONDS_10, MINUTES_1, MINUTES_5, MINUTES_15)
     * @return TPS value or -1 if not available
     */
    public double getTps(StatisticWindow.TicksPerSecond window) {
        if (!isAvailable()) return -1;

        try {
            DoubleStatistic<StatisticWindow.TicksPerSecond> tps = spark.tps();
            if (tps != null) {
                return tps.poll(window);
            }
        } catch (Exception e) {
            if (plugin.getConfig().getBoolean("performance.debug_mode", false)) {
                plugin.getLogger().warning("[Spark] Fehler beim Abrufen der TPS: " + e.getMessage());
            }
        }
        return -1;
    }

    /**
     * Get MSPT (milliseconds per tick) from Spark.
     * @return MSPT info or null if not available
     */
    public DoubleAverageInfo getMspt() {
        if (!isAvailable()) return null;

        try {
            GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt = spark.mspt();
            if (mspt != null) {
                return mspt.poll(StatisticWindow.MillisPerTick.SECONDS_10);
            }
        } catch (Exception e) {
            if (plugin.getConfig().getBoolean("performance.debug_mode", false)) {
                plugin.getLogger().warning("[Spark] Fehler beim Abrufen der MSPT: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Get MSPT mean value.
     * @return MSPT mean or -1 if not available
     */
    public double getMsptMean() {
        DoubleAverageInfo mspt = getMspt();
        return mspt != null ? mspt.mean() : -1;
    }

    /**
     * Get MSPT 95th percentile.
     * @return MSPT p95 or -1 if not available
     */
    public double getMsptP95() {
        DoubleAverageInfo mspt = getMspt();
        return mspt != null ? mspt.percentile95th() : -1;
    }

    /**
     * Get MSPT max value.
     * @return MSPT max or -1 if not available
     */
    public double getMsptMax() {
        DoubleAverageInfo mspt = getMspt();
        return mspt != null ? mspt.max() : -1;
    }

    /**
     * Get CPU usage (system) from Spark.
     * @return CPU usage percentage (0-100) or -1 if not available
     */
    public double getCpuSystem() {
        if (!isAvailable()) return -1;

        try {
            DoubleStatistic<StatisticWindow.CpuUsage> cpu = spark.cpuSystem();
            if (cpu != null) {
                return cpu.poll(StatisticWindow.CpuUsage.SECONDS_10) * 100;
            }
        } catch (Exception e) {
            if (plugin.getConfig().getBoolean("performance.debug_mode", false)) {
                plugin.getLogger().warning("[Spark] Fehler beim Abrufen der CPU-Auslastung: " + e.getMessage());
            }
        }
        return -1;
    }

    /**
     * Get CPU usage (process) from Spark.
     * @return CPU usage percentage (0-100) or -1 if not available
     */
    public double getCpuProcess() {
        if (!isAvailable()) return -1;

        try {
            DoubleStatistic<StatisticWindow.CpuUsage> cpu = spark.cpuProcess();
            if (cpu != null) {
                return cpu.poll(StatisticWindow.CpuUsage.SECONDS_10) * 100;
            }
        } catch (Exception e) {
            if (plugin.getConfig().getBoolean("performance.debug_mode", false)) {
                plugin.getLogger().warning("[Spark] Fehler beim Abrufen der Prozess-CPU: " + e.getMessage());
            }
        }
        return -1;
    }

    /**
     * Get a summary of all Spark statistics.
     * @return SparkStats object with all available data
     */
    public SparkStats getStats() {
        return new SparkStats(
            getTps(),
            getTps(StatisticWindow.TicksPerSecond.MINUTES_1),
            getTps(StatisticWindow.TicksPerSecond.MINUTES_5),
            getMsptMean(),
            getMsptP95(),
            getMsptMax(),
            getCpuSystem(),
            getCpuProcess()
        );
    }

    /**
     * Data class holding all Spark statistics.
     */
    public record SparkStats(
        double tps10s,
        double tps1m,
        double tps5m,
        double msptMean,
        double msptP95,
        double msptMax,
        double cpuSystem,
        double cpuProcess
    ) {
        public boolean hasValidTps() {
            return tps10s >= 0;
        }

        public boolean hasValidMspt() {
            return msptMean >= 0;
        }

        public boolean hasValidCpu() {
            return cpuSystem >= 0;
        }
    }
}
