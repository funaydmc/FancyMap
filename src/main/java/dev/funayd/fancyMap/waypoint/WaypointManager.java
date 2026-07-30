package dev.funayd.fancyMap.waypoint;

import dev.funayd.fancyMap.config.ConfigManager;
import dev.funayd.fancyMap.map.MapOverlay;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads, stores and persists admin-defined FancyMap waypoints. */
public final class WaypointManager {
    private static final int CANVAS_CENTER_X = MapOverlay.CANVAS_WIDTH / 2;
    private static final int CANVAS_CENTER_Y = MapOverlay.CANVAS_HEIGHT / 2;
    private static final int HOVER_RADIUS_PIXELS = 10;

    private final ConfigManager config;
    private final Map<String, Waypoint> waypoints = new LinkedHashMap<>();
    private volatile List<Waypoint> snapshot = List.of();

    /**
     * Loads waypoint definitions from the plugin configuration.
     *
     * @param config shared plugin configuration manager
     */
    public WaypointManager(ConfigManager config) {
        this.config = config;
        reload();
    }

    /** Reloads all waypoint definitions from YAML. */
    public void reload() {
        waypoints.clear();
        ConfigurationSection section = config.section("waypoints");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                String name = section.getString(id + ".name");
                String world = section.getString(id + ".world");
                if (name == null || name.isBlank() || world == null || world.isBlank()) {
                    continue;
                }
                waypoints.put(id, new Waypoint(
                        id,
                        name,
                        world,
                        section.getDouble(id + ".x"),
                        section.getDouble(id + ".y"),
                        section.getDouble(id + ".z"),
                        section.getString(id + ".icon.material"),
                        section.getString(id + ".icon.texture")
                ));
            }
        }
        updateSnapshot();
    }

    /** @return immutable current waypoint snapshot for async renderers */
    public List<Waypoint> all() {
        return snapshot;
    }

    /** @return waypoint ids in config order */
    public List<String> ids() {
        return snapshot.stream().map(Waypoint::id).toList();
    }

    /**
     * Creates a waypoint at the admin's current location.
     *
     * @param player creator and waypoint position source
     * @param id waypoint id
     * @param name display name
     * @return false when the id is invalid or already exists
     */
    public boolean create(Player player, String id, String name) {
        String normalizedId = normalizeId(id);
        if (normalizedId == null || name == null || name.isBlank()
                || waypoints.containsKey(normalizedId)) {
            return false;
        }

        Waypoint waypoint = new Waypoint(
                normalizedId,
                name.trim(),
                player.getWorld().getName(),
                player.getX(),
                player.getY(),
                player.getZ(),
                null,
                null
        );
        waypoints.put(normalizedId, waypoint);
        save(waypoint);
        updateSnapshot();
        return true;
    }

    /**
     * Removes a waypoint by id.
     *
     * @param id waypoint id
     * @return false when no waypoint has that id
     */
    public boolean remove(String id) {
        String normalizedId = normalizeId(id);
        if (normalizedId == null || waypoints.remove(normalizedId) == null) {
            return false;
        }
        config.set("waypoints." + normalizedId, null);
        config.save();
        updateSnapshot();
        return true;
    }

    /** Finds a waypoint by its normalized id. */
    public Waypoint get(String id) {
        String normalizedId = normalizeId(id);
        if (normalizedId == null) {
            return null;
        }
        for (Waypoint waypoint : snapshot) {
            if (waypoint.id().equals(normalizedId)) {
                return waypoint;
            }
        }
        return null;
    }

    /**
     * Replaces a waypoint icon and persists the change.
     *
     * @param id waypoint id
     * @param material Bukkit material name, or {@code null}
     * @param texture custom texture name without {@code .png}, or {@code null}
     * @return false when no waypoint has that id
     */
    public boolean setIcon(String id, String material, String texture) {
        String normalizedId = normalizeId(id);
        Waypoint current = normalizedId == null ? null : waypoints.get(normalizedId);
        if (current == null) {
            return false;
        }
        Waypoint updated = new Waypoint(
                current.id(),
                current.name(),
                current.worldName(),
                current.x(),
                current.y(),
                current.z(),
                material,
                texture
        );
        waypoints.put(normalizedId, updated);
        save(updated);
        updateSnapshot();
        return true;
    }

    /**
     * Finds the waypoint currently under the fixed canvas cursor.
     *
     * @param world rendered world
     * @param centerX current map center X
     * @param centerZ current map center Z
     * @param blocksPerPixel current map scale
     * @return hovered waypoint, or {@code null}
     */
    public Waypoint findHovered(
            World world,
            double centerX,
            double centerZ,
            double blocksPerPixel
    ) {
        Waypoint result = null;
        double closestDistance = Double.MAX_VALUE;
        for (Waypoint waypoint : snapshot) {
            if (!waypoint.worldName().equals(world.getName())) {
                continue;
            }
            double pixelX = MapOverlay.worldToCanvasX(
                    waypoint.x(),
                    centerX,
                    blocksPerPixel
            );
            double pixelY = MapOverlay.worldToCanvasY(
                    waypoint.z(),
                    centerZ,
                    blocksPerPixel
            );
            double dx = pixelX - CANVAS_CENTER_X;
            double dy = pixelY - CANVAS_CENTER_Y;
            double distance = dx * dx + dy * dy;
            if (distance <= HOVER_RADIUS_PIXELS * HOVER_RADIUS_PIXELS
                    && distance < closestDistance) {
                result = waypoint;
                closestDistance = distance;
            }
        }
        return result;
    }

    /** Writes one waypoint to the plugin configuration. */
    private void save(Waypoint waypoint) {
        String path = "waypoints." + waypoint.id();
        config.set(path + ".name", waypoint.name());
        config.set(path + ".world", waypoint.worldName());
        config.set(path + ".x", waypoint.x());
        config.set(path + ".y", waypoint.y());
        config.set(path + ".z", waypoint.z());
        config.set(path + ".icon.material", waypoint.iconMaterial());
        config.set(path + ".icon.texture", waypoint.iconTexture());
        config.save();
    }

    /** Publishes a stable snapshot for worker threads. */
    private void updateSnapshot() {
        snapshot = Collections.unmodifiableList(new ArrayList<>(waypoints.values()));
    }

    /** Normalizes and validates a command waypoint id. */
    private String normalizeId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_-]+") ? normalized : null;
    }
}
