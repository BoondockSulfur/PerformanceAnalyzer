package dev.boondock.performanceanalyzer.calibrate;

/**
 * Fixed-size counting histogram for bounded, non-negative observations.
 *
 * <p>Calibration needs percentiles over potentially millions of samples
 * (every entity count of every loaded chunk, once per minute, for days).
 * Keeping the raw values would grow without bound, so observations are
 * counted into buckets instead: memory is constant and a percentile costs one
 * pass over the bucket array.
 *
 * <p>The top bucket saturates. A histogram sized for 0-255 entities per chunk
 * counts a chunk holding 900 entities as 255 - percentiles stay correct up to
 * the saturation point, which is far above any threshold worth proposing.
 */
public final class Histogram {

    private final int[] buckets;
    private final int bucketWidth;
    private long count;

    /**
     * @param bucketCount number of buckets (must be &gt; 0)
     * @param bucketWidth values per bucket; 1 gives exact counting
     */
    public Histogram(int bucketCount, int bucketWidth) {
        if (bucketCount <= 0 || bucketWidth <= 0) {
            throw new IllegalArgumentException("bucketCount and bucketWidth must be > 0");
        }
        this.buckets = new int[bucketCount];
        this.bucketWidth = bucketWidth;
    }

    /** Records one observation. Negative values are ignored, high ones saturate. */
    public synchronized void record(int value) {
        if (value < 0) {
            return;
        }
        int index = Math.min(buckets.length - 1, value / bucketWidth);
        buckets[index]++;
        count++;
    }

    /** Number of recorded observations. */
    public synchronized long count() {
        return count;
    }

    /**
     * Value at the given percentile.
     *
     * <p>Returns the upper edge of the containing bucket, so a proposed
     * threshold is never below an observation it is meant to cover.
     *
     * @param percentile 0.0 - 1.0, clamped
     * @return the value, or -1 when nothing has been recorded
     */
    public synchronized int percentile(double percentile) {
        if (count == 0) {
            return -1;
        }
        double p = Math.min(1.0, Math.max(0.0, percentile));
        long target = (long) Math.ceil(p * count);
        if (target <= 0) {
            target = 1;
        }

        long cumulative = 0;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i];
            if (cumulative >= target) {
                return (i + 1) * bucketWidth - 1;
            }
        }
        return buckets.length * bucketWidth - 1;
    }

    /** Highest recorded value (bucket upper edge), or -1 when empty. */
    public synchronized int max() {
        for (int i = buckets.length - 1; i >= 0; i--) {
            if (buckets[i] > 0) {
                return (i + 1) * bucketWidth - 1;
            }
        }
        return -1;
    }

    public synchronized void reset() {
        java.util.Arrays.fill(buckets, 0);
        count = 0;
    }
}
