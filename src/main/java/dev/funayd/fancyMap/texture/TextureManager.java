package dev.funayd.fancyMap.texture;

import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads and initializes user-customizable FancyMap PNG textures. */
public final class TextureManager {
    private static final int MAX_TEXTURE_DIMENSION = 128;
    private static final String CURSOR_FILE = "cursor.png";
    private static final String PLAYER_FILE = "player.png";

    private final JavaPlugin plugin;
    private final Path textureDirectory;
    private volatile MapTexture cursor;
    private volatile MapTexture player;

    /**
     * Creates the texture directory and loads its two built-in textures.
     *
     * @param plugin owning plugin
     */
    public TextureManager(JavaPlugin plugin) {
        this.plugin = plugin;
        textureDirectory = plugin.getDataFolder().toPath().resolve("textures");
        reload();
    }

    /** Reloads both PNG files from the user texture directory. */
    public void reload() {
        createDirectory();
        cursor = loadOrCreate(CURSOR_FILE, defaultCursor());
        player = loadOrCreate(PLAYER_FILE, defaultPlayer());
    }

    /** @return texture drawn at the center of the viewport */
    public MapTexture cursor() {
        return cursor;
    }

    /** @return texture drawn at the player's world position */
    public MapTexture player() {
        return player;
    }

    /** Creates the user texture directory when possible. */
    private void createDirectory() {
        try {
            Files.createDirectories(textureDirectory);
        } catch (IOException exception) {
            plugin.getLogger().warning(
                    "Could not create texture directory: " + exception.getMessage()
            );
        }
    }

    /** Loads a user file or writes and uses its default replacement. */
    private MapTexture loadOrCreate(String fileName, BufferedImage fallback) {
        Path file = textureDirectory.resolve(fileName);
        try {
            if (Files.notExists(file)) {
                ImageIO.write(fallback, "png", file.toFile());
                return MapTexture.from(fallback);
            }
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null || !isSupported(image)) {
                throw new IOException("texture must be a PNG up to 128x128");
            }
            return MapTexture.from(image);
        } catch (IOException exception) {
            plugin.getLogger().warning(
                    "Could not load texture " + fileName + ": " + exception.getMessage()
            );
            return MapTexture.from(fallback);
        }
    }

    /** Checks dimensions before converting the image to a map texture. */
    private boolean isSupported(BufferedImage image) {
        return image.getWidth() > 0
                && image.getHeight() > 0
                && image.getWidth() <= MAX_TEXTURE_DIMENSION
                && image.getHeight() <= MAX_TEXTURE_DIMENSION;
    }

    /** Creates the default short, outline-free center cursor. */
    private BufferedImage defaultCursor() {
        BufferedImage image = new BufferedImage(7, 7, BufferedImage.TYPE_INT_ARGB);
        int white = Color.WHITE.getRGB();
        for (int index = 1; index < 6; index++) {
            image.setRGB(3, index, white);
            image.setRGB(index, 3, white);
        }
        return image;
    }

    /** Creates the default blue diamond player marker. */
    private BufferedImage defaultPlayer() {
        BufferedImage image = new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(64, 160, 255, 255));
        int[] xPoints = {4, 8, 4, 0};
        int[] yPoints = {0, 4, 8, 4};
        graphics.fillPolygon(xPoints, yPoints, 4);
        graphics.dispose();
        return image;
    }

}
