package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.analysis.SeverityModel;
import dev.boondock.performanceanalyzer.integration.SparkHook;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.GcSampler;
import dev.boondock.performanceanalyzer.metrics.TickStats;
import dev.boondock.performanceanalyzer.monitor.MonitorService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * /perfstatus — live view of the v3 measurement core.
 *
 * <p>MSPT here is <em>real tick work time</em> (from ServerTickEndEvent),
 * not the tick interval: single-digit values are healthy, 50 ms is the
 * deadline. All values come from {@link MonitorService}.</p>
 */
public class PerfStatusCommand implements CommandExecutor {

    private final PerformanceAnalyzer plugin;
    private final MonitorService monitor;

    public PerfStatusCommand(PerformanceAnalyzer plugin, MonitorService monitor) {
        this.plugin = plugin;
        this.monitor = monitor;
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

        TickStats ticks = monitor.tickStats();
        GcSampler.GcStats gc = monitor.gcStats();
        SeverityModel.Assessment assessment = monitor.assessment();

        sender.sendMessage(lang().get("status.title"));

        if (ticks.hasData()) {
            sender.sendMessage(lang().get("status.tps_windows",
                    "%tps10%", String.format("%.2f", ticks.tps10s()),
                    "%tps60%", String.format("%.2f", ticks.tps60s())));
            sender.sendMessage(lang().get("status.mspt_detail",
                    "%avg%", String.format("%.1f", ticks.msptAvg10s()),
                    "%p50%", String.format("%.1f", ticks.msptP50()),
                    "%p95%", String.format("%.1f", ticks.msptP95()),
                    "%p99%", String.format("%.1f", ticks.msptP99()),
                    "%max%", String.format("%.1f", ticks.msptMax10s())));
            sender.sendMessage(lang().get(ticks.eventDriven()
                    ? "status.mspt_note"
                    : "status.mspt_note_estimated"));
        } else {
            sender.sendMessage(lang().get("status.no_data"));
        }

        sender.sendMessage(lang().get("status.severity",
                "%color%", assessment.severity().color(),
                "%severity%", assessment.severity().name(),
                "%score%", String.valueOf(assessment.score())));

        if (gc != null) {
            String oldGen = gc.hasAfterGcData()
                    ? String.format("%.1f%%", gc.oldGenAfterGcPercent())
                    : "-";
            sender.sendMessage(lang().get("status.gc",
                    "%time%", String.valueOf(gc.gcTimeMs60s()),
                    "%count%", String.valueOf(gc.gcCount60s()),
                    "%oldgen%", oldGen));
            sender.sendMessage(lang().get("status.heap_detail",
                    "%used%", String.valueOf(gc.heapUsedMb()),
                    "%max%", String.valueOf(gc.heapMaxMb()),
                    "%percent%", String.format("%.1f", gc.heapUsedPercent())));
        }

        if (monitor.baseline().isEstablished()) {
            sender.sendMessage(lang().get("status.baseline",
                    "%value%", String.format("%.1f", monitor.baseline().avgMspt())));
        }

        SparkHook spark = plugin.spark();
        if (spark != null && spark.isAvailable()) {
            SparkHook.SparkStats stats = spark.getStats();
            if (stats.hasValidCpu()) {
                sender.sendMessage(lang().get("status.cpu",
                        "%system%", String.format("%.1f", stats.cpuSystem()),
                        "%process%", String.format("%.1f", stats.cpuProcess())));
            }
        }
        return true;
    }
}
