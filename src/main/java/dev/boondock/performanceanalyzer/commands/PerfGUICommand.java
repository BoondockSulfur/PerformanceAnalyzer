package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.gui.PerformanceGUI;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.MemorySampler;
import dev.boondock.performanceanalyzer.metrics.TickSampler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PerfGUICommand implements CommandExecutor {

    private final PerformanceAnalyzer plugin;
    private final PluginConfig config;
    private final TickSampler tickSampler;
    private final MemorySampler memorySampler;

    public PerfGUICommand(PerformanceAnalyzer plugin, PluginConfig config, TickSampler tickSampler, MemorySampler memorySampler) {
        this.plugin = plugin;
        this.config = config;
        this.tickSampler = tickSampler;
        this.memorySampler = memorySampler;
    }

    private LanguageManager lang() {
        return plugin.lang();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang().get("general.player_only"));
            return true;
        }

        if (!player.hasPermission("performance.gui")) {
            player.sendMessage(lang().get("general.no_permission"));
            return true;
        }

        new PerformanceGUI(plugin, config, tickSampler, memorySampler).open(player);
        return true;
    }
}
