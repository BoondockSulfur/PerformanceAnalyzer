package dev.boondock.performanceanalyzer.gui;

import dev.boondock.performanceanalyzer.analysis.Severity;
import dev.boondock.performanceanalyzer.analysis.SeverityModel;
import dev.boondock.performanceanalyzer.metrics.GcSampler;
import dev.boondock.performanceanalyzer.metrics.TickStats;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Main GUI page: live data from the {@link dev.boondock.performanceanalyzer.monitor.MonitorService}
 * plus navigation to the analysis pages. One instance per open.
 */
public final class PerformanceGUI extends AbstractGui {

    private static final int SLOT_STATUS = 4;
    private static final int SLOT_LIVE = 10;
    private static final int SLOT_LAG = 12;
    private static final int SLOT_INCIDENTS = 14;
    private static final int SLOT_CONFIG = 16;
    private static final int SLOT_CLOSE = 22;

    private final Inventory inventory;

    PerformanceGUI(GuiManager manager) {
        super(manager);
        this.inventory = Bukkit.createInventory(this, 27, lang.get("gui.main_title"));
        build();
    }

    private void build() {
        updateLiveItems();

        inventory.setItem(SLOT_LAG, item(Material.CLOCK,
                lang.get("gui.nav_lag"),
                lang.get("gui.nav_lag_lore"),
                lang.get("gui.settings_lore2")));

        inventory.setItem(SLOT_INCIDENTS, item(Material.REDSTONE_BLOCK,
                lang.get("gui.nav_incidents"),
                lang.get("gui.nav_incidents_lore"),
                lang.get("gui.settings_lore2")));

        inventory.setItem(SLOT_CONFIG, item(Material.COMPARATOR,
                lang.get("gui.settings"),
                lang.get("gui.settings_lore1"),
                lang.get("gui.settings_lore2")));

        inventory.setItem(SLOT_CLOSE, item(Material.BARRIER,
                lang.get("gui.close"),
                lang.get("gui.close_lore")));

        fillEmpty(inventory);
    }

    /** Rebuilds the data-bearing items (status pane + live stats). */
    private void updateLiveItems() {
        TickStats ticks = manager.monitor().tickStats();
        GcSampler.GcStats gc = manager.monitor().gcStats();
        SeverityModel.Assessment assessment = manager.monitor().assessment();

        // Status glass pane colored by severity
        inventory.setItem(SLOT_STATUS, item(paneFor(assessment.severity()),
                lang.get("gui.status_pane",
                        "%color%", assessment.severity().color(),
                        "%severity%", assessment.severity().name()),
                lang.get("gui.status_score_lore", "%score%", String.valueOf(assessment.score()))));

        List<String> lore = new ArrayList<>();
        if (ticks.hasData()) {
            lore.add(lang.get("gui.stat_tps",
                    "%tps10%", String.format("%.2f", ticks.tps10s()),
                    "%tps60%", String.format("%.2f", ticks.tps60s())));
            lore.add(lang.get("gui.stat_mspt",
                    "%avg%", String.format("%.1f", ticks.msptAvg10s()),
                    "%p95%", String.format("%.1f", ticks.msptP95()),
                    "%max%", String.format("%.1f", ticks.msptMax10s())));
        } else {
            lore.add(lang.get("gui.stat_no_data"));
        }
        if (gc != null) {
            lore.add(lang.get("gui.stat_heap",
                    "%used%", String.valueOf(gc.heapUsedMb()),
                    "%max%", String.valueOf(gc.heapMaxMb()),
                    "%percent%", String.format("%.1f", gc.heapUsedPercent())));
        }
        lore.add(lang.get("gui.stat_severity",
                "%color%", assessment.severity().color(),
                "%severity%", assessment.severity().name(),
                "%score%", String.valueOf(assessment.score())));
        lore.add(lang.get("gui.live_performance_lore2"));

        inventory.setItem(SLOT_LIVE, item(Material.REDSTONE_TORCH,
                lang.get("gui.live_performance"), lore));
    }

    private static Material paneFor(Severity severity) {
        return switch (severity) {
            case OK -> Material.LIME_STAINED_GLASS_PANE;
            case NOTICE -> Material.YELLOW_STAINED_GLASS_PANE;
            case WARNING -> Material.ORANGE_STAINED_GLASS_PANE;
            case CRITICAL, EMERGENCY -> Material.RED_STAINED_GLASS_PANE;
        };
    }

    @Override
    public void refresh() {
        updateLiveItems();
    }

    @Override
    public void handleClick(Player player, int slot) {
        switch (slot) {
            case SLOT_LIVE -> {
                updateLiveItems();
                player.sendMessage(lang.get("gui.data_refreshed"));
            }
            case SLOT_LAG -> {
                if (requireAdmin(player)) {
                    manager.openLater(player, manager::openLagAnalysis);
                }
            }
            case SLOT_INCIDENTS -> {
                if (requireAdmin(player)) {
                    manager.openLater(player, manager::openIncidents);
                }
            }
            case SLOT_CONFIG -> {
                if (requireAdmin(player)) {
                    manager.openLater(player, manager::openConfig);
                }
            }
            case SLOT_CLOSE -> manager.openLater(player, Player::closeInventory);
            default -> { }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
