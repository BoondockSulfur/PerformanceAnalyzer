package dev.boondock.performanceanalyzer.analysis;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-world performance statistics.
 * Tracks entity counts, chunk counts, player counts, and entity density.
 * Now includes trend analysis over time.
 */
public class WorldStatsManager {

    private final Plugin plugin;

    // Trend tracking: worldName -> List of snapshots (max 288 = 24 hours @ 5min intervals)
    private final Map<String, List<WorldStatsSnapshot>> trendHistory = new ConcurrentHashMap<>();
    private static final int MAX_SNAPSHOTS = 288; // 24 hours @ 5 minutes

    public WorldStatsManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Get statistics for all worlds.
     */
    public List<WorldStats> getAllWorldStats() {
        List<WorldStats> stats = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            stats.add(getWorldStats(world));
        }
        // Sort by entity count (highest first)
        stats.sort((a, b) -> Integer.compare(b.entityCount(), a.entityCount()));
        return stats;
    }

    /**
     * Get statistics for a specific world.
     */
    public WorldStats getWorldStats(World world) {
        int entityCount = world.getEntities().size();
        int playerCount = world.getPlayers().size();
        int loadedChunks = world.getLoadedChunks().length;
        int tileEntityCount = countTileEntities(world);

        // Calculate entity density (entities per loaded chunk)
        double entityDensity = loadedChunks > 0 ? (double) entityCount / loadedChunks : 0;

        // Get top entity types in this world
        Map<EntityType, Integer> entityBreakdown = getEntityBreakdown(world);
        List<Map.Entry<EntityType, Integer>> topEntities = entityBreakdown.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .toList();

        return new WorldStats(
                world.getName(),
                world.getEnvironment(),
                entityCount,
                playerCount,
                loadedChunks,
                tileEntityCount,
                entityDensity,
                topEntities
        );
    }

    /**
     * Count tile entities (block entities like chests, hoppers, etc.)
     */
    private int countTileEntities(World world) {
        int count = 0;
        for (Chunk chunk : world.getLoadedChunks()) {
            count += chunk.getTileEntities().length;
        }
        return count;
    }

    /**
     * Get breakdown of entity types in a world.
     */
    public Map<EntityType, Integer> getEntityBreakdown(World world) {
        Map<EntityType, Integer> counts = new EnumMap<>(EntityType.class);
        for (Entity entity : world.getEntities()) {
            counts.merge(entity.getType(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Find chunks with high entity density.
     */
    public List<ChunkEntityInfo> getHighDensityChunks(World world, int limit) {
        List<ChunkEntityInfo> chunkInfos = new ArrayList<>();

        for (Chunk chunk : world.getLoadedChunks()) {
            Entity[] entities = chunk.getEntities();
            if (entities.length > 0) {
                chunkInfos.add(new ChunkEntityInfo(
                        world.getName(),
                        chunk.getX(),
                        chunk.getZ(),
                        entities.length,
                        chunk.getTileEntities().length
                ));
            }
        }

        // Sort by entity count and return top chunks
        chunkInfos.sort((a, b) -> Integer.compare(b.entityCount(), a.entityCount()));
        return chunkInfos.stream().limit(limit).toList();
    }

    /**
     * Get overall server entity statistics.
     */
    public ServerEntityStats getServerStats() {
        int totalEntities = 0;
        int totalPlayers = 0;
        int totalChunks = 0;
        int totalTileEntities = 0;

        for (World world : Bukkit.getWorlds()) {
            totalEntities += world.getEntities().size();
            totalPlayers += world.getPlayers().size();
            totalChunks += world.getLoadedChunks().length;
            totalTileEntities += countTileEntities(world);
        }

        double avgDensity = totalChunks > 0 ? (double) totalEntities / totalChunks : 0;

        return new ServerEntityStats(
                Bukkit.getWorlds().size(),
                totalEntities,
                totalPlayers,
                totalChunks,
                totalTileEntities,
                avgDensity
        );
    }

    /**
     * World statistics record.
     */
    public record WorldStats(
            String worldName,
            World.Environment environment,
            int entityCount,
            int playerCount,
            int loadedChunks,
            int tileEntityCount,
            double entityDensity,
            List<Map.Entry<EntityType, Integer>> topEntities
    ) {
        public String getEnvironmentName() {
            return switch (environment) {
                case NORMAL -> "Overworld";
                case NETHER -> "Nether";
                case THE_END -> "End";
                case CUSTOM -> "Custom";
            };
        }
    }

    /**
     * Chunk entity info record.
     */
    public record ChunkEntityInfo(
            String worldName,
            int chunkX,
            int chunkZ,
            int entityCount,
            int tileEntityCount
    ) {
        public int blockX() { return chunkX * 16; }
        public int blockZ() { return chunkZ * 16; }
    }

    /**
     * Server-wide entity statistics.
     */
    public record ServerEntityStats(
            int worldCount,
            int totalEntities,
            int totalPlayers,
            int totalChunks,
            int totalTileEntities,
            double avgEntityDensity
    ) {}

    // ==================== TREND ANALYSIS ====================

    /**
     * Record a snapshot of current world stats for trend analysis.
     * Should be called periodically (e.g., every 5 minutes).
     */
    public void recordSnapshot(World world) {
        WorldStats stats = getWorldStats(world);
        String worldName = world.getName();

        List<WorldStatsSnapshot> history = trendHistory.computeIfAbsent(
            worldName, k -> Collections.synchronizedList(new ArrayList<>())
        );

        synchronized (history) {
            history.add(new WorldStatsSnapshot(
                stats.entityCount(),
                stats.loadedChunks(),
                stats.tileEntityCount(),
                stats.entityDensity(),
                System.currentTimeMillis()
            ));

            // Keep only last MAX_SNAPSHOTS entries
            while (history.size() > MAX_SNAPSHOTS) {
                history.remove(0);
            }
        }
    }

    /**
     * Record snapshots for all loaded worlds.
     */
    public void recordAllSnapshots() {
        for (World world : Bukkit.getWorlds()) {
            recordSnapshot(world);
        }
    }

    /**
     * Analyze trend for a specific world over a given period.
     *
     * @param worldName World name to analyze
     * @param periodMs Period to analyze (in milliseconds)
     * @return Trend analysis or null if not enough data
     */
    public TrendAnalysis analyzeTrend(String worldName, long periodMs) {
        List<WorldStatsSnapshot> history = trendHistory.get(worldName);
        if (history == null || history.size() < 2) {
            return null; // Not enough data
        }

        long cutoff = System.currentTimeMillis() - periodMs;
        List<WorldStatsSnapshot> relevant = history.stream()
            .filter(s -> s.timestamp >= cutoff)
            .toList();

        if (relevant.size() < 2) {
            return null; // Not enough data in time period
        }

        // Calculate statistics
        int[] entityCounts = relevant.stream().mapToInt(s -> s.entityCount).toArray();
        int[] chunkCounts = relevant.stream().mapToInt(s -> s.loadedChunks).toArray();
        int[] tileCounts = relevant.stream().mapToInt(s -> s.tileEntityCount).toArray();
        double[] densities = relevant.stream().mapToDouble(s -> s.entityDensity).toArray();

        WorldStatsSnapshot first = relevant.get(0);
        WorldStatsSnapshot last = relevant.get(relevant.size() - 1);

        // Calculate change rates (entities per hour)
        double hoursElapsed = (last.timestamp - first.timestamp) / 3600000.0;
        if (hoursElapsed < 0.01) hoursElapsed = 0.01; // Prevent division by zero

        double entityChangeRate = (last.entityCount - first.entityCount) / hoursElapsed;
        double chunkChangeRate = (last.loadedChunks - first.loadedChunks) / hoursElapsed;
        double tileChangeRate = (last.tileEntityCount - first.tileEntityCount) / hoursElapsed;

        return new TrendAnalysis(
            worldName,
            periodMs,
            relevant.size(),
            calculateAvg(entityCounts),
            calculateMin(entityCounts),
            calculateMax(entityCounts),
            entityChangeRate,
            calculateAvg(chunkCounts),
            calculateAvg(tileCounts),
            calculateAvg(densities),
            getTrendDirection(entityCounts),
            generateTrendSummary(worldName, entityChangeRate, last.entityCount, relevant.size())
        );
    }

    /**
     * Get trend analysis for all worlds.
     */
    public Map<String, TrendAnalysis> analyzeTrendsForAllWorlds(long periodMs) {
        Map<String, TrendAnalysis> trends = new HashMap<>();
        for (String worldName : trendHistory.keySet()) {
            TrendAnalysis trend = analyzeTrend(worldName, periodMs);
            if (trend != null) {
                trends.put(worldName, trend);
            }
        }
        return trends;
    }

    /**
     * Clear trend history for a world.
     */
    public void clearTrendHistory(String worldName) {
        trendHistory.remove(worldName);
    }

    /**
     * Clear all trend history.
     */
    public void clearAllTrendHistory() {
        trendHistory.clear();
    }

    // Helper methods for trend analysis

    private double calculateAvg(int[] values) {
        return Arrays.stream(values).average().orElse(0.0);
    }

    private double calculateAvg(double[] values) {
        return Arrays.stream(values).average().orElse(0.0);
    }

    private int calculateMin(int[] values) {
        return Arrays.stream(values).min().orElse(0);
    }

    private int calculateMax(int[] values) {
        return Arrays.stream(values).max().orElse(0);
    }

    private String getTrendDirection(int[] values) {
        if (values.length < 2) return "STABLE";

        int first = values[0];
        int last = values[values.length - 1];
        double change = ((double) last - first) / first;

        if (change > 0.1) return "INCREASING"; // +10%
        if (change < -0.1) return "DECREASING"; // -10%
        return "STABLE";
    }

    private String generateTrendSummary(String worldName, double entityChangeRate, int currentEntities, int samples) {
        StringBuilder summary = new StringBuilder();
        summary.append("World '").append(worldName).append("': ");

        if (entityChangeRate > 50) {
            summary.append("Entity count increasing rapidly (+").append(String.format("%.0f", entityChangeRate))
                   .append("/hour). Possible mob farm or entity accumulation.");
        } else if (entityChangeRate > 10) {
            summary.append("Entity count increasing moderately (+").append(String.format("%.0f", entityChangeRate))
                   .append("/hour).");
        } else if (entityChangeRate < -50) {
            summary.append("Entity count decreasing rapidly (").append(String.format("%.0f", entityChangeRate))
                   .append("/hour). Possible cleanup or chunk unloading.");
        } else if (entityChangeRate < -10) {
            summary.append("Entity count decreasing (").append(String.format("%.0f", entityChangeRate))
                   .append("/hour).");
        } else {
            summary.append("Entity count stable (").append(String.format("%.1f", entityChangeRate))
                   .append("/hour). Current: ").append(currentEntities).append(" entities.");
        }

        summary.append(" [").append(samples).append(" samples]");
        return summary.toString();
    }

    /**
     * World stats snapshot for trend tracking.
     */
    public record WorldStatsSnapshot(
            int entityCount,
            int loadedChunks,
            int tileEntityCount,
            double entityDensity,
            long timestamp
    ) {}

    /**
     * Trend analysis result.
     */
    public record TrendAnalysis(
            String worldName,
            long periodMs,
            int sampleCount,
            double avgEntityCount,
            int minEntityCount,
            int maxEntityCount,
            double entityChangeRate,
            double avgChunkCount,
            double avgTileEntityCount,
            double avgEntityDensity,
            String trendDirection,
            String summary
    ) {
        public String getFormattedPeriod() {
            long hours = periodMs / 3600000;
            if (hours >= 24) {
                return (hours / 24) + " day(s)";
            }
            return hours + " hour(s)";
        }
    }
}
