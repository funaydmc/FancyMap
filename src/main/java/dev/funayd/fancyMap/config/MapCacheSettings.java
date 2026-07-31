package dev.funayd.fancyMap.config;

/**
 * Typed limits for the persistent rendered-chunk cache.
 */
public final class MapCacheSettings {
    private static final String ROOT = "map-cache.";

    private final ConfigManager config;

    /** Registers cache limits and their safe defaults. */
    public MapCacheSettings(ConfigManager config) {
        this.config = config;
        config.registerInteger(path("max-entries"), 131_072);
    }

    /** @return maximum rendered chunks retained per world */
    public int maxEntries() {
        return config.getInteger(path("max-entries"));
    }

    private String path(String key) {
        return ROOT + key;
    }
}
