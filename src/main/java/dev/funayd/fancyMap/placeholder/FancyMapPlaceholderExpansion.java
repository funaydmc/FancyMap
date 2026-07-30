package dev.funayd.fancyMap.placeholder;

import dev.funayd.fancyMap.lockview.LockViewController;
import dev.funayd.fancyMap.waypoint.Waypoint;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PlaceholderAPI expansion for FancyMap player state.
 */
public final class FancyMapPlaceholderExpansion extends PlaceholderExpansion {
    private final LockViewController lockViewController;
    private final List<FancyMapPlaceholderHandler> handlers = new ArrayList<>();

    /**
     * Creates the FancyMap PlaceholderAPI expansion.
     *
     * @param lockViewController controller that owns active map sessions
     */
    public FancyMapPlaceholderExpansion(LockViewController lockViewController) {
        this.lockViewController = lockViewController;
    }

    /** @return expansion identifier used by the {@code fancymap} namespace */
    @Override
    public String getIdentifier() {
        return "fancymap";
    }

    /** @return expansion author */
    @Override
    public String getAuthor() {
        return "funaydd";
    }

    /** @return plugin version */
    @Override
    public String getVersion() {
        return "1.0";
    }

    /** @return true so PlaceholderAPI keeps this expansion registered */
    @Override
    public boolean persist() {
        return true;
    }

    /** Adds a handler to this expansion without creating another PlaceholderAPI registration. */
    public void registerHandler(FancyMapPlaceholderHandler handler) {
        handlers.add(handler);
    }

    /**
     * Resolves FancyMap placeholders.
     *
     * @param player player requesting the value
     * @param params placeholder name without the namespace
     * @return a FancyMap placeholder value, or {@code null} for an unknown key
     */
    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return null;
        }
        if (params.equalsIgnoreCase("open")) {
            return Boolean.toString(lockViewController.isMapOpen(player.getUniqueId()));
        }
        for (FancyMapPlaceholderHandler handler : handlers) {
            String value = handler.resolve(player, params);
            if (value != null) {
                return value;
            }
        }
        Waypoint hoveredWaypoint = lockViewController.hoveringWaypoint(player.getUniqueId());
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "hovering_waypoint", "hovering_waypoint_id" -> id(hoveredWaypoint);
            case "hovering_waypoint_name" -> name(hoveredWaypoint);
            case "hovering_waypoint_world" -> world(hoveredWaypoint);
            case "hovering_waypoint_x" -> coordinate(hoveredWaypoint, Axis.X);
            case "hovering_waypoint_y" -> coordinate(hoveredWaypoint, Axis.Y);
            case "hovering_waypoint_z" -> coordinate(hoveredWaypoint, Axis.Z);
            default -> null;
        };
    }

    /** Formats a coordinate exposed through PlaceholderAPI. */
    private String coordinate(Waypoint waypoint, Axis axis) {
        if (waypoint == null) {
            return "";
        }
        double value = switch (axis) {
            case X -> waypoint.x();
            case Y -> waypoint.y();
            case Z -> waypoint.z();
        };
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String id(Waypoint waypoint) {
        return waypoint == null ? "" : waypoint.id();
    }

    private String name(Waypoint waypoint) {
        return waypoint == null ? "" : waypoint.name();
    }

    private String world(Waypoint waypoint) {
        return waypoint == null ? "" : waypoint.worldName();
    }

    private enum Axis {
        X, Y, Z
    }
}
