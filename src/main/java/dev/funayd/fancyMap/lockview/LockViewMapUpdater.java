package dev.funayd.fancyMap.lockview;

import dev.funayd.fancyMap.FancyMapMessages;
import dev.funayd.fancyMap.map.MapConfig;
import dev.funayd.fancyMap.map.MapOverlay;
import dev.funayd.fancyMap.map.PersistentChunkRenderCache;
import dev.funayd.fancyMap.map.WorldMapRenderer;
import dev.funayd.fancyMap.texture.TextureManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Owns map pan/zoom state transitions and progressive render scheduling.
 */
final class LockViewMapUpdater {
    private static final double MAP_ZOOM_FACTOR = 1.25D;
    private static final long SNAPSHOT_REFRESH_INTERVAL_TICKS = 5L;

    private final JavaPlugin plugin;
    private final MapOverlay mapOverlay;
    private final MapConfig mapConfig;
    private final PersistentChunkRenderCache renderCache;
    private final TextureManager textureManager;
    private final BooleanSupplier debugEnabled;

    /**
     * Creates a map update coordinator.
     *
     * @param plugin owning plugin
     * @param mapOverlay overlay packet manager
     * @param renderCache persistent render cache
     * @param debugEnabled debug state supplier
     */
    LockViewMapUpdater(
            JavaPlugin plugin,
            MapOverlay mapOverlay,
            MapConfig mapConfig,
            PersistentChunkRenderCache renderCache,
            TextureManager textureManager,
            BooleanSupplier debugEnabled
    ) {
        this.plugin = plugin;
        this.mapOverlay = mapOverlay;
        this.mapConfig = mapConfig;
        this.renderCache = renderCache;
        this.textureManager = textureManager;
        this.debugEnabled = debugEnabled;
    }

    /**
     * Applies input and schedules all required progressive map refreshes.
     *
     * @param states active lock states
     * @param tickCounter current controller tick
     */
    void update(Map<UUID, LockViewState> states, long tickCounter) {
        for (Map.Entry<UUID, LockViewState> entry : states.entrySet()) {
            LockViewState state = entry.getValue();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }

            boolean changed = false;
            if (tickCounter >= state.nextSnapshotRefreshTick
                    && state.snapshotVersion() > state.lastRenderedSnapshotVersion) {
                changed = true;
                state.nextSnapshotRefreshTick = tickCounter
                        + SNAPSHOT_REFRESH_INTERVAL_TICKS;
            }

            MovementInput input = state.currentMovement;
            if (input != null && !input.isIdle()) {
                double step = state.blocksPerPixel * mapConfig.getMapPanSpeed();
                state.mapCenterX = Math.rint(state.mapCenterX
                        + (input.left() ? step : 0.0D)
                        - (input.right() ? step : 0.0D));
                state.mapCenterZ = Math.rint(state.mapCenterZ
                        + (input.forward() ? step : 0.0D)
                        - (input.backward() ? step : 0.0D));
                changed = input.forward() || input.backward()
                        || input.left() || input.right();
            }

            Integer zoomDelta;
            while ((zoomDelta = state.zoomInput.poll()) != null) {
                double previous = state.blocksPerPixel;
                state.blocksPerPixel = zoomDelta < 0
                        ? Math.max(mapConfig.getMinZoom(),
                        state.blocksPerPixel / MAP_ZOOM_FACTOR)
                        : Math.min(mapConfig.getMaxZoom(),
                        state.blocksPerPixel * MAP_ZOOM_FACTOR);
                changed |= previous != state.blocksPerPixel;
            }

            if (changed && !state.mapRenderPending) {
                state.mapRenderPending = true;
                state.renderSnapshotVersion = state.snapshotVersion();
                state.renderStartedAtNanos = System.nanoTime();
                mapOverlay.refreshAsync(
                        player,
                        createRenderer(state),
                        () -> {
                            recordRender(player, state);
                            state.lastRenderedSnapshotVersion =
                                    state.renderSnapshotVersion;
                            state.mapRenderPending = false;
                        }
                );
            }
        }
    }

    /**
     * Records render duration and emits periodic debug metrics.
     *
     * @param player target player
     * @param state active map state
     */
    void recordRender(Player player, LockViewState state) {
        if (state.renderStartedAtNanos == 0L) {
            return;
        }
        long elapsedNanos = System.nanoTime() - state.renderStartedAtNanos;
        state.renderStartedAtNanos = 0L;
        state.renderSamples++;
        state.totalRenderNanos += elapsedNanos;
        boolean slowRender = elapsedNanos >= 100_000_000L;
        if (!debugEnabled.getAsBoolean()
                || (!slowRender && state.renderSamples % 10 != 1)) {
            return;
        }

        double lastMillis = elapsedNanos / 1_000_000.0D;
        double averageMillis = state.totalRenderNanos
                / (double) state.renderSamples / 1_000_000.0D;
        Runtime runtime = Runtime.getRuntime();
        long usedHeapMb = (runtime.totalMemory() - runtime.freeMemory())
                / (1024L * 1024L);
        long maxHeapMb = runtime.maxMemory() / (1024L * 1024L);
        player.sendMessage(FancyMapMessages.debug(String.format(
                Locale.ROOT,
                "Map render: %.1f ms | avg: %.1f ms | samples: %d | heap: %d/%d MB",
                lastMillis,
                averageMillis,
                state.renderSamples,
                usedHeapMb,
                maxHeapMb
        )));
        plugin.getLogger().info(FancyMapMessages.consoleDebug(
                "player=" + player.getName()
                        + " renderLastMs=" + String.format(
                        Locale.ROOT,
                        "%.1f",
                        lastMillis
                )
                        + " renderAvgMs=" + String.format(
                        Locale.ROOT,
                        "%.1f",
                        averageMillis
                )
                        + " samples=" + state.renderSamples
                        + " heapMb=" + usedHeapMb + "/" + maxHeapMb
                        + " hiddenEntities=" + state.hiddenEntities.size()
                        + " hiddenPlayers=" + state.hiddenPlayers.size()
                        + " " + state.mapSnapshotStore.debugSummary()
                        + " " + renderCache.debugSummary(state.world)
        ));
    }

    /**
     * Creates a renderer for a session's current viewport.
     *
     * @param state active map state
     * @return configured renderer
     */
    WorldMapRenderer createRenderer(LockViewState state) {
        return new WorldMapRenderer(
                plugin,
                state.world,
                state.mapCenterX,
                state.mapCenterZ,
                state.blocksPerPixel,
                MapOverlay.CANVAS_WIDTH,
                MapOverlay.CANVAS_HEIGHT,
                state.mapSnapshotStore,
                renderCache,
                state.anchor.getX(),
                state.anchor.getZ(),
                textureManager.cursor(),
                textureManager.player()
        );
    }
}
