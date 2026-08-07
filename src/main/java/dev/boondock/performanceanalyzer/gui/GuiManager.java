package dev.boondock.performanceanalyzer.gui;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.analysis.IncidentAnalyzer;
import dev.boondock.performanceanalyzer.analysis.PlayerActivityTracker;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.monitor.MonitorService;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import dev.boondock.performanceanalyzer.timing.ListenerTimings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single event listener for all plugin GUIs plus per-player refresh task
 * management.
 *
 * <p>Fixes two v2 architecture bugs: (1) four fully populated throwaway GUI
 * inventories were built at startup just to act as listeners; (2) every
 * /perfgui started an eternal 3-second refresh task that was never reliably
 * cancelled. Here exactly one listener is registered and each player has at
 * most one refresh task, cancelled on inventory close and on quit.</p>
 */
public final class GuiManager implements Listener {

    /** Refresh period in ticks (3 seconds). */
    private static final long REFRESH_PERIOD_TICKS = 60L;

    private final PerformanceAnalyzer plugin;
    private final PluginConfig config;
    private final MonitorService monitorService;
    private final IncidentAnalyzer incidentAnalyzer;
    private final PlayerActivityTracker playerActivityTracker;
    private final ListenerTimings listenerTimings;

    private final Map<UUID, ScheduledTask> refreshTasks = new ConcurrentHashMap<>();

    public GuiManager(PerformanceAnalyzer plugin, PluginConfig config,
                      MonitorService monitorService, IncidentAnalyzer incidentAnalyzer,
                      PlayerActivityTracker playerActivityTracker, ListenerTimings listenerTimings) {
        this.plugin = plugin;
        this.config = config;
        this.monitorService = monitorService;
        this.incidentAnalyzer = incidentAnalyzer;
        this.playerActivityTracker = playerActivityTracker;
        this.listenerTimings = listenerTimings;
    }

    /* Accessors for the GUI instances */

    public PerformanceAnalyzer plugin() { return plugin; }
    public PluginConfig config() { return config; }
    public LanguageManager lang() { return plugin.lang(); }
    public MonitorService monitor() { return monitorService; }
    public IncidentAnalyzer incidents() { return incidentAnalyzer; }
    public PlayerActivityTracker activityTracker() { return playerActivityTracker; }
    public ListenerTimings timings() { return listenerTimings; }

    /* ------------------------------------------------------------------ */
    /* Openers                                                             */
    /* ------------------------------------------------------------------ */

    public void openMain(Player player) {
        new PerformanceGUI(this).open(player);
    }

    public void openLagAnalysis(Player player) {
        new LagAnalysisGUI(this).open(player);
    }

    public void openIncidents(Player player) {
        new IncidentsGUI(this).open(player);
    }

    public void openConfig(Player player) {
        new ConfigGUI(this).open(player);
    }

    /**
     * Opens a GUI from within a click handler. The actual open is deferred to
     * the player's scheduler — opening a new inventory while an
     * InventoryClickEvent is being processed is unsafe, and on Folia the
     * player's entity scheduler is the only legal place anyway.
     */
    void openLater(Player player, java.util.function.Consumer<Player> opener) {
        Scheduling.runAtEntity(plugin, player, () -> opener.accept(player));
    }

    /* ------------------------------------------------------------------ */
    /* Refresh task management                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Starts (or replaces) the auto-refresh task for a player viewing a GUI.
     * The task stops itself as soon as the player no longer has a plugin GUI
     * open, and is additionally cancelled on close/quit events.
     */
    void startRefresh(Player player) {
        UUID playerId = player.getUniqueId();
        stopRefresh(playerId);
        if (!config.guiAutoRefresh()) {
            return;
        }
        ScheduledTask task = Scheduling.runGlobalRepeating(plugin, () ->
                Scheduling.runAtEntity(plugin, player, () -> {
                    if (!player.isOnline()) {
                        stopRefresh(playerId);
                        return;
                    }
                    if (player.getOpenInventory().getTopInventory().getHolder() instanceof PluginGui gui) {
                        gui.refresh();
                    } else {
                        stopRefresh(playerId);
                    }
                }), REFRESH_PERIOD_TICKS, REFRESH_PERIOD_TICKS);
        refreshTasks.put(playerId, task);
    }

    void stopRefresh(UUID playerId) {
        Scheduling.cancel(refreshTasks.remove(playerId));
    }

    /* ------------------------------------------------------------------ */
    /* The one shared listener                                             */
    /* ------------------------------------------------------------------ */

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof PluginGui gui)) {
            return;
        }
        // Any click while a plugin GUI is open is cancelled (also shift-clicks
        // from the player's own inventory, which would move items into the GUI).
        event.setCancelled(true);

        // Only clicks in the TOP inventory reach the slot handling — the old
        // code reacted to clicks in the player's own inventory too.
        if (event.getClickedInventory() != top) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        gui.handleClick(player, event.getSlot());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof PluginGui)) {
            return;
        }
        if (event.getPlayer() instanceof Player player) {
            stopRefresh(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        stopRefresh(event.getPlayer().getUniqueId());
    }

    /** Cancels every refresh task (plugin disable). */
    public void shutdown() {
        for (ScheduledTask task : refreshTasks.values()) {
            Scheduling.cancel(task);
        }
        refreshTasks.clear();
    }
}
