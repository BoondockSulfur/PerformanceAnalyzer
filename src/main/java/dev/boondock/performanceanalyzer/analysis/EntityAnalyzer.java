package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;

import java.util.*;

/**
 * Analyzes entity distribution and identifies potential performance issues.
 * Thresholds are now configurable via config.yml (v2.0.0+)
 */
public class EntityAnalyzer {

    private final Plugin plugin;
    private final PluginConfig config;

    public EntityAnalyzer(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Get a complete entity analysis for the server.
     */
    public EntityAnalysis analyze() {
        Map<EntityType, Integer> globalCounts = new EnumMap<>(EntityType.class);
        Map<EntityCategory, Integer> categoryCounts = new EnumMap<>(EntityCategory.class);
        List<EntityHotspot> hotspots = new ArrayList<>();
        int totalEntities = 0;

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                EntityType type = entity.getType();
                globalCounts.merge(type, 1, Integer::sum);
                categoryCounts.merge(categorize(type), 1, Integer::sum);
                totalEntities++;
            }

            // Find hotspots in this world (using configurable threshold)
            int chunkWarningThreshold = config.chunkEntityWarning();
            for (Chunk chunk : world.getLoadedChunks()) {
                Entity[] entities = chunk.getEntities();
                if (entities.length >= chunkWarningThreshold) {
                    Map<EntityType, Integer> chunkBreakdown = new EnumMap<>(EntityType.class);
                    for (Entity e : entities) {
                        chunkBreakdown.merge(e.getType(), 1, Integer::sum);
                    }

                    EntityType dominant = chunkBreakdown.entrySet().stream()
                            .max(Comparator.comparingInt(Map.Entry::getValue))
                            .map(Map.Entry::getKey)
                            .orElse(EntityType.UNKNOWN);

                    // Determine severity based on config thresholds
                    int chunkCriticalThreshold = config.chunkEntityCritical();
                    HotspotSeverity severity = entities.length >= chunkCriticalThreshold
                            ? HotspotSeverity.CRITICAL
                            : HotspotSeverity.WARNING;

                    hotspots.add(new EntityHotspot(
                            world.getName(),
                            chunk.getX() * 16,
                            chunk.getZ() * 16,
                            entities.length,
                            dominant,
                            chunkBreakdown.getOrDefault(dominant, 0),
                            severity
                    ));
                }
            }
        }

        // Sort by count descending
        List<Map.Entry<EntityType, Integer>> topEntities = globalCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .toList();

        // Sort hotspots by entity count
        hotspots.sort((a, b) -> Integer.compare(b.entityCount(), a.entityCount()));

        return new EntityAnalysis(
                totalEntities,
                topEntities,
                categoryCounts,
                hotspots.stream().limit(10).toList()
        );
    }

    /**
     * Get entity breakdown for a specific world.
     */
    public WorldEntityAnalysis analyzeWorld(World world) {
        Map<EntityType, Integer> counts = new EnumMap<>(EntityType.class);
        Map<EntityCategory, Integer> categories = new EnumMap<>(EntityCategory.class);

        for (Entity entity : world.getEntities()) {
            counts.merge(entity.getType(), 1, Integer::sum);
            categories.merge(categorize(entity.getType()), 1, Integer::sum);
        }

        int totalEntities = world.getEntities().size();
        int worldCriticalThreshold = config.worldEntityCritical();
        int worldWarningThreshold = config.worldEntityWarning();
        HealthStatus status = totalEntities >= worldCriticalThreshold ? HealthStatus.CRITICAL
                : totalEntities >= worldWarningThreshold ? HealthStatus.WARNING
                : HealthStatus.HEALTHY;

        List<Map.Entry<EntityType, Integer>> topEntities = counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .toList();

        return new WorldEntityAnalysis(
                world.getName(),
                totalEntities,
                status,
                topEntities,
                categories
        );
    }

    /**
     * Categorize an entity type.
     */
    public EntityCategory categorize(EntityType type) {
        // Players
        if (type == EntityType.PLAYER) return EntityCategory.PLAYER;

        // Check if it's a hostile mob
        Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass != null) {
            if (Monster.class.isAssignableFrom(entityClass)) return EntityCategory.HOSTILE;
            if (Animals.class.isAssignableFrom(entityClass)) return EntityCategory.PASSIVE;
            if (WaterMob.class.isAssignableFrom(entityClass)) return EntityCategory.WATER;
            if (Ambient.class.isAssignableFrom(entityClass)) return EntityCategory.AMBIENT;
            if (Item.class.isAssignableFrom(entityClass)) return EntityCategory.ITEM;
            if (ExperienceOrb.class.isAssignableFrom(entityClass)) return EntityCategory.XP_ORB;
            if (Projectile.class.isAssignableFrom(entityClass)) return EntityCategory.PROJECTILE;
            if (Vehicle.class.isAssignableFrom(entityClass)) return EntityCategory.VEHICLE;
        }

        // Dropped items and XP orbs by name
        if (type == EntityType.ITEM) return EntityCategory.ITEM;
        if (type == EntityType.EXPERIENCE_ORB) return EntityCategory.XP_ORB;

        // Tile entities / technical
        if (type == EntityType.ARMOR_STAND || type == EntityType.ITEM_FRAME ||
            type == EntityType.GLOW_ITEM_FRAME || type == EntityType.PAINTING ||
            type == EntityType.LEASH_KNOT) {
            return EntityCategory.DECORATION;
        }

        return EntityCategory.OTHER;
    }

    /**
     * Entity categories for grouping.
     */
    public enum EntityCategory {
        PLAYER,
        HOSTILE,
        PASSIVE,
        WATER,
        AMBIENT,
        ITEM,
        XP_ORB,
        PROJECTILE,
        VEHICLE,
        DECORATION,
        OTHER;

        public String getDisplayName() {
            // Returns the enum name formatted nicely - actual translation happens in command
            String name = name().toLowerCase().replace("_", " ");
            return name.substring(0, 1).toUpperCase() + name.substring(1);
        }

        public String getKey() {
            return "entitystats.category_" + name().toLowerCase();
        }
    }

    public enum HotspotSeverity {
        WARNING, CRITICAL
    }

    public enum HealthStatus {
        HEALTHY, WARNING, CRITICAL
    }

    /**
     * Complete entity analysis.
     */
    public record EntityAnalysis(
            int totalEntities,
            List<Map.Entry<EntityType, Integer>> topEntities,
            Map<EntityCategory, Integer> byCategory,
            List<EntityHotspot> hotspots
    ) {}

    /**
     * World-specific entity analysis.
     */
    public record WorldEntityAnalysis(
            String worldName,
            int totalEntities,
            HealthStatus status,
            List<Map.Entry<EntityType, Integer>> topEntities,
            Map<EntityCategory, Integer> byCategory
    ) {}

    /**
     * Entity hotspot (chunk with many entities).
     */
    public record EntityHotspot(
            String worldName,
            int x,
            int z,
            int entityCount,
            EntityType dominantType,
            int dominantCount,
            HotspotSeverity severity
    ) {}
}
