package dev.boondock.performanceanalyzer.gui;

import dev.boondock.performanceanalyzer.analysis.Finding;
import dev.boondock.performanceanalyzer.analysis.Incident;
import dev.boondock.performanceanalyzer.commands.PerfIncidentsCommand;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Incident list page. Replaces the v2 PerformanceDropsGUI.
 *
 * <p>Numbering matches /perfincidents (#1 = newest). Click routing uses a
 * slot-to-incident map held by this instance — item display names are never
 * parsed. Clicking an incident closes the GUI and prints the same detail view
 * the command renders (shared helper).</p>
 */
public final class IncidentsGUI extends AbstractGui {

    private static final int MAX_DISPLAY = 21;
    private static final int SLOT_EMPTY_NOTICE = 22;
    private static final int SLOT_CLEAR = 48;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_REFRESH = 50;

    private record Entry(int number, Incident incident) {
    }

    private final Inventory inventory;
    private final Map<Integer, Entry> slotEntries = new HashMap<>();

    IncidentsGUI(GuiManager manager) {
        super(manager);
        this.inventory = Bukkit.createInventory(this, 54, lang.get("gui_incidents.title"));
        build();
    }

    private void build() {
        inventory.clear();
        slotEntries.clear();

        List<Incident> incidents = manager.incidents().recentIncidents();

        if (incidents.isEmpty()) {
            inventory.setItem(SLOT_EMPTY_NOTICE, item(Material.LIME_DYE,
                    lang.get("gui_incidents.none"),
                    lang.get("gui_incidents.none_lore")));
        } else {
            int slot = 10;
            int number = 1;
            for (Incident incident : incidents) {
                if (number > MAX_DISPLAY) break;
                // Skip border columns (slots ending the row)
                if (slot == 17 || slot == 18) slot = 19;
                if (slot == 26 || slot == 27) slot = 28;
                if (slot == 35 || slot == 36) slot = 37;
                if (slot >= 44) break;

                inventory.setItem(slot, incidentItem(incident, number));
                slotEntries.put(slot, new Entry(number, incident));
                slot++;
                number++;
            }
            inventory.setItem(SLOT_CLEAR, item(Material.TNT,
                    lang.get("gui_incidents.clear"),
                    lang.get("gui_incidents.clear_lore")));
        }

        inventory.setItem(SLOT_REFRESH, item(Material.LIME_DYE,
                lang.get("gui.refresh"), lang.get("gui.refresh_lore")));
        inventory.setItem(SLOT_BACK, item(Material.ARROW,
                lang.get("gui.back"), lang.get("gui.back_lore")));

        fillEmpty(inventory);
    }

    private ItemStack incidentItem(Incident incident, int number) {
        List<String> lore = new ArrayList<>();
        if (incident.isActive()) {
            lore.add(lang.get("incident_cmd.active_tag"));
        }
        lore.add(lang.get("gui_incidents.lore_severity",
                "%color%", incident.peakSeverity().color(),
                "%severity%", incident.peakSeverity().name(),
                "%score%", String.valueOf(incident.peakScore())));
        lore.add(lang.get("gui_incidents.lore_time",
                "%start%", incident.formattedStart(),
                "%duration%", incident.formattedDuration()));
        lore.add(lang.get("gui_incidents.lore_metrics",
                "%worst%", String.format("%.0f", incident.worstTickMs()),
                "%tps%", String.format("%.1f", incident.lowestTps())));
        String cause = topFindingTitle(incident);
        if (cause != null) {
            lore.add(lang.get("gui_incidents.lore_cause", "%cause%", cause));
        }
        lore.add(lang.get("gui_incidents.lore_click"));

        Material icon = switch (incident.peakSeverity()) {
            case CRITICAL, EMERGENCY -> Material.RED_CONCRETE;
            case WARNING -> Material.ORANGE_CONCRETE;
            default -> Material.YELLOW_CONCRETE;
        };
        return item(icon, lang.get("gui_incidents.entry", "%number%", String.valueOf(number)), lore);
    }

    private String topFindingTitle(Incident incident) {
        for (Finding finding : incident.findings()) {
            if (finding.type() != Finding.Type.UNKNOWN) {
                return finding.title();
            }
        }
        return null;
    }

    @Override
    public void refresh() {
        build();
    }

    @Override
    public void handleClick(Player player, int slot) {
        Entry entry = slotEntries.get(slot);
        if (entry != null) {
            manager.openLater(player, p -> {
                p.closeInventory();
                PerfIncidentsCommand.sendDetail(p, lang, entry.incident(), entry.number());
            });
            return;
        }
        switch (slot) {
            case SLOT_CLEAR -> {
                manager.incidents().clearHistory();
                build();
                player.sendMessage(lang.get("incident_cmd.cleared"));
            }
            case SLOT_REFRESH -> {
                build();
                player.sendMessage(lang.get("gui.data_refreshed"));
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
