package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.analysis.ChunkTracker;
import dev.boondock.performanceanalyzer.analysis.ChunkTracker.*;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command to show chunk statistics.
 * Usage:
 * - /chunkstats - Show chunk overview
 * - /chunkstats problems - Show problematic chunks
 * - /chunkstats frequent - Show frequently loaded chunks
 * - /chunkstats clear - Clear tracking history
 */
public class ChunkStatsCommand implements CommandExecutor, TabCompleter {

    private final PerformanceAnalyzer plugin;
    private final ChunkTracker tracker;

    public ChunkStatsCommand(PerformanceAnalyzer plugin, ChunkTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    private LanguageManager lang() {
        return plugin.lang();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("performance.admin")) {
            sender.sendMessage(lang().get("general.no_permission"));
            return true;
        }

        if (args.length == 0) {
            showOverview(sender);
        } else {
            switch (args[0].toLowerCase()) {
                case "problems", "problematic" -> showProblematicChunks(sender);
                case "frequent", "thrashing" -> showFrequentlyLoaded(sender);
                case "clear", "reset" -> clearHistory(sender);
                default -> showOverview(sender);
            }
        }

        return true;
    }

    private void showOverview(CommandSender sender) {
        ChunkSummary summary = tracker.getSummary();
        Map<String, WorldChunkInfo> perWorld = tracker.getPerWorldStats();

        sender.sendMessage(lang().get("chunkstats.header"));
        sender.sendMessage(lang().get("chunkstats.total_loaded", "%count%", String.valueOf(summary.totalLoaded())));
        sender.sendMessage(lang().get("chunkstats.force_loaded", "%count%", String.valueOf(summary.totalForceLoaded())));
        sender.sendMessage(lang().get("chunkstats.loads_minute", "%count%", String.valueOf(summary.loadsThisMinute())));
        sender.sendMessage(lang().get("chunkstats.total_loads", "%count%", String.valueOf(summary.totalLoadsAllTime())));
        sender.sendMessage(lang().get("chunkstats.total_unloads", "%count%", String.valueOf(summary.totalUnloadsAllTime())));
        sender.sendMessage("");

        // Per-world breakdown
        sender.sendMessage(lang().get("chunkstats.per_world"));
        for (Map.Entry<String, Integer> entry : summary.perWorldLoaded().entrySet()) {
            WorldChunkInfo info = perWorld.get(entry.getKey());
            sender.sendMessage(lang().get("chunkstats.world_entry",
                    "%world%", entry.getKey(),
                    "%loaded%", String.valueOf(entry.getValue()),
                    "%force%", info != null ? String.valueOf(info.forceLoadedChunks()) : "0",
                    "%generated%", info != null ? String.valueOf(info.newChunksGenerated()) : "0"));
        }

        sender.sendMessage("");
        sender.sendMessage(lang().get("chunkstats.hint_problems"));
        sender.sendMessage(lang().get("chunkstats.hint_frequent"));
    }

    private void showProblematicChunks(CommandSender sender) {
        List<ProblematicChunk> problems = tracker.getProblematicChunks(10);

        if (problems.isEmpty()) {
            sender.sendMessage(lang().get("chunkstats.no_problems"));
            return;
        }

        sender.sendMessage(lang().get("chunkstats.problems_header"));
        for (ProblematicChunk chunk : problems) {
            String status = chunk.forceLoaded() ? lang().get("chunkstats.force_status") : "";
            String type = chunk.problemType() == ChunkProblemType.HIGH_TILE_ENTITIES
                    ? lang().get("chunkstats.type_tiles")
                    : lang().get("chunkstats.type_entities");

            sender.sendMessage(lang().get("chunkstats.problem_entry",
                    "%world%", chunk.worldName(),
                    "%x%", String.valueOf(chunk.blockX()),
                    "%z%", String.valueOf(chunk.blockZ()),
                    "%status%", status));
            sender.sendMessage(lang().get("chunkstats.problem_details",
                    "%type%", type,
                    "%entities%", String.valueOf(chunk.entityCount()),
                    "%tiles%", String.valueOf(chunk.tileEntityCount())));
        }
    }

    private void showFrequentlyLoaded(CommandSender sender) {
        List<FrequentlyLoadedChunk> frequent = tracker.getFrequentlyLoadedChunks(10);

        if (frequent.isEmpty()) {
            sender.sendMessage(lang().get("chunkstats.no_frequent"));
            return;
        }

        sender.sendMessage(lang().get("chunkstats.frequent_header"));
        sender.sendMessage(lang().get("chunkstats.frequent_info"));
        sender.sendMessage("");

        for (FrequentlyLoadedChunk chunk : frequent) {
            sender.sendMessage(lang().get("chunkstats.frequent_entry",
                    "%world%", chunk.worldName(),
                    "%x%", String.valueOf(chunk.blockX()),
                    "%z%", String.valueOf(chunk.blockZ()),
                    "%loads%", String.valueOf(chunk.loadCount())));
            sender.sendMessage(lang().get("chunkstats.frequent_details",
                    "%entities%", String.valueOf(chunk.maxEntities()),
                    "%tiles%", String.valueOf(chunk.maxTileEntities())));
        }
    }

    private void clearHistory(CommandSender sender) {
        tracker.clearHistory();
        sender.sendMessage(lang().get("chunkstats.cleared"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("performance.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> completions = List.of("problems", "frequent", "clear");
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
