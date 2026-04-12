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

public class AntiCheatGUI implements Listener, InventoryHolder {

    private final PerformanceAnalyzer plugin;
    private final PluginConfig config;
    private final Player player;
    private final Inventory inventory;

    public AntiCheatGUI(PerformanceAnalyzer plugin, PluginConfig config, Player player) {
        this.plugin = plugin;
        this.config = config;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, 27, color("&cAntiCheat Status"));

        setupGUI();
    }

    private void setupGUI() {
        // AntiCheat Status
        boolean enabled = config.anticheatEnabled();
        Material mat = enabled ? Material.GREEN_CONCRETE : Material.RED_CONCRETE;
        inventory.setItem(4, createItem(mat, "&cAntiCheat Status",
            "&7Status: " + (enabled ? "&aAktiviert" : "&cDeaktiviert"),
            "&7",
            "&7Whitelist Spieler: &f" + config.anticheatWhitelistPlayers().size(),
            "&7Whitelist Gruppen: &f" + config.anticheatWhitelistGroups().size()
        ));

        // Whitelist Info
        inventory.setItem(11, createItem(Material.PLAYER_HEAD, "&eWhitelist Spieler",
            "&7Anzahl: &f" + config.anticheatWhitelistPlayers().size(),
            "&7",
            "&7Nutze &f/acwhitelist list",
            "&7um Details zu sehen"
        ));

        inventory.setItem(13, createItem(Material.IRON_CHESTPLATE, "&eWhitelist Gruppen",
            "&7Anzahl: &f" + config.anticheatWhitelistGroups().size(),
            "&7",
            "&7Nutze &f/acwhitelist list",
            "&7um Details zu sehen"
        ));

        // XRay Settings
        inventory.setItem(15, createItem(Material.DIAMOND_ORE, "&bXRay-Erkennung",
            "&7Status: " + (config.xrayDetectionEnabled() ? "&aAktiviert" : "&cDeaktiviert"),
            "&7Zeitfenster: &f" + config.xrayTimewindowSeconds() + " Sekunden",
            "&7Schwellenwerte: &fPer-Erz (config.yml)",
            "&8Diamond: " + config.xrayThresholdDiamond() + ", Debris: " + config.xrayThresholdAncientDebris()
        ));

        // Back
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
        if (!(event.getInventory().getHolder() instanceof AntiCheatGUI gui)) return;
        event.setCancelled(true);

        int slot = event.getSlot();
        Player p = (Player) event.getWhoClicked();

        if (slot == 22) {
            new PerformanceGUI(gui.plugin, gui.config, gui.plugin.tickSampler(), gui.plugin.memorySampler()).open(p);
        }
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));

            List<String> loreList = new ArrayList<>();
            for (String line : lore) loreList.add(color(line));
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
