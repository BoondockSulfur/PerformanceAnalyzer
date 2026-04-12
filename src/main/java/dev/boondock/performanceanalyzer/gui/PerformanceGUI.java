package dev.boondock.performanceanalyzer.gui;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.integration.SparkHook;
import dev.boondock.performanceanalyzer.metrics.MemorySampler;
import dev.boondock.performanceanalyzer.metrics.TickSampler;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PerformanceGUI implements Listener, InventoryHolder {

    private final PerformanceAnalyzer plugin;
    private final PluginConfig config;
    private final TickSampler tickSampler;
    private final MemorySampler memorySampler;
    private final Inventory inventory;

    // Auto-refresh system
    private final Map<UUID, Integer> autoRefreshTasks = new HashMap<>();
    private static final int AUTO_REFRESH_INTERVAL_SECONDS = 3;

    public PerformanceGUI(PerformanceAnalyzer plugin, PluginConfig config, TickSampler tickSampler, MemorySampler memorySampler) {
        this.plugin = plugin;
        this.config = config;
        this.tickSampler = tickSampler;
        this.memorySampler = memorySampler;
        this.inventory = Bukkit.createInventory(this, 27, color("&aPerformance Analyzer"));

        setupGUI();
    }

    private void setupGUI() {
        // Row 1: Main Info
        // Performance Status
        inventory.setItem(10, createItem(Material.REDSTONE_TORCH,
            "&aLive Performance",
            "&7TPS, MSPT, Heap-Nutzung",
            "&eKlicken zum Aktualisieren"
        ));

        // Configuration
        inventory.setItem(12, createItem(Material.COMPARATOR,
            "&eEinstellungen",
            "&7Plugin-Konfiguration",
            "&cNur für Admins"
        ));

        // AntiCheat Settings
        inventory.setItem(14, createItem(Material.SHIELD,
            "&cAntiCheat",
            "&7AntiCheat-Status & Whitelist",
            "&eKlicken für Details"
        ));

        // Reload Plugin
        inventory.setItem(16, createItem(Material.COMMAND_BLOCK,
            "&6Plugin Neu Laden",
            "&7Konfiguration neu laden",
            "&cNur für Admins"
        ));

        // Row 2: Analysis Tools (NEW!)
        // Lag Analysis
        inventory.setItem(19, createItem(Material.CLOCK,
            "&6&lLag-Analyse",
            "&7Zeigt aktive Spieler",
            "&7und verdächtige Plugins",
            "&e➜ NEU in v2.0.0"
        ));

        // Performance Drops
        inventory.setItem(21, createItem(Material.REDSTONE_BLOCK,
            "&c&lPerformance Drops",
            "&7Zeigt letzte TPS/MSPT Einbrüche",
            "&7Klick für Details",
            "&e➜ NEU in v2.0.0"
        ));

        // World Stats
        inventory.setItem(23, createItem(Material.GRASS_BLOCK,
            "&a&lWorld Stats",
            "&7Entity & Chunk Übersicht",
            "&7pro Welt"
        ));

        // Database Info
        inventory.setItem(25, createItem(Material.CHEST,
            "&b&lDatabase Info",
            "&7Datenbank-Typ & Status",
            "&7Performance-Metriken"
        ));

        // Close
        inventory.setItem(22, createItem(Material.BARRIER,
            "&cSchließen",
            "&7GUI schließen"
        ));

        // Fill empty slots with glass pane
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);
        }

        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    public void open(Player player) {
        updatePerformanceItem();
        player.openInventory(inventory);

        // Enable auto-refresh if configured
        if (config.guiAutoRefresh()) {
            enableAutoRefresh(player);
        }
    }

    /**
     * Enable auto-refresh for a player viewing this GUI.
     */
    private void enableAutoRefresh(Player player) {
        UUID playerId = player.getUniqueId();

        // Cancel existing task if any
        disableAutoRefresh(playerId);

        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Check if player still has this GUI open
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof PerformanceGUI) {
                updatePerformanceItem();
            } else {
                // Player closed GUI - cancel task
                disableAutoRefresh(playerId);
            }
        }, 20L * AUTO_REFRESH_INTERVAL_SECONDS, 20L * AUTO_REFRESH_INTERVAL_SECONDS).getTaskId();

        autoRefreshTasks.put(playerId, taskId);
    }

    /**
     * Disable auto-refresh for a player.
     */
    private void disableAutoRefresh(UUID playerId) {
        Integer taskId = autoRefreshTasks.remove(playerId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    /**
     * Cleanup auto-refresh tasks on plugin disable.
     */
    public void shutdown() {
        for (Integer taskId : autoRefreshTasks.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        autoRefreshTasks.clear();
    }

    /**
     * Update the performance display item with current metrics.
     * Uses Spark data if available, otherwise falls back to internal calculations.
     *
     * This method is called when the GUI is opened or manually refreshed.
     */
    private void updatePerformanceItem() {
        SparkHook spark = plugin.spark();
        boolean usingSpark = spark != null && spark.isAvailable();

        double heap = memorySampler.heapUsagePercent();

        List<String> lore = new ArrayList<>();
        lore.add(color("&7"));

        if (usingSpark) {
            // Use Spark data
            SparkHook.SparkStats stats = spark.getStats();
            lore.add(color("&7TPS (10s/1m/5m):"));
            lore.add(color(String.format("  &f%.2f &7/ &f%.2f &7/ &f%.2f",
                stats.tps10s(), stats.tps1m(), stats.tps5m())));
            lore.add(color(String.format("&7MSPT: &f%.2f &7avg | &f%.2f &7p95 | &f%.2f &7max",
                stats.msptMean(), stats.msptP95(), stats.msptMax())));
            lore.add(color(String.format("&7Heap: &f%.1f%%", heap)));

            if (stats.hasValidCpu()) {
                lore.add(color(String.format("&7CPU: &f%.1f%% &7sys | &f%.1f%% &7proc",
                    stats.cpuSystem(), stats.cpuProcess())));
            }

            lore.add(color("&7"));
            lore.add(color("&a(Spark-Daten)"));
        } else {
            // Use internal calculations
            double mspt = tickSampler.msptAvg();
            double tps = tickSampler.tpsApprox();

            lore.add(color(String.format("&7TPS: &f%.2f", tps)));
            lore.add(color(String.format("&7MSPT (avg): &f%.2f ms", mspt)));
            lore.add(color(String.format("&7MSPT (p95): &f%.2f ms", tickSampler.msptP95())));
            lore.add(color(String.format("&7Heap: &f%.1f%%", heap)));
            lore.add(color("&7"));
            lore.add(color("&e(Eigene Messung)"));
        }

        lore.add(color("&eKlicken zum Aktualisieren"));

        ItemStack item = inventory.getItem(10);
        if (item != null) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Check InventoryHolder type instead of specific instance
        if (!(event.getInventory().getHolder() instanceof PerformanceGUI gui)) return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        int slot = event.getSlot();

        switch (slot) {
            case 10 -> { // Performance Status
                gui.updatePerformanceItem();
                player.sendMessage(color("&aDaten aktualisiert!"));
            }
            case 12 -> { // Configuration
                if (player.hasPermission("performance.admin")) {
                    new ConfigGUI(gui.plugin, gui.config, player).open();
                } else {
                    player.sendMessage(color("&cDafür hast du keine Berechtigung."));
                }
            }
            case 14 -> { // AntiCheat
                new AntiCheatGUI(gui.plugin, gui.config, player).open();
            }
            case 16 -> { // Reload
                if (player.hasPermission("performance.admin")) {
                    player.closeInventory();
                    player.sendMessage(color("&ePlugin wird neu geladen..."));
                    try {
                        gui.plugin.reloadPlugin();
                        player.sendMessage(color("&aPlugin erfolgreich neu geladen!"));
                    } catch (Exception e) {
                        player.sendMessage(color("&cFehler beim Neuladen: " + e.getMessage()));
                    }
                } else {
                    player.sendMessage(color("&cDafür hast du keine Berechtigung."));
                }
            }
            case 19 -> { // Lag Analysis (NEW)
                if (player.hasPermission("performance.admin")) {
                    new LagAnalysisGUI(gui.plugin, gui.config, player,
                        gui.plugin.getPlayerActivityTracker(), gui.plugin.getPluginTimingsAnalyzer()).open();
                } else {
                    player.sendMessage(color("&cDafür hast du keine Berechtigung."));
                }
            }
            case 21 -> { // Performance Drops (NEW)
                if (player.hasPermission("performance.admin")) {
                    new PerformanceDropsGUI(gui.plugin, gui.config, player,
                        gui.plugin.getDropAnalyzer()).open();
                } else {
                    player.sendMessage(color("&cDafür hast du keine Berechtigung."));
                }
            }
            case 23 -> { // World Stats
                if (player.hasPermission("performance.admin")) {
                    player.closeInventory();
                    player.performCommand("worldstats");
                } else {
                    player.sendMessage(color("&cDafür hast du keine Berechtigung."));
                }
            }
            case 25 -> { // Database Info
                if (player.hasPermission("performance.admin")) {
                    player.closeInventory();
                    player.sendMessage(color("&7─────────────────────"));
                    player.sendMessage(color("&b&lDatabase Info"));
                    player.sendMessage(color("&7Typ: &f" + gui.config.dbType().toUpperCase()));
                    if (gui.config.dbType().equals("sqlite")) {
                        player.sendMessage(color("&7Datei: &f" + gui.config.sqliteFile()));
                    } else {
                        player.sendMessage(color("&7Host: &f" + gui.config.dbHost() + ":" + gui.config.dbPort()));
                        player.sendMessage(color("&7Datenbank: &f" + gui.config.dbName()));
                    }
                    player.sendMessage(color("&7Pool Size: &f" + gui.config.poolMax()));
                    player.sendMessage(color("&7Log-Intervall: &f" + gui.config.logIntervalSeconds() + "s"));
                    player.sendMessage(color("&7─────────────────────"));
                } else {
                    player.sendMessage(color("&cDafür hast du keine Berechtigung."));
                }
            }
            case 22 -> {
                // Close
                disableAutoRefresh(player.getUniqueId());
                player.closeInventory();
            }
        }
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof PerformanceGUI)) return;

        // Cleanup auto-refresh task when player closes GUI
        disableAutoRefresh(player.getUniqueId());
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));

            List<String> loreList = new ArrayList<>();
            for (String line : lore) {
                loreList.add(color(line));
            }
            meta.setLore(loreList);

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
