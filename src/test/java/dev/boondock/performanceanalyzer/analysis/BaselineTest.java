package dev.boondock.performanceanalyzer.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Baseline}: warmup gating, EWMA movement and the
 * update-only-by-caller poisoning-protection contract.
 */
class BaselineTest {

    /** Must match {@code Baseline.WARMUP_SAMPLES}. */
    private static final int WARMUP_SAMPLES = 120;
    /** Must match {@code Baseline.ALPHA} (2 / (600 + 1)). */
    private static final double ALPHA = 2.0 / 601.0;

    @Test
    void freshBaselineIsNotEstablishedAndHasNoValues() {
        Baseline baseline = new Baseline();
        assertFalse(baseline.isEstablished());
        assertEquals(-1.0, baseline.avgMspt(), 1e-9);
        assertEquals(-1.0, baseline.p95Mspt(), 1e-9);
    }

    @Test
    void firstUpdateSeedsValuesExactly() {
        Baseline baseline = new Baseline();
        baseline.update(10.0, 20.0);
        assertEquals(10.0, baseline.avgMspt(), 1e-9);
        assertEquals(20.0, baseline.p95Mspt(), 1e-9);
    }

    @Test
    void negativeInputsAreIgnored() {
        Baseline baseline = new Baseline();
        baseline.update(-1.0, 5.0);
        baseline.update(5.0, -1.0);
        assertEquals(-1.0, baseline.avgMspt(), 1e-9, "negative samples must not seed the baseline");
        assertFalse(baseline.isEstablished());

        // And they must not disturb an already-seeded baseline either.
        baseline.update(10.0, 20.0);
        baseline.update(-1.0, -1.0);
        assertEquals(10.0, baseline.avgMspt(), 1e-9);
        assertEquals(20.0, baseline.p95Mspt(), 1e-9);
    }

    @Test
    void ewmaMovesTowardNewSamplesByAlpha() {
        Baseline baseline = new Baseline();
        baseline.update(10.0, 20.0);
        baseline.update(20.0, 30.0);
        // avg = 10 + ALPHA * (20 - 10)
        assertEquals(10.0 + ALPHA * 10.0, baseline.avgMspt(), 1e-9);
        assertEquals(20.0 + ALPHA * 10.0, baseline.p95Mspt(), 1e-9);
        // A single outlier barely moves the baseline (smoothing over ~10 min).
        assertTrue(baseline.avgMspt() < 10.2, "one sample must not drag the EWMA far");
    }

    @Test
    void warmupGatingRequiresExactlyWarmupSamples() {
        Baseline baseline = new Baseline();
        for (int i = 0; i < WARMUP_SAMPLES - 1; i++) {
            baseline.update(5.0, 8.0);
        }
        assertFalse(baseline.isEstablished(), "one sample short of warmup must not be established");
        baseline.update(5.0, 8.0);
        assertTrue(baseline.isEstablished(), "warmup sample count reached, baseline must be trusted");
    }

    @Test
    void ignoredSamplesDoNotCountTowardWarmup() {
        Baseline baseline = new Baseline();
        for (int i = 0; i < WARMUP_SAMPLES * 2; i++) {
            baseline.update(-1.0, -1.0);
        }
        assertFalse(baseline.isEstablished(), "rejected samples must not advance the warmup counter");
    }

    /**
     * Poisoning protection is a caller contract: the baseline only moves when
     * {@code update} is invoked (the monitor skips it while severity is
     * WARNING or above). Verify that not calling update leaves it untouched.
     */
    @Test
    void baselineOnlyChangesThroughUpdate() {
        Baseline baseline = new Baseline();
        for (int i = 0; i < WARMUP_SAMPLES; i++) {
            baseline.update(5.0, 8.0);
        }
        double avgBefore = baseline.avgMspt();
        double p95Before = baseline.p95Mspt();

        // Reads must not mutate.
        baseline.isEstablished();
        baseline.avgMspt();
        baseline.p95Mspt();

        assertEquals(avgBefore, baseline.avgMspt(), 1e-12);
        assertEquals(p95Before, baseline.p95Mspt(), 1e-12);
        assertTrue(baseline.isEstablished());
    }

    @Test
    void stableInputConvergesToInput() {
        Baseline baseline = new Baseline();
        for (int i = 0; i < 500; i++) {
            baseline.update(7.5, 12.0);
        }
        assertEquals(7.5, baseline.avgMspt(), 1e-9);
        assertEquals(12.0, baseline.p95Mspt(), 1e-9);
    }
}
