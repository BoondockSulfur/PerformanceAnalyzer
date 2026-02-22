package dev.boondock.performanceanalyzer.gui;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
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

public class ConfigGUI implements Listener, InventoryHolder {

    private final PerformanceAnalyzer plugin;
    private final PluginConfig config;
    private final Player player;
    private final Inventory inventory;

    public ConfigGUI(PerformanceAnalyzer plugin, PluginConfig config, Player player) {
        this.plugin = plugin;
        this.config = config;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, 27, color("&eKonfiguration"));

        setupGUI();
    }

    private void setupGUI() {
        // AntiCheat Toggle
        boolean acEnabled = config.anticheatEnabled();
        inventory.setItem(10, createToggle(Material.SHIELD, "&cAntiCheat", acEnabled,
            "&7Aktiviert/Deaktiviert AntiCheat",
            "&eKlicken zum Umschalten"
        ));

        // XRay Detection Toggle
        boolean xrayEnabled = config.xrayDetectionEnabled();
        inventory.setItem(12, createToggle(Material.DIAMOND_ORE, "&bXRay-Erkennung", xrayEnabled,
            "&7Aktiviert/Deaktiviert XRay-Detection",
            "&eKlicken zum Umschalten"
        ));

        // Packet Analysis Toggle
        boolean packetAnalysis = config.packetAnalysisEnabled();
        inventory.setItem(14, createToggle(Material.PAPER, "&dPaket-Analyse", packetAnalysis,
            "&7Aktiviert/Deaktiviert Paket-Analyse",
            "&eErfordert ProtocolLib"
        ));

        // Discord Webhook Toggle
        boolean discordEnabled = config.discordEnabled();
        inventory.setItem(16, createToggle(Material.BOOK, "&9Discord Webhook", discordEnabled,
            "&7Aktiviert/Deaktiviert Discord-Benachrichtigungen",
            "&eKlicken zum Umschalten"
        ));

        // Back Button
        inventory.setItem(22, createItem(Material.ARROW, "&7Zurück", "&eZum Hauptmenü"));

        // Filler
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < 27; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler);
        }
    }

    public void open() {
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // Check InventoryHolder type instead of specific instance
        if (!(event.getInventory().getHolder() instanceof ConfigGUI gui)) return;
        event.setCancelled(true);

        int slot = event.getSlot();
        Player p = (Player) event.getWhoClicked();

        switch (slot) {
            case 10 -> gui.toggleConfig("performance.anticheat_enabled", gui.config.anticheatEnabled(), p);
            case 12 -> gui.toggleConfig("anticheat.xray_detection", gui.config.xrayDetectionEnabled(), p);
            case 14 -> gui.toggleConfig("performance.packet_analysis", gui.config.packetAnalysisEnabled(), p);
            case 16 -> gui.toggleConfig("discord.enabled", gui.config.discordEnabled(), p);
            case 22 -> new PerformanceGUI(gui.plugin, gui.config, gui.plugin.tickSampler(), gui.plugin.memorySampler()).open(p);
        }
    }

    private void toggleConfig(String path, boolean currentValue, Player p) {
        boolean newValue = !currentValue;
        plugin.getConfig().set(path, newValue);
        plugin.saveConfig();
        config.reload();

        p.sendMessage(color("&aEinstellung geändert: &f" + path + " = " + newValue));
        p.closeInventory();

        // Re-open GUI with updated values
        new ConfigGUI(plugin, config, p).open();
    }

    private ItemStack createToggle(Material mat, String name, boolean enabled, String... lore) {
        Material displayMat = enabled ? mat : Material.BARRIER;
        List<String> fullLore = new ArrayList<>();
        fullLore.add(color("&7"));
        fullLore.add(color("&7Status: " + (enabled ? "&aAktiviert" : "&cDeaktiviert")));
        fullLore.add(color("&7"));
        for (String line : lore) fullLore.add(color(line));

        return createItemWithLore(displayMat, name, fullLore);
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        List<String> loreList = new ArrayList<>();
        for (String line : lore) loreList.add(color(line));
        return createItemWithLore(mat, name, loreList);
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
