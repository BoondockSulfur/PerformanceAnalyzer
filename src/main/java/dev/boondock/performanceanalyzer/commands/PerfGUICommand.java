package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.gui.GuiManager;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PerfGUICommand implements CommandExecutor {

    private final PerformanceAnalyzer plugin;
    private final GuiManager guiManager;

    public PerfGUICommand(PerformanceAnalyzer plugin, GuiManager guiManager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
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

        guiManager.openMain(player);
        return true;
    }
}
