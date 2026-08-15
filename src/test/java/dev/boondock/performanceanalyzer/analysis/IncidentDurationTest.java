package dev.boondock.performanceanalyzer.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reported duration of an incident must be the time the server was
 * actually unhealthy — not that time plus the grace period the analyzer waits
 * to confirm the recovery held.
 */
class IncidentDurationTest {

    private static Incident opened(long startedAtMs) {
        return new Incident(1, startedAtMs, Severity.WARNING, 30, List.of("test"));
    }

    @Test
    void durationEndsWhenTheServerRecovered() {
        // Measured on production: elevated for 12 s, announced as "Dauer: 42s"
        // because the fixed 30 s resolve grace was counted as part of it.
        long start = 1_000_000L;
        long recovered = start + 12_000L;

        Incident incident = opened(start);
        incident.resolve(recovered);

        assertEquals(12_000L, incident.durationMs());
        assertEquals("12s", incident.formattedDuration());
    }

    @Test
    void anActiveIncidentStillReportsElapsedTime() {
        Incident incident = opened(System.currentTimeMillis() - 5_000L);

        assertTrue(incident.isActive());
        assertTrue(incident.durationMs() >= 5_000L, "a running incident measures against now");
    }

    @Test
    void durationIsNeverNegative() {
        // Defensive: clock adjustments must not produce a negative duration.
        Incident incident = opened(2_000_000L);
        incident.resolve(1_000_000L);

        assertEquals(0L, incident.durationMs());
    }
}
