package dev.funayd.fancyMap.texture;

import dev.funayd.fancyMap.map.MapCanvas;
import org.bukkit.map.MapPalette;

import java.awt.image.BufferedImage;

/** Immutable indexed-color texture that can be drawn onto a map canvas. */
public final class MapTexture {
    private final int width;
    private final int height;
    private final byte[] colors;
    private final boolean[] opaque;

    private MapTexture(int width, int height, byte[] colors, boolean[] opaque) {
        this.width = width;
        this.height = height;
        this.colors = colors;
        this.opaque = opaque;
    }

    /**
     * Converts a PNG image into Minecraft map palette colors.
     *
     * @param image source image
     * @return immutable map texture
     */
    @SuppressWarnings("removal")
    public static MapTexture from(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] colors = new byte[width * height];
        boolean[] opaque = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int index = y * width + x;
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha < 128) {
                    continue;
                }
                opaque[index] = true;
                colors[index] = MapPalette.matchColor(
                        (argb >>> 16) & 0xFF,
                        (argb >>> 8) & 0xFF,
                        argb & 0xFF
                );
            }
        }
        return new MapTexture(width, height, colors, opaque);
    }

    /**
     * Draws the texture with its center at the requested canvas coordinate.
     *
     * @param canvas destination canvas
     * @param centerX center X coordinate
     * @param centerY center Y coordinate
     */
    public void drawCentered(MapCanvas canvas, int centerX, int centerY) {
        draw(canvas, centerX - width / 2, centerY - height / 2);
    }

    /**
     * Draws the texture with its top-left corner at the requested coordinate.
     *
     * @param canvas destination canvas
     * @param x top-left X coordinate
     * @param y top-left Y coordinate
     */
    public void draw(MapCanvas canvas, int x, int y) {
        for (int textureY = 0; textureY < height; textureY++) {
            for (int textureX = 0; textureX < width; textureX++) {
                int index = textureY * width + textureX;
                if (opaque[index]) {
                    canvas.setPixel(
                            x + textureX,
                            y + textureY,
                            colors[index]
                    );
                }
            }
        }
    }
}
