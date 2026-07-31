package dev.funayd.fancyMap.map;

import dev.funayd.fancyMap.texture.MapTexture;
import dev.funayd.fancyMap.waypoint.Waypoint;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.Objects;

/**
 * Renders top-surface world colors into the combined map canvas.
 */
public final class WorldMapRenderer implements AsyncMapCanvasRenderer {
    // MapColor.BLACK with the darkest shade (29 * 4).
    private static final byte BLACK = 116;
    private static final byte WATER = 49;
    private static final byte GRASS = 6;
    private static final byte SAND = 10;
    private static final byte STONE = 46;
    private static final byte DIRT = 42;
    private static final byte WOOD = 54;
    private static final byte SNOW = 34;
    private static final byte LEAVES = 29;
    private static final byte LAVA = 18;
    private static final byte[] MATERIAL_COLORS = buildPalette();

    private final World world;
    private final AsyncChunkSnapshotStore snapshotStore;
    private final PersistentChunkRenderCache renderCache;
    private final double centerX;
    private final double centerZ;
    private final double blocksPerPixel;
    private final int width;
    private final int height;
    private final double playerX;
    private final double playerZ;
    private final boolean showPlayerMarker;
    private final MapTexture cursorTexture;
    private final MapTexture playerTexture;
    private final MapTexture waypointTexture;
    private final MapTexture waypointHoverTexture;
    private final Map<String, MapTexture> customWaypointTextures;
    private final List<Waypoint> waypoints;
    private final boolean showWaypoints;
    private final String hoveredWaypointId;
    private final boolean debugMaterialWaypoints;

    /**
     * Creates a world renderer for one viewport.
     *
     * @param plugin owning plugin
     * @param world world to sample
     * @param centerX viewport center X
     * @param centerZ viewport center Z
     * @param blocksPerPixel map scale
     * @param width canvas width
     * @param height canvas height
     * @param snapshotStore bounded snapshot loader
     * @param renderCache persistent compact render cache
     */
    public WorldMapRenderer(
            JavaPlugin plugin,
            World world,
            double centerX,
            double centerZ,
            double blocksPerPixel,
            int width,
            int height,
            AsyncChunkSnapshotStore snapshotStore,
            PersistentChunkRenderCache renderCache,
            double playerX,
            double playerZ,
            boolean showPlayerMarker,
            MapTexture cursorTexture,
            MapTexture playerTexture,
            List<Waypoint> waypoints,
            boolean showWaypoints,
            String hoveredWaypointId,
            MapTexture waypointTexture,
            MapTexture waypointHoverTexture,
            Map<String, MapTexture> customWaypointTextures,
            boolean debugMaterialWaypoints
    ) {
        if (!Double.isFinite(blocksPerPixel) || blocksPerPixel <= 0.0D) {
            throw new IllegalArgumentException("blocksPerPixel must be positive.");
        }
        Objects.requireNonNull(plugin, "plugin");
        this.world = Objects.requireNonNull(world, "world");
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.blocksPerPixel = blocksPerPixel;
        this.width = width;
        this.height = height;
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.renderCache = Objects.requireNonNull(renderCache, "renderCache");
        this.playerX = playerX;
        this.playerZ = playerZ;
        this.showPlayerMarker = showPlayerMarker;
        this.cursorTexture = Objects.requireNonNull(cursorTexture, "cursorTexture");
        this.playerTexture = Objects.requireNonNull(playerTexture, "playerTexture");
        this.waypoints = Objects.requireNonNull(waypoints, "waypoints");
        this.showWaypoints = showWaypoints;
        this.hoveredWaypointId = hoveredWaypointId;
        this.waypointTexture = Objects.requireNonNull(waypointTexture, "waypointTexture");
        this.waypointHoverTexture = Objects.requireNonNull(
                waypointHoverTexture,
                "waypointHoverTexture"
        );
        this.customWaypointTextures = Objects.requireNonNull(
                customWaypointTextures,
                "customWaypointTextures"
        );
        this.debugMaterialWaypoints = debugMaterialWaypoints;
    }

    /**
     * Starts an asynchronous viewport render.
     *
     * @param executor render worker executor
     * @return future canvas
     */
    @Override
    public CompletionStage<MapCanvas> render(Executor executor) {
        int minBlockX = (int) Math.floor(centerX - width * blocksPerPixel / 2.0D);
        int maxBlockX = (int) Math.ceil(centerX + width * blocksPerPixel / 2.0D);
        int minBlockZ = (int) Math.floor(centerZ - height * blocksPerPixel / 2.0D);
        int maxBlockZ = (int) Math.ceil(centerZ + height * blocksPerPixel / 2.0D);
        int minChunkX = Math.floorDiv(minBlockX, 16);
        int maxChunkX = Math.floorDiv(maxBlockX, 16);
        int minChunkZ = Math.floorDiv(minBlockZ, 16);
        int maxChunkZ = Math.floorDiv(maxBlockZ, 16);
        int centerChunkX = Math.floorDiv((int) Math.floor(centerX), 16);
        int centerChunkZ = Math.floorDiv((int) Math.floor(centerZ), 16);
        int lodLevel = renderCache.lodLevel(blocksPerPixel);
        snapshotStore.retainViewport(
                minChunkX,
                maxChunkX,
                minChunkZ,
                maxChunkZ,
                centerChunkX,
                centerChunkZ,
                lodLevel
        );

        if (lodLevel > 0) {
            return CompletableFuture.supplyAsync(
                    () -> renderLodCanvas(lodLevel, minChunkX, maxChunkX, minChunkZ, maxChunkZ),
                    executor
            );
        }

        int chunkCountX = maxChunkX - minChunkX + 1;
        int chunkCountZ = maxChunkZ - minChunkZ + 1;

        return CompletableFuture.supplyAsync(() -> {
                    byte[][] chunkColors = new byte[chunkCountX * chunkCountZ][];
                    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                        int canvasIndex = (chunkX - minChunkX) * chunkCountZ
                                + chunkZ - minChunkZ;
                        chunkColors[canvasIndex] = renderCache.get(world, chunkX, chunkZ);
                        }
                    }
                    renderCache.touch(
                            world,
                            minChunkX,
                            maxChunkX,
                            minChunkZ,
                            maxChunkZ
                    );
                    return renderCanvas(
                            chunkColors,
                            minChunkX,
                            minChunkZ,
                            chunkCountZ
                    );
        }, executor);
    }

    /** Renders one cached overview color per LOD cell instead of reading every detailed chunk. */
    private MapCanvas renderLodCanvas(
            int level,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ
    ) {
        int span = renderCache.lodSpanChunks(level);
        int minCellX = Math.floorDiv(minChunkX, span);
        int maxCellX = Math.floorDiv(maxChunkX, span);
        int minCellZ = Math.floorDiv(minChunkZ, span);
        int maxCellZ = Math.floorDiv(maxChunkZ, span);
        int cellCountZ = maxCellZ - minCellZ + 1;
        int[] cells = new int[(maxCellX - minCellX + 1) * cellCountZ];
        Arrays.fill(cells, BLACK);
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                int color = renderCache.getLodColor(world, level, cellX, cellZ);
                if (color >= 0) {
                    cells[(cellX - minCellX) * cellCountZ + cellZ - minCellZ] = color;
                }
            }
        }
        renderCache.touchLod(world, level, minCellX, maxCellX, minCellZ, maxCellZ);

        MapCanvas canvas = new MapCanvas(width, height, BLACK);
        int[] cellColumns = new int[width];
        int[] cellRows = new int[height];
        for (int pixelX = 0; pixelX < width; pixelX++) {
            int blockX = (int) Math.floor(
                    centerX + (pixelX - width / 2.0D) * blocksPerPixel
            );
            cellColumns[pixelX] = Math.floorDiv(Math.floorDiv(blockX, 16), span) - minCellX;
        }
        for (int pixelY = 0; pixelY < height; pixelY++) {
            int blockZ = (int) Math.floor(
                    centerZ - (pixelY - height / 2.0D) * blocksPerPixel
            );
            cellRows[pixelY] = Math.floorDiv(Math.floorDiv(blockZ, 16), span) - minCellZ;
        }
        for (int pixelY = 0; pixelY < height; pixelY++) {
            for (int pixelX = 0; pixelX < width; pixelX++) {
                canvas.setPixelUnchecked(
                        pixelX,
                        pixelY,
                        (byte) cells[cellColumns[pixelX] * cellCountZ + cellRows[pixelY]]
                );
            }
        }
        drawMarkers(canvas);
        return canvas;
    }

    /** Composes cached chunk colors into the requested canvas. */
    private MapCanvas renderCanvas(
            byte[][] chunkColors,
            int minChunkX,
            int minChunkZ,
            int chunkCountZ
    ) {
        MapCanvas canvas = new MapCanvas(width, height, BLACK);
        int[] localXs = new int[width];
        int[] snapshotColumnIndexes = new int[width];
        int[] localZs = new int[height];
        int[] snapshotRowIndexes = new int[height];

        for (int pixelX = 0; pixelX < width; pixelX++) {
            int blockX = (int) Math.floor(
                    centerX + (pixelX - width / 2.0D) * blocksPerPixel
            );
            int chunkX = Math.floorDiv(blockX, 16);
            localXs[pixelX] = Math.floorMod(blockX, 16);
            snapshotColumnIndexes[pixelX] = (chunkX - minChunkX) * chunkCountZ;
        }
        for (int pixelY = 0; pixelY < height; pixelY++) {
            int blockZ = (int) Math.floor(
                    centerZ - (pixelY - height / 2.0D) * blocksPerPixel
            );
            int chunkZ = Math.floorDiv(blockZ, 16);
            localZs[pixelY] = Math.floorMod(blockZ, 16);
            snapshotRowIndexes[pixelY] = chunkZ - minChunkZ;
        }

        for (int pixelY = 0; pixelY < height; pixelY++) {
            for (int pixelX = 0; pixelX < width; pixelX++) {
                byte[] colors = chunkColors[
                        snapshotColumnIndexes[pixelX] + snapshotRowIndexes[pixelY]
                ];
                if (colors == null) {
                    continue;
                }
                int localX = localXs[pixelX];
                int localZ = localZs[pixelY];
                canvas.setPixelUnchecked(pixelX, pixelY, colors[localZ * 16 + localX]);
            }
        }
        drawMarkers(canvas);
        return canvas;
    }

    /** Draws the center cursor and the player world-position marker. */
    private void drawMarkers(MapCanvas canvas) {
        int centerX = canvas.getWidth() / 2;
        int centerY = canvas.getHeight() / 2;
        int playerPixelX = (int) Math.round(MapOverlay.worldToCanvasX(
                playerX,
                this.centerX,
                blocksPerPixel
        ));
        int playerPixelY = (int) Math.round(MapOverlay.worldToCanvasY(
                playerZ,
                this.centerZ,
                blocksPerPixel
        ));
        if (showPlayerMarker) {
            playerTexture.drawCentered(canvas, playerPixelX, playerPixelY);
        }
        if (showWaypoints) {
            for (Waypoint waypoint : waypoints) {
            if (!waypoint.worldName().equals(world.getName())) {
                continue;
            }
            int waypointPixelX = (int) Math.round(MapOverlay.worldToCanvasX(
                    waypoint.x(),
                    this.centerX,
                    blocksPerPixel
            ));
            int waypointPixelY = (int) Math.round(MapOverlay.worldToCanvasY(
                    waypoint.z(),
                    this.centerZ,
                    blocksPerPixel
            ));
            if (waypoint.iconMaterial() != null) {
                if (debugMaterialWaypoints) {
                    waypointHoverTexture.drawCentered(
                            canvas,
                            waypointPixelX,
                            waypointPixelY
                    );
                }
                continue;
            }
            MapTexture texture = waypoint.iconTexture() == null
                    ? (waypoint.id().equals(hoveredWaypointId)
                    ? waypointHoverTexture
                    : waypointTexture)
                    : customWaypointTextures.getOrDefault(
                    waypoint.iconTexture(),
                    waypointTexture
            );
            texture.drawCentered(canvas, waypointPixelX, waypointPixelY);
        }
        }
        cursorTexture.drawCentered(canvas, centerX, centerY);
    }

    /** Returns the compact map color assigned to a material. */
    static byte mapColor(Material material) {
        return MATERIAL_COLORS[material.ordinal()];
    }

    /** Builds the material-to-map-color lookup table once at class load. */
    private static byte[] buildPalette() {
        byte[] palette = new byte[Material.values().length];
        for (Material material : Material.values()) {
            String name = material.name();
            byte color = STONE;
if (material.isAir() || name.contains("GLASS") || name.contains("BARRIER")) {
color = BLACK;
            } else if (name.contains("WATER")
                    || name.contains("KELP")
                    || name.contains("SEAGRASS")) {
                color = WATER;
            } else if (name.contains("LAVA") || name.contains("MAGMA")) {
                color = LAVA;
            } else if (name.contains("SNOW")
                    || name.contains("ICE")
                    || name.contains("QUARTZ")) {
                color = SNOW;
            } else if (name.contains("SAND") || name.contains("TERRACOTTA")) {
                color = SAND;
            } else if (name.contains("WOOD")
                    || name.contains("LOG")
                    || name.contains("PLANK")) {
                color = WOOD;
            } else if (name.contains("DIRT")
                    || name.contains("MUD")
                    || name.contains("FARMLAND")) {
                color = DIRT;
            } else if (name.contains("GRASS_BLOCK")
                    || name.contains("FLOWER")
                    || name.contains("SAPLING")) {
                color = GRASS;
            } else if (name.contains("LEAVES")
                    || name.contains("MOSS")
                    || name.contains("GRASS")) {
                color = LEAVES;
            }
            palette[material.ordinal()] = color;
        }
        return palette;
    }

}
