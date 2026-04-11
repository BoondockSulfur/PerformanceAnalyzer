package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Analyzes performance drops and identifies potential causes.
 * Tracks TPS/MSPT drops and collects detailed information about server state.
 */
public class PerformanceDropAnalyzer {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final Queue<PerformanceDrop> recentDrops = new ConcurrentLinkedQueue<>();
    private static final int MAX_STORED_DROPS = 20;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Track previous values to detect drops (volatile for thread-safety)
    private volatile double lastTps = 20.0;
    private volatile double lastMspt = 0.0;
    private volatile long lastCheckTime = System.currentTimeMillis();

    // Lag source analyzers
    private PlayerActivityTracker playerActivityTracker;
    private PluginTimingsAnalyzer pluginTimingsAnalyzer;

    public PerformanceDropAnalyzer(Plugin plugin, PluginConfig config, DatabaseManager database) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
    }

    /**
     * Set player activity tracker for lag source analysis.
     */
    public void setPlayerActivityTracker(PlayerActivityTracker tracker) {
        this.playerActivityTracker = tracker;
    }

    /**
     * Set plugin timings analyzer for lag source analysis.
     */
    public void setPluginTimingsAnalyzer(PluginTimingsAnalyzer analyzer) {
        this.pluginTimingsAnalyzer = analyzer;
    }

    /**
     * Check for performance drop and analyze if detected.
     * Should be called periodically (e.g., every second).
     *
     * @param currentTps Current TPS value
     * @param currentMspt Current MSPT value
     */
    public void checkForDrop(double currentTps, double currentMspt) {
        long now = System.currentTimeMillis();
        long timeSinceLastCheck = now - lastCheckTime;

        // Only check if enough time has passed (at least 1 second)
        if (timeSinceLastCheck < 1000) {
            return;
        }

        // Detect TPS drop (more than 2 TPS below threshold or sudden drop of 5+ TPS)
        double tpsDropThreshold = config.tpsDropThreshold();
        boolean significantDrop = (currentTps < tpsDropThreshold - 2) || (lastTps - currentTps >= 5.0);

        // Detect MSPT spike (more than 20ms above threshold or sudden spike of 30ms+)
        double msptThreshold = config.thresholdMspt();
        boolean significantSpike = (currentMspt > msptThreshold + 20) || (currentMspt - lastMspt >= 30.0);

        if (significantDrop || significantSpike) {
            // Performance drop detected - analyze it
            analyzeAndRecordDrop(currentTps, currentMspt, lastTps, lastMspt);
        }

        // Update tracking values
        lastTps = currentTps;
        lastMspt = currentMspt;
        lastCheckTime = now;
    }

    /**
     * Analyze current server state and record the performance drop.
     */
    private void analyzeAndRecordDrop(double currentTps, double currentMspt, double previousTps, double previousMspt) {
        plugin.getLogger().warning("[PerformanceDrop] Detected! TPS: " + previousTps + " -> " + currentTps +
            ", MSPT: " + previousMspt + " -> " + currentMspt);
        plugin.getLogger().info("[PerformanceDrop] Analyzing server state...");

        // Schedule analysis on main thread (chunks/entities need main thread)
        Bukkit.getScheduler().runTask(plugin, () -> {
            analyzeAndRecordDropSync(currentTps, currentMspt, previousTps, previousMspt);
        });
    }

    /**
     * Synchronous analysis on main thread.
     */
    private void analyzeAndRecordDropSync(double currentTps, double currentMspt, double previousTps, double previousMspt) {
        // PERFORMANCE SELF-MONITORING
        long analysisStart = System.currentTimeMillis();

        // Collect server state information
        Map<String, Object> analysisData = collectServerState();

        // Measure own performance impact
        long analysisTime = System.currentTimeMillis() - analysisStart;
        if (analysisTime > 1000) { // Warn if analysis takes >1 second
            plugin.getLogger().warning("[PerformanceDrop] Analysis took " + analysisTime + "ms - this may impact server performance!");
            plugin.getLogger().warning("[PerformanceDrop] Consider:");
            plugin.getLogger().warning("[PerformanceDrop]   - Increasing lag_analysis.chunk_analysis_timeout_ms");
            plugin.getLogger().warning("[PerformanceDrop]   - Disabling lag_analysis.player_tracking if you have many players");
        } else if (config.debugMode()) {
            plugin.getLogger().info("[PerformanceDrop] Analysis completed in " + analysisTime + "ms");
        }

        // Debug output
        if (analysisData.containsKey("totalRedstoneComponents")) {
            plugin.getLogger().info("[PerformanceDrop] Total Redstone: " + analysisData.get("totalRedstoneComponents"));
        }
        if (analysisData.containsKey("totalTileEntities")) {
            plugin.getLogger().info("[PerformanceDrop] Total TileEntities: " + analysisData.get("totalTileEntities"));
        }
        if (analysisData.containsKey("problematicChunks")) {
            @SuppressWarnings("unchecked")
            List<String> chunks = (List<String>) analysisData.get("problematicChunks");
            plugin.getLogger().info("[PerformanceDrop] Problematic Chunks found: " + chunks.size());
            for (String chunk : chunks) {
                plugin.getLogger().info("[PerformanceDrop]   - " + chunk);
            }
        }

        // Create drop record
        PerformanceDrop drop = new PerformanceDrop(
            LocalDateTime.now(),
            currentTps,
            currentMspt,
            previousTps,
            previousMspt,
            analysisData
        );

        // Store in memory
        recentDrops.offer(drop);
        while (recentDrops.size() > MAX_STORED_DROPS) {
            recentDrops.poll(); // Remove oldest
        }

        // Log to database
        if (database != null) {
            String summary = generateDropSummary(drop);
            database.logAsync("performance_drop", currentTps, summary);
        }

        // Notify in debug mode
        if (config.debugMode()) {
            plugin.getLogger().warning("[PerformanceDrop] " + generateDropSummary(drop));
        }
    }

    /**
     * Collect detailed information about current server state.
     */
    private Map<String, Object> collectServerState() {
        Map<String, Object> data = new HashMap<>();

        try {
            // World information
            List<Map<String, Object>> worldData = new ArrayList<>();
            String problematicWorld = null;
            int highestProblematicScore = 0;
            int totalTileEntities = 0;
            int totalRedstoneComponents = 0;
            List<String> problematicChunks = new ArrayList<>();

            // Timeout for chunk analysis to prevent performance issues
            long chunkAnalysisStart = System.currentTimeMillis();
            int timeoutMs = config.chunkAnalysisTimeoutMs();
            boolean timeoutReached = false;

            for (World world : Bukkit.getWorlds()) {
                // Check timeout
                if (System.currentTimeMillis() - chunkAnalysisStart > timeoutMs) {
                    plugin.getLogger().warning("[PerformanceDrop] Chunk analysis timeout reached (" + timeoutMs + "ms) - skipping remaining worlds");
                    timeoutReached = true;
                    break;
                }

                Map<String, Object> worldInfo = new HashMap<>();
                worldInfo.put("name", world.getName());
                int loadedChunks = world.getLoadedChunks().length;
                int entities = world.getEntities().size();

                worldInfo.put("loadedChunks", loadedChunks);
                worldInfo.put("entities", entities);

                // Calculate entity density (entities per chunk)
                double entityDensity = loadedChunks > 0 ? (double) entities / loadedChunks : 0;
                worldInfo.put("entityDensity", entityDensity);

                // Analyze chunks (with timeout protection)
                int tileEntityCount = 0;
                int redstoneCount = 0;
                Chunk[] chunks = world.getLoadedChunks();
                List<ChunkData> chunkDataList = new ArrayList<>();

                plugin.getLogger().info("[PerformanceDrop] Analyzing " + chunks.length + " chunks in world " + world.getName() + " (async)");

                // Create async analysis tasks for all chunks
                List<CompletableFuture<ChunkAnalysis>> analysisFutures = new ArrayList<>();
                for (Chunk chunk : chunks) {
                    analysisFutures.add(analyzeChunkAsync(chunk));
                }

                // Combine all futures and wait with timeout
                CompletableFuture<List<ChunkAnalysis>> allAnalyses = CompletableFuture.allOf(
                    analysisFutures.toArray(new CompletableFuture[0])
                ).thenApply(v -> analysisFutures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList())
                );

                List<ChunkAnalysis> analyses;
                try {
                    // Wait for all analyses with timeout
                    analyses = allAnalyses.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    plugin.getLogger().warning("[PerformanceDrop] Chunk analysis timeout or error: " + e.getMessage());
                    timeoutReached = true;
                    break; // Skip to next world
                }

                int analyzedChunks = 0;
                for (ChunkAnalysis analysis : analyses) {
                    if (analyzedChunks >= chunks.length) break;
                    Chunk chunk = chunks[analyzedChunks];
                    analyzedChunks++;

                    tileEntityCount += analysis.tileEntities;
                    redstoneCount += analysis.redstoneComponents;

                    // Debug: Log chunks with redstone
                    if (analysis.redstoneComponents > 20 || analysis.tileEntities > 5) {
                        plugin.getLogger().info("[PerformanceDrop] Chunk [" + chunk.getX() + "," + chunk.getZ() +
                            "]: " + analysis.tileEntities + " TileEntities, " + analysis.redstoneComponents + " Redstone (estimated)");
                    }

                    // Store chunk data for sorting
                    if (analysis.tileEntities > 0 || analysis.redstoneComponents > 0) {
                        chunkDataList.add(new ChunkData(
                            world.getName(),
                            chunk.getX(),
                            chunk.getZ(),
                            analysis.tileEntities,
                            analysis.redstoneComponents
                        ));
                    }
                }

                plugin.getLogger().info("[PerformanceDrop] World " + world.getName() +
                    " total: " + tileEntityCount + " TileEntities, " + redstoneCount + " Redstone (estimated)");

                // Sort chunks by problematic-ness (combined score)
                chunkDataList.sort((a, b) -> {
                    int scoreA = a.tileEntities * 2 + a.redstoneComponents;
                    int scoreB = b.tileEntities * 2 + b.redstoneComponents;
                    return Integer.compare(scoreB, scoreA);
                });

                // Track top 10 most problematic chunks (configurable thresholds)
                int added = 0;
                int tileEntityThreshold = config.chunkTileEntitiesThreshold();
                int redstoneThreshold = config.chunkRedstoneThreshold();

                for (ChunkData chunkData : chunkDataList) {
                    if (added >= 10) break;

                    // Check against configured thresholds
                    if (chunkData.tileEntities > tileEntityThreshold || chunkData.redstoneComponents > redstoneThreshold) {
                        String chunkInfo = String.format("%s [%d,%d]: %d TileEntities, ~%d Redstone (estimated)",
                            chunkData.worldName, chunkData.x, chunkData.z,
                            chunkData.tileEntities, chunkData.redstoneComponents);
                        problematicChunks.add(chunkInfo);
                        plugin.getLogger().info("[PerformanceDrop] Added to problematic list: " + chunkInfo);
                        added++;
                    }
                }

                if (added == 0 && !chunkDataList.isEmpty()) {
                    plugin.getLogger().warning("[PerformanceDrop] No chunks exceeded threshold. Top chunk: " +
                        chunkDataList.get(0).worldName + " [" + chunkDataList.get(0).x + "," + chunkDataList.get(0).z +
                        "]: " + chunkDataList.get(0).tileEntities + " TileEntities, " +
                        chunkDataList.get(0).redstoneComponents + " Redstone");
                }

                worldInfo.put("tileEntities", tileEntityCount);
                worldInfo.put("redstoneComponents", redstoneCount);
                totalTileEntities += tileEntityCount;
                totalRedstoneComponents += redstoneCount;

                // Calculate problematic score for this world
                // Redstone is weighted heavily (x5), TileEntities (x3), Entities (x1)
                int worldScore = (redstoneCount / 100 * 5) + (tileEntityCount / 10 * 3) + (entities / 100);
                worldInfo.put("problematicScore", worldScore);

                plugin.getLogger().info("[PerformanceDrop] World " + world.getName() + " score: " + worldScore +
                    " (Redstone: " + redstoneCount + ", TileEntities: " + tileEntityCount + ", Entities: " + entities + ")");

                // Track most problematic world (highest combined score)
                if (worldScore > highestProblematicScore) {
                    highestProblematicScore = worldScore;
                    problematicWorld = world.getName();
                    plugin.getLogger().info("[PerformanceDrop] New most problematic world: " + problematicWorld + " (score: " + worldScore + ")");
                }

                // Entity breakdown by type
                Map<EntityType, Long> entityCounts = world.getEntities().stream()
                    .filter(e -> !(e instanceof Player))
                    .collect(Collectors.groupingBy(Entity::getType, Collectors.counting()));

                // Get top 5 entity types
                List<String> topEntities = entityCounts.entrySet().stream()
                    .sorted(Map.Entry.<EntityType, Long>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> e.getKey().name() + ":" + e.getValue())
                    .collect(Collectors.toList());

                worldInfo.put("topEntities", topEntities);

                // Find most loaded chunk in this world (approximate - we can't easily get exact entity counts per chunk without iteration)
                if (loadedChunks > 0) {
                    worldInfo.put("avgEntitiesPerChunk", String.format("%.1f", entityDensity));
                }

                worldData.add(worldInfo);
            }
            data.put("worlds", worldData);
            data.put("totalTileEntities", totalTileEntities);
            data.put("totalRedstoneComponents", totalRedstoneComponents);

            if (timeoutReached) {
                data.put("analysisIncomplete", true);
                plugin.getLogger().warning("[PerformanceDrop] Chunk analysis incomplete due to timeout - consider increasing lag_analysis.chunk_analysis_timeout_ms");
            }

            if (!problematicChunks.isEmpty()) {
                data.put("problematicChunks", problematicChunks);
            }

            // Store problematic world with details
            if (problematicWorld != null) {
                data.put("problematicWorld", problematicWorld);
                data.put("problematicWorldScore", highestProblematicScore);

                // Find the world data for the problematic world
                for (Map<String, Object> worldInfo : worldData) {
                    if (worldInfo.get("name").equals(problematicWorld)) {
                        data.put("problematicWorldEntities", worldInfo.get("entities"));
                        data.put("problematicWorldTileEntities", worldInfo.get("tileEntities"));
                        data.put("problematicWorldRedstone", worldInfo.get("redstoneComponents"));
                        double density = worldInfo.containsKey("entityDensity") ? (double) worldInfo.get("entityDensity") : 0.0;
                        data.put("problematicWorldDensity", String.format("%.1f", density));
                        break;
                    }
                }
            }

            // Player information
            List<? extends Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            data.put("playerCount", players.size());

            List<String> playerLocations = players.stream()
                .map(p -> {
                    String worldName = p.getWorld() != null ? p.getWorld().getName() : "unknown";
                    Location loc = p.getLocation();
                    if (loc != null) {
                        return p.getName() + "@" + worldName +
                            "[" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + "]";
                    }
                    return p.getName() + "@" + worldName + "[unknown]";
                })
                .collect(Collectors.toList());
            data.put("playerLocations", playerLocations);

            // Total chunks and entities
            int totalChunks = Bukkit.getWorlds().stream()
                .mapToInt(w -> w.getLoadedChunks().length)
                .sum();
            int totalEntities = Bukkit.getWorlds().stream()
                .mapToInt(w -> w.getEntities().size())
                .sum();

            data.put("totalChunks", totalChunks);
            data.put("totalEntities", totalEntities);

            // Memory info
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            long maxMemory = runtime.maxMemory() / 1024 / 1024;
            data.put("usedMemoryMB", usedMemory);
            data.put("maxMemoryMB", maxMemory);
            data.put("memoryUsagePercent", (usedMemory * 100.0) / maxMemory);

            // LAG SOURCE ANALYSIS: Player Activity
            if (playerActivityTracker != null) {
                List<PlayerActivityTracker.PlayerActivitySnapshot> topPlayers =
                    playerActivityTracker.getTopActivePlayers(5);

                if (!topPlayers.isEmpty()) {
                    List<String> playerActivity = new ArrayList<>();
                    for (PlayerActivityTracker.PlayerActivitySnapshot snap : topPlayers) {
                        playerActivity.add(snap.playerName + ": " + snap.getActivitySummary());
                    }
                    data.put("topActivePlayers", playerActivity);
                    plugin.getLogger().info("[PerformanceDrop] Top active players: " + playerActivity.size());
                }
            }

            // LAG SOURCE ANALYSIS: Plugin Timings
            if (pluginTimingsAnalyzer != null) {
                List<PluginTimingsAnalyzer.PluginPerformanceSnapshot> topPlugins =
                    pluginTimingsAnalyzer.getTopLagSuspects(5);

                if (!topPlugins.isEmpty()) {
                    List<String> pluginData = new ArrayList<>();
                    for (PluginTimingsAnalyzer.PluginPerformanceSnapshot snap : topPlugins) {
                        pluginData.add(snap.getSummary());
                    }
                    data.put("suspectPlugins", pluginData);
                    plugin.getLogger().info("[PerformanceDrop] Suspect plugins: " + pluginData.size());
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("[PerformanceDrop] Error collecting server state: " + e.getMessage());
        }

        return data;
    }

    /**
     * Analyze a chunk for tile entities and redstone components using ChunkSnapshot.
     * Uses async processing to reduce main thread load.
     */
    private CompletableFuture<ChunkAnalysis> analyzeChunkAsync(Chunk chunk) {
        // Get tile entity count on main thread (fast operation)
        int tileEntities = chunk.getTileEntities().length;

        // Create chunk snapshot for async processing
        org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        int minHeight = chunk.getWorld().getMinHeight();
        int maxHeight = chunk.getWorld().getMaxHeight();

        // Process snapshot asynchronously
        return CompletableFuture.supplyAsync(() -> {
            int redstoneComponents = 0;

            try {
                // For chunks with many tile entities, do a more thorough scan
                boolean thorough = tileEntities > 10;
                int step = thorough ? 2 : 4; // Sample every 2nd or 4th block

                // Sample blocks for redstone using snapshot
                for (int x = 0; x < 16; x += step) {
                    for (int z = 0; z < 16; z += step) {
                        for (int y = minHeight; y < maxHeight; y += step) {
                            Material type = snapshot.getBlockType(x, y, z);

                            // Check for redstone components
                            if (isRedstoneComponent(type)) {
                                redstoneComponents++;
                            }
                        }
                    }
                }

                // Extrapolate redstone count based on sampling rate
                if (thorough) {
                    redstoneComponents *= 8;  // Sampled every 2nd block = 1/8th
                } else {
                    redstoneComponents *= 64; // Sampled every 4th block = 1/64th
                }

            } catch (Exception e) {
                // Snapshot might cause an error, skip it
            }

            return new ChunkAnalysis(tileEntities, redstoneComponents);
        });
    }

    /**
     * Legacy synchronous chunk analysis (fallback).
     */
    private ChunkAnalysis analyzeChunk(Chunk chunk) {
        int tileEntities = 0;
        int redstoneComponents = 0;

        try {
            // Count tile entities (accurate)
            BlockState[] tileEntityStates = chunk.getTileEntities();
            tileEntities = tileEntityStates.length;

            // For chunks with many tile entities, do a more thorough scan
            boolean thorough = tileEntities > 10;
            int step = thorough ? 2 : 4; // Sample every 2nd or 4th block

            // Sample blocks for redstone
            for (int x = 0; x < 16; x += step) {
                for (int z = 0; z < 16; z += step) {
                    for (int y = chunk.getWorld().getMinHeight(); y < chunk.getWorld().getMaxHeight(); y += step) {
                        Block block = chunk.getBlock(x, y, z);
                        Material type = block.getType();

                        // Check for redstone components
                        if (isRedstoneComponent(type)) {
                            redstoneComponents++;
                        }
                    }
                }
            }

            // Extrapolate redstone count based on sampling rate
            if (thorough) {
                redstoneComponents *= 8;  // Sampled every 2nd block = 1/8th
            } else {
                redstoneComponents *= 64; // Sampled every 4th block = 1/64th
            }

        } catch (Exception e) {
            // Chunk might be unloaded or cause an error, skip it
        }

        return new ChunkAnalysis(tileEntities, redstoneComponents);
    }

    /**
     * Check if a material is a redstone component.
     */
    private boolean isRedstoneComponent(Material type) {
        return type == Material.REDSTONE_WIRE ||
               type == Material.REPEATER ||
               type == Material.COMPARATOR ||
               type == Material.REDSTONE_TORCH ||
               type == Material.REDSTONE_WALL_TORCH ||
               type == Material.REDSTONE_BLOCK ||
               type == Material.OBSERVER ||
               type == Material.PISTON ||
               type == Material.STICKY_PISTON ||
               type == Material.DISPENSER ||
               type == Material.DROPPER ||
               type == Material.HOPPER ||
               type == Material.LEVER ||
               type == Material.STONE_BUTTON ||
               type == Material.OAK_BUTTON ||
               type == Material.SPRUCE_BUTTON ||
               type == Material.BIRCH_BUTTON ||
               type == Material.JUNGLE_BUTTON ||
               type == Material.ACACIA_BUTTON ||
               type == Material.DARK_OAK_BUTTON ||
               type == Material.CRIMSON_BUTTON ||
               type == Material.WARPED_BUTTON ||
               type == Material.STONE_PRESSURE_PLATE ||
               type == Material.OAK_PRESSURE_PLATE ||
               type == Material.SPRUCE_PRESSURE_PLATE ||
               type == Material.BIRCH_PRESSURE_PLATE ||
               type == Material.JUNGLE_PRESSURE_PLATE ||
               type == Material.ACACIA_PRESSURE_PLATE ||
               type == Material.DARK_OAK_PRESSURE_PLATE ||
               type == Material.CRIMSON_PRESSURE_PLATE ||
               type == Material.WARPED_PRESSURE_PLATE ||
               type == Material.LIGHT_WEIGHTED_PRESSURE_PLATE ||
               type == Material.HEAVY_WEIGHTED_PRESSURE_PLATE ||
               type == Material.TRIPWIRE ||
               type == Material.TRIPWIRE_HOOK ||
               type == Material.TARGET ||
               type == Material.LECTERN ||
               type == Material.DAYLIGHT_DETECTOR;
    }

    /**
     * Simple data class for chunk analysis results.
     */
    private static class ChunkAnalysis {
        final int tileEntities;
        final int redstoneComponents;

        ChunkAnalysis(int tileEntities, int redstoneComponents) {
            this.tileEntities = tileEntities;
            this.redstoneComponents = redstoneComponents;
        }
    }

    /**
     * Data class for storing chunk information for sorting.
     */
    private static class ChunkData {
        final String worldName;
        final int x;
        final int z;
        final int tileEntities;
        final int redstoneComponents;

        ChunkData(String worldName, int x, int z, int tileEntities, int redstoneComponents) {
            this.worldName = worldName;
            this.x = x;
            this.z = z;
            this.tileEntities = tileEntities;
            this.redstoneComponents = redstoneComponents;
        }
    }

    /**
     * Generate a human-readable summary of a performance drop.
     */
    private String generateDropSummary(PerformanceDrop drop) {
        StringBuilder sb = new StringBuilder();
        sb.append("Performance Drop @ ").append(drop.timestamp.format(TIME_FORMAT)).append(" | ");
        double tpsDiff = drop.lowestTps - drop.previousTps;
        double msptDiff = drop.highestMspt - drop.previousMspt;
        sb.append(String.format("TPS: %.1f -> %.1f (%+.1f) | ", drop.previousTps, drop.lowestTps, tpsDiff));
        sb.append(String.format("MSPT: %.1f -> %.1f (%+.1f) | ", drop.previousMspt, drop.highestMspt, msptDiff));

        // Add key metrics
        Map<String, Object> data = drop.analysisData;
        if (data.containsKey("totalChunks")) {
            sb.append("Chunks: ").append(data.get("totalChunks")).append(" | ");
        }
        if (data.containsKey("totalEntities")) {
            sb.append("Entities: ").append(data.get("totalEntities")).append(" | ");
        }
        if (data.containsKey("playerCount")) {
            sb.append("Players: ").append(data.get("playerCount"));
        }
        if (data.containsKey("problematicWorld")) {
            sb.append(" | Problematic World: ").append(data.get("problematicWorld"));
            sb.append(" (").append(data.get("problematicWorldEntities")).append(" entities)");
        }

        return sb.toString();
    }

    /**
     * Get recent performance drops for display.
     */
    public List<PerformanceDrop> getRecentDrops() {
        return new ArrayList<>(recentDrops);
    }

    /**
     * Clear all recorded performance drops.
     */
    public void clearDrops() {
        recentDrops.clear();
        if (config.debugMode()) {
            plugin.getLogger().info("[PerformanceDrop] Cache cleared");
        }
    }

    /**
     * Get a detailed report of a specific drop.
     */
    public String getDetailedReport(PerformanceDrop drop) {
        StringBuilder sb = new StringBuilder();
        sb.append("§6§l═══════════════════════════════════════════\n");
        sb.append("§e§lPerformance Drop Report\n");
        sb.append("§6§l═══════════════════════════════════════════\n\n");

        sb.append("§7Zeitpunkt: §f").append(drop.timestamp.format(TIME_FORMAT)).append("\n\n");

        sb.append("§c§lPerformance Metrics:\n");
        double tpsDiff = drop.lowestTps - drop.previousTps;
        double msptDiff = drop.highestMspt - drop.previousMspt;

        sb.append("§7TPS: §f").append(String.format("%.2f", drop.previousTps))
            .append(" §c→ §c§l").append(String.format("%.2f", drop.lowestTps))
            .append(" §7(§c").append(String.format("%+.2f", tpsDiff)).append("§7)\n");
        sb.append("§7MSPT: §f").append(String.format("%.2f", drop.previousMspt))
            .append(" §c→ §c§l").append(String.format("%.2f", drop.highestMspt))
            .append("ms §7(§c").append(String.format("%+.2f", msptDiff)).append("ms§7)\n\n");

        Map<String, Object> data = drop.analysisData;

        // Problematic World Highlight
        if (data.containsKey("problematicWorld")) {
            sb.append("§c§l⚠ Problematischste Welt:\n");
            sb.append("§7Welt: §c§l").append(data.get("problematicWorld")).append("\n");
            sb.append("§7Entities: §f").append(data.get("problematicWorldEntities"));
            sb.append(" §7(Dichte: §f").append(data.get("problematicWorldDensity")).append(" §7pro Chunk)\n");

            if (data.containsKey("problematicWorldTileEntities")) {
                sb.append("§7TileEntities: §f").append(data.get("problematicWorldTileEntities")).append("\n");
            }
            if (data.containsKey("problematicWorldRedstone")) {
                int redstone = (int) data.get("problematicWorldRedstone");
                sb.append("§7Redstone: §c§l").append(redstone).append(" §7(estimated)\n");
            }
            sb.append("\n");
        }

        sb.append("§a§lServer State:\n");
        if (data.containsKey("totalChunks")) {
            sb.append("§7Total Loaded Chunks: §f").append(data.get("totalChunks")).append("\n");
        }
        if (data.containsKey("totalEntities")) {
            sb.append("§7Total Entities: §f").append(data.get("totalEntities")).append("\n");
        }
        if (data.containsKey("totalTileEntities")) {
            int tileEntities = (int) data.get("totalTileEntities");
            String tileColor = tileEntities > 5000 ? "§c" : "§f";
            sb.append("§7Total Tile Entities: ").append(tileColor).append(tileEntities).append("\n");
        }
        if (data.containsKey("totalRedstoneComponents")) {
            int redstone = (int) data.get("totalRedstoneComponents");
            String redstoneColor = redstone > 10000 ? "§c" : "§f";
            sb.append("§7Total Redstone Components: ").append(redstoneColor).append(redstone).append("\n");
        }
        if (data.containsKey("playerCount")) {
            sb.append("§7Online Players: §f").append(data.get("playerCount")).append("\n");
        }
        if (data.containsKey("memoryUsagePercent")) {
            double memPercent = (double) data.get("memoryUsagePercent");
            String memColor = memPercent > 80 ? "§c" : "§f";
            sb.append("§7Memory Usage: ").append(memColor).append(String.format("%.1f%%", memPercent))
                .append(" §7(§f").append(data.get("usedMemoryMB")).append("MB / ")
                .append(data.get("maxMemoryMB")).append("MB§7)\n");
        }

        // Problematic Chunks
        if (data.containsKey("problematicChunks")) {
            @SuppressWarnings("unchecked")
            List<String> problematicChunks = (List<String>) data.get("problematicChunks");
            if (!problematicChunks.isEmpty()) {
                sb.append("\n§c§l⚠ Problematische Chunks:\n");
                for (String chunkInfo : problematicChunks) {
                    sb.append("§7- §c").append(chunkInfo).append("\n");
                }
            }
        }

        // World details
        if (data.containsKey("worlds")) {
            sb.append("\n§b§lWorld Details:\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> worlds = (List<Map<String, Object>>) data.get("worlds");
            String problematicWorld = (String) data.get("problematicWorld");

            for (Map<String, Object> world : worlds) {
                String worldName = (String) world.get("name");
                boolean isProblematic = worldName.equals(problematicWorld);

                sb.append(isProblematic ? "§c§l- " : "§7- ");
                sb.append("§e").append(worldName).append("§7: ");
                sb.append("§f").append(world.get("loadedChunks")).append(" §7chunks, ");
                sb.append("§f").append(world.get("entities")).append(" §7entities");

                if (world.containsKey("avgEntitiesPerChunk")) {
                    sb.append(" §7(Ø §f").append(world.get("avgEntitiesPerChunk")).append("§7/chunk)");
                }
                sb.append("\n");

                // Tile Entities and Redstone info
                if (world.containsKey("tileEntities") && world.containsKey("redstoneComponents")) {
                    int tileEntities = (int) world.get("tileEntities");
                    int redstone = (int) world.get("redstoneComponents");
                    if (tileEntities > 0 || redstone > 0) {
                        sb.append("  §7TileEntities: §f").append(tileEntities);
                        sb.append(" §7| Redstone: §f").append(redstone).append("\n");
                    }
                }

                if (world.containsKey("topEntities")) {
                    @SuppressWarnings("unchecked")
                    List<String> topEntities = (List<String>) world.get("topEntities");
                    if (!topEntities.isEmpty()) {
                        sb.append("  §7Top Entities: §f").append(String.join("§7, §f", topEntities)).append("\n");
                    }
                }
            }
        }

        // Player locations
        if (data.containsKey("playerLocations")) {
            @SuppressWarnings("unchecked")
            List<String> playerLocs = (List<String>) data.get("playerLocations");
            if (!playerLocs.isEmpty()) {
                sb.append("\n§d§lPlayer Locations:\n");
                for (String loc : playerLocs) {
                    sb.append("§7- §f").append(loc).append("\n");
                }
            }
        }

        // LAG SOURCE ANALYSIS
        sb.append("\n§c§l⚠ Lag-Ursachen-Analyse:\n");
        boolean foundLagSource = false;

        // Player Activity Analysis
        if (data.containsKey("topActivePlayers")) {
            @SuppressWarnings("unchecked")
            List<String> activePlayers = (List<String>) data.get("topActivePlayers");
            if (!activePlayers.isEmpty()) {
                sb.append("§e§lVerdächtige Spieler (hohe Aktivität):\n");
                for (String playerInfo : activePlayers) {
                    sb.append("  §c• §f").append(playerInfo).append("\n");
                }
                foundLagSource = true;
            }
        }

        // Plugin Analysis
        if (data.containsKey("suspectPlugins")) {
            @SuppressWarnings("unchecked")
            List<String> suspectPlugins = (List<String>) data.get("suspectPlugins");
            if (!suspectPlugins.isEmpty()) {
                sb.append("§e§lVerdächtige Plugins:\n");
                for (String pluginInfo : suspectPlugins) {
                    sb.append("  §c• §f").append(pluginInfo).append("\n");
                }
                foundLagSource = true;
            }
        }

        if (foundLagSource) {
            sb.append("\n");
        }

        // Possible causes analysis
        sb.append("§e§lWelt-basierte Probleme:\n");
        boolean foundCause = false;

        // Check if specific chunks are problematic
        if (data.containsKey("problematicChunks")) {
            @SuppressWarnings("unchecked")
            List<String> problematicChunksList = (List<String>) data.get("problematicChunks");
            if (!problematicChunksList.isEmpty()) {
                sb.append("§c✗ §7Problematische Chunks gefunden (§c").append(problematicChunksList.size()).append("§7)\n");
                sb.append("  §7→ Siehe Liste oben für genaue Koordinaten\n");
                foundCause = true;
            }
        }

        if (data.containsKey("totalTileEntities")) {
            int tileEntities = (int) data.get("totalTileEntities");
            if (tileEntities > 3000) {
                String severity = tileEntities > 5000 ? "§c✗ Sehr viele" : "§e⚠ Viele";
                sb.append(severity).append(" §7Tile Entities (§c").append(tileEntities).append("§7)\n");
                sb.append("  §7→ Hoppers, Furnaces, Chests können Lag verursachen\n");
                foundCause = true;
            }
        }

        if (data.containsKey("totalRedstoneComponents")) {
            int redstone = (int) data.get("totalRedstoneComponents");
            if (redstone > 5000) {
                String severity = redstone > 10000 ? "§c✗ Sehr viele" : "§e⚠ Viele";
                sb.append(severity).append(" §7Redstone-Komponenten (§c").append(redstone).append("§7)\n");
                sb.append("  §7→ Komplexe Redstone-Technik/Lagmaschinen\n");
                foundCause = true;
            }
        }

        if (data.containsKey("totalEntities")) {
            int entities = (int) data.get("totalEntities");
            if (entities > 3000) {
                String severity = entities > 5000 ? "§c✗ Sehr viele" : "§e⚠ Viele";
                sb.append(severity).append(" §7Entities (§c").append(entities).append("§7)\n");
                sb.append("  §7→ Mob-Farms oder Item-Accumulation\n");
                foundCause = true;
            }
        }

        if (data.containsKey("memoryUsagePercent")) {
            double memPercent = (double) data.get("memoryUsagePercent");
            if (memPercent > 90) {
                sb.append("§c✗ §7Kritischer Speicher (§c").append(String.format("%.1f%%", memPercent)).append("§7)\n");
                sb.append("  §7→ Erhöhe den Java Heap mit -Xmx\n");
                foundCause = true;
            }
        }

        if (!foundCause) {
            sb.append("§e⚠ §7Keine offensichtliche Welt-basierte Ursache gefunden\n");
            sb.append("§7Mögliche Ursachen:\n");
            sb.append("  §7• Plugin-bezogene Probleme (verwende §f/spark profiler§7)\n");
            sb.append("  §7• Datenbank-Operationen\n");
            sb.append("  §7• Disk I/O (chunk loading/saving)\n");
            sb.append("  §7• Garbage Collection\n");
            sb.append("  §7• Netzwerk-Issues\n");
            sb.append("\n§7§lEmpfehlung: §fInstalliere §espark§f für detaillierte Plugin-Analyse\n");
            sb.append("§7Download: §fhttps://spark.lucko.me/\n");
        }

        sb.append("\n§6§l═══════════════════════════════════════════\n");

        return sb.toString();
    }

    /**
     * Data class representing a performance drop event.
     */
    public static class PerformanceDrop {
        public final LocalDateTime timestamp;
        public final double currentTps;
        public final double currentMspt;
        public final double previousTps;
        public final double previousMspt;
        public final double lowestTps;
        public final double highestMspt;
        public final Map<String, Object> analysisData;

        public PerformanceDrop(LocalDateTime timestamp, double currentTps, double currentMspt,
                             double previousTps, double previousMspt, Map<String, Object> analysisData) {
            this.timestamp = timestamp;
            this.currentTps = currentTps;
            this.currentMspt = currentMspt;
            this.previousTps = previousTps;
            this.previousMspt = previousMspt;
            // Current values are the worst values for now (can be improved to track actual min/max)
            this.lowestTps = currentTps;
            this.highestMspt = currentMspt;
            this.analysisData = analysisData;
        }

        public String getShortSummary() {
            String hotspot = "";
            if (analysisData.containsKey("problematicWorld")) {
                hotspot = " §7| §c⚠ §f" + analysisData.get("problematicWorld");
            }

            return String.format("§7[§f%s§7] TPS: §c%.1f §7MSPT: §c%.1fms §7Chunks: §f%s §7Entities: §f%s%s",
                timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                lowestTps,
                highestMspt,
                analysisData.getOrDefault("totalChunks", "?"),
                analysisData.getOrDefault("totalEntities", "?"),
                hotspot
            );
        }
    }
}
