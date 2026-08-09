package dev.boondock.performanceanalyzer.calibrate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result of one calibration run.
 *
 * <p>Deliberately inert: it describes what <em>would</em> change and why, and
 * carries no ability to change anything. Applying is a separate, explicit
 * step, so {@code /perfcalibrate} can never surprise an admin by writing on
 * inspection.
 *
 * @param values          proposed threshold changes (empty when nothing to do)
 * @param blockers        reasons the proposal must not be applied
 * @param warnings        reasons to be sceptical, but not disqualifying
 * @param notes           findings outside the threshold scope, purely FYI
 * @param observedMinutes length of the sampling window behind these numbers
 */
public record CalibrationProposal(
        List<ProposedValue> values,
        List<String> blockers,
        List<String> warnings,
        List<String> notes,
        long observedMinutes) {

    /**
     * A single config value the calibration would change.
     *
     * @param path     config path, e.g. {@code lag_analysis.chunk_entity_warning}
     * @param oldValue value currently in effect
     * @param newValue proposed value
     * @param reason   localized, human-readable justification
     */
    public record ProposedValue(String path, Object oldValue, Object newValue, String reason) {

        /** True when the proposal would not actually change anything. */
        public boolean isUnchanged() {
            return String.valueOf(oldValue).equals(String.valueOf(newValue));
        }
    }

    /** Applying is allowed only without blockers and with something to write. */
    public boolean canApply() {
        return blockers.isEmpty() && !changedValues().isEmpty();
    }

    /** Proposed values that differ from what is configured today. */
    public List<ProposedValue> changedValues() {
        return values.stream().filter(v -> !v.isUnchanged()).collect(Collectors.toList());
    }

    /** Flattened {@code path -> value} view for writing. */
    public Map<String, Object> asConfigMap() {
        return changedValues().stream()
                .collect(Collectors.toMap(ProposedValue::path, ProposedValue::newValue));
    }
}
