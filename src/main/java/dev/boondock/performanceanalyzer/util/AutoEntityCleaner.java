package dev.boondock.performanceanalyzer.util;

import dev.boondock.performanceanalyzer.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;

import java.util.*;

/**
 * Automatic entity cleaner for lag prevention.
 * Monitors entity counts and removes excess entities when thresholds are exceeded.
 *
 * Features:
 * - Per-world and global entity limits
 * - Configurable entity type priorities
 * - Whitelist/blacklist support
 * - Notification system
 * - Dry-run mode
 *
 * Safety:
 * - Never kills players
 * - Respects entity persistence (named entities, tamed animals, etc.)
 * - Configurable protection for specific entity types
 */
public class AutoEntityCleaner {

    private final Plugin plugin;
    private final PluginConfig config;

    private int taskId = -1;
    private int totalCleaned = 0;

    public AutoEntityCleaner(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Start the auto-cleaner task.
     */
    public void start() {
        if (!config.entityCleanerEnabled()) {
            plugin.getLogger().info("[EntityCleaner] Auto entity cleaner disabled");
            return;
        }

        int intervalSeconds = config.entityCleanerIntervalSeconds();

        this.taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                performCleanup();
            } catch (Exception e) {
                plugin.getLogger().severe("[EntityCleaner] Error during cleanup: " + e.getMessage());
                e.printStackTrace();
            }
        }, 20L * 60, 20L * intervalSeconds).getTaskId(); // 1 minute delay, then periodic

        plugin.getLogger().info("[EntityCleaner] Auto entity cleaner started (interval: " + intervalSeconds + "s)");
        plugin.getLogger().info("[EntityCleaner] Thresholds:");
        plugin.getLogger().info("[EntityCleaner]   World limit: " + config.entityCleanerWorldLimit());
        plugin.getLogger().info("[EntityCleaner]   Per-chunk limit: " + config.entityCleanerChunkLimit());
        plugin.getLogger().info("[EntityCleaner]   Dry run: " + config.entityCleanerDryRun());
    }

    /**
     * Stop the auto-cleaner task.
     */
    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            plugin.getLogger().info("[EntityCleaner] Auto entity cleaner stopped (total cleaned: " + totalCleaned + ")");
        }
    }

    /**
     * Perform cleanup check and removal.
     */
    private void performCleanup() {
        boolean dryRun = config.entityCleanerDryRun();
        int worldLimit = config.entityCleanerWorldLimit();
        int chunkLimit = config.entityCleanerChunkLimit();

        int sessionCleaned = 0;

        for (World world : Bukkit.getWorlds()) {
            // Skip worlds in whitelist
            if (isWorldWhitelisted(world.getName())) {
                continue;
            }

            List<Entity> entities = new ArrayList<>(world.getEntities());

            // Remove players from list
            entities.removeIf(e -> e instanceof Player);

            // Check world limit
            if (entities.size() > worldLimit) {
                plugin.getLogger().warning(String.format(
                    "[EntityCleaner] World '%s' exceeds limit: %d/%d entities",
                    world.getName(), entities.size(), worldLimit
                ));

                int toRemove = entities.size() - worldLimit;
                List<Entity> victims = selectVictimsForRemoval(entities, toRemove);

                if (dryRun) {
                    plugin.getLogger().info(String.format(
                        "[EntityCleaner] [DRY RUN] Would remove %d entities from '%s'",
                        victims.size(), world.getName()
                    ));
                    logEntityBreakdown(victims);
                } else {
                    int removed = removeEntities(victims);
                    sessionCleaned += removed;
                    plugin.getLogger().warning(String.format(
                        "[EntityCleaner] Removed %d entities from '%s'",
                        removed, world.getName()
                    ));
                    logEntityBreakdown(victims);
                }
            }

            // Check per-chunk limits
            Map<String, List<Entity>> entitiesByChunk = groupByChunk(entities);
            for (Map.Entry<String, List<Entity>> entry : entitiesByChunk.entrySet()) {
                String chunkKey = entry.getKey();
                List<Entity> chunkEntities = entry.getValue();

                if (chunkEntities.size() > chunkLimit) {
                    int toRemove = chunkEntities.size() - chunkLimit;
                    List<Entity> victims = selectVictimsForRemoval(chunkEntities, toRemove);

                    if (dryRun) {
                        plugin.getLogger().info(String.format(
                            "[EntityCleaner] [DRY RUN] Would remove %d entities from chunk %s (world: %s)",
                            victims.size(), chunkKey, world.getName()
                        ));
                    } else {
                        int removed = removeEntities(victims);
                        sessionCleaned += removed;
                        plugin.getLogger().warning(String.format(
                            "[EntityCleaner] Removed %d entities from chunk %s (world: %s)",
                            removed, chunkKey, world.getName()
                        ));
                    }
                }
            }
        }

        totalCleaned += sessionCleaned;

        if (sessionCleaned > 0 && !dryRun) {
            // Notify admins
            String message = String.format(
                "§6[AutoCleaner] §eRemoved %d entities (total: %d)",
                sessionCleaned, totalCleaned
            );
            notifyAdmins(message);
        }
    }

    /**
     * Select entities for removal based on priority.
     * Priority (low to high):
     * 1. Dropped items (lowest priority = removed first)
     * 2. Projectiles
     * 3. Monsters
     * 4. Animals (passive mobs)
     * 5. Named entities (protected)
     * 6. Tamed animals (protected)
     * 7. Villagers (protected)
     */
    private List<Entity> selectVictimsForRemoval(List<Entity> candidates, int count) {
        List<Entity> victims = new ArrayList<>();

        // Sort by priority (least valuable first)
        candidates.sort((a, b) -> {
            int priorityA = getEntityPriority(a);
            int priorityB = getEntityPriority(b);
            return Integer.compare(priorityA, priorityB);
        });

        // Select lowest priority entities
        for (Entity entity : candidates) {
            if (victims.size() >= count) break;

            // Skip protected entities
            if (isProtected(entity)) continue;

            // Skip blacklisted types
            if (isTypeBlacklisted(entity.getType())) continue;

            victims.add(entity);
        }

        return victims;
    }

    /**
     * Get entity priority (lower = removed first).
     */
    private int getEntityPriority(Entity entity) {
        // Named entities = highest priority (protected)
        if (entity.customName() != null) return 100;

        // Tamed animals = high priority (protected)
        if (entity instanceof Tameable tameable && tameable.isTamed()) return 90;

        // Villagers and important NPCs
        if (entity instanceof Villager || entity instanceof WanderingTrader) return 80;

        // Item frames, armor stands
        if (entity instanceof ItemFrame || entity instanceof ArmorStand) return 70;

        // Passive mobs
        if (entity instanceof Animals) return 50;

        // Monsters
        if (entity instanceof Monster) return 30;

        // Projectiles
        if (entity instanceof Projectile) return 20;

        // Dropped items (lowest priority)
        if (entity instanceof Item) return 10;

        // Default
        return 40;
    }

    /**
     * Check if entity is protected from removal.
     */
    private boolean isProtected(Entity entity) {
        // Players always protected
        if (entity instanceof Player) return true;

        // Custom name = protected
        if (entity.customName() != null) return true;

        // Tamed = protected
        if (entity instanceof Tameable tameable && tameable.isTamed()) return true;

        // Villagers protected by default
        if (config.entityCleanerProtectVillagers() && entity instanceof Villager) return true;

        // Item frames protected by default
        if (config.entityCleanerProtectItemFrames() && entity instanceof ItemFrame) return true;

        return false;
    }

    /**
     * Check if entity type is blacklisted (never removed).
     */
    private boolean isTypeBlacklisted(EntityType type) {
        List<String> blacklist = config.entityCleanerBlacklist();
        return blacklist.contains(type.name());
    }

    /**
     * Check if world is whitelisted (never cleaned).
     */
    private boolean isWorldWhitelisted(String worldName) {
        List<String> whitelist = config.entityCleanerWorldWhitelist();
        return whitelist.contains(worldName);
    }

    /**
     * Group entities by chunk.
     */
    private Map<String, List<Entity>> groupByChunk(List<Entity> entities) {
        Map<String, List<Entity>> grouped = new HashMap<>();

        for (Entity entity : entities) {
            int chunkX = entity.getLocation().getChunk().getX();
            int chunkZ = entity.getLocation().getChunk().getZ();
            String key = chunkX + "," + chunkZ;

            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }

        return grouped;
    }

    /**
     * Remove entities.
     */
    private int removeEntities(List<Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            try {
                entity.remove();
                removed++;
            } catch (Exception e) {
                plugin.getLogger().warning("[EntityCleaner] Failed to remove entity: " + e.getMessage());
            }
        }
        return removed;
    }

    /**
     * Log breakdown of removed entities.
     */
    private void logEntityBreakdown(List<Entity> entities) {
        Map<EntityType, Integer> breakdown = new HashMap<>();
        for (Entity entity : entities) {
            breakdown.merge(entity.getType(), 1, Integer::sum);
        }

        plugin.getLogger().info("[EntityCleaner] Breakdown:");
        breakdown.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(5)
            .forEach(entry -> plugin.getLogger().info(
                "[EntityCleaner]   - " + entry.getKey().name() + ": " + entry.getValue()
            ));
    }

    /**
     * Notify admins.
     */
    private void notifyAdmins(String message) {
        Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.hasPermission("performance.admin"))
            .forEach(p -> p.sendMessage(message));
    }

    /**
     * Get total entities cleaned.
     */
    public int getTotalCleaned() {
        return totalCleaned;
    }

    /**
     * Reset counter.
     */
    public void resetCounter() {
        totalCleaned = 0;
    }
}
