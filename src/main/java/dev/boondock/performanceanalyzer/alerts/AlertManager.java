package dev.boondock.performanceanalyzer.alerts;

import dev.boondock.performanceanalyzer.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages performance alerts and notifications to admins.
 */
public class AlertManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private final DiscordWebhook discordWebhook;
    private final Set<AlertType> recentAlerts = ConcurrentHashMap.newKeySet();
    private final long ALERT_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(5); // 5 minute cooldown per alert type
    private final ConcurrentHashMap<AlertType, AtomicLong> lastAlertTimes = new ConcurrentHashMap<>();

    public AlertManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.discordWebhook = new DiscordWebhook(plugin, config);
    }

    /**
     * Send an alert if conditions are met and cooldown has passed.
     * Thread-safe implementation using ConcurrentHashMap and AtomicLong.
     */
    public void sendAlert(AlertType type, String message, double value) {
        long now = System.currentTimeMillis();

        // Thread-safe cooldown check
        AtomicLong lastTime = lastAlertTimes.computeIfAbsent(type, k -> new AtomicLong(0));
        long lastAlertTime = lastTime.get();

        if (recentAlerts.contains(type) && (now - lastAlertTime) < ALERT_COOLDOWN_MS) {
            return; // Skip to avoid spam
        }

        // Send alert
        String formatted = String.format("§c[Performance Alert] §e%s: §f%s (Wert: %.2f)", type.getDisplayName(), message, value);

        // Log to console only in debug mode
        if (config.debugMode()) {
            plugin.getLogger().warning(formatted.replaceAll("§[0-9a-fk-or]", ""));
        }

        // Notify online admins
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("performance.admin")) {
                player.sendMessage(formatted);
                // Optional: play sound
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);
            }
        }

        // Send to Discord
        discordWebhook.sendAlert(type, type.getDisplayName(), message, value);

        // Mark as sent - thread-safe
        recentAlerts.add(type);
        lastTime.set(now);

        // Schedule cooldown reset (ticks = milliseconds / 50)
        Bukkit.getScheduler().runTaskLater(plugin, () -> recentAlerts.remove(type), ALERT_COOLDOWN_MS / 50);
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
