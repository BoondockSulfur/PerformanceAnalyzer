package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.MainThreadSampler;
import dev.boondock.performanceanalyzer.monitor.ServerErrorMonitor;
import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Writes a complete, plain-text account of every resolved incident to
 * {@code plugins/PerformanceAnalyzer/incidents.log}.
 *
 * <p>Alerts carry only the most likely cause, the database row only the key
 * numbers, and the full detail behind {@code /perfincidents} lives in memory
 * and is gone after a restart. This file is what is left to read days later:
 * what was measured, what triggered it, every finding with its location,
 * where the main thread spent its time down to the code line, the slowest
 * ticks with their stacks, and the errors the server logged meanwhile.
 */
public final class IncidentReport {

    private static final String FILE_NAME = "incidents.log";
    /** Rotated to incidents.log.1 beyond this size; one old file is kept. */
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Pattern COLOR_CODES = Pattern.compile("§x(§[0-9a-fA-F]){6}|§[0-9a-fk-orA-FK-OR]");
    private static final Pattern HEADER_ID = Pattern.compile("^=== .*?#(\\d+)");
    /** Incident reports and slow-tick entries append to the same file from different threads. */
    private static final Object FILE_LOCK = new Object();
    /** Context shown before a stall: long enough for a trade or command that preceded it. */
    static final long CONTEXT_BEFORE_MS = 30_000L;
    /** Slow ticks this close to an incident belong to its report. */
    static final long SLOW_TICK_MARGIN_MS = 5_000L;
    /** Date and time with zone: the database stores UTC, this file local time. */
    static final String DATE_TIME = "yyyy-MM-dd HH:mm:ss z";

    /** Turns sampler data into localized text; implemented by the analyzer. */
    interface Describer {
        String target(MainThreadSampler.SlowTick tick);

        String trigger(MainThreadSampler.Trigger trigger);

        /** Context lines (players, recent actions) for a time span; empty when nothing is known. */
        List<String> context(long fromMs, long toMs);
    }

    private IncidentReport() {
    }

    /** Title of the best finding, with its location when it has one; for the database row. */
    static String primaryCause(Incident incident) {
        for (Finding f : incident.findings()) {
            if (f.type() != Finding.Type.UNKNOWN) {
                return plain(f.title()) + (f.hasLocation()
                        ? " @" + f.worldName() + " " + f.blockX() + "," + f.blockZ() : "");
            }
        }
        return "-";
    }

    static void write(Plugin plugin, LanguageManager lang, Incident incident,
                      MainThreadSampler sampler, ServerErrorMonitor errors, Describer describe) {
        try {
            append(plugin, render(lang, incident, sampler, errors, describe));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[Incident] Could not write " + FILE_NAME + ": " + e.getMessage());
        }
    }

    /**
     * A slow tick outside any incident. Short stalls rarely move the tick
     * rate enough to open one, so without this entry they left no trace.
     */
    static void writeSlowTick(Plugin plugin, LanguageManager lang, MainThreadSampler.SlowTick tick, Describer describe) {
        try {
            List<String> out = new ArrayList<>();
            out.add(lang.format("report.slow_tick_header",
                    new SimpleDateFormat(DATE_TIME).format(new Date(tick.atMs())), tick.durationMs(),
                    describe.target(tick), flags(lang, tick)));
            appendSlowTickBody(out, lang, tick, describe, "  ");
            List<String> context = describe.context(tick.atMs() - (long) tick.durationMs() - CONTEXT_BEFORE_MS, tick.atMs());
            if (!context.isEmpty()) {
                out.add("  " + lang.format("report.context"));
                context.forEach(line -> out.add("  " + line));
            }
            for (String frame : tick.frames()) {
                out.add("    at " + frame);
            }
            out.replaceAll(IncidentReport::plain);
            append(plugin, out);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[Incident] Could not write " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private static void append(Plugin plugin, List<String> lines) {
        synchronized (FILE_LOCK) {
            try {
                File file = new File(plugin.getDataFolder(), FILE_NAME);
                if (file.length() > MAX_BYTES) {
                    Files.move(file.toPath(), new File(plugin.getDataFolder(), FILE_NAME + ".1").toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (String line : lines) {
                        writer.write(line);
                        writer.newLine();
                    }
                    writer.newLine();
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[Incident] Could not write " + FILE_NAME + ": " + e.getMessage());
            }
        }
    }

    /**
     * Highest incident number in the report files, 0 when there is none.
     * Seeds the counter once on installs that predate the persisted sequence,
     * so numbers keep counting instead of starting at #1 again.
     */
    static long highestLoggedId(File dataFolder) {
        long highest = 0;
        for (String name : new String[]{FILE_NAME, FILE_NAME + ".1"}) {
            File file = new File(dataFolder, name);
            if (!file.isFile()) {
                continue;
            }
            try (var lines = Files.lines(file.toPath(), StandardCharsets.UTF_8)) {
                highest = Math.max(highest, lines.mapToLong(IncidentReport::headerId).max().orElse(0));
            } catch (IOException | java.io.UncheckedIOException e) {
                // Unreadable: start from what the other file says.
            }
        }
        return highest;
    }

    static long headerId(String line) {
        var m = HEADER_ID.matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    static List<String> render(LanguageManager lang, Incident incident,
                               MainThreadSampler sampler, ServerErrorMonitor errors, Describer describe) {
        SimpleDateFormat time = new SimpleDateFormat(DATE_TIME);
        long end = incident.isActive() ? System.currentTimeMillis() : incident.startedAtMs() + incident.durationMs();
        List<String> out = new ArrayList<>();

        out.add(lang.format("report.header", incident.id(), time.format(new Date(incident.startedAtMs())),
                time.format(new Date(end)), incident.formattedDuration()));
        out.add(lang.format("report.values", incident.peakSeverity().name(), incident.peakScore(),
                incident.worstAvgMspt(), incident.worstTickMs(), incident.lowestTps()));
        if (incident.alertsSuppressed()) {
            out.add(lang.format("report.suppressed"));
        }

        out.add(lang.format("report.triggers"));
        for (String reason : incident.triggerReasons()) {
            out.add("  - " + reason);
        }

        out.add(lang.format("report.findings"));
        appendFindings(out, lang, incident.findings());
        if (!sameTitles(incident.openingFindings(), incident.findings())) {
            out.add(lang.format("report.opening_findings"));
            appendFindings(out, lang, incident.openingFindings());
        }

        if (!incident.profile().isEmpty()) {
            out.add(lang.format("report.profile"));
            for (String line : incident.profile()) {
                out.add("  " + line);
            }
        }

        if (sampler != null) {
            List<MainThreadSampler.SlowTick> ticks = new ArrayList<>();
            for (MainThreadSampler.SlowTick t : sampler.allSlowTicks()) {
                if (t.atMs() >= incident.startedAtMs() - SLOW_TICK_MARGIN_MS && t.atMs() <= end + SLOW_TICK_MARGIN_MS) {
                    ticks.add(t);
                }
            }
            ticks.sort((a, b) -> Double.compare(b.durationMs(), a.durationMs()));
            if (!ticks.isEmpty()) {
                out.add(lang.format("report.slow_ticks"));
                for (MainThreadSampler.SlowTick t : ticks.subList(0, Math.min(3, ticks.size()))) {
                    out.add(String.format(java.util.Locale.ROOT, "  %s  %.0f ms  %s%s",
                            new SimpleDateFormat("HH:mm:ss").format(new Date(t.atMs())), t.durationMs(),
                            describe.target(t), flags(lang, t)));
                    appendSlowTickBody(out, lang, t, describe, "      ");
                    for (String frame : t.frames()) {
                        out.add("      at " + frame);
                    }
                }
            }
        }

        List<String> context = describe.context(incident.startedAtMs() - CONTEXT_BEFORE_MS, end);
        if (!context.isEmpty()) {
            out.add(lang.format("report.context"));
            context.forEach(line -> out.add("  " + line));
        }

        if (errors != null && errors.isAttached()) {
            long window = Math.max(60_000L, System.currentTimeMillis() - incident.startedAtMs() + 30_000L);
            var recent = errors.recent(window);
            if (!recent.isEmpty()) {
                out.add(lang.format("report.errors"));
                for (var e : recent) {
                    out.add(String.format(java.util.Locale.ROOT, "  %dx %s %s: %s",
                            e.recentCount(), e.kind(), e.source(), e.lastMessage()));
                }
            }
        }
        out.replaceAll(IncidentReport::plain);
        return out;
    }

    private static String flags(LanguageManager lang, MainThreadSampler.SlowTick t) {
        return (t.waitKind() == MainThreadSampler.WaitKind.NONE ? "" : "  [" + t.waitKind() + "]")
                + (t.betweenTicks() ? "  " + lang.format("report.between_ticks") : "");
    }

    private static void appendSlowTickBody(List<String> out, LanguageManager lang, MainThreadSampler.SlowTick t,
                                           Describer describe, String indent) {
        String trigger = describe.trigger(t.trigger());
        if (!trigger.isEmpty()) {
            out.add(indent + lang.format("report.trigger", trigger));
        }
    }

    private static void appendFindings(List<String> out, LanguageManager lang, List<Finding> findings) {
        if (findings.isEmpty()) {
            out.add("  " + lang.format("alert.no_clear_cause"));
            return;
        }
        for (Finding f : findings) {
            out.add(String.format("  > [%s/%s] %s", f.severity().name(), f.confidence().name(), f.title()));
            for (String detailLine : f.detail().split("\n")) {
                out.add("      " + detailLine.strip());
            }
            if (f.hasLocation()) {
                out.add("      " + lang.format("report.location", f.worldName(), f.blockX(), f.blockZ()));
            }
            if (f.recommendation() != null && !f.recommendation().isEmpty()) {
                out.add("      " + lang.format("report.recommendation", f.recommendation()));
            }
        }
    }

    private static boolean sameTitles(List<Finding> a, List<Finding> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).title().equals(b.get(i).title())) {
                return false;
            }
        }
        return true;
    }

    static String plain(String text) {
        return COLOR_CODES.matcher(text).replaceAll("");
    }
}
