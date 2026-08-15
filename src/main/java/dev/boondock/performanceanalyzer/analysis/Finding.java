package dev.boondock.performanceanalyzer.analysis;

/**
 * One attributed cause (or suspect) of a performance problem.
 *
 * <p>Every finding carries its {@link Confidence}: {@code MEASURED} means the
 * underlying number is a real measurement (GC time, listener nanos, event
 * rates); {@code HEURISTIC} means it is derived or correlated (e.g. "most
 * active chunk while lag was observed"). The distinction is shown to the
 * admin so the tool never presents a guess as a fact — the core failure of
 * the v2.x analyzer.</p>
 */
public record Finding(
        Type type,
        Severity severity,
        Confidence confidence,
        String title,
        String detail,
        String recommendation,
        String worldName,
        Integer blockX,
        Integer blockZ,
        double metric) {

    public enum Confidence {
        MEASURED,
        HEURISTIC
    }

    public enum Type {
        WORLD_SAVE,
        EXPENSIVE_COMMAND,
        /** A player arrived somewhere that had to be loaded: login or far teleport. */
        PLAYER_ARRIVAL,
        UNMEASURED_STALL,
        GC_PRESSURE,
        MEMORY_PRESSURE,
        PLUGIN_LOAD,
        HOT_CHUNK,
        CHUNK_GENERATION,
        CHUNK_CHURN,
        ENTITY_HOTSPOT,
        PACKET_FLOOD,
        UNKNOWN
    }

    public boolean hasLocation() {
        return worldName != null && blockX != null && blockZ != null;
    }

    public static Finding global(Type type, Severity severity, Confidence confidence,
                                 String title, String detail, String recommendation, double metric) {
        return new Finding(type, severity, confidence, title, detail, recommendation, null, null, null, metric);
    }

    public static Finding located(Type type, Severity severity, Confidence confidence,
                                  String title, String detail, String recommendation,
                                  String worldName, int blockX, int blockZ, double metric) {
        return new Finding(type, severity, confidence, title, detail, recommendation, worldName, blockX, blockZ, metric);
    }
}
