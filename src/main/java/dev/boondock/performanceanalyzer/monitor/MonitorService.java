package dev.boondock.performanceanalyzer.monitor;

import dev.boondock.performanceanalyzer.analysis.Baseline;
import dev.boondock.performanceanalyzer.analysis.IncidentAnalyzer;
import dev.boondock.performanceanalyzer.analysis.Severity;
import dev.boondock.performanceanalyzer.analysis.SeverityModel;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.GcSampler;
import dev.boondock.performanceanalyzer.metrics.TickStats;
import dev.boondock.performanceanalyzer.metrics.TickTimeSampler;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Central 1-second evaluation loop.
 *
 * <p>Fixes the v2.x architecture flaw where drop detection ran once per
 * minute as a side effect of database logging (and stopped entirely when the
 * DB was down). Here the loop always runs: it snapshots the tick and GC
 * samplers, evaluates severity, feeds the baseline (only while healthy, so
 * incidents cannot poison it) and drives the incident engine. Database
 * logging is a subordinate step that can fail without affecting detection.</p>
 */
public final class MonitorService {

    private final Plugin plugin;
    private final TickTimeSampler tickSampler;
    private final GcSampler gcSampler;
    private final IncidentAnalyzer incidentAnalyzer;
    private final Baseline baseline = new Baseline();

    private DatabaseManager database;
    private LanguageManager lang;
    private volatile int logIntervalSeconds;
    private int secondsSinceLog;

    private volatile TickStats tickStats = TickStats.EMPTY;
    private volatile SeverityModel.Assessment assessment = SeverityModel.Assessment.OK;

    private volatile long graceMs;
    private long armedAtMs;
    private ScheduledTask loopTask;

    // Sampled on the main thread (see start()); -1 means "not sampled yet".
    // chunks_loaded stays -1 on Folia, where walking every world's chunk
    // array from one thread is illegal - the series is simply absent there.
    private volatile int playersSnapshot = -1;
    private volatile int loadedChunksSnapshot = -1;
    private ScheduledTask loadSamplerTask;

    /** Milliseconds from start() to the first OK assessment; -1 until then. */
    private volatile long millisToFirstOk = -1;

    public MonitorService(Plugin plugin, TickTimeSampler tickSampler, GcSampler gcSampler,
                          IncidentAnalyzer incidentAnalyzer) {
        this.plugin = plugin;
        this.tickSampler = tickSampler;
        this.gcSampler = gcSampler;
        this.incidentAnalyzer = incidentAnalyzer;
    }

    /** Localizes assessment reason strings; optional (English fallback). */
    public void setLanguage(LanguageManager lang) {
        this.lang = lang;
    }

    /** Database logging is optional and reconfigurable at reload. */
    public void setDatabase(DatabaseManager database, int logIntervalSeconds) {
        this.database = database;
        this.logIntervalSeconds = Math.max(1, logIntervalSeconds);
    }

    /**
     * The startup grace period suppresses incidents and alerts while the
     * server is still booting — the first ticks during world loading are
     * always slow and would otherwise open an EMERGENCY incident on every
     * single start (observed on the test server).
     */
    public void setStartupGraceSeconds(int seconds) {
        this.graceMs = Math.max(0, seconds) * 1_000L;
    }

    public void start() {
        stop();
        armedAtMs = System.currentTimeMillis();
        loopTask = Scheduling.runAsyncRepeating(plugin, this::evaluate, 1_000L, 1_000L);
        // Load context has to be read from the tick thread, so it is sampled
        // here every 10 s and only consumed by the async logging step.
        loadSamplerTask = Scheduling.runGlobalRepeating(plugin, this::sampleLoadContext, 20L, 20L * 10);
    }

    public void stop() {
        Scheduling.cancel(loopTask);
        loopTask = null;
        Scheduling.cancel(loadSamplerTask);
        loadSamplerTask = null;
    }

    private void sampleLoadContext() {
        try {
            playersSnapshot = Bukkit.getOnlinePlayers().size();
            if (!Scheduling.FOLIA) {
                int loaded = 0;
                for (World world : Bukkit.getWorlds()) {
                    loaded += world.getLoadedChunks().length;
                }
                loadedChunksSnapshot = loaded;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Monitor] load context sampling failed: " + e.getMessage());
        }
    }

    /**
     * Out-of-band evaluation, wired to the tick sampler's spike callback so a
     * single pathological tick opens an incident immediately instead of
     * waiting for the next 1-second cycle.
     */
    public void evaluateNow() {
        evaluate();
    }

    private void evaluate() {
        try {
            // During server shutdown the tick loop stops while worlds save;
            // the counted TPS collapses (2 ms avg tick but "15 TPS") and every
            // scheduled restart raised a bogus CRITICAL/EMERGENCY alert.
            if (org.bukkit.Bukkit.isStopping()) {
                return;
            }
            TickStats ticks = tickSampler.snapshot();
            GcSampler.GcStats gc = gcSampler.stats();
            SeverityModel.Assessment result = SeverityModel.evaluate(ticks, gc, baseline, lang);

            this.tickStats = ticks;
            this.assessment = result;

            // Learn the baseline ONLY while fully healthy. Updating at NOTICE
            // lets sustained load ratchet the baseline upward until real lag
            // reads as "normal" and active incidents self-resolve (observed
            // in the villager load test: baseline crept 4.4 -> 9.0 ms).
            if (ticks.hasData() && result.severity() == Severity.OK) {
                baseline.update(ticks.msptAvg10s(), ticks.msptP95());
                if (millisToFirstOk < 0) {
                    // How long this server needs to settle after a start.
                    // Guessing that number is what makes startup_grace_seconds
                    // wrong on most installs; measuring it is trivial.
                    millisToFirstOk = System.currentTimeMillis() - armedAtMs;
                }
            }

            // During the startup grace period metrics are shown but no
            // incidents are opened — boot ticks are always slow.
            if (System.currentTimeMillis() - armedAtMs >= graceMs) {
                incidentAnalyzer.onEvaluation(ticks, gc, result);
            }

            logToDatabase(ticks, gc, result);
        } catch (Exception e) {
            plugin.getLogger().warning("[Monitor] evaluation failed: " + e.getMessage());
        }
    }

    private void logToDatabase(TickStats ticks, GcSampler.GcStats gc, SeverityModel.Assessment result) {
        DatabaseManager db = this.database;
        if (db == null || !ticks.hasData()) {
            return;
        }
        if (++secondsSinceLog < logIntervalSeconds) {
            return;
        }
        secondsSinceLog = 0;
        db.logAsync("mspt", ticks.msptAvg10s(), null);
        db.logAsync("mspt_p95", ticks.msptP95(), null);
        db.logAsync("tps", ticks.tps10s(), null);
        db.logAsync("severity_score", result.score(), result.severity().name());
        if (gc != null) {
            db.logAsync("gc_time_60s", gc.gcTimeMs60s(), null);
            if (gc.hasAfterGcData()) {
                db.logAsync("oldgen_after_gc", gc.oldGenAfterGcPercent(), null);
            }
        }

        // Load context. Without these two series nothing can tell a quiet
        // server from a healthy one after the fact - which is exactly what
        // /perfcalibrate needs to judge whether a sample is representative.
        // Both values come from the main-thread sampler; this method runs
        // async and must not touch world state itself.
        int players = playersSnapshot;
        if (players >= 0) {
            db.logAsync("players_online", players, null);
        }
        int chunks = loadedChunksSnapshot;
        if (chunks >= 0) {
            db.logAsync("chunks_loaded", chunks, null);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Read API for commands, GUIs and the REST API                        */
    /* ------------------------------------------------------------------ */

    public TickStats tickStats() {
        return tickStats;
    }

    public GcSampler.GcStats gcStats() {
        return gcSampler.stats();
    }

    public SeverityModel.Assessment assessment() {
        return assessment;
    }

    public Baseline baseline() {
        return baseline;
    }

    /**
     * How long this server took to reach a healthy state after the last
     * start, in milliseconds; -1 while it never has. Basis for a measured
     * {@code startup_grace_seconds} instead of a guessed one.
     */
    public long millisToFirstOk() {
        return millisToFirstOk;
    }

    /** Loaded chunks across all worlds, -1 when unsampled (Folia). */
    public int loadedChunks() {
        return loadedChunksSnapshot;
    }
}
