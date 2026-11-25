package dev.boondock.performanceanalyzer.db;

/** One queued measurement; {@code atMs} is when it was taken, not when it is written. */
public record LogEntry(String type, double value, String description, long atMs) {}
