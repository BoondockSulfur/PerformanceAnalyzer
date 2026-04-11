package dev.boondock.performanceanalyzer.anticheat;

import dev.boondock.performanceanalyzer.alerts.AlertManager;
import dev.boondock.performanceanalyzer.alerts.AlertPreferenceManager;
import dev.boondock.performanceanalyzer.alerts.DiscordWebhook;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages XRay alerts - collects them and provides a summary view.
 * Only sends summary notifications to chat, detailed info available via command.
 */
public class XRayAlertManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DiscordWebhook discordWebhook;

    // Store alerts per player
    private final Map<UUID, List<XRayAlert>> playerAlerts = new ConcurrentHashMap<>();

    // Track if we already notified admins about suspicious players
    private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();
    private AlertPreferenceManager preferenceManager;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public XRayAlertManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.discordWebhook = new DiscordWebhook(plugin, config);

        // Schedule cleanup of old alerts every 10 minutes
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupOldAlerts, 12000L, 12000L);
    }

    /**
     * Set the alert preference manager for silent mode support.
     */
    public void setPreferenceManager(AlertPreferenceManager preferenceManager) {
        this.preferenceManager = preferenceManager;
    }

    /**
     * Add a new XRay alert for a player.
     */
    public void addAlert(Player player, String type, String details, int oreCount) {
        addAlert(player, type, details, oreCount, null, null);
    }

    /**
     * Add a new XRay alert for a player with ore breakdown.
     */
    public void addAlert(Player player, String type, String details, int oreCount, Map<String, Integer> oreBreakdown) {
        addAlert(player, type, details, oreCount, oreBreakdown, null);
    }

    /**
     * Add a new XRay alert for a player with ore breakdown and locations.
     */
    public void addAlert(Player player, String type, String details, int oreCount, Map<String, Integer> oreBreakdown, List<String> locations) {
        UUID playerId = player.getUniqueId();

        List<XRayAlert> alerts = playerAlerts.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());
        XRayAlert alert = new XRayAlert(type, details, oreCount, System.currentTimeMillis(), oreBreakdown, locations);
        alerts.add(alert);

        // Only log to console in debug mode
        if (config.debugMode()) {
            plugin.getLogger().info("[XRay] " + player.getName() + ": " + type + " - " + details);
        }

        // Only send one summary notification per player (reset after 5 minutes)
        if (!notifiedPlayers.contains(playerId)) {
            notifiedPlayers.add(playerId);
            notifyAdmins(player, oreBreakdown, locations);
            sendDiscordAlert(player, type, details, oreCount, oreBreakdown, locations);

            // Reset notification flag after 5 minutes
            Bukkit.getScheduler().runTaskLater(plugin, () -> notifiedPlayers.remove(playerId), 6000L);
        }
    }

    /**
     * Send a summary notification to admins.
     */
    private void notifyAdmins(Player suspect, Map<String, Integer> oreBreakdown, List<String> locations) {
        String message = String.format(
                "\u00a7e[XRay] \u00a7cVerdaechtiger Spieler: \u00a7f%s \u00a7e- Nutze \u00a7f/xrayalerts %s \u00a7efuer Details",
                suspect.getName(), suspect.getName()
        );

        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("performance.admin"))
                .filter(p -> preferenceManager == null ||
                        preferenceManager.shouldReceive(p, AlertPreferenceManager.AlertCategory.XRAY))
                .forEach(admin -> {
                    admin.sendMessage(message);
                    if (oreBreakdown != null && !oreBreakdown.isEmpty()) {
                        admin.sendMessage("\u00a77  Erze: " + formatOreBreakdownShort(oreBreakdown));
                    }
                    // Show last location if available
                    if (locations != null && !locations.isEmpty()) {
                        admin.sendMessage("\u00a77  Letzte Position: " + locations.get(locations.size() - 1));
                    }
                });
    }

    /**
     * Send alert to Discord webhook.
     */
    private void sendDiscordAlert(Player player, String type, String details, int oreCount, Map<String, Integer> oreBreakdown, List<String> locations) {
        if (!config.discordEnabled()) return;

        String oreInfo = "";
        if (oreBreakdown != null && !oreBreakdown.isEmpty()) {
            oreInfo = "\\n**Erze:** " + formatOreBreakdownShort(oreBreakdown);
        }

        String locationInfo = "";
        if (locations != null && !locations.isEmpty()) {
            locationInfo = "\\n**Letzte Positionen:**\\n" + String.join("\\n", locations);
        }

        String description = String.format(
                "**Spieler:** %s\\n**Typ:** %s\\n**Details:** %s%s%s",
                player.getName(), type, details, oreInfo, locationInfo
        );

        discordWebhook.sendAlert(
                AlertManager.AlertType.ANTICHEAT,
                "XRay Verdacht: " + player.getName(),
                description,
                oreCount
        );
    }

    /**
     * Format ore breakdown as short string.
     */
    private String formatOreBreakdownShort(Map<String, Integer> breakdown) {
        if (breakdown == null || breakdown.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        breakdown.forEach((ore, count) -> {
            if (sb.length() > 0) sb.append(", ");
            // Shorten ore name: DIAMOND_ORE -> Diamond
            String shortName = ore.replace("_ORE", "").replace("DEEPSLATE_", "");
            shortName = shortName.substring(0, 1) + shortName.substring(1).toLowerCase();
            sb.append(shortName).append(": ").append(count);
        });
        return sb.toString();
    }

    /**
     * Get all alerts for a player.
     */
    public List<XRayAlert> getAlerts(UUID playerId) {
        return playerAlerts.getOrDefault(playerId, Collections.emptyList());
    }

    /**
     * Get all players with alerts.
     */
    public Set<UUID> getSuspiciousPlayers() {
        return new HashSet<>(playerAlerts.keySet());
    }

    /**
     * Get total alert count for a player.
     */
    public int getAlertCount(UUID playerId) {
        return getAlerts(playerId).size();
    }

    /**
     * Clear alerts for a player (e.g., after review).
     */
    public void clearAlerts(UUID playerId) {
        playerAlerts.remove(playerId);
        notifiedPlayers.remove(playerId);
    }

    /**
     * Clear all alerts.
     */
    public void clearAllAlerts() {
        playerAlerts.clear();
        notifiedPlayers.clear();
    }

    /**
     * Cleanup alerts older than 30 minutes.
     */
    private void cleanupOldAlerts() {
        long cutoff = System.currentTimeMillis() - (30 * 60 * 1000L);

        playerAlerts.forEach((uuid, alerts) -> {
            alerts.removeIf(alert -> alert.timestamp() < cutoff);
            if (alerts.isEmpty()) {
                playerAlerts.remove(uuid);
                notifiedPlayers.remove(uuid);
            }
        });
    }

    /**
     * Format alerts for display.
     */
    public List<String> formatAlerts(UUID playerId) {
        List<String> lines = new ArrayList<>();
        List<XRayAlert> alerts = getAlerts(playerId);

        if (alerts.isEmpty()) {
            lines.add("\u00a77Keine Alerts fuer diesen Spieler.");
            return lines;
        }

        for (XRayAlert alert : alerts) {
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(alert.timestamp()));
            lines.add(String.format("\u00a77[%s] \u00a7e%s: \u00a7f%s \u00a77(%d Erze)",
                    time, alert.type(), alert.details(), alert.oreCount()));

            // Show ore breakdown if available
            if (alert.oreBreakdown() != null && !alert.oreBreakdown().isEmpty()) {
                lines.add("\u00a77  Erze: \u00a7f" + formatOreBreakdownShort(alert.oreBreakdown()));
            }

            // Show locations if available
            if (alert.locations() != null && !alert.locations().isEmpty()) {
                lines.add("\u00a77  Positionen:");
                for (String loc : alert.locations()) {
                    lines.add("\u00a78    - \u00a7f" + loc);
                }
            }
        }

        return lines;
    }

    /**
     * Alert data record.
     */
    public record XRayAlert(String type, String details, int oreCount, long timestamp, Map<String, Integer> oreBreakdown, List<String> locations) {}
}
