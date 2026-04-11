package dev.boondock.performanceanalyzer.anticheat;

import dev.boondock.performanceanalyzer.alerts.AlertManager;
import dev.boondock.performanceanalyzer.alerts.AlertPreferenceManager;
import dev.boondock.performanceanalyzer.alerts.DiscordWebhook;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Movement/Speed/Fly alerts - collects them and provides a summary view.
 * Only sends summary notifications to chat, detailed info available via command.
 */
public class MovementAlertManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DiscordWebhook discordWebhook;

    // Store alerts per player
    private final Map<UUID, List<MovementAlert>> playerAlerts = new ConcurrentHashMap<>();

    // Track if we already notified admins about suspicious players
    private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();

    // Cooldown per player per type (to prevent spam)
    private final Map<UUID, Map<String, Long>> alertCooldowns = new ConcurrentHashMap<>();
    private AlertPreferenceManager preferenceManager;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    // Cooldown in milliseconds (5 minutes per type)
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000L;

    public MovementAlertManager(Plugin plugin, PluginConfig config) {
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
     * Add a new movement alert for a player.
     */
    public void addAlert(Player player, String type, String details, double value, Location location) {
        UUID playerId = player.getUniqueId();

        // Check cooldown for this type
        if (isOnCooldown(playerId, type)) {
            return;
        }

        List<MovementAlert> alerts = playerAlerts.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());

        String locationStr = formatLocation(location);
        MovementAlert alert = new MovementAlert(type, details, value, System.currentTimeMillis(), locationStr);
        alerts.add(alert);

        // Set cooldown
        setCooldown(playerId, type);

        // Only log to console in debug mode
        if (config.debugMode()) {
            plugin.getLogger().info("[Movement] " + player.getName() + ": " + type + " - " + details);
        }

        // Only send one summary notification per player (reset after 5 minutes)
        if (!notifiedPlayers.contains(playerId)) {
            notifiedPlayers.add(playerId);
            notifyAdmins(player, type, details, value, locationStr);
            sendDiscordAlert(player, type, details, value, locationStr);

            // Reset notification flag after 5 minutes
            Bukkit.getScheduler().runTaskLater(plugin, () -> notifiedPlayers.remove(playerId), 6000L);
        }
    }

    private boolean isOnCooldown(UUID playerId, String type) {
        Map<String, Long> cooldowns = alertCooldowns.get(playerId);
        if (cooldowns == null) return false;

        Long lastAlert = cooldowns.get(type);
        if (lastAlert == null) return false;

        return System.currentTimeMillis() - lastAlert < ALERT_COOLDOWN_MS;
    }

    private void setCooldown(UUID playerId, String type) {
        alertCooldowns.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(type, System.currentTimeMillis());
    }

    private String formatLocation(Location loc) {
        if (loc == null) return "Unknown";
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
        return String.format("%s [%d, %d, %d]", worldName, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Send a summary notification to admins.
     */
    private void notifyAdmins(Player suspect, String type, String details, double value, String location) {
        String message = String.format(
                "\u00a7e[AntiCheat] \u00a7cVerdaechtiger Spieler: \u00a7f%s \u00a7e- Nutze \u00a7f/movealerts %s \u00a7efuer Details",
                suspect.getName(), suspect.getName()
        );

        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("performance.admin"))
                .filter(p -> preferenceManager == null ||
                        preferenceManager.shouldReceive(p, AlertPreferenceManager.AlertCategory.MOVEMENT))
                .forEach(admin -> {
                    admin.sendMessage(message);
                    admin.sendMessage(String.format("\u00a77  Typ: %s | Wert: %.2f | Position: %s", type, value, location));
                });
    }

    /**
     * Send alert to Discord webhook.
     */
    private void sendDiscordAlert(Player player, String type, String details, double value, String location) {
        if (!config.discordEnabled()) return;

        String description = String.format(
                "**Spieler:** %s\\n**Typ:** %s\\n**Details:** %s\\n**Wert:** %.2f\\n**Position:** %s",
                player.getName(), type, details, value, location
        );

        discordWebhook.sendAlert(
                AlertManager.AlertType.ANTICHEAT,
                "Movement Verdacht: " + player.getName(),
                description,
                value
        );
    }

    /**
     * Get all alerts for a player.
     */
    public List<MovementAlert> getAlerts(UUID playerId) {
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
        alertCooldowns.remove(playerId);
    }

    /**
     * Clear all alerts.
     */
    public void clearAllAlerts() {
        playerAlerts.clear();
        notifiedPlayers.clear();
        alertCooldowns.clear();
    }

    /**
     * Cleanup alerts older than 30 minutes and expired cooldowns.
     */
    private void cleanupOldAlerts() {
        long now = System.currentTimeMillis();
        long alertCutoff = now - (30 * 60 * 1000L);
        long cooldownCutoff = now - ALERT_COOLDOWN_MS;

        // Clean up old alerts
        playerAlerts.entrySet().removeIf(entry -> {
            List<MovementAlert> alerts = entry.getValue();
            alerts.removeIf(alert -> alert.timestamp() < alertCutoff);

            // Remove empty entries and cleanup tracking
            if (alerts.isEmpty()) {
                notifiedPlayers.remove(entry.getKey());
                return true; // Remove this entry
            }
            return false;
        });

        // Clean up expired cooldowns (more efficient)
        alertCooldowns.entrySet().removeIf(entry -> {
            Map<String, Long> cooldowns = entry.getValue();
            cooldowns.entrySet().removeIf(cd -> cd.getValue() < cooldownCutoff);
            return cooldowns.isEmpty();
        });
    }

    /**
     * Format alerts for display.
     */
    public List<String> formatAlerts(UUID playerId) {
        List<String> lines = new ArrayList<>();
        List<MovementAlert> alerts = getAlerts(playerId);

        if (alerts.isEmpty()) {
            lines.add("\u00a77Keine Alerts fuer diesen Spieler.");
            return lines;
        }

        for (MovementAlert alert : alerts) {
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(alert.timestamp()));
            lines.add(String.format("\u00a77[%s] \u00a7e%s: \u00a7f%s",
                    time, alert.type(), alert.details()));
            lines.add(String.format("\u00a77  Wert: \u00a7f%.2f \u00a77| Position: \u00a7f%s",
                    alert.value(), alert.location()));
        }

        return lines;
    }

    /**
     * Alert data record.
     */
    public record MovementAlert(String type, String details, double value, long timestamp, String location) {}
}
