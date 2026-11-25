package dev.boondock.performanceanalyzer.metrics;

import dev.boondock.performanceanalyzer.platform.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls JVM garbage-collection and memory-pool beans once per second.
 *
 * <p>Two things matter for lag diagnosis that plain
 * {@code totalMemory - freeMemory} cannot provide:</p>
 * <ul>
 *   <li><b>GC pause time deltas</b> — a 300 ms MSPT spike that coincides with
 *       300 ms of GC time is a memory problem, not a redstone problem.</li>
 *   <li><b>Old-gen occupancy after the last collection</b>
 *       ({@link MemoryPoolMXBean#getCollectionUsage()}) — the only honest
 *       "memory pressure" signal. Live heap usage of 90% right before a young
 *       GC is completely normal and must never trigger an alert.</li>
 * </ul>
 */
public final class GcSampler {

    /** GC time and pause events observed over a rolling window. */
    public record GcStats(
            long gcCount60s,
            long gcTimeMs60s,
            double longestPauseMs60s,
            double oldGenAfterGcPercent,
            double heapUsedPercent,
            long heapUsedMb,
            long heapMaxMb,
            double metaspaceUsedPercent) {

        public static final GcStats EMPTY = new GcStats(0, 0, 0, -1, -1, 0, 0, -1);

        /** True when old-gen occupancy after GC is known. */
        public boolean hasAfterGcData() {
            return oldGenAfterGcPercent >= 0;
        }
    }

    private record GcEvent(long atMs, long collections, long timeMs) {
    }

    /**
     * Long-term trend of the old-generation floor.
     *
     * <p>Garbage in the old generation comes and goes, but what is left right
     * after a collection only creeps upward when something keeps objects
     * alive - a leak. The lowest after-GC value per 10-minute bucket is that
     * floor; a steady climb over hours is the early warning that otherwise
     * only arrives as an OutOfMemoryError.
     *
     * @param slopeMbPerHour  fitted growth of the floor
     * @param r2              how well a straight line fits (1 = perfectly steady)
     * @param hoursUntilFull  hours until the floor reaches 90 % of the heap at
     *                        this rate; negative when not growing
     */
    public record MemoryTrend(boolean hasData, double spanHours, double slopeMbPerHour, double r2,
                              long floorMb, long maxMb, double hoursUntilFull) {

        public static final MemoryTrend NONE = new MemoryTrend(false, 0, 0, 0, 0, 0, -1);

        /** Steady growth worth mentioning: over 2 h of data, a good fit, and a real rate. */
        public boolean leakSuspected() {
            return hasData && spanHours >= 2.0 && r2 >= 0.7
                    && slopeMbPerHour >= Math.max(20.0, maxMb * 0.01);
        }
    }

    static final long TREND_BUCKET_MS = 10 * 60_000L;
    static final int TREND_BUCKETS_KEPT = 36;

    private final Plugin plugin;
    private final Map<String, long[]> lastPerBean = new HashMap<>();
    private final ArrayDeque<GcEvent> events = new ArrayDeque<>();
    private volatile GcStats stats = GcStats.EMPTY;
    /** Lowest old-gen after-GC usage (MB) per bucket, oldest first; written by poll() only. */
    private final ArrayDeque<long[]> floorBuckets = new ArrayDeque<>();
    private volatile MemoryTrend trend = MemoryTrend.NONE;
    private ScheduledTask task;

    public GcSampler(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Scheduling.runAsyncRepeating(plugin, this::poll, 1_000L, 1_000L);
    }

    public void stop() {
        Scheduling.cancel(task);
        task = null;
    }

    public GcStats stats() {
        return stats;
    }

    private synchronized void poll() {
        long now = System.currentTimeMillis();

        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            // ZGC and Shenandoah expose a "... Cycles" bean next to "... Pauses".
            // Its collection time is the length of the concurrent cycle, during
            // which the application keeps running - counting it reported
            // seconds of "GC time" on servers that never paused.
            if (bean.getName().contains("Cycles")) {
                continue;
            }
            long count = bean.getCollectionCount();
            long time = bean.getCollectionTime();
            if (count < 0) {
                continue;
            }
            long[] last = lastPerBean.get(bean.getName());
            if (last != null) {
                long dCount = count - last[0];
                long dTime = time - last[1];
                if (dCount > 0) {
                    events.addLast(new GcEvent(now, dCount, Math.max(0, dTime)));
                    recordFloor(bean, now);
                }
            }
            lastPerBean.put(bean.getName(), new long[]{count, time});
        }

        long cutoff = now - 60_000L;
        while (!events.isEmpty() && events.peekFirst().atMs < cutoff) {
            events.removeFirst();
        }
        recompute();
    }

    public MemoryTrend memoryTrend() {
        return trend;
    }

    private void recordFloor(GarbageCollectorMXBean bean, long now) {
        if (!(bean instanceof com.sun.management.GarbageCollectorMXBean sunBean)) {
            return;
        }
        com.sun.management.GcInfo info = sunBean.getLastGcInfo();
        if (info == null) {
            return;
        }
        long oldBytes = -1;
        for (var entry : info.getMemoryUsageAfterGc().entrySet()) {
            String name = entry.getKey().toLowerCase();
            if (name.contains("old") || name.contains("tenured")) {
                oldBytes = Math.max(0, oldBytes) + entry.getValue().getUsed();
            }
        }
        if (oldBytes < 0) {
            return;
        }
        long mb = oldBytes / (1024 * 1024);
        long bucket = now / TREND_BUCKET_MS;
        long[] last = floorBuckets.peekLast();
        if (last != null && last[0] == bucket) {
            last[1] = Math.min(last[1], mb);
        } else {
            floorBuckets.addLast(new long[]{bucket, mb});
            while (floorBuckets.size() > TREND_BUCKETS_KEPT) {
                floorBuckets.removeFirst();
            }
            // A bucket is only final once the next one starts.
            trend = computeTrend(floorBuckets, maxMb());
        }
    }

    /**
     * Least-squares line through the completed buckets (all but the newest).
     * Package-private and static so the arithmetic stays under test.
     */
    static MemoryTrend computeTrend(java.util.Collection<long[]> buckets, long maxMb) {
        List<long[]> done = new java.util.ArrayList<>(buckets);
        if (!done.isEmpty()) {
            done.remove(done.size() - 1);
        }
        int n = done.size();
        if (n < 6) {
            return MemoryTrend.NONE;
        }
        double x0 = done.get(0)[0];
        double sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0;
        for (long[] b : done) {
            double x = (b[0] - x0) * TREND_BUCKET_MS / 3_600_000.0; // hours
            double y = b[1];
            sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y;
        }
        double denom = n * sxx - sx * sx;
        if (denom <= 0) {
            return MemoryTrend.NONE;
        }
        double slope = (n * sxy - sx * sy) / denom;
        double varY = n * syy - sy * sy;
        double r2 = varY <= 0 ? 0 : Math.pow(n * sxy - sx * sy, 2) / (denom * varY);
        long floor = done.get(n - 1)[1];
        double span = (done.get(n - 1)[0] - done.get(0)[0]) * TREND_BUCKET_MS / 3_600_000.0;
        double hoursUntilFull = slope > 0 ? Math.max(0, (maxMb * 0.9 - floor) / slope) : -1;
        return new MemoryTrend(true, span, slope, r2, floor, maxMb, hoursUntilFull);
    }

    /**
     * Drops every collection recorded so far from the rolling window.
     *
     * <p>Called when the startup grace period ends: a boot GC pause would
     * otherwise stay in the 60-second window and raise a "GC pressure"
     * WARNING for a boot that is already over.
     */
    public synchronized void forgetWindow() {
        events.clear();
        recompute();
    }

    private void recompute() {

        long count60 = 0;
        long time60 = 0;
        double longestPause = 0;
        for (GcEvent event : events) {
            count60 += event.collections;
            time60 += event.timeMs;
            // Average per collection: if several collections happened in one
            // poll only their combined time is known, so a single long pause
            // among short ones is under-reported.
            double pause = (double) event.timeMs / Math.max(1, event.collections);
            if (pause > longestPause) {
                longestPause = pause;
            }
        }

        this.stats = new GcStats(
                count60,
                time60,
                longestPause,
                oldGenAfterGcPercent(),
                heapUsedPercent(),
                usedMb(),
                maxMb(),
                metaspaceUsedPercent());
    }

    private static double oldGenAfterGcPercent() {
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean pool : pools) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            String name = pool.getName().toLowerCase();
            if (!name.contains("old") && !name.contains("tenured")) {
                continue;
            }
            MemoryUsage afterGc = pool.getCollectionUsage();
            if (afterGc == null || afterGc.getMax() <= 0) {
                return -1;
            }
            return afterGc.getUsed() * 100.0 / afterGc.getMax();
        }
        return -1;
    }

    private static double metaspaceUsedPercent() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if ("Metaspace".equals(pool.getName())) {
                MemoryUsage usage = pool.getUsage();
                if (usage == null || usage.getMax() <= 0) {
                    return -1; // unbounded metaspace
                }
                return usage.getUsed() * 100.0 / usage.getMax();
            }
        }
        return -1;
    }

    private static double heapUsedPercent() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        if (max <= 0) {
            return -1;
        }
        return (rt.totalMemory() - rt.freeMemory()) * 100.0 / max;
    }

    private static long usedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    private static long maxMb() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }
}
