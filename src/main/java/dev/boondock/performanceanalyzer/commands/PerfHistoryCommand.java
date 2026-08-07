package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.TickStats;
import dev.boondock.performanceanalyzer.monitor.MonitorService;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import dev.boondock.performanceanalyzer.util.AsciiSparkline;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * /perfhistory [minutes] — sparkline + aggregates from the database.
 *
 * <p>All database queries run off the tick threads; the composed message is
 * then sent directly (CommandSender#sendMessage is thread-safe for plain
 * text on Paper/Folia).</p>
 */
public class PerfHistoryCommand implements CommandExecutor {

    /** Tick-work spike threshold for the spike counter (the 50 ms deadline). */
    private static final double SPIKE_THRESHOLD_MS = 50.0;

    private final PerformanceAnalyzer plugin;
    private final MonitorService monitor;
    private final DatabaseManager db;

    public PerfHistoryCommand(PerformanceAnalyzer plugin, MonitorService monitor, DatabaseManager db) {
        this.plugin = plugin;
        this.monitor = monitor;
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

        // Parse optional time argument (default 60 minutes), clamped to [1, 1440]
        int requested = 60;
        if (args.length > 0) {
            try {
                requested = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                sender.sendMessage(lang().get("history.invalid_time"));
                return true;
            }
        }
        final int minutesBack = Math.max(1, Math.min(1440, requested));

        // Live values are read up front (cheap, no DB involved).
        TickStats ticks = monitor.tickStats();
        final double liveAvg = ticks.hasData() ? ticks.msptAvg10s() : 0.0;

        Scheduling.runAsync(plugin, () -> {
            try {
                double[] msptWindow = AsciiSparkline.recentMsptWindow(db, liveAvg, minutesBack);
                double dbAvg = db.getAverageByType("mspt", minutesBack);
                int spikes = db.countPerformanceSpikes(SPIKE_THRESHOLD_MS, minutesBack);

                reply(sender, lang().get("history.title", "%minutes%", String.valueOf(minutesBack)));
                reply(sender, lang().get("history.mspt_history",
                        "%sparkline%", AsciiSparkline.spark(msptWindow)));
                reply(sender, lang().get("history.live_avg",
                        "%value%", String.format("%.2f", liveAvg)));
                if (dbAvg > 0) {
                    reply(sender, lang().get("history.db_avg",
                            "%minutes%", String.valueOf(minutesBack),
                            "%value%", String.format("%.2f", dbAvg)));
                }
                if (spikes > 0) {
                    reply(sender, lang().get("history.spikes_warning",
                            "%count%", String.valueOf(spikes),
                            "%threshold%", String.format("%.0f", SPIKE_THRESHOLD_MS)));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[History] Query failed: " + e.getMessage());
                reply(sender, lang().get("general.unknown_error"));
            }
        });
        return true;
    }

    /**
     * Async replies never reach RCON senders (the RCON response is closed
     * before the query finishes), so non-player senders additionally get the
     * result written to the server log.
     */
    private void reply(org.bukkit.command.CommandSender sender, String message) {
        sender.sendMessage(message);
        if (!(sender instanceof org.bukkit.entity.Player)) {
            plugin.getLogger().info("[History] " + message.replaceAll("§x(§[0-9a-fA-F]){6}|§[0-9a-fk-orA-FK-OR]", ""));
        }
    }
}
