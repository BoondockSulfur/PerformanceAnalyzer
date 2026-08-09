package dev.boondock.performanceanalyzer.integration;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.alerts.AlertManager;
import dev.boondock.performanceanalyzer.analysis.Severity;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class ProtocolLibHook {
    private final Plugin plugin;
    private final PluginConfig cfg;
    private final ProtocolManager pm;
    private DatabaseManager database;
    private volatile AlertManager alertManager;

    private final AtomicLong packetsThisTick = new AtomicLong(0);
    private final AtomicLong peakPacketsPerTick = new AtomicLong(0);
    private final AtomicLong packetsReceived = new AtomicLong(0);
    private final AtomicLong packetsSent = new AtomicLong(0);
    private final AtomicLong totalPackets = new AtomicLong(0);

    private ScheduledTask resetTask;
    private ScheduledTask logTask;

    private ProtocolLibHook(Plugin plugin, PluginConfig cfg, ProtocolManager pm) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.pm = pm;
    }

    public static ProtocolLibHook tryHook(Plugin plugin, PluginConfig cfg) {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().info("ProtocolLib nicht gefunden - Paket-Analyse deaktiviert.");
            return null;
        }
        ProtocolLibHook hook = new ProtocolLibHook(plugin, cfg, ProtocolLibrary.getProtocolManager());
        hook.register();
        plugin.getLogger().info("ProtocolLib Hook aktiviert - Paket-Analyse gestartet.");
        return hook;
    }

    /** Wires the alert system; packet floods become real alerts instead of log lines. */
    public void setAlertManager(AlertManager alertManager) {
        this.alertManager = alertManager;
    }

    private LanguageManager lang() {
        return plugin instanceof PerformanceAnalyzer pa ? pa.lang() : null;
    }

    /**
     * Set database for packet count logging.
     * Cancels previous logging task if exists to prevent memory leak.
     */
    public void setDatabase(DatabaseManager db) {
        // Cancel old logging task if exists (prevents memory leak on reload)
        Scheduling.cancel(logTask);
        logTask = null;

        this.database = db;

        // Log packet stats every minute
        if (db != null) {
            this.logTask = Scheduling.runAsyncRepeating(plugin, () -> {
                if (database != null) {
                    database.logAsync("packets_total", totalPackets.get(), "Total packets processed");
                    database.logAsync("packets_received", packetsReceived.get(), "Packets received from clients");
                    database.logAsync("packets_sent", packetsSent.get(), "Packets sent to clients");
                }
            }, 60_000L, 60_000L);
        }
    }

    private void register() {
        // Filter only valid/supported packet types for current MC version
        List<PacketType> validPackets = new ArrayList<>();
        for (PacketType type : PacketType.values()) {
            try {
                // Check if packet is supported in current version
                if (type.isSupported() && !type.isDeprecated()) {
                    validPackets.add(type);
                }
            } catch (Exception ignored) {
                // Skip unsupported packets
            }
        }

        plugin.getLogger().info("ProtocolLib: " + validPackets.size() + " gültige Paket-Typen registriert.");

        if (validPackets.isEmpty()) {
            plugin.getLogger().warning("Keine gültigen Paket-Typen gefunden!");
            return;
        }

        pm.addPacketListener(new PacketAdapter(plugin, ListenerPriority.MONITOR, validPackets) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                packetsThisTick.incrementAndGet();
                packetsReceived.incrementAndGet();
                totalPackets.incrementAndGet();
            }

            @Override
            public void onPacketSending(PacketEvent event) {
                packetsThisTick.incrementAndGet();
                packetsSent.incrementAndGet();
                totalPackets.incrementAndGet();
            }
        });

        // Per-tick counter reset + packet flood detection (potential lag cause or attack)
        resetTask = Scheduling.runGlobalRepeating(plugin, () -> {
            long count = packetsThisTick.getAndSet(0);
            // Remember the peak so calibration can derive a flood threshold
            // from real traffic. The database only stores cumulative totals,
            // whose deltas average the spikes away.
            peakPacketsPerTick.accumulateAndGet(count, Math::max);

            double threshold = cfg.packetFloodThreshold();
            if (threshold > 0 && count > threshold) {
                Severity severity = count > threshold * 5 ? Severity.CRITICAL : Severity.WARNING;
                LanguageManager lang = lang();
                String message = lang != null
                        ? lang.format("alert.packet_flood", count, threshold)
                        : String.format("High packet load: %d packets/tick (threshold: %.0f)", count, threshold);
                AlertManager alerts = this.alertManager;
                if (alerts != null) {
                    alerts.sendAlert(AlertManager.AlertType.PACKET_FLOOD, severity, message, count);
                } else {
                    plugin.getLogger().warning(message);
                }
            }
        }, 1L, 1L);
    }

    public void shutdown() {
        Scheduling.cancel(resetTask);
        Scheduling.cancel(logTask);
        resetTask = null;
        logTask = null;
        pm.removePacketListeners(plugin);
    }

    // Getter for stats
    public long getTotalPackets() { return totalPackets.get(); }
    public long getPacketsReceived() { return packetsReceived.get(); }
    public long getPacketsSent() { return packetsSent.get(); }

    /** Highest packets-per-tick observed since the last call, then resets. */
    public long drainPeakPacketsPerTick() { return peakPacketsPerTick.getAndSet(0L); }
}
