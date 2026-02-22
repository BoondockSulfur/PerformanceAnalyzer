package dev.boondock.performanceanalyzer.api;

import org.bukkit.World;

import java.util.UUID;

/**
 * Public API for PerformanceAnalyzer v3.0.0+
 * Other plugins can use this to access performance data.
 *
 * Usage:
 * <pre>
 * PerformanceAPI api = (PerformanceAPI) Bukkit.getPluginManager().getPlugin("PerformanceAnalyzer");
 * double tps = api.getCurrentTPS();
 * </pre>
 *
 * @since 3.0.0
 */
public interface PerformanceAPI {

    /**
     * Get current server TPS (Ticks Per Second).
     * @return TPS value (0-20)
     */
    double getCurrentTPS();

    /**
     * Get current MSPT (Milliseconds Per Tick).
     * @return MSPT value
     */
    double getCurrentMSPT();

    /**
     * Get current heap memory usage percentage.
     * @return Memory usage (0-100%)
     */
    double getHeapUsagePercent();

    /**
     * Get total entity count across all worlds.
     * @return Total entity count
     */
    int getTotalEntityCount();

    /**
     * Get entity count for a specific world.
     * @param world World to check
     * @return Entity count in that world
     */
    int getEntityCount(World world);

    /**
     * Check if a player is flagged as suspicious by AntiCheat.
     * @param playerUUID Player UUID
     * @return true if player has active violations
     */
    boolean isPlayerSuspicious(UUID playerUUID);

    /**
     * Get the plugin version.
     * @return Version string (e.g. "3.0.0")
     */
    String getVersion();
}
