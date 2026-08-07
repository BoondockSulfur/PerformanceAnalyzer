package dev.boondock.performanceanalyzer.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

/**
 * Contract for all plugin GUIs: the inventory's holder is the GUI instance,
 * so the single shared {@link GuiManager} listener can route clicks to the
 * exact instance that owns the inventory (never by parsing item names).
 */
public interface PluginGui extends InventoryHolder {

    /** Handles a click in the TOP inventory. The event is already cancelled. */
    void handleClick(Player player, int slot);

    /** Called by the refresh task while this GUI is open. Optional. */
    default void refresh() {
    }
}
