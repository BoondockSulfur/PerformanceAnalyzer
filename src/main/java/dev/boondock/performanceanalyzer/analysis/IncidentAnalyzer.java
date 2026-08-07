package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.GcSampler;
import dev.boondock.performanceanalyzer.metrics.TickStats;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import dev.boondock.performanceanalyzer.timing.ListenerTimings;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Root-cause engine: opens, updates and resolves {@link Incident}s and
 * attributes them to causes using only <em>already collected</em> data.
 *
 * <p>Cardinal rule learned from v2.x: the analyzer must never make the lag
 * worse. It therefore performs no chunk snapshots, no world iteration from
 * the monitor thread and no blocking waits. Attribution draws on:</p>
 * <ol>
 *   <li>GC pause deltas (measured) — checked first, because a GC-caused
 *       spike must not be blamed on redstone,</li>
 *   <li>per-plugin listener timings (measured),</li>
 *   <li>per-chunk activity rates: hoppers, redstone, pistons, spawns,
 *       chunk load/gen (measured),</li>
 *   <li>chunk-churn history and old-gen occupancy (measured),</li>
 *   <li>an optional one-shot entity-hotspot sweep on the global thread —
 *       Paper only, budgeted, once per incident refresh cycle.</li>
 * </ol>
 */
public final class IncidentAnalyzer {

    /** Callback for incident lifecycle events (wired to the alert system). */
    public interface Listener {
        void incidentOpened(Incident incident);

        void incidentEscalated(Incident incident);

        void incidentResolved(Incident incident);
    }

    private static final int MAX_STORED_INCIDENTS = 30;
    private static final long RESOLVE_GRACE_MS = 30_000L;
    private static final long FINDINGS_REFRESH_MS = 5_000L;

    private final Plugin plugin;
    private final LanguageManager lang;
    private final ActivityCounters activity;
    private final ChunkTracker chunkTracker;
    private final EntityAnalyzer entityAnalyzer;
    private final DatabaseManager database;

    private ListenerTimings listenerTimings;
    private Listener listener;

    private final ArrayDeque<Incident> incidents = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private volatile Incident active;
    private long healthySinceMs = -1;
    private long lastFindingsRefreshMs;
    private volatile List<EntityAnalyzer.EntityHotspot> lastEntityHotspots = List.of();
    private volatile boolean entitySweepPending;

    public IncidentAnalyzer(Plugin plugin, LanguageManager lang, ActivityCounters activity,
                            ChunkTracker chunkTracker, EntityAnalyzer entityAnalyzer,
                            DatabaseManager database) {
        this.plugin = plugin;
        this.lang = lang;
        this.activity = activity;
        this.chunkTracker = chunkTracker;
        this.entityAnalyzer = entityAnalyzer;
        this.database = database;
    }

    public void setListenerTimings(ListenerTimings listenerTimings) {
        this.listenerTimings = listenerTimings;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle driving — called once per second by the monitor service   */
    /* ------------------------------------------------------------------ */

    public synchronized void onEvaluation(TickStats ticks, GcSampler.GcStats gc,
                                          SeverityModel.Assessment assessment) {
        long now = System.currentTimeMillis();
        Incident current = active;

        if (current == null) {
            if (assessment.severity().atLeast(Severity.WARNING)) {
                open(now, ticks, gc, assessment);
            }
            return;
        }

        boolean escalated = assessment.severity().level() > current.peakSeverity().level();
        current.update(assessment.severity(), assessment.score(),
                ticks.msptAvg10s(), ticks.msptMax10s(), ticks.tps10s());
        if (escalated) {
            refreshFindings(current, ticks, gc, now);
            Listener l = listener;
            if (l != null) {
                l.incidentEscalated(current);
            }
        }

        if (assessment.severity().atLeast(Severity.WARNING)) {
            healthySinceMs = -1;
            if (now - lastFindingsRefreshMs >= FINDINGS_REFRESH_MS) {
                refreshFindings(current, ticks, gc, now);
            }
        } else {
            if (healthySinceMs < 0) {
                healthySinceMs = now;
            } else if (now - healthySinceMs >= RESOLVE_GRACE_MS) {
                resolve(current, now);
            }
        }
    }

    private void open(long now, TickStats ticks, GcSampler.GcStats gc,
                      SeverityModel.Assessment assessment) {
        Incident incident = new Incident(nextId.getAndIncrement(), now,
                assessment.severity(), assessment.score(), assessment.reasons());
        incident.update(assessment.severity(), assessment.score(),
                ticks.msptAvg10s(), ticks.msptMax10s(), ticks.tps10s());
        active = incident;
        healthySinceMs = -1;
        refreshFindings(incident, ticks, gc, now);
        synchronized (incidents) {
            incidents.addFirst(incident);
            while (incidents.size() > MAX_STORED_INCIDENTS) {
                incidents.removeLast();
            }
        }
        Listener l = listener;
        if (l != null) {
            l.incidentOpened(incident);
        }
    }

    private void resolve(Incident incident, long now) {
        incident.resolve(now);
        active = null;
        healthySinceMs = -1;
        if (database != null) {
            database.logAsync("incident", incident.peakScore(),
                    String.format("severity=%s duration=%ds worstTick=%.0fms lowestTps=%.1f",
                            incident.peakSeverity(), incident.durationMs() / 1000,
                            incident.worstTickMs(), incident.lowestTps()));
        }
        Listener l = listener;
        if (l != null) {
            l.incidentResolved(incident);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Findings                                                            */
    /* ------------------------------------------------------------------ */

    private void refreshFindings(Incident incident, TickStats ticks, GcSampler.GcStats gc, long now) {
        lastFindingsRefreshMs = now;
        requestEntitySweep();
        List<Finding> findings = collectFindings(ticks, gc);
        incident.setFindings(findings);
    }

    /**
     * Builds the current list of findings, best-attributed first. Cheap: all
     * inputs are pre-aggregated snapshots.
     */
    public List<Finding> collectFindings(TickStats ticks, GcSampler.GcStats gc) {
        List<Finding> findings = new ArrayList<>(8);

        // 1. GC — checked first so memory problems aren't blamed on content.
        if (gc != null) {
            if (gc.gcTimeMs60s() >= 2_000 || gc.longestPauseMs60s() >= 150) {
                Severity severity = (gc.gcTimeMs60s() >= 6_000 || gc.longestPauseMs60s() >= 400)
                        ? Severity.CRITICAL : Severity.WARNING;
                findings.add(Finding.global(Finding.Type.GC_PRESSURE, severity, Finding.Confidence.MEASURED,
                        lang.format("finding.gc.title"),
                        lang.format("finding.gc.detail", gc.gcTimeMs60s(), gc.longestPauseMs60s(), gc.gcCount60s()),
                        lang.format("finding.gc.recommendation"),
                        gc.gcTimeMs60s()));
            }
            if (gc.hasAfterGcData() && gc.oldGenAfterGcPercent() >= 85) {
                Severity severity = gc.oldGenAfterGcPercent() >= 92 ? Severity.CRITICAL : Severity.WARNING;
                findings.add(Finding.global(Finding.Type.MEMORY_PRESSURE, severity, Finding.Confidence.MEASURED,
                        lang.format("finding.memory.title"),
                        lang.format("finding.memory.detail", gc.oldGenAfterGcPercent(), gc.heapMaxMb()),
                        lang.format("finding.memory.recommendation"),
                        gc.oldGenAfterGcPercent()));
            }
        }

        // 2. Plugins by measured listener time.
        ListenerTimings timings = this.listenerTimings;
        if (timings != null && timings.isActive()) {
            int added = 0;
            for (ListenerTimings.PluginLoad load : timings.loads()) {
                if (load.msPerSec() < 50 || added >= 3) {
                    break;
                }
                Severity severity = load.msPerSec() >= 200 ? Severity.CRITICAL
                        : load.msPerSec() >= 100 ? Severity.WARNING : Severity.NOTICE;
                findings.add(Finding.global(Finding.Type.PLUGIN_LOAD, severity, Finding.Confidence.MEASURED,
                        lang.format("finding.plugin.title", load.pluginName()),
                        lang.format("finding.plugin.detail", load.msPerSec(), load.percentOfThreadBudget(),
                                load.topEvent(), load.topEventMsPerSec()),
                        lang.format("finding.plugin.recommendation", load.pluginName()),
                        load.msPerSec()));
                added++;
            }
        }

        // 3. Hot chunks by measured activity rates.
        int added = 0;
        for (ActivityCounters.HotChunk chunk : activity.hotChunks()) {
            if (chunk.score() < 200 || added >= 5) {
                break;
            }
            Severity severity = chunk.score() >= 3_000 ? Severity.CRITICAL
                    : chunk.score() >= 1_000 ? Severity.WARNING : Severity.NOTICE;
            String dominant = dominantActivity(chunk);
            findings.add(Finding.located(Finding.Type.HOT_CHUNK, severity, Finding.Confidence.MEASURED,
                    lang.format("finding.hotchunk.title", chunk.worldName(), chunk.blockX(), chunk.blockZ()),
                    lang.format("finding.hotchunk.detail", dominant,
                            chunk.hopperMovesPerSec(), chunk.redstonePerSec(), chunk.spawnsPerSec()),
                    lang.format("finding.hotchunk.recommendation", chunk.worldName(), chunk.blockX(), chunk.blockZ()),
                    chunk.worldName(), chunk.blockX(), chunk.blockZ(), chunk.score()));
            added++;
        }

        // 4. Chunk generation load (exploration / world border pushes).
        ActivityCounters.GlobalActivity global = activity.globalActivity();
        if (global.chunkGensPerSec() >= 3) {
            findings.add(Finding.global(Finding.Type.CHUNK_GENERATION,
                    global.chunkGensPerSec() >= 10 ? Severity.CRITICAL : Severity.WARNING,
                    Finding.Confidence.MEASURED,
                    lang.format("finding.chunkgen.title"),
                    lang.format("finding.chunkgen.detail", global.chunkGensPerSec()),
                    lang.format("finding.chunkgen.recommendation"),
                    global.chunkGensPerSec()));
        }

        // 5. Chunk churn (repeatedly re-loaded chunks — portals, chunk loaders).
        for (ChunkTracker.FrequentlyLoadedChunk frequent : chunkTracker.getFrequentlyLoadedChunks(2)) {
            if (frequent.loadCount() < 20) {
                continue;
            }
            findings.add(Finding.located(Finding.Type.CHUNK_CHURN, Severity.NOTICE, Finding.Confidence.HEURISTIC,
                    lang.format("finding.churn.title", frequent.worldName(), frequent.blockX(), frequent.blockZ()),
                    lang.format("finding.churn.detail", frequent.loadCount()),
                    lang.format("finding.churn.recommendation"),
                    frequent.worldName(), frequent.blockX(), frequent.blockZ(), frequent.loadCount()));
        }

        // 6. Entity hotspots from the last global-thread sweep (Paper only).
        // EntityHotspot.x()/z() are already block coordinates.
        for (EntityAnalyzer.EntityHotspot hotspot : lastEntityHotspots) {
            findings.add(Finding.located(Finding.Type.ENTITY_HOTSPOT,
                    hotspot.severity() == EntityAnalyzer.HotspotSeverity.CRITICAL ? Severity.WARNING : Severity.NOTICE,
                    Finding.Confidence.MEASURED,
                    lang.format("finding.entities.title", hotspot.worldName(), hotspot.x(), hotspot.z()),
                    lang.format("finding.entities.detail", hotspot.entityCount(),
                            String.valueOf(hotspot.dominantType()), hotspot.dominantCount()),
                    lang.format("finding.entities.recommendation"),
                    hotspot.worldName(), hotspot.x(), hotspot.z(), hotspot.entityCount()));
        }

        findings.sort(Comparator.comparingInt((Finding f) -> f.severity().level()).reversed());

        if (findings.isEmpty()) {
            findings.add(Finding.global(Finding.Type.UNKNOWN, Severity.NOTICE, Finding.Confidence.HEURISTIC,
                    lang.format("finding.unknown.title"),
                    lang.format("finding.unknown.detail"),
                    lang.format("finding.unknown.recommendation"),
                    0));
        }
        return findings;
    }

    private String dominantActivity(ActivityCounters.HotChunk chunk) {
        double hopper = chunk.hopperMovesPerSec() * 3;
        double redstone = chunk.redstonePerSec() + chunk.pistonsPerSec() * 2;
        double spawns = chunk.spawnsPerSec() * 4;
        if (hopper >= redstone && hopper >= spawns) {
            return lang.format("finding.hotchunk.kind.hopper");
        }
        if (redstone >= spawns) {
            return lang.format("finding.hotchunk.kind.redstone");
        }
        return lang.format("finding.hotchunk.kind.spawns");
    }

    /**
     * One-shot entity sweep on the global thread. Paper only: on Folia,
     * iterating foreign regions' entities from the global thread is illegal;
     * hot-chunk activity rates remain the location signal there.
     */
    private void requestEntitySweep() {
        if (Scheduling.FOLIA || entityAnalyzer == null || entitySweepPending) {
            return;
        }
        entitySweepPending = true;
        Scheduling.runGlobal(plugin, () -> {
            try {
                EntityAnalyzer.EntityAnalysis analysis = entityAnalyzer.analyze();
                List<EntityAnalyzer.EntityHotspot> hotspots = analysis.hotspots();
                lastEntityHotspots = hotspots.size() > 3 ? hotspots.subList(0, 3) : hotspots;
            } catch (Exception e) {
                plugin.getLogger().fine("Entity sweep failed: " + e.getMessage());
            } finally {
                entitySweepPending = false;
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Read API                                                            */
    /* ------------------------------------------------------------------ */

    public Incident activeIncident() {
        return active;
    }

    /** Most recent first. */
    public List<Incident> recentIncidents() {
        synchronized (incidents) {
            return List.copyOf(incidents);
        }
    }

    public void clearHistory() {
        synchronized (incidents) {
            Incident current = active;
            incidents.clear();
            if (current != null) {
                incidents.addFirst(current);
            }
        }
    }
}
