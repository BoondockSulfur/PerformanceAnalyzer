package dev.boondock.performanceanalyzer;

import dev.boondock.performanceanalyzer.alerts.AlertManager;
import dev.boondock.performanceanalyzer.alerts.AlertPreferenceManager;
import dev.boondock.performanceanalyzer.analysis.ActivityCounters;
import dev.boondock.performanceanalyzer.analysis.ChunkTracker;
import dev.boondock.performanceanalyzer.analysis.EntityAnalyzer;
import dev.boondock.performanceanalyzer.analysis.IncidentAnalyzer;
import dev.boondock.performanceanalyzer.analysis.PlayerActivityTracker;
import dev.boondock.performanceanalyzer.analysis.WorldStatsManager;
import dev.boondock.performanceanalyzer.api.MetricsAPI;
import dev.boondock.performanceanalyzer.calibrate.CalibrationEngine;
import dev.boondock.performanceanalyzer.calibrate.CalibrationSampler;
import dev.boondock.performanceanalyzer.commands.CommandRegistry;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.integration.ProtocolLibHook;
import dev.boondock.performanceanalyzer.integration.SparkHook;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.GcSampler;
import dev.boondock.performanceanalyzer.metrics.TickTimeSampler;
import dev.boondock.performanceanalyzer.monitor.MonitorService;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import dev.boondock.performanceanalyzer.timing.ListenerTimings;
import dev.boondock.performanceanalyzer.util.Constants;
import dev.boondock.performanceanalyzer.util.UpdateChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
    private AlertPreferenceManager alertPreferenceManager;

    // v3.1.0 measurement core
    private TickTimeSampler tickTimeSampler;
    private GcSampler gcSampler;
    private ActivityCounters activityCounters;
    private ListenerTimings listenerTimings;
    private MonitorService monitorService;
    private IncidentAnalyzer incidentAnalyzer;

    // Exact-data analyzers
    private WorldStatsManager worldStatsManager;
    private EntityAnalyzer entityAnalyzer;
    private ChunkTracker chunkTracker;
    private PlayerActivityTracker playerActivityTracker;

    // Integrations
    private ProtocolLibHook protocolLibHook;
    private SparkHook sparkHook;

    private CommandRegistry commandRegistry;
    private CalibrationSampler calibrationSampler;
    private CalibrationEngine calibrationEngine;

    /** Newer version found by the update check; null while up to date. */
    private volatile String availableVersion;
    private MetricsAPI metricsApi;
    private org.bstats.bukkit.Metrics bstats;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configAdapter = new PluginConfig(this);
        this.lang = new LanguageManager(this, configAdapter.language());

        this.database = new DatabaseManager(this, configAdapter);
        this.database.init();

        this.alertManager = new AlertManager(this, configAdapter, lang);
        this.alertPreferenceManager = new AlertPreferenceManager(this, configAdapter);
        this.alertManager.setPreferenceManager(alertPreferenceManager);

        if (configAdapter.discordEnabled()) {
            String webhookUrl = configAdapter.discordWebhookUrl();
            if (webhookUrl == null || webhookUrl.isEmpty()) {
                getLogger().warning("Discord webhook enabled but URL is empty!");
            }
        }

        // Integrations (Spark supplies CPU numbers; ProtocolLib packet rates).
        // The ProtocolLib presence check must happen BEFORE the first reference
        // to ProtocolLibHook: loading that class resolves ProtocolLib types and
        // throws NoClassDefFoundError when the plugin is absent.
        this.sparkHook = SparkHook.tryHook(this);
        if (configAdapter.packetAnalysisEnabled()
                && getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            this.protocolLibHook = ProtocolLibHook.tryHook(this, configAdapter);
            if (this.protocolLibHook != null) {
                this.protocolLibHook.setDatabase(database);
                this.protocolLibHook.setAlertManager(alertManager);
            }
        }

        // Exact-data analyzers
        this.worldStatsManager = new WorldStatsManager(this);
        this.entityAnalyzer = new EntityAnalyzer(this, configAdapter);
        this.chunkTracker = new ChunkTracker(this, configAdapter);
        getServer().getPluginManager().registerEvents(chunkTracker, this);

        this.playerActivityTracker = new PlayerActivityTracker(this, configAdapter);
        if (configAdapter.lagAnalysisPlayerTracking()) {
            getServer().getPluginManager().registerEvents(playerActivityTracker, this);
        }

        // v3.1.0 measurement core
        this.tickTimeSampler = new TickTimeSampler(this);
        this.gcSampler = new GcSampler(this);
        this.activityCounters = new ActivityCounters(this);

        // Always constructed, started only when enabled: every consumer
        // (CommandRegistry, GuiManager, MetricsAPI) takes the reference once at
        // construction, so a null here could never become non-null again and
        // toggling the setting would have needed a full server restart.
        // ListenerTimings.isActive() carries the on/off state instead.
        this.listenerTimings = new ListenerTimings(this);

        this.incidentAnalyzer = new IncidentAnalyzer(this, lang, activityCounters,
                chunkTracker, entityAnalyzer, database, configAdapter);
        this.incidentAnalyzer.setListenerTimings(listenerTimings);
        this.incidentAnalyzer.setTickSampler(tickTimeSampler);
        this.incidentAnalyzer.setListener(alertManager);
        // WorldSaveEvent marker for save-stall attribution
        getServer().getPluginManager().registerEvents(incidentAnalyzer, this);

        this.monitorService = new MonitorService(this, tickTimeSampler, gcSampler, incidentAnalyzer);
        this.monitorService.setLanguage(lang);
        this.monitorService.setStartupGraceSeconds(configAdapter.startupGraceSeconds());
        this.monitorService.setDatabase(database, configAdapter.logIntervalSeconds());
        this.tickTimeSampler.onSpike(configAdapter.spikeTickMs(),
                worstTickMs -> monitorService.evaluateNow());

        this.tickTimeSampler.start();
        this.gcSampler.start();
        this.activityCounters.start();
        if (configAdapter.lagAnalysisPluginAnalysis()) {
            this.listenerTimings.start();
        }
        this.monitorService.start();

        // Trend snapshots every 5 minutes (Paper only: iterating all worlds'
        // chunks from one thread is illegal on Folia).
        if (!Scheduling.FOLIA) {
            Scheduling.runGlobalRepeating(this, () -> worldStatsManager.recordAllSnapshots(),
                    20L * 60, 20L * 300);
        }

        getServer().getPluginManager().registerEvents(this, this);

        // Calibration: the sampler runs from startup so /perfcalibrate has a
        // window to work with whenever an admin gets around to running it.
        this.calibrationSampler = new CalibrationSampler(this);
        this.calibrationSampler.setPacketSource(protocolLibHook);
        this.calibrationSampler.start();
        this.calibrationEngine = new CalibrationEngine(this, configAdapter, lang, monitorService,
                incidentAnalyzer, database, calibrationSampler);

        this.commandRegistry = new CommandRegistry(this, configAdapter, lang, monitorService,
                database, worldStatsManager, entityAnalyzer, chunkTracker, incidentAnalyzer,
                playerActivityTracker, listenerTimings, calibrationEngine);
        commandRegistry.registerAll();

        // REST API (off by default; binds to 127.0.0.1 unless configured otherwise)
        if (configAdapter.apiEnabled()) {
            this.metricsApi = new MetricsAPI(this, configAdapter, monitorService,
                    incidentAnalyzer, activityCounters, listenerTimings,
                    worldStatsManager, database);
            this.metricsApi.start();
        }

        // bStats anonymous usage metrics (https://bstats.org/plugin/bukkit/PerformanceAnalyzer/32115)
        this.bstats = new org.bstats.bukkit.Metrics(this, 32115);

        Scheduling.runAsyncDelayed(this, this::checkForUpdates, 3_000L);

        getLogger().info("PerformanceAnalyzer v" + getDescription().getVersion()
                + " enabled (" + (Scheduling.FOLIA ? "Folia" : "Paper") + " mode).");
    }

    private void checkForUpdates() {
        UpdateChecker checker = new UpdateChecker(this);
        checker.checkForUpdates().thenAccept(result -> {
            if (result.isUpdateAvailable()) {
                availableVersion = result.getLatestVersion();
                getLogger().warning("Update available: " + getDescription().getVersion()
                        + " -> " + result.getLatestVersion()
                        + "  |  Modrinth: " + Constants.URL_MODRINTH
                        + "  |  CurseForge: " + Constants.URL_CURSEFORGE);
            } else {
                getLogger().info("[UpdateChecker] You are running the latest version ("
                        + result.getLatestVersion() + ")");
            }
        }).exceptionally(ex -> null);
    }

    /**
     * Tells operators about an update once, when they join.
     *
     * <p>The console line above is easy to miss on a server that prints
     * hundreds of startup lines, and the two download pages are only clickable
     * in chat. Operators only: everyone else can neither install nor act on it.
     */
    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        String latest = availableVersion;
        if (latest == null || !event.getPlayer().isOp()) {
            return;
        }

        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        Component message = legacy.deserialize(lang.get("update.available",
                        "%current%", getDescription().getVersion(),
                        "%latest%", latest))
                .append(Component.space())
                .append(downloadLink("update.link_modrinth", Constants.URL_MODRINTH, legacy))
                .append(Component.space())
                .append(downloadLink("update.link_curseforge", Constants.URL_CURSEFORGE, legacy));

        event.getPlayer().sendMessage(message);
    }

    private Component downloadLink(String labelKey, String url, LegacyComponentSerializer legacy) {
        return legacy.deserialize(lang.get(labelKey))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(
                        legacy.deserialize(lang.get("update.link_hover", "%url%", url))));
    }

    @Override
    public void onDisable() {
        if (bstats != null) bstats.shutdown();
        if (metricsApi != null) metricsApi.stop();
        if (calibrationSampler != null) calibrationSampler.stop();
        if (monitorService != null) monitorService.stop();
        if (listenerTimings != null) listenerTimings.stop();
        if (activityCounters != null) activityCounters.stop();
        if (gcSampler != null) gcSampler.stop();
        if (tickTimeSampler != null) tickTimeSampler.stop();
        if (protocolLibHook != null) protocolLibHook.shutdown();
        if (database != null) database.shutdown();
        if (configAdapter != null) configAdapter.saveSyncOnShutdown();
        Scheduling.cancelAll(this);
        getLogger().info("PerformanceAnalyzer v" + getDescription().getVersion() + " disabled.");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            UUID playerId = player.getUniqueId();
            if (playerActivityTracker != null) {
                playerActivityTracker.cleanupPlayer(playerId);
            }
            if (alertPreferenceManager != null) {
                alertPreferenceManager.cleanup(playerId);
            }
        }
    }

    /**
     * Reload plugin configuration without full restart. Does not restart the
     * measurement core — the v2.x reload leaked one metric/alert task per
     * invocation — but it does apply every setting the config GUI offers,
     * which is what "takes effect after restart or /perfreload" promises.
     */
    public void reloadPlugin() {
        getLogger().info("Reloading plugin configuration...");
        reloadConfig();
        configAdapter.reload();
        lang.setLanguage(configAdapter.language());
        if (monitorService != null) {
            monitorService.setDatabase(database, configAdapter.logIntervalSeconds());
            monitorService.setStartupGraceSeconds(configAdapter.startupGraceSeconds());
        }
        if (tickTimeSampler != null && monitorService != null) {
            tickTimeSampler.onSpike(configAdapter.spikeTickMs(),
                    worstTickMs -> monitorService.evaluateNow());
        }
        if (chunkTracker != null) {
            chunkTracker.refreshThresholds();
        }
        applyListenerTimingsSetting();
        applyPacketAnalysisSetting();
        applyApiSetting();
        getLogger().info("Plugin configuration reloaded successfully.");
    }

    /** Starts or stops listener timing injection to match the config. */
    private void applyListenerTimingsSetting() {
        if (listenerTimings == null) {
            return;
        }
        boolean wanted = configAdapter.lagAnalysisPluginAnalysis();
        if (wanted && !listenerTimings.isActive()) {
            listenerTimings.start();
            getLogger().info("[Reload] Listener timings enabled.");
        } else if (!wanted && listenerTimings.isActive()) {
            listenerTimings.stop();
            getLogger().info("[Reload] Listener timings disabled.");
        }
    }

    /**
     * Hooks or unhooks ProtocolLib. The presence check has to stay in front of
     * every reference to {@link ProtocolLibHook}: loading that class resolves
     * ProtocolLib types and throws NoClassDefFoundError when it is absent.
     */
    private void applyPacketAnalysisSetting() {
        boolean wanted = configAdapter.packetAnalysisEnabled()
                && getServer().getPluginManager().getPlugin("ProtocolLib") != null;
        if (wanted && protocolLibHook == null) {
            this.protocolLibHook = ProtocolLibHook.tryHook(this, configAdapter);
            if (this.protocolLibHook != null) {
                this.protocolLibHook.setDatabase(database);
                this.protocolLibHook.setAlertManager(alertManager);
                if (calibrationSampler != null) {
                    calibrationSampler.setPacketSource(protocolLibHook);
                }
                getLogger().info("[Reload] Packet analysis enabled.");
            }
        } else if (!wanted && protocolLibHook != null) {
            protocolLibHook.shutdown();
            protocolLibHook = null;
            if (calibrationSampler != null) {
                calibrationSampler.setPacketSource(null);
            }
            getLogger().info("[Reload] Packet analysis disabled.");
        }
    }

    /** Starts or stops the REST API to match the config. */
    private void applyApiSetting() {
        boolean wanted = configAdapter.apiEnabled();
        if (wanted && metricsApi == null) {
            this.metricsApi = new MetricsAPI(this, configAdapter, monitorService,
                    incidentAnalyzer, activityCounters, listenerTimings,
                    worldStatsManager, database);
            this.metricsApi.start();
        } else if (!wanted && metricsApi != null) {
            metricsApi.stop();
            metricsApi = null;
            getLogger().info("[Reload] REST API stopped.");
        }
    }

    /* Accessors */

    public DatabaseManager database() { return database; }
    public LanguageManager lang() { return lang; }
    public SparkHook spark() { return sparkHook; }
    public MonitorService monitor() { return monitorService; }
    public IncidentAnalyzer incidents() { return incidentAnalyzer; }
    public ActivityCounters activity() { return activityCounters; }
    public ListenerTimings timings() { return listenerTimings; }
    public MetricsAPI api() { return metricsApi; }
    public AlertPreferenceManager getAlertPreferenceManager() { return alertPreferenceManager; }
    public PlayerActivityTracker getPlayerActivityTracker() { return playerActivityTracker; }
}
