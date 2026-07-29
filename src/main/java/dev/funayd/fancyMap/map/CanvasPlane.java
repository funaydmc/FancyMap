package dev.funayd.fancyMap.map;

import org.bukkit.Location;

import java.util.Locale;

/**
 * Defines the single coordinate transform shared by the rendered map canvas,
 * its item-frame grid and client-side display entities.
 *
 * <p>Canvas coordinates start at the top-left, with X increasing right and Y
 * increasing down. The item-frame map texture is mirrored horizontally, so
 * logical canvas X runs opposite the physical map-plane right vector.</p>
 */
final class CanvasPlane {
    /*
     * The client floors an item-frame packet position to BlockPos, renders
     * from that block's center, then moves the map quad along its local Z.
     */
    private static final double MAP_FROM_BLOCK_CENTER =
            0.5D - 1.01D / MapOverlay.MAP_SIZE;
    private static final double CHECK_EPSILON = 1.0E-9D;

    private final Location frameGridCenter;
    private final Location surfaceCenter;
    private final double forwardX;
    private final double forwardZ;
    private final double screenRightX;
    private final double screenRightZ;
    private final float yaw;
    private final int width;
    private final int height;

    /**
     * Creates a plane for one cardinal item-frame grid.
     *
     * @param frameGridCenter center of the item-frame entity grid
     * @param frameDirection protocol item-frame direction
     * @param yaw camera yaw
     * @param width canvas width in pixels
     * @param height canvas height in pixels
     * @return unified canvas plane
     */
    static CanvasPlane create(
            Location frameGridCenter,
            int frameDirection,
            float yaw,
            int width,
            int height
    ) {
        return new CanvasPlane(
                frameGridCenter,
                frameDirection,
                yaw,
                width,
                height
        );
    }

    private CanvasPlane(
            Location frameGridCenter,
            int frameDirection,
            float yaw,
            int width,
            int height
    ) {
        this.frameGridCenter = frameGridCenter.clone();
        this.yaw = yaw;
        this.width = width;
        this.height = height;

        switch (frameDirection) {
            case 2 -> {
                forwardX = 0.0D;
                forwardZ = 1.0D;
            }
            case 3 -> {
                forwardX = 0.0D;
                forwardZ = -1.0D;
            }
            case 4 -> {
                forwardX = 1.0D;
                forwardZ = 0.0D;
            }
            case 5 -> {
                forwardX = -1.0D;
                forwardZ = 0.0D;
            }
            default -> throw new IllegalArgumentException(
                    "Invalid item-frame direction: " + frameDirection
            );
        }
        screenRightX = -forwardZ;
        screenRightZ = forwardX;
        surfaceCenter = new Location(
                frameGridCenter.getWorld(),
                Math.floor(frameGridCenter.getX()) + 0.5D
                        + forwardX * MAP_FROM_BLOCK_CENTER,
                Math.floor(frameGridCenter.getY()) + 0.5D,
                Math.floor(frameGridCenter.getZ()) + 0.5D
                        + forwardZ * MAP_FROM_BLOCK_CENTER
        );
    }

    /**
     * Resolves the entity center for one logical canvas tile.
     * Item-frame packet columns run opposite the displayed canvas X axis.
     *
     * @param column zero-based tile column from the canvas left
     * @param row zero-based tile row from the canvas top
     * @param columns total tile columns
     * @param rows total tile rows
     * @return item-frame entity location
     */
    Location frameEntityLocation(
            int column,
            int row,
            int columns,
            int rows
    ) {
        double horizontal = column - (columns - 1) / 2.0D;
        double vertical = (rows - 1) / 2.0D - row;
        return frameGridCenter.clone().add(
                -screenRightX * horizontal,
                vertical,
                -screenRightZ * horizontal
        );
    }

    /**
     * Converts a logical canvas pixel center to a world location.
     *
     * @param canvasX canvas X, including values outside the canvas
     * @param canvasY canvas Y, including values outside the canvas
     * @param towardViewerDepth distance in front of the map surface
     * @return world-space display location
     */
    Location canvasLocation(
            double canvasX,
            double canvasY,
            double towardViewerDepth
    ) {
        double horizontal =
                (canvasX + 0.5D - width / 2.0D) / MapOverlay.MAP_SIZE;
        double vertical =
                (height / 2.0D - canvasY - 0.5D) / MapOverlay.MAP_SIZE;
        Location location = surfaceCenter.clone().add(
                -screenRightX * horizontal - forwardX * towardViewerDepth,
                vertical,
                -screenRightZ * horizontal - forwardZ * towardViewerDepth
        );
        location.setYaw(yaw);
        location.setPitch(0.0F);
        return location;
    }

    /**
     * Describes the active basis and corner coordinates for debug logging.
     *
     * @return compact coordinate-system summary
     */
    String debugSummary() {
        Location topLeft = canvasLocation(0.0D, 0.0D, 0.0D);
        Location bottomRight = canvasLocation(
                width - 1.0D,
                height - 1.0D,
                0.0D
        );
        return String.format(
                Locale.ROOT,
                "canvasBasis forward=(%.0f,%.0f) right=(%.0f,%.0f)"
                        + " frameCenter=(%.3f,%.3f,%.3f)"
                        + " surfaceCenter=(%.3f,%.3f,%.3f)"
                        + " canvasTL=(%.3f,%.3f,%.3f)"
                        + " canvasBR=(%.3f,%.3f,%.3f)",
                forwardX,
                forwardZ,
                screenRightX,
                screenRightZ,
                frameGridCenter.getX(),
                frameGridCenter.getY(),
                frameGridCenter.getZ(),
                surfaceCenter.getX(),
                surfaceCenter.getY(),
                surfaceCenter.getZ(),
                topLeft.getX(),
                topLeft.getY(),
                topLeft.getZ(),
                bottomRight.getX(),
                bottomRight.getY(),
                bottomRight.getZ()
        );
    }

    /**
     * Verifies the canonical south-facing coordinate system at startup.
     */
    static void verifyCoordinateSystem() {
        CanvasPlane plane = create(
                new Location(null, 0.0D, 0.0D, 0.0D),
                2,
                0.0F,
                MapOverlay.CANVAS_WIDTH,
                MapOverlay.CANVAS_HEIGHT
        );
        Location firstFrame = plane.frameEntityLocation(0, 0, 5, 3);
        Location lastFrame = plane.frameEntityLocation(4, 2, 5, 3);
        Location topLeft = plane.canvasLocation(0.0D, 0.0D, 0.0D);
        Location bottomRight = plane.canvasLocation(
                MapOverlay.CANVAS_WIDTH - 1.0D,
                MapOverlay.CANVAS_HEIGHT - 1.0D,
                0.0D
        );

        requireNear(firstFrame.getX(), -2.0D);
        requireNear(firstFrame.getY(), 1.0D);
        requireNear(lastFrame.getX(), 2.0D);
        requireNear(lastFrame.getY(), -1.0D);
        requireNear(topLeft.getX(), -1.99609375D);
        requireNear(topLeft.getY(), 1.99609375D);
        requireNear(bottomRight.getX(), 2.99609375D);
        requireNear(bottomRight.getY(), -0.99609375D);
        requireNear(topLeft.getZ(), 0.5D + MAP_FROM_BLOCK_CENTER);
        requireNear(bottomRight.getZ(), 0.5D + MAP_FROM_BLOCK_CENTER);
        requireNear(
                MapOverlay.canvasXForScreenRight(16.0D),
                MapOverlay.canvasCenterX() - 16.0D
        );
    }

    private static void requireNear(double actual, double expected) {
        if (Math.abs(actual - expected) > CHECK_EPSILON) {
            throw new IllegalStateException(
                    "Invalid canvas coordinate transform: expected "
                            + expected + ", got " + actual
            );
        }
    }
}
