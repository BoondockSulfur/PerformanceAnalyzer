package dev.boondock.performanceanalyzer.metrics;

public class MemorySampler {
    public double heapUsagePercent() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        if (max <= 0) return 0.0;
        return (used * 100.0) / max;
    }
}
