package dev.boondock.performanceanalyzer.analysis;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Analyzes plugin performance by monitoring event handler execution times.
 * Uses reflection to access timing data where available.
 */
public class PluginTimingsAnalyzer {

    private final Plugin plugin;

    // Track event execution times per plugin
    private final Map<String, PluginTimingData> pluginTimings = new ConcurrentHashMap<>();

    public PluginTimingsAnalyzer(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Analyze all plugins and their approximate performance impact.
     * This is a snapshot based on registered listeners and tasks.
     */
    public List<PluginPerformanceSnapshot> analyzePlugins() {
        List<PluginPerformanceSnapshot> snapshots = new ArrayList<>();

        for (Plugin pl : Bukkit.getPluginManager().getPlugins()) {
            if (!pl.isEnabled()) continue;

            PluginPerformanceSnapshot snapshot = new PluginPerformanceSnapshot(
                pl.getName(),
                pl.getDescription().getVersion(),
                countEventListeners(pl),
                countScheduledTasks(pl),
                hasWorldEditOrFAWE(pl)
            );

            snapshots.add(snapshot);
        }

        // Sort by "risk score" (more listeners + tasks = higher risk)
        snapshots.sort((a, b) -> Integer.compare(b.getRiskScore(), a.getRiskScore()));

        return snapshots;
    }

    /**
     * Get top N plugins most likely to cause lag.
     */
    public List<PluginPerformanceSnapshot> getTopLagSuspects(int limit) {
        List<PluginPerformanceSnapshot> all = analyzePlugins();
        return all.subList(0, Math.min(limit, all.size()));
    }

    /**
     * Count registered event listeners for a plugin.
     */
    private int countEventListeners(Plugin plugin) {
        int count = 0;

        try {
            // Get all handler lists via reflection
            for (Method method : HandlerList.class.getDeclaredMethods()) {
                if (method.getName().equals("getHandlerLists") && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    ArrayList<HandlerList> handlerLists = (ArrayList<HandlerList>) method.invoke(null);

                    for (HandlerList handlerList : handlerLists) {
                        for (RegisteredListener listener : handlerList.getRegisteredListeners()) {
                            if (listener.getPlugin().equals(plugin)) {
                                count++;
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            // Fallback: estimate based on common events
            plugin.getLogger().fine("Could not count listeners for " + plugin.getName() + ": " + e.getMessage());
        }

        return count;
    }

    /**
     * Count scheduled tasks (sync + async) for a plugin.
     */
    private int countScheduledTasks(Plugin plugin) {
        return (int) Bukkit.getScheduler().getPendingTasks().stream()
            .filter(task -> task.getOwner().equals(plugin))
            .count();
    }

    /**
     * Check if plugin is a known heavy plugin (WorldEdit, FAWE, etc.).
     */
    private boolean hasWorldEditOrFAWE(Plugin plugin) {
        String name = plugin.getName().toLowerCase();
        return name.contains("worldedit") ||
               name.contains("fawe") ||
               name.contains("asyncworldedit") ||
               name.contains("voxelsniper") ||
               name.contains("coreprotect");
    }

    /**
     * Generate a detailed report of all plugins.
     */
    public String generatePluginReport() {
        List<PluginPerformanceSnapshot> snapshots = analyzePlugins();
        StringBuilder sb = new StringBuilder();

        sb.append("§6§l════════════════════════════════════════\n");
        sb.append("§e§lPlugin Performance Analysis\n");
        sb.append("§6§l════════════════════════════════════════\n\n");

        sb.append("§7Total Plugins: §f").append(snapshots.size()).append("\n\n");

        sb.append("§c§lTop Lag Suspects:\n");
        List<PluginPerformanceSnapshot> top = getTopLagSuspects(10);

        for (int i = 0; i < top.size(); i++) {
            PluginPerformanceSnapshot snap = top.get(i);
            String color = i < 3 ? "§c" : (i < 7 ? "§e" : "§7");

            sb.append(color).append(i + 1).append(". §f").append(snap.pluginName)
              .append(" §7v").append(snap.version).append("\n");
            sb.append("   §7Listeners: §f").append(snap.eventListeners)
              .append(" §7| Tasks: §f").append(snap.scheduledTasks)
              .append(" §7| Risk Score: ").append(color).append(snap.getRiskScore());

            if (snap.isHeavyPlugin) {
                sb.append(" §c[HEAVY]");
            }
            sb.append("\n");
        }

        sb.append("\n§6§l════════════════════════════════════════\n");

        return sb.toString();
    }

    /**
     * Data class for tracking plugin timing data.
     */
    private static class PluginTimingData {
        long totalExecutionTime = 0;
        int eventCount = 0;

        void addExecution(long nanos) {
            totalExecutionTime += nanos;
            eventCount++;
        }

        double getAverageMs() {
            return eventCount > 0 ? (totalExecutionTime / 1_000_000.0) / eventCount : 0.0;
        }
    }

    /**
     * Immutable snapshot of plugin performance data.
     */
    public static class PluginPerformanceSnapshot {
        public final String pluginName;
        public final String version;
        public final int eventListeners;
        public final int scheduledTasks;
        public final boolean isHeavyPlugin;

        public PluginPerformanceSnapshot(String pluginName, String version,
                                        int eventListeners, int scheduledTasks,
                                        boolean isHeavyPlugin) {
            this.pluginName = pluginName;
            this.version = version;
            this.eventListeners = eventListeners;
            this.scheduledTasks = scheduledTasks;
            this.isHeavyPlugin = isHeavyPlugin;
        }

        /**
         * Calculate a "risk score" for this plugin.
         * Higher score = more likely to cause lag.
         */
        public int getRiskScore() {
            int score = eventListeners * 2 + scheduledTasks * 3;
            if (isHeavyPlugin) {
                score *= 2; // Double the score for known heavy plugins
            }
            return score;
        }

        public String getSummary() {
            return String.format("%s v%s - Listeners: %d, Tasks: %d, Risk: %d%s",
                pluginName, version, eventListeners, scheduledTasks, getRiskScore(),
                isHeavyPlugin ? " [HEAVY]" : "");
        }
    }
}
