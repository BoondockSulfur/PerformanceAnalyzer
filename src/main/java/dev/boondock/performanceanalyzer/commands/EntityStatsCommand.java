package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.analysis.EntityAnalyzer;
import dev.boondock.performanceanalyzer.analysis.EntityAnalyzer.*;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command to show entity analysis.
 * Usage:
 * - /entitystats - Show server-wide entity analysis
 * - /entitystats <world> - Show analysis for specific world
 * - /entitystats hotspots - Show entity hotspots
 */
public class EntityStatsCommand implements CommandExecutor, TabCompleter {

    private final PerformanceAnalyzer plugin;
    private final EntityAnalyzer analyzer;

    public EntityStatsCommand(PerformanceAnalyzer plugin, EntityAnalyzer analyzer) {
        this.plugin = plugin;
        this.analyzer = analyzer;
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
            showServerAnalysis(sender);
        } else if (args[0].equalsIgnoreCase("hotspots")) {
            showHotspots(sender);
        } else {
            String worldName = args[0];
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(lang().get("worldstats.world_not_found", "%world%", worldName));
                return true;
            }
            showWorldAnalysis(sender, world);
        }

        return true;
    }

    private void showServerAnalysis(CommandSender sender) {
        EntityAnalysis analysis = analyzer.analyze();

        sender.sendMessage(lang().get("entitystats.header"));
        sender.sendMessage(lang().get("entitystats.total", "%count%", String.valueOf(analysis.totalEntities())));
        sender.sendMessage("");

        // Categories
        sender.sendMessage(lang().get("entitystats.by_category"));
        for (Map.Entry<EntityCategory, Integer> entry : analysis.byCategory().entrySet()) {
            if (entry.getValue() > 0) {
                sender.sendMessage(lang().get("entitystats.category_entry",
                        "%category%", getCategoryName(entry.getKey()),
                        "%count%", String.valueOf(entry.getValue())));
            }
        }
        sender.sendMessage("");

        // Top entities
        sender.sendMessage(lang().get("entitystats.top_entities"));
        for (Map.Entry<EntityType, Integer> entry : analysis.topEntities()) {
            sender.sendMessage(lang().get("entitystats.entity_entry",
                    "%type%", formatEntityType(entry.getKey()),
                    "%count%", String.valueOf(entry.getValue())));
        }

        // Hotspot preview
        if (!analysis.hotspots().isEmpty()) {
            sender.sendMessage("");
            sender.sendMessage(lang().get("entitystats.hotspot_preview",
                    "%count%", String.valueOf(analysis.hotspots().size())));
            sender.sendMessage(lang().get("entitystats.hint_hotspots"));
        }
    }

    private void showWorldAnalysis(CommandSender sender, World world) {
        WorldEntityAnalysis analysis = analyzer.analyzeWorld(world);

        String statusColor = switch (analysis.status()) {
            case HEALTHY -> "\u00a7a";
            case WARNING -> "\u00a7e";
            case CRITICAL -> "\u00a7c";
        };
        String statusText = switch (analysis.status()) {
            case HEALTHY -> lang().get("entitystats.status_healthy");
            case WARNING -> lang().get("entitystats.status_warning");
            case CRITICAL -> lang().get("entitystats.status_critical");
        };

        sender.sendMessage(lang().get("entitystats.world_header", "%world%", world.getName()));
        sender.sendMessage(lang().get("entitystats.world_status",
                "%status%", statusColor + statusText));
        sender.sendMessage(lang().get("entitystats.total", "%count%", String.valueOf(analysis.totalEntities())));
        sender.sendMessage("");

        // Categories
        sender.sendMessage(lang().get("entitystats.by_category"));
        for (Map.Entry<EntityCategory, Integer> entry : analysis.byCategory().entrySet()) {
            if (entry.getValue() > 0) {
                sender.sendMessage(lang().get("entitystats.category_entry",
                        "%category%", getCategoryName(entry.getKey()),
                        "%count%", String.valueOf(entry.getValue())));
            }
        }
        sender.sendMessage("");

        // Top entities
        sender.sendMessage(lang().get("entitystats.top_entities"));
        for (Map.Entry<EntityType, Integer> entry : analysis.topEntities()) {
            sender.sendMessage(lang().get("entitystats.entity_entry",
                    "%type%", formatEntityType(entry.getKey()),
                    "%count%", String.valueOf(entry.getValue())));
        }
    }

    private String getCategoryName(EntityCategory category) {
        String translated = lang().get(category.getKey());
        // If key not found, LanguageManager returns the key itself
        if (translated.equals(category.getKey())) {
            return category.getDisplayName();
        }
        return translated;
    }

    private void showHotspots(CommandSender sender) {
        EntityAnalysis analysis = analyzer.analyze();
        List<EntityHotspot> hotspots = analysis.hotspots();

        if (hotspots.isEmpty()) {
            sender.sendMessage(lang().get("entitystats.no_hotspots"));
            return;
        }

        sender.sendMessage(lang().get("entitystats.hotspots_header"));
        for (EntityHotspot hotspot : hotspots) {
            String severity = hotspot.severity() == HotspotSeverity.CRITICAL
                    ? lang().get("entitystats.severity_critical")
                    : lang().get("entitystats.severity_warning");

            sender.sendMessage(lang().get("entitystats.hotspot_entry",
                    "%severity%", severity,
                    "%world%", hotspot.worldName(),
                    "%x%", String.valueOf(hotspot.x()),
                    "%z%", String.valueOf(hotspot.z()),
                    "%count%", String.valueOf(hotspot.entityCount())));
            sender.sendMessage(lang().get("entitystats.hotspot_dominant",
                    "%type%", formatEntityType(hotspot.dominantType()),
                    "%count%", String.valueOf(hotspot.dominantCount())));
        }
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
            List<String> completions = new ArrayList<>();
            completions.add("hotspots");
            Bukkit.getWorlds().forEach(w -> completions.add(w.getName()));

            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
