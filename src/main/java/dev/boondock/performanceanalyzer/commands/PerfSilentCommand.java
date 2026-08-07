package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.alerts.AlertPreferenceManager;
import dev.boondock.performanceanalyzer.alerts.AlertPreferenceManager.AlertCategory;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Command to toggle alert notifications for admins.
 * Useful for streamers or admins who don't want alerts in chat.
 *
 * Usage:
 *   /perfsilent           - Toggle ALL alerts on/off
 *   /perfsilent <type>    - Toggle specific type (performance)
 *   /perfsilent list      - Show current alert preferences
 *
 * @since 2.3.1
 */
public class PerfSilentCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final LanguageManager lang;
    private final AlertPreferenceManager preferences;

    public PerfSilentCommand(Plugin plugin, LanguageManager lang, AlertPreferenceManager preferences) {
        this.plugin = plugin;
        this.lang = lang;
        this.preferences = preferences;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("performance.admin")) {
            sender.sendMessage(lang.get("general.no_permission"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.get("general.player_only"));
            return true;
        }

        if (args.length == 0) {
            // Toggle all alerts
            boolean silenced = preferences.toggleAll(player.getUniqueId());
            if (silenced) {
                player.sendMessage(lang.get("silent.all_muted"));
            } else {
                player.sendMessage(lang.get("silent.all_unmuted"));
            }
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("list")) {
            showStatus(player);
            return true;
        }

        if (subCommand.equals("reset")) {
            preferences.resetAll(player.getUniqueId());
            player.sendMessage(lang.get("silent.reset"));
            return true;
        }

        // Try to parse as category
        AlertCategory category;
        try {
            category = AlertCategory.valueOf(subCommand.toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(lang.get("silent.unknown_type", "%type%", subCommand));
            player.sendMessage(lang.get("silent.available_types"));
            return true;
        }

        boolean silenced = preferences.toggleCategory(player.getUniqueId(), category);
        if (silenced) {
            player.sendMessage(lang.get("silent.category_muted",
                    "%category%", category.getDisplayName(lang.getCurrentLanguage())));
        } else {
            player.sendMessage(lang.get("silent.category_unmuted",
                    "%category%", category.getDisplayName(lang.getCurrentLanguage())));
        }

        return true;
    }

    private void showStatus(Player player) {
        player.sendMessage(lang.get("silent.list_header"));

        Set<AlertCategory> muted = preferences.getMutedCategories(player.getUniqueId());

        if (muted.isEmpty()) {
            player.sendMessage(lang.get("silent.receiving_all"));
        } else if (muted.contains(AlertCategory.ALL)) {
            player.sendMessage(lang.get("silent.status_all_muted"));
        } else {
            for (AlertCategory category : AlertCategory.values()) {
                if (category == AlertCategory.ALL) continue;

                String status = muted.contains(category)
                        ? "\u00a7c\u2716 " + lang.get("silent.status_muted")
                        : "\u00a7a\u2714 " + lang.get("silent.status_active");
                player.sendMessage(String.format("  \u00a77%s: %s",
                        category.getDisplayName(lang.getCurrentLanguage()), status));
            }
        }

        player.sendMessage(lang.get("silent.list_hint"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("list");
            completions.add("reset");
            for (AlertCategory category : AlertCategory.values()) {
                completions.add(category.name().toLowerCase());
            }
            String input = args[0].toLowerCase();
            return completions.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
