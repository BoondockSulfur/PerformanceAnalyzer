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

    private final Plugin plugin;
    private final Map<String, long[]> lastPerBean = new HashMap<>();
    private final ArrayDeque<GcEvent> events = new ArrayDeque<>();
    private volatile GcStats stats = GcStats.EMPTY;
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
                }
            }
            lastPerBean.put(bean.getName(), new long[]{count, time});
        }

        long cutoff = now - 60_000L;
        while (!events.isEmpty() && events.peekFirst().atMs < cutoff) {
            events.removeFirst();
        }

        long count60 = 0;
        long time60 = 0;
        double longestPause = 0;
        for (GcEvent event : events) {
            count60 += event.collections;
            time60 += event.timeMs;
            // Upper bound: if several collections happened in one poll we
            // only know their combined time.
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
