package dev.boondock.performanceanalyzer.calibrate;

import dev.boondock.performanceanalyzer.integration.ProtocolLibHook;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Collects the distributions {@code /perfcalibrate} needs but the database
 * cannot supply.
 *
 * <p>perf.db stores one row per metric per minute - fine for tick times, but
 * useless for anything spatial: entities per <em>chunk</em> and tile entities
 * per <em>chunk</em> never existed as a series, and packet counts are stored
 * as cumulative totals whose deltas average the spikes away.
 *
 * <p>So this samples on the tick thread once a minute and counts observations
 * into {@link Histogram}s. Cost is one pass over the loaded chunks, the same
 * work {@code WorldStatsManager} already does every five minutes.
 *
 * <p>On Folia the chunk pass is skipped: walking every world's chunk array
 * from one thread is illegal there. Packet peaks are still collected, so
 * calibration degrades to the values it can honestly derive.
 */
public final class CalibrationSampler {

    /** Entities/tiles per chunk: exact counting up to 255, saturating above. */
    private static final int PER_CHUNK_BUCKETS = 256;
    /** Entities per world: 50 per bucket, saturating at 10 000. */
    private static final int WORLD_BUCKETS = 200;
    private static final int WORLD_BUCKET_WIDTH = 50;
    /** Packets per tick: 50 per bucket, saturating at 20 000. */
    private static final int PACKET_BUCKETS = 400;
    private static final int PACKET_BUCKET_WIDTH = 50;

    private static final long SAMPLE_PERIOD_TICKS = 20L * 60;

    private final Plugin plugin;

    /** Players online per sample - decides whether the window saw real use. */
    private final Histogram playersOnline = new Histogram(PER_CHUNK_BUCKETS, 1);
    private final Histogram entitiesPerChunk = new Histogram(PER_CHUNK_BUCKETS, 1);
    private final Histogram tilesPerChunk = new Histogram(PER_CHUNK_BUCKETS, 1);
    private final Histogram entitiesPerWorld = new Histogram(WORLD_BUCKETS, WORLD_BUCKET_WIDTH);
    private final Histogram packetsPerTick = new Histogram(PACKET_BUCKETS, PACKET_BUCKET_WIDTH);

    private volatile ProtocolLibHook packetSource;
    private volatile long startedAtMs;
    private volatile int sampleCount;
    private volatile int lastWorldCount;
    private volatile int lastWorldsWithoutChunks;
    private ScheduledTask task;

    public CalibrationSampler(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Optional; without it no packet threshold can be proposed. */
    public void setPacketSource(ProtocolLibHook hook) {
        this.packetSource = hook;
    }

    public void start() {
        stop();
        startedAtMs = System.currentTimeMillis();
        task = Scheduling.runGlobalRepeating(plugin, this::sample,
                SAMPLE_PERIOD_TICKS, SAMPLE_PERIOD_TICKS);
    }

    public void stop() {
        Scheduling.cancel(task);
        task = null;
    }

    private void sample() {
        try {
            playersOnline.record(Bukkit.getOnlinePlayers().size());

            ProtocolLibHook hook = this.packetSource;
            if (hook != null) {
                long peak = hook.drainPeakPacketsPerTick();
                if (peak > 0) {
                    packetsPerTick.record((int) Math.min(Integer.MAX_VALUE, peak));
                }
            }

            if (!Scheduling.FOLIA) {
                sampleChunks();
            }
            sampleCount++;
        } catch (Exception e) {
            plugin.getLogger().warning("[Calibrate] sampling failed: " + e.getMessage());
        }
    }

    private void sampleChunks() {
        int worlds = 0;
        int worldsWithoutChunks = 0;

        for (World world : Bukkit.getWorlds()) {
            worlds++;
            Chunk[] loaded = world.getLoadedChunks();
            if (loaded.length == 0) {
                worldsWithoutChunks++;
                continue;
            }

            int worldEntities = 0;
            for (Chunk chunk : loaded) {
                int entities = chunk.getEntities().length;
                int tiles = chunk.getTileEntities().length;
                entitiesPerChunk.record(entities);
                tilesPerChunk.record(tiles);
                worldEntities += entities;
            }
            entitiesPerWorld.record(worldEntities);
        }

        lastWorldCount = worlds;
        lastWorldsWithoutChunks = worldsWithoutChunks;
    }

    /* Read API for the calibration engine */

    public Histogram playersOnline() { return playersOnline; }
    public Histogram entitiesPerChunk() { return entitiesPerChunk; }
    public Histogram tilesPerChunk() { return tilesPerChunk; }
    public Histogram entitiesPerWorld() { return entitiesPerWorld; }
    public Histogram packetsPerTick() { return packetsPerTick; }

    /** Minutes since sampling began (0 when never started). */
    public long observedMinutes() {
        long started = startedAtMs;
        if (started <= 0) {
            return 0;
        }
        return (System.currentTimeMillis() - started) / 60_000L;
    }

    public int sampleCount() { return sampleCount; }
    public int lastWorldCount() { return lastWorldCount; }
    public int lastWorldsWithoutChunks() { return lastWorldsWithoutChunks; }
}
