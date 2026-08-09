package dev.boondock.performanceanalyzer.calibrate;

import dev.boondock.performanceanalyzer.analysis.IncidentAnalyzer;
import dev.boondock.performanceanalyzer.analysis.Severity;
import dev.boondock.performanceanalyzer.calibrate.CalibrationProposal.ProposedValue;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.monitor.MonitorService;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives threshold values from what this server actually did.
 *
 * <p>Only thresholds are proposed. Everything else a first-time setup gets
 * wrong - a port already taken, a missing soft dependency - is reported as a
 * note and left alone: those are decisions with context the plugin does not
 * have, and a wrong automatic guess there is expensive.
 *
 * <p>Thresholds do not maintain themselves afterwards. That is deliberate: a
 * threshold that follows its own measurements upward would let a slowly
 * degrading server raise its own bar until nothing is ever reported again -
 * the same trap that made the tick baseline learn only while healthy.
 */
public final class CalibrationEngine {

    /** Below this the sample cannot describe normal operation. */
    static final long MIN_WINDOW_MINUTES = 30;
    /** Per-chunk observations needed before chunk thresholds are proposed. */
    static final long MIN_CHUNK_OBSERVATIONS = 500;
    /** History window for the representativeness check. */
    private static final int HISTORY_DAYS = 30;

    /**
     * Shipped defaults, used as floors.
     *
     * <p>Calibration may only ever <em>relax</em> a threshold, never tighten
     * it below what the plugin ships with. Its job is to stop false alarms,
     * and that means raising limits; a tighter limit buys no better detection,
     * only more noise - the adaptive half of detection is the learned tick
     * baseline in the severity model, not these backstops.
     *
     * <p>Without this floor a calibration run on an idle server proposed
     * spike_tick_ms 100 -> 52 and world_entity_warning 5000 -> 500, values
     * that would have alerted on ordinary play.
     */
    static final double DEFAULT_SPIKE_TICK_MS = 100.0;
    static final double DEFAULT_PACKET_FLOOD = 1000.0;
    static final int DEFAULT_CHUNK_ENTITY_WARNING = 50;
    static final int DEFAULT_CHUNK_ENTITY_CRITICAL = 100;
    static final int DEFAULT_CHUNK_TILE_ENTITIES = 10;
    static final int DEFAULT_WORLD_ENTITY_WARNING = 5_000;
    static final int DEFAULT_WORLD_ENTITY_CRITICAL = 10_000;

    /** Above this share of worlds without loaded chunks the sample is void. */
    static final double MAX_EMPTY_WORLD_SHARE = 1.0 / 3.0;

    /* Formula constants - see proposeX() for the reasoning behind each. */
    static final double SPIKE_FACTOR = 15.0;
    static final double SPIKE_FACTOR_WITH_WORLDEDIT = 20.0;
    static final double SPIKE_MIN_MS = 50.0;
    static final double SPIKE_MAX_MS = 200.0;
    static final double SPIKE_MAX_MS_WITH_WORLDEDIT = 300.0;
    static final double PACKET_HEADROOM = 2.0;
    static final double PACKET_MIN = 1000.0;
    static final double CHUNK_ENTITY_HEADROOM = 1.3;
    static final double CHUNK_CRITICAL_FACTOR = 1.5;
    static final int CHUNK_ENTITY_MIN = 20;
    static final double TILE_HEADROOM = 1.3;
    static final int TILE_MIN = 10;
    static final double WORLD_HEADROOM = 1.5;
    static final double WORLD_CRITICAL_FACTOR = 1.6;
    static final int WORLD_ENTITY_MIN = 500;
    static final double GRACE_HEADROOM = 1.5;
    static final int GRACE_MIN_SECONDS = 60;

    private final Plugin plugin;
    private final PluginConfig config;
    private final LanguageManager lang;
    private final MonitorService monitor;
    private final IncidentAnalyzer incidents;
    private final DatabaseManager database;
    private final CalibrationSampler sampler;

    public CalibrationEngine(Plugin plugin, PluginConfig config, LanguageManager lang,
                             MonitorService monitor, IncidentAnalyzer incidents,
                             DatabaseManager database, CalibrationSampler sampler) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.monitor = monitor;
        this.incidents = incidents;
        this.database = database;
        this.sampler = sampler;
    }

    /**
     * Builds a proposal. Safe to call off the main thread and does so by
     * design - it queries the database.
     */
    public CalibrationProposal analyze() {
        List<ProposedValue> values = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        long windowMinutes = sampler.observedMinutes();

        checkHealth(blockers);
        checkWindow(windowMinutes, blockers);
        checkRepresentativeness(blockers, warnings);

        proposeSpikeThreshold(values);
        proposePacketThreshold(values, notes);
        proposeChunkThresholds(values);
        proposeWorldThresholds(values);
        proposeStartupGrace(values);

        collectNotes(notes);

        return new CalibrationProposal(List.copyOf(values), List.copyOf(blockers),
                List.copyOf(warnings), List.copyOf(notes), windowMinutes);
    }

    /* ------------------------------------------------------------------ */
    /* Guards                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Calibrating a struggling server bakes its trouble in as the new normal.
     * Refuse unless everything is currently healthy.
     */
    private void checkHealth(List<String> blockers) {
        Severity severity = monitor.assessment().severity();
        if (severity != Severity.OK) {
            blockers.add(lang.get("calibrate.block.not_ok", "%status%", severity.name()));
        }
        if (incidents.activeIncident() != null) {
            blockers.add(lang.get("calibrate.block.incident"));
        }
    }

    private void checkWindow(long windowMinutes, List<String> blockers) {
        if (windowMinutes < MIN_WINDOW_MINUTES) {
            blockers.add(lang.get("calibrate.block.window",
                    "%minutes%", String.valueOf(windowMinutes),
                    "%needed%", String.valueOf(MIN_WINDOW_MINUTES)));
            blockers.add(lang.get("calibrate.block.window_hint"));
        }
    }

    /**
     * A server can run for hours and still produce a useless sample: at night
     * one admin is online and most worlds sit at zero loaded chunks.
     *
     * <p>Comparing against the whole retention window (not against the same
     * hour of day) is intentional - the question is "is this like normal
     * use?", and 3 a.m. is never like normal use.
     */
    private void checkRepresentativeness(List<String> blockers, List<String> warnings) {
        // An empty server describes nothing about a populated one. Verified
        // the hard way: a 34-minute window with players_online flat at 0
        // produced thresholds that would have alerted on ordinary play, and
        // the old median comparison could not catch it - on a server that
        // never had players the median is 0, and 0 < 0 is false.
        int peakPlayers = sampler.playersOnline().max();
        if (peakPlayers <= 0) {
            blockers.add(lang.get("calibrate.block.idle"));
        }

        int empty = sampler.lastWorldsWithoutChunks();
        int worlds = sampler.lastWorldCount();
        if (worlds > 0 && empty > worlds * MAX_EMPTY_WORLD_SHARE) {
            blockers.add(lang.get("calibrate.block.empty_worlds",
                    "%empty%", String.valueOf(empty),
                    "%total%", String.valueOf(worlds)));
        } else if (empty > 0) {
            warnings.add(lang.get("calibrate.warn.empty_worlds",
                    "%empty%", String.valueOf(empty),
                    "%total%", String.valueOf(worlds)));
        }

        // Real but thin traffic: worth flagging, not worth refusing.
        int online = Bukkit.getOnlinePlayers().size();
        double median = database != null
                ? database.getPercentileByType("players_online", 0.5, HISTORY_DAYS)
                : Double.NaN;

        if (Double.isNaN(median)) {
            warnings.add(lang.get("calibrate.warn.no_history"));
        } else if (peakPlayers > 0 && online < median) {
            warnings.add(lang.get("calibrate.warn.quiet",
                    "%players%", String.valueOf(online),
                    "%days%", String.valueOf(HISTORY_DAYS),
                    "%median%", String.format("%.0f", median)));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Proposals                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * A single-tick spike is worth analysing when it dwarfs this server's own
     * normal tick. Servers with WorldEdit get more room: a large //paste is a
     * legitimate multi-hundred-millisecond tick, not an incident.
     */
    private void proposeSpikeThreshold(List<ProposedValue> values) {
        if (!monitor.baseline().isEstablished()) {
            return;
        }
        double baseline = monitor.baseline().avgMspt();
        boolean worldEdit = hasPlugin("WorldEdit") || hasPlugin("FastAsyncWorldEdit");

        double factor = worldEdit ? SPIKE_FACTOR_WITH_WORLDEDIT : SPIKE_FACTOR;
        double max = worldEdit ? SPIKE_MAX_MS_WITH_WORLDEDIT : SPIKE_MAX_MS;
        double proposed = Math.max(DEFAULT_SPIKE_TICK_MS,
                round(clamp(baseline * factor, SPIKE_MIN_MS, max), 0));

        String reasonKey = worldEdit ? "calibrate.reason.spike_we" : "calibrate.reason.spike";
        values.add(new ProposedValue("thresholds.spike_tick_ms",
                config.spikeTickMs(), proposed,
                lang.get(reasonKey,
                        "%baseline%", String.format("%.1f", baseline),
                        "%factor%", String.format("%.0f", factor))));
    }

    /**
     * Derived from observed peaks, not from the stored packet totals: those
     * are cumulative counters whose per-interval deltas average every spike
     * away (1400/tick peaks read as ~200/tick over a minute).
     */
    private void proposePacketThreshold(List<ProposedValue> values, List<String> notes) {
        if (!hasPlugin("ProtocolLib")) {
            notes.add(lang.get("calibrate.note.no_protocollib"));
            return;
        }
        Histogram packets = sampler.packetsPerTick();
        if (packets.count() == 0) {
            return;
        }
        int p99 = packets.percentile(0.99);
        double proposed = Math.max(DEFAULT_PACKET_FLOOD,
                round(Math.max(PACKET_MIN, p99 * PACKET_HEADROOM), 0));

        values.add(new ProposedValue("thresholds.packet_flood_per_tick",
                config.packetFloodThreshold(), proposed,
                lang.get("calibrate.reason.packets", "%p99%", String.valueOf(p99))));
    }

    private void proposeChunkThresholds(List<ProposedValue> values) {
        Histogram entities = sampler.entitiesPerChunk();
        if (entities.count() < MIN_CHUNK_OBSERVATIONS) {
            return;
        }

        int entityP99 = entities.percentile(0.99);
        int warning = Math.max(DEFAULT_CHUNK_ENTITY_WARNING,
                Math.max(CHUNK_ENTITY_MIN, (int) Math.ceil(entityP99 * CHUNK_ENTITY_HEADROOM)));
        int critical = Math.max(DEFAULT_CHUNK_ENTITY_CRITICAL,
                (int) Math.ceil(warning * CHUNK_CRITICAL_FACTOR));

        values.add(new ProposedValue("lag_analysis.chunk_entity_warning",
                config.chunkEntityWarning(), warning,
                lang.get("calibrate.reason.chunk_entity", "%p99%", String.valueOf(entityP99))));
        values.add(new ProposedValue("lag_analysis.chunk_entity_critical",
                config.chunkEntityCritical(), critical,
                lang.get("calibrate.reason.chunk_crit")));

        Histogram tiles = sampler.tilesPerChunk();
        if (tiles.count() >= MIN_CHUNK_OBSERVATIONS) {
            int tileP99 = tiles.percentile(0.99);
            int tileThreshold = Math.max(DEFAULT_CHUNK_TILE_ENTITIES,
                    Math.max(TILE_MIN, (int) Math.ceil(tileP99 * TILE_HEADROOM)));
            values.add(new ProposedValue("lag_analysis.chunk_tile_entities_threshold",
                    config.chunkTileEntitiesThreshold(), tileThreshold,
                    lang.get("calibrate.reason.tiles", "%p99%", String.valueOf(tileP99))));
        }
    }

    private void proposeWorldThresholds(List<ProposedValue> values) {
        Histogram worlds = sampler.entitiesPerWorld();
        if (worlds.count() == 0) {
            return;
        }
        int p95 = worlds.percentile(0.95);
        int warning = Math.max(DEFAULT_WORLD_ENTITY_WARNING,
                Math.max(WORLD_ENTITY_MIN, (int) Math.ceil(p95 * WORLD_HEADROOM)));
        int critical = Math.max(DEFAULT_WORLD_ENTITY_CRITICAL,
                (int) Math.ceil(warning * WORLD_CRITICAL_FACTOR));

        values.add(new ProposedValue("lag_analysis.world_entity_warning",
                config.worldEntityWarning(), warning,
                lang.get("calibrate.reason.world_entity", "%p95%", String.valueOf(p95))));
        values.add(new ProposedValue("lag_analysis.world_entity_critical",
                config.worldEntityCritical(), critical,
                lang.get("calibrate.reason.world_crit")));
    }

    /**
     * The one non-threshold value that is written, because it is measured
     * rather than guessed: how long this server needed to become healthy.
     */
    private void proposeStartupGrace(List<ProposedValue> values) {
        long millis = monitor.millisToFirstOk();
        if (millis < 0) {
            return;
        }
        int measuredSeconds = (int) Math.ceil(millis / 1000.0);
        int proposed = Math.max(GRACE_MIN_SECONDS, (int) Math.ceil(measuredSeconds * GRACE_HEADROOM));

        values.add(new ProposedValue("performance.startup_grace_seconds",
                config.startupGraceSeconds(), proposed,
                lang.get("calibrate.reason.grace", "%seconds%", String.valueOf(measuredSeconds))));
    }

    /* ------------------------------------------------------------------ */
    /* Notes - reported, never changed                                     */
    /* ------------------------------------------------------------------ */

    private void collectNotes(List<String> notes) {
        if (Scheduling.FOLIA) {
            notes.add(lang.get("calibrate.note.folia"));
        }
        if (!hasPlugin("spark")) {
            notes.add(lang.get("calibrate.note.no_spark"));
        }
        if (hasPlugin("Citizens")) {
            notes.add(lang.get("calibrate.note.citizens"));
        }

        String bind = config.apiBind();
        int port = config.apiPort();
        if (!config.apiEnabled() && isPortInUse(bind, port)) {
            notes.add(lang.get("calibrate.note.port_in_use",
                    "%port%", String.valueOf(port), "%bind%", bind));
        }
    }

    /**
     * Probes whether the configured API port is already taken - the trap that
     * bites every install whose map plugin sits on 8080.
     */
    private boolean isPortInUse(String bind, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(bind, port), 1);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean hasPlugin(String name) {
        return plugin.getServer().getPluginManager().getPlugin(name) != null;
    }

    /* ------------------------------------------------------------------ */

    static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
