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

        var errors = plugin.errors();
        if (errors != null && errors.isAttached()) {
            var recent = errors.recent(10 * 60_000L);
            if (!recent.isEmpty()) {
                int total = recent.stream().mapToInt(e -> e.recentCount()).sum();
                var top = recent.get(0);
                sender.sendMessage(lang().get("status.errors",
                        "%count%", String.valueOf(total),
                        "%top%", lang().format("finding.error.title."
                                + top.kind().name().toLowerCase(java.util.Locale.ROOT), top.source())));
            }
        }

        var sampler = plugin.mainThreadSampler();
        if (sampler != null && sampler.isRunning()) {
            var costs = sampler.costs(6);
            if (!costs.isEmpty()) {
                StringBuilder list = new StringBuilder();
                for (int i = 0; i < Math.min(3, costs.size()); i++) {
                    var c = costs.get(i);
                    if (i > 0) {
                        list.append("§7, §f");
                    }
                    list.append(plugin.incidents().describeSampleTarget(c.category(), c.label(), c.detail()))
                            .append(String.format(" %.1f ms/s", c.msPerSec()));
                }
                sender.sendMessage(lang().get("status.main_thread", "%list%", list.toString()));
            }
            sampler.slowTicks(10 * 60_000L).stream()
                    .max(java.util.Comparator.comparingDouble(t -> t.durationMs()))
                    .ifPresent(t -> sender.sendMessage(lang().get("status.slow_tick",
                            "%ms%", String.format("%.0f", t.durationMs()),
                            "%target%", plugin.incidents().describeSampleTarget(t.category(), t.label(), t.detail()))));
        }

        var network = plugin.network();
        if (network != null) {
            var ping = network.snapshot();
            if (ping.players() > 0) {
                sender.sendMessage(lang().get("status.ping",
                        "%avg%", String.format("%.0f", ping.avgPingMs()),
                        "%max%", String.valueOf(ping.worstPingMs()),
                        "%player%", ping.worstPlayer(),
                        "%high%", String.valueOf(ping.highPingPlayers())));
            }
            int spikes = network.spikes(10 * 60_000L).size();
            if (spikes > 0) {
                sender.sendMessage(lang().get("status.network_spikes", "%count%", String.valueOf(spikes)));
            }
        }

        var gcSampler = plugin.gc();
        if (gcSampler != null && gcSampler.memoryTrend().hasData()) {
            var trend = gcSampler.memoryTrend();
            if (trend.leakSuspected()) {
                sender.sendMessage(lang().get("status.memory_trend_warn",
                        "%floor%", String.valueOf(trend.floorMb()),
                        "%slope%", String.format("%.0f", trend.slopeMbPerHour()),
                        "%hours%", String.format("%.0f", trend.hoursUntilFull())));
            } else {
                sender.sendMessage(lang().get("status.memory_trend",
                        "%floor%", String.valueOf(trend.floorMb()),
                        "%slope%", String.format("%+.0f", trend.slopeMbPerHour()),
                        "%span%", String.format("%.1f", trend.spanHours())));
            }
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
