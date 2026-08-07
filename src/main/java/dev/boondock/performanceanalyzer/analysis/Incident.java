package dev.boondock.performanceanalyzer.analysis;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * One performance incident with a full lifecycle: opened when the severity
 * model crosses WARNING, updated with peak values and fresh findings while
 * active, and resolved once the server has been healthy again for a grace
 * period. Replaces the v2.x "performance drop" snapshots, whose stored
 * "lowest TPS" was in fact just a single 60-second average.
 */
public final class Incident {

    private final long id;
    private final long startedAtMs;
    private final List<String> triggerReasons;

    private volatile long resolvedAtMs = -1;
    private volatile Severity currentSeverity;
    private volatile Severity peakSeverity;
    private volatile int peakScore;
    private volatile double worstTickMs;
    private volatile double worstAvgMspt;
    private volatile double lowestTps = 20.0;
    private volatile List<Finding> findings = List.of();

    Incident(long id, long startedAtMs, Severity severity, int score, List<String> triggerReasons) {
        this.id = id;
        this.startedAtMs = startedAtMs;
        this.currentSeverity = severity;
        this.peakSeverity = severity;
        this.peakScore = score;
        this.triggerReasons = List.copyOf(triggerReasons);
    }

    /* Updated by IncidentAnalyzer only. */

    void update(Severity severity, int score, double avgMspt, double worstTick, double tps) {
        this.currentSeverity = severity;
        if (severity.atLeast(peakSeverity)) {
            peakSeverity = severity;
        }
        if (score > peakScore) {
            peakScore = score;
        }
        if (avgMspt > worstAvgMspt) {
            worstAvgMspt = avgMspt;
        }
        if (worstTick > worstTickMs) {
            worstTickMs = worstTick;
        }
        if (tps >= 0 && tps < lowestTps) {
            lowestTps = tps;
        }
    }

    void setFindings(List<Finding> findings) {
        this.findings = List.copyOf(findings);
    }

    void resolve(long atMs) {
        this.resolvedAtMs = atMs;
    }

    /* Read API */

    public long id() {
        return id;
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    public boolean isActive() {
        return resolvedAtMs < 0;
    }

    public long durationMs() {
        long end = resolvedAtMs < 0 ? System.currentTimeMillis() : resolvedAtMs;
        return Math.max(0, end - startedAtMs);
    }

    public Severity currentSeverity() {
        return currentSeverity;
    }

    public Severity peakSeverity() {
        return peakSeverity;
    }

    public int peakScore() {
        return peakScore;
    }

    public double worstTickMs() {
        return worstTickMs;
    }

    public double worstAvgMspt() {
        return worstAvgMspt;
    }

    public double lowestTps() {
        return lowestTps;
    }

    public List<Finding> findings() {
        return findings;
    }

    public List<String> triggerReasons() {
        return triggerReasons;
    }

    public String formattedStart() {
        return new SimpleDateFormat("dd.MM. HH:mm:ss").format(new Date(startedAtMs));
    }

    public String formattedDuration() {
        long seconds = durationMs() / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m " + (seconds % 60) + "s";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
