package dev.funayd.fancyMap.map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import dev.funayd.fancyMap.FancyMapMessages;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Manages the client-side item-frame map overlay and its render workers.
 */
public final class MapOverlay implements AutoCloseable {
    private static final int FIRST_FRAME_ENTITY_ID = 1_500_000_000;
    private static final int FIRST_MAP_ID = 1_000_000_000;
    private static final double MAP_PLACEMENT_DISTANCE = 1.0D;
    public static final int MAP_SIZE = 128;
    private static final int COLUMNS = 5;
    private static final int ROWS = 3;
    public static final int CANVAS_WIDTH = COLUMNS * MAP_SIZE;
    public static final int CANVAS_HEIGHT = ROWS * MAP_SIZE;
    private static final byte TRANSPARENT_COLOR = 0;
    private static final byte BORDER_COLOR = 110;

    private final JavaPlugin plugin;
    private final BooleanSupplier debugEnabled;
    private final ExecutorService renderExecutor;
    private final ClientMapPacketTransport packetTransport;
    private final AtomicInteger nextFrameEntityId =
            new AtomicInteger(FIRST_FRAME_ENTITY_ID);
    private final AtomicInteger nextMapId = new AtomicInteger(FIRST_MAP_ID);
    private final AtomicInteger renderThreadId = new AtomicInteger();
    private final Map<UUID, RenderSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * Creates a bounded asynchronous overlay manager.
     *
     * @param plugin owning plugin
     * @param debugEnabled debug state supplier
     */
    public MapOverlay(JavaPlugin plugin, BooleanSupplier debugEnabled) {
        this.plugin = plugin;
        this.debugEnabled = debugEnabled;
        packetTransport = new ClientMapPacketTransport(plugin, debugEnabled);
        CanvasPlane.verifyCoordinateSystem();
        int threadCount = Math.min(4, Math.max(2,
                Runtime.getRuntime().availableProcessors() / 2));
        renderExecutor = Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable,
                    "FancyMap-MapRenderer-" + renderThreadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Shows the default diagnostic canvas.
     *
     * @param player target player
     * @param cameraOrigin camera origin
     * @param yaw camera yaw
     */
    public void show(Player player, Location cameraOrigin, float yaw) {
        show(player, cameraOrigin, yaw, this::renderDefault);
    }

    /**
     * Shows a synchronously rendered canvas.
     *
     * @param player target player
     * @param cameraOrigin camera origin
     * @param yaw camera yaw
     * @param renderer canvas renderer
     */
    public void show(
            Player player,
            Location cameraOrigin,
            float yaw,
            MapCanvasRenderer renderer
    ) {
        hide(player);

        RenderSession session = createSession(cameraOrigin, yaw);
        activeSessions.put(player.getUniqueId(), session);
        logCoordinateSystem(player, session);
        CompletableFuture
                .supplyAsync(() -> render(renderer), renderExecutor)
                .thenAccept(rendered -> Bukkit.getScheduler().runTask(
                        plugin,
                        () -> sendRendered(player, session, rendered, true)
                ))
                .exceptionally(exception -> {
                    plugin.getLogger().warning("Map render failed: " + exception.getCause());
                    return null;
                });
    }

    /**
     * Shows an asynchronously rendered canvas.
     *
     * @param player target player
     * @param cameraOrigin camera origin
     * @param yaw camera yaw
     * @param renderer asynchronous renderer
     */
    public void showAsync(
            Player player,
            Location cameraOrigin,
            float yaw,
            AsyncMapCanvasRenderer renderer
    ) {
        showAsync(player, cameraOrigin, yaw, renderer, null);
    }

    /**
     * Shows an asynchronous canvas and invokes a main-thread completion callback.
     *
     * @param player target player
     * @param cameraOrigin camera origin
     * @param yaw camera yaw
     * @param renderer asynchronous renderer
     * @param completion completion callback, nullable
     */
    public void showAsync(
            Player player,
            Location cameraOrigin,
            float yaw,
            AsyncMapCanvasRenderer renderer,
            Runnable completion
    ) {
        hide(player);
        RenderSession session = createSession(cameraOrigin, yaw);
        activeSessions.put(player.getUniqueId(), session);
        logCoordinateSystem(player, session);
        renderAsync(player, session, startAsyncRender(renderer), true, completion);
    }

    /**
     * Refreshes an active overlay without respawning its frames.
     *
     * @param player target player
     * @param renderer asynchronous renderer
     * @param completion completion callback, nullable
     */
    public void refreshAsync(
            Player player,
            AsyncMapCanvasRenderer renderer,
            Runnable completion
    ) {
        RenderSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            if (completion != null) {
                completion.run();
            }
            return;
        }
        renderAsync(player, session, startAsyncRender(renderer), !session.framesSpawned, completion);
    }

    /** Cancels the in-flight render for one active overlay. */
    public void cancelRender(Player player) {
        RenderSession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            session.cancelRender();
        }
    }

    /**
     * Calculates the normalized map-plane center in front of a camera.
     *
     * @param origin camera origin
     * @param yaw camera yaw
     * @return normalized map center
     */
    public static Location mapCenter(Location origin, float yaw) {
        double yawRadians = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        Location center = origin.clone();
        center.setX(Math.rint(origin.getX() + forwardX * MAP_PLACEMENT_DISTANCE));
        center.setY(Math.rint(origin.getY()));
        center.setZ(Math.rint(origin.getZ() + forwardZ * MAP_PLACEMENT_DISTANCE));
        return center;
    }

    /**
     * Returns the canvas X coordinate at the visual center of the map.
     *
     * @return center canvas X coordinate
     */
    public static double canvasCenterX() {
        return CANVAS_WIDTH / 2.0D;
    }

    /**
     * Returns the canvas Y coordinate at the visual center of the map.
     *
     * @return center canvas Y coordinate
     */
    public static double canvasCenterY() {
        return CANVAS_HEIGHT / 2.0D;
    }

    /**
     * Converts a visual rightward screen offset to the mirrored map canvas X.
     *
     * <p>Item-frame maps mirror the logical canvas horizontally. Positive
     * screen-right values therefore decrease logical canvas X.</p>
     *
     * @param screenRightPixels pixels to the player's right from the center
     * @return logical canvas X coordinate
     */
    public static double canvasXForScreenRight(double screenRightPixels) {
        return canvasCenterX() - screenRightPixels;
    }

    /**
     * Converts a world X coordinate to the logical canvas X coordinate.
     *
     * @param worldX world X coordinate
     * @param centerX rendered map center X
     * @param blocksPerPixel rendered map scale
     * @return horizontal pixel coordinate on the combined canvas
     */
    public static double worldToCanvasX(
            double worldX,
            double centerX,
            double blocksPerPixel
    ) {
        return canvasCenterX() + (worldX - centerX) / blocksPerPixel;
    }

    /**
     * Converts a world Z coordinate to the logical canvas Y coordinate.
     *
     * @param worldZ world Z coordinate
     * @param centerZ rendered map center Z
     * @param blocksPerPixel rendered map scale
     * @return vertical pixel coordinate on the combined canvas
     */
    public static double worldToCanvasY(
            double worldZ,
            double centerZ,
            double blocksPerPixel
    ) {
        return canvasCenterY() - (worldZ - centerZ) / blocksPerPixel;
    }

    /**
     * Converts a canvas coordinate to a point on the active map plane.
     *
     * @param player target player
     * @param canvasX horizontal canvas coordinate, including values outside the canvas
     * @param canvasY vertical canvas coordinate, including values outside the canvas
     * @param depth distance toward the camera from the map surface
     * @return world position, or {@code null} when no overlay is active
     */
    public Location canvasLocation(
            Player player,
            double canvasX,
            double canvasY,
            double depth
    ) {
        RenderSession session = activeSessions.get(player.getUniqueId());
        return session == null ? null : session.canvasLocation(canvasX, canvasY, depth);
    }

    /**
     * Hides and destroys the active client-side overlay.
     *
     * @param player target player
     */
    public void hide(Player player) {
        RenderSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        session.active = false;
        session.cancelRender();
        if (player.isOnline()) {
            packetTransport.destroy(player, session.entityIds());
        }
    }

    /**
     * Deactivates sessions and stops render workers.
     */
    @Override
    public void close() {
        for (RenderSession session : activeSessions.values()) {
            session.active = false;
            session.cancelRender();
        }
        activeSessions.clear();
        renderExecutor.shutdownNow();
    }

    /** Creates the fixed 5×3 client-side frame layout. */
    private RenderSession createSession(Location cameraOrigin, float yaw) {
        Location center = mapCenter(cameraOrigin, yaw);
        int frameDirection = frameDirection(yaw);
        CanvasPlane canvasPlane = CanvasPlane.create(
                center,
                frameDirection,
                yaw,
                CANVAS_WIDTH,
                CANVAS_HEIGHT
        );
        ClientMapFrame[] frames = new ClientMapFrame[COLUMNS * ROWS];

        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                Location frameLocation = canvasPlane.frameEntityLocation(
                        column,
                        row,
                        COLUMNS,
                        ROWS
                );
                frames[row * COLUMNS + column] = new ClientMapFrame(
                        nextFrameEntityId.getAndDecrement(),
                        nextMapId.getAndIncrement(),
                        frameLocation.getX(),
                        frameLocation.getY(),
                        frameLocation.getZ(),
                        frameDirection
                );
            }
        }
        return new RenderSession(frames, canvasPlane);
    }

    /** Renders and splits a synchronous canvas into map tiles. */
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

    /** Bridges worker rendering to main-thread packet delivery. */
    private void renderAsync(
            Player player,
            RenderSession session,
            CompletionStage<MapCanvas> rendered,
            boolean spawnEntities,
            Runnable completion
    ) {
        session.renderFuture = rendered.toCompletableFuture()
                .thenApplyAsync(this::splitCanvas, renderExecutor)
                .thenAccept(tiles -> Bukkit.getScheduler().runTask(
                        plugin,
                        () -> {
                            sendRendered(player, session, tiles, spawnEntities);
                            if (completion != null) {
                                completion.run();
                            }
                        }
                ))
                .exceptionally(exception -> {
                    plugin.getLogger().warning("Map render failed: " + exception.getCause());
                    if (completion != null) {
                        Bukkit.getScheduler().runTask(plugin, completion);
                    }
                    return null;
                });
    }

    /** Starts the renderer without blocking the caller. */
    private CompletionStage<MapCanvas> startAsyncRender(AsyncMapCanvasRenderer renderer) {
        return renderer.render(renderExecutor);
    }

    /** Splits a combined canvas into mirrored 128×128 map tiles. */
    private byte[][] splitCanvas(MapCanvas canvas) {
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

    /** Mirrors a tile to compensate for item-frame orientation. */
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

    /** Draws the default border-and-diagonal diagnostic canvas. */
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

    /** Sends map data and optionally spawns the client-side frames. */
    private void sendRendered(
            Player player,
            RenderSession session,
            byte[][] tiles,
            boolean spawnEntities
    ) {
        if (!session.active || activeSessions.get(player.getUniqueId()) != session
                || !player.isOnline()) {
            return;
        }

        packetTransport.send(player, session.frames, tiles, spawnEntities);
        session.framesSpawned = true;
    }

    /** Converts yaw into the protocol item-frame direction id. */
    private int frameDirection(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90.0F), 4)) {
            case 0 -> 2;
            case 1 -> 5;
            case 2 -> 3;
            default -> 4;
        };
    }

    /** Logs the canonical canvas basis when map debugging is enabled. */
    private void logCoordinateSystem(Player player, RenderSession session) {
        if (debugEnabled.getAsBoolean()) {
            plugin.getLogger().info(FancyMapMessages.consoleDebug(
                    "player=" + player.getName() + " "
                            + session.canvasPlane.debugSummary()
            ));
        }
    }

    private static final class RenderSession {
        private final ClientMapFrame[] frames;
        private final CanvasPlane canvasPlane;
        private volatile boolean active = true;
        private volatile boolean framesSpawned;
        private volatile CompletableFuture<?> renderFuture;

        private RenderSession(ClientMapFrame[] frames, CanvasPlane canvasPlane) {
            this.frames = frames;
            this.canvasPlane = canvasPlane;
        }

        private int[] entityIds() {
            int[] entityIds = new int[frames.length];
            for (int index = 0; index < frames.length; index++) {
                entityIds[index] = frames[index].entityId();
            }
            return entityIds;
        }

        private Location canvasLocation(double canvasX, double canvasY, double depth) {
            return canvasPlane.canvasLocation(canvasX, canvasY, depth);
        }

        /** Cancels work whose result can no longer be visible to the client. */
        private void cancelRender() {
            CompletableFuture<?> future = renderFuture;
            if (future != null) {
                future.cancel(true);
            }
        }
    }

}
