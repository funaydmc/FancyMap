package dev.funayd.fancyMap.lockview;

import dev.funayd.fancyMap.FancyMapMessages;
import dev.funayd.fancyMap.map.MapConfig;
import dev.funayd.fancyMap.map.MapOverlay;
import dev.funayd.fancyMap.map.PersistentChunkRenderCache;
import dev.funayd.fancyMap.packet.PacketLocations;
import dev.funayd.fancyMap.texture.TextureManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates the player lock, client camera and map viewport lifecycle.
 */
public final class LockViewController {
    private static final int FIRST_CAMERA_ENTITY_ID = 2_000_000_000;
    private static final float LOCKED_YAW = 0.0F;
    private static final int CLIENT_SPECTATOR_GAME_MODE = 3;
    private final JavaPlugin plugin;
    private final PacketListenerCommon packetListenerRegistration;
    private final BukkitTask timerTask;
    private final MapConfig mapConfig;
    private final MapOverlay mapOverlay;
    private final PersistentChunkRenderCache mapRenderCache;
    private final TextureManager textureManager;
    private final LockViewVisibility visibility;
    private final LockViewMapUpdater mapUpdater;
    private final AtomicInteger nextCameraEntityId =
            new AtomicInteger(FIRST_CAMERA_ENTITY_ID);
    private final ConcurrentMap<UUID, LockViewState> states = new ConcurrentHashMap<>();
    private volatile boolean debugEnabled;
    private long tickCounter;

    /**
     * Creates the lock controller, registers its packet listener and starts its tick loop.
     *
     * @param plugin owning plugin
     */
    public LockViewController(JavaPlugin plugin) {
        this.plugin = plugin;
        mapConfig = new MapConfig(plugin);
        mapOverlay = new MapOverlay(plugin, () -> debugEnabled);
        mapRenderCache = new PersistentChunkRenderCache(plugin);
        textureManager = new TextureManager(plugin);
        visibility = new LockViewVisibility(plugin, states);
        mapUpdater = new LockViewMapUpdater(
                plugin,
                mapOverlay,
                mapConfig,
                mapRenderCache,
                textureManager,
                () -> debugEnabled
        );
        packetListenerRegistration = PacketEvents.getAPI()
                .getEventManager()
                .registerListener(
                        new LockViewPacketListener(this),
                        PacketListenerPriority.HIGH
                );
        timerTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tickLockedPlayers,
                1L,
                1L
        );
    }

    /**
     * Stops the tick loop, unlocks active players and releases map resources.
     */
    public void close() {
        unlockAll();
        mapOverlay.close();
        mapRenderCache.close();
        timerTask.cancel();
        PacketEvents.getAPI()
                .getEventManager()
                .unregisterListener(packetListenerRegistration);
    }

    /**
     * Toggles the map lock for a player.
     *
     * @param player target player
     * @return true when the map is now open
     */
    public boolean toggle(Player player) {
        if (states.containsKey(player.getUniqueId())) {
            unlock(player);
            return false;
        }

        return lock(player);
    }

    /**
     * Returns the persistent cache used by the map listener.
     *
     * @return shared map render cache
     */
    public PersistentChunkRenderCache mapRenderCache() {
        return mapRenderCache;
    }

    /**
     * Returns the config keys registered by the active map components.
     *
     * @return canonical config keys
     */
    public List<String> configKeys() {
        return mapConfig.keys();
    }

    /**
     * Toggles debug messages for lock and render diagnostics.
     *
     * @return the new debug state
     */
    public boolean toggleDebug() {
        debugEnabled = !debugEnabled;
        return debugEnabled;
    }

    /**
     * Updates a map configuration value and rebuilds active map sessions.
     *
     * @param key configuration key
     * @param value new numeric value
     * @return true when the key was accepted
     */
    public boolean updateConfig(String key, double value) {
        if (mapConfig.update(key, value) == null) {
            return false;
        }

        restartActiveSessions();
        return true;
    }

    /** Reloads YAML, textures and all currently open map sessions. */
    public void reload() {
        plugin.reloadConfig();
        mapConfig.reload();
        textureManager.reload();
        restartActiveSessions();
    }

    /** Rebuilds every session so new runtime settings take effect. */
    private void restartActiveSessions() {
        for (UUID playerId : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                unlock(player);
                lock(player);
            }
        }
    }

    /**
     * Starts a client-side map session for a player.
     *
     * @param player target player
     * @return true when the session was created
     */
    /** Creates the camera, visibility state and asynchronous map session. */
    private boolean lock(Player player) {
        if (player.isInsideVehicle()) {
            player.sendMessage(FancyMapMessages.text(
                    "§cKhông thể khóa khi đang ngồi trên phương tiện khác."
            ));
            return false;
        }

        Location anchor = player.getLocation().clone();
        Location initialEye = player.getEyeLocation().clone();
        float lockedYaw = LOCKED_YAW;
        Location mapCenter = MapOverlay.mapCenter(initialEye, lockedYaw);

        Location normalized = normalizePlayerLocation(
                anchor,
                lockedYaw,
                mapCenter,
                mapConfig.getMapDistance(),
                mapConfig.getMapHorizontalOffset(),
                mapConfig.getVerticalOffset(),
                initialEye.getY() - anchor.getY()
        );
        if (!player.teleport(normalized)) {
            mapOverlay.hide(player);
            return false;
        }

        Location cameraOrigin = player.getEyeLocation().clone();
        int cameraEntityId = nextCameraEntityId.getAndDecrement();
        LockViewState state = new LockViewState(
                cameraEntityId,
                anchor,
                lockedYaw,
                0.0F,
                mapCenter.getWorld(),
                mapCenter.getX(),
                mapCenter.getZ(),
                player.getInventory().getHeldItemSlot(),
                player.isInvulnerable(),
                player.getPlayerWeather(),
                player.getGameMode(),
                plugin,
                mapRenderCache
        );
        states.put(player.getUniqueId(), state);
        player.setInvulnerable(true);
        player.setPlayerWeather(WeatherType.CLEAR);
        send(player, new WrapperPlayServerChangeGameState(
                WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE,
                CLIENT_SPECTATOR_GAME_MODE
        ));
        visibility.hideEntities(player, state);
        state.renderSnapshotVersion = state.snapshotVersion();
        state.renderStartedAtNanos = System.nanoTime();
        mapOverlay.showAsync(
                player,
                initialEye,
                lockedYaw,
                mapUpdater.createRenderer(state),
                () -> {
                    if (states.get(player.getUniqueId()) == state
                            && player.isOnline()) {
                        mapUpdater.recordRender(player, state);
                        state.clientAirBlocks.addAll(visibility.hideNearbyBlocks(
                                player,
                                player.getEyeLocation()
                        ));
                        state.clientConcreteBlocks.addAll(visibility.showClientBox(
                                player,
                                player.getEyeLocation()
                        ));
                    }
                    state.lastRenderedSnapshotVersion = state.renderSnapshotVersion;
                    state.mapRenderPending = false;
                }
        );

        send(player, new WrapperPlayServerSpawnEntity(
                cameraEntityId,
                UUID.randomUUID(),
                EntityTypes.ARMOR_STAND,
                PacketLocations.at(
                        cameraOrigin.getX(),
                        cameraOrigin.getY(),
                        cameraOrigin.getZ(),
                        lockedYaw,
                        0.0F
                ),
                lockedYaw,
                0,
                null
        ));
        send(player, new WrapperPlayServerEntityMetadata(
                cameraEntityId,
                List.of(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20))
        ));
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    LockViewState current = states.get(player.getUniqueId());
                    if (current == state && player.isOnline()) {
                        send(player, new WrapperPlayServerCamera(cameraEntityId));
                    }
                },
                2L
        );
        return true;
    }

    /**
     * Aligns the player to the normalized camera/map grid.
     *
     * @param source original player location
     * @param yaw locked yaw
     * @param mapCenter normalized map center
     * @param mapDistance map distance offset
     * @param mapHorizontalOffset horizontal offset
     * @param verticalOffset vertical offset
     * @param eyeHeight player eye height
     * @return normalized player location
     */
    private Location normalizePlayerLocation(
            Location source,
            float yaw,
            Location mapCenter,
            double mapDistance,
            double mapHorizontalOffset,
            double verticalOffset,
            double eyeHeight
    ) {
        Location normalized = source.clone();
        double yawRadians = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double rightX = Math.cos(yawRadians);
        double rightZ = Math.sin(yawRadians);

        normalized.setX(
                mapCenter.getX()
                        - forwardX * mapDistance
                        - rightX * mapHorizontalOffset
        );
        normalized.setZ(
                mapCenter.getZ()
                        - forwardZ * mapDistance
                        - rightZ * mapHorizontalOffset
        );
        normalized.setY(mapCenter.getY() - eyeHeight - verticalOffset);
        normalized.setYaw(yaw);
        normalized.setPitch(0.0F);
        return normalized;
    }

    /**
     * Unlocks a player and restores their location.
     *
     * @param player target player
     */
    public void unlock(Player player) {
        unlock(player, true);
    }

    /**
     * Disables the map without teleporting the player back, for death/teleport events.
     *
     * @param player target player
     */
    void disable(Player player) {
        unlock(player, false);
    }

    /**
     * Ends a session and optionally restores the original position.
     *
     * @param player target player
     * @param restorePosition whether to teleport to the anchor
     */
    private void unlock(Player player, boolean restorePosition) {
        LockViewState state = states.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.mapSnapshotStore.close();

        if (player.isOnline()) {
            player.setInvulnerable(state.originalInvulnerable);
            if (state.originalPlayerWeather == null) {
                player.resetPlayerWeather();
            } else {
                player.setPlayerWeather(state.originalPlayerWeather);
            }
            visibility.restoreEntities(player, state);
            mapOverlay.hide(player);
            visibility.restoreNearbyBlocks(player, state);
            visibility.restoreClientBox(player, state);
            send(player, new WrapperPlayServerCamera(player.getEntityId()));
            send(player, new WrapperPlayServerChangeGameState(
                    WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE,
                    toPacketGameMode(player.getGameMode())
            ));
            send(player, new WrapperPlayServerDestroyEntities(state.cameraEntityId));
            if (restorePosition) {
                player.teleport(state.anchor);
            }
        }
    }

    /** Converts a Bukkit game mode to the protocol game-mode id. */
    private int toPacketGameMode(GameMode gameMode) {
        return switch (gameMode) {
            case CREATIVE -> 1;
            case ADVENTURE -> 2;
            case SPECTATOR -> 3;
            case SURVIVAL -> 0;
        };
    }

    /**
     * Finds the active state for a player.
     *
     * @param player target player
     * @return active state or {@code null}
     */
    LockViewState stateFor(Player player) {
        return states.get(player.getUniqueId());
    }

    /**
     * Checks whether a player currently has the map open.
     *
     * @param player target player
     * @return true when locked
     */
    boolean isLocked(Player player) {
        return stateFor(player) != null;
    }

    /**
     * Applies visibility hiding to a newly spawned entity.
     *
     * @param entity entity to hide
     */
    void hideEntityFromLockedPlayers(
            Entity entity
    ) {
        visibility.hideEntityFromLockedPlayers(entity);
    }

    /**
     * Sends the previous hotbar slot back to a locked player.
     *
     * @param player target player
     * @param state expected active state
     * @param slot slot to restore
     */
    void restoreHotbar(Player player, LockViewState state, int slot) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            LockViewState current = stateFor(player);
            if (current == state && player.isOnline()) {
                send(player, new WrapperPlayServerHeldItemChange(slot));
            }
        });
    }

    /** Closes the map from a Shift packet on the server main thread. */
    void closeFromInput(Player player, LockViewState state) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (states.get(player.getUniqueId()) == state && player.isOnline()) {
                unlock(player);
            }
        });
    }

    /**
     * Unlocks every active player during shutdown.
     */
    void unlockAll() {
        for (UUID playerId : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                unlock(player);
            } else {
                LockViewState state = states.remove(playerId);
                if (state != null) {
                    state.mapSnapshotStore.close();
                }
            }
        }
    }

    /**
     * Runs one main-thread lock tick.
     */
    void tickLockedPlayers() {
        tickCounter++;
        for (Map.Entry<UUID, LockViewState> entry : states.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                send(player, new WrapperPlayServerCamera(entry.getValue().cameraEntityId));
            }
        }

        sendMovementInputs();
        updateMapViews();
    }

    /**
     * Applies the newest button state and emits optional debug output.
     */
    private void sendMovementInputs() {
        for (Map.Entry<UUID, LockViewState> entry : states.entrySet()) {
            LockViewState state = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            MovementInput input;
            while ((input = state.movementInput.poll()) != null) {
                if (input.equals(state.currentMovement)) {
                    continue;
                }

                state.currentMovement = input;
                if (debugEnabled && player != null) {
                    player.sendMessage(FancyMapMessages.debug(input.isIdle()
                            ? "Đã thả các phím điều khiển."
                            : "Đang giữ: " + input.describe()));
                }
            }
        }
    }

    /**
     * Advances pan/zoom state and schedules progressive map refreshes.
     */
    private void updateMapViews() {
        mapUpdater.update(states, tickCounter);
    }

    /**
     * Sends a PacketEvents packet to a player.
     *
     * @param player packet recipient
     * @param packet packet to send
     */
    private void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }
}
