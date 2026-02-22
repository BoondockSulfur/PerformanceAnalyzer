package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.analysis.ChunkTracker;
import dev.boondock.performanceanalyzer.analysis.EntityAnalyzer;
import dev.boondock.performanceanalyzer.analysis.PerformanceDropAnalyzer;
import dev.boondock.performanceanalyzer.analysis.WorldStatsManager;
import dev.boondock.performanceanalyzer.anticheat.MovementAlertManager;
import dev.boondock.performanceanalyzer.anticheat.XRayAlertManager;
import dev.boondock.performanceanalyzer.anticheat.XRayDetector;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.gui.AntiCheatGUI;
import dev.boondock.performanceanalyzer.gui.ConfigGUI;
import dev.boondock.performanceanalyzer.gui.LagAnalysisGUI;
import dev.boondock.performanceanalyzer.gui.PerformanceDropsGUI;
import dev.boondock.performanceanalyzer.gui.PerformanceGUI;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.MemorySampler;
import dev.boondock.performanceanalyzer.metrics.TickSampler;
import dev.boondock.performanceanalyzer.analysis.PlayerActivityTracker;
import dev.boondock.performanceanalyzer.analysis.PluginTimingsAnalyzer;
import org.bukkit.command.PluginCommand;

/**
 * Central command registry for PerformanceAnalyzer.
 * Manages all command registration and GUI event listeners.
 *
 * @since 3.0.0
 */
public class CommandRegistry {

    private final PerformanceAnalyzer plugin;
    private final PluginConfig config;
    private final LanguageManager lang;
    private final TickSampler tickSampler;
    private final MemorySampler memorySampler;
    private final DatabaseManager database;
    private final WorldStatsManager worldStatsManager;
    private final EntityAnalyzer entityAnalyzer;
    private final ChunkTracker chunkTracker;
    private final PerformanceDropAnalyzer dropAnalyzer;
    private final PlayerActivityTracker playerActivityTracker;
    private final PluginTimingsAnalyzer pluginTimingsAnalyzer;

    // AntiCheat components (nullable if disabled)
    private XRayAlertManager xrayAlertManager;
    private XRayDetector xrayDetector;
    private MovementAlertManager movementAlertManager;

    public CommandRegistry(PerformanceAnalyzer plugin, PluginConfig config, LanguageManager lang,
                          TickSampler tickSampler, MemorySampler memorySampler,
                          DatabaseManager database, WorldStatsManager worldStatsManager,
                          EntityAnalyzer entityAnalyzer, ChunkTracker chunkTracker,
                          PerformanceDropAnalyzer dropAnalyzer,
                          PlayerActivityTracker playerActivityTracker,
                          PluginTimingsAnalyzer pluginTimingsAnalyzer) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.tickSampler = tickSampler;
        this.memorySampler = memorySampler;
        this.database = database;
        this.worldStatsManager = worldStatsManager;
        this.entityAnalyzer = entityAnalyzer;
        this.chunkTracker = chunkTracker;
        this.dropAnalyzer = dropAnalyzer;
        this.playerActivityTracker = playerActivityTracker;
        this.pluginTimingsAnalyzer = pluginTimingsAnalyzer;
    }

    /**
     * Set AntiCheat components (called when AntiCheat is enabled).
     */
    public void setAntiCheatComponents(XRayAlertManager xrayAlertManager,
                                       XRayDetector xrayDetector,
                                       MovementAlertManager movementAlertManager) {
        this.xrayAlertManager = xrayAlertManager;
        this.xrayDetector = xrayDetector;
        this.movementAlertManager = movementAlertManager;
    }

    /**
     * Register all commands and GUI listeners.
     */
    public void registerAll() {
        registerPerformanceCommands();
        registerAnalysisCommands();
        registerGUICommands();
        registerAntiCheatCommands();
        plugin.getLogger().info("Command registry initialized: All commands registered");
    }

    /**
     * Re-register AntiCheat commands (used when AntiCheat is enabled via reload).
     */
    public void reregisterAntiCheatCommands() {
        registerAntiCheatCommands();
    }

    /**
     * Register performance monitoring commands.
     */
    private void registerPerformanceCommands() {
        PluginCommand status = plugin.getCommand("perfstatus");
        if (status != null) {
            status.setExecutor(new PerfStatusCommand(plugin, config, tickSampler, memorySampler));
        }

        PluginCommand history = plugin.getCommand("perfhistory");
        if (history != null) {
            history.setExecutor(new PerfHistoryCommand(plugin, config, tickSampler, memorySampler, database));
        }

        PluginCommand reload = plugin.getCommand("perfreload");
        if (reload != null) {
            reload.setExecutor(new ReloadCommand(plugin));
        }
    }

    /**
     * Register analysis commands (world stats, entity stats, chunk stats, performance drops).
     */
    private void registerAnalysisCommands() {
        PluginCommand worldstats = plugin.getCommand("worldstats");
        if (worldstats != null) {
            WorldStatsCommand wsCmd = new WorldStatsCommand(plugin, worldStatsManager);
            worldstats.setExecutor(wsCmd);
            worldstats.setTabCompleter(wsCmd);
        }

        PluginCommand entitystats = plugin.getCommand("entitystats");
        if (entitystats != null) {
            EntityStatsCommand esCmd = new EntityStatsCommand(plugin, entityAnalyzer);
            entitystats.setExecutor(esCmd);
            entitystats.setTabCompleter(esCmd);
        }

        PluginCommand chunkstats = plugin.getCommand("chunkstats");
        if (chunkstats != null) {
            ChunkStatsCommand csCmd = new ChunkStatsCommand(plugin, chunkTracker);
            chunkstats.setExecutor(csCmd);
            chunkstats.setTabCompleter(csCmd);
        }

        PluginCommand perfdrops = plugin.getCommand("perfdrops");
        if (perfdrops != null) {
            PerformanceDropsCommand pdCmd = new PerformanceDropsCommand(plugin, config, dropAnalyzer);
            perfdrops.setExecutor(pdCmd);
            perfdrops.setTabCompleter(pdCmd);
        }
    }

    /**
     * Register GUI command and event listeners.
     */
    private void registerGUICommands() {
        PluginCommand gui = plugin.getCommand("perfgui");
        if (gui != null) {
            PerfGUICommand guiCmd = new PerfGUICommand(plugin, config, tickSampler, memorySampler);
            gui.setExecutor(guiCmd);

            // Register GUI listeners
            plugin.getServer().getPluginManager().registerEvents(
                new PerformanceGUI(plugin, config, tickSampler, memorySampler), plugin);
            plugin.getServer().getPluginManager().registerEvents(
                new ConfigGUI(plugin, config, null), plugin);
            plugin.getServer().getPluginManager().registerEvents(
                new AntiCheatGUI(plugin, config, null), plugin);
            plugin.getServer().getPluginManager().registerEvents(
                new LagAnalysisGUI(plugin, config, null, playerActivityTracker, pluginTimingsAnalyzer), plugin);
            plugin.getServer().getPluginManager().registerEvents(
                new PerformanceDropsGUI(plugin, config, null, dropAnalyzer), plugin);

            plugin.getLogger().info("Extended GUI pages registered: Lag Analysis & Performance Drops");
        }
    }

    /**
     * Register AntiCheat commands (if AntiCheat is enabled).
     */
    private void registerAntiCheatCommands() {
        PluginCommand acwhitelist = plugin.getCommand("acwhitelist");
        if (acwhitelist != null) {
            ACWhitelistCommand acwlCmd = new ACWhitelistCommand(plugin, config);
            acwhitelist.setExecutor(acwlCmd);
            acwhitelist.setTabCompleter(acwlCmd);
        }

        PluginCommand xrayalerts = plugin.getCommand("xrayalerts");
        if (xrayalerts != null) {
            if (xrayAlertManager != null) {
                XRayAlertsCommand xraCmd = new XRayAlertsCommand(plugin, xrayAlertManager);
                xrayalerts.setExecutor(xraCmd);
                xrayalerts.setTabCompleter(xraCmd);
            } else {
                // AntiCheat disabled - show message
                xrayalerts.setExecutor((sender, cmd, label, args) -> {
                    sender.sendMessage(lang.get("xray.disabled"));
                    sender.sendMessage(lang.get("xray.disabled_hint"));
                    return true;
                });
            }
        }

        PluginCommand xrayores = plugin.getCommand("xrayores");
        if (xrayores != null) {
            if (xrayDetector != null) {
                XRayOresCommand xroCmd = new XRayOresCommand(plugin, config, xrayDetector);
                xrayores.setExecutor(xroCmd);
                xrayores.setTabCompleter(xroCmd);
            } else {
                xrayores.setExecutor((sender, cmd, label, args) -> {
                    sender.sendMessage(lang.get("xray.disabled"));
                    sender.sendMessage(lang.get("xray.disabled_hint"));
                    return true;
                });
            }
        }

        PluginCommand movealerts = plugin.getCommand("movealerts");
        if (movealerts != null) {
            if (movementAlertManager != null) {
                MoveAlertsCommand mvaCmd = new MoveAlertsCommand(plugin, movementAlertManager);
                movealerts.setExecutor(mvaCmd);
                movealerts.setTabCompleter(mvaCmd);
            } else {
                // AntiCheat disabled - show message
                movealerts.setExecutor((sender, cmd, label, args) -> {
                    sender.sendMessage(lang.get("movement.disabled"));
                    sender.sendMessage(lang.get("movement.disabled_hint"));
                    return true;
                });
            }
        }
    }
}
