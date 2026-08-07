package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.metrics.GcSampler;
import dev.boondock.performanceanalyzer.metrics.TickStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SeverityModel}: severity boundaries, GC rules, baseline
 * deviation and score monotonicity. Records are constructed directly — no
 * Bukkit involved.
 */
class SeverityModelTest {

    /** Healthy-server GC stats (no pressure at all). */
    private static final GcSampler.GcStats GC_QUIET =
            new GcSampler.GcStats(2, 40, 25.0, 20.0, 35.0, 700, 2048, 40.0);

    /**
     * Convenience TickStats builder: percentiles derived plausibly from avg,
     * exact tail values overridable where a test needs them.
     */
    private static TickStats ticks(double avg, double p95, double max, double tps) {
        return new TickStats(avg, avg * 0.9, p95, p95 * 1.1, max, max, tps, tps, 200, true, false);
    }

    /* ------------------------------------------------------------------ */
    /* No data / OK                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    void nullOrEmptyTicksYieldOk() {
        assertEquals(Severity.OK, SeverityModel.evaluate(null, GC_QUIET, null).severity());
        SeverityModel.Assessment empty = SeverityModel.evaluate(TickStats.EMPTY, GC_QUIET, null);
        assertEquals(Severity.OK, empty.severity());
        assertEquals(0, empty.score());
        assertTrue(empty.reasons().isEmpty());
    }

    @Test
    void healthyServerIsOkWithLowScore() {
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(5, 8, 20, 20.0), GC_QUIET, null);
        assertEquals(Severity.OK, a.severity());
        assertTrue(a.score() < 15, "healthy single-digit MSPT must score low, was " + a.score());
    }

    /* ------------------------------------------------------------------ */
    /* Severity boundaries                                                 */
    /* ------------------------------------------------------------------ */

    @Test
    void emergencyOnTpsBelowTen() {
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(120, 140, 300, 8.0), GC_QUIET, null);
        assertEquals(Severity.EMERGENCY, a.severity());
        assertFalse(a.reasons().isEmpty());
    }

    @Test
    void emergencyOnAvgAtOrAbove100msEvenWithDecentTps() {
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(100, 110, 150, 18.0), GC_QUIET, null);
        assertEquals(Severity.EMERGENCY, a.severity());
    }

    @Test
    void criticalAtTheFiftyMsDeadline() {
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(50, 55, 80, 19.0), GC_QUIET, null);
        assertEquals(Severity.CRITICAL, a.severity());
        assertFalse(a.reasons().isEmpty());
    }

    @Test
    void criticalOnTpsBelowSeventeen() {
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(30, 40, 60, 16.5), GC_QUIET, null);
        assertEquals(Severity.CRITICAL, a.severity());
    }

    @Test
    void warningOnP95AtFiftyMs() {
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(20, 50, 70, 19.8), GC_QUIET, null);
        assertEquals(Severity.WARNING, a.severity());
        assertFalse(a.reasons().isEmpty());
    }

    @Test
    void warningOnAvgAtThirtyFiveMs() {
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(35, 45, 70, 19.5), GC_QUIET, null);
        assertEquals(Severity.WARNING, a.severity());
    }

    @Test
    void noticeOnSingleTickSpikeWhenOtherwiseOk() {
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(5, 8, 160, 20.0), GC_QUIET, null);
        assertEquals(Severity.NOTICE, a.severity());
        assertFalse(a.reasons().isEmpty());
    }

    @Test
    void justBelowWarningBoundariesStaysOk() {
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(34.9, 49.9, 100, 19.9), GC_QUIET, null);
        assertEquals(Severity.OK, a.severity());
    }

    /* ------------------------------------------------------------------ */
    /* GC rules                                                            */
    /* ------------------------------------------------------------------ */

    @Test
    void oldGenAtNinetyPercentAfterGcIsCriticalEvenWithHealthyTicks() {
        GcSampler.GcStats pressure = new GcSampler.GcStats(10, 500, 80.0, 92.0, 90.0, 3600, 4096, 50.0);
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(5, 8, 20, 20.0), pressure, null);
        assertEquals(Severity.CRITICAL, a.severity());
        assertFalse(a.reasons().isEmpty());
    }

    @Test
    void heavyGcTimeIsAtLeastWarning() {
        GcSampler.GcStats busy = new GcSampler.GcStats(30, 3500, 150.0, 40.0, 60.0, 2000, 4096, 50.0);
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(5, 8, 20, 20.0), busy, null);
        assertEquals(Severity.WARNING, a.severity());
    }

    @Test
    void longGcPauseIsAtLeastWarning() {
        GcSampler.GcStats pausey = new GcSampler.GcStats(3, 800, 250.0, 40.0, 60.0, 2000, 4096, 50.0);
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(5, 8, 20, 20.0), pausey, null);
        assertEquals(Severity.WARNING, a.severity());
    }

    @Test
    void gcRulesDoNotDowngradeAnEmergency() {
        GcSampler.GcStats pressure = new GcSampler.GcStats(10, 500, 80.0, 92.0, 90.0, 3600, 4096, 50.0);
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(120, 140, 300, 8.0), pressure, null);
        assertEquals(Severity.EMERGENCY, a.severity());
    }

    @Test
    void nullGcStatsAreTolerated() {
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(5, 8, 20, 20.0), null, null);
        assertEquals(Severity.OK, a.severity());
    }

    /* ------------------------------------------------------------------ */
    /* Baseline deviation                                                  */
    /* ------------------------------------------------------------------ */

    private static Baseline establishedBaseline(double avg, double p95) {
        Baseline baseline = new Baseline();
        for (int i = 0; i < 120; i++) {
            baseline.update(avg, p95);
        }
        return baseline;
    }

    @Test
    void doubleOfNormalPlusTenIsWarningEvenBelowGlobalThresholds() {
        Baseline baseline = establishedBaseline(5.0, 8.0);
        // 25 ms is below the fixed 35 ms WARNING bound but > 5*2+10 = 20 ms.
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(25, 30, 60, 19.8), GC_QUIET, baseline);
        assertEquals(Severity.WARNING, a.severity());
        assertFalse(a.reasons().isEmpty());
    }

    @Test
    void moderateDeviationFromNormalIsNotice() {
        Baseline baseline = establishedBaseline(5.0, 8.0);
        // 14 ms > 5*1.5+5 = 12.5 ms but <= 5*2+10 = 20 ms.
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(14, 18, 40, 19.9), GC_QUIET, baseline);
        assertEquals(Severity.NOTICE, a.severity());
    }

    @Test
    void unestablishedBaselineNeverTriggersDeviation() {
        Baseline fresh = new Baseline();
        fresh.update(5.0, 8.0); // seeded but far from warmup
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(25, 30, 60, 19.8), GC_QUIET, fresh);
        assertEquals(Severity.OK, a.severity());
    }

    @Test
    void highBaselineServerToleratesItsOwnNormal() {
        Baseline baseline = establishedBaseline(30.0, 40.0);
        // 34 ms on a server that normally runs 30 ms: below 30*1.5+5 = 50 -> no deviation.
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(34, 44, 60, 19.5), GC_QUIET, baseline);
        assertEquals(Severity.OK, a.severity());
    }

    /* ------------------------------------------------------------------ */
    /* Score monotonicity & reasons                                        */
    /* ------------------------------------------------------------------ */

    @Test
    void scoreNeverDecreasesForWorseLoadAndTail() {
        double[][] orderedInputs = {
                // {avg, p95, max, tps}
                {2, 4, 10, 20.0},
                {10, 15, 30, 20.0},
                {20, 30, 60, 19.8},
                {35, 50, 90, 19.0},
                {50, 70, 120, 17.5},
                {80, 110, 200, 12.0},
                {120, 160, 400, 6.0},
        };
        int previous = -1;
        for (double[] in : orderedInputs) {
            int score = SeverityModel.evaluate(ticks(in[0], in[1], in[2], in[3]), GC_QUIET, null).score();
            assertTrue(score >= previous,
                    "score must not drop for strictly worse input: " + score + " < " + previous
                            + " at avg=" + in[0]);
            previous = score;
        }
    }

    @Test
    void scoreMonotonicInGcPressure() {
        TickStats fixed = ticks(20, 30, 60, 19.8);
        GcSampler.GcStats mild = new GcSampler.GcStats(5, 500, 60.0, 50.0, 60.0, 2000, 4096, 50.0);
        GcSampler.GcStats heavy = new GcSampler.GcStats(40, 5000, 300.0, 90.0, 95.0, 3800, 4096, 80.0);
        int mildScore = SeverityModel.evaluate(fixed, mild, null).score();
        int heavyScore = SeverityModel.evaluate(fixed, heavy, null).score();
        assertTrue(heavyScore >= mildScore, heavyScore + " < " + mildScore);
    }

    @Test
    void scoreIsCappedAtHundred() {
        GcSampler.GcStats extreme = new GcSampler.GcStats(100, 60000, 2000.0, 99.0, 99.0, 4000, 4096, 95.0);
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(500, 800, 2000, 1.0), extreme, null);
        assertTrue(a.score() <= 100);
        assertEquals(100, a.score());
    }

    @Test
    void reasonsNonEmptyWheneverSeverityAtLeastWarning() {
        SeverityModel.Assessment[] cases = {
                SeverityModel.evaluate(ticks(40, 55, 90, 19.0), GC_QUIET, null),
                SeverityModel.evaluate(ticks(60, 80, 120, 15.0), GC_QUIET, null),
                SeverityModel.evaluate(ticks(150, 200, 500, 5.0), GC_QUIET, null),
                SeverityModel.evaluate(ticks(5, 8, 20, 20.0),
                        new GcSampler.GcStats(10, 4000, 300.0, 95.0, 95.0, 3900, 4096, 60.0), null),
        };
        for (SeverityModel.Assessment a : cases) {
            assertTrue(a.severity().atLeast(Severity.WARNING), "test case must reach WARNING");
            assertFalse(a.reasons().isEmpty(),
                    "severity " + a.severity() + " must always carry at least one reason");
        }
    }

    @Test
    void gcStatsWithoutAfterGcDataNeverTriggerOldGenRule() {
        // oldGenAfterGcPercent = -1 -> hasAfterGcData() false.
        GcSampler.GcStats noAfterGc = new GcSampler.GcStats(2, 100, 50.0, -1.0, 60.0, 2000, 4096, 50.0);
        assertFalse(noAfterGc.hasAfterGcData());
        SeverityModel.Assessment a = SeverityModel.evaluate(ticks(5, 8, 20, 20.0), noAfterGc, null);
        assertEquals(Severity.OK, a.severity());
    }
}
