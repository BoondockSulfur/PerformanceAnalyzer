package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.metrics.GcSampler;
import dev.boondock.performanceanalyzer.metrics.TickStats;
import dev.boondock.performanceanalyzer.metrics.TickTimeSampler;
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
public final class IncidentAnalyzer implements org.bukkit.event.Listener {

    /** Callback for incident lifecycle events (wired to the alert system). */
    public interface Listener {
        void incidentOpened(Incident incident);

        void incidentEscalated(Incident incident);

        void incidentResolved(Incident incident);
    }

    private static final int MAX_STORED_INCIDENTS = 30;
    private static final long RESOLVE_GRACE_MS = 30_000L;
    private static final long FINDINGS_REFRESH_MS = 5_000L;
    /** How long after a WorldSaveEvent a stall is attributed to the save. */
    private static final long WORLD_SAVE_ATTRIBUTION_MS = 12_000L;
    private static final long STALL_ATTRIBUTION_MS = 12_000L;
    /** How long after an expensive command a stall is attributed to it. */
    private static final long COMMAND_ATTRIBUTION_MS = 15_000L;
    /** How long after a login or far teleport its chunk loading is blamed for a stall. */
    private static final long ARRIVAL_ATTRIBUTION_MS = 8_000L;
    /** How long after a logged server error it is listed with an incident. */
    private static final long ERROR_ATTRIBUTION_MS = 30_000L;
    /** Window for "this chunk keeps being re-loaded" (chunk churn). */
    private static final long CHURN_WINDOW_MS = 10 * 60_000L;
    /**
     * Distance beyond which a teleport must load chunks that were not already
     * in memory. Below it the destination is almost certainly loaded already,
     * and blaming a teleport that cost nothing would hide the real cause.
     */
    private static final double TELEPORT_MIN_DISTANCE = 256.0;
    /**
     * Minimum gap between two entity sweeps. The sweep walks every loaded
     * chunk on the main thread; repeating it with every 5-second findings
     * refresh added main-thread work to exactly the periods that were already
     * lagging.
     */
    private static final long ENTITY_SWEEP_MIN_INTERVAL_MS = 30_000L;
    /** Sweep results older than this describe a past state and are not reported. */
    private static final long ENTITY_HOTSPOT_MAX_AGE_MS = 60_000L;

    private final Plugin plugin;
    private final LanguageManager lang;
    private final ActivityCounters activity;
    private final ChunkTracker chunkTracker;
    private final EntityAnalyzer entityAnalyzer;
    private final DatabaseManager database;
    private final dev.boondock.performanceanalyzer.config.PluginConfig config;

    private ListenerTimings listenerTimings;
    private volatile dev.boondock.performanceanalyzer.monitor.ServerErrorMonitor errorMonitor;
    private volatile dev.boondock.performanceanalyzer.metrics.MainThreadSampler mainThreadSampler;
    private volatile GcSampler gcSampler;
    private Listener listener;
    private TickTimeSampler tickSampler;

    private final ArrayDeque<Incident> incidents = new ArrayDeque<>();
    /** Persisted, so incident numbers keep counting across restarts instead of repeating #1. */
    private static final String SEQUENCE_FILE = "incident.seq";
    private final AtomicLong nextId;
    private volatile ActivityContext activityContext;

    /** Slow ticks reach the database at most this often; a sustained lag is an incident, not a list. */
    private static final long SLOW_TICK_LOG_MIN_INTERVAL_MS = 2_000L;
    /** From this duration on a slow tick outside an incident also gets an entry in incidents.log. */
    private static final double SLOW_TICK_REPORT_MS = 500.0;
    private final AtomicLong lastSlowTickLogMs = new AtomicLong();
    private static final long SLOW_TICK_REPORT_DELAY_MS = 5_000L;

    private volatile Incident active;
    private long healthySinceMs = -1;
    /** When the current run of WARNING-level evaluations began; -1 when none. */
    private long warningSinceMs = -1;
    private double pendingWorstTickMs;
    private double pendingWorstAvgMs;
    private double pendingLowestTps = 20.0;
    private long lastFindingsRefreshMs;
    private volatile long lastWorldSaveAtMs;
    private volatile String lastSavedWorldName = "";
    private volatile long lastExpensiveCommandAtMs;
    private volatile String lastExpensiveCommand = "";
    private volatile String lastExpensiveCommandSender = "";
    private volatile long lastArrivalAtMs;
    private volatile String lastArrivalWorldName = "";
    private volatile String lastArrivalDetailKey = "finding.arrival.detail_teleport";
    private volatile List<EntityAnalyzer.EntityHotspot> lastEntityHotspots = List.of();
    private volatile long lastEntitySweepAtMs;
    private volatile long lastEntitySweepRequestAtMs;
    private volatile boolean entitySweepPending;

    public IncidentAnalyzer(Plugin plugin, LanguageManager lang, ActivityCounters activity,
                            ChunkTracker chunkTracker, EntityAnalyzer entityAnalyzer,
                            DatabaseManager database,
                            dev.boondock.performanceanalyzer.config.PluginConfig config) {
        this.plugin = plugin;
        this.lang = lang;
        this.activity = activity;
        this.chunkTracker = chunkTracker;
        this.entityAnalyzer = entityAnalyzer;
        this.database = database;
        this.config = config;
        this.nextId = new AtomicLong(loadNextId());
    }

    private long loadNextId() {
        if (plugin == null) {
            return 1;
        }
        java.io.File file = new java.io.File(plugin.getDataFolder(), SEQUENCE_FILE);
        try {
            if (file.isFile()) {
                return Math.max(1, Long.parseLong(java.nio.file.Files.readString(file.toPath()).strip()));
            }
        } catch (java.io.IOException | NumberFormatException e) {
            plugin.getLogger().warning("[Incident] Could not read " + SEQUENCE_FILE + ": " + e.getMessage());
        }
        return IncidentReport.highestLoggedId(plugin.getDataFolder()) + 1;
    }

    private void saveNextId(long next) {
        if (plugin == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(new java.io.File(plugin.getDataFolder(), SEQUENCE_FILE).toPath(),
                    Long.toString(next));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("[Incident] Could not write " + SEQUENCE_FILE + ": " + e.getMessage());
        }
    }

    /** Optional; without it reports carry no player context. */
    public void setActivityContext(ActivityContext activityContext) {
        this.activityContext = activityContext;
    }

    /** Optional; without it no unmeasured-stall attribution is produced. */
    public void setTickSampler(TickTimeSampler tickSampler) {
        this.tickSampler = tickSampler;
    }

    public void setListenerTimings(ListenerTimings listenerTimings) {
        this.listenerTimings = listenerTimings;
    }

    public void setErrorMonitor(dev.boondock.performanceanalyzer.monitor.ServerErrorMonitor errorMonitor) {
        this.errorMonitor = errorMonitor;
    }

    public void setMainThreadSampler(dev.boondock.performanceanalyzer.metrics.MainThreadSampler sampler) {
        this.mainThreadSampler = sampler;
    }

    public void setGcSampler(GcSampler gcSampler) {
        this.gcSampler = gcSampler;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * World saves (autosave, backup plugins running save-all) pause the tick
     * loop for the duration of the save. Without this marker such stalls
     * ended up as "no clear cause" — or worse, blamed on unrelated content
     * (observed nightly at 00:00 when the backup container triggered a save).
     */
    @org.bukkit.event.EventHandler
    public void onWorldSave(org.bukkit.event.world.WorldSaveEvent event) {
        this.lastWorldSaveAtMs = System.currentTimeMillis();
        this.lastSavedWorldName = event.getWorld().getName();
    }

    /**
     * Operator commands that do a world's worth of work in one tick.
     *
     * <p>These were the one class of stall that reliably ended in "no clear
     * cause" while the reason sat in the server log two lines above: a
     * WorldEdit {@code //paste} dropped TPS to 15.5, and {@code /mv delete} +
     * {@code /mv create} produced "Prepared spawn area in 4083 ms". None of
     * the instrumented sources can see them - the work happens inside the
     * command, not in a listener, a chunk counter or a GC pause.
     *
     * <p>Only commands on {@link PluginConfig#expensiveCommands()} set the
     * marker. Matching every command would attribute stalls to whatever ran
     * last, and something harmless runs constantly - a monitoring
     * {@code /list} every five minutes would collect the blame for everything.
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        markCommand(event.getMessage(), event.getPlayer().getName());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(org.bukkit.event.server.ServerCommandEvent event) {
        markCommand(event.getCommand(), event.getSender().getName());
    }

    private void markCommand(String rawCommand, String senderName) {
        if (rawCommand == null || rawCommand.isEmpty() || config == null) {
            return;
        }
        String command = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
        String lower = command.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : config.expensiveCommands()) {
            if (matchesExpensivePrefix(lower, prefix)) {
                this.lastExpensiveCommandAtMs = System.currentTimeMillis();
                // Trimmed: a //paste can carry a very long argument list, and
                // this string ends up in chat and Discord.
                this.lastExpensiveCommand = command.length() > 60
                        ? command.substring(0, 60) + "…" : command;
                this.lastExpensiveCommandSender = senderName == null ? "?" : senderName;
                return;
            }
        }
    }

    /**
     * True when {@code command} (lower-case, first slash already removed)
     * starts with the configured {@code prefix}. A lone "/" is the WorldEdit
     * entry and stays as-is: stripped to "" it would match every command.
     */
    static boolean matchesExpensivePrefix(String command, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return false;
        }
        String needle = prefix.startsWith("/") && prefix.length() > 1 ? prefix.substring(1) : prefix;
        return command.startsWith(needle.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * A player arriving somewhere that is not in memory forces chunks to be
     * read from disk or generated, which blocks the tick loop for as long as
     * that takes. Two ways in, same physics:
     *
     * <ul>
     *   <li>a teleport across worlds or over a long distance - seen on
     *       rattenkolonie as {@code /home} jumps producing 778 ms and 451 ms
     *       ticks,</li>
     *   <li>a login, which loads the area around wherever the player logged
     *       out - the case that was still reported as "no clear cause" after
     *       teleports had already been covered.</li>
     * </ul>
     *
     * <p>Short hops within one world are ignored on purpose: their destination
     * is already loaded, so blaming them would bury the real cause.
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        org.bukkit.Location from = event.getFrom();
        org.bukkit.Location to = event.getTo();
        if (to == null || from == null) {
            return;
        }
        boolean crossesWorld = from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld());
        if (!crossesWorld && from.distanceSquared(to) < TELEPORT_MIN_DISTANCE * TELEPORT_MIN_DISTANCE) {
            return;
        }
        markArrival(to.getWorld() == null ? "?" : to.getWorld().getName(),
                "finding.arrival.detail_teleport");
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        org.bukkit.World world = event.getPlayer().getWorld();
        markArrival(world == null ? "?" : world.getName(), "finding.arrival.detail_join");
    }

    private void markArrival(String worldName, String detailKey) {
        this.lastArrivalAtMs = System.currentTimeMillis();
        this.lastArrivalWorldName = worldName;
        this.lastArrivalDetailKey = detailKey;
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle driving — called once per second by the monitor service   */
    /* ------------------------------------------------------------------ */

    public synchronized void onEvaluation(TickStats ticks, GcSampler.GcStats gc,
                                          SeverityModel.Assessment assessment) {
        long now = System.currentTimeMillis();
        Incident current = active;

        if (current == null) {
            openIfWarranted(now, ticks, gc, assessment);
            return;
        }

        boolean escalated = assessment.severity().level() > current.peakSeverity().level();
        current.update(assessment.severity(), assessment.score(),
                ticks.msptAvg10s(), ticks.msptMax10s(), ticks.tps10s());
        if (escalated) {
            refreshFindings(current, ticks, gc, now);
            Listener l = listener;
            if (l != null && !current.alertsSuppressed()) {
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
                // Resolve *at the moment the server recovered*, not now. The
                // grace period is confirmation that the recovery held, not
                // part of the incident: reporting `now` added a flat 30 s to
                // every duration, so a 12-second blip was announced as
                // "Dauer: 42s" and looked four times worse than it was.
                resolve(current, healthySinceMs);
            }
        }
    }

    /**
     * Decides whether a WARNING is worth an incident yet.
     *
     * <p>A short rough patch is not a problem, it is a server doing work:
     * chunks loading behind a player who just logged in, a redstone burst, a
     * pasted schematic. Both of the false alarms that survived the severity
     * rework were of that shape - five and fourteen seconds, tick rate 19.7
     * and 19.9. So a WARNING has to <em>hold</em> before anyone hears about
     * it.
     *
     * <p>CRITICAL and above skip the wait entirely. That is the "unless it
     * causes extreme load" half: at TPS below 17 or a 50 ms average the server
     * is already failing, and waiting half a minute to mention it would be
     * absurd.
     *
     * <p>The incident is dated from when the trouble started, not from when
     * the wait expired, and the peaks measured during the wait are carried
     * into it - otherwise this filter would quietly shorten every incident it
     * lets through.
     */
    private void openIfWarranted(long now, TickStats ticks, GcSampler.GcStats gc,
                                 SeverityModel.Assessment assessment) {
        if (assessment.severity().atLeast(Severity.CRITICAL)) {
            open(warningSinceMs >= 0 ? warningSinceMs : now, ticks, gc, assessment);
            return;
        }
        if (!assessment.severity().atLeast(Severity.WARNING)) {
            warningSinceMs = -1;
            return;
        }

        if (warningSinceMs < 0) {
            warningSinceMs = now;
            pendingWorstTickMs = 0;
            pendingWorstAvgMs = 0;
            pendingLowestTps = 20.0;
        }
        pendingWorstTickMs = Math.max(pendingWorstTickMs, ticks.msptMax10s());
        pendingWorstAvgMs = Math.max(pendingWorstAvgMs, ticks.msptAvg10s());
        if (ticks.tps10s() >= 0) {
            pendingLowestTps = Math.min(pendingLowestTps, ticks.tps10s());
        }

        long sustainMs = config == null ? 30_000L : config.warningSustainSeconds() * 1_000L;
        if (now - warningSinceMs >= sustainMs) {
            open(warningSinceMs, ticks, gc, assessment);
        }
    }

    private void open(long startedAtMs, TickStats ticks, GcSampler.GcStats gc,
                      SeverityModel.Assessment assessment) {
        long now = System.currentTimeMillis();
        long id = nextId.getAndIncrement();
        saveNextId(id + 1);
        Incident incident = new Incident(id, startedAtMs,
                assessment.severity(), assessment.score(), assessment.reasons());
        if (warningSinceMs >= 0) {
            incident.update(assessment.severity(), assessment.score(),
                    pendingWorstAvgMs, pendingWorstTickMs, pendingLowestTps);
        }
        warningSinceMs = -1;
        incident.update(assessment.severity(), assessment.score(),
                ticks.msptAvg10s(), ticks.msptMax10s(), ticks.tps10s());
        active = incident;
        healthySinceMs = -1;
        // Close the activity window first: otherwise the opening report is
        // built from the last completed one, which can be ten seconds old and
        // may predate the trouble entirely.
        activity.rotateNow();
        refreshFindings(incident, ticks, gc, now);
        incident.setOpeningFindings(incident.findings());
        synchronized (incidents) {
            incidents.addFirst(incident);
            while (incidents.size() > MAX_STORED_INCIDENTS) {
                incidents.removeLast();
            }
        }
        // Decided once, here: findings change over the incident's life, and
        // re-deciding per alert would send an escalation or "resolved" for an
        // incident whose opening was never announced, or the reverse.
        if (alertDampened(incident)) {
            incident.suppressAlerts();
            plugin.getLogger().info("[Incident] #" + incident.id()
                    + " attributed to a world save - recorded, alert suppressed"
                    + " (alerts.dampen_world_save).");
        }
        Listener l = listener;
        if (l != null && !incident.alertsSuppressed()) {
            l.incidentOpened(incident);
        }
    }

    private void resolve(Incident incident, long recoveredAtMs) {
        incident.resolve(recoveredAtMs);
        active = null;
        healthySinceMs = -1;
        warningSinceMs = -1;
        if (database != null) {
            database.logAsync("incident", incident.peakScore(),
                    String.format(java.util.Locale.ROOT, "#%d start=%s severity=%s duration=%ds worstTick=%.0fms lowestTps=%.1f cause=%s",
                            incident.id(),
                            new java.text.SimpleDateFormat(IncidentReport.DATE_TIME).format(new java.util.Date(incident.startedAtMs())),
                            incident.peakSeverity(), incident.durationMs() / 1000,
                            incident.worstTickMs(), incident.lowestTps(), IncidentReport.primaryCause(incident)));
        }
        IncidentReport.write(plugin, lang, incident, mainThreadSampler, errorMonitor, describer());
        Listener l = listener;
        if (l != null && !incident.alertsSuppressed()) {
            l.incidentResolved(incident);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Findings                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * True when this incident is the scheduled world save and alerting for it
     * is dampened.
     *
     * <p>A nightly backup runs {@code save-all flush}, which blocks the tick
     * loop for as long as the worlds need (7 s on a 25 GB install) and is
     * correctly measured as an EMERGENCY-grade stall. Paging someone for a
     * known, harmless, scheduled event is noise. The incident is still
     * recorded in full - only the alert is withheld, so the measurement stays
     * honest and the history stays complete.
     */
    private boolean alertDampened(Incident incident) {
        if (config == null || !config.dampenWorldSaveAlerts()) {
            return false;
        }
        List<Finding> findings = incident.findings();
        return !findings.isEmpty() && findings.get(0).type() == Finding.Type.WORLD_SAVE;
    }

    private void refreshFindings(Incident incident, TickStats ticks, GcSampler.GcStats gc, long now) {
        lastFindingsRefreshMs = now;
        requestEntitySweep();
        List<Finding> findings = collectFindings(ticks, gc);
        incident.setFindings(findings);
        var sampler = this.mainThreadSampler;
        if (sampler != null && sampler.isRunning()) {
            List<String> lines = new java.util.ArrayList<>();
            for (var cost : sampler.costs(1)) {
                if (lines.size() >= 8 || cost.percent() < 1.0) {
                    break;
                }
                lines.add(String.format(java.util.Locale.ROOT, "%s: %.1f ms/s (%.0f %%)%s",
                        describeSampleTarget(cost.category(), cost.label(), cost.detail()),
                        cost.msPerSec(), cost.percent(),
                        cost.waitPercent() >= 20 ? " — " + lang.format("finding.tickcost.waiting",
                                cost.waitPercent(), waitName(cost.waitKind())).strip() : ""));
                for (var hotspot : cost.hotspots()) {
                    lines.add(String.format(java.util.Locale.ROOT, "    %4.0f %%  %s", hotspot.percent(), hotspot.path()));
                }
            }
            if (!lines.isEmpty()) {
                incident.setProfile(lines);
            }
        }
    }

    /** Localized "what the main thread was waiting on". */
    public String waitName(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.WaitKind wait) {
        return lang.format("sampler.wait." + wait.name().toLowerCase(java.util.Locale.ROOT));
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
                    // The dominant activity belongs in the title, not only in
                    // the detail line: the incident list and the Discord alert
                    // show the title alone, and "high activity chunk" makes an
                    // admin go looking for what the plugin already knows.
                    lang.format("finding.hotchunk.title", dominant,
                            chunk.worldName(), chunk.blockX(), chunk.blockZ()),
                    lang.format("finding.hotchunk.detail", dominant,
                            chunk.hopperMovesPerSec(), chunk.redstonePerSec(), chunk.spawnsPerSec(),
                            chunk.neighborUpdatesPerSec()),
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
        // Only recent loads count: the lifetime total lets any busy chunk
        // collect the blame for every later incident.
        for (ChunkTracker.FrequentlyLoadedChunk frequent
                : chunkTracker.getRecentlyChurningChunks(CHURN_WINDOW_MS, 20, 2)) {
            findings.add(Finding.located(Finding.Type.CHUNK_CHURN, Severity.NOTICE, Finding.Confidence.HEURISTIC,
                    lang.format("finding.churn.title", frequent.worldName(), frequent.blockX(), frequent.blockZ()),
                    lang.format("finding.churn.detail", frequent.loadCount()),
                    lang.format("finding.churn.recommendation"),
                    frequent.worldName(), frequent.blockX(), frequent.blockZ(), frequent.loadCount()));
        }

        // 6. Entity hotspots from the last global-thread sweep (Paper only),
        // and only while that sweep is recent: an old result would present
        // a crowd that may long be gone as the cause of a new incident.
        // EntityHotspot.x()/z() are already block coordinates.
        boolean sweepFresh = System.currentTimeMillis() - lastEntitySweepAtMs <= ENTITY_HOTSPOT_MAX_AGE_MS;
        for (EntityAnalyzer.EntityHotspot hotspot : sweepFresh ? lastEntityHotspots : List.<EntityAnalyzer.EntityHotspot>of()) {
            findings.add(Finding.located(Finding.Type.ENTITY_HOTSPOT,
                    hotspot.severity() == EntityAnalyzer.HotspotSeverity.CRITICAL ? Severity.WARNING : Severity.NOTICE,
                    Finding.Confidence.MEASURED,
                    lang.format("finding.entities.title", hotspot.worldName(), hotspot.x(), hotspot.z()),
                    lang.format("finding.entities.detail", hotspot.entityCount(),
                            String.valueOf(hotspot.dominantType()), hotspot.dominantCount()),
                    lang.format("finding.entities.recommendation"),
                    hotspot.worldName(), hotspot.x(), hotspot.z(), hotspot.entityCount()));
        }

        // 6b. A player who just arrived. Unlike the markers below this one is
        // *not* pinned: a login or teleport overlapping an incident is a
        // correlation, not proof, so it takes part in the normal sort and
        // surfaces only when nothing measured outranks it - which is exactly
        // the "no clear cause" case it exists to answer.
        long sinceArrivalMs = System.currentTimeMillis() - lastArrivalAtMs;
        if (lastArrivalAtMs > 0 && sinceArrivalMs <= ARRIVAL_ATTRIBUTION_MS) {
            findings.add(Finding.global(Finding.Type.PLAYER_ARRIVAL, Severity.NOTICE,
                    Finding.Confidence.HEURISTIC,
                    lang.format("finding.arrival.title"),
                    lang.format(lastArrivalDetailKey, lastArrivalWorldName, sinceArrivalMs / 1000),
                    lang.format("finding.arrival.recommendation"),
                    sinceArrivalMs / 1000.0));
        }

        // 6c. Errors from the server log. Only the ones that directly cost
        // tick time (an aborted update chain, a StackOverflow from an update
        // suppressor) count as measured; any other exception is a correlation.
        var errors = this.errorMonitor;
        if (errors != null && errors.isAttached()) {
            int shown = 0;
            for (var error : errors.recent(ERROR_ATTRIBUTION_MS)) {
                if (shown++ >= 3) {
                    break;
                }
                findings.add(errorFinding(error));
            }
        }

        // 6d. Sampled main-thread cost: plugin tasks, entity and block-entity
        // ticking, chunk I/O - everything the event-based sources cannot see.
        var mainSampler = this.mainThreadSampler;
        if (mainSampler != null && mainSampler.isRunning()) {
            addSampledFindings(findings, mainSampler, timings != null && timings.isActive());
        }

        // 6e. A steadily rising memory floor is no cause of a single stall,
        // but it is the explanation once GC pressure starts to bite.
        GcSampler gcs = this.gcSampler;
        if (gcs != null) {
            GcSampler.MemoryTrend trend = gcs.memoryTrend();
            if (trend.leakSuspected()) {
                findings.add(Finding.global(Finding.Type.MEMORY_TREND,
                        trend.hoursUntilFull() >= 0 && trend.hoursUntilFull() < 6 ? Severity.WARNING : Severity.NOTICE,
                        Finding.Confidence.HEURISTIC,
                        lang.format("finding.memtrend.title", trend.slopeMbPerHour()),
                        lang.format("finding.memtrend.detail", trend.floorMb(), trend.maxMb(),
                                trend.spanHours(), trend.hoursUntilFull()),
                        lang.format("finding.memtrend.recommendation"),
                        trend.slopeMbPerHour()));
            }
        }

        // Within one severity, measured findings come first: a log error that
        // merely coincides with the incident must not outrank a measured cost.
        findings.sort(Comparator.comparingInt((Finding f) -> f.severity().level()).reversed()
                .thenComparing(Finding::confidence));

        // 7. Unmeasured stall: the main thread was blocked outside the tick
        // loop, so none of the instrumented sources above could have seen it.
        // Pinned high because it explains the one case that otherwise ends in
        // "no clear cause" — a healthy worst tick next to collapsed TPS.
        TickTimeSampler sampler = this.tickSampler;
        if (sampler != null) {
            long sinceStallMs = System.currentTimeMillis() - sampler.lastStallAtMs();
            if (sampler.lastStallAtMs() > 0 && sinceStallMs <= STALL_ATTRIBUTION_MS) {
                double stallSeconds = sampler.lastStallMs() / 1000.0;
                Severity severity = stallSeconds >= 5 ? Severity.CRITICAL : Severity.WARNING;
                findings.add(0, Finding.global(Finding.Type.UNMEASURED_STALL, severity,
                        Finding.Confidence.MEASURED,
                        lang.format("finding.stall.title"),
                        lang.format("finding.stall.detail", stallSeconds, sinceStallMs / 1000),
                        lang.format("finding.stall.advice"),
                        stallSeconds));
            }
        }

        // 8. Cause markers, pinned above everything measured inside the tick
        // loop — the work they describe happens outside it, so no counter can
        // have seen it. Inserted at 0 in ascending order of confidence, which
        // leaves them ordered world save > command > stall. A login or
        // teleport (6b) is deliberately not pinned: it only correlates.
        long sinceCommandMs = System.currentTimeMillis() - lastExpensiveCommandAtMs;
        if (lastExpensiveCommandAtMs > 0 && sinceCommandMs <= COMMAND_ATTRIBUTION_MS) {
            findings.add(0, Finding.global(Finding.Type.EXPENSIVE_COMMAND, Severity.NOTICE,
                    Finding.Confidence.MEASURED,
                    lang.format("finding.command.title"),
                    lang.format("finding.command.detail", lastExpensiveCommand,
                            lastExpensiveCommandSender, sinceCommandMs / 1000),
                    lang.format("finding.command.recommendation"),
                    sinceCommandMs / 1000.0));
        }

        long sinceSaveMs = System.currentTimeMillis() - lastWorldSaveAtMs;
        if (lastWorldSaveAtMs > 0 && sinceSaveMs <= WORLD_SAVE_ATTRIBUTION_MS) {
            findings.add(0, Finding.global(Finding.Type.WORLD_SAVE, Severity.NOTICE,
                    Finding.Confidence.MEASURED,
                    lang.format("finding.worldsave.title"),
                    lang.format("finding.worldsave.detail", lastSavedWorldName, sinceSaveMs / 1000),
                    lang.format("finding.worldsave.recommendation"),
                    sinceSaveMs / 1000.0));
        }

        if (findings.isEmpty()) {
            findings.add(Finding.global(Finding.Type.UNKNOWN, Severity.NOTICE, Finding.Confidence.HEURISTIC,
                    lang.format("finding.unknown.title"),
                    lang.format("finding.unknown.detail"),
                    lang.format("finding.unknown.recommendation"),
                    0));
        }
        return findings;
    }

    /** Below this a sampled cost is noise: 10 ms/s is 1 % of one second. */
    private static final double SAMPLED_COST_MIN_MS = 10.0;
    private static final long SLOW_TICK_ATTRIBUTION_MS = 30_000L;

    private void addSampledFindings(List<Finding> findings,
                                    dev.boondock.performanceanalyzer.metrics.MainThreadSampler sampler,
                                    boolean listenerTimingsActive) {
        int added = 0;
        for (var cost : sampler.costs(3)) {
            if (cost.msPerSec() < SAMPLED_COST_MIN_MS || added >= 4) {
                break;
            }
            // Listener time is measured exactly by ListenerTimings; a sampled
            // estimate next to it would only report the same plugin twice.
            if (listenerTimingsActive && cost.category() == dev.boondock.performanceanalyzer.metrics.MainThreadSampler.Category.PLUGIN
                    && "LISTENER".equals(cost.detail())) {
                continue;
            }
            if (cost.category() == dev.boondock.performanceanalyzer.metrics.MainThreadSampler.Category.SERVER) {
                continue;
            }
            Severity severity = cost.msPerSec() >= 200 ? Severity.CRITICAL
                    : cost.msPerSec() >= 100 ? Severity.WARNING : Severity.NOTICE;
            String target = describeSampleTarget(cost.category(), cost.label(), cost.detail());
            findings.add(Finding.global(Finding.Type.TICK_COST, severity, Finding.Confidence.MEASURED,
                    lang.format("finding.tickcost.title", target, cost.msPerSec()),
                    tickCostDetail(cost),
                    isStructureSearch(cost.category(), cost.label())
                            ? lang.format("finding.structure.recommendation")
                            : blocking(cost)
                            ? lang.format("finding.tickcost.recommendation.blocking", target, waitName(cost.waitKind()))
                            : lang.format("finding.tickcost.recommendation."
                            + cost.category().name().toLowerCase(java.util.Locale.ROOT),
                            switch (cost.category()) {
                                case PLUGIN, ENTITY, BLOCK_ENTITY -> cost.label();
                                default -> target;
                            }),
                    cost.msPerSec()));
            added++;
        }

        var slowest = sampler.slowTicks(SLOW_TICK_ATTRIBUTION_MS).stream()
                .max(Comparator.comparingDouble(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.SlowTick::durationMs));
        slowest.ifPresent(tick -> {
            Severity severity = tick.durationMs() >= 1_000 ? Severity.CRITICAL
                    : tick.durationMs() >= 250 ? Severity.WARNING : Severity.NOTICE;
            String frames = String.join("\n   < ", tick.frames().subList(0, Math.min(5, tick.frames().size())));
            String detail = lang.format("finding.slowtick.detail", frames);
            if (tick.waitKind() != dev.boondock.performanceanalyzer.metrics.MainThreadSampler.WaitKind.NONE) {
                detail += lang.format("finding.slowtick.waiting", waitName(tick.waitKind()));
            }
            String trigger = describeTrigger(tick.trigger());
            if (!trigger.isEmpty()) {
                detail += lang.format("finding.slowtick.trigger", trigger);
            }
            if (tick.betweenTicks()) {
                detail += lang.format("finding.slowtick.between_ticks");
            }
            findings.add(Finding.global(Finding.Type.SLOW_TICK, severity, Finding.Confidence.MEASURED,
                    lang.format("finding.slowtick.title", tick.durationMs(),
                            describeSampleTarget(tick.category(), tick.label(), tick.detail())),
                    detail,
                    isStructureSearch(tick.category(), tick.label())
                            ? lang.format("finding.structure.recommendation")
                            : lang.format("finding.slowtick.recommendation"),
                    tick.durationMs()));
        });
    }

    private static boolean isStructureSearch(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.Category category,
                                             String label) {
        return category == dev.boondock.performanceanalyzer.metrics.MainThreadSampler.Category.CHUNK_IO
                && "structure_search".equals(label);
    }

    /**
     * Records a finished slow tick: always in the database (rate-limited),
     * and with stack and context in incidents.log when it is long and no
     * incident is open - an open incident's report lists it anyway.
     * Called on the sampler thread; the file is written asynchronously.
     */
    public void logSlowTick(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.SlowTick tick) {
        long last = lastSlowTickLogMs.get();
        if (tick.atMs() - last < SLOW_TICK_LOG_MIN_INTERVAL_MS || !lastSlowTickLogMs.compareAndSet(last, tick.atMs())) {
            return;
        }
        String target = describeSampleTarget(tick.category(), tick.label(), tick.detail());
        String trigger = describeTrigger(tick.trigger());
        if (database != null) {
            database.logAsync("slow_tick", tick.durationMs(), IncidentReport.plain(target
                    + (trigger.isEmpty() ? "" : " | " + trigger)
                    + (tick.waitKind() == dev.boondock.performanceanalyzer.metrics.MainThreadSampler.WaitKind.NONE
                    ? "" : " | wait=" + tick.waitKind())
                    + (tick.betweenTicks() ? " | between" : "")
                    + " | players=" + org.bukkit.Bukkit.getOnlinePlayers().size()));
        }
        if (tick.durationMs() >= SLOW_TICK_REPORT_MS && plugin != null && plugin.isEnabled()) {
            // A stall usually ends before the evaluation that opens its
            // incident; waiting a moment keeps it from being written twice.
            IncidentReport.Describer describer = describer();
            Scheduling.runAsyncDelayed(plugin, () -> {
                if (!coveredByIncident(tick.atMs())) {
                    IncidentReport.writeSlowTick(plugin, lang, tick, describer);
                }
            }, SLOW_TICK_REPORT_DELAY_MS);
        }
    }

    /** True when an incident's report will list a slow tick that ended at {@code atMs}. */
    private boolean coveredByIncident(long atMs) {
        Incident current = active;
        if (current != null && current.startedAtMs() - IncidentReport.SLOW_TICK_MARGIN_MS <= atMs) {
            return true;
        }
        for (Incident incident : recentIncidents()) {
            long end = incident.isActive() ? Long.MAX_VALUE : incident.startedAtMs() + incident.durationMs();
            if (atMs >= incident.startedAtMs() - IncidentReport.SLOW_TICK_MARGIN_MS && atMs <= end + IncidentReport.SLOW_TICK_MARGIN_MS) {
                return true;
            }
        }
        return false;
    }

    /** Localized entry point of a slow tick, empty when the stack named none. */
    public String describeTrigger(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.Trigger trigger) {
        if (trigger == null) {
            return "";
        }
        String entry = switch (trigger.kind()) {
            case NONE -> "";
            case PLAYER_ACTION -> lang.format("sampler.trigger.player_action",
                    trigger.what().startsWith("handle") ? trigger.what().substring("handle".length()) : trigger.what());
            case COMMAND -> lang.format("sampler.trigger.command");
            case PLUGIN -> describeSampleTarget(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.Category.PLUGIN,
                    trigger.what(), trigger.detail());
            case ENTITY -> lang.format("sampler.target.entity", trigger.what());
            case BLOCK_ENTITY -> lang.format("sampler.target.block_entity", trigger.what());
            case CHUNK_TICK -> lang.format("sampler.trigger.chunk_tick");
            case WORLD_SAVE -> lang.format("sampler.trigger.world_save");
        };
        if (trigger.via().isEmpty()) {
            return entry;
        }
        String via = lang.format("sampler.trigger.via", trigger.via());
        return entry.isEmpty() ? via.strip() : entry + via;
    }

    private IncidentReport.Describer describer() {
        return new IncidentReport.Describer() {
            @Override
            public String target(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.SlowTick tick) {
                return describeSampleTarget(tick.category(), tick.label(), tick.detail());
            }

            @Override
            public String trigger(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.Trigger trigger) {
                return describeTrigger(trigger);
            }

            @Override
            public List<String> context(long fromMs, long toMs) {
                return contextLines(fromMs, toMs);
            }
        };
    }

    /** Players online at the start of the span and the actions recorded within it. */
    List<String> contextLines(long fromMs, long toMs) {
        ActivityContext context = this.activityContext;
        if (context == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        var players = context.playersAt(fromMs + IncidentReport.CONTEXT_BEFORE_MS);
        if (!players.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (var p : players) {
                names.add(String.format(java.util.Locale.ROOT, "%s (%s %d, %d)", p.name(), p.world(), p.x(), p.z()));
            }
            lines.add(lang.format("report.context_players", String.join(", ", names)));
        }
        var events = context.events(fromMs, toMs);
        java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm:ss");
        for (var e : events) {
            lines.add(String.format(java.util.Locale.ROOT, "%s  %s: %s%s",
                    time.format(new java.util.Date(e.atMs())),
                    e.player().isEmpty() ? "-" : e.player(),
                    lang.format("context.kind." + e.kind().name().toLowerCase(java.util.Locale.ROOT), e.detail()),
                    e.world().isEmpty() ? "" : String.format(java.util.Locale.ROOT, " (%s %d, %d)", e.world(), e.x(), e.z())));
        }
        if (events.isEmpty() && !players.isEmpty()) {
            lines.add(lang.format("report.context_none"));
        }
        return lines;
    }

    /** Waiting is a hang worth its own advice once it is a fifth of the target's time. */
    private static boolean blocking(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.Cost cost) {
        return cost.waitPercent() >= 20
                && cost.waitKind() != dev.boondock.performanceanalyzer.metrics.MainThreadSampler.WaitKind.LOCK
                || cost.waitPercent() >= 50;
    }

    private String tickCostDetail(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.Cost cost) {
        StringBuilder detail = new StringBuilder(lang.format("finding.tickcost.detail", cost.msPerSec(), cost.percent()));
        int shown = 0;
        for (var hotspot : cost.hotspots()) {
            if (shown++ >= 2) {
                break;
            }
            detail.append(lang.format("finding.tickcost.hotspot", hotspot.path(), hotspot.percent()));
        }
        if (cost.waitPercent() >= 20) {
            detail.append(lang.format("finding.tickcost.waiting", cost.waitPercent(), waitName(cost.waitKind())));
        }
        return detail.toString();
    }

    /** Human-readable name of what a main-thread sample was attributed to. */
    public String describeSampleTarget(dev.boondock.performanceanalyzer.metrics.MainThreadSampler.Category category,
                                       String label, String detail) {
        return switch (category) {
            case PLUGIN -> lang.format("sampler.target.plugin."
                    + (detail == null || detail.isEmpty() ? "other" : detail.toLowerCase(java.util.Locale.ROOT)), label);
            case ENTITY -> lang.format("sampler.target.entity", label);
            case BLOCK_ENTITY -> lang.format("sampler.target.block_entity", label);
            case CHUNK_IO -> lang.format("sampler.target.chunk." + label);
            case REDSTONE -> lang.format("sampler.target.redstone");
            case SERVER -> lang.format("sampler.target.server");
        };
    }

    private Finding errorFinding(dev.boondock.performanceanalyzer.monitor.ServerErrorMonitor.ErrorSummary error) {
        String kind = error.kind().name().toLowerCase(java.util.Locale.ROOT);
        Finding.Confidence confidence = switch (error.kind()) {
            case NEIGHBOR_CHAIN, STACK_OVERFLOW, OUT_OF_MEMORY -> Finding.Confidence.MEASURED;
            default -> Finding.Confidence.HEURISTIC;
        };
        return Finding.global(Finding.Type.SERVER_ERROR, error.severity(), confidence,
                lang.format("finding.error.title." + kind, error.source()),
                lang.format("finding.error.detail", error.recentCount(), error.lastMessage()),
                lang.format("finding.error.recommendation." + kind, error.source()),
                error.recentCount());
    }

    private String dominantActivity(ActivityCounters.HotChunk chunk) {
        double hopper = chunk.hopperMovesPerSec() * 3;
        double redstone = chunk.redstonePerSec() + chunk.pistonsPerSec() * 2;
        double spawns = chunk.spawnsPerSec() * 4;
        double neighbors = chunk.neighborUpdatesPerSec() * 0.1;
        if (neighbors > hopper && neighbors > redstone && neighbors > spawns) {
            return lang.format("finding.hotchunk.kind.neighbor");
        }
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
        long now = System.currentTimeMillis();
        if (now - lastEntitySweepRequestAtMs < ENTITY_SWEEP_MIN_INTERVAL_MS) {
            return;
        }
        lastEntitySweepRequestAtMs = now;
        entitySweepPending = true;
        Scheduling.runGlobal(plugin, () -> {
            try {
                EntityAnalyzer.EntityAnalysis analysis = entityAnalyzer.analyze();
                List<EntityAnalyzer.EntityHotspot> hotspots = analysis.hotspots();
                lastEntityHotspots = hotspots.size() > 3 ? hotspots.subList(0, 3) : hotspots;
                lastEntitySweepAtMs = System.currentTimeMillis();
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
