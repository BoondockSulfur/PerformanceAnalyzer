package dev.boondock.performanceanalyzer.util;

/**
 * Central constants class for PerformanceAnalyzer plugin.
 * Consolidates all magic numbers and configuration defaults in one place.
 */
public final class Constants {

    // Prevent instantiation
    private Constants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    // ==================== DATABASE ====================

    /**
     * Maximum number of entries to batch before flushing to database
     */
    public static final int DB_MAX_BATCH_SIZE = 1000;

    /**
     * Maximum queue size for database log entries.
     * Prevents unbounded memory growth if the database is unavailable.
     */
    public static final int DB_MAX_QUEUE_SIZE = 10000;

    // ==================== DISCORD WEBHOOK ====================

    /**
     * Minimum delay between Discord webhook requests (milliseconds)
     * Enforces 1 request per 2 seconds = max 30 per minute
     */
    public static final long DISCORD_MIN_REQUEST_DELAY_MS = 2000L;

    /**
     * Maximum queue size for Discord webhook requests
     * Prevents memory issues during alert storms
     */
    public static final int DISCORD_MAX_QUEUE_SIZE = 50;

    // ==================== CONFIG DEFAULTS ====================

    /**
     * Current config version for migration tracking
     */
    public static final int CONFIG_VERSION = 7;

    // ==================== PLAYER ACTIVITY TRACKER ====================

    /**
     * Activity weights for different player actions
     */
    public static final int ACTIVITY_WEIGHT_BLOCK_BREAK = 3;
    public static final int ACTIVITY_WEIGHT_BLOCK_PLACE = 2;
    public static final int ACTIVITY_WEIGHT_MOVEMENT = 1;
    public static final int ACTIVITY_WEIGHT_COMMAND = 5;
    public static final int ACTIVITY_WEIGHT_INTERACTION = 2;

    // ==================== DOWNLOAD PAGES ====================

    /**
     * Where an update can be fetched. Both are offered side by side because
     * admins install from whichever platform they already use.
     */
    public static final String URL_MODRINTH = "https://modrinth.com/plugin/performanceanalyzer";
    public static final String URL_CURSEFORGE =
            "https://www.curseforge.com/minecraft/bukkit-plugins/performanceanalyzer";
}
