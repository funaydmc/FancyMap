package dev.funayd.fancyMap.config;

/**
 * Typed configuration for the global map chunk scheduler.
 */
public final class ChunkSchedulerSettings {
    private static final String ROOT = "chunk-scheduler.";

    private final ConfigManager config;

    /** Registers global scheduler limits and their safe defaults. */
    public ChunkSchedulerSettings(ConfigManager config) {
        this.config = config;
        config.registerInteger(path("requests-per-tick"), 4);
        config.registerInteger(path("max-in-flight-requests"), 16);
        config.registerInteger(path("snapshot-workers"), 2);
        config.registerInteger(path("max-candidate-scans-per-tick"), 512);
        config.registerInteger(path("max-retries"), 3);
    }

    /** @return global chunk-load starts permitted per server tick */
    public int requestsPerTick() {
        return config.getInteger(path("requests-per-tick"));
    }

    /** @return maximum combined loading and snapshot-processing jobs */
    public int maxInFlightRequests() {
        return config.getInteger(path("max-in-flight-requests"));
    }

    /** @return bounded number of asynchronous snapshot conversion workers */
    public int snapshotWorkers() {
        return config.getInteger(path("snapshot-workers"));
    }

    /** @return maximum cached/duplicate candidates inspected each scheduler tick */
    public int maxCandidateScansPerTick() {
        return config.getInteger(path("max-candidate-scans-per-tick"));
    }

    /** @return retry attempts for one failed chunk load */
    public int maxRetries() {
        return config.getInteger(path("max-retries"));
    }

    /** Builds a full YAML path for this config section. */
    private String path(String key) {
        return ROOT + key;
    }
}
