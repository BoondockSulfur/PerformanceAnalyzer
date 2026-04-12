package dev.boondock.performanceanalyzer.metrics;

import dev.boondock.performanceanalyzer.alerts.AlertManager;
import dev.boondock.performanceanalyzer.analysis.PerformanceDropAnalyzer;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.integration.SparkHook;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TickSampler implements Sampler {

    private final Plugin plugin;
    private final PluginConfig config;
    private DatabaseManager database;
    private AlertManager alerts;
    private SparkHook sparkHook;
    private PerformanceDropAnalyzer dropAnalyzer;
    private int taskId = -1;
    private int logTaskId = -1;
    private final long[] nanos = new long[200]; // ~10s window at 20 TPS
    private final AtomicInteger idx = new AtomicInteger(0);
    private volatile long last = -1L;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TickSampler(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        Arrays.fill(nanos, -1L);
    }

    public void setAlertManager(AlertManager alerts) {
        this.alerts = alerts;
    }

    public void setSparkHook(SparkHook sparkHook) {
        this.sparkHook = sparkHook;
    }

    public void setDropAnalyzer(PerformanceDropAnalyzer dropAnalyzer) {
        this.dropAnalyzer = dropAnalyzer;
    }

    /**
     * Set database manager for automatic logging
     * @param db DatabaseManager instance
     * @param intervalSeconds How often to log metrics (in seconds)
     */
    public void setDatabase(DatabaseManager db, int intervalSeconds) {
        this.database = db;
        // Schedule periodic logging
        if (intervalSeconds > 0) {
            this.logTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                if (database != null && running.get()) {
                    // Use Spark data if available, otherwise use our own calculations
                    double mspt = getMsptAvg();
                    double tps = getTps();
                    double p95 = getMsptP95();

                    database.logAsync("mspt", mspt, "Average MSPT");
                    database.logAsync("tps", tps, "TPS");
                    database.logAsync("mspt_p95", p95, "95th Percentile MSPT");

                    // Check for performance drops and analyze them
                    if (dropAnalyzer != null) {
                        dropAnalyzer.checkForDrop(tps, mspt);
                    }

                    // Check thresholds and send alerts
                    if (alerts != null) {
                        if (mspt > config.thresholdMspt()) {
                            alerts.sendAlert(AlertManager.AlertType.HIGH_MSPT,
                                "MSPT über Schwellenwert", mspt);
                        }
                        if (tps < config.tpsDropThreshold()) {
                            alerts.sendAlert(AlertManager.AlertType.TPS_DROP,
                                "TPS unter " + config.tpsDropThreshold(), tps);
                        }
                    }
                }
            }, intervalSeconds * 20L, intervalSeconds * 20L).getTaskId();
        }
    }

    @Override
    public void start() {
        if (running.getAndSet(true)) return;
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.nanoTime();
            if (last != -1L) {
                long dt = now - last;
                synchronized (nanos) {
                    int currentIdx = idx.getAndUpdate(i -> (i + 1) % nanos.length);
                    nanos[currentIdx] = dt;
                }
            }
            last = now;
        }, 0L, 1L).getTaskId();
    }

    @Override
    public void stop() {
        if (!running.getAndSet(false)) return;
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        if (logTaskId != -1) Bukkit.getScheduler().cancelTask(logTaskId);
    }

    /**
     * Creates a thread-safe snapshot of current tick data.
     *
     * THREAD-SAFETY: This method uses synchronized block to ensure
     * a consistent copy of the nanos array. The array is accessed
     * from both the main thread (writing) and async threads (reading),
     * so synchronization is critical to prevent data races.
     *
     * @return Copy of tick timings array (in nanoseconds)
     */
    private long[] snapshot() {
        synchronized (nanos) {
            return Arrays.copyOf(nanos, nanos.length);
        }
    }

    /**
     * Get MSPT average - uses Spark if available, otherwise internal calculation.
     */
    public double getMsptAvg() {
        if (sparkHook != null && sparkHook.isAvailable()) {
            double sparkMspt = sparkHook.getMsptMean();
            if (sparkMspt >= 0) return sparkMspt;
        }
        return msptAvg();
    }

    /**
     * Get MSPT P95 - uses Spark if available, otherwise internal calculation.
     */
    public double getMsptP95() {
        if (sparkHook != null && sparkHook.isAvailable()) {
            double sparkP95 = sparkHook.getMsptP95();
            if (sparkP95 >= 0) return sparkP95;
        }
        return msptP95();
    }

    /**
     * Get TPS - uses Spark if available, otherwise internal calculation.
     */
    public double getTps() {
        if (sparkHook != null && sparkHook.isAvailable()) {
            double sparkTps = sparkHook.getTps();
            if (sparkTps >= 0) return sparkTps;
        }
        return tpsApprox();
    }

    /**
     * Check if Spark data is being used.
     */
    public boolean isUsingSparkData() {
        return sparkHook != null && sparkHook.isAvailable();
    }

    // Internal calculations (always available as fallback)

    public double msptAvg() {
        long[] copy = Arrays.stream(snapshot()).filter(v -> v > 0).toArray();
        if (copy.length == 0) return 0.0;
        long sum = 0;
        for (long v : copy) sum += v;
        return sum / 1_000_000.0 / copy.length;
    }

    /**
     * Calculate 95th percentile MSPT from tick samples.
     * Uses correct percentile formula: ceil(n * p) - 1
     */
    public double msptP95() {
        long[] copy = Arrays.stream(snapshot()).filter(v -> v > 0).sorted().toArray();
        if (copy.length == 0) return 0.0;
        // Correct P95 formula: ceil(n * 0.95) - 1
        int i = (int) Math.ceil(copy.length * 0.95) - 1;
        if (i < 0) i = 0;
        if (i >= copy.length) i = copy.length - 1;
        return copy[i] / 1_000_000.0;
    }

    public double tpsApprox() {
        double mspt = msptAvg();
        if (mspt <= 0.0) return 20.0;
        return Math.min(20.0, 1000.0 / mspt);
    }
}
