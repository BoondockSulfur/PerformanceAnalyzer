package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.integration.SparkHook;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.MemorySampler;
import dev.boondock.performanceanalyzer.metrics.TickSampler;
import dev.boondock.performanceanalyzer.util.AsciiSparkline;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class PerfHistoryCommand implements CommandExecutor {

    private final PerformanceAnalyzer plugin;
    private final PluginConfig cfg;
    private final TickSampler tick;
    private final MemorySampler mem;
    private final DatabaseManager db;

    public PerfHistoryCommand(PerformanceAnalyzer plugin, PluginConfig cfg, TickSampler tick, MemorySampler mem, DatabaseManager db) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.tick = tick;
        this.mem = mem;
        this.db = db;
    }

    private LanguageManager lang() {
        return plugin.lang();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("performance.history")) {
            sender.sendMessage(lang().get("general.no_permission"));
            return true;
        }

        // Parse optional time argument (default 60 minutes)
        int minutesBack = 60;
        if (args.length > 0) {
            try {
                minutesBack = Integer.parseInt(args[0]);
                if (minutesBack <= 0) minutesBack = 60;
            } catch (NumberFormatException e) {
                sender.sendMessage(lang().get("history.invalid_time"));
                return true;
            }
        }

        // Check if using Spark
        SparkHook spark = plugin.spark();
        boolean usingSpark = spark != null && spark.isAvailable();

        // Show title with data source info
        if (usingSpark) {
            sender.sendMessage(lang().get("history.title_spark", "%minutes%", String.valueOf(minutesBack)));
        } else {
            sender.sendMessage(lang().get("history.title", "%minutes%", String.valueOf(minutesBack)));
        }

        // Real historical data from DB
        double[] msptWindow = AsciiSparkline.recentMsptWindow(db, tick, minutesBack);
        sender.sendMessage(lang().get("history.mspt_history", "%sparkline%", AsciiSparkline.spark(msptWindow)));

        // Current live stats - use Spark if available
        double liveAvg = usingSpark ? tick.getMsptAvg() : tick.msptAvg();
        sender.sendMessage(lang().get("history.live_avg", "%value%", String.format("%.2f", liveAvg)));

        // Show additional Spark stats if available
        if (usingSpark) {
            SparkHook.SparkStats stats = spark.getStats();
            sender.sendMessage(lang().get("history.live_tps",
                "%tps10s%", String.format("%.2f", stats.tps10s()),
                "%tps1m%", String.format("%.2f", stats.tps1m()),
                "%tps5m%", String.format("%.2f", stats.tps5m())));
        }

        // DB aggregated stats
        double dbAvg = db.getAverageByType("mspt", minutesBack);
        if (dbAvg > 0) {
            sender.sendMessage(lang().get("history.db_avg", "%minutes%", String.valueOf(minutesBack), "%value%", String.format("%.2f", dbAvg)));
        }

        // Performance spikes
        int spikes = db.countPerformanceSpikes(cfg.thresholdMspt(), minutesBack);
        if (spikes > 0) {
            sender.sendMessage(lang().get("history.spikes_warning", "%count%", String.valueOf(spikes), "%threshold%", String.valueOf(cfg.thresholdMspt())));
        }

        return true;
    }
}
