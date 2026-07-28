package dev.funayd.fancyMap.map;

import java.util.Arrays;

public final class MapCanvas {
    private final int width;
    private final int height;
    private final byte[] pixels;

    public MapCanvas(int width, int height, byte background) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Canvas dimensions must be positive.");
        }
        this.width = width;
        this.height = height;
        pixels = new byte[width * height];
        clear(background);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void clear(byte color) {
        Arrays.fill(pixels, color);
    }

    public void setPixel(int x, int y, byte color) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            pixels[y * width + x] = color;
        }
    }

    public byte getPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return 0;
        }
        return pixels[y * width + x];
    }

    public void fillRect(int x, int y, int rectWidth, int rectHeight, byte color) {
        if (rectWidth <= 0 || rectHeight <= 0) {
            return;
        }
        int fromX = Math.max(0, x);
        int toX = Math.min(width, x + rectWidth);
        int fromY = Math.max(0, y);
        int toY = Math.min(height, y + rectHeight);
        if (fromX >= toX || fromY >= toY) {
            return;
        }
        for (int row = fromY; row < toY; row++) {
            Arrays.fill(pixels, row * width + fromX, row * width + toX, color);
        }
    }

    public void drawRect(int x, int y, int rectWidth, int rectHeight, byte color) {
        if (rectWidth <= 0 || rectHeight <= 0) {
            return;
        }
        for (int px = x; px < x + rectWidth; px++) {
            setPixel(px, y, color);
            setPixel(px, y + rectHeight - 1, color);
        }
        for (int py = y; py < y + rectHeight; py++) {
            setPixel(x, py, color);
            setPixel(x + rectWidth - 1, py, color);
        }
    }

    public void drawLine(int x1, int y1, int x2, int y2, byte color) {
        int dx = Math.abs(x2 - x1);
        int sx = x1 < x2 ? 1 : -1;
        int dy = -Math.abs(y2 - y1);
        int sy = y1 < y2 ? 1 : -1;
        int error = dx + dy;

        while (true) {
            setPixel(x1, y1, color);
            if (x1 == x2 && y1 == y2) {
                return;
            }
            int doubledError = 2 * error;
            if (doubledError >= dy) {
                error += dy;
                x1 += sx;
            }
            if (doubledError <= dx) {
                error += dx;
                y1 += sy;
            }
        }
    }

    public byte[] copyRegion(int x, int y, int regionWidth, int regionHeight) {
        if (regionWidth <= 0 || regionHeight <= 0
                || x < 0 || y < 0
                || x + regionWidth > width
                || y + regionHeight > height) {
            throw new IllegalArgumentException("Region is outside the canvas.");
        }

        byte[] region = new byte[regionWidth * regionHeight];
        for (int row = 0; row < regionHeight; row++) {
            System.arraycopy(
                    pixels,
                    (y + row) * width + x,
                    region,
                    row * regionWidth,
                    regionWidth
            );
        }
        return region;
    }
}
