package dev.boondock.performanceanalyzer.calibrate;

import org.junit.jupiter.api.Test;

import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.CHUNK_CRITICAL_FACTOR;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.DEFAULT_CHUNK_ENTITY_CRITICAL;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.DEFAULT_CHUNK_ENTITY_WARNING;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.DEFAULT_PACKET_FLOOD;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.DEFAULT_SPIKE_TICK_MS;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.DEFAULT_WORLD_ENTITY_WARNING;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.SPIKE_FACTOR_WITH_WORLDEDIT;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.WORLD_ENTITY_MIN;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.WORLD_HEADROOM;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.CHUNK_ENTITY_HEADROOM;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.CHUNK_ENTITY_MIN;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.PACKET_HEADROOM;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.PACKET_MIN;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.SPIKE_FACTOR;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.SPIKE_MAX_MS;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.SPIKE_MIN_MS;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.GRACE_HEADROOM;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.GRACE_MIN_SECONDS;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.clamp;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.floor;
import static dev.boondock.performanceanalyzer.calibrate.CalibrationEngine.round;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the calibration arithmetic.
 *
 * <p>These guard the property that actually matters: a proposed threshold
 * must always sit above what the server normally does, so calibration can
 * never produce a config that alerts on healthy operation.
 */
class CalibrationFormulaTest {

    @Test
    void clampKeepsValuesInsideBounds() {
        assertEquals(50.0, clamp(10.0, 50.0, 200.0), 1e-9);
        assertEquals(200.0, clamp(9000.0, 50.0, 200.0), 1e-9);
        assertEquals(120.0, clamp(120.0, 50.0, 200.0), 1e-9);
    }

    @Test
    void roundTrimsToWholeMilliseconds() {
        assertEquals(85.0, round(85.4, 0), 1e-9);
        assertEquals(86.0, round(85.5, 0), 1e-9);
    }

    @Test
    void spikeThresholdStaysWellAboveTheBaseline() {
        // A healthy 3.4 ms server must not get a threshold anywhere near its
        // normal tick, or every ordinary tick opens an incident.
        double baseline = 3.4;
        double proposed = clamp(baseline * SPIKE_FACTOR, SPIKE_MIN_MS, SPIKE_MAX_MS);

        assertEquals(51.0, proposed, 1e-9);
        assertTrue(proposed > baseline * 10, "threshold must dwarf normal ticks");
        assertTrue(proposed >= SPIKE_MIN_MS, "never below the floor");
    }

    @Test
    void veryFastServersGetTheFloorInstead() {
        // At 2 ms the raw formula yields 30 ms, which would fire on ordinary
        // chunk loads. The floor exists for exactly this case.
        double proposed = clamp(2.0 * SPIKE_FACTOR, SPIKE_MIN_MS, SPIKE_MAX_MS);
        assertEquals(SPIKE_MIN_MS, proposed, 1e-9);
    }

    @Test
    void spikeThresholdIsCappedForSlowServers() {
        // A server whose normal tick is already 40 ms must not end up with a
        // 600 ms threshold that hides real stalls.
        double proposed = clamp(40.0 * SPIKE_FACTOR, SPIKE_MIN_MS, SPIKE_MAX_MS);
        assertEquals(SPIKE_MAX_MS, proposed, 1e-9);
    }

    @Test
    void packetThresholdCoversObservedPeaks() {
        // Observed on production: 1433 packets/tick during normal admin work
        // while the threshold sat at 1000 - pure noise.
        int observedP99 = 1433;
        double proposed = Math.max(PACKET_MIN, observedP99 * PACKET_HEADROOM);

        assertTrue(proposed > observedP99, "normal traffic must not alert");
        assertEquals(2866.0, proposed, 1e-9);
    }

    @Test
    void packetThresholdNeverDropsBelowTheFloor() {
        double proposed = Math.max(PACKET_MIN, 12 * PACKET_HEADROOM);
        assertEquals(PACKET_MIN, proposed, 1e-9, "a quiet server keeps the floor");
    }

    @Test
    void chunkThresholdClearsDecorationHotspots() {
        // The item frame gallery peaks at 61 entities per chunk and is inert
        // decoration; the proposal must sit above it.
        int observedP99 = 61;
        int warning = Math.max(CHUNK_ENTITY_MIN, (int) Math.ceil(observedP99 * CHUNK_ENTITY_HEADROOM));
        int critical = (int) Math.ceil(warning * CHUNK_CRITICAL_FACTOR);

        assertEquals(80, warning);
        assertEquals(120, critical);
        assertTrue(warning > observedP99);
        assertTrue(critical > warning, "critical must always exceed warning");
    }

    @Test
    void chunkThresholdHasAFloorForEmptyServers() {
        int warning = Math.max(CHUNK_ENTITY_MIN, (int) Math.ceil(1 * CHUNK_ENTITY_HEADROOM));
        assertEquals(CHUNK_ENTITY_MIN, warning,
                "a nearly empty sample must not produce a threshold of 2");
    }

    /* ------------------------------------------------------------------ */
    /* Calibration may only relax, never tighten                           */
    /*                                                                     */
    /* Every case below is a value the engine actually proposed on an idle  */
    /* mc-test run before the shipped defaults became floors.              */
    /* ------------------------------------------------------------------ */

    @Test
    void idleSampleCannotTightenTheSpikeThreshold() {
        // Measured: baseline 2.6 ms x 20 = 52 ms, below the shipped 100.
        double raw = clamp(2.6 * SPIKE_FACTOR_WITH_WORLDEDIT, SPIKE_MIN_MS, SPIKE_MAX_MS);
        double floored = Math.max(DEFAULT_SPIKE_TICK_MS, raw);

        assertEquals(52.0, raw, 1e-9, "the raw formula really does drop this low");
        assertEquals(DEFAULT_SPIKE_TICK_MS, floored, 1e-9, "the floor must catch it");
    }

    @Test
    void idleSampleCannotTightenChunkThresholds() {
        // Measured: p99 of 11 entities per chunk on an empty server.
        int raw = Math.max(CHUNK_ENTITY_MIN, (int) Math.ceil(11 * CHUNK_ENTITY_HEADROOM));
        int warning = Math.max(DEFAULT_CHUNK_ENTITY_WARNING, raw);
        int critical = Math.max(DEFAULT_CHUNK_ENTITY_CRITICAL,
                (int) Math.ceil(warning * CHUNK_CRITICAL_FACTOR));

        assertEquals(20, raw);
        assertEquals(DEFAULT_CHUNK_ENTITY_WARNING, warning);
        assertEquals(DEFAULT_CHUNK_ENTITY_CRITICAL, critical);
    }

    @Test
    void idleSampleCannotTightenWorldThresholds() {
        // Measured: p95 of 199 entities per world with 6 of 9 worlds unloaded.
        int raw = Math.max(WORLD_ENTITY_MIN, (int) Math.ceil(199 * WORLD_HEADROOM));
        int warning = Math.max(DEFAULT_WORLD_ENTITY_WARNING, raw);

        assertEquals(500, raw, "the raw formula would have cut this to a tenth");
        assertEquals(DEFAULT_WORLD_ENTITY_WARNING, warning);
    }

    @Test
    void aRaisedThresholdIsNeverPulledBackDown() {
        // Production: packet_flood_per_tick had been raised to 3000 after 671
        // real alerts between 1002 and 5104 packets/tick. A one-minute sample
        // on an empty server measured p99 = 49, and the shipped-default floor
        // alone still proposed 3000 -> 1000 - handing every one of those
        // alerts back. The configured value is the second half of the floor.
        double measured = Math.max(PACKET_MIN, 49 * PACKET_HEADROOM);
        double shippedFloorOnly = Math.max(DEFAULT_PACKET_FLOOD, measured);
        double proposed = floor(measured, DEFAULT_PACKET_FLOOD, 3000.0);

        assertEquals(1000.0, shippedFloorOnly, 1e-9, "the old floor really did land here");
        assertEquals(3000.0, proposed, 1e-9, "a configured 3000 must survive an idle sample");
    }

    @Test
    void aThresholdBelowTheDefaultIsStillRaised() {
        // The configured value is a floor, not an anchor: someone running
        // below the shipped default still gets pulled up to it.
        assertEquals(1000.0, floor(98.0, DEFAULT_PACKET_FLOOD, 500.0), 1e-9);
    }

    @Test
    void graceOnlyEverGrows() {
        // A 34 s boot must not halve the 120 s an admin set after phantom
        // boot incidents; a genuinely slower boot still widens it.
        int measuredFromGoodBoot = (int) Math.ceil(34 * GRACE_HEADROOM);
        assertEquals(120, floor(measuredFromGoodBoot, GRACE_MIN_SECONDS, 120));

        int measuredFromSlowBoot = (int) Math.ceil(200 * GRACE_HEADROOM);
        assertEquals(300, floor(measuredFromSlowBoot, GRACE_MIN_SECONDS, 120));
    }

    @Test
    void abusyServerStillGetsItsRelaxedThresholds() {
        // The floors must not block the whole point of the command: a server
        // whose measurements exceed the defaults still gets the wider limits.
        int raw = Math.max(CHUNK_ENTITY_MIN, (int) Math.ceil(120 * CHUNK_ENTITY_HEADROOM));
        int warning = Math.max(DEFAULT_CHUNK_ENTITY_WARNING, raw);

        assertEquals(156, warning, "relaxing upward must still work");
        assertTrue(warning > DEFAULT_CHUNK_ENTITY_WARNING);
    }
}
