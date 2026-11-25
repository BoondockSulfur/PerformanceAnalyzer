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
    private dev.boondock.performanceanalyzer.monitor.ServerErrorMonitor errorMonitor;
    private dev.boondock.performanceanalyzer.metrics.MainThreadSampler mainThreadSampler;
    private dev.boondock.performanceanalyzer.metrics.NetworkSampler networkSampler;
    private dev.boondock.performanceanalyzer.analysis.ActivityContext activityContext;
    private volatile long lastMemoryTrendAlertMs;
    private ListenerTimings listenerTimings;
    private MonitorService monitorService;
    private IncidentAnalyzer incidentAnalyzer;

    // Exact-data analyzers
    private WorldStatsManager worldStatsManager;
    private EntityAnalyzer entityAnalyzer;
    private ChunkTracker chunkTracker;
    private PlayerActivityTracker playerActivityTracker;
    private boolean playerTrackingRegistered;

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
        applyPlayerTrackingSetting();

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

        java.util.Set<String> pluginNames = java.util.Arrays.stream(getServer().getPluginManager().getPlugins())
                .map(org.bukkit.plugin.Plugin::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.errorMonitor = new dev.boondock.performanceanalyzer.monitor.ServerErrorMonitor(
                getLogger().getName(), () -> pluginNames);
        this.errorMonitor.setSink(this::onServerError);
        this.incidentAnalyzer.setErrorMonitor(errorMonitor);

        this.mainThreadSampler = new dev.boondock.performanceanalyzer.metrics.MainThreadSampler(this);
        this.incidentAnalyzer.setMainThreadSampler(mainThreadSampler);
        this.incidentAnalyzer.setGcSampler(gcSampler);
        this.networkSampler = new dev.boondock.performanceanalyzer.metrics.NetworkSampler(this);
        this.networkSampler.setSpikeListener(this::onNetworkSpike);
        this.networkSampler.setPlayerJumpListener(this::onPlayerPingJump);
        this.activityContext = new dev.boondock.performanceanalyzer.analysis.ActivityContext(this);
        this.incidentAnalyzer.setActivityContext(activityContext);
        getServer().getPluginManager().registerEvents(activityContext, this);
        this.activityContext.start();
        // WorldSaveEvent marker for save-stall attribution
        getServer().getPluginManager().registerEvents(incidentAnalyzer, this);

        this.monitorService = new MonitorService(this, tickTimeSampler, gcSampler, incidentAnalyzer);
        this.monitorService.setLanguage(lang);
        this.monitorService.setStartupGraceSeconds(configAdapter.startupGraceSeconds());
        this.monitorService.setDatabase(database, configAdapter.logIntervalSeconds());
        this.monitorService.setNetworkSampler(networkSampler);
        this.networkSampler.setServerHealthy(() ->
                !monitorService.assessment().severity().atLeast(dev.boondock.performanceanalyzer.analysis.Severity.WARNING));
        this.tickTimeSampler.onSpike(configAdapter.spikeTickMs(), this::onTickSpike);
        this.mainThreadSampler.setSlowTickListener(tick -> {
            MonitorService monitor = this.monitorService;
            if (monitor != null && !monitor.inStartupGrace()) {
                incidentAnalyzer.logSlowTick(tick);
            }
        });

        this.tickTimeSampler.start();
        this.gcSampler.start();
        this.activityCounters.start();
        applyNeighborUpdateSetting();
        applyErrorDetectionSetting();
        applyMainThreadSamplingSetting();
        applyNetworkSamplingSetting();
        Scheduling.runAsyncRepeating(this, this::checkMemoryTrend, 60_000L, 60_000L);
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
        applyApiSetting();

        // bStats anonymous usage metrics (https://bstats.org/plugin/bukkit/PerformanceAnalyzer/32115)
        this.bstats = new org.bstats.bukkit.Metrics(this, 32115);

        Scheduling.runAsyncDelayed(this, this::checkForUpdates, 3_000L);

        getLogger().info("PerformanceAnalyzer v" + getDescription().getVersion()
                + " enabled (" + (Scheduling.FOLIA ? "Folia" : "Paper") + " mode).");
    }

    /**
     * Spike callback. The sampler invokes it from the tick-end event, i.e. on
     * the main thread, so the evaluation is handed to the async scheduler:
     * running it inline put the whole incident pipeline - and a wait for the
     * analyzer's lock - into the very moment the server was already lagging.
     */
    private void onTickSpike(double worstTickMs) {
        MonitorService monitor = this.monitorService;
        if (monitor != null) {
            Scheduling.runAsync(this, monitor::evaluateNow);
        }
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
        if (errorMonitor != null) errorMonitor.detach(getLogger());
        if (mainThreadSampler != null) mainThreadSampler.stop();
        if (networkSampler != null) networkSampler.stop();
        if (activityContext != null) activityContext.stop();
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
            // Only the reasons that point at the connection: a normal logout
            // and a kick say nothing about it.
            PlayerQuitEvent.QuitReason reason = event.getReason();
            if (database != null && (reason == PlayerQuitEvent.QuitReason.TIMED_OUT
                    || reason == PlayerQuitEvent.QuitReason.ERRONEOUS_STATE)) {
                database.logAsync("disconnect", player.getPing(), player.getName() + " " + reason.name());
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
        if (database != null) {
            database.scheduleFlush(configAdapter.logIntervalSeconds());
        }
        if (monitorService != null) {
            monitorService.setDatabase(database, configAdapter.logIntervalSeconds());
            monitorService.setStartupGraceSeconds(configAdapter.startupGraceSeconds());
        }
        if (tickTimeSampler != null && monitorService != null) {
            tickTimeSampler.onSpike(configAdapter.spikeTickMs(), this::onTickSpike);
        }
        if (chunkTracker != null) {
            chunkTracker.refreshThresholds();
        }
        applyPlayerTrackingSetting();
        applyNeighborUpdateSetting();
        applyErrorDetectionSetting();
        applyMainThreadSamplingSetting();
        applyNetworkSamplingSetting();
        applyListenerTimingsSetting();
        applyPacketAnalysisSetting();
        applyApiSetting();
        getLogger().info("Plugin configuration reloaded successfully.");
    }

    /** Registers or unregisters the player activity listener to match the config. */
    private void applyPlayerTrackingSetting() {
        if (playerActivityTracker == null) {
            return;
        }
        boolean wanted = configAdapter.lagAnalysisPlayerTracking();
        if (wanted && !playerTrackingRegistered) {
            getServer().getPluginManager().registerEvents(playerActivityTracker, this);
            playerTrackingRegistered = true;
        } else if (!wanted && playerTrackingRegistered) {
            org.bukkit.event.HandlerList.unregisterAll(playerActivityTracker);
            playerActivityTracker.clearActivity();
            playerTrackingRegistered = false;
        }
    }

    /** Counts block/neighbor updates only while lag_analysis.neighbor_updates is on. */
    private void applyNeighborUpdateSetting() {
        if (activityCounters != null) {
            activityCounters.setNeighborUpdateTracking(configAdapter.lagAnalysisNeighborUpdates());
        }
    }

    /** Attaches or detaches the server-log error reader to match the config. */
    private void applyErrorDetectionSetting() {
        if (errorMonitor == null) {
            return;
        }
        if (configAdapter.lagAnalysisErrorDetection()) {
            errorMonitor.attach(getLogger());
        } else {
            errorMonitor.detach(getLogger());
        }
    }

    /** Starts, reconfigures or stops the main-thread sampler to match the config. */
    private void applyMainThreadSamplingSetting() {
        if (mainThreadSampler == null) {
            return;
        }
        mainThreadSampler.configure(configAdapter.samplingIntervalMs(), configAdapter.spikeTickMs());
        if (configAdapter.lagAnalysisMainThreadSampling()) {
            mainThreadSampler.start();
        } else {
            mainThreadSampler.stop();
        }
    }

    private void applyNetworkSamplingSetting() {
        if (networkSampler == null) {
            return;
        }
        networkSampler.stop();
        if (configAdapter.lagAnalysisNetworkSampling()) {
            networkSampler.start();
        }
    }

    /** One player's connection stumbled while the server was healthy. */
    private void onPlayerPingJump(dev.boondock.performanceanalyzer.metrics.NetworkSampler.PlayerJump jump) {
        if (database != null) {
            database.logAsync("ping_jump", jump.pingMs(), jump.player() + " usual=" + Math.round(jump.usualMs()) + "ms");
        }
        if (activityContext != null) {
            activityContext.recordPingJump(jump.player(), jump.pingMs(), jump.usualMs());
        }
    }

    private void onNetworkSpike(dev.boondock.performanceanalyzer.metrics.NetworkSampler.NetworkSpike spike) {
        if (database != null) {
            database.logAsync("network_spike", spike.avgNowMs(),
                    spike.affected() + "/" + spike.players() + " players, before " + Math.round(spike.avgBeforeMs()) + " ms");
        }
        if (alertManager != null && configAdapter.networkSpikeAlerts()) {
            alertManager.sendAlert(AlertManager.AlertType.NETWORK, dev.boondock.performanceanalyzer.analysis.Severity.WARNING,
                    lang.format("alert.network_spike", spike.affected(), spike.players(),
                            spike.avgBeforeMs(), spike.avgNowMs()),
                    spike.avgNowMs());
        }
    }

    /**
     * Alerts once every two hours while the memory floor is on course to
     * fill the heap within six hours. Rarer than the other alerts on
     * purpose: the trend changes over hours, not minutes.
     */
    private void checkMemoryTrend() {
        if (gcSampler == null || alertManager == null || !configAdapter.memoryTrendAlerts()) {
            return;
        }
        var trend = gcSampler.memoryTrend();
        long now = System.currentTimeMillis();
        if (!trend.leakSuspected() || trend.hoursUntilFull() < 0 || trend.hoursUntilFull() >= 6
                || now - lastMemoryTrendAlertMs < 2 * 3_600_000L) {
            return;
        }
        lastMemoryTrendAlertMs = now;
        alertManager.sendAlert(AlertManager.AlertType.MEMORY, dev.boondock.performanceanalyzer.analysis.Severity.WARNING,
                lang.format("alert.memory_trend", trend.slopeMbPerHour(), trend.floorMb(), trend.maxMb(),
                        trend.hoursUntilFull()),
                trend.slopeMbPerHour());
    }

    /**
     * Called from inside the logging call, at most once a minute per error
     * source: it only queues work and never logs itself.
     */
    private void onServerError(dev.boondock.performanceanalyzer.monitor.ServerErrorMonitor.ErrorSummary error) {
        String kind = error.kind().name().toLowerCase(java.util.Locale.ROOT);
        if (database != null) {
            database.logAsync("server_error", error.recentCount(),
                    kind + " " + error.source() + ": " + error.lastMessage());
        }
        if (alertManager != null && configAdapter.serverErrorAlerts() && isEnabled()) {
            String message = lang.format("alert.server_error",
                    lang.format("finding.error.title." + kind, error.source()),
                    error.recentCount(), error.lastMessage());
            Scheduling.runAsync(this, () -> alertManager.sendAlert(
                    AlertManager.AlertType.SERVER_ERROR, error.severity(), message, error.recentCount()));
        }
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

    /**
     * Starts, restarts or stops the REST API to match the config.
     *
     * <p>{@link #metricsApi} only ever holds a server that is actually
     * listening. It used to be assigned before {@code start()} was known to
     * succeed, so after one refused start (empty key, port taken) every later
     * reload saw "already there" and did nothing until a server restart.
     * A running server is restarted when bind, port or key changed - it reads
     * them once, at start.
     */
    private void applyApiSetting() {
        boolean wanted = configAdapter.apiEnabled();
        if (wanted && metricsApi != null && metricsApi.runsWith(
                configAdapter.apiBind(), configAdapter.apiPort(), configAdapter.apiKey())) {
            return;
        }
        if (metricsApi != null) {
            metricsApi.stop();
            metricsApi = null;
        }
        if (!wanted) {
            return;
        }
        MetricsAPI api = new MetricsAPI(this, configAdapter, monitorService,
                incidentAnalyzer, activityCounters, listenerTimings,
                worldStatsManager, database);
        if (api.start()) {
            this.metricsApi = api;
        }
    }

    /* Accessors */

    public DatabaseManager database() { return database; }
    public LanguageManager lang() { return lang; }
    public SparkHook spark() { return sparkHook; }
    public MonitorService monitor() { return monitorService; }
    public IncidentAnalyzer incidents() { return incidentAnalyzer; }
    public ActivityCounters activity() { return activityCounters; }
    public dev.boondock.performanceanalyzer.monitor.ServerErrorMonitor errors() { return errorMonitor; }
    public dev.boondock.performanceanalyzer.metrics.MainThreadSampler mainThreadSampler() { return mainThreadSampler; }
    public dev.boondock.performanceanalyzer.metrics.NetworkSampler network() { return networkSampler; }
    public dev.boondock.performanceanalyzer.metrics.GcSampler gc() { return gcSampler; }
    public ListenerTimings timings() { return listenerTimings; }
    public MetricsAPI api() { return metricsApi; }
    public AlertPreferenceManager getAlertPreferenceManager() { return alertPreferenceManager; }
    public PlayerActivityTracker getPlayerActivityTracker() { return playerActivityTracker; }
}
