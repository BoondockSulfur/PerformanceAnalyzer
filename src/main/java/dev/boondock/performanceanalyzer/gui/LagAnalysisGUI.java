package dev.boondock.performanceanalyzer.gui;

import dev.boondock.performanceanalyzer.analysis.PlayerActivityTracker;
import dev.boondock.performanceanalyzer.timing.ListenerTimings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Lag analysis page: measured per-plugin listener load from
 * {@link ListenerTimings} plus the most active players (neutral wording — a
 * busy player is a data point, not a suspect).
 *
 * <p>Slot layout is non-overlapping (v2 wrote the plugin list starting at 23
 * and overwrote the 5th player entry): players occupy 19-23, plugins 28-32.</p>
 */
public final class LagAnalysisGUI extends AbstractGui {

    private static final int SLOT_PLAYERS_HEADER = 10;
    private static final int SLOT_PLUGINS_HEADER = 14;
    private static final int PLAYERS_START = 19;   // 19..23
    private static final int PLUGINS_START = 28;   // 28..32
    private static final int MAX_ENTRIES = 5;
    private static final int SLOT_REFRESH = 40;
    private static final int SLOT_CLEAR = 41;
    private static final int SLOT_BACK = 49;

    private final Inventory inventory;

    LagAnalysisGUI(GuiManager manager) {
        super(manager);
        this.inventory = Bukkit.createInventory(this, 54, lang.get("gui_lag.title"));
        build();
    }

    private void build() {
        inventory.clear();

        buildPlayerSection();
        buildPluginSection();

        inventory.setItem(SLOT_REFRESH, item(Material.LIME_DYE,
                lang.get("gui.refresh"), lang.get("gui.refresh_lore")));
        inventory.setItem(SLOT_CLEAR, item(Material.RED_DYE,
                lang.get("gui_lag.clear"), lang.get("gui_lag.clear_lore")));
        inventory.setItem(SLOT_BACK, item(Material.ARROW,
                lang.get("gui.back"), lang.get("gui.back_lore")));

        fillEmpty(inventory);
    }

    private void buildPlayerSection() {
        inventory.setItem(SLOT_PLAYERS_HEADER, item(Material.PLAYER_HEAD,
                lang.get("gui_lag.players_header"),
                lang.get("gui_lag.players_header_lore1"),
                lang.get("gui_lag.players_header_lore2")));

        PlayerActivityTracker tracker = manager.activityTracker();
        if (!manager.config().lagAnalysisPlayerTracking() || tracker == null) {
            inventory.setItem(PLAYERS_START, item(Material.BARRIER,
                    lang.get("gui_lag.players_disabled"),
                    lang.get("gui_lag.players_disabled_lore1"),
                    lang.get("gui_lag.players_disabled_lore2")));
            return;
        }

        List<PlayerActivityTracker.PlayerActivitySnapshot> topPlayers =
                tracker.getTopActivePlayers(MAX_ENTRIES);
        if (topPlayers.isEmpty()) {
            inventory.setItem(PLAYERS_START, item(Material.GRAY_DYE,
                    lang.get("gui_lag.no_player_data"),
                    lang.get("gui_lag.no_player_data_lore")));
            return;
        }
        int slot = PLAYERS_START;
        for (PlayerActivityTracker.PlayerActivitySnapshot snap : topPlayers) {
            if (slot >= PLAYERS_START + MAX_ENTRIES) break;
            inventory.setItem(slot++, playerItem(snap));
        }
    }

    private void buildPluginSection() {
        inventory.setItem(SLOT_PLUGINS_HEADER, item(Material.REDSTONE,
                lang.get("gui_lag.plugins_header"),
                lang.get("gui_lag.plugins_header_lore1"),
                lang.get("gui_lag.plugins_header_lore2")));

        ListenerTimings timings = manager.timings();
        if (timings == null || !timings.isActive()) {
            inventory.setItem(PLUGINS_START, item(Material.BARRIER,
                    lang.get("gui_lag.timings_disabled"),
                    lang.get("gui_lag.timings_disabled_lore1"),
                    lang.get("gui_lag.timings_disabled_lore2")));
            return;
        }

        List<ListenerTimings.PluginLoad> loads = timings.loads();
        if (loads.isEmpty()) {
            inventory.setItem(PLUGINS_START, item(Material.GRAY_DYE,
                    lang.get("gui_lag.no_plugin_data"),
                    lang.get("gui_lag.no_plugin_data_lore")));
            return;
        }
        int slot = PLUGINS_START;
        for (ListenerTimings.PluginLoad load : loads) {
            if (slot >= PLUGINS_START + MAX_ENTRIES) break;
            inventory.setItem(slot++, pluginItem(load));
        }
    }

    private org.bukkit.inventory.ItemStack playerItem(PlayerActivityTracker.PlayerActivitySnapshot snap) {
        List<String> lore = new ArrayList<>();
        lore.add(lang.get("gui_lag.player_blocks",
                "%breaks%", String.valueOf(snap.blockBreaks),
                "%places%", String.valueOf(snap.blockPlaces)));
        lore.add(lang.get("gui_lag.player_moves", "%moves%", String.valueOf(snap.movements)));
        lore.add(lang.get("gui_lag.player_commands", "%commands%", String.valueOf(snap.commands)));
        lore.add(lang.get("gui_lag.player_interactions",
                "%interactions%", String.valueOf(snap.interactions + snap.entityInteractions)));
        lore.add(lang.get("gui_lag.player_score", "%score%", String.valueOf(snap.getTotalActivity())));
        return item(Material.PLAYER_HEAD, "§e" + snap.playerName, lore);
    }

    private org.bukkit.inventory.ItemStack pluginItem(ListenerTimings.PluginLoad load) {
        List<String> lore = new ArrayList<>();
        lore.add(lang.get("gui_lag.plugin_load",
                "%ms%", String.format("%.1f", load.msPerSec()),
                "%percent%", String.format("%.1f", load.percentOfThreadBudget())));
        lore.add(lang.get("gui_lag.plugin_calls", "%calls%", String.valueOf(load.callsPerSec())));
        lore.add(lang.get("gui_lag.plugin_top_event",
                "%event%", shortEventName(load.topEvent()),
                "%ms%", String.format("%.1f", load.topEventMsPerSec())));
        Material icon = load.msPerSec() >= 100 ? Material.REDSTONE_BLOCK
                : load.msPerSec() >= 50 ? Material.REDSTONE : Material.PAPER;
        return item(icon, "§6" + load.pluginName(), lore);
    }

    private static String shortEventName(String event) {
        if (event == null) return "-";
        int idx = event.lastIndexOf('.');
        return idx >= 0 ? event.substring(idx + 1) : event;
    }

    @Override
    public void refresh() {
        build();
    }

    @Override
    public void handleClick(Player player, int slot) {
        switch (slot) {
            case SLOT_REFRESH -> {
                build();
                player.sendMessage(lang.get("gui.data_refreshed"));
            }
            case SLOT_CLEAR -> {
                PlayerActivityTracker tracker = manager.activityTracker();
                if (tracker != null) {
                    tracker.clearActivity();
                }
                build();
                player.sendMessage(lang.get("gui_lag.cleared"));
            }
            case SLOT_BACK -> manager.openLater(player, manager::openMain);
            default -> { }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
