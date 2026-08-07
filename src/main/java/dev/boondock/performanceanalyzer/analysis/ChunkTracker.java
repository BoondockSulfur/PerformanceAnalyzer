package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.platform.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks chunk loading statistics and identifies problematic chunks.
 */
public class ChunkTracker implements Listener {

    private final Plugin plugin;

    // Track chunk load times (approximation via event timing)
    private final Map<String, ChunkLoadStats> chunkLoadHistory = new ConcurrentHashMap<>();

    // Track per-world chunk statistics
    private final Map<String, WorldChunkStats> worldStats = new ConcurrentHashMap<>();

    // Global counters
    private final AtomicLong totalLoads = new AtomicLong(0);
    private final AtomicLong totalUnloads = new AtomicLong(0);
    private final AtomicInteger loadsThisMinute = new AtomicInteger(0);

    // Slow chunk threshold (chunks that might cause lag)
    private static final int TILE_ENTITY_WARNING = 50;
    private static final int ENTITY_WARNING = 30;

    public ChunkTracker(Plugin plugin) {
        this.plugin = plugin;

        // Reset per-minute counter every minute (counters are concurrent,
        // so this can run off the tick threads)
        Scheduling.runAsyncRepeating(plugin, () -> loadsThisMinute.set(0), 60_000L, 60_000L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        String worldName = chunk.getWorld().getName();

        totalLoads.incrementAndGet();
        loadsThisMinute.incrementAndGet();

        // Update world stats
        worldStats.computeIfAbsent(worldName, k -> new WorldChunkStats())
                .recordLoad(event.isNewChunk());

        // Track if this is a potentially problematic chunk
        int tileEntities = chunk.getTileEntities().length;
        int entities = chunk.getEntities().length;

        if (tileEntities >= TILE_ENTITY_WARNING || entities >= ENTITY_WARNING) {
            String key = worldName + ":" + chunk.getX() + "," + chunk.getZ();
            chunkLoadHistory.computeIfAbsent(key, k -> new ChunkLoadStats(
                    worldName, chunk.getX(), chunk.getZ()
            )).recordLoad(tileEntities, entities);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        totalUnloads.incrementAndGet();

        String worldName = event.getChunk().getWorld().getName();
        WorldChunkStats stats = worldStats.get(worldName);
        if (stats != null) {
            stats.recordUnload();
        }
    }

    /**
     * Get current chunk statistics summary.
     */
    public ChunkSummary getSummary() {
        int totalLoaded = 0;
        int totalForceLoaded = 0;
        Map<String, Integer> perWorldLoaded = new HashMap<>();

        for (World world : Bukkit.getWorlds()) {
            int loaded = world.getLoadedChunks().length;
            int forceLoaded = world.getForceLoadedChunks().size();
            totalLoaded += loaded;
            totalForceLoaded += forceLoaded;
            perWorldLoaded.put(world.getName(), loaded);
        }

        return new ChunkSummary(
                totalLoaded,
                totalForceLoaded,
                perWorldLoaded,
                totalLoads.get(),
                totalUnloads.get(),
                loadsThisMinute.get()
        );
    }

    /**
     * Get potentially problematic chunks (high tile entity or entity count).
     */
    public List<ProblematicChunk> getProblematicChunks(int limit) {
        List<ProblematicChunk> problematic = new ArrayList<>();

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                int tileEntities = chunk.getTileEntities().length;
                int entities = chunk.getEntities().length;

                if (tileEntities >= TILE_ENTITY_WARNING || entities >= ENTITY_WARNING) {
                    ChunkProblemType type = tileEntities >= TILE_ENTITY_WARNING
                            ? ChunkProblemType.HIGH_TILE_ENTITIES
                            : ChunkProblemType.HIGH_ENTITIES;

                    problematic.add(new ProblematicChunk(
                            world.getName(),
                            chunk.getX(),
                            chunk.getZ(),
                            entities,
                            tileEntities,
                            chunk.isForceLoaded(),
                            type
                    ));
                }
            }
        }

        // Sort by combined score (entities + tile entities)
        problematic.sort((a, b) -> Integer.compare(
                b.entityCount() + b.tileEntityCount(),
                a.entityCount() + a.tileEntityCount()
        ));

        return problematic.stream().limit(limit).toList();
    }

    /**
     * Get chunks that load frequently (might indicate chunk thrashing).
     */
    public List<FrequentlyLoadedChunk> getFrequentlyLoadedChunks(int limit) {
        return chunkLoadHistory.values().stream()
                .filter(s -> s.loadCount.get() > 5)
                .map(s -> new FrequentlyLoadedChunk(
                        s.worldName,
                        s.chunkX,
                        s.chunkZ,
                        s.loadCount.get(),
                        s.maxTileEntities.get(),
                        s.maxEntities.get()
                ))
                .sorted((a, b) -> Integer.compare(b.loadCount(), a.loadCount()))
                .limit(limit)
                .toList();
    }

    /**
     * Get per-world chunk statistics.
     */
    public Map<String, WorldChunkInfo> getPerWorldStats() {
        Map<String, WorldChunkInfo> result = new HashMap<>();

        for (World world : Bukkit.getWorlds()) {
            WorldChunkStats stats = worldStats.get(world.getName());
            int loaded = world.getLoadedChunks().length;
            int forceLoaded = world.getForceLoadedChunks().size();

            result.put(world.getName(), new WorldChunkInfo(
                    loaded,
                    forceLoaded,
                    stats != null ? stats.totalLoads.get() : 0,
                    stats != null ? stats.totalUnloads.get() : 0,
                    stats != null ? stats.newChunksGenerated.get() : 0
            ));
        }

        return result;
    }

    /**
     * Clear tracking history.
     */
    public void clearHistory() {
        chunkLoadHistory.clear();
        worldStats.clear();
        totalLoads.set(0);
        totalUnloads.set(0);
    }

    // Helper classes

    private static class ChunkLoadStats {
        final String worldName;
        final int chunkX;
        final int chunkZ;
        final AtomicInteger loadCount = new AtomicInteger(0);
        final AtomicInteger maxTileEntities = new AtomicInteger(0);
        final AtomicInteger maxEntities = new AtomicInteger(0);

        ChunkLoadStats(String worldName, int chunkX, int chunkZ) {
            this.worldName = worldName;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        void recordLoad(int tileEntities, int entities) {
            loadCount.incrementAndGet();
            maxTileEntities.updateAndGet(v -> Math.max(v, tileEntities));
            maxEntities.updateAndGet(v -> Math.max(v, entities));
        }
    }

    private static class WorldChunkStats {
        final AtomicLong totalLoads = new AtomicLong(0);
        final AtomicLong totalUnloads = new AtomicLong(0);
        final AtomicLong newChunksGenerated = new AtomicLong(0);

        void recordLoad(boolean isNew) {
            totalLoads.incrementAndGet();
            if (isNew) newChunksGenerated.incrementAndGet();
        }

        void recordUnload() {
            totalUnloads.incrementAndGet();
        }
    }

    // Public record types

    public record ChunkSummary(
            int totalLoaded,
            int totalForceLoaded,
            Map<String, Integer> perWorldLoaded,
            long totalLoadsAllTime,
            long totalUnloadsAllTime,
            int loadsThisMinute
    ) {}

    public record ProblematicChunk(
            String worldName,
            int chunkX,
            int chunkZ,
            int entityCount,
            int tileEntityCount,
            boolean forceLoaded,
            ChunkProblemType problemType
    ) {
        public int blockX() { return chunkX * 16; }
        public int blockZ() { return chunkZ * 16; }
    }

    public record FrequentlyLoadedChunk(
            String worldName,
            int chunkX,
            int chunkZ,
            int loadCount,
            int maxTileEntities,
            int maxEntities
    ) {
        public int blockX() { return chunkX * 16; }
        public int blockZ() { return chunkZ * 16; }
    }

    public record WorldChunkInfo(
            int loadedChunks,
            int forceLoadedChunks,
            long totalLoads,
            long totalUnloads,
            long newChunksGenerated
    ) {}

    public enum ChunkProblemType {
        HIGH_TILE_ENTITIES("Viele Block-Entities"),
        HIGH_ENTITIES("Viele Entities");

        private final String displayName;

        ChunkProblemType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
