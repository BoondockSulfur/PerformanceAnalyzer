package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.alerts.AlertPreferenceManager;
import dev.boondock.performanceanalyzer.analysis.ChunkTracker;
import dev.boondock.performanceanalyzer.analysis.EntityAnalyzer;
import dev.boondock.performanceanalyzer.analysis.IncidentAnalyzer;
import dev.boondock.performanceanalyzer.analysis.PlayerActivityTracker;
import dev.boondock.performanceanalyzer.analysis.WorldStatsManager;
import dev.boondock.performanceanalyzer.calibrate.CalibrationEngine;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.gui.GuiManager;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.monitor.MonitorService;
import dev.boondock.performanceanalyzer.timing.ListenerTimings;
import org.bukkit.command.PluginCommand;

/**
 * Central command registry for PerformanceAnalyzer.
 * Manages all command registration and the single shared GUI listener.
 *
 * @since 3.1.0
 */
public class CommandRegistry {

    private final PerformanceAnalyzer plugin;
    private final PluginConfig config;
    private final LanguageManager lang;
    private final MonitorService monitorService;
    private final DatabaseManager database;
    private final WorldStatsManager worldStatsManager;
    private final EntityAnalyzer entityAnalyzer;
    private final ChunkTracker chunkTracker;
    private final IncidentAnalyzer incidentAnalyzer;
    private final PlayerActivityTracker playerActivityTracker;
    private final ListenerTimings listenerTimings;
    private final CalibrationEngine calibrationEngine;

    private GuiManager guiManager;

    public CommandRegistry(PerformanceAnalyzer plugin, PluginConfig config, LanguageManager lang,
                           MonitorService monitorService, DatabaseManager database,
                           WorldStatsManager worldStatsManager, EntityAnalyzer entityAnalyzer,
                           ChunkTracker chunkTracker, IncidentAnalyzer incidentAnalyzer,
                           PlayerActivityTracker playerActivityTracker,
                           ListenerTimings listenerTimings,
                           CalibrationEngine calibrationEngine) {
        this.calibrationEngine = calibrationEngine;
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.monitorService = monitorService;
        this.database = database;
        this.worldStatsManager = worldStatsManager;
        this.entityAnalyzer = entityAnalyzer;
        this.chunkTracker = chunkTracker;
        this.incidentAnalyzer = incidentAnalyzer;
        this.playerActivityTracker = playerActivityTracker;
        this.listenerTimings = listenerTimings;
    }

    /**
     * Register all commands and the shared GUI listener.
     */
    public void registerAll() {
        registerPerformanceCommands();
        registerAnalysisCommands();
        registerGUICommands();
        plugin.getLogger().info("Command registry initialized: All commands registered");
    }

    /**
     * Register performance monitoring commands.
     */
    private void registerPerformanceCommands() {
        PluginCommand status = plugin.getCommand("perfstatus");
        if (status != null) {
            status.setExecutor(new PerfStatusCommand(plugin, monitorService));
        }

        PluginCommand history = plugin.getCommand("perfhistory");
        if (history != null) {
            history.setExecutor(new PerfHistoryCommand(plugin, monitorService, database));
        }

        PluginCommand reload = plugin.getCommand("perfreload");
        if (reload != null) {
            reload.setExecutor(new ReloadCommand(plugin));
        }

        // Silent mode command (streamer mode)
        PluginCommand silent = plugin.getCommand("perfsilent");
        if (silent != null) {
            AlertPreferenceManager preferences = plugin.getAlertPreferenceManager();
            if (preferences != null) {
                PerfSilentCommand silentCmd = new PerfSilentCommand(plugin, lang, preferences);
                silent.setExecutor(silentCmd);
                silent.setTabCompleter(silentCmd);
            }
        }

        PluginCommand calibrate = plugin.getCommand("perfcalibrate");
        if (calibrate != null && calibrationEngine != null) {
            PerfCalibrateCommand calCmd = new PerfCalibrateCommand(plugin, calibrationEngine, config);
            calibrate.setExecutor(calCmd);
            calibrate.setTabCompleter(calCmd);
        }
    }

    /**
     * Register analysis commands (world stats, entity stats, chunk stats, incidents).
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

        PluginCommand incidents = plugin.getCommand("perfincidents");
        if (incidents != null) {
            PerfIncidentsCommand piCmd = new PerfIncidentsCommand(plugin, incidentAnalyzer);
            incidents.setExecutor(piCmd);
            incidents.setTabCompleter(piCmd);
        }
    }

    /**
     * Register GUI command and the ONE shared GUI listener (no pre-built
     * throwaway GUI instances anymore).
     */
    private void registerGUICommands() {
        this.guiManager = new GuiManager(plugin, config, monitorService, incidentAnalyzer,
                playerActivityTracker, listenerTimings);
        plugin.getServer().getPluginManager().registerEvents(guiManager, plugin);

        PluginCommand gui = plugin.getCommand("perfgui");
        if (gui != null) {
            gui.setExecutor(new PerfGUICommand(plugin, guiManager));
        }
    }

    public GuiManager guiManager() {
        return guiManager;
    }
}
