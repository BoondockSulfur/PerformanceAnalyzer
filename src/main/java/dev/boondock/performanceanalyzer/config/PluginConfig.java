package dev.boondock.performanceanalyzer.config;

import dev.boondock.performanceanalyzer.util.Constants;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class PluginConfig {

    /**
     * Commands known to do a world's worth of work in a single tick. Kept
     * short on purpose: every entry here can collect the blame for a stall it
     * merely overlapped, so a command belongs on this list only if it is
     * genuinely capable of freezing the server.
     */
    static final java.util.List<String> DEFAULT_EXPENSIVE_COMMANDS = java.util.List.of(
            "/",            // every WorldEdit / FAWE command: //paste, //replace, …
            "mv create", "mv delete", "mv regen",
            "mvcreate", "mvdelete", "mvregen",
            "chunky start", "chunky continue");

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
     * Migrate config - add missing keys and drop obsolete ones.
     *
     * <p>Both steps are idempotent and run on EVERY start, independent of
     * {@code config_version}. Gating them on the version number is what broke
     * production twice: a config stamped with the current version by an
     * older/experimental build keeps dead sections forever and never receives
     * keys added later. Observed on production-mc-1, which carried a
     * {@code config_version: 7} file with no api/alerts/gui blocks at all
     * because this method returned early.
     *
     * <p>Presence is checked with {@code isSet}, never {@code contains}:
     * {@link org.bukkit.plugin.java.JavaPlugin#getConfig()} attaches the
     * jar-embedded config.yml as defaults, so {@code contains} reports true
     * for every key the default file has - which is all of them, making the
     * add-missing-key branches dead code.
     */
    private void migrateConfig() {
        boolean cleaned = removeObsoleteKeys();

        int configVersion = cfg.getInt("config_version", 0);
        if (configVersion < Constants.CONFIG_VERSION) {
            plugin.getLogger().info("[Config] Migrating config from version " + configVersion
                    + " to " + Constants.CONFIG_VERSION);
        }

        boolean changed = false;

        // v1.2.3 - Language setting
        if (!cfg.isSet("language")) {
            cfg.set("language", "en");
            changed = true;
            plugin.getLogger().info("[Config] New entry added: language (default: en)");
        }

        // v1.2.2 - Debug mode
        if (!cfg.isSet("performance.debug_mode")) {
            cfg.set("performance.debug_mode", false);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: performance.debug_mode");
        }

        // v1.2.0 - Discord settings
        if (!cfg.isSet("discord.enabled")) {
            cfg.set("discord.enabled", false);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: discord.enabled");
        }
        if (!cfg.isSet("discord.webhook_url")) {
            cfg.set("discord.webhook_url", "");
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: discord.webhook_url");
        }
        if (!cfg.isSet("discord.alert_types")) {
            cfg.set("discord.alert_types.high_mspt", true);
            cfg.set("discord.alert_types.tps_drop", true);
            cfg.set("discord.alert_types.high_heap", true);
            cfg.set("discord.alert_types.packet_flood", true);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: discord.alert_types");
        }

        // v2.0.0 - Lag Analysis settings (performance optimization)
        if (!cfg.isSet("lag_analysis.player_tracking")) {
            cfg.set("lag_analysis.player_tracking", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: lag_analysis.player_tracking");
        }
        if (!cfg.isSet("lag_analysis.plugin_analysis")) {
            cfg.set("lag_analysis.plugin_analysis", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: lag_analysis.plugin_analysis");
        }
        if (!cfg.isSet("lag_analysis.chunk_analysis_timeout_ms")) {
            cfg.set("lag_analysis.chunk_analysis_timeout_ms", 5000);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: lag_analysis.chunk_analysis_timeout_ms");
        }

        // v2.0.0 - Lag Analysis Thresholds (configurable)
        if (!cfg.isSet("lag_analysis.chunk_tile_entities_threshold")) {
            cfg.set("lag_analysis.chunk_tile_entities_threshold", 10);
            changed = true;
        }
        if (!cfg.isSet("lag_analysis.chunk_redstone_threshold")) {
            cfg.set("lag_analysis.chunk_redstone_threshold", 30);
            changed = true;
        }
        if (!cfg.isSet("lag_analysis.chunk_entity_warning")) {
            cfg.set("lag_analysis.chunk_entity_warning", 50);
            changed = true;
        }
        if (!cfg.isSet("lag_analysis.chunk_entity_critical")) {
            cfg.set("lag_analysis.chunk_entity_critical", 100);
            changed = true;
        }
        if (!cfg.isSet("lag_analysis.world_entity_warning")) {
            cfg.set("lag_analysis.world_entity_warning", 5000);
            changed = true;
        }
        if (!cfg.isSet("lag_analysis.world_entity_critical")) {
            cfg.set("lag_analysis.world_entity_critical", 10000);
            changed = true;
        }
        if (!cfg.isSet("lag_analysis.plugin_risk_low")) {
            cfg.set("lag_analysis.plugin_risk_low", 50);
            changed = true;
        }
        if (!cfg.isSet("lag_analysis.plugin_risk_medium")) {
            cfg.set("lag_analysis.plugin_risk_medium", 100);
            changed = true;
        }

        // v2.2.1 - Database auto-cleanup
        if (!cfg.isSet("database.retention_days")) {
            cfg.set("database.retention_days", 30);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: database.retention_days");
        }

        // v2.2.1 - GUI auto-refresh
        if (!cfg.isSet("gui.auto_refresh")) {
            cfg.set("gui.auto_refresh", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: gui.auto_refresh");
        }

        // v2.2.1 - REST API
        if (!cfg.isSet("api.enabled")) {
            cfg.set("api.enabled", false);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: api.enabled");
        }
        if (!cfg.isSet("api.port")) {
            cfg.set("api.port", 8080);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: api.port");
        }
        if (!cfg.isSet("api.key")) {
            cfg.set("api.key", "");
            changed = true;
            plugin.getLogger().info("[Config] New entry added: api.key (IMPORTANT: set a strong key before enabling the API!)");
        }

        // v3.1.0 (config_version 7) - REST API bind address (localhost-only default)
        if (!cfg.isSet("api.bind")) {
            cfg.set("api.bind", "127.0.0.1");
            changed = true;
            plugin.getLogger().info("[Config] New entry added: api.bind (default: 127.0.0.1)");
        }

        // v2.3.1 - Silent mode / Streamer mode
        if (!cfg.isSet("alerts.silent_players")) {
            cfg.set("alerts.silent_players", new ArrayList<String>());
            changed = true;
            plugin.getLogger().info("[Config] New entry added: alerts.silent_players");
        }

        // v3.1.0 - keys that shipped in the default config.yml but never had a
        // migration branch, so existing installs silently ran on jar defaults.
        if (!cfg.isSet("performance.startup_grace_seconds")) {
            cfg.set("performance.startup_grace_seconds", 60);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: performance.startup_grace_seconds");
        }
        if (!cfg.isSet("thresholds.spike_tick_ms")) {
            cfg.set("thresholds.spike_tick_ms", 100.0);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: thresholds.spike_tick_ms");
        }
        if (!cfg.isSet("database.fallback_file_logging")) {
            cfg.set("database.fallback_file_logging", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: database.fallback_file_logging");
        }
        if (!cfg.isSet("alerts.dampen_world_save")) {
            cfg.set("alerts.dampen_world_save", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: alerts.dampen_world_save");
        }
        if (!cfg.isSet("database.fallback_log_file")) {
            cfg.set("database.fallback_log_file", "plugins/PerformanceAnalyzer/fallback.log");
            changed = true;
            plugin.getLogger().info("[Config] New entry added: database.fallback_log_file");
        }
        if (!cfg.isSet("alerts.warning_sustain_seconds")) {
            cfg.set("alerts.warning_sustain_seconds", 30);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: alerts.warning_sustain_seconds");
        }
        if (!cfg.isSet("attribution.expensive_commands")) {
            cfg.set("attribution.expensive_commands", DEFAULT_EXPENSIVE_COMMANDS);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: attribution.expensive_commands");
        }

        // The alert_types block above is only created when absent as a whole -
        // configs that already carry it never received the v3.1.0 incident
        // types, so each subkey is checked individually.
        for (String alertType : new String[]{
                "incident_opened", "incident_escalated", "incident_resolved", "performance"}) {
            String path = "discord.alert_types." + alertType;
            if (!cfg.isSet(path)) {
                cfg.set(path, true);
                changed = true;
                plugin.getLogger().info("[Config] New entry added: " + path);
            }
        }

        if (changed || cleaned || configVersion < Constants.CONFIG_VERSION) {
            cfg.set("config_version", Constants.CONFIG_VERSION);
            requestSave();
            if (configVersion < Constants.CONFIG_VERSION) {
                plugin.getLogger().info("[Config] Config migrated to version " + Constants.CONFIG_VERSION);
            } else {
                plugin.getLogger().info("[Config] Config updated (missing/obsolete keys fixed)");
            }
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

    /**
     * Whether incidents attributed to a world save alert at all.
     *
     * <p>A scheduled backup blocks the tick loop for as long as the worlds
     * need and is measured as a genuine stall - but it is known, harmless and
     * happens every night. Default is to record it and stay quiet.
     */
    public boolean dampenWorldSaveAlerts() {
        return cfg.getBoolean("alerts.dampen_world_save", true);
    }

    /**
     * How long a WARNING has to hold before it becomes an incident. CRITICAL
     * and above ignore it. Clamped to 0-600 s; 0 restores the old
     * report-immediately behaviour.
     */
    public int warningSustainSeconds() {
        return Math.max(0, Math.min(600, cfg.getInt("alerts.warning_sustain_seconds", 30)));
    }

    /**
     * Command prefixes whose execution is remembered as a possible cause for a
     * stall in the seconds that follow. Matched case-insensitively against the
     * command without its leading slash, so {@code "/"} covers every WorldEdit
     * command ({@code //paste}, {@code //replace}, …).
     */
    public java.util.List<String> expensiveCommands() {
        java.util.List<String> configured = cfg.getStringList("attribution.expensive_commands");
        return configured.isEmpty() ? DEFAULT_EXPENSIVE_COMMANDS : configured;
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

    /* ------------------------------------------------------------------ */
    /* Calibration support                                                 */
    /* ------------------------------------------------------------------ */

    /** File name of the backup written before calibrated values are applied. */
    public static final String CALIBRATION_BACKUP = "config.yml.pre-calibrate";

    /**
     * Copies the current config.yml aside so a calibration can be undone.
     * Overwrites an older backup - only the most recent calibration is
     * revertible, which is what {@code /perfcalibrate revert} promises.
     *
     * @return true when a backup exists afterwards
     */
    public boolean backupForCalibration() {
        File source = new File(plugin.getDataFolder(), "config.yml");
        if (!source.isFile()) {
            return false;
        }
        try {
            Files.copy(source.toPath(),
                    new File(plugin.getDataFolder(), CALIBRATION_BACKUP).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[Config] Could not write calibration backup: " + e.getMessage());
            return false;
        }
    }

    /** True when a revertible calibration backup is present. */
    public boolean hasCalibrationBackup() {
        return new File(plugin.getDataFolder(), CALIBRATION_BACKUP).isFile();
    }

    /**
     * Restores the pre-calibration config.yml. The caller is responsible for
     * reloading afterwards.
     *
     * @return true on success
     */
    public boolean restoreCalibrationBackup() {
        File backup = new File(plugin.getDataFolder(), CALIBRATION_BACKUP);
        if (!backup.isFile()) {
            return false;
        }
        try {
            Files.copy(backup.toPath(),
                    new File(plugin.getDataFolder(), "config.yml").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            // The in-memory copy is stale now; drop the dirty flag so the
            // shutdown save cannot write it back over the restored file.
            dirty = false;
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[Config] Could not restore calibration backup: " + e.getMessage());
            return false;
        }
    }

    /**
     * Writes calibrated values and flushes them to disk.
     *
     * @return future completing once config.yml has been written
     */
    public CompletableFuture<Void> applyCalibration(Map<String, Object> values) {
        values.forEach(cfg::set);
        dirty = true;
        return asyncSaver.saveAsync();
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
