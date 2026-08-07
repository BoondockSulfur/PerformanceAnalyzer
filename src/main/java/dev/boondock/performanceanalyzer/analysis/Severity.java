package dev.boondock.performanceanalyzer.analysis;

/**
 * Urgency levels for performance findings and incidents.
 * Order matters: {@link #ordinal()} is used for comparisons and escalation.
 */
public enum Severity {

    /** Everything within normal bounds. */
    OK("§a", 0),
    /** Noticeable but harmless — worth a look, no action required. */
    NOTICE("§e", 1),
    /** Degradation players can start to feel; action recommended. */
    WARNING("§6", 2),
    /** Server cannot hold 20 TPS; action required. */
    CRITICAL("§c", 3),
    /** Server severely degraded; immediate action required. */
    EMERGENCY("§4", 4);

    private final String color;
    private final int level;

    Severity(String color, int level) {
        this.color = color;
        this.level = level;
    }

    public String color() {
        return color;
    }

    public int level() {
        return level;
    }

    public boolean atLeast(Severity other) {
        return this.level >= other.level;
    }
}
