package dev.boondock.performanceanalyzer.util;

import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.metrics.TickSampler;

import java.util.List;

public class AsciiSparkline {
    private static final char[] BARS = { '▁','▂','▃','▄','▅','▆','▇','█' };

    public static String spark(double[] values) {
        if (values == null || values.length == 0) return "";
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : values) { if (v < min) min = v; if (v > max) max = v; }
        if (!Double.isFinite(min) || !Double.isFinite(max) || max - min < 1e-9) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.length; i++) sb.append(BARS[0]);
            return sb.toString();
        }
        StringBuilder out = new StringBuilder();
        for (double v : values) {
            double norm = (v - min) / (max - min);
            int idx = (int) Math.round(norm * (BARS.length - 1));
            if (idx < 0) idx = 0; if (idx >= BARS.length) idx = BARS.length - 1;
            out.append(BARS[idx]);
        }
        return out.toString();
    }

    /**
     * Gets real historical MSPT data from the database.
     * Falls back to current live data if DB has no data yet.
     * @param db DatabaseManager instance
     * @param sampler TickSampler for fallback live data
     * @param minutesBack How many minutes of history to show
     * @return Array of MSPT values for sparkline visualization
     */
    public static double[] recentMsptWindow(DatabaseManager db, TickSampler sampler, int minutesBack) {
        List<Double> dbValues = db.getRecentMspt(minutesBack);

        // If we have enough DB data, use it
        if (!dbValues.isEmpty()) {
            // Sample down to max 60 points for readability
            int targetSize = Math.min(60, dbValues.size());
            double[] result = new double[targetSize];
            int step = Math.max(1, dbValues.size() / targetSize);

            for (int i = 0; i < targetSize; i++) {
                int idx = Math.min(i * step, dbValues.size() - 1);
                result[i] = dbValues.get(idx);
            }
            return result;
        }

        // Fallback: No DB data yet, show current live average as flat line
        double currentAvg = sampler.msptAvg();
        double[] fallback = new double[20];
        for (int i = 0; i < fallback.length; i++) {
            fallback[i] = currentAvg;
        }
        return fallback;
    }
}
