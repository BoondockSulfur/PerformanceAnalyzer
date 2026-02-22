package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.analysis.WorldStatsManager;
import dev.boondock.performanceanalyzer.analysis.WorldStatsManager.*;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Command to show per-world statistics.
 * Usage:
 * - /worldstats - Show all worlds overview
 * - /worldstats <world> - Show details for specific world
 */
public class WorldStatsCommand implements CommandExecutor, TabCompleter {

    private final PerformanceAnalyzer plugin;
    private final WorldStatsManager statsManager;

    public WorldStatsCommand(PerformanceAnalyzer plugin, WorldStatsManager statsManager) {
        this.plugin = plugin;
        this.statsManager = statsManager;
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
            String worldName = args[0];
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(lang().get("worldstats.world_not_found", "%world%", worldName));
                return true;
            }
            showWorldDetails(sender, world);
        }

        return true;
    }

    private void showOverview(CommandSender sender) {
        ServerEntityStats serverStats = statsManager.getServerStats();
        List<WorldStats> worldStats = statsManager.getAllWorldStats();

        sender.sendMessage(lang().get("worldstats.header"));
        sender.sendMessage(lang().get("worldstats.server_summary",
                "%worlds%", String.valueOf(serverStats.worldCount()),
                "%entities%", String.valueOf(serverStats.totalEntities()),
                "%chunks%", String.valueOf(serverStats.totalChunks()),
                "%tiles%", String.valueOf(serverStats.totalTileEntities())));
        sender.sendMessage(lang().get("worldstats.avg_density",
                "%density%", String.format("%.2f", serverStats.avgEntityDensity())));
        sender.sendMessage("");

        for (WorldStats stats : worldStats) {
            String status = getStatusColor(stats.entityCount());
            sender.sendMessage(lang().get("worldstats.world_entry",
                    "%status%", status,
                    "%world%", stats.worldName(),
                    "%type%", getEnvironmentName(stats.environment()),
                    "%entities%", String.valueOf(stats.entityCount()),
                    "%chunks%", String.valueOf(stats.loadedChunks()),
                    "%density%", String.format("%.2f", stats.entityDensity())));
        }

        sender.sendMessage("");
        sender.sendMessage(lang().get("worldstats.hint_details"));
    }

    private void showWorldDetails(CommandSender sender, World world) {
        WorldStats stats = statsManager.getWorldStats(world);

        sender.sendMessage(lang().get("worldstats.detail_header", "%world%", world.getName()));
        sender.sendMessage(lang().get("worldstats.detail_type", "%type%", getEnvironmentName(stats.environment())));
        sender.sendMessage(lang().get("worldstats.detail_entities", "%count%", String.valueOf(stats.entityCount())));
        sender.sendMessage(lang().get("worldstats.detail_players", "%count%", String.valueOf(stats.playerCount())));
        sender.sendMessage(lang().get("worldstats.detail_chunks", "%count%", String.valueOf(stats.loadedChunks())));
        sender.sendMessage(lang().get("worldstats.detail_tiles", "%count%", String.valueOf(stats.tileEntityCount())));
        sender.sendMessage(lang().get("worldstats.detail_density", "%density%", String.format("%.2f", stats.entityDensity())));

        if (!stats.topEntities().isEmpty()) {
            sender.sendMessage("");
            sender.sendMessage(lang().get("worldstats.top_entities"));
            for (Map.Entry<EntityType, Integer> entry : stats.topEntities()) {
                sender.sendMessage(lang().get("worldstats.entity_entry",
                        "%type%", formatEntityType(entry.getKey()),
                        "%count%", String.valueOf(entry.getValue())));
            }
        }

        // Show high-density chunks
        List<ChunkEntityInfo> hotChunks = statsManager.getHighDensityChunks(world, 5);
        if (!hotChunks.isEmpty()) {
            sender.sendMessage("");
            sender.sendMessage(lang().get("worldstats.hotspots"));
            for (ChunkEntityInfo chunk : hotChunks) {
                sender.sendMessage(lang().get("worldstats.hotspot_entry",
                        "%x%", String.valueOf(chunk.blockX()),
                        "%z%", String.valueOf(chunk.blockZ()),
                        "%entities%", String.valueOf(chunk.entityCount()),
                        "%tiles%", String.valueOf(chunk.tileEntityCount())));
            }
        }
    }

    private String getStatusColor(int entityCount) {
        if (entityCount >= 10000) return "\u00a7c"; // Red
        if (entityCount >= 5000) return "\u00a7e"; // Yellow
        return "\u00a7a"; // Green
    }

    private String getEnvironmentName(World.Environment environment) {
        String key = "worldstats.environment_" + environment.name().toLowerCase();
        String translated = lang().get(key);
        if (translated.equals(key)) {
            // Fallback if not translated
            return switch (environment) {
                case NORMAL -> "Overworld";
                case NETHER -> "Nether";
                case THE_END -> "The End";
                case CUSTOM -> "Custom";
            };
        }
        return translated;
    }

    private String formatEntityType(EntityType type) {
        String name = type.name().toLowerCase().replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("performance.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Bukkit.getWorlds().stream()
                    .map(World::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
