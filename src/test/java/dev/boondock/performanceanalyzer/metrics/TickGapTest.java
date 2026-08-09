package dev.boondock.performanceanalyzer.metrics;

import org.junit.jupiter.api.Test;

import static dev.boondock.performanceanalyzer.metrics.TickTimeSampler.unaccountedMs;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for detecting main-thread time the tick measurement cannot see.
 *
 * <p>The cases are taken from two real production incidents that behaved
 * completely differently and must stay distinguishable.
 */
class TickGapTest {

    private static final double THRESHOLD = 1_000.0;

    @Test
    void healthyServerHasNothingUnaccounted() {
        // 6 ms of work, then the usual ~50 ms until the next tick ends.
        assertTrue(unaccountedMs(56.0, 6.0) < THRESHOLD);
        assertTrue(unaccountedMs(50.0, 3.0) < THRESHOLD);
    }

    @Test
    void longTickIsNotAGap() {
        // Production, 09.08.: save-all flush blocked the server for 7 s. That
        // time IS the tick, is already reported as a 6948 ms tick, and must
        // not be double-counted as an unmeasured stall.
        double durationMs = 6948.0;
        double intervalMs = durationMs + 50.0;
        assertTrue(unaccountedMs(intervalMs, durationMs) < THRESHOLD,
                "a long tick explains itself and must not also count as a gap");
    }

    @Test
    void workOutsideTheTickLoopIsCaught() {
        // Production, 08.08.: /mv create regenerated a world. "Prepared spawn
        // area in 4083 ms" while the worst measured tick was 12 ms - the
        // stall happened outside the tick loop and left the incident with
        // "no clear cause".
        double durationMs = 12.0;
        double intervalMs = 4083.0 + durationMs + 50.0;

        double unaccounted = unaccountedMs(intervalMs, durationMs);
        assertTrue(unaccounted >= THRESHOLD, "a 4 s freeze must be detected");
        assertTrue(unaccounted > 4_000 && unaccounted < 4_200,
                "and must report roughly its real length, was " + unaccounted);
    }

    @Test
    void ordinaryJitterStaysBelowTheThreshold() {
        // A single skipped tick or a short GC pause is not a stall worth
        // reporting; only a full second of unexplained time is.
        assertTrue(unaccountedMs(300.0, 40.0) < THRESHOLD);
        assertTrue(unaccountedMs(900.0, 120.0) < THRESHOLD);
    }
}
