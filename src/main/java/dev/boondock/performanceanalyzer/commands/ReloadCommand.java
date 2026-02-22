package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ReloadCommand implements CommandExecutor {

    private final PerformanceAnalyzer plugin;

    public ReloadCommand(PerformanceAnalyzer plugin) {
        this.plugin = plugin;
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

        sender.sendMessage(lang().get("general.reload_start"));

        try {
            plugin.reloadPlugin();
            sender.sendMessage(lang().get("general.reload_success"));
        } catch (Exception e) {
            sender.sendMessage(lang().get("general.reload_error", "%error%", e.getMessage()));
            plugin.getLogger().severe("Reload failed: " + e.getMessage());
            e.printStackTrace();
        }

        return true;
    }
}
