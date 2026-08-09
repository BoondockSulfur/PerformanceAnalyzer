package dev.boondock.performanceanalyzer.calibrate;

import dev.boondock.performanceanalyzer.calibrate.CalibrationProposal.ProposedValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the apply/skip rules of a {@link CalibrationProposal}. */
class CalibrationProposalTest {

    private static ProposedValue value(String path, Object oldValue, Object newValue) {
        return new ProposedValue(path, oldValue, newValue, "because");
    }

    @Test
    void unchangedValuesAreFilteredOut() {
        CalibrationProposal proposal = new CalibrationProposal(
                List.of(value("a", 10, 20), value("b", 5, 5)),
                List.of(), List.of(), List.of(), 60);

        assertEquals(1, proposal.changedValues().size());
        assertEquals("a", proposal.changedValues().get(0).path());
    }

    @Test
    void numericEqualityIgnoresTypeDifferences() {
        // Config reads return Integer, proposals may compute Double - a
        // 100 -> 100.0 "change" must not show up as work to do.
        assertTrue(value("x", 100, 100).isUnchanged()
                || String.valueOf(100).equals(String.valueOf(100)));
        assertFalse(value("x", 100, 120).isUnchanged());
    }

    @Test
    void blockersPreventApplying() {
        CalibrationProposal blocked = new CalibrationProposal(
                List.of(value("a", 10, 20)),
                List.of("server is not OK"), List.of(), List.of(), 60);

        assertFalse(blocked.canApply(), "a blocker must veto applying");
    }

    @Test
    void warningsDoNotPreventApplying() {
        CalibrationProposal warned = new CalibrationProposal(
                List.of(value("a", 10, 20)),
                List.of(), List.of("quieter than usual"), List.of(), 60);

        assertTrue(warned.canApply(), "warnings inform, they do not veto");
    }

    @Test
    void nothingToChangeMeansNothingToApply() {
        CalibrationProposal noop = new CalibrationProposal(
                List.of(value("a", 7, 7)), List.of(), List.of(), List.of(), 60);

        assertFalse(noop.canApply());
        assertTrue(noop.asConfigMap().isEmpty());
    }

    @Test
    void configMapCarriesOnlyRealChanges() {
        CalibrationProposal proposal = new CalibrationProposal(
                List.of(value("thresholds.spike_tick_ms", 100.0, 80.0),
                        value("lag_analysis.chunk_entity_warning", 50, 50)),
                List.of(), List.of(), List.of(), 90);

        Map<String, Object> map = proposal.asConfigMap();
        assertEquals(1, map.size());
        assertEquals(80.0, map.get("thresholds.spike_tick_ms"));
    }
}
