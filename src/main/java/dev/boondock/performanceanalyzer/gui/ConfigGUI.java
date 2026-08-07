package dev.boondock.performanceanalyzer.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Config page with the toggles that still exist in v3: packet analysis,
 * Discord webhook and listener timings (lag_analysis.plugin_analysis).
 */
public final class ConfigGUI extends AbstractGui {

    private static final int SLOT_PACKET = 11;
    private static final int SLOT_DISCORD = 13;
    private static final int SLOT_TIMINGS = 15;
    private static final int SLOT_BACK = 22;

    private final Inventory inventory;

    ConfigGUI(GuiManager manager) {
        super(manager);
        this.inventory = Bukkit.createInventory(this, 27, lang.get("gui_config.title"));
        build();
    }

    private void build() {
        inventory.clear();

        inventory.setItem(SLOT_PACKET, toggle(Material.PAPER,
                lang.get("gui_config.packet_analysis"),
                manager.config().packetAnalysisEnabled(),
                lang.get("gui_config.packet_analysis_lore"),
                lang.get("gui_config.packet_analysis_hint"),
                lang.get("gui_config.requires_restart")));

        inventory.setItem(SLOT_DISCORD, toggle(Material.BOOK,
                lang.get("gui_config.discord_webhook"),
                manager.config().discordEnabled(),
                lang.get("gui_config.discord_webhook_lore")));

        inventory.setItem(SLOT_TIMINGS, toggle(Material.CLOCK,
                lang.get("gui_config.listener_timings"),
                manager.config().lagAnalysisPluginAnalysis(),
                lang.get("gui_config.listener_timings_lore"),
                lang.get("gui_config.requires_restart")));

        inventory.setItem(SLOT_BACK, item(Material.ARROW,
                lang.get("gui.back"), lang.get("gui.back_lore")));

        fillEmpty(inventory);
    }

    private ItemStack toggle(Material material, String name, boolean enabled, String... lore) {
        Material display = enabled ? material : Material.BARRIER;
        List<String> fullLore = new ArrayList<>();
        fullLore.add(lang.get("gui_config.status_format",
                "%status%", lang.get(enabled ? "gui_config.status_enabled" : "gui_config.status_disabled")));
        for (String line : lore) {
            fullLore.add(line);
        }
        fullLore.add(lang.get("gui_config.click_toggle"));
        return item(display, name, fullLore);
    }

    @Override
    public void handleClick(Player player, int slot) {
        switch (slot) {
            case SLOT_PACKET -> toggleConfig(player, "performance.packet_analysis",
                    manager.config().packetAnalysisEnabled());
            case SLOT_DISCORD -> toggleConfig(player, "discord.enabled",
                    manager.config().discordEnabled());
            case SLOT_TIMINGS -> toggleConfig(player, "lag_analysis.plugin_analysis",
                    manager.config().lagAnalysisPluginAnalysis());
            case SLOT_BACK -> manager.openLater(player, manager::openMain);
            default -> { }
        }
    }

    private void toggleConfig(Player player, String path, boolean currentValue) {
        if (!requireAdmin(player)) {
            return;
        }
        boolean newValue = !currentValue;
        manager.plugin().getConfig().set(path, newValue);
        manager.plugin().saveConfig();
        manager.config().reload();

        player.sendMessage(lang.get("gui_config.setting_changed",
                "%setting%", path + " = " + newValue));
        build();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
