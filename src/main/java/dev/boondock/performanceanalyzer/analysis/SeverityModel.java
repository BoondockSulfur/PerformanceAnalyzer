package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.GcSampler;
import dev.boondock.performanceanalyzer.metrics.TickStats;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw measurements into a severity level plus a 0–100 urgency score.
 *
 * <p>The formula is deliberately simple and documented so admins can reason
 * about it. Inputs (10-second window unless noted):</p>
 * <ul>
 *   <li><b>Sustained load</b> — average MSPT against the 50 ms deadline.
 *       At 50 ms the server can no longer hold 20 TPS.</li>
 *   <li><b>Tail latency</b> — p95 MSPT; players feel stutter long before the
 *       average moves.</li>
 *   <li><b>GC pressure</b> — GC time in the last 60 s plus old-gen occupancy
 *       after the last collection.</li>
 *   <li><b>Baseline deviation</b> — how far the server is from its own
 *       learned normal, once the baseline is established.</li>
 * </ul>
 */
public final class SeverityModel {

    /** Result of one evaluation: level, numeric urgency and the reasons for it. */
    public record Assessment(Severity severity, int score, List<String> reasons) {

        public static final Assessment OK = new Assessment(Severity.OK, 0, List.of());
    }

    private SeverityModel() {
    }

    /** Locale-independent variant (used by tests); reasons fall back to English. */
    public static Assessment evaluate(TickStats ticks, GcSampler.GcStats gc, Baseline baseline) {
        return evaluate(ticks, gc, baseline, null);
    }

    public static Assessment evaluate(TickStats ticks, GcSampler.GcStats gc, Baseline baseline, LanguageManager lang) {
        if (ticks == null || !ticks.hasData()) {
            return Assessment.OK;
        }

        List<String> reasons = new ArrayList<>(4);
        double avg = ticks.msptAvg10s();
        double p95 = ticks.msptP95();
        double tps = ticks.tps10s();

        // --- Numeric urgency score (0–100) ------------------------------
        // 45 points: sustained load. avg == 50 ms -> all 45 points.
        double loadScore = Math.min(45.0, avg / 50.0 * 45.0);
        // 30 points: tail latency. p95 == 100 ms -> all 30 points.
        double tailScore = Math.min(30.0, p95 / 100.0 * 30.0);
        // 25 points: GC pressure. 6 s GC/min or old gen 95% after GC -> full points.
        double gcScore = 0;
        if (gc != null) {
            gcScore = Math.min(15.0, gc.gcTimeMs60s() / 6000.0 * 15.0);
            if (gc.hasAfterGcData() && gc.oldGenAfterGcPercent() > 70) {
                gcScore += Math.min(10.0, (gc.oldGenAfterGcPercent() - 70) / 25.0 * 10.0);
            }
        }
        int score = (int) Math.round(Math.min(100.0, loadScore + tailScore + gcScore));

        // --- Severity level ---------------------------------------------
        Severity severity = Severity.OK;

        if (tps < 10.0 || avg >= 100.0) {
            severity = Severity.EMERGENCY;
            reasons.add(reason(lang, "severity.reason.emergency", "Server running at %.1f TPS (avg tick %.1f ms)", tps, avg));
        } else if (avg >= 50.0 || tps < 17.0) {
            severity = Severity.CRITICAL;
            reasons.add(reason(lang, "severity.reason.critical", "Cannot hold 20 TPS: avg tick %.1f ms, TPS %.1f", avg, tps));
        } else if (p95 >= 50.0 || avg >= 35.0) {
            severity = Severity.WARNING;
            reasons.add(reason(lang, "severity.reason.warning", "Noticeable stutter: p95 tick %.1f ms, avg %.1f ms", p95, avg));
        }

        if (gc != null) {
            if (gc.hasAfterGcData() && gc.oldGenAfterGcPercent() >= 90) {
                severity = max(severity, Severity.CRITICAL);
                reasons.add(reason(lang, "severity.reason.memory", "Old gen still at %.0f%% after GC - real memory pressure", gc.oldGenAfterGcPercent()));
            } else if (gc.gcTimeMs60s() >= 3000 || gc.longestPauseMs60s() >= 200) {
                severity = max(severity, Severity.WARNING);
                reasons.add(reason(lang, "severity.reason.gc", "GC load: %d ms in 60 s, longest pause ~%.0f ms",
                        gc.gcTimeMs60s(), gc.longestPauseMs60s()));
            }
        }

        // Baseline deviation: only meaningful when otherwise still "OK"-ish.
        if (baseline != null && baseline.isEstablished() && severity.level() < Severity.WARNING.level()) {
            double normal = baseline.avgMspt();
            if (avg > normal * 2.0 + 10.0) {
                severity = max(severity, Severity.WARNING);
                reasons.add(reason(lang, "severity.reason.baseline_double", "Avg tick %.1f ms - more than double this server's normal (%.1f ms)", avg, normal));
            } else if (avg > normal * 1.5 + 5.0) {
                severity = max(severity, Severity.NOTICE);
                reasons.add(reason(lang, "severity.reason.baseline_elevated", "Avg tick %.1f ms - clearly above this server's normal (%.1f ms)", avg, normal));
            }
        }

        if (severity == Severity.OK && ticks.msptMax10s() >= 150.0) {
            severity = Severity.NOTICE;
            reasons.add(reason(lang, "severity.reason.spike", "Single tick spike of %.0f ms", ticks.msptMax10s()));
        }

        return new Assessment(severity, score, List.copyOf(reasons));
    }

    private static Severity max(Severity a, Severity b) {
        return a.atLeast(b) ? a : b;
    }

    private static String reason(LanguageManager lang, String key, String fallback, Object... args) {
        if (lang != null) {
            String formatted = lang.format(key, args);
            if (!formatted.equals(key)) {
                return formatted;
            }
        }
        return String.format(fallback, args);
    }
}
