package dev.funayd.fancyMap.map;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads, validates and persists FancyMap placement settings.
 */
public final class MapConfig {
    private static final double DEFAULT_MAP_DISTANCE = 0.85D;
    private static final double DEFAULT_MAP_HORIZONTAL_OFFSET = -0.49D;
    private static final double DEFAULT_MAP_VERTICAL_OFFSET = 1.28D;
    private static final double DEFAULT_MAP_PAN_SPEED = 4.0D;
    private static final double DEFAULT_MIN_ZOOM = 0.25D;
    private static final double DEFAULT_MAX_ZOOM = 4.0D;
    private static final double DEFAULT_DEFAULT_ZOOM = 1.0D;

    private final JavaPlugin plugin;
    private final Map<String, DoubleSetting> settings = new LinkedHashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, Double> values = new HashMap<>();
    private boolean initialized;
    private boolean dirty;

    /**
     * Loads configuration values from the plugin configuration.
     *
     * @param plugin owning plugin
     */
    public MapConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        registerDouble("map-distance", DEFAULT_MAP_DISTANCE, true, "distance");
        registerDouble(
                "map-horizontal-offset",
                DEFAULT_MAP_HORIZONTAL_OFFSET,
                false,
                "horizontal-offset"
        );
        registerDouble(
                "map-vertical-offset",
                DEFAULT_MAP_VERTICAL_OFFSET,
                false,
                "map-y-offset",
                "vertical-offset"
        );
        registerDouble("map-pan-speed", DEFAULT_MAP_PAN_SPEED, true, "pan-speed", "cursor-speed");
        registerDouble("min-zoom", DEFAULT_MIN_ZOOM, true, "map-min-zoom");
        registerDouble("max-zoom", DEFAULT_MAX_ZOOM, true, "map-max-zoom");
        registerDouble("default-zoom", DEFAULT_DEFAULT_ZOOM, true, "map-default-zoom");
        normalizeZoomBounds();
        initialized = true;
        saveIfDirty();
    }

    /**
     * Registers a numeric map setting and loads it from YAML when available.
     * Missing settings are written using their default value.
     *
     * @param key canonical config key without the {@code map.} prefix
     * @param defaultValue value used when the key is missing or invalid
     * @param positive whether zero and negative values are rejected
     * @param keyAliases command aliases for the setting
     */
    public void registerDouble(
            String key,
            double defaultValue,
            boolean positive,
            String... keyAliases
    ) {
        String canonical = normalize(key);
        if (settings.containsKey(canonical)) {
            throw new IllegalArgumentException("Config key already registered: " + canonical);
        }
        if (!isValid(defaultValue, positive)) {
            throw new IllegalArgumentException("Invalid default config value: " + canonical);
        }

        settings.put(canonical, new DoubleSetting(defaultValue, positive));
        aliases.put(canonical, canonical);
        for (String keyAlias : keyAliases) {
            aliases.put(normalize(keyAlias), canonical);
        }

        values.put(canonical, readValue(canonical, settings.get(canonical)));
        saveIfDirty();
    }

    /** Reloads all currently registered values from the active Bukkit config. */
    public void reload() {
        dirty = false;
        for (Map.Entry<String, DoubleSetting> entry : settings.entrySet()) {
            values.put(entry.getKey(), readValue(entry.getKey(), entry.getValue()));
        }
        normalizeZoomBounds();
        saveIfDirty();
    }

    /** @return distance from the player to the map plane */
    public double getMapDistance() {
        return getDouble("map-distance");
    }

    /** @return horizontal player offset */
    public double getMapHorizontalOffset() {
        return getDouble("map-horizontal-offset");
    }

    /** @return vertical player offset */
    public double getVerticalOffset() {
        return getDouble("map-vertical-offset");
    }

    /** @return map movement distance per tick while a direction is held */
    public double getMapPanSpeed() {
        return getDouble("map-pan-speed");
    }

    /** @return minimum blocks-per-pixel zoom value */
    public double getMinZoom() {
        return getDouble("min-zoom");
    }

    /** @return maximum blocks-per-pixel zoom value */
    public double getMaxZoom() {
        return getDouble("max-zoom");
    }

    /** @return initial blocks-per-pixel zoom for new map sessions */
    public double getDefaultZoom() {
        return getDouble("default-zoom");
    }

    /**
     * Returns a registered setting value by canonical key or alias.
     *
     * @param key setting key
     * @return current numeric value
     */
    public double getDouble(String key) {
        String canonical = aliases.get(normalize(key));
        if (canonical == null) {
            throw new IllegalArgumentException("Unknown config key: " + key);
        }
        return values.get(canonical);
    }

    /**
     * Returns canonical keys for command completion.
     *
     * @return registered setting keys in registration order
     */
    public List<String> keys() {
        return List.copyOf(settings.keySet());
    }

    /**
     * Updates a supported key and persists it immediately.
     *
     * @param key configuration key
     * @param value new value
     * @return canonical key, or {@code null} when unsupported
     */
    public String update(String key, double value) {
        if (!Double.isFinite(value)) {
            return null;
        }

        String canonical = aliases.get(normalize(key));
        if (canonical == null) {
            return null;
        }
        DoubleSetting setting = settings.get(canonical);
        if (!isValid(value, setting.positive())) {
            return null;
        }
        if (canonical.equals("min-zoom") && value > getMaxZoom()) {
            return null;
        }
        if (canonical.equals("min-zoom") && value > getDefaultZoom()) {
            return null;
        }
        if (canonical.equals("max-zoom")
                && (value < getMinZoom() || value < getDefaultZoom())) {
            return null;
        }
        if (canonical.equals("default-zoom")
                && (value < getMinZoom() || value > getMaxZoom())) {
            return null;
        }

        String path = "map." + canonical;
        values.put(canonical, value);
        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        return canonical;
    }

    /** Normalizes config keys used by YAML and command input. */
    private String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Validates one registered numeric value. */
    private boolean isValid(double value, boolean positive) {
        return Double.isFinite(value) && (!positive || value > 0.0D);
    }

    /** Reads one setting from its current path or its legacy path. */
    private double readValue(String canonical, DoubleSetting setting) {
        String path = "map." + canonical;
        double value;
        if (plugin.getConfig().contains(path)) {
            value = plugin.getConfig().getDouble(path);
        } else {
            String legacyPath = "lockview." + canonical;
            value = plugin.getConfig().contains(legacyPath)
                    ? plugin.getConfig().getDouble(legacyPath)
                    : setting.defaultValue();
            plugin.getConfig().set(path, value);
            dirty = true;
        }
        if (!isValid(value, setting.positive())) {
            value = setting.defaultValue();
            plugin.getConfig().set(path, value);
            dirty = true;
        }
        return value;
    }

    /** Persists defaults added during dynamic registration. */
    private void saveIfDirty() {
        if (initialized && dirty) {
            plugin.saveConfig();
            dirty = false;
        }
    }

    /** Restores safe zoom bounds when a manually edited config is inverted. */
    private void normalizeZoomBounds() {
        double minZoom = values.get("min-zoom");
        double maxZoom = values.get("max-zoom");
        if (minZoom > maxZoom) {
            minZoom = DEFAULT_MIN_ZOOM;
            maxZoom = DEFAULT_MAX_ZOOM;
            values.put("min-zoom", minZoom);
            values.put("max-zoom", maxZoom);
            plugin.getConfig().set("map.min-zoom", minZoom);
            plugin.getConfig().set("map.max-zoom", maxZoom);
            dirty = true;
        }

        double defaultZoom = values.get("default-zoom");
        double normalizedDefault = Math.max(minZoom, Math.min(maxZoom, defaultZoom));
        if (defaultZoom != normalizedDefault) {
            values.put("default-zoom", normalizedDefault);
            plugin.getConfig().set("map.default-zoom", normalizedDefault);
            dirty = true;
        }
    }

    private record DoubleSetting(double defaultValue, boolean positive) {
    }
}
