package dev.boondock.performanceanalyzer.metrics;

/**
 * Immutable snapshot of tick-time statistics over fixed time windows.
 *
 * <p>All duration values are the <em>real work time per tick</em> in
 * milliseconds (from {@code ServerTickEndEvent#getTickDuration()}), never the
 * tick interval. A healthy server therefore shows single-digit values here,
 * and 50 ms is the hard deadline, not the baseline.</p>
 *
 * <p>On Folia the samples come from every region thread, so the distribution
 * spans all regions: {@code msptP95}/{@code msptMax} reflect the slowest
 * regions and {@code tps} is a worst-region estimate derived from the p95
 * duration. On plain Paper {@code tps} is exact (counted ticks per window).</p>
 */
public record TickStats(
        double msptAvg10s,
        double msptP50,
        double msptP95,
        double msptP99,
        double msptMax10s,
        double worstTickMs60s,
        double tps10s,
        double tps60s,
        long sampleCount10s,
        boolean eventDriven,
        boolean folia) {

    public static final TickStats EMPTY =
            new TickStats(-1, -1, -1, -1, -1, -1, -1, -1, 0, false, false);

    /** True once at least one full 10s window of samples exists. */
    public boolean hasData() {
        return sampleCount10s > 0 && msptAvg10s >= 0;
    }
}
