package dev.funayd.fancyMap.config;

import dev.funayd.fancyMap.placeholder.WaypointPlaceholderHandler;
import dev.funayd.fancyMap.waypoint.Waypoint;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Configurable text templates for waypoint tooltips and list entries.
 */
public final class WaypointDisplaySettings {
    private static final String ROOT = "waypoint-display.";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ConfigManager config;
    private final WaypointPlaceholderHandler placeholders;

    /** Registers all waypoint display templates and safe defaults. */
    public WaypointDisplaySettings(
            ConfigManager config,
            WaypointPlaceholderHandler placeholders
    ) {
        this.config = config;
        this.placeholders = placeholders;
        config.registerString(
                path("tooltip"),
                "<yellow>%fancymap_waypoint_name%<newline><white>Press <lime>F <white>to teleport"
        );
        config.registerString(path("list.name"), "<yellow>%fancymap_waypoint_name%");
        config.registerStringList(path("list.lore"), List.of(
                "<gray>ID: <white>%fancymap_waypoint_id%",
                "<gray>World: <white>%fancymap_waypoint_world%",
                "<gray>X: <white>%fancymap_waypoint_x% <gray>Y: <white>%fancymap_waypoint_y% <gray>Z: <white>%fancymap_waypoint_z%",
                "",
                "<green>Click to view on map"
        ));
    }

    /** Renders the configured map tooltip for one hovered waypoint. */
    public Component tooltip(Player player, Waypoint waypoint) {
        return component(player, waypoint, config.getString(path("tooltip")));
    }

    /** Renders the configured GUI item name for one waypoint. */
    public Component listName(Player player, Waypoint waypoint) {
        return component(player, waypoint, config.getString(path("list.name")));
    }

    /** Renders the configured GUI lore for one waypoint. */
    public List<Component> listLore(Player player, Waypoint waypoint) {
        return config.getStringList(path("list.lore")).stream()
                .map(line -> component(player, waypoint, line))
                .toList();
    }

    /** Resolves PlaceholderAPI tokens, then MiniMessage tags. */
    private Component component(Player player, Waypoint waypoint, String template) {
        return MINI_MESSAGE.deserialize(placeholders.resolve(player, waypoint, template));
    }

    /** Builds one config path inside the waypoint display section. */
    private String path(String key) {
        return ROOT + key;
    }
}
