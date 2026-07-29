package dev.funayd.fancyMap.lockview;

import dev.funayd.fancyMap.map.AsyncChunkSnapshotStore;
import dev.funayd.fancyMap.map.PersistentChunkRenderCache;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mutable per-player state for one active FancyMap session.
 */
final class LockViewState {
    /** Client-only camera entity id. */
    final int cameraEntityId;
    /** Position to restore when the session ends. */
    final Location anchor;
    /** Fixed camera yaw. */
    final float lockedYaw;
    /** Fixed camera pitch. */
    final float lockedPitch;
    /** World rendered by the map. */
    final World world;
    /** Player invulnerability state before locking. */
    final boolean originalInvulnerable;
    /** Player-specific weather before locking. */
    final WeatherType originalPlayerWeather;
    /** Client game mode before locking. */
    final GameMode originalGameMode;
    /** Client-side blocks temporarily cleared around the player. */
    final List<Location> clientAirBlocks = new ArrayList<>();
    /** Client-side blocks temporarily replaced by the outer concrete box. */
    final List<Location> clientConcreteBlocks = new ArrayList<>();
    /** Weak entity set so removed entities cannot be retained by a long session. */
    final Set<Entity> hiddenEntities = Collections.newSetFromMap(new WeakHashMap<>());
    /** Players hidden from this viewer. */
    final Set<UUID> hiddenPlayers = new HashSet<>();
    /** Client-only waypoint ItemDisplay keys currently visible on the canvas. */
    final Set<String> visibleWaypointItemDisplays = new HashSet<>();
    /** Snapshot store for this session. */
    final AsyncChunkSnapshotStore mapSnapshotStore;
    /** Latest movement packets received from the client. */
    final ConcurrentLinkedQueue<MovementInput> movementInput =
            new ConcurrentLinkedQueue<>();
    /** Pending hotbar zoom input. */
    final ConcurrentLinkedQueue<Integer> zoomInput =
            new ConcurrentLinkedQueue<>();
    /** Whether a map render is currently being sent. */
    boolean mapRenderPending = true;
    /** Whether the map must render after a command-selected center change. */
    boolean focusRequested;
    /** Snapshot version represented by the last completed render. */
    long lastRenderedSnapshotVersion;
    /** Snapshot version captured when the current render started. */
    long renderSnapshotVersion;
    /** Next tick at which progressive refresh may run. */
    long nextSnapshotRefreshTick;
    /** Start time for the current render metric. */
    long renderStartedAtNanos;
    /** Total render duration accumulated for debug metrics. */
    long totalRenderNanos;
    /** Number of render samples collected. */
    int renderSamples;
    /** Last movement state applied by the tick loop. */
    MovementInput currentMovement;
    /** Whether Space was held in the last received input packet. */
    volatile boolean jumpHeld;
    /** Current map center X coordinate. */
    double mapCenterX;
    /** Current map center Z coordinate. */
    double mapCenterZ;
    /** Current blocks-per-pixel zoom. */
    double blocksPerPixel;
    /** Waypoint currently under the fixed canvas cursor. */
    volatile String hoveredWaypointId;
    /** Last server hotbar slot acknowledged to the client. */
    volatile int lastHotbarSlot;

    /**
     * Creates a new session state and its bounded snapshot store.
     *
     * @param cameraEntityId client-only camera entity id
     * @param anchor original player location
     * @param lockedYaw fixed camera yaw
     * @param lockedPitch fixed camera pitch
     * @param world map world
     * @param mapCenterX initial map center X
     * @param mapCenterZ initial map center Z
     * @param lastHotbarSlot current server hotbar slot
     * @param initialZoom initial blocks-per-pixel zoom
     * @param originalInvulnerable original invulnerability state
     * @param originalPlayerWeather original per-player weather override
     * @param originalGameMode original server game mode
     * @param plugin owning plugin
     * @param renderCache persistent render cache
     */
    LockViewState(
            int cameraEntityId,
            Location anchor,
            float lockedYaw,
            float lockedPitch,
            World world,
            double mapCenterX,
            double mapCenterZ,
            int lastHotbarSlot,
            double initialZoom,
            boolean originalInvulnerable,
            WeatherType originalPlayerWeather,
            GameMode originalGameMode,
            JavaPlugin plugin,
            PersistentChunkRenderCache renderCache
    ) {
        this.cameraEntityId = cameraEntityId;
        this.anchor = anchor;
        this.lockedYaw = lockedYaw;
        this.lockedPitch = lockedPitch;
        this.world = world;
        this.mapCenterX = mapCenterX;
        this.mapCenterZ = mapCenterZ;
        this.lastHotbarSlot = lastHotbarSlot;
        this.blocksPerPixel = initialZoom;
        this.originalInvulnerable = originalInvulnerable;
        this.originalPlayerWeather = originalPlayerWeather;
        this.originalGameMode = originalGameMode;
        this.mapSnapshotStore = new AsyncChunkSnapshotStore(plugin, world, renderCache);
    }

    /**
     * Returns the snapshot version currently available to this session.
     *
     * @return monotonically increasing snapshot version
     */
    long snapshotVersion() {
        return mapSnapshotStore.snapshotVersion();
    }
}
