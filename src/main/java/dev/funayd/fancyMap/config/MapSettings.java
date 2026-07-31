package dev.funayd.fancyMap.config;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Defines FancyMap's map-specific numeric settings on the shared config registry. */
public final class MapSettings {
    private static final double DEFAULT_MAP_DISTANCE = 0.85D;
    private static final double DEFAULT_MAP_HORIZONTAL_OFFSET = -0.49D;
    private static final double DEFAULT_MAP_VERTICAL_OFFSET = 1.28D;
    private static final double DEFAULT_MAP_PAN_SPEED = 4.0D;
    private static final double DEFAULT_FAST_MOVE_MULTIPLIER = 2.0D;
    private static final double DEFAULT_MIN_ZOOM = 0.25D;
    private static final double DEFAULT_MAX_ZOOM = 4_096.0D;
    private static final double DEFAULT_DEFAULT_ZOOM = 1.0D;
    private static final String ROOT = "map.";
    private static final String LOAD_UNGENERATED_CHUNKS = "load-ungenerated-chunks";

    private final ConfigManager config;
    private final Map<String, Setting> settings = new LinkedHashMap<>();
    private final Map<String, String> aliases = new HashMap<>();

    /** Registers every map setting and its default value. */
    public MapSettings(ConfigManager config) {
        this.config = config;
        register("map-distance", DEFAULT_MAP_DISTANCE, true, "distance");
        register("map-horizontal-offset", DEFAULT_MAP_HORIZONTAL_OFFSET, false, "horizontal-offset");
        register("map-vertical-offset", DEFAULT_MAP_VERTICAL_OFFSET, false, "map-y-offset", "vertical-offset");
        register("map-pan-speed", DEFAULT_MAP_PAN_SPEED, true, "pan-speed", "cursor-speed");
        register("fast-move-multiplier", DEFAULT_FAST_MOVE_MULTIPLIER, true);
        register("min-zoom", DEFAULT_MIN_ZOOM, true, "map-min-zoom");
        register("max-zoom", DEFAULT_MAX_ZOOM, true, "map-max-zoom");
        register("default-zoom", DEFAULT_DEFAULT_ZOOM, true, "map-default-zoom");
        registerBoolean(LOAD_UNGENERATED_CHUNKS, false);
        normalizeZoomBounds();
    }

    /** Reapplies constraints after the shared config manager reloads YAML. */
    public void reload() {
        normalizeZoomBounds();
    }

    /** @return distance from the player to the map plane */
    public double getMapDistance() { return value("map-distance"); }

    /** @return horizontal player offset */
    public double getMapHorizontalOffset() { return value("map-horizontal-offset"); }

    /** @return vertical player offset */
    public double getVerticalOffset() { return value("map-vertical-offset"); }

    /** @return map movement distance per tick while a direction is held */
    public double getMapPanSpeed() { return value("map-pan-speed"); }

    /** @return pan speed multiplier while Shift and a movement key are held */
    public double getFastMoveMultiplier() { return value("fast-move-multiplier"); }

    /** @return minimum blocks-per-pixel zoom value */
    public double getMinZoom() { return value("min-zoom"); }

    /** @return maximum blocks-per-pixel zoom value */
    public double getMaxZoom() { return value("max-zoom"); }

    /** @return initial blocks-per-pixel zoom */
    public double getDefaultZoom() { return value("default-zoom"); }

    /** @return whether map rendering may generate chunks that do not exist yet */
    public boolean loadUngeneratedChunks() {
        return config.getBoolean(path(LOAD_UNGENERATED_CHUNKS));
    }

    /** @return canonical map-setting keys for command completion */
    public List<String> keys() {
        List<String> keys = new ArrayList<>(settings.keySet());
        keys.add(LOAD_UNGENERATED_CHUNKS);
        return List.copyOf(keys);
    }

    /** Updates either a numeric setting or the chunk-generation toggle. */
    public boolean update(String key, String rawValue) {
        String canonical = aliases.get(normalize(key));
        if (LOAD_UNGENERATED_CHUNKS.equals(canonical)) {
            if (!"true".equalsIgnoreCase(rawValue) && !"false".equalsIgnoreCase(rawValue)) {
                return false;
            }
            return config.setBoolean(path(canonical), Boolean.parseBoolean(rawValue));
        }
        try {
            return update(key, Double.parseDouble(rawValue)) != null;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /** Updates one map setting after validating cross-setting zoom constraints. */
    public String update(String key, double value) {
        String canonical = aliases.get(normalize(key));
        Setting setting = canonical == null ? null : settings.get(canonical);
        if (setting == null || !Double.isFinite(value)
                || (setting.positive() && value <= 0.0D) || !validZoom(canonical, value)) {
            return null;
        }
        return config.setDouble(path(canonical), value) ? canonical : null;
    }

    private void register(String key, double defaultValue, boolean positive, String... keyAliases) {
        String canonical = normalize(key);
        settings.put(canonical, new Setting(positive));
        aliases.put(canonical, canonical);
        for (String alias : keyAliases) {
            aliases.put(normalize(alias), canonical);
        }
        config.migrate("lockview." + canonical, path(canonical));
        config.registerDouble(path(canonical), defaultValue, positive);
    }

    private void registerBoolean(String key, boolean defaultValue) {
        String canonical = normalize(key);
        aliases.put(canonical, canonical);
        config.registerBoolean(path(canonical), defaultValue);
    }

    private boolean validZoom(String key, double value) {
        return switch (key) {
            case "min-zoom" -> value <= getMaxZoom() && value <= getDefaultZoom();
            case "max-zoom" -> value >= getMinZoom() && value >= getDefaultZoom();
            case "default-zoom" -> value >= getMinZoom() && value <= getMaxZoom();
            default -> true;
        };
    }

    private void normalizeZoomBounds() {
        if (getMinZoom() > getMaxZoom()) {
            config.setDouble(path("min-zoom"), DEFAULT_MIN_ZOOM);
            config.setDouble(path("max-zoom"), DEFAULT_MAX_ZOOM);
        }
        double normalized = Math.max(getMinZoom(), Math.min(getMaxZoom(), getDefaultZoom()));
        if (normalized != getDefaultZoom()) {
            config.setDouble(path("default-zoom"), normalized);
        }
        config.save();
    }

    private double value(String key) { return config.getDouble(path(key)); }

    private String path(String key) { return ROOT + key; }

    private String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private record Setting(boolean positive) {
    }
}
