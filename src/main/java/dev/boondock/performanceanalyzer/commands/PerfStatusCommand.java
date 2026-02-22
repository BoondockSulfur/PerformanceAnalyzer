package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.integration.SparkHook;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.MemorySampler;
import dev.boondock.performanceanalyzer.metrics.TickSampler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PerfStatusCommand implements CommandExecutor {

    private final PerformanceAnalyzer plugin;
    private final PluginConfig cfg;
    private final TickSampler tick;
    private final MemorySampler mem;

    public PerfStatusCommand(PerformanceAnalyzer plugin, PluginConfig cfg, TickSampler tick, MemorySampler mem) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.tick = tick;
        this.mem = mem;
    }

    private LanguageManager lang() {
        return plugin.lang();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("performance.status")) {
            sender.sendMessage(lang().get("general.no_permission"));
            return true;
        }

        // Check if Spark is available for more accurate data
        SparkHook spark = plugin.spark();
        boolean useSparkData = spark != null && spark.isAvailable();

        double tps;
        double msptAvg;
        double msptP95;
        double heap = mem.heapUsagePercent();

        if (useSparkData) {
            // Use Spark's more accurate data
            SparkHook.SparkStats stats = spark.getStats();
            tps = stats.hasValidTps() ? stats.tps10s() : tick.tpsApprox();
            msptAvg = stats.hasValidMspt() ? stats.msptMean() : tick.msptAvg();
            msptP95 = stats.hasValidMspt() ? stats.msptP95() : tick.msptP95();

            sender.sendMessage(lang().get("status.title_spark"));
            sender.sendMessage(lang().get("status.tps_spark",
                    "%tps10s%", String.format("%.2f", stats.tps10s()),
                    "%tps1m%", String.format("%.2f", stats.tps1m()),
                    "%tps5m%", String.format("%.2f", stats.tps5m())));
            sender.sendMessage(lang().get("status.mspt_spark",
                    "%avg%", String.format("%.2f", msptAvg),
                    "%p95%", String.format("%.2f", msptP95),
                    "%max%", String.format("%.2f", stats.msptMax())));
            sender.sendMessage(lang().get("status.heap", "%value%", String.format("%.1f", heap)));

            // Show CPU if available
            if (stats.hasValidCpu()) {
                sender.sendMessage(lang().get("status.cpu",
                        "%system%", String.format("%.1f", stats.cpuSystem()),
                        "%process%", String.format("%.1f", stats.cpuProcess())));
            }
        } else {
            // Use our own approximation
            tps = tick.tpsApprox();
            msptAvg = tick.msptAvg();
            msptP95 = tick.msptP95();

            sender.sendMessage(lang().get("status.title"));
            sender.sendMessage(lang().get("status.tps", "%value%", String.format("%.2f", tps)));
            sender.sendMessage(lang().get("status.mspt_avg", "%avg%", String.format("%.2f", msptAvg), "%p95%", String.format("%.2f", msptP95)));
            sender.sendMessage(lang().get("status.heap", "%value%", String.format("%.1f", heap)));
        }

        if (sender instanceof Player p) {
            if (msptAvg > cfg.thresholdMspt() || heap > cfg.thresholdHeapUsage()) {
                p.sendTitle(lang().get("status.alert_title"), lang().get("status.alert_message"), 10, 40, 10);
            }
        }
        return true;
    }
}
