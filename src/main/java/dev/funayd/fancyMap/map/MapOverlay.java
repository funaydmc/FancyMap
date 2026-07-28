package dev.funayd.fancyMap.map;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MapOverlay implements AutoCloseable {
    private static final int FIRST_FRAME_ENTITY_ID = 1_500_000_000;
    private static final int FIRST_MAP_ID = 1_000_000_000;
    private static final double MAP_PLACEMENT_DISTANCE = 1.0D;
    private static final int MAP_SIZE = 128;
    private static final int COLUMNS = 5;
    private static final int ROWS = 3;
    private static final int CANVAS_WIDTH = COLUMNS * MAP_SIZE;
    private static final int CANVAS_HEIGHT = ROWS * MAP_SIZE;
    private static final byte TRANSPARENT_COLOR = 0;
    private static final byte BORDER_COLOR = 110;

    private final JavaPlugin plugin;
    private final ExecutorService renderExecutor;
    private final AtomicInteger nextFrameEntityId =
            new AtomicInteger(FIRST_FRAME_ENTITY_ID);
    private final AtomicInteger nextMapId = new AtomicInteger(FIRST_MAP_ID);
    private final Map<UUID, RenderSession> activeSessions = new ConcurrentHashMap<>();

    public MapOverlay(JavaPlugin plugin) {
        this.plugin = plugin;
        renderExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "FancyMap-MapRenderer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void show(Player player, org.bukkit.Location cameraOrigin, float yaw) {
        show(player, cameraOrigin, yaw, this::renderDefault);
    }

    public void show(
            Player player,
            org.bukkit.Location cameraOrigin,
            float yaw,
            MapCanvasRenderer renderer
    ) {
        hide(player);

        RenderSession session = createSession(cameraOrigin, yaw);
        activeSessions.put(player.getUniqueId(), session);
        CompletableFuture
                .supplyAsync(() -> render(renderer), renderExecutor)
                .thenAccept(rendered -> Bukkit.getScheduler().runTask(
                        plugin,
                        () -> sendRendered(player, session, rendered)
                ))
                .exceptionally(exception -> {
                    plugin.getLogger().warning("Map render failed: " + exception.getCause());
                    return null;
                });
    }

    public static org.bukkit.Location mapCenter(org.bukkit.Location origin, float yaw) {
        double yawRadians = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        org.bukkit.Location center = origin.clone();
        center.setX(Math.rint(origin.getX() + forwardX * MAP_PLACEMENT_DISTANCE));
        center.setY(Math.rint(origin.getY()));
        center.setZ(Math.rint(origin.getZ() + forwardZ * MAP_PLACEMENT_DISTANCE));
        return center;
    }

    public void hide(Player player) {
        RenderSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        session.active = false;
        if (player.isOnline()) {
            send(player, new WrapperPlayServerDestroyEntities(session.entityIds()));
        }
    }

    @Override
    public void close() {
        for (RenderSession session : activeSessions.values()) {
            session.active = false;
        }
        activeSessions.clear();
        renderExecutor.shutdownNow();
    }

    private RenderSession createSession(org.bukkit.Location cameraOrigin, float yaw) {
        org.bukkit.Location center = mapCenter(cameraOrigin, yaw);
        double yawRadians = Math.toRadians(yaw);
        double rightX = Math.cos(yawRadians);
        double rightZ = Math.sin(yawRadians);
        int frameDirection = frameDirection(yaw);
        Frame[] frames = new Frame[COLUMNS * ROWS];

        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                double horizontalOffset = column - (COLUMNS - 1) / 2.0D;
                double verticalOffset = (ROWS - 1) / 2.0D - row;
                frames[row * COLUMNS + column] = new Frame(
                        nextFrameEntityId.getAndDecrement(),
                        nextMapId.getAndIncrement(),
                        center.getX() + rightX * horizontalOffset,
                        center.getY() + verticalOffset,
                        center.getZ() + rightZ * horizontalOffset,
                        frameDirection
                );
            }
        }
        return new RenderSession(frames);
    }

    private byte[][] render(MapCanvasRenderer renderer) {
        MapCanvas canvas = new MapCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, TRANSPARENT_COLOR);
        renderer.render(canvas);
        byte[][] tiles = new byte[ROWS * COLUMNS][];
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                byte[] tile = canvas.copyRegion(
                        column * MAP_SIZE,
                        row * MAP_SIZE,
                        MAP_SIZE,
                        MAP_SIZE
                );
                mirrorHorizontally(tile);
                tiles[row * COLUMNS + column] = tile;
            }
        }
        return tiles;
    }

    private void mirrorHorizontally(byte[] tile) {
        for (int row = 0; row < MAP_SIZE; row++) {
            int rowStart = row * MAP_SIZE;
            for (int left = 0; left < MAP_SIZE / 2; left++) {
                int right = MAP_SIZE - 1 - left;
                byte pixel = tile[rowStart + left];
                tile[rowStart + left] = tile[rowStart + right];
                tile[rowStart + right] = pixel;
            }
        }
    }

    private void renderDefault(MapCanvas canvas) {
        canvas.drawRect(
                0,
                0,
                canvas.getWidth(),
                canvas.getHeight(),
                BORDER_COLOR
        );
        canvas.drawLine(
                0,
                0,
                canvas.getWidth() - 1,
                canvas.getHeight() - 1,
                BORDER_COLOR
        );
        canvas.drawLine(
                canvas.getWidth() - 1,
                0,
                0,
                canvas.getHeight() - 1,
                BORDER_COLOR
        );
    }

    private void sendRendered(Player player, RenderSession session, byte[][] tiles) {
        if (!session.active || activeSessions.get(player.getUniqueId()) != session
                || !player.isOnline()) {
            return;
        }

        for (int index = 0; index < session.frames.length; index++) {
            Frame frame = session.frames[index];
            send(player, new WrapperPlayServerMapData(
                    frame.mapId,
                    (byte) 0,
                    false,
                    true,
                    null,
                    MAP_SIZE,
                    MAP_SIZE,
                    0,
                    0,
                    tiles[index]
            ));
            ItemStack mapItem = ItemStack.builder()
                    .type(ItemTypes.FILLED_MAP)
                    .component(ComponentTypes.MAP_ID, frame.mapId)
                    .build();

            send(player, new WrapperPlayServerSpawnEntity(
                    frame.entityId,
                    UUID.randomUUID(),
                    EntityTypes.GLOW_ITEM_FRAME,
                    new Location(frame.x, frame.y, frame.z, 0.0F, 0.0F),
                    0.0F,
                    frame.direction,
                    null
            ));
            send(player, new WrapperPlayServerEntityMetadata(
                    frame.entityId,
                    java.util.List.of(
                            new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20),
                            new EntityData<>(
                                    8,
                                    EntityDataTypes.BLOCK_FACE,
                                    frameFace(frame.direction)
                            ),
                            new EntityData<>(
                                    9,
                                    EntityDataTypes.ITEMSTACK,
                                    mapItem
                            ),
                            new EntityData<>(10, EntityDataTypes.INT, 0)
                    )
            ));
        }
    }

    private int frameDirection(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90.0F), 4)) {
            case 0 -> 2;
            case 1 -> 5;
            case 2 -> 3;
            default -> 4;
        };
    }

    private BlockFace frameFace(int direction) {
        return switch (direction) {
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.SOUTH;
            case 4 -> BlockFace.WEST;
            case 5 -> BlockFace.EAST;
            default -> throw new IllegalArgumentException("Invalid item frame direction: " + direction);
        };
    }

    private void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private static final class RenderSession {
        private final Frame[] frames;
        private volatile boolean active = true;

        private RenderSession(Frame[] frames) {
            this.frames = frames;
        }

        private int[] entityIds() {
            int[] entityIds = new int[frames.length];
            for (int index = 0; index < frames.length; index++) {
                entityIds[index] = frames[index].entityId;
            }
            return entityIds;
        }
    }

    private record Frame(
            int entityId,
            int mapId,
            double x,
            double y,
            double z,
            int direction
    ) {
    }
}
