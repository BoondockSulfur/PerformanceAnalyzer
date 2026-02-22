package dev.boondock.performanceanalyzer.gui;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.analysis.PerformanceDropAnalyzer;
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

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * GUI page showing recent performance drops.
 * Allows clicking on drops for detailed reports.
 */
public class PerformanceDropsGUI implements Listener, InventoryHolder {

    private final PerformanceAnalyzer plugin;
    private final PluginConfig config;
    private final Player player;
    private final Inventory inventory;
    private final PerformanceDropAnalyzer dropAnalyzer;
    private final List<PerformanceDropAnalyzer.PerformanceDrop> drops;

    public PerformanceDropsGUI(PerformanceAnalyzer plugin, PluginConfig config, Player player,
                              PerformanceDropAnalyzer dropAnalyzer) {
        this.plugin = plugin;
        this.config = config;
        this.player = player;
        this.dropAnalyzer = dropAnalyzer;
        this.drops = dropAnalyzer.getRecentDrops();
        this.inventory = Bukkit.createInventory(this, 54, color("&cPerformance Drops (" + drops.size() + ")"));

        setupGUI();
    }

    private void setupGUI() {
        if (drops.isEmpty()) {
            inventory.setItem(22, createItem(Material.LIME_DYE,
                "&a&lKeine Performance Drops!",
                "&7Der Server läuft stabil",
                "&7(Letzte 20 Drops werden gespeichert)"
            ));
        } else {
            // Display up to 21 drops (3 rows)
            int slot = 10;
            int row = 0;
            for (int i = 0; i < Math.min(drops.size(), 21); i++) {
                PerformanceDropAnalyzer.PerformanceDrop drop = drops.get(i);

                // Skip slots 17, 18, 26, 27, etc. (border slots)
                if (slot == 17 || slot == 18) slot = 19;
                if (slot == 26 || slot == 27) slot = 28;
                if (slot == 35 || slot == 36) slot = 37;

                inventory.setItem(slot, createDropItem(drop, i));
                slot++;
            }
        }

        // Clear All Button
        if (!drops.isEmpty()) {
            inventory.setItem(48, createItem(Material.TNT,
                "&c&lAlle Löschen",
                "&7Löscht alle Performance-Drop-Daten",
                "&eNützlich für Clean Start"
            ));
        }

        // Refresh Button
        inventory.setItem(50, createItem(Material.LIME_DYE,
            "&a&lAktualisieren",
            "&7Lade Drops neu"
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

    private ItemStack createDropItem(PerformanceDropAnalyzer.PerformanceDrop drop, int index) {
        List<String> lore = new ArrayList<>();
        lore.add(color("&7"));
        lore.add(color("&7Zeit: &f" + drop.timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
        lore.add(color("&7"));
        lore.add(color("&cTPS: &f" + String.format("%.1f", drop.previousTps) + " &c→ &c&l" + String.format("%.1f", drop.lowestTps)));
        lore.add(color("&cMSPT: &f" + String.format("%.1f", drop.previousMspt) + "ms &c→ &c&l" + String.format("%.1f", drop.highestMspt) + "ms"));
        lore.add(color("&7"));

        // Show lag sources if available
        if (drop.analysisData.containsKey("topActivePlayers")) {
            @SuppressWarnings("unchecked")
            List<String> players = (List<String>) drop.analysisData.get("topActivePlayers");
            if (!players.isEmpty()) {
                lore.add(color("&e⚠ Aktive Spieler: &f" + players.size()));
            }
        }

        if (drop.analysisData.containsKey("suspectPlugins")) {
            @SuppressWarnings("unchecked")
            List<String> plugins = (List<String>) drop.analysisData.get("suspectPlugins");
            if (!plugins.isEmpty()) {
                lore.add(color("&c⚠ Verdächtige Plugins: &f" + plugins.size()));
            }
        }

        if (drop.analysisData.containsKey("problematicWorld")) {
            lore.add(color("&6⚠ Problem-Welt: &f" + drop.analysisData.get("problematicWorld")));
        }

        lore.add(color("&7"));
        lore.add(color("&eKlicke für Details!"));

        // Color based on severity
        Material icon;
        if (drop.lowestTps < 15) {
            icon = Material.RED_CONCRETE;
        } else if (drop.lowestTps < 18) {
            icon = Material.ORANGE_CONCRETE;
        } else {
            icon = Material.YELLOW_CONCRETE;
        }

        return createItemWithLore(icon, "&c&lDrop #" + (index + 1), lore);
    }

    public void open() {
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PerformanceDropsGUI gui)) return;
        event.setCancelled(true);

        Player p = (Player) event.getWhoClicked();
        int slot = event.getSlot();
        ItemStack clicked = event.getCurrentItem();

        // Check if clicked on a drop item
        if (clicked != null && clicked.getType().toString().contains("CONCRETE")) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta != null && meta.getDisplayName().contains("Drop #")) {
                // Extract index from name
                String name = ChatColor.stripColor(meta.getDisplayName());
                try {
                    int index = Integer.parseInt(name.replace("Drop #", "")) - 1;
                    if (index >= 0 && index < gui.drops.size()) {
                        PerformanceDropAnalyzer.PerformanceDrop drop = gui.drops.get(index);
                        p.closeInventory();
                        // Send detailed report
                        String report = gui.dropAnalyzer.getDetailedReport(drop);
                        for (String line : report.split("\n")) {
                            p.sendMessage(line);
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        switch (slot) {
            case 48 -> { // Clear All
                gui.dropAnalyzer.clearDrops();
                p.sendMessage(color("&aAlle Performance-Drops gelöscht!"));
                p.closeInventory();
                new PerformanceDropsGUI(gui.plugin, gui.config, p, gui.dropAnalyzer).open();
            }
            case 50 -> { // Refresh
                p.closeInventory();
                new PerformanceDropsGUI(gui.plugin, gui.config, p, gui.dropAnalyzer).open();
                p.sendMessage(color("&aDaten aktualisiert!"));
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
