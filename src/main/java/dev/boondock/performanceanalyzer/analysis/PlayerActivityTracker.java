package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

import dev.boondock.performanceanalyzer.util.Constants;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player activities to identify potential lag sources.
 * Monitors block interactions, movements, commands, and entity interactions.
 *
 * PERFORMANCE OPTIMIZED:
 * - Movement tracking uses sampling (only every Nth move or >2 blocks)
 * - Can be disabled via config
 * - Automatic cleanup of old data
 */
public class PlayerActivityTracker implements Listener {

    private final Plugin plugin;
    private final PluginConfig config;

    // Track activity counts per player (last 10 seconds)
    private final Map<UUID, PlayerActivity> playerActivities = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActivityReset = new ConcurrentHashMap<>();

    // Movement sampling to reduce overhead
    private final Map<UUID, Location> lastTrackedLocation = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> movementCounter = new ConcurrentHashMap<>();

    private static final long ACTIVITY_WINDOW_MS = 10000; // 10 seconds
    private static final int MOVEMENT_SAMPLE_RATE = 10; // Only track every 10th move
    private static final double MOVEMENT_MIN_DISTANCE = 2.0; // Or if moved >2 blocks

    public PlayerActivityTracker(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        getActivity(event.getPlayer()).blockBreaks++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        getActivity(event.getPlayer()).blockPlaces++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Skip if player tracking disabled
        if (!config.lagAnalysisPlayerTracking()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Location to = event.getTo();
        Location from = event.getFrom();

        // Only count if actually moved (not just head rotation)
        if (to == null || from.distanceSquared(to) < 0.01) {
            return;
        }

        // PERFORMANCE OPTIMIZATION: Sampling strategy
        // Option 1: Count every Nth movement
        int count = movementCounter.merge(playerId, 1, Integer::sum);
        if (count % MOVEMENT_SAMPLE_RATE == 0) {
            getActivity(player).movements++;
            movementCounter.put(playerId, 0);
            return;
        }

        // Option 2: Or if significant distance (>2 blocks from last tracked position)
        Location lastLoc = lastTrackedLocation.get(playerId);
        if (lastLoc != null && to.getWorld().equals(lastLoc.getWorld())) {
            if (lastLoc.distance(to) > MOVEMENT_MIN_DISTANCE) {
                getActivity(player).movements++;
                lastTrackedLocation.put(playerId, to.clone());
            }
        } else {
            lastTrackedLocation.put(playerId, to.clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        getActivity(event.getPlayer()).commands++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        getActivity(event.getPlayer()).interactions++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            getActivity((Player) event.getDamager()).entityInteractions++;
        }
    }

    /**
     * Get or create activity tracker for a player.
     * Automatically resets if activity window has passed.
     */
    private PlayerActivity getActivity(Player player) {
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Reset if window expired
        Long lastReset = lastActivityReset.get(playerId);
        if (lastReset == null || (now - lastReset) > ACTIVITY_WINDOW_MS) {
            playerActivities.put(playerId, new PlayerActivity(player.getName()));
            lastActivityReset.put(playerId, now);
        }

        return playerActivities.computeIfAbsent(playerId, k -> new PlayerActivity(player.getName()));
    }

    /**
     * Get current activity snapshot for all players.
     * Returns players sorted by total activity (most active first).
     */
    public List<PlayerActivitySnapshot> getActivitySnapshot() {
        List<PlayerActivitySnapshot> snapshots = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, PlayerActivity> entry : playerActivities.entrySet()) {
            UUID playerId = entry.getKey();
            PlayerActivity activity = entry.getValue();

            // Only include recent activity
            Long lastReset = lastActivityReset.get(playerId);
            if (lastReset != null && (now - lastReset) <= ACTIVITY_WINDOW_MS) {
                snapshots.add(new PlayerActivitySnapshot(
                    activity.playerName,
                    activity.blockBreaks,
                    activity.blockPlaces,
                    activity.movements,
                    activity.commands,
                    activity.interactions,
                    activity.entityInteractions
                ));
            }
        }

        // Sort by total activity (descending)
        snapshots.sort((a, b) -> Integer.compare(b.getTotalActivity(), a.getTotalActivity()));

        return snapshots;
    }

    /**
     * Get top N most active players.
     */
    public List<PlayerActivitySnapshot> getTopActivePlayers(int limit) {
        List<PlayerActivitySnapshot> all = getActivitySnapshot();
        return all.subList(0, Math.min(limit, all.size()));
    }

    /**
     * Clear all activity data.
     */
    public void clearActivity() {
        playerActivities.clear();
        lastActivityReset.clear();
        lastTrackedLocation.clear();
        movementCounter.clear();
    }

    /**
     * Cleanup data for a specific player (on quit).
     */
    public void cleanupPlayer(UUID playerId) {
        playerActivities.remove(playerId);
        lastActivityReset.remove(playerId);
        lastTrackedLocation.remove(playerId);
        movementCounter.remove(playerId);
    }

    /**
     * Internal class to track player activity counts.
     */
    private static class PlayerActivity {
        final String playerName;
        int blockBreaks = 0;
        int blockPlaces = 0;
        int movements = 0;
        int commands = 0;
        int interactions = 0;
        int entityInteractions = 0;

        PlayerActivity(String playerName) {
            this.playerName = playerName;
        }
    }

    /**
     * Immutable snapshot of player activity.
     */
    public static class PlayerActivitySnapshot {
        public final String playerName;
        public final int blockBreaks;
        public final int blockPlaces;
        public final int movements;
        public final int commands;
        public final int interactions;
        public final int entityInteractions;

        public PlayerActivitySnapshot(String playerName, int blockBreaks, int blockPlaces,
                                     int movements, int commands, int interactions, int entityInteractions) {
            this.playerName = playerName;
            this.blockBreaks = blockBreaks;
            this.blockPlaces = blockPlaces;
            this.movements = movements;
            this.commands = commands;
            this.interactions = interactions;
            this.entityInteractions = entityInteractions;
        }

        public int getTotalActivity() {
            // Weighted total using centralized constants (some actions are more expensive than others)
            return blockBreaks * Constants.ACTIVITY_WEIGHT_BLOCK_BREAK
                 + blockPlaces * Constants.ACTIVITY_WEIGHT_BLOCK_PLACE
                 + movements * Constants.ACTIVITY_WEIGHT_MOVEMENT
                 + commands * Constants.ACTIVITY_WEIGHT_COMMAND
                 + interactions * Constants.ACTIVITY_WEIGHT_INTERACTION
                 + entityInteractions * Constants.ACTIVITY_WEIGHT_INTERACTION;
        }

        public String getActivitySummary() {
            return String.format("Blocks: %d/%d | Move: %d | Cmd: %d | Int: %d | Entity: %d | Score: %d",
                blockBreaks, blockPlaces, movements, commands, interactions, entityInteractions, getTotalActivity());
        }
    }
}
