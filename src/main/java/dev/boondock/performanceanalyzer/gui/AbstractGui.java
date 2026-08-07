package dev.boondock.performanceanalyzer.gui;

import dev.boondock.performanceanalyzer.lang.LanguageManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared plumbing for all plugin GUIs: item building, filler panes and the
 * open flow (open inventory, then register the auto-refresh task with the
 * {@link GuiManager}).
 */
abstract class AbstractGui implements PluginGui {

    protected final GuiManager manager;
    protected final LanguageManager lang;

    protected AbstractGui(GuiManager manager) {
        this.manager = manager;
        this.lang = manager.lang();
    }

    /** Opens this GUI for the player and starts the shared refresh task. */
    public void open(Player player) {
        player.openInventory(getInventory());
        manager.startRefresh(player);
    }

    protected ItemStack item(Material material, String name, String... lore) {
        return item(material, name, Arrays.asList(lore));
    }

    protected ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(new ArrayList<>(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Fills every empty slot of the inventory with a gray glass pane. */
    protected void fillEmpty(Inventory inventory) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    protected boolean requireAdmin(Player player) {
        if (player.hasPermission("performance.admin")) {
            return true;
        }
        player.sendMessage(lang.get("general.no_permission"));
        return false;
    }
}
