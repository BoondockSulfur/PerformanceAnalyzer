package dev.boondock.performanceanalyzer.monitor;

import dev.boondock.performanceanalyzer.analysis.Severity;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks errors out of the server log that the measurement core cannot see.
 *
 * <p>A tick that throws, an update suppressor that ends in a
 * {@link StackOverflowError}, a plugin listener failing on every event, or
 * vanilla giving up on a neighbor-update chain ("Too many chained neighbor
 * updates") all cost tick time or break a machine, but none of them show up
 * in listener timings, chunk activity or GC numbers. They are only ever
 * written to the log, so that is where this reads them.
 *
 * <p>Only WARN and above are looked at, and only a handful of string checks
 * run per line, so the cost stays negligible even on a log that spams.
 * Repeated errors are aggregated per kind and source; the sink hears about
 * each one at most once per {@link #REPORT_INTERVAL_MS}.
 */
public final class ServerErrorMonitor extends AbstractAppender {

    public enum Kind {
        NEIGHBOR_CHAIN,
        STACK_OVERFLOW,
        OUT_OF_MEMORY,
        SERVER_EXCEPTION,
        PLUGIN_EXCEPTION
    }

    /** One kind of error from one source, aggregated. */
    public record ErrorSummary(Kind kind, String source, String lastMessage, long lastAtMs, int recentCount) {

        /**
         * StackOverflow and OutOfMemory break the server outright; an aborted
         * update chain or a tick exception breaks a machine or an entity; a
         * plugin error matters once it floods.
         */
        public Severity severity() {
            return switch (kind) {
                case STACK_OVERFLOW, OUT_OF_MEMORY -> Severity.CRITICAL;
                case NEIGHBOR_CHAIN, SERVER_EXCEPTION -> Severity.WARNING;
                case PLUGIN_EXCEPTION -> recentCount >= 100 ? Severity.WARNING : Severity.NOTICE;
            };
        }
    }

    /** Receives each error key at most once per report interval. */
    public interface Sink {
        void onError(ErrorSummary summary);
    }

    record Classification(Kind kind, String source) {
    }

    private static final String APPENDER_NAME = "PerformanceAnalyzer-ErrorMonitor";
    private static final long REPORT_INTERVAL_MS = 60_000L;
    /** Timestamps kept per error key; enough to tell "once" from "flooding". */
    private static final int RECENT_KEPT = 128;
    private static final int MAX_KEYS = 256;
    /** Error keys not seen for this long are dropped, so old ones never block new ones. */
    private static final long FORGET_AFTER_MS = 60 * 60_000L;
    private static final int MAX_MESSAGE_LENGTH = 160;

    private static final String NEIGHBOR_CHAIN_TEXT = "Too many chained neighbor updates";
    private static final Pattern NEIGHBOR_POSITION = Pattern.compile("First skipped position: (.+)$");
    private static final Pattern PASS_EVENT = Pattern.compile("Could not pass event \\S+ to (\\S+)");
    private static final Pattern TASK_EXCEPTION = Pattern.compile("Plugin (\\S+) v\\S+ generated an exception");
    private static final Pattern PLUGIN_LIFECYCLE =
            Pattern.compile("Error occurred while (?:enabling|disabling|loading) (\\S+)");

    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final class Aggregate {
        final long[] recent = new long[RECENT_KEPT];
        int index;
        volatile String lastMessage = "";
        volatile long lastAtMs;
        long lastReportedAtMs;

        synchronized void record(long atMs, String message) {
            recent[index] = atMs;
            index = (index + 1) % recent.length;
            lastMessage = message;
            lastAtMs = atMs;
        }

        synchronized int countSince(long sinceMs) {
            int count = 0;
            for (long at : recent) {
                if (at >= sinceMs && at > 0) {
                    count++;
                }
            }
            return count;
        }

        synchronized boolean dueForReport(long now) {
            if (now - lastReportedAtMs < REPORT_INTERVAL_MS) {
                return false;
            }
            lastReportedAtMs = now;
            return true;
        }
    }

    private record Key(Kind kind, String source) {
    }

    private final String ownLoggerName;
    private final Supplier<Set<String>> pluginNames;
    private final ConcurrentHashMap<Key, Aggregate> errors = new ConcurrentHashMap<>();
    private volatile Sink sink;
    private volatile boolean attached;

    /**
     * @param ownLoggerName this plugin's logger name: its own warnings must
     *                      never be read back, or an alert about an error
     *                      would be counted as the next error
     * @param pluginNames   names of the installed plugins, for attributing
     *                      errors logged under a plugin's own logger
     */
    public ServerErrorMonitor(String ownLoggerName, Supplier<Set<String>> pluginNames) {
        // ignoreExceptions = true: a failure here must never reach the code
        // that was trying to log.
        super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
        this.ownLoggerName = ownLoggerName;
        this.pluginNames = pluginNames;
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void append(LogEvent event) {
        if (Boolean.TRUE.equals(INSIDE.get())) {
            return;
        }
        INSIDE.set(Boolean.TRUE);
        try {
            Level level = event.getLevel();
            if (level == null || !level.isMoreSpecificThan(Level.WARN)) {
                return;
            }
            String loggerName = event.getLoggerName();
            if (ownLoggerName.equals(loggerName)) {
                return;
            }
            // Copy everything out now: Log4j recycles the event object.
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            Throwable thrown = event.getThrown();
            Classification c = classify(loggerName, level.isMoreSpecificThan(Level.ERROR), message, thrown,
                    pluginNames.get());
            if (c != null) {
                record(c, describe(message, thrown), event.getTimeMillis());
            }
        } catch (Throwable ignored) {
            // Never log from inside the logging path.
        } finally {
            INSIDE.set(Boolean.FALSE);
        }
    }

    /**
     * Decides whether a log line is an error worth keeping, and whose it is.
     * Package-private and static so the patterns stay under test.
     */
    static Classification classify(String loggerName, boolean errorLevel, String message, Throwable thrown,
                                   Set<String> pluginNames) {
        if (message.contains(NEIGHBOR_CHAIN_TEXT)) {
            Matcher m = NEIGHBOR_POSITION.matcher(message);
            return new Classification(Kind.NEIGHBOR_CHAIN, m.find() ? m.group(1).trim() : "?");
        }
        if (hasCause(thrown, StackOverflowError.class)) {
            return new Classification(Kind.STACK_OVERFLOW, sourceOf(loggerName, message, pluginNames, "server"));
        }
        if (hasCause(thrown, OutOfMemoryError.class)) {
            return new Classification(Kind.OUT_OF_MEMORY, "heap");
        }
        // WARN lines without a stack trace are advice, not failures.
        if (thrown == null && !errorLevel) {
            return null;
        }
        String plugin = pluginOf(loggerName, message, pluginNames);
        if (plugin != null) {
            return new Classification(Kind.PLUGIN_EXCEPTION, plugin);
        }
        if (thrown != null) {
            return new Classification(Kind.SERVER_EXCEPTION, thrown.getClass().getSimpleName());
        }
        return null;
    }

    private static String sourceOf(String loggerName, String message, Set<String> pluginNames, String fallback) {
        String plugin = pluginOf(loggerName, message, pluginNames);
        return plugin != null ? plugin : fallback;
    }

    private static String pluginOf(String loggerName, String message, Set<String> pluginNames) {
        for (Pattern p : new Pattern[]{PASS_EVENT, TASK_EXCEPTION, PLUGIN_LIFECYCLE}) {
            Matcher m = p.matcher(message);
            if (m.find()) {
                return m.group(1);
            }
        }
        if (loggerName != null && pluginNames.contains(loggerName)) {
            return loggerName;
        }
        return null;
    }

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        Throwable t = thrown;
        for (int depth = 0; t != null && depth < 16; depth++) {
            if (type.isInstance(t)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static String describe(String message, Throwable thrown) {
        String text = message == null ? "" : message.strip();
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline);
        }
        if (thrown != null) {
            String cause = thrown.getClass().getSimpleName()
                    + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
            text = text.isEmpty() ? cause : text + " (" + cause + ")";
        }
        return text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) + "…" : text;
    }

    private void record(Classification c, String message, long atMs) {
        Key key = new Key(c.kind(), c.source());
        Aggregate agg = errors.get(key);
        if (agg == null) {
            if (errors.size() >= MAX_KEYS) {
                forgetOlderThan(atMs - FORGET_AFTER_MS);
                if (errors.size() >= MAX_KEYS) {
                    return;
                }
            }
            agg = errors.computeIfAbsent(key, k -> new Aggregate());
        }
        agg.record(atMs, message);
        Sink s = sink;
        if (s != null && agg.dueForReport(atMs)) {
            s.onError(new ErrorSummary(c.kind(), c.source(), message, atMs,
                    agg.countSince(atMs - REPORT_INTERVAL_MS)));
        }
    }

    private void forgetOlderThan(long cutoffMs) {
        errors.values().removeIf(agg -> agg.lastAtMs < cutoffMs);
    }

    /** Errors seen within the last {@code windowMs}, most frequent first. */
    public List<ErrorSummary> recent(long windowMs) {
        long now = System.currentTimeMillis();
        forgetOlderThan(now - FORGET_AFTER_MS);
        long since = now - windowMs;
        List<ErrorSummary> result = new ArrayList<>();
        for (var entry : errors.entrySet()) {
            Aggregate agg = entry.getValue();
            if (agg.lastAtMs < since) {
                continue;
            }
            result.add(new ErrorSummary(entry.getKey().kind(), entry.getKey().source(),
                    agg.lastMessage, agg.lastAtMs, agg.countSince(since)));
        }
        result.sort(Comparator.comparingInt(ErrorSummary::recentCount).reversed());
        return result;
    }

    public boolean isAttached() {
        return attached;
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    /** @return true when the appender was attached */
    public synchronized boolean attach(Logger log) {
        if (attached) {
            return true;
        }
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();
            start();
            configuration.getRootLogger().addAppender(this, Level.WARN, null);
            context.updateLoggers();
            attached = true;
            return true;
        } catch (Throwable t) {
            // A different logging backend must not stop the rest of the plugin.
            log.warning("[Errors] Could not attach to the server log: " + t);
            return false;
        }
    }

    /** Detaching matters: a stale appender would survive a plugin reload. */
    public synchronized void detach(Logger log) {
        if (!attached) {
            return;
        }
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.getConfiguration().getRootLogger().removeAppender(APPENDER_NAME);
            context.updateLoggers();
            stop();
        } catch (Throwable t) {
            log.warning("[Errors] Could not detach from the server log: " + t);
        }
        attached = false;
    }
}
