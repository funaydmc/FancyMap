package dev.funayd.fancyMap.map;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import dev.funayd.fancyMap.FancyMapMessages;
import dev.funayd.fancyMap.packet.PacketLocations;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Sends and destroys the client-only item-frame map packets for an overlay. */
final class ClientMapPacketTransport {
    private final JavaPlugin plugin;
    private final BooleanSupplier debugEnabled;

    ClientMapPacketTransport(JavaPlugin plugin, BooleanSupplier debugEnabled) {
        this.plugin = plugin;
        this.debugEnabled = debugEnabled;
    }

    /** Sends all canvas map data and, on first render, its invisible frames. */
    void send(Player player, ClientMapFrame[] frames, byte[][] tiles, boolean spawnEntities) {
        long sendStarted = System.nanoTime();
        for (int index = 0; index < frames.length; index++) {
            ClientMapFrame frame = frames[index];
            send(player, new WrapperPlayServerMapData(
                    frame.mapId(), (byte) 0, false, true, null,
                    MapOverlay.MAP_SIZE, MapOverlay.MAP_SIZE, 0, 0, tiles[index]
            ));
            if (spawnEntities) {
                spawnFrame(player, frame);
            }
        }
        long sendMillis = (System.nanoTime() - sendStarted) / 1_000_000L;
        if (debugEnabled.getAsBoolean() && sendMillis >= 50L) {
            plugin.getLogger().info(FancyMapMessages.consoleDebug(
                    "player=" + player.getName() + " mapPacketSendMs=" + sendMillis
                            + " packets=" + frames.length
            ));
        }
    }

    /** Destroys the supplied client-only frame entities. */
    void destroy(Player player, int[] entityIds) {
        send(player, new WrapperPlayServerDestroyEntities(entityIds));
    }

    private void spawnFrame(Player player, ClientMapFrame frame) {
        ItemStack mapItem = ItemStack.builder()
                .type(ItemTypes.FILLED_MAP)
                .component(ComponentTypes.MAP_ID, frame.mapId())
                .build();
        send(player, new WrapperPlayServerSpawnEntity(
                frame.entityId(), UUID.randomUUID(), EntityTypes.GLOW_ITEM_FRAME,
                PacketLocations.at(frame.x(), frame.y(), frame.z(), 0.0F, 0.0F),
                0.0F, frame.direction(), null
        ));
        send(player, new WrapperPlayServerEntityMetadata(
                frame.entityId(),
                List.of(
                        new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20),
                        new EntityData<>(8, EntityDataTypes.BLOCK_FACE, frameFace(frame.direction())),
                        new EntityData<>(9, EntityDataTypes.ITEMSTACK, mapItem),
                        new EntityData<>(10, EntityDataTypes.INT, 0)
                )
        ));
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
}
