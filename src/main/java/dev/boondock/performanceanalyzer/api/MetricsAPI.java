package dev.boondock.performanceanalyzer.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.analysis.WorldStatsManager;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.metrics.MemorySampler;
import dev.boondock.performanceanalyzer.metrics.TickSampler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * REST API for external monitoring tools.
 * Provides JSON endpoints for performance metrics.
 *
 * Security: Requires API key authentication.
 *
 * Endpoints:
 * - GET /api/health       - Health check (no auth)
 * - GET /api/metrics      - All performance metrics
 * - GET /api/worlds       - World statistics
 * - GET /api/trends       - Trend analysis
 */
public class MetricsAPI {

    private final Plugin plugin;
    private final PluginConfig config;
    private final TickSampler tickSampler;
    private final MemorySampler memorySampler;
    private final WorldStatsManager worldStatsManager;

    private HttpServer server;
    private String apiKey;

    public MetricsAPI(Plugin plugin, PluginConfig config, TickSampler tickSampler,
                     MemorySampler memorySampler, WorldStatsManager worldStatsManager) {
        this.plugin = plugin;
        this.config = config;
        this.tickSampler = tickSampler;
        this.memorySampler = memorySampler;
        this.worldStatsManager = worldStatsManager;
    }

    /**
     * Start the API server.
     */
    public void start() throws IOException {
        if (!config.apiEnabled()) {
            plugin.getLogger().info("[API] REST API disabled in config");
            return;
        }

        int port = config.apiPort();
        this.apiKey = config.apiKey();

        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("changeme")) {
            plugin.getLogger().severe("[API] Invalid API key! Please set a secure key in config.yml");
            plugin.getLogger().severe("[API] API server NOT started for security reasons");
            return;
        }

        // Create HTTP server
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        // Register endpoints
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/metrics", new MetricsHandler());
        server.createContext("/api/worlds", new WorldsHandler());
        server.createContext("/api/trends", new TrendsHandler());

        // Start server
        server.setExecutor(null); // Use default executor
        server.start();

        plugin.getLogger().info("[API] REST API started on port " + port);
        plugin.getLogger().info("[API] Endpoints:");
        plugin.getLogger().info("[API]   GET /api/health       (no auth)");
        plugin.getLogger().info("[API]   GET /api/metrics      (requires API key)");
        plugin.getLogger().info("[API]   GET /api/worlds       (requires API key)");
        plugin.getLogger().info("[API]   GET /api/trends       (requires API key)");
    }

    /**
     * Stop the API server.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            plugin.getLogger().info("[API] REST API stopped");
        }
    }

    /**
     * Check API key authentication.
     */
    private boolean isAuthenticated(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null) return false;

        // Support both "Bearer TOKEN" and just "TOKEN"
        String providedKey = authHeader.startsWith("Bearer ")
            ? authHeader.substring(7)
            : authHeader;

        return apiKey.equals(providedKey);
    }

    /**
     * Send JSON response.
     */
    private void sendJSON(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    /**
     * Send error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String json = String.format("{\"error\": \"%s\", \"status\": %d}", message, statusCode);
        sendJSON(exchange, statusCode, json);
    }

    // ==================== HANDLERS ====================

    /**
     * Health check endpoint (no authentication required).
     */
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            String json = String.format("""
                {
                  "status": "healthy",
                  "plugin": "PerformanceAnalyzer",
                  "version": "%s",
                  "server": "%s",
                  "players": %d,
                  "uptime": %d
                }
                """,
                plugin.getDescription().getVersion(),
                Bukkit.getVersion(),
                Bukkit.getOnlinePlayers().size(),
                System.currentTimeMillis()
            );

            sendJSON(exchange, 200, json);
        }
    }

    /**
     * Performance metrics endpoint.
     */
    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            if (!isAuthenticated(exchange)) {
                sendError(exchange, 401, "Unauthorized - Invalid API key");
                return;
            }

            // Collect metrics
            double tps = tickSampler.getTps();
            double mspt = tickSampler.getMsptAvg();
            double msptP95 = tickSampler.getMsptP95();
            double heapUsage = memorySampler.heapUsagePercent();

            long heapUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
            long heapMax = Runtime.getRuntime().maxMemory() / 1024 / 1024;

            int totalEntities = Bukkit.getWorlds().stream()
                .mapToInt(w -> w.getEntities().size())
                .sum();

            int totalChunks = Bukkit.getWorlds().stream()
                .mapToInt(w -> w.getLoadedChunks().length)
                .sum();

            String json = String.format("""
                {
                  "timestamp": %d,
                  "tps": %.2f,
                  "mspt": {
                    "avg": %.2f,
                    "p95": %.2f
                  },
                  "memory": {
                    "heap_used_mb": %d,
                    "heap_max_mb": %d,
                    "heap_usage_percent": %.2f
                  },
                  "world": {
                    "total_entities": %d,
                    "total_chunks": %d,
                    "worlds": %d
                  },
                  "players": {
                    "online": %d,
                    "max": %d
                  },
                  "using_spark": %b
                }
                """,
                System.currentTimeMillis(),
                tps,
                mspt,
                msptP95,
                heapUsed,
                heapMax,
                heapUsage,
                totalEntities,
                totalChunks,
                Bukkit.getWorlds().size(),
                Bukkit.getOnlinePlayers().size(),
                Bukkit.getMaxPlayers(),
                tickSampler.isUsingSparkData()
            );

            sendJSON(exchange, 200, json);
        }
    }

    /**
     * World statistics endpoint.
     */
    private class WorldsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            if (!isAuthenticated(exchange)) {
                sendError(exchange, 401, "Unauthorized - Invalid API key");
                return;
            }

            StringBuilder json = new StringBuilder();
            json.append("{\n  \"worlds\": [\n");

            boolean first = true;
            for (World world : Bukkit.getWorlds()) {
                WorldStatsManager.WorldStats stats = worldStatsManager.getWorldStats(world);

                if (!first) json.append(",\n");
                first = false;

                json.append("    {\n");
                json.append(String.format("      \"name\": \"%s\",\n", stats.worldName()));
                json.append(String.format("      \"environment\": \"%s\",\n", stats.getEnvironmentName()));
                json.append(String.format("      \"entities\": %d,\n", stats.entityCount()));
                json.append(String.format("      \"players\": %d,\n", stats.playerCount()));
                json.append(String.format("      \"loaded_chunks\": %d,\n", stats.loadedChunks()));
                json.append(String.format("      \"tile_entities\": %d,\n", stats.tileEntityCount()));
                json.append(String.format("      \"entity_density\": %.2f\n", stats.entityDensity()));
                json.append("    }");
            }

            json.append("\n  ]\n}");
            sendJSON(exchange, 200, json.toString());
        }
    }

    /**
     * Trend analysis endpoint.
     */
    private class TrendsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            if (!isAuthenticated(exchange)) {
                sendError(exchange, 401, "Unauthorized - Invalid API key");
                return;
            }

            // Default: 1 hour trend
            long periodMs = 3600000L;

            // Parse query parameter for custom period
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("period=")) {
                try {
                    String periodStr = query.split("period=")[1].split("&")[0];
                    periodMs = Long.parseLong(periodStr) * 1000L; // Convert seconds to ms
                } catch (Exception e) {
                    // Use default
                }
            }

            Map<String, WorldStatsManager.TrendAnalysis> trends =
                worldStatsManager.analyzeTrendsForAllWorlds(periodMs);

            if (trends.isEmpty()) {
                sendJSON(exchange, 200, "{\"trends\": [], \"message\": \"No trend data available yet\"}");
                return;
            }

            StringBuilder json = new StringBuilder();
            json.append("{\n  \"period_ms\": ").append(periodMs).append(",\n");
            json.append("  \"trends\": [\n");

            boolean first = true;
            for (WorldStatsManager.TrendAnalysis trend : trends.values()) {
                if (!first) json.append(",\n");
                first = false;

                json.append("    {\n");
                json.append(String.format("      \"world\": \"%s\",\n", trend.worldName()));
                json.append(String.format("      \"samples\": %d,\n", trend.sampleCount()));
                json.append(String.format("      \"avg_entities\": %.1f,\n", trend.avgEntityCount()));
                json.append(String.format("      \"min_entities\": %d,\n", trend.minEntityCount()));
                json.append(String.format("      \"max_entities\": %d,\n", trend.maxEntityCount()));
                json.append(String.format("      \"entity_change_rate_per_hour\": %.2f,\n", trend.entityChangeRate()));
                json.append(String.format("      \"trend_direction\": \"%s\",\n", trend.trendDirection()));
                json.append(String.format("      \"summary\": \"%s\"\n", escapeJson(trend.summary())));
                json.append("    }");
            }

            json.append("\n  ]\n}");
            sendJSON(exchange, 200, json.toString());
        }
    }

    private String escapeJson(String str) {
        return str.replace("\"", "\\\"").replace("\n", "\\n");
    }
}
