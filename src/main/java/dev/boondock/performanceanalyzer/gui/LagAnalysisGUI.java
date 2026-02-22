package dev.boondock.performanceanalyzer.gui;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.analysis.PlayerActivityTracker;
import dev.boondock.performanceanalyzer.analysis.PluginTimingsAnalyzer;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI page showing lag source analysis.
 * Displays top active players and suspect plugins.
 */
public class LagAnalysisGUI implements Listener, InventoryHolder {

    private final PerformanceAnalyzer plugin;
    private final PluginConfig config;
    private final Player player;
    private final Inventory inventory;
    private final PlayerActivityTracker activityTracker;
    private final PluginTimingsAnalyzer pluginAnalyzer;

    public LagAnalysisGUI(PerformanceAnalyzer plugin, PluginConfig config, Player player,
                         PlayerActivityTracker activityTracker, PluginTimingsAnalyzer pluginAnalyzer) {
        this.plugin = plugin;
        this.config = config;
        this.player = player;
        this.activityTracker = activityTracker;
        this.pluginAnalyzer = pluginAnalyzer;
        this.inventory = Bukkit.createInventory(this, 54, color("&6Lag-Ursachen Analyse"));

        setupGUI();
    }

    private void setupGUI() {
        // Top Active Players
        inventory.setItem(10, createItem(Material.PLAYER_HEAD,
            "&e&lAktivste Spieler",
            "&7Zeigt die Top 5 aktivsten Spieler",
            "&7(Block-Interaktionen, Bewegungen, etc.)"
        ));

        // Populate player list
        if (config.lagAnalysisPlayerTracking() && activityTracker != null) {
            List<PlayerActivityTracker.PlayerActivitySnapshot> topPlayers = activityTracker.getTopActivePlayers(5);
            int slot = 19;
            for (PlayerActivityTracker.PlayerActivitySnapshot snap : topPlayers) {
                if (slot > 23) break;
                inventory.setItem(slot++, createPlayerItem(snap));
            }
            if (topPlayers.isEmpty()) {
                inventory.setItem(19, createItem(Material.GRAY_DYE, "&7Keine Daten", "&7Warte auf Spieleraktivität..."));
            }
        } else {
            inventory.setItem(19, createItem(Material.BARRIER, "&cDeaktiviert",
                "&7Player-Tracking ist deaktiviert",
                "&7Aktiviere in config.yml:",
                "&elag_analysis.player_tracking: true"));
        }

        // Top Suspect Plugins
        inventory.setItem(14, createItem(Material.REDSTONE,
            "&c&lVerdächtige Plugins",
            "&7Zeigt Plugins mit vielen Listenern",
            "&7und hoher Aktivität"
        ));

        // Populate plugin list
        if (config.lagAnalysisPluginAnalysis() && pluginAnalyzer != null) {
            List<PluginTimingsAnalyzer.PluginPerformanceSnapshot> topPlugins = pluginAnalyzer.getTopLagSuspects(5);
            int slot = 23;
            for (PluginTimingsAnalyzer.PluginPerformanceSnapshot snap : topPlugins) {
                if (slot > 27) break;
                inventory.setItem(slot++, createPluginItem(snap));
            }
        } else {
            inventory.setItem(23, createItem(Material.BARRIER, "&cDeaktiviert", "&7Plugin-Analyse ist deaktiviert"));
        }

        // Refresh Button
        inventory.setItem(40, createItem(Material.LIME_DYE,
            "&a&lAktualisieren",
            "&7Daten neu laden"
        ));

        // Clear Data Button
        inventory.setItem(41, createItem(Material.RED_DYE,
            "&c&lDaten Zurücksetzen",
            "&7Löscht alle Aktivitätsdaten",
            "&eNützlich nach Untersuchung"
        ));

        // Back Button
        inventory.setItem(49, createItem(Material.ARROW, "&7Zurück", "&eZum Hauptmenü"));

        // Fill empty slots
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);

        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    private ItemStack createPlayerItem(PlayerActivityTracker.PlayerActivitySnapshot snap) {
        List<String> lore = new ArrayList<>();
        lore.add(color("&7"));
        lore.add(color("&7Block Breaks: &f" + snap.blockBreaks));
        lore.add(color("&7Block Places: &f" + snap.blockPlaces));
        lore.add(color("&7Movements: &f" + snap.movements));
        lore.add(color("&7Commands: &f" + snap.commands));
        lore.add(color("&7Interactions: &f" + snap.interactions));
        lore.add(color("&7Entity Hits: &f" + snap.entityInteractions));
        lore.add(color("&7"));
        lore.add(color("&6Activity Score: &e" + snap.getTotalActivity()));

        return createItemWithLore(Material.PLAYER_HEAD, "&e" + snap.playerName, lore);
    }

    private ItemStack createPluginItem(PluginTimingsAnalyzer.PluginPerformanceSnapshot snap) {
        List<String> lore = new ArrayList<>();
        lore.add(color("&7"));
        lore.add(color("&7Version: &f" + snap.version));
        lore.add(color("&7Event Listeners: &f" + snap.eventListeners));
        lore.add(color("&7Scheduled Tasks: &f" + snap.scheduledTasks));
        lore.add(color("&7"));

        String riskColor = snap.getRiskScore() > 100 ? "&c" : (snap.getRiskScore() > 50 ? "&e" : "&a");
        lore.add(color("&6Risk Score: " + riskColor + snap.getRiskScore()));

        if (snap.isHeavyPlugin) {
            lore.add(color("&c&l⚠ HEAVY PLUGIN"));
        }

        Material icon = snap.isHeavyPlugin ? Material.REDSTONE_BLOCK : Material.PAPER;
        return createItemWithLore(icon, "&6" + snap.pluginName, lore);
    }

    public void open() {
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof LagAnalysisGUI gui)) return;
        event.setCancelled(true);

        Player p = (Player) event.getWhoClicked();
        int slot = event.getSlot();

        switch (slot) {
            case 40 -> { // Refresh
                p.closeInventory();
                new LagAnalysisGUI(gui.plugin, gui.config, p, gui.activityTracker, gui.pluginAnalyzer).open();
                p.sendMessage(color("&aDaten aktualisiert!"));
            }
            case 41 -> { // Clear
                if (gui.activityTracker != null) {
                    gui.activityTracker.clearActivity();
                    p.sendMessage(color("&aAktivitätsdaten zurückgesetzt!"));
                    p.closeInventory();
                    new LagAnalysisGUI(gui.plugin, gui.config, p, gui.activityTracker, gui.pluginAnalyzer).open();
                }
            }
            case 49 -> // Back
                new PerformanceGUI(gui.plugin, gui.config, gui.plugin.tickSampler(), gui.plugin.memorySampler()).open(p);
        }
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        List<String> loreList = new ArrayList<>();
        for (String line : lore) {
            loreList.add(color(line));
        }
        return createItemWithLore(material, name, loreList);
    }

    private ItemStack createItemWithLore(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
