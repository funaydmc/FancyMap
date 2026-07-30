package dev.funayd.fancyMap.map;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import dev.funayd.fancyMap.packet.PacketLocations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.map.MinecraftFont;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages client-only display entities positioned on the active map canvas.
 * Coordinates are canvas pixels, so callers do not need to handle map-plane
 * orientation or item-frame layout.
 */
public final class ClientCanvasDisplayHelper {
    private static final int FIRST_DISPLAY_ENTITY_ID = 1_600_000_000;
    private static final double BASE_DISPLAY_SCALE = 0.25D;
    private static final double ITEM_SURFACE_DEPTH = 1.0D / MapOverlay.MAP_SIZE;
    private static final double TEXT_SURFACE_DEPTH = 3.0D / MapOverlay.MAP_SIZE;
    private static final byte BILLBOARD_CENTER = 3;
    private static final byte ITEM_DISPLAY_GUI = 6;
    private static final byte TEXT_FLAGS = 0x0C;
    private static final int FULL_BRIGHTNESS = 0xF000F0;
    private static final double TEXT_PIXEL_SIZE = 0.025D;
    private static final int TEXT_LINE_HEIGHT = 10;

    private final MapOverlay mapOverlay;
    private final AtomicInteger nextEntityId =
            new AtomicInteger(FIRST_DISPLAY_ENTITY_ID);
    private final ConcurrentMap<UUID, Map<String, Display>> displays =
            new ConcurrentHashMap<>();

    /**
     * Creates a display helper for one map overlay.
     *
     * @param mapOverlay active map-plane provider
     */
    public ClientCanvasDisplayHelper(MapOverlay mapOverlay) {
        this.mapOverlay = mapOverlay;
    }

    /**
     * Shows or replaces a text display at a canvas coordinate.
     *
     * @param player target player
     * @param key caller-owned display key
     * @param canvasX canvas X coordinate
     * @param canvasY canvas Y coordinate
     * @param text display text
     * @param scale multiplier of the {@code 0.25} base display scale
     */
    public void showText(
            Player player,
            String key,
            double canvasX,
            double canvasY,
            String text,
            double scale
    ) {
        showText(
                player,
                key,
                canvasX,
                canvasY,
                Component.text(text),
                scale,
                TextAnchor.MIDDLE_LEFT
        );
    }

    /**
     * Shows or replaces a colored text display at a canvas coordinate.
     *
     * @param player target player
     * @param key caller-owned display key
     * @param canvasX canvas X coordinate
     * @param canvasY canvas Y coordinate
     * @param text Adventure text component
     * @param scale multiplier of the {@code 0.25} base display scale
     */
    public void showText(
            Player player,
            String key,
            double canvasX,
            double canvasY,
            Component text,
            double scale
    ) {
        showText(
                player,
                key,
                canvasX,
                canvasY,
                text,
                scale,
                TextAnchor.MIDDLE_LEFT
        );
    }

    /**
     * Shows or replaces anchored text at a canvas coordinate.
     *
     * @param player target player
     * @param key caller-owned display key
     * @param canvasX canvas X coordinate
     * @param canvasY canvas Y coordinate
     * @param text Adventure text component
     * @param scale multiplier of the {@code 0.25} base display scale
     * @param anchor point of the rendered text placed at the canvas coordinate
     */
    public void showText(
            Player player,
            String key,
            double canvasX,
            double canvasY,
            Component text,
            double scale,
            TextAnchor anchor
    ) {
        showText(player, key, canvasX, canvasY, text, scale, anchor, false);
    }

    /** Shows anchored text with an optional client-side shadow. */
    public void showText(
            Player player,
            String key,
            double canvasX,
            double canvasY,
            Component text,
            double scale,
            TextAnchor anchor,
            boolean shadow
    ) {
        show(
                player,
                key,
                canvasX,
                canvasY,
                scale,
                TEXT_SURFACE_DEPTH,
                EntityTypes.TEXT_DISPLAY,
                textMetadata(text, scale, anchor, shadow),
                true
        );
    }

    /**
     * Shows or replaces an item display at a canvas coordinate.
     *
     * @param player target player
     * @param key caller-owned display key
     * @param canvasX canvas X coordinate
     * @param canvasY canvas Y coordinate
     * @param item item rendered by the display
     * @param scale multiplier of the {@code 0.25} base display scale
     */
    public void showItem(
            Player player,
            String key,
            double canvasX,
            double canvasY,
            ItemStack item,
            double scale
    ) {
        show(
                player,
                key,
                canvasX,
                canvasY,
                scale,
                ITEM_SURFACE_DEPTH,
                EntityTypes.ITEM_DISPLAY,
                itemMetadata(item, scale),
                false
        );
    }

    /** Hides one keyed display from a player. */
    public void hide(Player player, String key) {
        Map<String, Display> playerDisplays = displays.get(player.getUniqueId());
        if (playerDisplays == null) {
            return;
        }
        Display display = playerDisplays.remove(key);
        if (display != null && player.isOnline()) {
            send(player, new WrapperPlayServerDestroyEntities(display.entityId()));
        }
        if (playerDisplays.isEmpty()) {
            displays.remove(player.getUniqueId(), playerDisplays);
        }
    }

    /** Hides every client-only display owned by a player. */
    public void hideAll(Player player) {
        Map<String, Display> playerDisplays = displays.remove(player.getUniqueId());
        if (playerDisplays == null || !player.isOnline()) {
            return;
        }
        int[] entityIds = playerDisplays.values().stream()
                .mapToInt(Display::entityId)
                .toArray();
        if (entityIds.length > 0) {
            send(player, new WrapperPlayServerDestroyEntities(entityIds));
        }
    }

    /** Hides all displays and releases the helper's player registry. */
    public void close() {
        displays.clear();
    }

    /** Sends a client-only entity packet. */
    private void show(
            Player player,
            String key,
            double canvasX,
            double canvasY,
            double scale,
            double surfaceDepth,
            EntityType entityType,
            List<EntityData<?>> metadata,
            boolean refreshMetadata
    ) {
        showAtLocation(
                player,
                key,
                mapOverlay.canvasLocation(
                        player,
                        canvasX,
                        canvasY,
                        surfaceDepth
                ),
                entityType,
                metadata,
                scale,
                refreshMetadata
        );
    }

    private void showAtLocation(
            Player player,
            String key,
            Location location,
            EntityType entityType,
            List<EntityData<?>> metadata,
            double scale,
            boolean refreshMetadata
    ) {
        if (key == null || key.isBlank() || !Double.isFinite(scale) || scale <= 0.0D) {
            throw new IllegalArgumentException("Display key and scale must be valid.");
        }
        if (location == null) {
            return;
        }
        Map<String, Display> playerDisplays = displays.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new HashMap<>()
        );
        Display existing = playerDisplays.get(key);
        if (existing != null && existing.entityType().equals(entityType)) {
            send(player, new WrapperPlayServerEntityTeleport(
                    existing.entityId(),
                    PacketLocations.at(
                            location.getX(),
                            location.getY(),
                            location.getZ(),
                            location.getYaw(),
                            location.getPitch()
                    ),
                    false
            ));
            if (refreshMetadata) {
                send(player, new WrapperPlayServerEntityMetadata(
                        existing.entityId(),
                        metadata
                ));
            }
            return;
        }
        if (existing != null) {
            hide(player, key);
            playerDisplays = displays.computeIfAbsent(
                    player.getUniqueId(),
                    ignored -> new HashMap<>()
            );
        }
        int entityId = nextEntityId.getAndDecrement();
        playerDisplays.put(key, new Display(entityId, entityType));
        send(player, new WrapperPlayServerSpawnEntity(
                entityId,
                UUID.randomUUID(),
                entityType,
                PacketLocations.at(
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch()
                ),
                location.getYaw(),
                0,
                null
        ));
        send(player, new WrapperPlayServerEntityMetadata(entityId, metadata));
    }

    /** Creates common display metadata for the requested scale. */
    private List<EntityData<?>> baseMetadata(double scale) {
        float displayScale = (float) (BASE_DISPLAY_SCALE * scale);
        return new ArrayList<>(List.of(
                new EntityData<>(12, EntityDataTypes.VECTOR3F,
                        new Vector3f(displayScale, displayScale, displayScale)),
                new EntityData<>(15, EntityDataTypes.BYTE, BILLBOARD_CENTER),
                new EntityData<>(16, EntityDataTypes.INT, FULL_BRIGHTNESS)
        ));
    }

    /** Creates TextDisplay metadata using the game's default readable background. */
    private List<EntityData<?>> textMetadata(
            Component text,
            double scale,
            TextAnchor anchor,
            boolean shadow
    ) {
        List<EntityData<?>> metadata = baseMetadata(scale);
        metadata.add(new EntityData<>(
                11,
                EntityDataTypes.VECTOR3F,
                textAnchorOffset(text, scale, anchor)
        ));
        metadata.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, text));
        metadata.add(new EntityData<>(24, EntityDataTypes.INT, 200));
        metadata.add(new EntityData<>(25, EntityDataTypes.INT, 0xFF000000));
        metadata.add(new EntityData<>(26, EntityDataTypes.BYTE, (byte) -1));
        metadata.add(new EntityData<>(27, EntityDataTypes.BYTE,
                (byte) (TEXT_FLAGS | (shadow ? 0x01 : 0))));
        return metadata;
    }

    /** Resolves the display transformation required for a text pivot. */
    private Vector3f textAnchorOffset(
            Component text,
            double scale,
            TextAnchor anchor
    ) {
        String plainText = PlainTextComponentSerializer.plainText().serialize(text);
        String[] lines = plainText.split("\n", -1);
        int width = Arrays.stream(lines)
                .mapToInt(this::textWidth)
                .max()
                .orElse(0);
        int height = Math.max(1, lines.length) * TEXT_LINE_HEIGHT - 1;
        float pixel = (float) (
                TEXT_PIXEL_SIZE * BASE_DISPLAY_SCALE * scale
        );
        return switch (anchor) {
            case TOP_LEFT -> new Vector3f(
                    (width / 2.0F - 1.0F) * pixel,
                    -height * pixel,
                    0.0F
            );
            case MIDDLE_LEFT -> new Vector3f(
                    (width / 2.0F - 1.0F) * pixel,
                    -height * pixel / 2.0F,
                    0.0F
            );
            case BOTTOM_RIGHT -> new Vector3f(
                    -(width / 2.0F + 1.0F) * pixel,
                    0.0F,
                    0.0F
            );
        };
    }

    /** Estimates client font width while tolerating Unicode fallback glyphs. */
    private int textWidth(String line) {
        if (line.isEmpty()) {
            return 0;
        }
        if (MinecraftFont.Font.isValid(line)) {
            return MinecraftFont.Font.getWidth(line);
        }
        int width = 0;
        for (int index = 0; index < line.length(); index++) {
            String character = line.substring(index, index + 1);
            width += MinecraftFont.Font.isValid(character)
                    ? MinecraftFont.Font.getWidth(character)
                    : 6;
        }
        return width;
    }

    /** Creates ItemDisplay metadata for a client-side item. */
    private List<EntityData<?>> itemMetadata(ItemStack item, double scale) {
        List<EntityData<?>> metadata = baseMetadata(scale);
        float displayScale = (float) (BASE_DISPLAY_SCALE * scale);
        metadata.set(0, new EntityData<>(
                12,
                EntityDataTypes.VECTOR3F,
                new Vector3f(-displayScale, displayScale, displayScale)
        ));
        metadata.add(new EntityData<>(23, EntityDataTypes.ITEMSTACK, item));
        metadata.add(new EntityData<>(24, EntityDataTypes.BYTE, ITEM_DISPLAY_GUI));
        return metadata;
    }

    /** Sends a PacketEvents packet to a player. */
    private void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private record Display(int entityId, EntityType entityType) {
    }

    /** Supported text pivots in logical canvas coordinates. */
    public enum TextAnchor {
        TOP_LEFT,
        MIDDLE_LEFT,
        BOTTOM_RIGHT
    }
}
