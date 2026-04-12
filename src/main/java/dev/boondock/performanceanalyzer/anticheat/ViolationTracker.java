package dev.boondock.performanceanalyzer.anticheat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks violation counts per player for cheat detection.
 */
public class ViolationTracker {

    private final Map<UUID, PlayerViolations> violations = new ConcurrentHashMap<>();

    public void addViolation(UUID playerId, ViolationType type) {
        violations.computeIfAbsent(playerId, k -> new PlayerViolations()).increment(type);
    }

    public int getViolationCount(UUID playerId, ViolationType type) {
        PlayerViolations pv = violations.get(playerId);
        return pv != null ? pv.getCount(type) : 0;
    }

    public void resetViolations(UUID playerId) {
        violations.remove(playerId);
    }

    public void resetViolations(UUID playerId, ViolationType type) {
        violations.computeIfPresent(playerId, (k, pv) -> {
            pv.counts.remove(type);
            return pv;
        });
    }

    public void decay(UUID playerId, ViolationType type, int amount) {
        PlayerViolations pv = violations.get(playerId);
        if (pv != null) {
            pv.decay(type, amount);
        }
    }

    private static class PlayerViolations {
        private final Map<ViolationType, Integer> counts = new ConcurrentHashMap<>();

        void increment(ViolationType type) {
            counts.merge(type, 1, Integer::sum);
        }

        int getCount(ViolationType type) {
            return counts.getOrDefault(type, 0);
        }

        void decay(ViolationType type, int amount) {
            counts.computeIfPresent(type, (k, v) -> Math.max(0, v - amount));
        }
    }

    public enum ViolationType {
        FLY,
        SPEED,
        IMPOSSIBLE_MOVEMENT,
        NO_FALL
    }
}
