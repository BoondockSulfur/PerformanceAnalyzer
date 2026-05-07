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
     * Database connection pool defaults
     */
    public static final int DB_DEFAULT_POOL_SIZE = 10;
    public static final int DB_DEFAULT_MIN_IDLE = 2;
    public static final long DB_DEFAULT_CONNECTION_TIMEOUT_MS = 10000L;

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

    // ==================== MOVEMENT CHECKER ====================

    /**
     * Movement event sampling rate
     * Check only every Nth move event to reduce CPU usage
     */
    public static final int MOVEMENT_SAMPLE_RATE = 10;

    /**
     * Minimum distance threshold for movement checks (blocks)
     * Always check if player moves more than this distance
     */
    public static final double MOVEMENT_MIN_DISTANCE_THRESHOLD = 2.0;

    /**
     * Counter reset threshold to prevent integer overflow
     */
    public static final int MOVEMENT_COUNTER_RESET_THRESHOLD = 1000;

    // ==================== XRAY DETECTOR ====================

    /**
     * Expiration time for player-placed blocks tracking (milliseconds)
     * Tracks placed blocks for 1 hour to prevent false positives
     */
    public static final long XRAY_PLACED_BLOCK_EXPIRY_MS = 3600000L; // 1 hour

    /**
     * Periodic cleanup interval for XRay detector (ticks)
     * Runs every 5 minutes
     */
    public static final long XRAY_CLEANUP_INTERVAL_TICKS = 6000L; // 5 minutes

    /**
     * Maximum size for player-placed blocks map
     * Prevents memory issues on servers with heavy building
     */
    public static final int XRAY_MAX_PLACED_BLOCKS_SIZE = 10000;

    /**
     * Minimum stone blocks mined for ore ratio analysis
     * Prevents false positives from small samples
     */
    public static final int XRAY_MIN_STONE_FOR_RATIO_CHECK = 20;

    /**
     * Maximum number of player entries in XRay detector maps
     * Prevents memory issues on large servers with 1000+ unique players
     */
    public static final int XRAY_MAX_PLAYER_ENTRIES = 5000;

    // ==================== PERFORMANCE ANALYZER ====================

    /**
     * Update checker delay (ticks)
     * Checks for updates 3 seconds after startup
     */
    public static final long UPDATE_CHECKER_DELAY_TICKS = 60L;

    /**
     * Default log interval (seconds)
     * How often performance metrics are logged to database
     */
    public static final int DEFAULT_LOG_INTERVAL_SECONDS = 60;

    /**
     * Minimum time delta for movement checks (seconds)
     * Prevents division issues and false positives
     */
    public static final double MOVEMENT_MIN_TIME_DELTA = 0.01; // 10ms

    // ==================== CONFIG DEFAULTS ====================

    /**
     * Current config version for migration tracking
     */
    public static final int CONFIG_VERSION = 5;

    /**
     * Default language
     */
    public static final String DEFAULT_LANGUAGE = "en";

    /**
     * Default database type
     */
    public static final String DEFAULT_DB_TYPE = "sqlite";

    /**
     * Default SQLite file path
     */
    public static final String DEFAULT_SQLITE_PATH = "plugins/PerformanceAnalyzer/perf.db";

    // ==================== ALERT MANAGER ====================

    /**
     * Alert cooldown period (milliseconds)
     * Prevents alert spam
     */
    public static final long ALERT_COOLDOWN_MS = 300000L; // 5 minutes

    /**
     * Alert auto-cleanup period (milliseconds)
     * Old alerts are removed after 30 minutes
     */
    public static final long ALERT_CLEANUP_MS = 1800000L; // 30 minutes

    // ==================== PERFORMANCE THRESHOLDS ====================

    /**
     * Default MSPT threshold (milliseconds)
     */
    public static final double DEFAULT_MSPT_THRESHOLD = 50.0;

    /**
     * Default heap usage threshold (percent)
     */
    public static final double DEFAULT_HEAP_THRESHOLD = 80.0;

    /**
     * Default TPS drop threshold
     */
    public static final double DEFAULT_TPS_DROP_THRESHOLD = 19.0;

    /**
     * Default packet flood threshold (packets per tick)
     */
    public static final double DEFAULT_PACKET_FLOOD_THRESHOLD = 1000.0;

    // ==================== CHUNK ANALYSIS ====================

    /**
     * Default chunk analysis timeout (milliseconds)
     */
    public static final int DEFAULT_CHUNK_ANALYSIS_TIMEOUT_MS = 5000;

    /**
     * Default chunk entity warning threshold
     */
    public static final int DEFAULT_CHUNK_ENTITY_WARNING = 50;

    /**
     * Default chunk entity critical threshold
     */
    public static final int DEFAULT_CHUNK_ENTITY_CRITICAL = 100;

    /**
     * Default world entity warning threshold
     */
    public static final int DEFAULT_WORLD_ENTITY_WARNING = 5000;

    /**
     * Default world entity critical threshold
     */
    public static final int DEFAULT_WORLD_ENTITY_CRITICAL = 10000;

    // ==================== MOVEMENT SPEEDS ====================

    /**
     * Speed multiplier per Speed potion level
     */
    public static final double SPEED_POTION_MULTIPLIER_PER_LEVEL = 0.2;

    /**
     * Soul speed enchantment multiplier
     */
    public static final double SOUL_SPEED_MULTIPLIER = 0.3;

    /**
     * Sneaking speed multiplier (relative to walking)
     */
    public static final double SNEAKING_SPEED_MULTIPLIER = 0.3;

    /**
     * Swimming speed multiplier (relative to walking)
     */
    public static final double SWIMMING_SPEED_MULTIPLIER = 0.8;

    /**
     * Climbing speed multiplier (relative to walking)
     */
    public static final double CLIMBING_SPEED_MULTIPLIER = 0.5;

    /**
     * Creative fly speed multiplier
     */
    public static final double CREATIVE_FLY_MULTIPLIER = 2.0;

    // ==================== VEHICLE SPEEDS ====================

    public static final double HORSE_MAX_SPEED = 15.0;
    public static final double DONKEY_MAX_SPEED = 8.0;
    public static final double LLAMA_MAX_SPEED = 6.0;
    public static final double CAMEL_MAX_SPEED = 10.0;
    public static final double PIG_MAX_SPEED = 5.0;
    public static final double STRIDER_MAX_SPEED = 8.0;
    public static final double BOAT_MAX_SPEED = 10.0;
    public static final double MINECART_MAX_SPEED = 20.0;
    public static final double ELYTRA_MAX_SPEED = 100.0;
    public static final double RIPTIDE_MAX_SPEED = 50.0;
    public static final double OTHER_VEHICLE_MAX_SPEED = 20.0;

    // ==================== GUI ====================

    /**
     * Maximum performance drops to store in memory
     */
    public static final int MAX_PERFORMANCE_DROPS = 20;

    /**
     * GUI update interval (ticks)
     */
    public static final long GUI_UPDATE_INTERVAL_TICKS = 20L; // 1 second

    /**
     * Performance drops GUI display limit
     */
    public static final int GUI_DROPS_DISPLAY_LIMIT = 21;

    // ==================== PLAYER ACTIVITY TRACKER ====================

    /**
     * Activity weights for different player actions
     */
    public static final int ACTIVITY_WEIGHT_BLOCK_BREAK = 3;
    public static final int ACTIVITY_WEIGHT_BLOCK_PLACE = 2;
    public static final int ACTIVITY_WEIGHT_MOVEMENT = 1;
    public static final int ACTIVITY_WEIGHT_COMMAND = 5;
    public static final int ACTIVITY_WEIGHT_INTERACTION = 2;

    /**
     * Cleanup interval for player activity data (ticks)
     */
    public static final long ACTIVITY_CLEANUP_INTERVAL_TICKS = 6000L; // 5 minutes
}
