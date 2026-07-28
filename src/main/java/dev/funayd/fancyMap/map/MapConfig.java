package dev.funayd.fancyMap.map;

import org.bukkit.plugin.java.JavaPlugin;

public final class MapConfig {
    private static final double DEFAULT_MAP_DISTANCE = 0.85D;
    private static final double DEFAULT_MAP_HORIZONTAL_OFFSET = -0.49D;
    private static final double DEFAULT_MAP_VERTICAL_OFFSET = 1.28D;

    private final JavaPlugin plugin;
    private double mapDistance;
    private double mapHorizontalOffset;
    private double verticalOffset;

    public MapConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        mapDistance = getDouble("map.map-distance", "lockview.map-distance", DEFAULT_MAP_DISTANCE);
        mapHorizontalOffset = getDouble(
                "map.map-horizontal-offset",
                "lockview.map-horizontal-offset",
                DEFAULT_MAP_HORIZONTAL_OFFSET
        );
        verticalOffset = getDouble(
                "map.map-vertical-offset",
                "lockview.map-vertical-offset",
                DEFAULT_MAP_VERTICAL_OFFSET
        );
    }

    public double getMapDistance() {
        return mapDistance;
    }

    public double getMapHorizontalOffset() {
        return mapHorizontalOffset;
    }

    public double getVerticalOffset() {
        return verticalOffset;
    }

    public String update(String key, double value) {
        if (!Double.isFinite(value)) {
            return null;
        }

        String normalized = key.toLowerCase().replace('_', '-');
        String path;
        switch (normalized) {
            case "map-distance", "distance" -> {
                if (value <= 0.0D) {
                    return null;
                }
                mapDistance = value;
                path = "map.map-distance";
            }
            case "map-horizontal-offset", "horizontal-offset" -> {
                mapHorizontalOffset = value;
                path = "map.map-horizontal-offset";
            }
            case "map-vertical-offset", "map-y-offset", "vertical-offset" -> {
                verticalOffset = value;
                path = "map.map-vertical-offset";
            }
            default -> {
                return null;
            }
        }

        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        return path.substring("map.".length());
    }

    private double getDouble(String path, String legacyPath, double defaultValue) {
        if (plugin.getConfig().contains(path)) {
            return plugin.getConfig().getDouble(path);
        }
        return plugin.getConfig().getDouble(legacyPath, defaultValue);
    }
}
