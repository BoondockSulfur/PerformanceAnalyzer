package dev.boondock.performanceanalyzer.analysis;

/**
 * Exponentially weighted moving average of the server's normal tick times.
 *
 * <p>Fixed global thresholds treat every server the same; in reality a server
 * that idles at 30 ms MSPT should alert at 45 ms while one idling at 5 ms is
 * already in trouble at 15 ms. The baseline learns the server's own normal
 * and lets the severity model alert on <em>deviation from normal</em> in
 * addition to the hard 50 ms deadline.</p>
 *
 * <p>The baseline is only updated while the server is healthy so an ongoing
 * incident cannot poison it ("the lag is normal now").</p>
 */
public final class Baseline {

    /** Smoothing over roughly 10 minutes of 1-second updates. */
    private static final double ALPHA = 2.0 / (600 + 1);
    /** Number of healthy samples required before the baseline is trusted. */
    private static final int WARMUP_SAMPLES = 120;

    private double avgMspt = -1;
    private double p95Mspt = -1;
    private int samples;

    /**
     * Feeds one healthy measurement. Callers must only invoke this while the
     * severity is OK — learning during NOTICE or worse lets sustained load
     * ratchet the baseline upward until lag reads as normal.
     */
    public synchronized void update(double msptAvg, double msptP95) {
        if (msptAvg < 0 || msptP95 < 0) {
            return;
        }
        if (avgMspt < 0) {
            avgMspt = msptAvg;
            p95Mspt = msptP95;
        } else {
            avgMspt += ALPHA * (msptAvg - avgMspt);
            p95Mspt += ALPHA * (msptP95 - p95Mspt);
        }
        if (samples < WARMUP_SAMPLES) {
            samples++;
        }
    }

    /** True once enough healthy samples were seen to trust the baseline. */
    public synchronized boolean isEstablished() {
        return samples >= WARMUP_SAMPLES && avgMspt >= 0;
    }

    public synchronized double avgMspt() {
        return avgMspt;
    }

    public synchronized double p95Mspt() {
        return p95Mspt;
    }
}
