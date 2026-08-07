package dev.boondock.performanceanalyzer.config;

import dev.boondock.performanceanalyzer.util.Constants;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class PluginConfig {

    private final JavaPlugin plugin;
    private FileConfiguration cfg;
    private final AsyncConfigSaver asyncSaver;
    /** True when the in-memory config differs from disk (pending save). */
    private volatile boolean dirty;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.getConfig();
        this.asyncSaver = new AsyncConfigSaver(plugin);
        migrateConfig();
        validateConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        this.cfg = plugin.getConfig();
        migrateConfig();
        validateConfig();
    }

    /**
     * Validate config values to prevent runtime errors.
     * Warns about invalid values and uses safe defaults.
     */
    private void validateConfig() {
        boolean hasErrors = false;

        // Validate log interval (must be > 0)
        int logInterval = cfg.getInt("performance.log_interval_seconds", 60);
        if (logInterval <= 0) {
            plugin.getLogger().warning("[Config] Invalid performance.log_interval_seconds: " + logInterval + " (must be > 0). Using default: 60");
            cfg.set("performance.log_interval_seconds", 60);
            hasErrors = true;
        }

        // Validate chunk analysis timeout (must be > 0)
        int timeout = cfg.getInt("lag_analysis.chunk_analysis_timeout_ms", 5000);
        if (timeout <= 0) {
            plugin.getLogger().warning("[Config] Invalid lag_analysis.chunk_analysis_timeout_ms: " + timeout + " (must be > 0). Using default: 5000");
            cfg.set("lag_analysis.chunk_analysis_timeout_ms", 5000);
            hasErrors = true;
        }

        if (hasErrors) {
            requestSave();
            plugin.getLogger().warning("[Config] Config validation found errors. Fixed values saved asynchronously.");
        }
    }

    /**
     * Migrate config - add missing keys from newer versions.
     * Uses version tracking to avoid unnecessary checks.
     */
    private void migrateConfig() {
        // Obsolete-key cleanup runs on EVERY start, independent of
        // config_version: merging only ever adds keys, and configs stamped
        // with a matching version by older/experimental builds would keep
        // dead sections forever (seen with the anticheat block surviving a
        // 6->7 migration because the removal step was gated on "< 6").
        boolean cleaned = removeObsoleteKeys();

        int configVersion = cfg.getInt("config_version", 0);

        // No migration needed if config is up to date
        if (configVersion >= Constants.CONFIG_VERSION) {
            if (cleaned) {
                requestSave();
                plugin.getLogger().info("[Config] Removed obsolete keys from otherwise up-to-date config");
            }
            return;
        }

        plugin.getLogger().info("[Config] Migrating config from version " + configVersion + " to " + Constants.CONFIG_VERSION);
        boolean changed = false;

        // v1.2.3 - Language setting
        if (!cfg.contains("language")) {
            cfg.set("language", "en");
            changed = true;
            plugin.getLogger().info("[Config] New entry added: language (default: en)");
        }

        // v1.2.2 - Debug mode
        if (!cfg.contains("performance.debug_mode")) {
            cfg.set("performance.debug_mode", false);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: performance.debug_mode");
        }

        // v1.2.0 - Discord settings
        if (!cfg.contains("discord.enabled")) {
            cfg.set("discord.enabled", false);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: discord.enabled");
        }
        if (!cfg.contains("discord.webhook_url")) {
            cfg.set("discord.webhook_url", "");
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: discord.webhook_url");
        }
        if (!cfg.contains("discord.alert_types")) {
            cfg.set("discord.alert_types.high_mspt", true);
            cfg.set("discord.alert_types.tps_drop", true);
            cfg.set("discord.alert_types.high_heap", true);
            cfg.set("discord.alert_types.packet_flood", true);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: discord.alert_types");
        }

        // v2.0.0 - Lag Analysis settings (performance optimization)
        if (!cfg.contains("lag_analysis.player_tracking")) {
            cfg.set("lag_analysis.player_tracking", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: lag_analysis.player_tracking");
        }
        if (!cfg.contains("lag_analysis.plugin_analysis")) {
            cfg.set("lag_analysis.plugin_analysis", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: lag_analysis.plugin_analysis");
        }
        if (!cfg.contains("lag_analysis.chunk_analysis_timeout_ms")) {
            cfg.set("lag_analysis.chunk_analysis_timeout_ms", 5000);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: lag_analysis.chunk_analysis_timeout_ms");
        }

        // v2.0.0 - Lag Analysis Thresholds (configurable)
        if (!cfg.contains("lag_analysis.chunk_tile_entities_threshold")) {
            cfg.set("lag_analysis.chunk_tile_entities_threshold", 10);
            changed = true;
        }
        if (!cfg.contains("lag_analysis.chunk_redstone_threshold")) {
            cfg.set("lag_analysis.chunk_redstone_threshold", 30);
            changed = true;
        }
        if (!cfg.contains("lag_analysis.chunk_entity_warning")) {
            cfg.set("lag_analysis.chunk_entity_warning", 50);
            changed = true;
        }
        if (!cfg.contains("lag_analysis.chunk_entity_critical")) {
            cfg.set("lag_analysis.chunk_entity_critical", 100);
            changed = true;
        }
        if (!cfg.contains("lag_analysis.world_entity_warning")) {
            cfg.set("lag_analysis.world_entity_warning", 5000);
            changed = true;
        }
        if (!cfg.contains("lag_analysis.world_entity_critical")) {
            cfg.set("lag_analysis.world_entity_critical", 10000);
            changed = true;
        }
        if (!cfg.contains("lag_analysis.plugin_risk_low")) {
            cfg.set("lag_analysis.plugin_risk_low", 50);
            changed = true;
        }
        if (!cfg.contains("lag_analysis.plugin_risk_medium")) {
            cfg.set("lag_analysis.plugin_risk_medium", 100);
            changed = true;
        }

        // v2.2.1 - Database auto-cleanup
        if (!cfg.contains("database.retention_days")) {
            cfg.set("database.retention_days", 30);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: database.retention_days");
        }

        // v2.2.1 - GUI auto-refresh
        if (!cfg.contains("gui.auto_refresh")) {
            cfg.set("gui.auto_refresh", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: gui.auto_refresh");
        }

        // v2.2.1 - REST API
        if (!cfg.contains("api.enabled")) {
            cfg.set("api.enabled", false);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: api.enabled");
        }
        if (!cfg.contains("api.port")) {
            cfg.set("api.port", 8080);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: api.port");
        }
        if (!cfg.contains("api.key")) {
            cfg.set("api.key", "");
            changed = true;
            plugin.getLogger().info("[Config] New entry added: api.key (IMPORTANT: set a strong key before enabling the API!)");
        }

        // v3.1.0 (config_version 7) - REST API bind address (localhost-only default)
        if (!cfg.contains("api.bind")) {
            cfg.set("api.bind", "127.0.0.1");
            changed = true;
            plugin.getLogger().info("[Config] New entry added: api.bind (default: 127.0.0.1)");
        }

        // v2.3.1 - Silent mode / Streamer mode
        if (!cfg.contains("alerts.silent_players")) {
            cfg.set("alerts.silent_players", new ArrayList<String>());
            changed = true;
            plugin.getLogger().info("[Config] New entry added: alerts.silent_players");
        }

        // Update config version if changes were made
        if (changed || cleaned || configVersion < Constants.CONFIG_VERSION) {
            cfg.set("config_version", Constants.CONFIG_VERSION);
            requestSave();
            plugin.getLogger().info("[Config] Config migrated to version " + Constants.CONFIG_VERSION);
        }
    }

    /**
     * Removes keys that no longer exist in v3.1.0 (AntiCheat split into its
     * own plugin, entity cleaner removed, fixed thresholds replaced by the
     * severity model). Uses {@code isSet} instead of {@code contains} so
     * jar-embedded defaults never count as "present" — only keys the user's
     * file actually carries are removed.
     *
     * @return true when at least one key was removed
     */
    private boolean removeObsoleteKeys() {
        boolean changed = false;
        for (String obsolete : new String[]{
                "anticheat",
                "performance.anticheat_enabled",
                "entity_cleaner",
                "discord.alert_types.anticheat",
                "discord.alert_types.high_mspt",
                "discord.alert_types.tps_drop",
                "discord.alert_types.high_heap",
                "thresholds.mspt",
                "thresholds.tps_drop",
                "thresholds.heap_usage",
                "thresholds.heap_usage_percent",
                "messages"}) {
            if (cfg.isSet(obsolete)) {
                cfg.set(obsolete, null);
                changed = true;
                plugin.getLogger().info("[Config] Removed obsolete entry: " + obsolete);
            }
        }
        return changed;
    }

    // Language (null-safe with default)
    public String language() {
        String lang = cfg.getString("language", "en");
        return lang != null && !lang.isEmpty() ? lang : "en";
    }

    // Database configuration (null-safe)
    public String dbType() {
        String type = cfg.getString("database.type", "sqlite");
        return type != null && !type.isEmpty() ? type : "sqlite";
    }

    public String dbHost() {
        String host = cfg.getString("database.host", "localhost");
        return host != null && !host.isEmpty() ? host : "localhost";
    }

    public int dbPort() { return cfg.getInt("database.port", 3306); }

    public String dbName() {
        String name = cfg.getString("database.name", "performance");
        return name != null && !name.isEmpty() ? name : "performance";
    }

    public String dbUser() {
        String user = cfg.getString("database.user", "perfuser");
        return user != null && !user.isEmpty() ? user : "perfuser";
    }

    public String dbPassword() {
        // Password can be empty, so only check for null
        String password = cfg.getString("database.password", "");
        return password != null ? password : "";
    }

    public String sqliteFile() {
        String file = cfg.getString("database.sqlite_file", "plugins/PerformanceAnalyzer/perf.db");
        return file != null && !file.isEmpty() ? file : "plugins/PerformanceAnalyzer/perf.db";
    }

    public int poolMax() { return cfg.getInt("database.pool.max_pool_size", 10); }
    public int poolMinIdle() { return cfg.getInt("database.pool.minimum_idle", 2); }
    public long poolConnTimeoutMs() { return cfg.getLong("database.pool.connection_timeout_ms", 10000L); }

    public int databaseRetentionDays() {
        return cfg.getInt("database.retention_days", 30);
    }

    public boolean guiAutoRefresh() {
        return cfg.getBoolean("gui.auto_refresh", true);
    }

    // API settings
    public boolean apiEnabled() {
        return cfg.getBoolean("api.enabled", false);
    }

    public int apiPort() {
        return cfg.getInt("api.port", 8080);
    }

    /**
     * Bind address for the REST API. Defaults to loopback; anything else
     * exposes the API to the network and should only be used behind a
     * TLS-terminating reverse proxy.
     */
    public String apiBind() {
        String bind = cfg.getString("api.bind", "127.0.0.1");
        return bind != null && !bind.isEmpty() ? bind : "127.0.0.1";
    }

    public String apiKey() {
        return cfg.getString("api.key", "");
    }

    public int logIntervalSeconds() { return cfg.getInt("performance.log_interval_seconds", 60); }
    /** Seconds after startup during which no incidents/alerts are raised (v3.1.0). */
    public int startupGraceSeconds() { return cfg.getInt("performance.startup_grace_seconds", 60); }
    public boolean profilingEnabled() { return cfg.getBoolean("performance.enable_profiling", true); }
    public boolean packetAnalysisEnabled() { return cfg.getBoolean("performance.packet_analysis", true); }
    public boolean debugMode() { return cfg.getBoolean("performance.debug_mode", false); }

    /** Single-tick spike threshold in ms of real tick work (v3.1.0). */
    public double spikeTickMs() { return cfg.getDouble("thresholds.spike_tick_ms", 100.0); }
    public double packetFloodThreshold() { return cfg.getDouble("thresholds.packet_flood_per_tick", 1000.0); }

    // Lag Analysis (v2.0.0+)
    public boolean lagAnalysisPlayerTracking() {
        return cfg.getBoolean("lag_analysis.player_tracking", true);
    }

    public boolean lagAnalysisPluginAnalysis() {
        return cfg.getBoolean("lag_analysis.plugin_analysis", true);
    }

    public int chunkAnalysisTimeoutMs() {
        return cfg.getInt("lag_analysis.chunk_analysis_timeout_ms", 5000);
    }

    // Lag Analysis Thresholds (v2.0.0+)
    public int chunkTileEntitiesThreshold() {
        return cfg.getInt("lag_analysis.chunk_tile_entities_threshold", 10);
    }

    public int chunkRedstoneThreshold() {
        return cfg.getInt("lag_analysis.chunk_redstone_threshold", 30);
    }

    public int chunkEntityWarning() {
        return cfg.getInt("lag_analysis.chunk_entity_warning", 50);
    }

    public int chunkEntityCritical() {
        return cfg.getInt("lag_analysis.chunk_entity_critical", 100);
    }

    public int worldEntityWarning() {
        return cfg.getInt("lag_analysis.world_entity_warning", 5000);
    }

    public int worldEntityCritical() {
        return cfg.getInt("lag_analysis.world_entity_critical", 10000);
    }

    public int pluginRiskLow() {
        return cfg.getInt("lag_analysis.plugin_risk_low", 50);
    }

    public int pluginRiskMedium() {
        return cfg.getInt("lag_analysis.plugin_risk_medium", 100);
    }

    // Discord
    public boolean discordEnabled() { return cfg.getBoolean("discord.enabled", false); }
    public String discordWebhookUrl() { return cfg.getString("discord.webhook_url", ""); }
    public boolean discordAlertType(String type) { return cfg.getBoolean("discord.alert_types." + type, true); }

    // Fallback File Logging (v3.1.0)
    public boolean fallbackFileLoggingEnabled() {
        return cfg.getBoolean("database.fallback_file_logging", true);
    }

    public String fallbackLogFile() {
        String path = cfg.getString("database.fallback_log_file", "plugins/PerformanceAnalyzer/fallback.log");
        return path != null && !path.isEmpty() ? path : "plugins/PerformanceAnalyzer/fallback.log";
    }

    // Silent mode (persistent alert preferences)
    public List<String> silentPlayers() {
        return cfg.getStringList("alerts.silent_players");
    }

    public void setSilentPlayers(List<String> players) {
        cfg.set("alerts.silent_players", players);
        requestSave();
    }

    private void requestSave() {
        dirty = true;
        asyncSaver.saveAsync();
    }

    /**
     * Save config synchronously on shutdown — but ONLY when this plugin
     * actually changed the in-memory config. Unconditional saving would
     * write the stale in-memory state over any config.yml edits an admin
     * made while the server was running (observed: a hand-added api section
     * was wiped by the shutdown save).
     */
    public void saveSyncOnShutdown() {
        if (!dirty) {
            plugin.getLogger().fine("[Config] No pending changes, skipping shutdown save");
            return;
        }
        asyncSaver.saveSyncOnShutdown();
        dirty = false;
    }
}
