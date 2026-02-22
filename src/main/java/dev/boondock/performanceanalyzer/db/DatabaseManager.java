package dev.boondock.performanceanalyzer.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.util.Constants;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DatabaseManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private HikariDataSource ds;
    private final ConcurrentLinkedQueue<LogEntry> queue = new ConcurrentLinkedQueue<>();
    private int taskId = -1;
    private int cleanupTaskId = -1;
    private boolean isMySQL = false;
    private FallbackLogger fallbackLogger;
    private volatile boolean databaseAvailable = true;
    private int consecutiveFailures = 0;
    private static final int MAX_FAILURES_BEFORE_FALLBACK = 3;

    public DatabaseManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;

        // Initialize fallback logger if enabled
        if (config.fallbackFileLoggingEnabled()) {
            this.fallbackLogger = new FallbackLogger(plugin, config.fallbackLogFile());
            plugin.getLogger().info("Fallback file logging enabled: " + config.fallbackLogFile());
        }
    }

    public void init() {
        HikariConfig hc = new HikariConfig();
        String type = config.dbType().toLowerCase();
        switch (type) {
            case "mysql":
                // Check if MySQL driver is available
                if (!isMySqlDriverAvailable()) {
                    plugin.getLogger().severe("===========================================");
                    plugin.getLogger().severe("MySQL was configured as database,");
                    plugin.getLogger().severe("but the MySQL JDBC driver is not available!");
                    plugin.getLogger().severe("");
                    plugin.getLogger().severe("Solution: Download MySQL Connector/J and");
                    plugin.getLogger().severe("place it in the 'plugins' or 'libraries' folder.");
                    plugin.getLogger().severe("Download: https://dev.mysql.com/downloads/connector/j/");
                    plugin.getLogger().severe("");
                    plugin.getLogger().severe("Alternative: Switch to SQLite in config.yml");
                    plugin.getLogger().severe("===========================================");
                    throw new RuntimeException("MySQL JDBC driver not found! Please install mysql-connector-j or switch to SQLite.");
                }
                hc.setJdbcUrl("jdbc:mysql://" + config.dbHost() + ":" + config.dbPort() + "/" + config.dbName() + "?useSSL=true&requireSSL=false&serverTimezone=UTC");
                hc.setUsername(config.dbUser());
                hc.setPassword(config.dbPassword());
                hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
                this.isMySQL = true;
                plugin.getLogger().info("MySQL database configured: " + config.dbHost() + ":" + config.dbPort() + "/" + config.dbName());
                break;
            case "sqlite":
            default:
                File f = new File(config.sqliteFile());
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                hc.setJdbcUrl("jdbc:sqlite:" + f.getPath());
                this.isMySQL = false;
                break;
        }
        hc.setMaximumPoolSize(config.poolMax());
        hc.setMinimumIdle(config.poolMinIdle());
        hc.setConnectionTimeout(config.poolConnTimeoutMs());
        hc.setPoolName("PerfAnalyzerPool");
        this.ds = new HikariDataSource(hc);

        // Schema (MySQL and SQLite compatible)
        String autoIncrementSyntax = isMySQL ? "AUTO_INCREMENT" : "AUTOINCREMENT";
        String timestampDefault = isMySQL ? "DEFAULT CURRENT_TIMESTAMP" : "NOT NULL DEFAULT CURRENT_TIMESTAMP";

        // Create performance_logs table
        // This table stores all performance metrics with timestamps
        run("CREATE TABLE IF NOT EXISTS performance_logs (" +
                "id INTEGER PRIMARY KEY " + autoIncrementSyntax + ", " +
                "ts TIMESTAMP " + timestampDefault + ", " +  // Timestamp of the metric
                "log_type VARCHAR(32) NOT NULL, " +           // Metric type (mspt, tps, heap, etc.)
                "value DOUBLE, " +                            // Numeric value of the metric
                "description VARCHAR(255))");                 // Additional description/context

        // PERFORMANCE OPTIMIZATION: Composite index for time-based queries
        // This index is crucial for query performance:
        // - WHERE log_type = ? AND ts >= ?  (getRecentMspt, getAverageByType, countPerformanceSpikes)
        // - ORDER BY ts ASC/DESC
        // The (log_type, ts) composite index allows efficient filtering and sorting
        run("CREATE INDEX IF NOT EXISTS idx_logs_type_ts ON performance_logs(log_type, ts)");

        // Additional index for timestamp-only queries (optional, for cleanOldData)
        // Helps with DELETE operations based on timestamp
        run("CREATE INDEX IF NOT EXISTS idx_logs_ts ON performance_logs(ts)");

        // Async Flush Task
        int period = Math.max(1, config.logIntervalSeconds());
        this.taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushBatchSafe, period * 20L, period * 20L).getTaskId();

        // Auto-Cleanup Task (runs daily at server startup + 1 hour, then every 24 hours)
        startAutoCleanup();
    }

    /**
     * Start automatic cleanup task for old database entries.
     * Runs daily to remove entries older than configured retention period.
     */
    private void startAutoCleanup() {
        int retentionDays = config.databaseRetentionDays();

        if (retentionDays <= 0) {
            plugin.getLogger().info("[Database] Auto-cleanup disabled (retention_days = " + retentionDays + ")");
            return;
        }

        // Run cleanup 1 hour after startup, then every 24 hours
        long initialDelay = 20L * 60 * 60; // 1 hour in ticks
        long period = 20L * 60 * 60 * 24;  // 24 hours in ticks

        this.cleanupTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            plugin.getLogger().info("[Database] Running auto-cleanup (retention: " + retentionDays + " days)...");
            try {
                cleanOldData(retentionDays);
            } catch (Exception e) {
                plugin.getLogger().severe("[Database] Auto-cleanup failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, initialDelay, period).getTaskId();

        plugin.getLogger().info("[Database] Auto-cleanup scheduled: retention " + retentionDays + " days, runs daily");
    }

    /**
     * Queue a log entry for asynchronous database insertion.
     * Entries are batched and written periodically to minimize database load.
     *
     * @param type Log type/category (e.g., "mspt", "tps", "heap")
     * @param value Numeric value to log
     * @param description Optional description or context
     */
    public void logAsync(String type, double value, String description) {
        queue.add(new LogEntry(type, value, description));
    }

    private void flushBatchSafe() {
        try {
            flushBatch();
            // Reset failure counter on success
            if (consecutiveFailures > 0) {
                consecutiveFailures = 0;
                if (!databaseAvailable) {
                    plugin.getLogger().info("Database connection restored!");
                    databaseAvailable = true;
                }
            }
        } catch (Exception e) {
            consecutiveFailures++;
            plugin.getLogger().warning("DB flush failed (attempt " + consecutiveFailures + "): " + e.getMessage());

            // Switch to fallback after multiple failures
            if (consecutiveFailures >= MAX_FAILURES_BEFORE_FALLBACK && databaseAvailable) {
                plugin.getLogger().severe("Database appears to be down after " + MAX_FAILURES_BEFORE_FALLBACK + " failures!");
                plugin.getLogger().severe("Switching to fallback file logging...");
                databaseAvailable = false;
            }

            // Log to fallback if available
            if (fallbackLogger != null && !databaseAvailable) {
                List<LogEntry> failedEntries = new ArrayList<>(queue);
                for (LogEntry entry : failedEntries) {
                    fallbackLogger.log(entry.type(), entry.value(), entry.description());
                }
                queue.clear();
                plugin.getLogger().info("Logged " + failedEntries.size() + " entries to fallback file");
            }
        }
    }

    private void flushBatch() throws SQLException {
        List<LogEntry> batch = new ArrayList<>();
        LogEntry e;
        while ((e = queue.poll()) != null && batch.size() < Constants.DB_MAX_BATCH_SIZE) {
            batch.add(e);
        }
        if (batch.isEmpty()) return;

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO performance_logs (log_type, value, description) VALUES (?, ?, ?)")) {
            for (LogEntry le : batch) {
                ps.setString(1, le.type());
                ps.setDouble(2, le.value());
                ps.setString(3, le.description());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Shutdown the database manager.
     * Flushes remaining queued entries and closes the connection pool.
     * Should be called during plugin disable.
     */
    public void shutdown() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        if (cleanupTaskId != -1) Bukkit.getScheduler().cancelTask(cleanupTaskId);
        flushBatchSafe();
        if (fallbackLogger != null) {
            fallbackLogger.shutdown();
        }
        if (ds != null) ds.close();
    }

    /**
     * Check if database is currently available.
     * @return true if database is operational
     */
    public boolean isDatabaseAvailable() {
        return databaseAvailable;
    }

    /**
     * Execute a SQL statement (DDL/DML) without parameters.
     * Used for schema initialization and index creation.
     *
     * @param sql SQL statement to execute
     */
    private void run(String sql) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        } catch (SQLException e) {
            plugin.getLogger().severe("DB-Init-Error: " + e.getMessage());
            plugin.getLogger().severe("Failed SQL: " + sql);
            e.printStackTrace();
        }
    }

    /**
     * Check if MySQL JDBC driver is available on the classpath.
     * @return true if driver is available
     */
    private boolean isMySqlDriverAvailable() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Generate SQL for time-based WHERE clause (database-agnostic).
     * Uses enum for type-safe time unit handling.
     *
     * @param unit Time unit for the query
     * @return SQL snippet for WHERE clause
     */
    private String getTimeSinceSQL(TimeUnit unit) {
        if (isMySQL) {
            // MySQL: NOW() - INTERVAL ? MINUTE
            return "ts >= NOW() - INTERVAL ? " + unit.getSqlNameUpper();
        } else {
            // SQLite: datetime('now', '-X minutes')
            return "ts >= datetime('now', '-' || ? || ' " + unit.getSqlName() + "')";
        }
    }

    /**
     * Retrieves recent MSPT values from the database for the given time window.
     * @param minutesBack How many minutes back to fetch
     * @return List of MSPT values (oldest first)
     */
    public List<Double> getRecentMspt(int minutesBack) {
        List<Double> values = new ArrayList<>();
        String sql = "SELECT value FROM performance_logs WHERE log_type = 'mspt' " +
                     "AND " + getTimeSinceSQL(TimeUnit.MINUTE) + " ORDER BY ts ASC";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, minutesBack);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getDouble("value"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Query error (getRecentMspt): " + e.getMessage());
            e.printStackTrace();
        }
        return values;
    }

    /**
     * Retrieves average value for a specific metric type within a time range.
     * @param logType The metric type (e.g., "mspt", "heap", "tps")
     * @param minutesBack Time window in minutes
     * @return Average value or 0.0 if no data
     */
    public double getAverageByType(String logType, int minutesBack) {
        String sql = "SELECT AVG(value) as avg_val FROM performance_logs " +
                     "WHERE log_type = ? AND " + getTimeSinceSQL(TimeUnit.MINUTE);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, logType);
            ps.setInt(2, minutesBack);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("avg_val");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Query error (getAverageByType): " + e.getMessage());
            e.printStackTrace();
        }
        return 0.0;
    }

    /**
     * Finds performance spikes where MSPT exceeded a threshold.
     * @param thresholdMspt MSPT threshold
     * @param minutesBack Time window
     * @return Count of spikes
     */
    public int countPerformanceSpikes(double thresholdMspt, int minutesBack) {
        String sql = "SELECT COUNT(*) as spike_count FROM performance_logs " +
                     "WHERE log_type = 'mspt' AND value > ? " +
                     "AND " + getTimeSinceSQL(TimeUnit.MINUTE);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, thresholdMspt);
            ps.setInt(2, minutesBack);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("spike_count");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Query error (countPerformanceSpikes): " + e.getMessage());
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * Clean up old data to prevent database bloat.
     * @param daysToKeep How many days of data to retain
     */
    public void cleanOldData(int daysToKeep) {
        String timeCondition = isMySQL
            ? "ts < NOW() - INTERVAL ? DAY"
            : "ts < datetime('now', '-' || ? || ' days')";
        String sql = "DELETE FROM performance_logs WHERE " + timeCondition;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, daysToKeep);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                plugin.getLogger().info("Old performance logs cleaned: " + deleted + " entries deleted.");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Cleanup error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
