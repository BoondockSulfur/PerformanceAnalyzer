package dev.boondock.performanceanalyzer;

import dev.boondock.performanceanalyzer.alerts.AlertManager;
import dev.boondock.performanceanalyzer.alerts.AlertPreferenceManager;
import dev.boondock.performanceanalyzer.analysis.ChunkTracker;
import dev.boondock.performanceanalyzer.analysis.EntityAnalyzer;
import dev.boondock.performanceanalyzer.analysis.PerformanceDropAnalyzer;
import dev.boondock.performanceanalyzer.analysis.PlayerActivityTracker;
import dev.boondock.performanceanalyzer.analysis.PluginTimingsAnalyzer;
import dev.boondock.performanceanalyzer.analysis.WorldStatsManager;
import dev.boondock.performanceanalyzer.anticheat.MovementAlertManager;
import dev.boondock.performanceanalyzer.anticheat.MovementChecker;
import dev.boondock.performanceanalyzer.anticheat.ViolationTracker;
import dev.boondock.performanceanalyzer.anticheat.XRayAlertManager;
import dev.boondock.performanceanalyzer.anticheat.XRayDetector;
import dev.boondock.performanceanalyzer.commands.CommandRegistry;
import dev.boondock.performanceanalyzer.commands.ReloadCommand;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.gui.AntiCheatGUI;
import dev.boondock.performanceanalyzer.gui.ConfigGUI;
import dev.boondock.performanceanalyzer.gui.LagAnalysisGUI;
import dev.boondock.performanceanalyzer.gui.PerformanceDropsGUI;
import dev.boondock.performanceanalyzer.gui.PerformanceGUI;
import dev.boondock.performanceanalyzer.integration.LuckPermsHook;
import dev.boondock.performanceanalyzer.integration.ProtocolLibHook;
import dev.boondock.performanceanalyzer.integration.SparkHook;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.MemorySampler;
import dev.boondock.performanceanalyzer.metrics.TickSampler;
import dev.boondock.performanceanalyzer.util.UpdateChecker;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class PerformanceAnalyzer extends JavaPlugin implements Listener {

    private PluginConfig configAdapter;
    private LanguageManager lang;
    private DatabaseManager database;
    private AlertManager alertManager;
    private TickSampler tickSampler;
    private MemorySampler memorySampler;
    private ProtocolLibHook protocolLibHook;
    private SparkHook sparkHook;
    private LuckPermsHook luckPermsHook;
    private ViolationTracker violationTracker;
    private MovementChecker movementChecker;
    private MovementAlertManager movementAlertManager;
    private XRayDetector xrayDetector;
    private XRayAlertManager xrayAlertManager;
    private WorldStatsManager worldStatsManager;
    private EntityAnalyzer entityAnalyzer;
    private ChunkTracker chunkTracker;
    private PerformanceDropAnalyzer dropAnalyzer;
    private PlayerActivityTracker playerActivityTracker;
    private PluginTimingsAnalyzer pluginTimingsAnalyzer;
    private AlertPreferenceManager alertPreferenceManager;
    private CommandRegistry commandRegistry;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configAdapter = new PluginConfig(this);

        // Language System
        this.lang = new LanguageManager(this, configAdapter.language());

        // DB
        this.database = new DatabaseManager(this, configAdapter);
        this.database.init();

        // Alert System
        this.alertManager = new AlertManager(this, configAdapter);

        // Alert Preferences (Silent Mode / Streamer Mode)
        this.alertPreferenceManager = new AlertPreferenceManager(this, configAdapter);
        this.alertManager.setPreferenceManager(alertPreferenceManager);

        // Discord Webhook Status
        if (configAdapter.discordEnabled()) {
            String webhookUrl = configAdapter.discordWebhookUrl();
            if (webhookUrl != null && !webhookUrl.isEmpty()) {
                getLogger().info("Discord Webhook: AKTIV (URL konfiguriert)");
            } else {
                getLogger().warning("Discord Webhook: aktiviert aber URL ist leer!");
            }
        } else {
            getLogger().info("Discord Webhook: DEAKTIVIERT (discord.enabled: false)");
        }

        // Optional Hooks (initialize before Sampler so Spark data can be used)
        if (configAdapter.packetAnalysisEnabled()) {
            this.protocolLibHook = ProtocolLibHook.tryHook(this, configAdapter);
            if (this.protocolLibHook != null) {
                this.protocolLibHook.setDatabase(database);
            }
        }
        this.sparkHook = SparkHook.tryHook(this);

        // Performance Drop Analyzer
        this.dropAnalyzer = new PerformanceDropAnalyzer(this, configAdapter, database);

        // Lag Source Analyzers (NEW in v2.0.0)
        this.playerActivityTracker = new PlayerActivityTracker(this, configAdapter);
        this.pluginTimingsAnalyzer = new PluginTimingsAnalyzer(this);

        // Only register if enabled
        if (configAdapter.lagAnalysisPlayerTracking()) {
            getServer().getPluginManager().registerEvents(playerActivityTracker, this);
            getLogger().info("Lag source analysis enabled: Player tracking & plugin analysis");
        } else {
            getLogger().info("Lag source analysis: Only plugin analysis enabled (player tracking disabled)");
        }

        // Connect analyzers to drop analyzer
        this.dropAnalyzer.setPlayerActivityTracker(playerActivityTracker);
        this.dropAnalyzer.setPluginTimingsAnalyzer(pluginTimingsAnalyzer);

        // Sampler
        this.tickSampler = new TickSampler(this, configAdapter);
        this.memorySampler = new MemorySampler();
        this.tickSampler.setAlertManager(alertManager);
        this.tickSampler.setSparkHook(sparkHook); // Use Spark for accurate TPS/MSPT
        this.tickSampler.setDropAnalyzer(dropAnalyzer); // Enable performance drop analysis
        this.tickSampler.setDatabase(database, configAdapter.logIntervalSeconds());
        this.tickSampler.start();

        // LuckPerms Integration
        this.luckPermsHook = LuckPermsHook.tryHook(this);

        // Analysis Tools
        this.worldStatsManager = new WorldStatsManager(this);
        this.entityAnalyzer = new EntityAnalyzer(this, configAdapter);
        this.chunkTracker = new ChunkTracker(this);
        getServer().getPluginManager().registerEvents(chunkTracker, this);
        getLogger().info("Analysis tools enabled: WorldStats, EntityStats, ChunkStats");

        // AntiCheat (if enabled)
        if (configAdapter.debugMode()) {
            getLogger().info("[Debug] Config anticheat_enabled = " + configAdapter.anticheatEnabled());
        }
        if (configAdapter.anticheatEnabled()) {
            initializeAntiCheat();
        } else {
            getLogger().info("AntiCheat module is DISABLED (anticheat_enabled: false in config.yml)");
        }

        // Event listener for cleanup
        getServer().getPluginManager().registerEvents(this, this);

        // Commands - Initialize CommandRegistry
        this.commandRegistry = new CommandRegistry(this, configAdapter, lang, tickSampler, memorySampler,
                database, worldStatsManager, entityAnalyzer, chunkTracker, dropAnalyzer,
                playerActivityTracker, pluginTimingsAnalyzer);

        // Register AntiCheat components if enabled
        if (xrayAlertManager != null && xrayDetector != null && movementAlertManager != null) {
            commandRegistry.setAntiCheatComponents(xrayAlertManager, xrayDetector, movementAlertManager);
        }

        // Register all commands
        commandRegistry.registerAll();

        // Update Checker (run async after 3 seconds delay)
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::checkForUpdates, 60L);

        getLogger().info("PerformanceAnalyzer v" + getDescription().getVersion() + " enabled.");
    }

    /**
     * Check for plugin updates on Modrinth.
     * Runs asynchronously and logs results to console.
     */
    private void checkForUpdates() {
        UpdateChecker checker = new UpdateChecker(this);
        checker.checkForUpdates().thenAccept(result -> {
            if (result.isUpdateAvailable()) {
                // Display update notification box
                getLogger().warning("╔════════════════════════════════════════════════════╗");
                getLogger().warning("║  UPDATE AVAILABLE!                                 ║");
                getLogger().warning("║                                                    ║");
                getLogger().warning("║  Current Version: " + String.format("%-32s", getDescription().getVersion()) + " ║");
                getLogger().warning("║  Latest Version:  " + String.format("%-32s", result.getLatestVersion()) + " ║");
                getLogger().warning("║                                                    ║");
                getLogger().warning("║  Download: https://modrinth.com/plugin/performanceanalyzer ║");
                getLogger().warning("╚════════════════════════════════════════════════════╝");
            } else {
                getLogger().info("[UpdateChecker] You are running the latest version (" + result.getLatestVersion() + ")");
            }
        }).exceptionally(ex -> {
            // Errors are already logged in UpdateChecker
            return null;
        });
    }

    @Override
    public void onDisable() {
        if (protocolLibHook != null) protocolLibHook.shutdown();
        if (tickSampler != null) tickSampler.stop();
        if (database != null) database.shutdown();
        if (configAdapter != null) configAdapter.saveSyncOnShutdown();
        getLogger().info("PerformanceAnalyzer v" + getDescription().getVersion() + " disabled.");
    }


    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Null-safe cleanup on player disconnect
        Player player = event.getPlayer();
        if (player != null) {
            UUID playerId = player.getUniqueId();
            if (movementChecker != null) {
                movementChecker.cleanup(playerId);
            }
            if (xrayDetector != null) {
                xrayDetector.cleanup(playerId);
            }
            if (playerActivityTracker != null) {
                playerActivityTracker.cleanupPlayer(playerId);
            }
            if (alertPreferenceManager != null) {
                alertPreferenceManager.cleanup(playerId);
            }
        }
    }

    /**
     * Reload plugin configuration without full restart.
     */
    public void reloadPlugin() {
        getLogger().info("Reloading plugin configuration...");

        // Reload config
        reloadConfig();
        configAdapter.reload();

        // Reload language
        lang.setLanguage(configAdapter.language());

        // Update tickSampler with new config values
        if (tickSampler != null) {
            tickSampler.setDatabase(database, configAdapter.logIntervalSeconds());
        }

        // Handle AntiCheat state changes
        boolean anticheatShouldBeEnabled = configAdapter.anticheatEnabled();
        boolean anticheatCurrentlyEnabled = (xrayDetector != null);

        if (anticheatShouldBeEnabled && !anticheatCurrentlyEnabled) {
            // AntiCheat was disabled, now should be enabled -> initialize
            initializeAntiCheat();
            reregisterAntiCheatCommands();
            getLogger().info("AntiCheat module has been enabled!");
        } else if (!anticheatShouldBeEnabled && anticheatCurrentlyEnabled) {
            // AntiCheat was enabled, now should be disabled
            // Note: Event listeners cannot be easily unregistered, they will just not process events
            getLogger().info("AntiCheat module has been disabled. (Complete deactivation requires server restart)");
        } else if (anticheatShouldBeEnabled && anticheatCurrentlyEnabled) {
            // AntiCheat stays enabled -> just reload settings
            xrayDetector.reloadExcludedOres();
            getLogger().info("AntiCheat settings have been reloaded.");
        }

        getLogger().info("Plugin configuration reloaded successfully.");
    }

    /**
     * Initialize AntiCheat components (called on enable or when enabled via reload).
     */
    private void initializeAntiCheat() {
        this.violationTracker = new ViolationTracker();
        this.xrayAlertManager = new XRayAlertManager(this, configAdapter);
        this.movementAlertManager = new MovementAlertManager(this, configAdapter);
        this.movementChecker = new MovementChecker(this, configAdapter, database, violationTracker);
        this.movementChecker.setAlertManager(movementAlertManager);
        this.xrayDetector = new XRayDetector(this, configAdapter, database);
        this.xrayDetector.setAlertManager(xrayAlertManager);

        // Set alert preference manager for silent mode
        if (alertPreferenceManager != null) {
            xrayAlertManager.setPreferenceManager(alertPreferenceManager);
            movementAlertManager.setPreferenceManager(alertPreferenceManager);
        }

        // Set LuckPerms hooks
        if (luckPermsHook != null) {
            movementChecker.setLuckPerms(luckPermsHook);
            xrayDetector.setLuckPerms(luckPermsHook);
            getLogger().info("AntiCheat: LuckPerms integration enabled.");
        }

        // Register event listeners
        getServer().getPluginManager().registerEvents(movementChecker, this);
        getServer().getPluginManager().registerEvents(xrayDetector, this);

        getLogger().info("AntiCheat module enabled:");
        getLogger().info("  - Movement-Checks: ACTIVE (Speed/Fly/Teleport)");
        getLogger().info("  - XRay-Detection: " + (configAdapter.xrayDetectionEnabled() ? "ACTIVE" : "DISABLED"));
        getLogger().info("  - XRay-Timewindow: " + configAdapter.xrayTimewindowSeconds() + "s (Per-ore thresholds in config.yml)");
    }

    /**
     * Re-register AntiCheat commands with the new components.
     * Delegates to CommandRegistry.
     */
    private void reregisterAntiCheatCommands() {
        if (commandRegistry != null && xrayAlertManager != null && xrayDetector != null && movementAlertManager != null) {
            commandRegistry.setAntiCheatComponents(xrayAlertManager, xrayDetector, movementAlertManager);
            commandRegistry.reregisterAntiCheatCommands();
        }
    }

    public DatabaseManager database() { return database; }
    public TickSampler tickSampler() { return tickSampler; }
    public MemorySampler memorySampler() { return memorySampler; }
    public LanguageManager lang() { return lang; }
    public SparkHook spark() { return sparkHook; }

    public AlertPreferenceManager getAlertPreferenceManager() { return alertPreferenceManager; }

    // Getters for GUI access (v2.0.0+)
    public PlayerActivityTracker getPlayerActivityTracker() { return playerActivityTracker; }
    public PluginTimingsAnalyzer getPluginTimingsAnalyzer() { return pluginTimingsAnalyzer; }
    public PerformanceDropAnalyzer getDropAnalyzer() { return dropAnalyzer; }
}
