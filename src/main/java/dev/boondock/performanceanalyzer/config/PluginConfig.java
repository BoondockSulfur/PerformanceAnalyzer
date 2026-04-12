package dev.boondock.performanceanalyzer.config;

import dev.boondock.performanceanalyzer.util.Constants;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PluginConfig {

    private final JavaPlugin plugin;
    private FileConfiguration cfg;
    private final AsyncConfigSaver asyncSaver;

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

        // Validate xray_stone_ore_ratio (must be 0.0-1.0)
        double ratio = cfg.getDouble("anticheat.xray_stone_ore_ratio", 0.10);
        if (ratio < 0.0 || ratio > 1.0) {
            plugin.getLogger().warning("[Config] Invalid anticheat.xray_stone_ore_ratio: " + ratio + " (must be 0.0-1.0). Using default: 0.10");
            cfg.set("anticheat.xray_stone_ore_ratio", 0.10);
            hasErrors = true;
        }

        // Validate log interval (must be > 0)
        int logInterval = cfg.getInt("performance.log_interval_seconds", 60);
        if (logInterval <= 0) {
            plugin.getLogger().warning("[Config] Invalid performance.log_interval_seconds: " + logInterval + " (must be > 0). Using default: 60");
            cfg.set("performance.log_interval_seconds", 60);
            hasErrors = true;
        }

        // Validate TPS drop threshold (must be 0-20)
        double tpsThreshold = cfg.getDouble("thresholds.tps_drop", 19.0);
        if (tpsThreshold < 0.0 || tpsThreshold > 20.0) {
            plugin.getLogger().warning("[Config] Invalid thresholds.tps_drop: " + tpsThreshold + " (must be 0-20). Using default: 19.0");
            cfg.set("thresholds.tps_drop", 19.0);
            hasErrors = true;
        }

        // Validate chunk analysis timeout (must be > 0)
        int timeout = cfg.getInt("lag_analysis.chunk_analysis_timeout_ms", 5000);
        if (timeout <= 0) {
            plugin.getLogger().warning("[Config] Invalid lag_analysis.chunk_analysis_timeout_ms: " + timeout + " (must be > 0). Using default: 5000");
            cfg.set("lag_analysis.chunk_analysis_timeout_ms", 5000);
            hasErrors = true;
        }

        // Validate speed thresholds (must be > 0)
        double walkSpeed = cfg.getDouble("anticheat.speed_thresholds.walk", 0.5);
        if (walkSpeed <= 0) {
            plugin.getLogger().warning("[Config] Invalid anticheat.speed_thresholds.walk: " + walkSpeed + " (must be > 0). Using default: 0.5");
            cfg.set("anticheat.speed_thresholds.walk", 0.5);
            hasErrors = true;
        }

        double sprintSpeed = cfg.getDouble("anticheat.speed_thresholds.sprint", 1.0);
        if (sprintSpeed <= 0) {
            plugin.getLogger().warning("[Config] Invalid anticheat.speed_thresholds.sprint: " + sprintSpeed + " (must be > 0). Using default: 1.0");
            cfg.set("anticheat.speed_thresholds.sprint", 1.0);
            hasErrors = true;
        }

        // Validate Y-Level thresholds (must be 0.0-1.0 and ordered: low < medium < high)
        double yHigh = cfg.getDouble("anticheat.xray_ylevel_high", 0.85);
        double yMedium = cfg.getDouble("anticheat.xray_ylevel_medium", 0.75);
        double yLow = cfg.getDouble("anticheat.xray_ylevel_low", 0.65);
        if (yHigh < 0.0 || yHigh > 1.0 || yMedium < 0.0 || yMedium > 1.0 || yLow < 0.0 || yLow > 1.0
                || yLow >= yMedium || yMedium >= yHigh) {
            plugin.getLogger().warning("[Config] Invalid anticheat.xray_ylevel thresholds (must be 0.0-1.0 and low < medium < high). Using defaults.");
            cfg.set("anticheat.xray_ylevel_high", 0.85);
            cfg.set("anticheat.xray_ylevel_medium", 0.75);
            cfg.set("anticheat.xray_ylevel_low", 0.65);
            hasErrors = true;
        }

        if (hasErrors) {
            asyncSaver.saveAsync();
            plugin.getLogger().warning("[Config] Config validation found errors. Fixed values saved asynchronously.");
        }
    }

    /**
     * Migrate config - add missing keys from newer versions.
     * Uses version tracking to avoid unnecessary checks.
     */
    private void migrateConfig() {
        int configVersion = cfg.getInt("config_version", 0);

        // No migration needed if config is up to date
        if (configVersion >= Constants.CONFIG_VERSION) {
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

        // v1.2.1 - XRay excluded ores
        if (!cfg.contains("anticheat.xray_excluded_ores")) {
            cfg.set("anticheat.xray_excluded_ores", new ArrayList<String>());
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: anticheat.xray_excluded_ores");
        }

        // v1.2.2 - Debug mode
        if (!cfg.contains("performance.debug_mode")) {
            cfg.set("performance.debug_mode", false);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: performance.debug_mode");
        }

        // v1.2.2 - OPs bypass option
        if (!cfg.contains("anticheat.ops_bypass")) {
            cfg.set("anticheat.ops_bypass", false);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: anticheat.ops_bypass (Standard: false - OPs werden geprueft!)");
        }

        // v1.2.2 - Per-ore XRay thresholds
        if (!cfg.contains("anticheat.xray_thresholds")) {
            cfg.set("anticheat.xray_thresholds.coal", 20);
            cfg.set("anticheat.xray_thresholds.iron", 15);
            cfg.set("anticheat.xray_thresholds.copper", 15);
            cfg.set("anticheat.xray_thresholds.gold", 10);
            cfg.set("anticheat.xray_thresholds.redstone", 10);
            cfg.set("anticheat.xray_thresholds.lapis", 8);
            cfg.set("anticheat.xray_thresholds.diamond", 6);
            cfg.set("anticheat.xray_thresholds.emerald", 4);
            cfg.set("anticheat.xray_thresholds.ancient_debris", 3);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: anticheat.xray_thresholds");
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
            cfg.set("discord.alert_types.anticheat", true);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: discord.alert_types");
        }

        // v1.2.0 - AntiCheat settings (default: disabled - must be enabled consciously)
        if (!cfg.contains("performance.anticheat_enabled")) {
            cfg.set("performance.anticheat_enabled", false);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: performance.anticheat_enabled");
        }
        if (!cfg.contains("anticheat.xray_detection")) {
            cfg.set("anticheat.xray_detection", true);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: anticheat.xray_detection");
        }
        // Note: xray_threshold_ores was replaced by per-ore thresholds in v1.2.2
        if (!cfg.contains("anticheat.xray_timewindow_seconds")) {
            cfg.set("anticheat.xray_timewindow_seconds", 60);
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: anticheat.xray_timewindow_seconds");
        }
        if (!cfg.contains("anticheat.whitelist_players")) {
            cfg.set("anticheat.whitelist_players", new ArrayList<String>());
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: anticheat.whitelist_players");
        }
        if (!cfg.contains("anticheat.whitelist_groups")) {
            cfg.set("anticheat.whitelist_groups", new ArrayList<String>());
            changed = true;
            plugin.getLogger().info("[Config] Neuer Eintrag hinzugefuegt: anticheat.whitelist_groups");
        }

        // v1.2.3 - Speed/Movement thresholds
        if (!cfg.contains("anticheat.speed_thresholds")) {
            cfg.set("anticheat.speed_thresholds.walk", 0.5);
            cfg.set("anticheat.speed_thresholds.sprint", 1.0);
            cfg.set("anticheat.speed_thresholds.fly", 1.5);
            cfg.set("anticheat.speed_thresholds.vertical", 3.5);
            cfg.set("anticheat.speed_thresholds.teleport", 15.0);
            cfg.set("anticheat.speed_thresholds.violations_before_alert", 5);
            cfg.set("anticheat.speed_thresholds.fly_violations_before_alert", 10);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: anticheat.speed_thresholds");
        }

        // v1.2.3 - TPS drop threshold
        if (!cfg.contains("thresholds.tps_drop")) {
            cfg.set("thresholds.tps_drop", 19.0);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: thresholds.tps_drop");
        }

        // v1.2.4 - XRay stone-to-ore ratio threshold
        if (!cfg.contains("anticheat.xray_stone_ore_ratio")) {
            cfg.set("anticheat.xray_stone_ore_ratio", 0.10);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: anticheat.xray_stone_ore_ratio");
        }

        // v2.0.0 - Restricted worlds (instant alert zones)
        if (!cfg.contains("anticheat.restricted_worlds")) {
            cfg.set("anticheat.restricted_worlds", new ArrayList<String>());
            changed = true;
            plugin.getLogger().info("[Config] New entry added: anticheat.restricted_worlds");
        }
        if (!cfg.contains("anticheat.restricted_world_ores")) {
            cfg.set("anticheat.restricted_world_ores", new ArrayList<String>());
            changed = true;
            plugin.getLogger().info("[Config] New entry added: anticheat.restricted_world_ores");
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

        // v2.0.1 - Individual AntiCheat toggles
        if (!cfg.contains("anticheat.movement_checks")) {
            cfg.set("anticheat.movement_checks", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: anticheat.movement_checks");
        }
        if (!cfg.contains("anticheat.speed_detection")) {
            cfg.set("anticheat.speed_detection", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: anticheat.speed_detection");
        }
        if (!cfg.contains("anticheat.fly_detection")) {
            cfg.set("anticheat.fly_detection", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: anticheat.fly_detection");
        }
        if (!cfg.contains("anticheat.teleport_detection")) {
            cfg.set("anticheat.teleport_detection", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: anticheat.teleport_detection");
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
            cfg.set("api.key", "changeme");
            changed = true;
            plugin.getLogger().info("[Config] New entry added: api.key (IMPORTANT: Change this!)");
        }

        // v2.2.1 - Auto Entity Cleaner
        if (!cfg.contains("entity_cleaner.enabled")) {
            cfg.set("entity_cleaner.enabled", false);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: entity_cleaner.enabled");
        }
        if (!cfg.contains("entity_cleaner.interval_seconds")) {
            cfg.set("entity_cleaner.interval_seconds", 300);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: entity_cleaner.interval_seconds");
        }
        if (!cfg.contains("entity_cleaner.world_limit")) {
            cfg.set("entity_cleaner.world_limit", 5000);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: entity_cleaner.world_limit");
        }
        if (!cfg.contains("entity_cleaner.chunk_limit")) {
            cfg.set("entity_cleaner.chunk_limit", 100);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: entity_cleaner.chunk_limit");
        }
        if (!cfg.contains("entity_cleaner.dry_run")) {
            cfg.set("entity_cleaner.dry_run", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: entity_cleaner.dry_run");
        }
        if (!cfg.contains("entity_cleaner.protect_villagers")) {
            cfg.set("entity_cleaner.protect_villagers", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: entity_cleaner.protect_villagers");
        }
        if (!cfg.contains("entity_cleaner.protect_item_frames")) {
            cfg.set("entity_cleaner.protect_item_frames", true);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: entity_cleaner.protect_item_frames");
        }
        if (!cfg.contains("entity_cleaner.blacklist")) {
            List<String> defaultBlacklist = Arrays.asList("ENDER_DRAGON", "WITHER", "WARDEN");
            cfg.set("entity_cleaner.blacklist", defaultBlacklist);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: entity_cleaner.blacklist");
        }
        if (!cfg.contains("entity_cleaner.world_whitelist")) {
            cfg.set("entity_cleaner.world_whitelist", new ArrayList<String>());
            changed = true;
            plugin.getLogger().info("[Config] New entry added: entity_cleaner.world_whitelist");
        }

        // v2.3.1 - XRay Y-Level analysis thresholds
        if (!cfg.contains("anticheat.xray_ylevel_high")) {
            cfg.set("anticheat.xray_ylevel_high", 0.85);
            cfg.set("anticheat.xray_ylevel_medium", 0.75);
            cfg.set("anticheat.xray_ylevel_low", 0.65);
            changed = true;
            plugin.getLogger().info("[Config] New entry added: anticheat.xray_ylevel thresholds");
        }

        // v2.3.1 - Silent mode / Streamer mode
        if (!cfg.contains("alerts.silent_players")) {
            cfg.set("alerts.silent_players", new ArrayList<String>());
            changed = true;
            plugin.getLogger().info("[Config] New entry added: alerts.silent_players");
        }

        // Update config version if changes were made
        if (changed || configVersion < Constants.CONFIG_VERSION) {
            cfg.set("config_version", Constants.CONFIG_VERSION);
            asyncSaver.saveAsync();
            plugin.getLogger().info("[Config] Config migrated to version " + Constants.CONFIG_VERSION);
        }
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

    public String apiKey() {
        return cfg.getString("api.key", "changeme");
    }

    // Entity cleaner settings
    public boolean entityCleanerEnabled() {
        return cfg.getBoolean("entity_cleaner.enabled", false);
    }

    public int entityCleanerIntervalSeconds() {
        return cfg.getInt("entity_cleaner.interval_seconds", 300);
    }

    public int entityCleanerWorldLimit() {
        return cfg.getInt("entity_cleaner.world_limit", 5000);
    }

    public int entityCleanerChunkLimit() {
        return cfg.getInt("entity_cleaner.chunk_limit", 100);
    }

    public boolean entityCleanerDryRun() {
        return cfg.getBoolean("entity_cleaner.dry_run", true);
    }

    public boolean entityCleanerProtectVillagers() {
        return cfg.getBoolean("entity_cleaner.protect_villagers", true);
    }

    public boolean entityCleanerProtectItemFrames() {
        return cfg.getBoolean("entity_cleaner.protect_item_frames", true);
    }

    public List<String> entityCleanerBlacklist() {
        return cfg.getStringList("entity_cleaner.blacklist");
    }

    public List<String> entityCleanerWorldWhitelist() {
        return cfg.getStringList("entity_cleaner.world_whitelist");
    }

    public int logIntervalSeconds() { return cfg.getInt("performance.log_interval_seconds", 60); }
    public boolean profilingEnabled() { return cfg.getBoolean("performance.enable_profiling", true); }
    public boolean packetAnalysisEnabled() { return cfg.getBoolean("performance.packet_analysis", true); }
    public boolean anticheatEnabled() { return cfg.getBoolean("performance.anticheat_enabled", false); }
    public boolean debugMode() { return cfg.getBoolean("performance.debug_mode", false); }

    public double thresholdMspt() { return cfg.getDouble("thresholds.mspt", 50.0); }
    public double thresholdHeapUsage() { return cfg.getDouble("thresholds.heap_usage_percent", 80.0); }
    public double packetFloodThreshold() { return cfg.getDouble("thresholds.packet_flood_per_tick", 1000.0); }

    // AntiCheat
    public boolean xrayDetectionEnabled() { return cfg.getBoolean("anticheat.xray_detection", true); }
    public boolean movementChecksEnabled() { return cfg.getBoolean("anticheat.movement_checks", true); }
    public boolean speedDetectionEnabled() { return cfg.getBoolean("anticheat.speed_detection", true); }
    public boolean flyDetectionEnabled() { return cfg.getBoolean("anticheat.fly_detection", true); }
    public boolean teleportDetectionEnabled() { return cfg.getBoolean("anticheat.teleport_detection", true); }
    public int xrayTimewindowSeconds() { return cfg.getInt("anticheat.xray_timewindow_seconds", 60); }
    public List<String> xrayExcludedOres() { return cfg.getStringList("anticheat.xray_excluded_ores"); }

    // Per-ore XRay thresholds
    public int xrayThreshold(String oreType) {
        String key = oreType.toLowerCase()
            .replace("_ore", "")
            .replace("deepslate_", "");

        // Special handling for ancient_debris (no _ore suffix)
        if (key.equals("ancient_debris")) {
            int threshold = cfg.getInt("anticheat.xray_thresholds.ancient_debris", 3);
            if (debugMode()) {
                plugin.getLogger().info("[XRay Threshold] " + oreType + " -> ancient_debris -> threshold: " + threshold);
            }
            return threshold;
        }

        int threshold = cfg.getInt("anticheat.xray_thresholds." + key, 10);
        // Debug: Log threshold lookups
        if (debugMode()) {
            plugin.getLogger().info("[XRay Threshold] " + oreType + " -> key: " + key + " -> threshold: " + threshold);
        }
        return threshold;
    }

    // XRay Stone-to-Ore ratio threshold
    public double xrayStoneOreRatio() {
        return cfg.getDouble("anticheat.xray_stone_ore_ratio", 0.10);
    }

    // XRay Y-Level pattern analysis thresholds
    public double xrayYLevelHigh() { return cfg.getDouble("anticheat.xray_ylevel_high", 0.85); }
    public double xrayYLevelMedium() { return cfg.getDouble("anticheat.xray_ylevel_medium", 0.75); }
    public double xrayYLevelLow() { return cfg.getDouble("anticheat.xray_ylevel_low", 0.65); }
    public int xrayThresholdCoal() { return cfg.getInt("anticheat.xray_thresholds.coal", 20); }
    public int xrayThresholdIron() { return cfg.getInt("anticheat.xray_thresholds.iron", 15); }
    public int xrayThresholdCopper() { return cfg.getInt("anticheat.xray_thresholds.copper", 15); }
    public int xrayThresholdGold() { return cfg.getInt("anticheat.xray_thresholds.gold", 10); }
    public int xrayThresholdRedstone() { return cfg.getInt("anticheat.xray_thresholds.redstone", 10); }
    public int xrayThresholdLapis() { return cfg.getInt("anticheat.xray_thresholds.lapis", 8); }
    public int xrayThresholdDiamond() { return cfg.getInt("anticheat.xray_thresholds.diamond", 6); }
    public int xrayThresholdEmerald() { return cfg.getInt("anticheat.xray_thresholds.emerald", 4); }
    public int xrayThresholdAncientDebris() { return cfg.getInt("anticheat.xray_thresholds.ancient_debris", 3); }
    public boolean opsBypass() { return cfg.getBoolean("anticheat.ops_bypass", false); }
    public List<String> anticheatWhitelistPlayers() { return cfg.getStringList("anticheat.whitelist_players"); }
    public List<String> anticheatWhitelistGroups() { return cfg.getStringList("anticheat.whitelist_groups"); }

    // Movement/Speed thresholds
    public double speedThresholdWalk() { return cfg.getDouble("anticheat.speed_thresholds.walk", 0.5); }
    public double speedThresholdSprint() { return cfg.getDouble("anticheat.speed_thresholds.sprint", 1.0); }
    public double speedThresholdFly() { return cfg.getDouble("anticheat.speed_thresholds.fly", 1.5); }
    public double flyThreshold() { return cfg.getDouble("anticheat.speed_thresholds.vertical", 3.5); }
    public double teleportThreshold() { return cfg.getDouble("anticheat.speed_thresholds.teleport", 15.0); }
    public int speedViolationsThreshold() { return cfg.getInt("anticheat.speed_thresholds.violations_before_alert", 5); }
    public int flyViolationsThreshold() { return cfg.getInt("anticheat.speed_thresholds.fly_violations_before_alert", 10); }

    // TPS threshold
    public double tpsDropThreshold() { return cfg.getDouble("thresholds.tps_drop", 19.0); }

    public void addWhitelistPlayer(String uuid) {
        List<String> list = anticheatWhitelistPlayers();
        if (!list.contains(uuid)) {
            list.add(uuid);
            cfg.set("anticheat.whitelist_players", list);
            asyncSaver.saveAsync();
        }
    }

    public void removeWhitelistPlayer(String uuid) {
        List<String> list = anticheatWhitelistPlayers();
        if (list.remove(uuid)) {
            cfg.set("anticheat.whitelist_players", list);
            asyncSaver.saveAsync();
        }
    }

    public void addWhitelistGroup(String group) {
        List<String> list = anticheatWhitelistGroups();
        if (!list.contains(group)) {
            list.add(group);
            cfg.set("anticheat.whitelist_groups", list);
            asyncSaver.saveAsync();
        }
    }

    public void removeWhitelistGroup(String group) {
        List<String> list = anticheatWhitelistGroups();
        if (list.remove(group)) {
            cfg.set("anticheat.whitelist_groups", list);
            asyncSaver.saveAsync();
        }
    }

    // XRay Excluded Ores
    public void addExcludedOre(String ore) {
        List<String> list = new ArrayList<>(xrayExcludedOres());
        String upperOre = ore.toUpperCase();
        if (!list.contains(upperOre)) {
            list.add(upperOre);
            cfg.set("anticheat.xray_excluded_ores", list);
            asyncSaver.saveAsync();
        }
    }

    public void removeExcludedOre(String ore) {
        List<String> list = new ArrayList<>(xrayExcludedOres());
        String upperOre = ore.toUpperCase();
        if (list.remove(upperOre)) {
            cfg.set("anticheat.xray_excluded_ores", list);
            asyncSaver.saveAsync();
        }
    }

    // Restricted Worlds (instant alert zones)
    public List<String> restrictedWorlds() {
        return cfg.getStringList("anticheat.restricted_worlds");
    }

    public List<String> restrictedWorldOres() {
        List<String> ores = cfg.getStringList("anticheat.restricted_world_ores");
        // If empty, return empty list (will be interpreted as "all ores" in XRayDetector)
        return ores;
    }

    public boolean isRestrictedWorld(String worldName) {
        return restrictedWorlds().contains(worldName);
    }

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

    // Fallback File Logging (v3.0.0)
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
        asyncSaver.saveAsync();
    }

    public String msg(String path, String def) { return cfg.getString("messages." + path, def); }

    /**
     * Save config synchronously on shutdown.
     * Should only be called during plugin disable.
     */
    public void saveSyncOnShutdown() {
        asyncSaver.saveSyncOnShutdown();
    }
}
