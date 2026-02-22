package dev.boondock.performanceanalyzer.integration;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.db.DatabaseManager;
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

    private final AtomicLong packetsThisTick = new AtomicLong(0);
    private final AtomicLong packetsReceived = new AtomicLong(0);
    private final AtomicLong packetsSent = new AtomicLong(0);
    private final AtomicLong totalPackets = new AtomicLong(0);

    private int resetTaskId = -1;
    private int logTaskId = -1;

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

    /**
     * Set database for packet count logging.
     * Cancels previous logging task if exists to prevent memory leak.
     */
    public void setDatabase(DatabaseManager db) {
        // Cancel old logging task if exists (prevents memory leak on reload)
        if (logTaskId != -1) {
            Bukkit.getScheduler().cancelTask(logTaskId);
            logTaskId = -1;
        }

        this.database = db;

        // Log packet stats every minute
        if (db != null) {
            this.logTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                if (database != null) {
                    long total = totalPackets.get();
                    long recv = packetsReceived.get();
                    long sent = packetsSent.get();

                    database.logAsync("packets_total", total, "Total packets processed");
                    database.logAsync("packets_received", recv, "Packets received from clients");
                    database.logAsync("packets_sent", sent, "Packets sent to clients");
                }
            }, 1200L, 1200L).getTaskId(); // 60 seconds
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

        // Check for packet flood (potential lag cause or attack)
        resetTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long count = packetsThisTick.getAndSet(0);

            // Warn if packet count exceeds threshold
            double threshold = cfg.packetFloodThreshold();
            if (threshold > 0 && count > threshold) {
                plugin.getLogger().warning(
                    String.format("Hohe Paket-Last erkannt: %d Pakete/Tick (Schwellenwert: %.0f)", count, threshold)
                );
            }
        }, 1L, 1L).getTaskId();
    }

    public void shutdown() {
        if (resetTaskId != -1) Bukkit.getScheduler().cancelTask(resetTaskId);
        if (logTaskId != -1) Bukkit.getScheduler().cancelTask(logTaskId);
        pm.removePacketListeners(plugin);
    }

    // Getter for stats
    public long getTotalPackets() { return totalPackets.get(); }
    public long getPacketsReceived() { return packetsReceived.get(); }
    public long getPacketsSent() { return packetsSent.get(); }
}
