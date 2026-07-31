package dev.funayd.fancyMap.config;

/**
 * Typed limits for the persistent map level-of-detail pyramid.
 */
public final class MapLodSettings {
    private static final String ROOT = "map-lod.";
    private static final int MAX_SUPPORTED_LEVEL = 22;

    private final ConfigManager config;

    /** Registers the highest overview level retained on disk. */
    public MapLodSettings(ConfigManager config) {
        this.config = config;
        config.registerInteger(path("max-level"), 12);
    }

    /**
     * Returns the configured overview depth, capped at the Minecraft world-border limit.
     *
     * @return highest LOD level, where level zero is one chunk
     */
    public int maxLevel() {
        return Math.min(MAX_SUPPORTED_LEVEL, config.getInteger(path("max-level")));
    }

    private String path(String key) {
        return ROOT + key;
    }
}
