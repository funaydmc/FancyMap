package dev.funayd.fancyMap.lockview;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import dev.funayd.fancyMap.FancyMapMessages;
import dev.funayd.fancyMap.config.MapSettings;
import dev.funayd.fancyMap.config.WaypointDisplaySettings;
import dev.funayd.fancyMap.input.MovementInput;
import dev.funayd.fancyMap.map.ClientCanvasDisplayHelper;
import dev.funayd.fancyMap.map.MapOverlay;
import dev.funayd.fancyMap.map.PersistentChunkRenderCache;
import dev.funayd.fancyMap.map.WorldMapRenderer;
import dev.funayd.fancyMap.texture.TextureManager;
import dev.funayd.fancyMap.waypoint.Waypoint;
import dev.funayd.fancyMap.waypoint.WaypointManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.Objects;
import java.util.Set;

/**
 * Owns map pan/zoom state transitions and progressive render scheduling.
 */
final class LockViewMapUpdater {
    private static final double MAP_ZOOM_FACTOR = 1.25D;
    private static final double WAYPOINT_TOOLTIP_OFFSET_X = 16.0D;
    private static final long SNAPSHOT_REFRESH_INTERVAL_TICKS = 5L;

    private final JavaPlugin plugin;
    private final MapOverlay mapOverlay;
    private final MapSettings mapSettings;
    private final PersistentChunkRenderCache renderCache;
    private final TextureManager textureManager;
    private final WaypointManager waypointManager;
    private final WaypointDisplaySettings waypointDisplaySettings;
    private final ClientCanvasDisplayHelper canvasDisplays;
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
            MapSettings mapSettings,
            PersistentChunkRenderCache renderCache,
            TextureManager textureManager,
            WaypointManager waypointManager,
            WaypointDisplaySettings waypointDisplaySettings,
            ClientCanvasDisplayHelper canvasDisplays,
            BooleanSupplier debugEnabled
    ) {
        this.plugin = plugin;
        this.mapOverlay = mapOverlay;
        this.mapSettings = mapSettings;
        this.renderCache = renderCache;
        this.textureManager = textureManager;
        this.waypointManager = waypointManager;
        this.waypointDisplaySettings = waypointDisplaySettings;
        this.canvasDisplays = canvasDisplays;
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

            boolean changed = state.focusRequested;
            if (tickCounter >= state.nextSnapshotRefreshTick
                    && state.snapshotVersion() > state.lastRenderedSnapshotVersion) {
                changed = true;
                state.nextSnapshotRefreshTick = tickCounter
                        + SNAPSHOT_REFRESH_INTERVAL_TICKS;
            }

            MovementInput input = state.currentMovement;
            if (input != null && !input.isIdle()) {
                double step = state.blocksPerPixel * mapSettings.getMapPanSpeed()
                        * (input.shift() ? mapSettings.getFastMoveMultiplier() : 1.0D);
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
                        ? Math.max(mapSettings.getMinZoom(),
                        state.blocksPerPixel / MAP_ZOOM_FACTOR)
                        : Math.min(mapSettings.getMaxZoom(),
                        state.blocksPerPixel * MAP_ZOOM_FACTOR);
                changed |= previous != state.blocksPerPixel;
            }

            Waypoint hoveredWaypoint = waypointManager.findHovered(
                    state.world,
                    state.mapCenterX,
                    state.mapCenterZ,
                    state.blocksPerPixel
            );
            String hoveredId = hoveredWaypoint == null ? null : hoveredWaypoint.id();
            boolean waypointChanged = !Objects.equals(state.hoveredWaypointId, hoveredId);
            if (waypointChanged) {
                state.hoveredWaypointId = hoveredId;
                changed = true;
            }
            if (waypointChanged) {
                updateTooltip(player, hoveredWaypoint, state);
            }

            if (changed && !state.mapRenderPending) {
                state.mapRenderPending = true;
                state.focusRequested = false;
                state.renderSnapshotVersion = state.snapshotVersion();
                state.renderStartedAtNanos = System.nanoTime();
                double renderedCenterX = state.mapCenterX;
                double renderedCenterZ = state.mapCenterZ;
                double renderedBlocksPerPixel = state.blocksPerPixel;
                mapOverlay.refreshAsync(
                        player,
                        createRenderer(state),
                        () -> {
                            recordRender(player, state);
                            if (states.get(player.getUniqueId()) == state
                                    && player.isOnline()) {
                                updateItemDisplaysIfViewportChanged(
                                        player, state, renderedCenterX,
                                        renderedCenterZ, renderedBlocksPerPixel
                                );
                            }
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
                textureManager.player(),
                waypointManager.all(),
                state.hoveredWaypointId,
                textureManager.waypoint(),
                textureManager.waypointHover(),
                textureManager.customTextures(),
                debugEnabled.getAsBoolean()
        );
    }

    /**
     * Reconciles client-only ItemDisplays with one rendered viewport.
     *
     * @param player target player
     * @param state active map state
     * @param centerX rendered map center X
     * @param centerZ rendered map center Z
     * @param blocksPerPixel rendered map scale
     */
    void updateItemDisplays(
            Player player,
            LockViewState state,
            double centerX,
            double centerZ,
            double blocksPerPixel
    ) {
        Set<String> visible = new HashSet<>();
        for (Waypoint waypoint : waypointManager.all()) {
            if (!waypoint.worldName().equals(state.world.getName())
                    || waypoint.iconMaterial() == null) {
                continue;
            }
            double canvasX = MapOverlay.worldToCanvasX(
                    waypoint.x(),
                    centerX,
                    blocksPerPixel
            );
            double canvasY = MapOverlay.worldToCanvasY(
                    waypoint.z(),
                    centerZ,
                    blocksPerPixel
            );
            if (canvasX < 0.0D || canvasX >= MapOverlay.CANVAS_WIDTH
                    || canvasY < 0.0D || canvasY >= MapOverlay.CANVAS_HEIGHT) {
                continue;
            }
            Material material = Material.matchMaterial(waypoint.iconMaterial());
            if (material == null || !material.isItem()) {
                continue;
            }
            String key = "waypoint-item-" + waypoint.id();
            visible.add(key);
            canvasDisplays.showItem(
                    player,
                    key,
                    canvasX,
                    canvasY,
                    SpigotConversionUtil.fromBukkitItemStack(new ItemStack(material)),
                    0.5D
            );
        }
        for (String key : state.visibleWaypointItemDisplays) {
            if (!visible.contains(key)) {
                canvasDisplays.hide(player, key);
            }
        }
        state.visibleWaypointItemDisplays.clear();
        state.visibleWaypointItemDisplays.addAll(visible);
    }

    /** Avoids resending ItemDisplay packets for progressive chunk-only refreshes. */
    private void updateItemDisplaysIfViewportChanged(
            Player player,
            LockViewState state,
            double centerX,
            double centerZ,
            double blocksPerPixel
    ) {
        if (centerX == state.itemDisplayCenterX
                && centerZ == state.itemDisplayCenterZ
                && blocksPerPixel == state.itemDisplayBlocksPerPixel) {
            return;
        }
        updateItemDisplays(player, state, centerX, centerZ, blocksPerPixel);
        state.itemDisplayCenterX = centerX;
        state.itemDisplayCenterZ = centerZ;
        state.itemDisplayBlocksPerPixel = blocksPerPixel;
    }

    /** Shows or hides the tooltip for the waypoint under the cursor. */
    private void updateTooltip(Player player, Waypoint waypoint, LockViewState state) {
        if (waypoint == null) {
            canvasDisplays.hide(player, "waypoint-tooltip");
            return;
        }
        canvasDisplays.showText(
                player,
                "waypoint-tooltip",
                MapOverlay.canvasXForScreenRight(WAYPOINT_TOOLTIP_OFFSET_X),
                MapOverlay.canvasCenterY(),
                waypointDisplaySettings.tooltip(player, waypoint),
                1.0D,
                ClientCanvasDisplayHelper.TextAnchor.MIDDLE_LEFT,
                true
        );
    }
}
