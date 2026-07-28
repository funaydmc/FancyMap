package dev.funayd.fancyMap.lockview;

import dev.funayd.fancyMap.FancyMapMessages;
import dev.funayd.fancyMap.map.MapConfig;
import dev.funayd.fancyMap.map.MapOverlay;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class LockViewController implements PacketListener {
    private static final int FIRST_CAMERA_ENTITY_ID = 2_000_000_000;
    private static final float LOCKED_YAW = 0.0F;

    private final JavaPlugin plugin;
    private final PacketListenerCommon packetListenerRegistration;
    private final BukkitTask timerTask;
    private final MapConfig mapConfig;
    private final MapOverlay mapOverlay;
    private final AtomicInteger nextCameraEntityId =
            new AtomicInteger(FIRST_CAMERA_ENTITY_ID);
    private final Map<UUID, LockState> states = new ConcurrentHashMap<>();
    private volatile boolean debugEnabled;

    public LockViewController(JavaPlugin plugin) {
        this.plugin = plugin;
        mapConfig = new MapConfig(plugin);
        mapOverlay = new MapOverlay(plugin);
        packetListenerRegistration = PacketEvents.getAPI()
                .getEventManager()
                .registerListener(this, PacketListenerPriority.HIGH);
        timerTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tickLockedPlayers,
                1L,
                1L
        );
    }

    public void close() {
        unlockAll();
        mapOverlay.close();
        timerTask.cancel();
        PacketEvents.getAPI()
                .getEventManager()
                .unregisterListener(packetListenerRegistration);
    }

    public boolean toggle(Player player) {
        if (states.containsKey(player.getUniqueId())) {
            unlock(player);
            return false;
        }

        return lock(player);
    }

    public boolean toggleDebug() {
        debugEnabled = !debugEnabled;
        return debugEnabled;
    }

    public boolean updateConfig(String key, double value) {
        if (mapConfig.update(key, value) == null) {
            return false;
        }

        for (UUID playerId : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                unlock(player);
                lock(player);
            }
        }
        return true;
    }

    private boolean lock(Player player) {
        if (player.isInsideVehicle()) {
            player.sendMessage(FancyMapMessages.text(
                    "§cKhông thể khóa khi đang ngồi trên phương tiện khác."
            ));
            return false;
        }

        org.bukkit.Location anchor = player.getLocation().clone();
        org.bukkit.Location initialEye = player.getEyeLocation().clone();
        float lockedYaw = LOCKED_YAW;
        org.bukkit.Location mapCenter = MapOverlay.mapCenter(initialEye, lockedYaw);
        mapOverlay.show(player, initialEye, lockedYaw);

        org.bukkit.Location normalized = normalizePlayerLocation(
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

        org.bukkit.Location cameraOrigin = player.getEyeLocation().clone();
        int cameraEntityId = nextCameraEntityId.getAndDecrement();
        LockState state = new LockState(
                cameraEntityId,
                anchor,
                lockedYaw,
                0.0F
        );
        states.put(player.getUniqueId(), state);

        send(player, new WrapperPlayServerSpawnEntity(
                cameraEntityId,
                UUID.randomUUID(),
                EntityTypes.ARMOR_STAND,
                new Location(
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
                    LockState current = states.get(player.getUniqueId());
                    if (current == state && player.isOnline()) {
                        send(player, new WrapperPlayServerCamera(cameraEntityId));
                    }
                },
                2L
        );
        return true;
    }

    private org.bukkit.Location normalizePlayerLocation(
            org.bukkit.Location source,
            float yaw,
            org.bukkit.Location mapCenter,
            double mapDistance,
            double mapHorizontalOffset,
            double verticalOffset,
            double eyeHeight
    ) {
        org.bukkit.Location normalized = source.clone();
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

    public void unlock(Player player) {
        LockState state = states.remove(player.getUniqueId());
        if (state == null) {
            return;
        }

        if (player.isOnline()) {
            mapOverlay.hide(player);
            send(player, new WrapperPlayServerCamera(player.getEntityId()));
            send(player, new WrapperPlayServerDestroyEntities(state.cameraEntityId));
            player.teleport(state.anchor);
        }
    }

    void unlockAll() {
        for (UUID playerId : new ArrayList<>(states.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                unlock(player);
            } else {
                states.remove(playerId);
            }
        }
    }

    void tickLockedPlayers() {
        for (Map.Entry<UUID, LockState> entry : states.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                send(player, new WrapperPlayServerCamera(entry.getValue().cameraEntityId));
            }
        }

        sendMovementInputs();
    }

    private void sendMovementInputs() {
        for (Map.Entry<UUID, LockState> entry : states.entrySet()) {
            LockState state = entry.getValue();
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

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        LockState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.STEER_VEHICLE) {
            WrapperPlayClientSteerVehicle packet =
                    new WrapperPlayClientSteerVehicle(event);
            state.movementInput.add(MovementInput.from(
                    packet.getSideways(),
                    packet.getForward(),
                    packet.isJump(),
                    packet.isUnmount()
            ));
            if (packet.isUnmount()) {
                packet.setUnmount(false);
                event.markForReEncode(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_INPUT) {
            WrapperPlayClientPlayerInput packet =
                    new WrapperPlayClientPlayerInput(event);
            state.movementInput.add(new MovementInput(
                    packet.isForward(),
                    packet.isBackward(),
                    packet.isLeft(),
                    packet.isRight(),
                    packet.isJump(),
                    packet.isShift()
            ));
            if (packet.isShift()) {
                packet.setShift(false);
                event.markForReEncode(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION) {
            WrapperPlayClientPlayerRotation packet =
                    new WrapperPlayClientPlayerRotation(event);
            packet.setYaw(state.lockedYaw);
            packet.setPitch(state.lockedPitch);
            event.markForReEncode(true);
            return;
        }

        if (event.getPacketType()
                == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            event.setCancelled(true);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION) {
            event.setCancelled(true);
        }
    }

    private void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private static final class LockState {
        private final int cameraEntityId;
        private final org.bukkit.Location anchor;
        private final float lockedYaw;
        private final float lockedPitch;
        private final ConcurrentLinkedQueue<MovementInput> movementInput =
                new ConcurrentLinkedQueue<>();
        private MovementInput currentMovement;

        private LockState(
                int cameraEntityId,
                org.bukkit.Location anchor,
                float lockedYaw,
                float lockedPitch
        ) {
            this.cameraEntityId = cameraEntityId;
            this.anchor = anchor;
            this.lockedYaw = lockedYaw;
            this.lockedPitch = lockedPitch;
        }
    }

    private record MovementInput(
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump,
            boolean shift
    ) {
        private static MovementInput from(
                float sideways,
                float forward,
                boolean jump,
                boolean shift
        ) {
            return new MovementInput(
                    forward > 0.01F,
                    forward < -0.01F,
                    sideways > 0.01F,
                    sideways < -0.01F,
                    jump,
                    shift
            );
        }

        private boolean isIdle() {
            return !forward && !backward && !left && !right && !jump && !shift;
        }

        private String describe() {
            StringBuilder result = new StringBuilder();
            append(result, forward, "W");
            append(result, backward, "S");
            append(result, left, "A");
            append(result, right, "D");
            append(result, jump, "SPACE");
            append(result, shift, "SHIFT");
            return result.toString();
        }

        private static void append(StringBuilder result, boolean active, String key) {
            if (!active) {
                return;
            }
            if (result.length() > 0) {
                result.append('+');
            }
            result.append(key);
        }
    }
}
