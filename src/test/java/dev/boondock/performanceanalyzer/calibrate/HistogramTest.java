package dev.boondock.performanceanalyzer.calibrate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests for the bounded {@link Histogram} behind threshold calibration. */
class HistogramTest {

    @Test
    void emptyHistogramReportsNoData() {
        Histogram histogram = new Histogram(16, 1);
        assertEquals(0, histogram.count());
        assertEquals(-1, histogram.percentile(0.99), "empty must not fake a value");
        assertEquals(-1, histogram.max());
    }

    @Test
    void percentileOfUniformDataMatchesPosition() {
        Histogram histogram = new Histogram(256, 1);
        for (int i = 0; i < 100; i++) {
            histogram.record(i);
        }
        assertEquals(100, histogram.count());
        // 100 values 0..99: p50 sits at the 50th, p99 at the 99th.
        assertEquals(49, histogram.percentile(0.5));
        assertEquals(98, histogram.percentile(0.99));
        assertEquals(99, histogram.max());
    }

    @Test
    void percentileIgnoresRareOutliers() {
        // The point of using p99 rather than max: one decorated chunk must
        // not drag a whole server's threshold upward.
        Histogram histogram = new Histogram(256, 1);
        for (int i = 0; i < 999; i++) {
            histogram.record(3);
        }
        histogram.record(200);

        assertEquals(3, histogram.percentile(0.99), "a single outlier must not move p99");
        assertEquals(200, histogram.max(), "but it is still visible as the maximum");
    }

    @Test
    void valuesAboveRangeSaturateInTopBucket() {
        Histogram histogram = new Histogram(4, 1);
        histogram.record(99);
        assertEquals(3, histogram.percentile(1.0), "out-of-range must clamp, not throw");
        assertEquals(1, histogram.count());
    }

    @Test
    void bucketWidthReportsUpperEdge() {
        // Width 50: a world holding 120 entities lands in bucket 2 (100-149)
        // and is reported as 149 - never below the observation it covers.
        Histogram histogram = new Histogram(200, 50);
        histogram.record(120);
        assertEquals(149, histogram.percentile(0.5));
    }

    @Test
    void negativeValuesAreIgnored() {
        Histogram histogram = new Histogram(16, 1);
        histogram.record(-5);
        assertEquals(0, histogram.count());
    }

    @Test
    void resetClearsEverything() {
        Histogram histogram = new Histogram(16, 1);
        histogram.record(4);
        histogram.reset();
        assertEquals(0, histogram.count());
        assertEquals(-1, histogram.percentile(0.5));
    }

    @Test
    void invalidSizingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Histogram(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Histogram(16, 0));
    }
}
