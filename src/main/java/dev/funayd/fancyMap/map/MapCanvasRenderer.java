package dev.funayd.fancyMap.map;

/**
 * Synchronous canvas drawing callback used by simple overlays.
 */
@FunctionalInterface
public interface MapCanvasRenderer {
    /**
     * Draws onto the supplied canvas.
     *
     * @param canvas target canvas
     */
    void render(MapCanvas canvas);
}
