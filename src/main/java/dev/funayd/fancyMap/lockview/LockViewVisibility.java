package dev.funayd.fancyMap.lockview;

import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

/**
 * Owns client-only visibility changes made while a map is open.
 */
final class LockViewVisibility {
    private static final int CLIENT_BOX_RADIUS_XZ = 2;
    private static final int CLIENT_BOX_RADIUS_Y = 3;
    private static final int CLIENT_BOX_VERTICAL_OFFSET = 2;
    private static final int CLIENT_CONCRETE_RADIUS_XZ = CLIENT_BOX_RADIUS_XZ + 1;
    private static final int CLIENT_CONCRETE_RADIUS_Y = CLIENT_BOX_RADIUS_Y + 1;

    private final JavaPlugin plugin;
    private final ConcurrentMap<UUID, LockViewState> states;

    /**
     * Creates a visibility coordinator.
     *
     * @param plugin owning plugin
     * @param states active lock states
     */
    LockViewVisibility(JavaPlugin plugin, ConcurrentMap<UUID, LockViewState> states) {
        this.plugin = plugin;
        this.states = states;
    }

    /**
     * Hides every currently loaded entity from the viewer.
     *
     * @param viewer locked player
     * @param state viewer state
     */
    void hideEntities(Player viewer, LockViewState state) {
        for (Entity entity : new ArrayList<>(viewer.getWorld().getEntities())) {
            if (entity != viewer) {
                hideEntity(viewer, state, entity);
            }
        }
    }

    /**
     * Hides a newly spawned or joined entity from all matching locked viewers.
     *
     * @param entity entity to hide
     */
    void hideEntityFromLockedPlayers(Entity entity) {
        for (Map.Entry<UUID, LockViewState> entry : states.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline()
                    || viewer == entity
                    || viewer.getWorld() != entity.getWorld()) {
                continue;
            }
            hideEntity(viewer, entry.getValue(), entity);
        }
    }

    /**
     * Restores entity visibility owned by FancyMap.
     *
     * @param viewer locked player
     * @param state viewer state
     */
    void restoreEntities(Player viewer, LockViewState state) {
        for (UUID playerId : state.hiddenPlayers) {
            Player hiddenPlayer = Bukkit.getPlayer(playerId);
            if (hiddenPlayer != null) {
                viewer.showPlayer(plugin, hiddenPlayer);
            }
        }
        for (Entity entity : state.hiddenEntities) {
            viewer.showEntity(plugin, entity);
        }
    }

    /**
     * Sends temporary air blocks around the viewer and returns their locations.
     *
     * @param player viewer
     * @param center center of the client-only clearing volume
     * @return locations whose original block data must be restored
     */
    List<Location> hideNearbyBlocks(
            Player player,
            Location center
    ) {
        World world = center.getWorld();
        int centerX = center.getBlockX();
        int centerY = center.getBlockY() + CLIENT_BOX_VERTICAL_OFFSET;
        int centerZ = center.getBlockZ();
        int minY = Math.max(world.getMinHeight(), centerY - CLIENT_BOX_RADIUS_Y);
        int maxY = Math.min(
                world.getMaxHeight() - 1,
                centerY + CLIENT_BOX_RADIUS_Y
        );
        List<Location> locations = new ArrayList<>();
        Map<Position, BlockData> changes = new HashMap<>();
        BlockData air = Material.AIR.createBlockData();

        for (int x = centerX - CLIENT_BOX_RADIUS_XZ;
             x <= centerX + CLIENT_BOX_RADIUS_XZ;
             x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = centerZ - CLIENT_BOX_RADIUS_XZ;
                     z <= centerZ + CLIENT_BOX_RADIUS_XZ;
                     z++) {
                    Location location = new Location(world, x, y, z);
                    locations.add(location);
                    changes.put(Position.block(x, y, z), air);
                }
            }
        }
        player.sendMultiBlockChange(changes);
        return locations;
    }

    /**
     * Adds a client-only black-concrete shell immediately outside the cleared
     * volume and returns its changed locations.
     *
     * @param player viewer
     * @param center center of the client-only box
     * @return locations whose original block data must be restored
     */
    List<Location> showClientBox(Player player, Location center) {
        World world = center.getWorld();
        int centerX = center.getBlockX();
        int centerY = center.getBlockY() + CLIENT_BOX_VERTICAL_OFFSET;
        int centerZ = center.getBlockZ();
        int innerMinY = Math.max(
                world.getMinHeight(),
                centerY - CLIENT_BOX_RADIUS_Y
        );
        int innerMaxY = Math.min(
                world.getMaxHeight() - 1,
                centerY + CLIENT_BOX_RADIUS_Y
        );
        int minY = Math.max(world.getMinHeight(), innerMinY - 1);
        int maxY = Math.min(world.getMaxHeight() - 1, innerMaxY + 1);
        List<Location> locations = new ArrayList<>();
        Map<Position, BlockData> changes = new HashMap<>();
        BlockData concrete = Material.BLACK_CONCRETE.createBlockData();

        for (int x = centerX - CLIENT_CONCRETE_RADIUS_XZ;
             x <= centerX + CLIENT_CONCRETE_RADIUS_XZ;
             x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = centerZ - CLIENT_CONCRETE_RADIUS_XZ;
                     z <= centerZ + CLIENT_CONCRETE_RADIUS_XZ;
                     z++) {
                    boolean boundary = x == centerX - CLIENT_CONCRETE_RADIUS_XZ
                            || x == centerX + CLIENT_CONCRETE_RADIUS_XZ
                            || y == minY
                            || y == maxY
                            || z == centerZ - CLIENT_CONCRETE_RADIUS_XZ
                            || z == centerZ + CLIENT_CONCRETE_RADIUS_XZ;
                    if (!boundary) {
                        continue;
                    }
                    Location location = new Location(world, x, y, z);
                    locations.add(location);
                    changes.put(Position.block(x, y, z), concrete);
                }
            }
        }
        player.sendMultiBlockChange(changes);
        return locations;
    }

    /**
     * Restores server block data that was cleared for the client.
     *
     * @param player viewer
     * @param state viewer state
     */
    void restoreNearbyBlocks(Player player, LockViewState state) {
        if (state.clientAirBlocks.isEmpty()) {
            return;
        }
        Map<Position, BlockData> changes = new HashMap<>();
        for (Location location : state.clientAirBlocks) {
            changes.put(
                    Position.block(
                            location.getBlockX(),
                            location.getBlockY(),
                            location.getBlockZ()
                    ),
                    location.getBlock().getBlockData()
            );
        }
        player.sendMultiBlockChange(changes);
    }

    /** Restores server block data replaced by the concrete shell. */
    void restoreClientBox(Player player, LockViewState state) {
        if (state.clientConcreteBlocks.isEmpty()) {
            return;
        }
        Map<Position, BlockData> changes = new HashMap<>();
        for (Location location : state.clientConcreteBlocks) {
            changes.put(
                    Position.block(
                            location.getBlockX(),
                            location.getBlockY(),
                            location.getBlockZ()
                    ),
                    location.getBlock().getBlockData()
            );
        }
        player.sendMultiBlockChange(changes);
    }

    private void hideEntity(Player viewer, LockViewState state, Entity entity) {
        if (entity instanceof Player otherPlayer) {
            viewer.hidePlayer(plugin, otherPlayer);
            state.hiddenPlayers.add(otherPlayer.getUniqueId());
        } else {
            viewer.hideEntity(plugin, entity);
            state.hiddenEntities.add(entity);
        }
    }
}
