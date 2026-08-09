package dev.boondock.performanceanalyzer.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.boondock.performanceanalyzer.analysis.ActivityCounters;
import dev.boondock.performanceanalyzer.analysis.Baseline;
import dev.boondock.performanceanalyzer.analysis.Finding;
import dev.boondock.performanceanalyzer.analysis.Incident;
import dev.boondock.performanceanalyzer.analysis.IncidentAnalyzer;
import dev.boondock.performanceanalyzer.analysis.SeverityModel;
import dev.boondock.performanceanalyzer.analysis.WorldStatsManager;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.metrics.GcSampler;
import dev.boondock.performanceanalyzer.metrics.TickStats;
import dev.boondock.performanceanalyzer.monitor.MonitorService;
import dev.boondock.performanceanalyzer.timing.ListenerTimings;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * REST API for external monitoring tools (Grafana, Uptime-Kuma, custom
 * dashboards).
 *
 * <p>Security model: binds to {@code 127.0.0.1} by default — exposing it on
 * {@code 0.0.0.0} should only ever happen behind a TLS-terminating reverse
 * proxy. Every endpoint except {@code /api/health} requires
 * {@code Authorization: Bearer <api.key>}; the token comparison is
 * constant-time. The server refuses to start when the key is empty or still
 * the {@code changeme} placeholder.</p>
 *
 * <p>Thread model: handlers run on a small daemon thread pool and read only
 * lock-free snapshots published by the samplers ({@link MonitorService},
 * {@link ActivityCounters}, {@link ListenerTimings}, cached
 * {@link WorldStatsManager} snapshots). No Bukkit world/entity API is ever
 * touched from an HTTP thread — that would be an illegal cross-thread access
 * on Folia and a lag source on Paper.</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/health             - liveness probe (no auth)</li>
 *   <li>GET /api/metrics            - full tick/GC/severity/baseline/plugin/activity snapshot</li>
 *   <li>GET /api/incidents          - active + recent incidents with findings</li>
 *   <li>GET /api/worlds             - cached per-world stats + hot chunks</li>
 *   <li>GET /api/metrics/prometheus - Prometheus text exposition (v0.0.4)</li>
 * </ul>
 */
public final class MetricsAPI {

    private static final int MAX_PLUGIN_LOADS = 10;

    private final Plugin plugin;
    private final PluginConfig config;
    private final MonitorService monitor;
    private final IncidentAnalyzer incidents;
    private final ActivityCounters activity;
    private final ListenerTimings timings; // nullable (plugin_analysis: false)
    private final WorldStatsManager worldStats;
    @SuppressWarnings("unused") // reserved for future /api/history endpoints
    private final DatabaseManager database;

    private HttpServer server;
    private ExecutorService executor;
    private byte[] apiKeyBytes;

    public MetricsAPI(Plugin plugin, PluginConfig config, MonitorService monitor,
                      IncidentAnalyzer incidents, ActivityCounters activity,
                      ListenerTimings timings, WorldStatsManager worldStats,
                      DatabaseManager database) {
        this.plugin = plugin;
        this.config = config;
        this.monitor = monitor;
        this.incidents = incidents;
        this.activity = activity;
        this.timings = timings;
        this.worldStats = worldStats;
        this.database = database;
    }

    /**
     * Start the API server. Logs and refuses instead of throwing: an API
     * that cannot start must never take the whole plugin down.
     *
     * @return true when the server is listening
     */
    public boolean start() {
        if (!config.apiEnabled()) {
            return false;
        }

        String key = config.apiKey();
        if (key == null || key.isEmpty() || "changeme".equals(key)) {
            plugin.getLogger().severe("[API] api.enabled is true but api.key is empty or still "
                    + "\"changeme\". Set a strong random key in config.yml.");
            plugin.getLogger().severe("[API] REST API NOT started for security reasons.");
            return false;
        }
        this.apiKeyBytes = key.getBytes(StandardCharsets.UTF_8);

        String bind = config.apiBind();
        int port = config.apiPort();

        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (java.net.BindException e) {
            // By far the most common setup mistake: the default port 8080 is
            // already taken by a map plugin (squaremap, dynmap, BlueMap).
            // "Address already in use" alone sends admins hunting in the
            // wrong place, so name the cause and the fix.
            plugin.getLogger().severe("[API] Port " + port + " on " + bind
                    + " is already in use - the REST API was NOT started.");
            plugin.getLogger().severe("[API] Another plugin is most likely listening there "
                    + "(squaremap, dynmap and BlueMap all default to 8080). "
                    + "Set a free port in config.yml under api.port.");
            server = null;
            return false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "[API] Could not bind " + bind + ":" + port
                    + " - " + e.getMessage());
            server = null;
            return false;
        }

        server.createContext("/api/health", new ApiHandler("/api/health", false, this::healthJson));
        server.createContext("/api/metrics", new ApiHandler("/api/metrics", true, this::metricsJson));
        server.createContext("/api/incidents", new ApiHandler("/api/incidents", true, this::incidentsJson));
        server.createContext("/api/worlds", new ApiHandler("/api/worlds", true, this::worldsJson));
        server.createContext("/api/metrics/prometheus",
                new ApiHandler("/api/metrics/prometheus", true, this::prometheusText));

        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "PerformanceAnalyzer-API");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();

        plugin.getLogger().info("[API] REST API listening on " + bind + ":" + port);
        if (!"127.0.0.1".equals(bind) && !"localhost".equals(bind)) {
            plugin.getLogger().warning("[API] api.bind is " + bind + " - the API is reachable from "
                    + "the network. Only do this behind a TLS reverse proxy (nginx/caddy).");
        }
        return true;
    }

    /** Stop the API server and its executor. Safe to call when never started. */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            plugin.getLogger().info("[API] REST API stopped");
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Request plumbing                                                    */
    /* ------------------------------------------------------------------ */

    /** A prepared response: body plus content type. */
    private record Response(String contentType, String body) {
        static Response json(String body) {
            return new Response("application/json; charset=utf-8", body);
        }
    }

    @FunctionalInterface
    private interface ResponseSupplier {
        Response get() throws Exception;
    }

    /**
     * Shared handler shell: method check, exact-path check (contexts match by
     * prefix), auth, and a hard exception barrier — clients get a plain 500,
     * never a stack trace.
     */
    private final class ApiHandler implements HttpHandler {
        private final String path;
        private final boolean requiresAuth;
        private final ResponseSupplier supplier;

        ApiHandler(String path, boolean requiresAuth, ResponseSupplier supplier) {
            this.path = path;
            this.requiresAuth = requiresAuth;
            this.supplier = supplier;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String requestPath = exchange.getRequestURI().getPath();
                if (requestPath != null && requestPath.endsWith("/") && requestPath.length() > 1) {
                    requestPath = requestPath.substring(0, requestPath.length() - 1);
                }
                if (!path.equals(requestPath)) {
                    sendError(exchange, 404, "Not found");
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method not allowed");
                    return;
                }
                if (requiresAuth && !isAuthenticated(exchange)) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    sendError(exchange, 401, "Unauthorized");
                    return;
                }
                Response response = supplier.get();
                send(exchange, 200, response.contentType(), response.body());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[API] Handler failed for " + path + ": " + e, e);
                try {
                    sendError(exchange, 500, "Internal server error");
                } catch (IOException ignored) {
                    // Client is gone; nothing to do.
                }
            } finally {
                exchange.close();
            }
        }
    }

    /** Constant-time bearer-token check ({@link MessageDigest#isEqual}). */
    private boolean isAuthenticated(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] provided = header.substring("Bearer ".length()).trim()
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, apiKeyBytes);
    }

    private void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Json json = new Json().obj().field("error", message).field("status", status).endObj();
        send(exchange, status, "application/json; charset=utf-8", json.toString());
    }

    /* ------------------------------------------------------------------ */
    /* Endpoints                                                           */
    /* ------------------------------------------------------------------ */

    /** GET /api/health — liveness probe, deliberately unauthenticated. */
    private Response healthJson() {
        TickStats ticks = monitor.tickStats();
        SeverityModel.Assessment assessment = monitor.assessment();
        Json json = new Json().obj()
                .field("status", "ok")
                .field("tps10s", ticks.hasData() ? ticks.tps10s() : -1.0)
                .field("severity", assessment.severity().name())
                .field("active_incident", incidents.activeIncident() != null)
                .endObj();
        return Response.json(json.toString());
    }

    /** GET /api/metrics — the full measurement snapshot. */
    private Response metricsJson() {
        TickStats ticks = monitor.tickStats();
        GcSampler.GcStats gc = monitor.gcStats();
        SeverityModel.Assessment assessment = monitor.assessment();
        Baseline baseline = monitor.baseline();
        ActivityCounters.GlobalActivity global = activity.globalActivity();

        Json json = new Json().obj()
                .field("timestamp", System.currentTimeMillis());

        json.name("tick").obj()
                .field("has_data", ticks.hasData())
                .field("mspt_avg_10s", ticks.msptAvg10s())
                .field("mspt_p50", ticks.msptP50())
                .field("mspt_p95", ticks.msptP95())
                .field("mspt_p99", ticks.msptP99())
                .field("mspt_max_10s", ticks.msptMax10s())
                .field("worst_tick_ms_60s", ticks.worstTickMs60s())
                .field("tps_10s", ticks.tps10s())
                .field("tps_60s", ticks.tps60s())
                .field("sample_count_10s", ticks.sampleCount10s())
                .field("event_driven", ticks.eventDriven())
                .field("folia", ticks.folia())
                .endObj();

        json.name("gc").obj()
                .field("count_60s", gc.gcCount60s())
                .field("time_ms_60s", gc.gcTimeMs60s())
                .field("longest_pause_ms_60s", gc.longestPauseMs60s())
                .fieldOrNull("oldgen_after_gc_percent",
                        gc.hasAfterGcData() ? gc.oldGenAfterGcPercent() : null)
                .field("heap_used_percent", gc.heapUsedPercent())
                .field("heap_used_mb", gc.heapUsedMb())
                .field("heap_max_mb", gc.heapMaxMb())
                .field("metaspace_used_percent", gc.metaspaceUsedPercent())
                .endObj();

        json.name("assessment").obj()
                .field("severity", assessment.severity().name())
                .field("score", assessment.score());
        json.name("reasons").arr();
        for (String reason : assessment.reasons()) {
            json.value(reason);
        }
        json.endArr().endObj();

        json.name("baseline").obj()
                .field("established", baseline.isEstablished())
                .field("avg_mspt", baseline.avgMspt())
                .field("p95_mspt", baseline.p95Mspt())
                .endObj();

        json.name("plugin_loads").arr();
        for (ListenerTimings.PluginLoad load : topPluginLoads()) {
            json.obj()
                    .field("name", load.pluginName())
                    .field("ms_per_sec", load.msPerSec())
                    .field("percent", load.percentOfThreadBudget())
                    .endObj();
        }
        json.endArr();

        json.name("activity").obj()
                .field("redstone_per_sec", global.redstonePerSec())
                .field("pistons_per_sec", global.pistonsPerSec())
                .field("hopper_moves_per_sec", global.hopperMovesPerSec())
                .field("spawns_per_sec", global.spawnsPerSec())
                .field("chunk_loads_per_sec", global.chunkLoadsPerSec())
                .field("chunk_gens_per_sec", global.chunkGensPerSec())
                .field("tracked_chunks", global.trackedChunks())
                .field("overflow", global.overflow())
                .endObj();

        json.endObj();
        return Response.json(json.toString());
    }

    /** GET /api/incidents — active incident (or null) plus recent history. */
    private Response incidentsJson() {
        Incident active = incidents.activeIncident();
        List<Incident> recent = incidents.recentIncidents();

        Json json = new Json().obj();
        json.name("active");
        if (active == null) {
            json.nullValue();
        } else {
            writeIncident(json, active);
        }
        json.name("recent").arr();
        for (Incident incident : recent) {
            writeIncident(json, incident);
        }
        json.endArr().endObj();
        return Response.json(json.toString());
    }

    private void writeIncident(Json json, Incident incident) {
        json.obj()
                .field("id", incident.id())
                .field("started_at_ms", incident.startedAtMs())
                .field("active", incident.isActive())
                .field("duration_ms", incident.durationMs())
                .field("current_severity", incident.currentSeverity().name())
                .field("peak_severity", incident.peakSeverity().name())
                .field("peak_score", incident.peakScore())
                .field("worst_tick_ms", incident.worstTickMs())
                .field("worst_avg_mspt", incident.worstAvgMspt())
                .field("lowest_tps", incident.lowestTps());
        json.name("trigger_reasons").arr();
        for (String reason : incident.triggerReasons()) {
            json.value(reason);
        }
        json.endArr();
        json.name("findings").arr();
        for (Finding finding : incident.findings()) {
            json.obj()
                    .field("type", finding.type().name())
                    .field("severity", finding.severity().name())
                    .field("confidence", finding.confidence().name())
                    .field("title", finding.title())
                    .field("detail", finding.detail())
                    .field("recommendation", finding.recommendation())
                    .field("metric", finding.metric());
            if (finding.hasLocation()) {
                json.field("world", finding.worldName())
                        .field("x", finding.blockX())
                        .field("z", finding.blockZ());
            }
            json.endObj();
        }
        json.endArr().endObj();
    }

    /**
     * GET /api/worlds — the cached snapshots the 5-minute scheduler fills via
     * {@code recordAllSnapshots()} plus the always-available activity hot
     * chunks. Never touches Bukkit world APIs from this thread; on Folia (no
     * global world scan) the worlds array stays empty and hot chunks are the
     * fallback signal.
     */
    private Response worldsJson() {
        List<WorldStatsManager.WorldStats> cached = worldStats.cachedWorldStats();
        long snapshotAt = worldStats.lastSnapshotAtMs();
        List<ActivityCounters.HotChunk> hot = activity.hotChunks();

        Json json = new Json().obj();
        if (snapshotAt > 0) {
            json.field("snapshot_at_ms", snapshotAt)
                    .field("snapshot_age_ms", System.currentTimeMillis() - snapshotAt);
        } else {
            json.name("snapshot_at_ms").nullValue();
            json.field("note", "No world snapshot available yet (collected every 5 minutes "
                    + "on Paper; unavailable on Folia). See hot_chunks for measured activity.");
        }
        json.name("worlds").arr();
        for (WorldStatsManager.WorldStats stats : cached) {
            json.obj()
                    .field("name", stats.worldName())
                    .field("environment", stats.getEnvironmentName())
                    .field("entities", stats.entityCount())
                    .field("players", stats.playerCount())
                    .field("loaded_chunks", stats.loadedChunks())
                    .field("tile_entities", stats.tileEntityCount())
                    .field("entity_density", stats.entityDensity())
                    .endObj();
        }
        json.endArr();
        json.name("hot_chunks").arr();
        for (ActivityCounters.HotChunk chunk : hot) {
            json.obj()
                    .field("world", chunk.worldName())
                    .field("chunk_x", chunk.chunkX())
                    .field("chunk_z", chunk.chunkZ())
                    .field("block_x", chunk.blockX())
                    .field("block_z", chunk.blockZ())
                    .field("redstone_per_sec", chunk.redstonePerSec())
                    .field("pistons_per_sec", chunk.pistonsPerSec())
                    .field("hopper_moves_per_sec", chunk.hopperMovesPerSec())
                    .field("spawns_per_sec", chunk.spawnsPerSec())
                    .field("chunk_loads_per_sec", chunk.chunkLoadsPerSec())
                    .field("chunk_gens_per_sec", chunk.chunkGensPerSec())
                    .field("score", chunk.score())
                    .endObj();
        }
        json.endArr().endObj();
        return Response.json(json.toString());
    }

    /** GET /api/metrics/prometheus — text exposition format 0.0.4 for scraping. */
    private Response prometheusText() {
        TickStats ticks = monitor.tickStats();
        GcSampler.GcStats gc = monitor.gcStats();
        SeverityModel.Assessment assessment = monitor.assessment();
        ActivityCounters.GlobalActivity global = activity.globalActivity();

        StringBuilder sb = new StringBuilder(1024);

        sb.append("# HELP performanceanalyzer_tps Ticks per second (worst-region estimate on Folia).\n");
        sb.append("# TYPE performanceanalyzer_tps gauge\n");
        promLine(sb, "performanceanalyzer_tps", "window", "10s", ticks.tps10s());
        promLine(sb, "performanceanalyzer_tps", "window", "60s", ticks.tps60s());

        sb.append("# HELP performanceanalyzer_mspt Real tick work time in milliseconds (10s window).\n");
        sb.append("# TYPE performanceanalyzer_mspt gauge\n");
        promLine(sb, "performanceanalyzer_mspt", "stat", "avg", ticks.msptAvg10s());
        promLine(sb, "performanceanalyzer_mspt", "stat", "p50", ticks.msptP50());
        promLine(sb, "performanceanalyzer_mspt", "stat", "p95", ticks.msptP95());
        promLine(sb, "performanceanalyzer_mspt", "stat", "p99", ticks.msptP99());
        promLine(sb, "performanceanalyzer_mspt", "stat", "max", ticks.msptMax10s());

        sb.append("# HELP performanceanalyzer_severity_score Severity score 0-100 (0 = healthy).\n");
        sb.append("# TYPE performanceanalyzer_severity_score gauge\n");
        promLine(sb, "performanceanalyzer_severity_score", null, null, assessment.score());

        sb.append("# HELP performanceanalyzer_gc_time_ms_60s Total GC time over the last 60s.\n");
        sb.append("# TYPE performanceanalyzer_gc_time_ms_60s gauge\n");
        promLine(sb, "performanceanalyzer_gc_time_ms_60s", null, null, gc.gcTimeMs60s());

        sb.append("# HELP performanceanalyzer_oldgen_after_gc_percent Old-gen occupancy after last GC.\n");
        sb.append("# TYPE performanceanalyzer_oldgen_after_gc_percent gauge\n");
        promLine(sb, "performanceanalyzer_oldgen_after_gc_percent", null, null,
                gc.hasAfterGcData() ? gc.oldGenAfterGcPercent() : Double.NaN);

        sb.append("# HELP performanceanalyzer_heap_used_percent Heap usage percent.\n");
        sb.append("# TYPE performanceanalyzer_heap_used_percent gauge\n");
        promLine(sb, "performanceanalyzer_heap_used_percent", null, null, gc.heapUsedPercent());

        sb.append("# HELP performanceanalyzer_plugin_listener_ms_per_sec Measured event-listener time per plugin.\n");
        sb.append("# TYPE performanceanalyzer_plugin_listener_ms_per_sec gauge\n");
        for (ListenerTimings.PluginLoad load : topPluginLoads()) {
            promLine(sb, "performanceanalyzer_plugin_listener_ms_per_sec",
                    "plugin", load.pluginName(), load.msPerSec());
        }

        sb.append("# HELP performanceanalyzer_activity Server-wide activity events per second (10s window).\n");
        sb.append("# TYPE performanceanalyzer_activity gauge\n");
        promLine(sb, "performanceanalyzer_activity", "type", "redstone", global.redstonePerSec());
        promLine(sb, "performanceanalyzer_activity", "type", "hopper", global.hopperMovesPerSec());
        promLine(sb, "performanceanalyzer_activity", "type", "spawns", global.spawnsPerSec());
        promLine(sb, "performanceanalyzer_activity", "type", "chunk_gen", global.chunkGensPerSec());

        return new Response("text/plain; version=0.0.4; charset=utf-8", sb.toString());
    }

    /** Append one Prometheus sample line; non-finite values are skipped. */
    private static void promLine(StringBuilder sb, String metric,
                                 String labelName, String labelValue, double value) {
        if (!Double.isFinite(value)) {
            return;
        }
        sb.append(metric);
        if (labelName != null) {
            sb.append('{').append(labelName).append("=\"")
                    .append(labelValue.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                    .append("\"}");
        }
        sb.append(' ').append(formatDouble(value)).append('\n');
    }

    private List<ListenerTimings.PluginLoad> topPluginLoads() {
        if (timings == null) {
            return List.of();
        }
        List<ListenerTimings.PluginLoad> loads = timings.loads();
        return loads.size() > MAX_PLUGIN_LOADS ? loads.subList(0, MAX_PLUGIN_LOADS) : loads;
    }

    private static String formatDouble(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /* ------------------------------------------------------------------ */
    /* Minimal JSON builder (no dependencies, correct escaping)            */
    /* ------------------------------------------------------------------ */

    /**
     * Tiny streaming JSON builder. Tracks whether a comma is needed; a call
     * to {@link #name(String)} suppresses the comma before the value that
     * follows it. Non-finite doubles serialize as {@code null} (JSON has no
     * NaN/Infinity).
     */
    private static final class Json {
        private final StringBuilder sb = new StringBuilder(1024);
        private boolean comma;

        Json obj() {
            pre();
            sb.append('{');
            comma = false;
            return this;
        }

        Json endObj() {
            sb.append('}');
            comma = true;
            return this;
        }

        Json arr() {
            pre();
            sb.append('[');
            comma = false;
            return this;
        }

        Json endArr() {
            sb.append(']');
            comma = true;
            return this;
        }

        Json name(String name) {
            pre();
            sb.append('"');
            escape(name);
            sb.append("\":");
            comma = false;
            return this;
        }

        Json value(String value) {
            pre();
            if (value == null) {
                sb.append("null");
            } else {
                sb.append('"');
                escape(value);
                sb.append('"');
            }
            comma = true;
            return this;
        }

        Json value(double value) {
            pre();
            if (Double.isFinite(value)) {
                sb.append(formatDouble(value));
            } else {
                sb.append("null");
            }
            comma = true;
            return this;
        }

        Json value(long value) {
            pre();
            sb.append(value);
            comma = true;
            return this;
        }

        Json value(boolean value) {
            pre();
            sb.append(value);
            comma = true;
            return this;
        }

        Json nullValue() {
            pre();
            sb.append("null");
            comma = true;
            return this;
        }

        Json field(String name, String value) {
            return name(name).value(value);
        }

        Json field(String name, double value) {
            return name(name).value(value);
        }

        Json field(String name, long value) {
            return name(name).value(value);
        }

        Json field(String name, boolean value) {
            return name(name).value(value);
        }

        Json fieldOrNull(String name, Double value) {
            name(name);
            return value == null ? nullValue() : value(value.doubleValue());
        }

        private void pre() {
            if (comma) {
                sb.append(',');
            }
        }

        private void escape(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
