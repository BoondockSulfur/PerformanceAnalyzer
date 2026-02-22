package dev.boondock.performanceanalyzer.config;

import dev.boondock.performanceanalyzer.util.Constants;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;

/**
 * Handles configuration migration between versions.
 * Separated from PluginConfig for better maintainability.
 *
 * @since 3.0.0
 */
public class ConfigMigrator {

    private final JavaPlugin plugin;
    private final FileConfiguration config;

    public ConfigMigrator(JavaPlugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Migrate configuration to current version if needed.
     * @return true if changes were made
     */
    public boolean migrate() {
        int currentVersion = config.getInt("config_version", 0);

        if (currentVersion >= Constants.CONFIG_VERSION) {
            return false; // Already up to date
        }

        plugin.getLogger().info("[Config] Migrating from v" + currentVersion + " to v" + Constants.CONFIG_VERSION);
        boolean changed = false;

        // Migrate sequentially through versions
        if (currentVersion < 1) changed |= migrateToV1();
        if (currentVersion < 2) changed |= migrateToV2();
        if (currentVersion < 3) changed |= migrateToV3();
        if (currentVersion < 4) changed |= migrateToV4();
        if (currentVersion < 5) changed |= migrateToV5();

        // Update version
        config.set("config_version", Constants.CONFIG_VERSION);
        return true;
    }

    private boolean migrateToV1() {
        boolean changed = false;
        if (!config.contains("language")) {
            config.set("language", "en");
            changed = true;
        }
        return changed;
    }

    private boolean migrateToV2() {
        boolean changed = false;
        // Discord settings
        if (!config.contains("discord.enabled")) {
            config.set("discord.enabled", false);
            config.set("discord.webhook_url", "");
            config.set("discord.alert_types.high_mspt", true);
            config.set("discord.alert_types.tps_drop", true);
            config.set("discord.alert_types.high_heap", true);
            config.set("discord.alert_types.packet_flood", true);
            config.set("discord.alert_types.anticheat", true);
            changed = true;
        }

        // AntiCheat settings
        if (!config.contains("performance.anticheat_enabled")) {
            config.set("performance.anticheat_enabled", false);
            config.set("anticheat.xray_detection", true);
            config.set("anticheat.xray_timewindow_seconds", 60);
            config.set("anticheat.whitelist_players", new ArrayList<String>());
            config.set("anticheat.whitelist_groups", new ArrayList<String>());
            changed = true;
        }
        return changed;
    }

    private boolean migrateToV3() {
        boolean changed = false;
        // XRay per-ore thresholds
        if (!config.contains("anticheat.xray_thresholds")) {
            config.set("anticheat.xray_thresholds.coal", 20);
            config.set("anticheat.xray_thresholds.iron", 15);
            config.set("anticheat.xray_thresholds.copper", 15);
            config.set("anticheat.xray_thresholds.gold", 10);
            config.set("anticheat.xray_thresholds.redstone", 10);
            config.set("anticheat.xray_thresholds.lapis", 8);
            config.set("anticheat.xray_thresholds.diamond", 6);
            config.set("anticheat.xray_thresholds.emerald", 4);
            config.set("anticheat.xray_thresholds.ancient_debris", 3);
            changed = true;
        }

        // Movement checks
        if (!config.contains("anticheat.speed_thresholds")) {
            config.set("anticheat.speed_thresholds.walk", 0.5);
            config.set("anticheat.speed_thresholds.sprint", 1.0);
            config.set("anticheat.speed_thresholds.fly", 1.5);
            config.set("anticheat.speed_thresholds.vertical", 3.5);
            config.set("anticheat.speed_thresholds.teleport", 15.0);
            config.set("anticheat.speed_thresholds.violations_before_alert", 5);
            config.set("anticheat.speed_thresholds.fly_violations_before_alert", 10);
            changed = true;
        }
        return changed;
    }

    private boolean migrateToV4() {
        boolean changed = false;
        // Lag analysis settings (v2.0.0)
        if (!config.contains("lag_analysis.player_tracking")) {
            config.set("lag_analysis.player_tracking", true);
            config.set("lag_analysis.plugin_analysis", true);
            config.set("lag_analysis.chunk_analysis_timeout_ms", 5000);
            config.set("lag_analysis.chunk_tile_entities_threshold", 10);
            config.set("lag_analysis.chunk_redstone_threshold", 30);
            config.set("lag_analysis.chunk_entity_warning", 50);
            config.set("lag_analysis.chunk_entity_critical", 100);
            config.set("lag_analysis.world_entity_warning", 5000);
            config.set("lag_analysis.world_entity_critical", 10000);
            config.set("lag_analysis.plugin_risk_low", 50);
            config.set("lag_analysis.plugin_risk_medium", 100);
            changed = true;
        }
        return changed;
    }

    private boolean migrateToV5() {
        boolean changed = false;
        // v3.0.0 - Prometheus metrics
        if (!config.contains("metrics.prometheus_enabled")) {
            config.set("metrics.prometheus_enabled", false);
            config.set("metrics.prometheus_port", 9100);
            changed = true;
        }

        // Fallback file logging
        if (!config.contains("database.fallback_file_logging")) {
            config.set("database.fallback_file_logging", true);
            config.set("database.fallback_log_file", "plugins/PerformanceAnalyzer/fallback.log");
            changed = true;
        }
        return changed;
    }
}
