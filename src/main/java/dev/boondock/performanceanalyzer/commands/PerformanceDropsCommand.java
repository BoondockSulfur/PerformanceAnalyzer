package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.analysis.PerformanceDropAnalyzer;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Command to view recent performance drops and their analysis.
 */
public class PerformanceDropsCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final PerformanceDropAnalyzer dropAnalyzer;

    public PerformanceDropsCommand(JavaPlugin plugin, PluginConfig config, PerformanceDropAnalyzer dropAnalyzer) {
        this.plugin = plugin;
        this.config = config;
        this.dropAnalyzer = dropAnalyzer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("performance.admin")) {
            sender.sendMessage(config.msg("no_permission", "§cDafür hast du keine Berechtigung."));
            return true;
        }

        if (dropAnalyzer == null) {
            sender.sendMessage("§cPerformance Drop Analyzer ist nicht verfügbar.");
            return true;
        }

        // Check for clear command
        if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
            dropAnalyzer.clearDrops();
            sender.sendMessage("§aPerformance-Drop Cache wurde geleert.");
            return true;
        }

        List<PerformanceDropAnalyzer.PerformanceDrop> drops = dropAnalyzer.getRecentDrops();

        if (drops.isEmpty()) {
            sender.sendMessage("§aKeine Performance-Einbrüche seit dem letzten Server-Start aufgezeichnet.");
            sender.sendMessage("§7Tipp: Verwende §f/perfdrops clear§7 um den Cache zu leeren.");
            return true;
        }

        // Check if user wants details for a specific drop
        if (args.length > 0) {
            try {
                int index = Integer.parseInt(args[0]) - 1; // User-friendly 1-based index
                if (index >= 0 && index < drops.size()) {
                    // Reverse order to show most recent first
                    List<PerformanceDropAnalyzer.PerformanceDrop> reversed = new ArrayList<>(drops);
                    java.util.Collections.reverse(reversed);
                    PerformanceDropAnalyzer.PerformanceDrop drop = reversed.get(index);
                    sender.sendMessage(dropAnalyzer.getDetailedReport(drop));
                    return true;
                } else {
                    sender.sendMessage("§cUngültiger Index. Verwende eine Nummer zwischen 1 und " + drops.size());
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§cUngültige Nummer. Verwendung: /perfdrops [nummer|clear]");
                return true;
            }
        }

        // Show list of recent drops
        sender.sendMessage("§6§l═══════════════════════════════════════════");
        sender.sendMessage("§e§lLetzte Performance-Einbrüche §7(" + drops.size() + ")");
        sender.sendMessage("§6§l═══════════════════════════════════════════");
        sender.sendMessage("");

        // Reverse to show most recent first
        List<PerformanceDropAnalyzer.PerformanceDrop> reversed = new ArrayList<>(drops);
        java.util.Collections.reverse(reversed);

        int index = 1;
        for (PerformanceDropAnalyzer.PerformanceDrop drop : reversed) {
            sender.sendMessage("§e#" + index + " §7- " + drop.getShortSummary());
            index++;
        }

        sender.sendMessage("");
        sender.sendMessage("§7Verwende §f/perfdrops <nummer> §7für Details");
        sender.sendMessage("§7Verwende §f/perfdrops clear §7um den Cache zu leeren");
        sender.sendMessage("§6§l═══════════════════════════════════════════");

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1 && sender.hasPermission("performance.admin")) {
            // Add "clear" option
            if ("clear".startsWith(args[0].toLowerCase())) {
                completions.add("clear");
            }

            // Add numeric options
            if (dropAnalyzer != null) {
                int dropCount = dropAnalyzer.getRecentDrops().size();
                for (int i = 1; i <= dropCount; i++) {
                    String num = String.valueOf(i);
                    if (num.startsWith(args[0])) {
                        completions.add(num);
                    }
                }
            }
        }

        return completions;
    }
}
