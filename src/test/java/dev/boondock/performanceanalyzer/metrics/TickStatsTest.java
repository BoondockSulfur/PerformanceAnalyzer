package dev.boondock.performanceanalyzer.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the {@link TickStats} snapshot record. */
class TickStatsTest {

    @Test
    void emptyHasNoData() {
        assertFalse(TickStats.EMPTY.hasData());
        assertEquals(0, TickStats.EMPTY.sampleCount10s());
        assertEquals(-1.0, TickStats.EMPTY.msptAvg10s(), 1e-9);
    }

    @Test
    void hasDataRequiresSamplesAndNonNegativeAverage() {
        TickStats valid = new TickStats(5, 4, 8, 10, 12, 12, 20, 20, 200, true, false);
        assertTrue(valid.hasData());

        TickStats noSamples = new TickStats(5, 4, 8, 10, 12, 12, 20, 20, 0, true, false);
        assertFalse(noSamples.hasData(), "zero samples must mean no data");

        TickStats negativeAvg = new TickStats(-1, -1, -1, -1, -1, -1, -1, -1, 5, false, false);
        assertFalse(negativeAvg.hasData(), "negative average must mean no data");
    }

    @Test
    void recordCarriesPlatformFlags() {
        TickStats folia = new TickStats(5, 4, 8, 10, 12, 12, 19.8, 19.9, 400, true, true);
        assertTrue(folia.folia());
        assertTrue(folia.eventDriven());

        TickStats fallback = new TickStats(50, 50, 50, 50, 50, 50, 20, 20, 200, false, false);
        assertFalse(fallback.eventDriven());
        assertTrue(fallback.hasData());
    }
}
