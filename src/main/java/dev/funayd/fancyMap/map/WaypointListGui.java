package dev.funayd.fancyMap.map;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Bukkit inventory list that lets players seek configured waypoints. */
public final class WaypointListGui implements Listener {
    private static final int CONTENT_SIZE = 45;
    private static final int INVENTORY_SIZE = 54;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;

    private final WaypointManager waypointManager;
    private final BiConsumer<Player, String> seeker;

    /**
     * Creates the waypoint list GUI.
     *
     * @param waypointManager source of waypoint definitions
     * @param seeker callback used after selecting one waypoint
     */
    public WaypointListGui(
            WaypointManager waypointManager,
            BiConsumer<Player, String> seeker
    ) {
        this.waypointManager = waypointManager;
        this.seeker = seeker;
    }

    /** Opens the first page of configured waypoints. */
    public void open(Player player) {
        open(player, 0);
    }

    /** Handles a waypoint selection or page navigation. */
    @EventHandler
    private void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Page page)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            return;
        }
        int index = page.index * CONTENT_SIZE + slot;
        if (slot < CONTENT_SIZE && index < page.waypoints.size()) {
            player.closeInventory();
            seeker.accept(player, page.waypoints.get(index).id());
        } else if (slot == PREVIOUS_SLOT && page.index > 0) {
            open(player, page.index - 1);
        } else if (slot == NEXT_SLOT && indexForPage(page.index + 1) < page.waypoints.size()) {
            open(player, page.index + 1);
        }
    }

    /** Prevents moving items into the read-only waypoint inventory. */
    @EventHandler
    private void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Page) {
            event.setCancelled(true);
        }
    }

    /** Opens one bounded page from the latest waypoint snapshot. */
    private void open(Player player, int pageIndex) {
        List<Waypoint> waypoints = waypointManager.all();
        Page holder = new Page(pageIndex, waypoints);
        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text("FancyMap Waypoints")
        );
        holder.inventory = inventory;
        int first = indexForPage(pageIndex);
        for (int slot = 0; slot < CONTENT_SIZE && first + slot < waypoints.size(); slot++) {
            inventory.setItem(slot, waypointItem(waypoints.get(first + slot)));
        }
        if (pageIndex > 0) {
            inventory.setItem(PREVIOUS_SLOT, button(Material.ARROW, "Previous"));
        }
        if (indexForPage(pageIndex + 1) < waypoints.size()) {
            inventory.setItem(NEXT_SLOT, button(Material.ARROW, "Next"));
        }
        player.openInventory(inventory);
    }

    /** Creates one clickable waypoint entry. */
    private ItemStack waypointItem(Waypoint waypoint) {
        Material material = waypoint.iconMaterial() == null
                ? Material.COMPASS
                : Material.matchMaterial(waypoint.iconMaterial());
        if (material == null || !material.isItem()) {
            material = Material.COMPASS;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(waypoint.name()));
        meta.lore(List.of(
                Component.text("ID: " + waypoint.id()),
                Component.text(waypoint.worldName()),
                Component.text(String.format(
                        "X: %.1f  Y: %.1f  Z: %.1f",
                        waypoint.x(),
                        waypoint.y(),
                        waypoint.z()
                )),
                Component.text("Click to view on map")
        ));
        item.setItemMeta(meta);
        return item;
    }

    /** Creates one navigation button. */
    private ItemStack button(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        item.setItemMeta(meta);
        return item;
    }

    /** Converts a page number to the first waypoint index. */
    private int indexForPage(int page) {
        return page * CONTENT_SIZE;
    }

    /** Read-only holder carrying the exact waypoint snapshot displayed to a player. */
    private static final class Page implements InventoryHolder {
        private final int index;
        private final List<Waypoint> waypoints;
        private Inventory inventory;

        private Page(int index, List<Waypoint> waypoints) {
            this.index = index;
            this.waypoints = new ArrayList<>(waypoints);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
