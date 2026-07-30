package dev.funayd.fancyMap.waypoint;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Immutable waypoint definition persisted by FancyMap. */
public record Waypoint(
        String id,
        String name,
        String worldName,
        double x,
        double y,
        double z,
        String iconMaterial,
        String iconTexture
) {
    /**
     * Resolves this waypoint to a Bukkit location.
     *
     * @return target location, or {@code null} when its world is unavailable
     */
    public Location location() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z);
    }
}
