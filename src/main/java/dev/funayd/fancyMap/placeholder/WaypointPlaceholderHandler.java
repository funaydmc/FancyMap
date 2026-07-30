package dev.funayd.fancyMap.placeholder;

import dev.funayd.fancyMap.waypoint.Waypoint;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/** Resolves waypoint display placeholders with an optional PlaceholderAPI bridge. */
public final class WaypointPlaceholderHandler implements FancyMapPlaceholderHandler {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final ThreadLocal<Waypoint> renderingWaypoint = new ThreadLocal<>();

    /** Creates a resolver for the owning plugin. */
    public WaypointPlaceholderHandler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Resolves FancyMap waypoint placeholders and delegates all placeholders to PlaceholderAPI when available. */
    public String resolve(Player player, Waypoint waypoint, String template) {
        renderingWaypoint.set(waypoint);
        try {
            if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                return PlaceholderApiBridge.resolve(player, template);
            }
            return fallback(template, waypoint);
        } finally {
            renderingWaypoint.remove();
        }
    }

    /** Resolves waypoint display parameters inside FancyMap's shared PlaceholderAPI expansion. */
    @Override
    public String resolve(OfflinePlayer player, String parameter) {
        Waypoint waypoint = renderingWaypoint.get();
        if (waypoint == null) {
            return null;
        }
        return switch (parameter.toLowerCase(Locale.ROOT)) {
            case "waypoint", "waypoint_id" -> waypoint.id();
            case "waypoint_name" -> waypoint.name();
            case "waypoint_world" -> waypoint.worldName();
            case "waypoint_x" -> coordinate(waypoint.x());
            case "waypoint_y" -> coordinate(waypoint.y());
            case "waypoint_z" -> coordinate(waypoint.z());
            case "waypoint_icon" -> icon(waypoint);
            default -> null;
        };
    }

    private String fallback(String template, Waypoint waypoint) {
        return template
                .replace("%fancymap_waypoint_id%", text(waypoint.id()))
                .replace("%fancymap_waypoint_name%", text(waypoint.name()))
                .replace("%fancymap_waypoint_world%", text(waypoint.worldName()))
                .replace("%fancymap_waypoint_x%", coordinate(waypoint.x()))
                .replace("%fancymap_waypoint_y%", coordinate(waypoint.y()))
                .replace("%fancymap_waypoint_z%", coordinate(waypoint.z()))
                .replace("%fancymap_waypoint_icon%", text(icon(waypoint)));
    }

    private String text(String value) {
        return MINI_MESSAGE.escapeTags(value == null ? "" : value);
    }

    private String coordinate(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String icon(Waypoint waypoint) {
        return waypoint.iconMaterial() != null ? waypoint.iconMaterial()
                : waypoint.iconTexture() == null ? "" : waypoint.iconTexture();
    }
}
