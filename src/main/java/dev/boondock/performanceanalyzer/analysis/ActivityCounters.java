package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.platform.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts lag-relevant <em>activity</em> per chunk in rolling 10-second
 * windows.
 *
 * <p>This is the replacement for the old static-block-count estimator: what
 * costs tick time is not how much redstone <em>exists</em> in a chunk but how
 * often it <em>fires</em>. A 10,000-component idle contraption costs nothing;
 * a two-block clock at 10 Hz costs real time. The same applies to hoppers
 * (item churn, not hopper count) and mob farms (spawn rate).</p>
 *
 * <p>Every event handler is a map lookup plus a {@link LongAdder} increment —
 * they run on hot paths (redstone events fire per component change) and are
 * kept allocation-minimal. Window rotation happens off the tick threads.</p>
 */
public final class ActivityCounters implements Listener {

    /** Hard cap on tracked chunks per window; beyond it activity is counted globally only. */
    private static final int MAX_TRACKED_CHUNKS = 4096;
    private static final long WINDOW_MS = 10_000L;

    /** Per-chunk counters for one window. */
    private static final class Window {
        final LongAdder redstone = new LongAdder();
        final LongAdder pistons = new LongAdder();
        final LongAdder hopperMoves = new LongAdder();
        final LongAdder spawns = new LongAdder();
        final LongAdder chunkLoads = new LongAdder();
        final LongAdder chunkGens = new LongAdder();
    }

    private record ChunkAddress(UUID worldId, String worldName, int x, int z) {
    }

    /**
     * Measured activity rates for one chunk over the last completed window.
     * Rates are per second. The score is a weighted sum — the weights are a
     * documented heuristic, but the underlying rates are real measurements.
     */
    public record HotChunk(
            String worldName,
            int chunkX,
            int chunkZ,
            double redstonePerSec,
            double pistonsPerSec,
            double hopperMovesPerSec,
            double spawnsPerSec,
            double chunkLoadsPerSec,
            double chunkGensPerSec,
            double score) {

        public int blockX() {
            return chunkX << 4;
        }

        public int blockZ() {
            return chunkZ << 4;
        }
    }

    /** Server-wide activity rates (per second) over the last completed window. */
    public record GlobalActivity(
            double redstonePerSec,
            double pistonsPerSec,
            double hopperMovesPerSec,
            double spawnsPerSec,
            double chunkLoadsPerSec,
            double chunkGensPerSec,
            int trackedChunks,
            boolean overflow) {

        public static final GlobalActivity EMPTY = new GlobalActivity(0, 0, 0, 0, 0, 0, 0, false);
    }

    private final Plugin plugin;
    private final AtomicReference<ConcurrentHashMap<ChunkAddress, Window>> current =
            new AtomicReference<>(new ConcurrentHashMap<>());
    private final Window globalWindow = new Window();
    private final LongAdder overflowEvents = new LongAdder();

    private volatile List<HotChunk> hotChunks = List.of();
    private volatile GlobalActivity globalActivity = GlobalActivity.EMPTY;
    private volatile long windowStartedAtMs = System.currentTimeMillis();
    private ScheduledTask rotateTask;

    public ActivityCounters(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        windowStartedAtMs = System.currentTimeMillis();
        rotateTask = Scheduling.runAsyncRepeating(plugin, this::rotateWindow, WINDOW_MS, WINDOW_MS);
    }

    public void stop() {
        Scheduling.cancel(rotateTask);
        rotateTask = null;
        HandlerList.unregisterAll(this);
    }

    /** Top chunks by activity score from the last completed 10s window. */
    public List<HotChunk> hotChunks() {
        return hotChunks;
    }

    /** Server-wide rates from the last completed 10s window. */
    public GlobalActivity globalActivity() {
        return globalActivity;
    }

    /* ------------------------------------------------------------------ */
    /* Event handlers — must stay cheap                                    */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        globalWindow.redstone.increment();
        Window w = windowFor(event.getBlock().getWorld().getUID(),
                event.getBlock().getWorld().getName(),
                event.getBlock().getX() >> 4, event.getBlock().getZ() >> 4);
        if (w != null) {
            w.redstone.increment();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        countPiston(event.getBlock().getWorld().getUID(), event.getBlock().getWorld().getName(),
                event.getBlock().getX() >> 4, event.getBlock().getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        countPiston(event.getBlock().getWorld().getUID(), event.getBlock().getWorld().getName(),
                event.getBlock().getX() >> 4, event.getBlock().getZ() >> 4);
    }

    private void countPiston(UUID worldId, String worldName, int cx, int cz) {
        globalWindow.pistons.increment();
        Window w = windowFor(worldId, worldName, cx, cz);
        if (w != null) {
            w.pistons.increment();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        globalWindow.hopperMoves.increment();
        Location loc = event.getInitiator().getLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        Window w = windowFor(loc.getWorld().getUID(), loc.getWorld().getName(),
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        if (w != null) {
            w.hopperMoves.increment();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL
                && reason != CreatureSpawnEvent.SpawnReason.SPAWNER
                && reason != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                && reason != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) {
            return;
        }
        globalWindow.spawns.increment();
        Location loc = event.getLocation();
        Window w = windowFor(loc.getWorld().getUID(), loc.getWorld().getName(),
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        if (w != null) {
            w.spawns.increment();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Window w = windowFor(event.getWorld().getUID(), event.getWorld().getName(),
                event.getChunk().getX(), event.getChunk().getZ());
        if (event.isNewChunk()) {
            globalWindow.chunkGens.increment();
            if (w != null) {
                w.chunkGens.increment();
            }
        } else {
            globalWindow.chunkLoads.increment();
            if (w != null) {
                w.chunkLoads.increment();
            }
        }
    }

    private Window windowFor(UUID worldId, String worldName, int cx, int cz) {
        ConcurrentHashMap<ChunkAddress, Window> map = current.get();
        ChunkAddress key = new ChunkAddress(worldId, worldName, cx, cz);
        Window existing = map.get(key);
        if (existing != null) {
            return existing;
        }
        if (map.size() >= MAX_TRACKED_CHUNKS) {
            overflowEvents.increment();
            return null;
        }
        return map.computeIfAbsent(key, k -> new Window());
    }

    /* ------------------------------------------------------------------ */
    /* Window rotation (async)                                             */
    /* ------------------------------------------------------------------ */

    private void rotateWindow() {
        ConcurrentHashMap<ChunkAddress, Window> finished = current.getAndSet(new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        double seconds = Math.max(1.0, (now - windowStartedAtMs) / 1000.0);
        windowStartedAtMs = now;

        boolean overflow = overflowEvents.sumThenReset() > 0;
        this.globalActivity = new GlobalActivity(
                globalWindow.redstone.sumThenReset() / seconds,
                globalWindow.pistons.sumThenReset() / seconds,
                globalWindow.hopperMoves.sumThenReset() / seconds,
                globalWindow.spawns.sumThenReset() / seconds,
                globalWindow.chunkLoads.sumThenReset() / seconds,
                globalWindow.chunkGens.sumThenReset() / seconds,
                finished.size(),
                overflow);

        List<HotChunk> ranked = new ArrayList<>(Math.min(64, finished.size()));
        for (var entry : finished.entrySet()) {
            Window w = entry.getValue();
            double redstone = w.redstone.sum() / seconds;
            double pistons = w.pistons.sum() / seconds;
            double hoppers = w.hopperMoves.sum() / seconds;
            double spawns = w.spawns.sum() / seconds;
            double loads = w.chunkLoads.sum() / seconds;
            double gens = w.chunkGens.sum() / seconds;
            // Weighted activity score. Weights approximate relative tick cost
            // per event: hopper item moves and spawner output are the classic
            // heavy hitters, chunk generation dwarfs everything else.
            double score = redstone + pistons * 2 + hoppers * 3 + spawns * 4 + loads * 2 + gens * 20;
            if (score <= 0) {
                continue;
            }
            ChunkAddress address = entry.getKey();
            ranked.add(new HotChunk(address.worldName(), address.x(), address.z(),
                    redstone, pistons, hoppers, spawns, loads, gens, score));
        }
        ranked.sort(Comparator.comparingDouble(HotChunk::score).reversed());
        this.hotChunks = ranked.size() > 25 ? List.copyOf(ranked.subList(0, 25)) : List.copyOf(ranked);
    }
}
