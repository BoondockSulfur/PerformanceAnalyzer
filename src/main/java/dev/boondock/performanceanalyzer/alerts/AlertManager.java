package dev.boondock.performanceanalyzer.alerts;

import dev.boondock.performanceanalyzer.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import dev.boondock.performanceanalyzer.util.Constants;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages performance alerts and notifications to admins.
 */
public class AlertManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DiscordWebhook discordWebhook;
    private final Set<AlertType> recentAlerts = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<AlertType, AtomicLong> lastAlertTimes = new ConcurrentHashMap<>();
    private AlertPreferenceManager preferenceManager;

    public AlertManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.discordWebhook = new DiscordWebhook(plugin, config);

        // Periodically clean up stale entries from lastAlertTimes to prevent memory leak
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            lastAlertTimes.entrySet().removeIf(e -> (now - e.getValue().get()) > Constants.ALERT_COOLDOWN_MS * 2);
        }, 6000L, 6000L); // Every 5 minutes
    }

    /**
     * Send an alert if conditions are met and cooldown has passed.
     * Thread-safe implementation using ConcurrentHashMap and AtomicLong.
     */
    public void sendAlert(AlertType type, String message, double value) {
        long now = System.currentTimeMillis();

        // Thread-safe cooldown check using atomic CAS to prevent race conditions
        AtomicLong lastTime = lastAlertTimes.computeIfAbsent(type, k -> new AtomicLong(0));
        while (true) {
            long lastAlertTime = lastTime.get();
            if ((now - lastAlertTime) < Constants.ALERT_COOLDOWN_MS) {
                return; // Still cooling down
            }
            if (lastTime.compareAndSet(lastAlertTime, now)) {
                break; // Successfully claimed this alert slot
            }
            // CAS failed, another thread updated — retry
        }

        // Send alert
        String formatted = String.format("§c[Performance Alert] §e%s: §f%s (Wert: %.2f)", type.getDisplayName(), message, value);

        // Log to console only in debug mode
        if (config.debugMode()) {
            plugin.getLogger().warning(formatted.replaceAll("§[0-9a-fk-or]", ""));
        }

        // Notify online admins (respecting silent mode preferences)
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("performance.admin")) {
                if (preferenceManager != null &&
                    !preferenceManager.shouldReceive(player, AlertPreferenceManager.AlertCategory.PERFORMANCE)) {
                    continue; // Player has muted performance alerts
                }
                player.sendMessage(formatted);
                // Optional: play sound
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);
            }
        }

        // Send to Discord
        discordWebhook.sendAlert(type, type.getDisplayName(), message, value);

        // Schedule cooldown reset (ticks = milliseconds / 50)
        Bukkit.getScheduler().runTaskLater(plugin, () -> recentAlerts.remove(type), Constants.ALERT_COOLDOWN_MS / 50);
    }

    /**
     * Set the alert preference manager for silent mode support.
     */
    public void setPreferenceManager(AlertPreferenceManager preferenceManager) {
        this.preferenceManager = preferenceManager;
    }

    public enum AlertType {
        HIGH_MSPT("Hohe MSPT"),
        HIGH_HEAP("Hoher Heap-Verbrauch"),
        TPS_DROP("TPS-Einbruch"),
        PACKET_FLOOD("Paket-Flut"),
        ANTICHEAT("AntiCheat-Warnung");

        private final String displayName;

        AlertType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
