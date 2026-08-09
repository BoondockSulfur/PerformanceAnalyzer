package dev.boondock.performanceanalyzer.metrics;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;

/**
 * Measures the real per-tick work duration via {@link ServerTickEndEvent}.
 *
 * <p>This replaces the old interval-based sampler which measured the time
 * <em>between</em> scheduler runs (~50 ms on an idle server) instead of the
 * time a tick actually took (2–10 ms on an idle server) — the root cause of
 * the permanent false alerts in v2.x.</p>
 *
 * <p>Design constraints:</p>
 * <ul>
 *   <li>The event handler is lock-free and allocation-free — it runs on every
 *       tick of every region and must never itself cost tick time.</li>
 *   <li>Samples carry a nanoTime timestamp so statistics are computed over
 *       fixed <em>time</em> windows (10 s / 60 s), not fixed tick counts.</li>
 *   <li>A single tick above the spike threshold triggers an immediate,
 *       rate-limited callback so short spikes are caught the moment they
 *       happen instead of being averaged away.</li>
 *   <li>If tick events are unavailable, a global-region heartbeat task keeps
 *       an interval-based estimate alive and the snapshot is flagged
 *       {@code eventDriven=false}.</li>
 * </ul>
 */
public final class TickTimeSampler implements Listener {

    /** ~2 min of samples at 20 TPS on Paper; shorter effective span on Folia (more regions). */
    private static final int CAPACITY = 4096;
    private static final long SPIKE_CALLBACK_MIN_INTERVAL_MS = 1_000L;
    private static final long EVENT_SILENCE_FALLBACK_NANOS = 5_000_000_000L;
    /** Recorded in interval-fallback mode while the tick deadline is met (true duration unknown). */
    private static final double FALLBACK_HEALTHY_NOMINAL_MS = 5.0;
    /** Nominal gap between two ticks on a healthy server. */
    private static final double NOMINAL_TICK_INTERVAL_MS = 50.0;
    /** Unaccounted main-thread time above this counts as a stall. */
    private static final double STALL_THRESHOLD_MS = 1_000.0;

    private final Plugin plugin;

    // Ring buffer: parallel arrays, single atomic cursor. Slots may be read
    // while written; a torn read yields one bogus sample out of thousands,
    // which the percentile math tolerates — correctness is not tick-priced.
    private final long[] sampleEndNanos = new long[CAPACITY];
    private final float[] sampleDurationMs = new float[CAPACITY];
    private final AtomicInteger cursor = new AtomicInteger();

    private volatile double spikeThresholdMs = 100.0;
    private volatile DoubleConsumer spikeCallback;
    private final AtomicLong lastSpikeCallbackAtMs = new AtomicLong();

    /** Worst single tick since the last {@link #drainWorstTick()} call. */
    private final AtomicLong pendingWorstTickBits = new AtomicLong(Double.doubleToRawLongBits(0.0));

    private final AtomicLong lastEventSampleNanos = new AtomicLong();

    // Stalls the tick measurement cannot see. Work that blocks the main
    // thread *outside* the tick loop - loading or generating a world, for
    // instance - produces no ServerTickEndEvent at all, so tick durations
    // stay healthy while TPS collapses. Observed on production: /mv create
    // froze the server for 4 s and the worst recorded tick was 12 ms, which
    // left the incident with "no clear cause". The gap between two tick ends
    // exposes exactly that time, and needs no new instrumentation.
    private volatile double lastStallMs;
    private volatile long lastStallAtMs;

    private volatile long lastHeartbeatNanos = -1L;
    private volatile long startedAtNanos;
    private ScheduledTask heartbeatTask;

    public TickTimeSampler(Plugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    public void start() {
        startedAtNanos = System.nanoTime();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Heartbeat on the global region: feeds the interval fallback and
        // detects whether tick-end events are actually firing.
        heartbeatTask = Scheduling.runGlobalRepeating(plugin, this::heartbeat, 1L, 1L);
    }

    public void stop() {
        Scheduling.cancel(heartbeatTask);
        heartbeatTask = null;
        ServerTickEndEvent.getHandlerList().unregister(this);
    }

    /** Sets the single-tick spike threshold and the rate-limited callback fired when it is exceeded. */
    public void onSpike(double thresholdMs, DoubleConsumer callback) {
        this.spikeThresholdMs = thresholdMs;
        this.spikeCallback = callback;
    }

    /* ------------------------------------------------------------------ */
    /* Sampling                                                            */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        long now = System.nanoTime();
        double durationMs = event.getTickDuration();
        long previousEnd = lastEventSampleNanos.getAndSet(now);

        if (previousEnd > 0) {
            double intervalMs = (now - previousEnd) / 1_000_000.0;
            double unaccounted = unaccountedMs(intervalMs, durationMs);
            if (unaccounted >= STALL_THRESHOLD_MS) {
                lastStallMs = unaccounted;
                lastStallAtMs = System.currentTimeMillis();
            }
        }

        record(now, durationMs);
    }

    /**
     * Main-thread time between two ticks that the tick duration does not
     * explain.
     *
     * <p>A long <em>tick</em> is already visible as its duration - a 7-second
     * {@code save-all} shows up as a 7-second tick and leaves nothing
     * unaccounted here. What this catches is the opposite case: the interval
     * is huge while the tick itself was short, which means the server was
     * busy somewhere the measurement never ran.
     */
    static double unaccountedMs(double intervalMs, double durationMs) {
        return intervalMs - durationMs - NOMINAL_TICK_INTERVAL_MS;
    }

    private void heartbeat() {
        long now = System.nanoTime();
        long previous = lastHeartbeatNanos;
        lastHeartbeatNanos = now;
        if (previous < 0) {
            return;
        }
        if (eventDriven(now)) {
            return;
        }
        // Fallback: no tick events on this platform (Folia does not fire
        // ServerTickEndEvent). The interval only equals the work time when the
        // server is overloaded. While the deadline is met the real duration is
        // unknown-but-healthy, so record a nominal low value — recording the
        // ~50 ms interval itself would look like a saturated server and cause
        // permanent false WARNING incidents (observed on Folia 26.2).
        double intervalMs = (now - previous) / 1_000_000.0;
        record(now, intervalMs <= 55.0 ? FALLBACK_HEALTHY_NOMINAL_MS : intervalMs);
    }

    private void record(long endNanos, double durationMs) {
        int slot = Math.floorMod(cursor.getAndIncrement(), CAPACITY);
        sampleDurationMs[slot] = (float) durationMs;
        sampleEndNanos[slot] = endNanos;

        if (durationMs >= spikeThresholdMs) {
            accumulateMax(pendingWorstTickBits, durationMs);
            DoubleConsumer callback = spikeCallback;
            if (callback != null) {
                long nowMs = System.currentTimeMillis();
                long last = lastSpikeCallbackAtMs.get();
                if (nowMs - last >= SPIKE_CALLBACK_MIN_INTERVAL_MS
                        && lastSpikeCallbackAtMs.compareAndSet(last, nowMs)) {
                    callback.accept(durationMs);
                }
            }
        }
    }

    private static void accumulateMax(AtomicLong bits, double value) {
        long current;
        do {
            current = bits.get();
            if (Double.longBitsToDouble(current) >= value) {
                return;
            }
        } while (!bits.compareAndSet(current, Double.doubleToRawLongBits(value)));
    }

    /** Length of the most recent unmeasured stall in ms (0 if none seen). */
    public double lastStallMs() {
        return lastStallMs;
    }

    /** Wall-clock time of the most recent unmeasured stall (0 if none seen). */
    public long lastStallAtMs() {
        return lastStallAtMs;
    }

    /** Returns and resets the worst spike tick recorded since the last call (0 if none). */
    public double drainWorstTick() {
        return Double.longBitsToDouble(pendingWorstTickBits.getAndSet(Double.doubleToRawLongBits(0.0)));
    }

    private boolean eventDriven(long nowNanos) {
        long lastEvent = lastEventSampleNanos.get();
        return lastEvent != 0 && nowNanos - lastEvent < EVENT_SILENCE_FALLBACK_NANOS;
    }

    /* ------------------------------------------------------------------ */
    /* Statistics                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Computes a fresh snapshot over fixed time windows. Called from the
     * monitor thread (about once per second) — never on a tick thread.
     */
    public TickStats snapshot() {
        long now = System.nanoTime();
        long cut10s = now - 10_000_000_000L;
        long cut60s = now - 60_000_000_000L;

        int size = Math.min(cursor.get(), CAPACITY);
        if (size == 0) {
            return TickStats.EMPTY;
        }

        double[] window10s = new double[size];
        double[] window60s = new double[size];
        int n10 = 0;
        int n60 = 0;
        double sum10 = 0;
        double max10 = 0;
        double max60 = 0;

        for (int i = 0; i < size; i++) {
            long ts = sampleEndNanos[i];
            if (ts < cut60s || ts > now) {
                continue;
            }
            double duration = sampleDurationMs[i];
            if (duration < 0 || duration > 600_000) {
                continue; // torn read guard
            }
            window60s[n60++] = duration;
            if (duration > max60) {
                max60 = duration;
            }
            if (ts >= cut10s) {
                window10s[n10++] = duration;
                sum10 += duration;
                if (duration > max10) {
                    max10 = duration;
                }
            }
        }

        if (n10 == 0) {
            return TickStats.EMPTY;
        }

        double[] sorted = Arrays.copyOf(window10s, n10);
        Arrays.sort(sorted);
        double p50 = percentile(sorted, 0.50);
        double p95 = percentile(sorted, 0.95);
        double p99 = percentile(sorted, 0.99);
        double avg = sum10 / n10;

        boolean eventDriven = eventDriven(now);
        double tps10s;
        double tps60s;
        if (Scheduling.FOLIA) {
            // Mixed-region samples: counting ticks is meaningless, estimate
            // the worst region's rate from the p95 duration instead.
            double[] sorted60 = Arrays.copyOf(window60s, n60);
            Arrays.sort(sorted60);
            tps10s = estimateTps(p95);
            tps60s = estimateTps(percentile(sorted60, 0.95));
        } else {
            // Clamp the window to the sampler's uptime — otherwise the first
            // minute after start divides by a window that hasn't filled yet
            // and reports a bogus low TPS.
            double uptimeSec = Math.max(1.0, (now - startedAtNanos) / 1_000_000_000.0);
            tps10s = Math.min(20.0, n10 / Math.min(10.0, uptimeSec));
            tps60s = Math.min(20.0, n60 / Math.min(60.0, uptimeSec));
        }

        return new TickStats(avg, p50, p95, p99, max10, max60, tps10s, tps60s, n10, eventDriven, Scheduling.FOLIA);
    }

    private static double estimateTps(double durationMs) {
        if (durationMs <= 50.0) {
            return 20.0;
        }
        return Math.max(1.0, 1000.0 / durationMs);
    }

    private static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return -1;
        }
        int index = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
