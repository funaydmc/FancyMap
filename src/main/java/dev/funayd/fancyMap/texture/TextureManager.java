package dev.funayd.fancyMap.texture;

import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Loads and initializes user-customizable FancyMap PNG textures. */
public final class TextureManager {
    private static final int MAX_TEXTURE_DIMENSION = 128;
    private static final String CURSOR_FILE = "cursor.png";
    private static final String PLAYER_FILE = "player.png";
    private static final String WAYPOINT_FILE = "waypoint.png";
    private static final String WAYPOINT_HOVER_FILE = "waypoint_hover.png";

    private final JavaPlugin plugin;
    private final Path textureDirectory;
    private volatile MapTexture cursor;
    private volatile MapTexture player;
    private volatile MapTexture waypoint;
    private volatile MapTexture waypointHover;
    private volatile Map<String, MapTexture> customTextures = Map.of();

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
        waypoint = loadOrCreate(WAYPOINT_FILE, defaultWaypoint(false));
        waypointHover = loadOrCreate(WAYPOINT_HOVER_FILE, defaultWaypoint(true));
        customTextures = loadCustomTextures();
    }

    /** @return texture drawn at the center of the viewport */
    public MapTexture cursor() {
        return cursor;
    }

    /** @return texture drawn at the player's world position */
    public MapTexture player() {
        return player;
    }

    /** @return texture drawn for an unhovered waypoint */
    public MapTexture waypoint() {
        return waypoint;
    }

    /** @return texture drawn for the waypoint under the cursor */
    public MapTexture waypointHover() {
        return waypointHover;
    }

    /** @return immutable custom texture snapshot for async renderers */
    public Map<String, MapTexture> customTextures() {
        return customTextures;
    }

    /** @return sorted custom texture names without {@code .png} */
    public List<String> customTextureNames() {
        return customTextures.keySet().stream().sorted().toList();
    }

    /**
     * Resolves a configured custom texture name.
     *
     * @param name texture name with or without {@code .png}
     * @return normalized loaded name, or {@code null}
     */
    public String resolveCustomTextureName(String name) {
        String normalized = normalizeTextureName(name);
        return normalized != null && customTextures.containsKey(normalized)
                ? normalized
                : null;
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

    /** Loads every non-built-in PNG in the texture directory. */
    private Map<String, MapTexture> loadCustomTextures() {
        Map<String, MapTexture> loaded = new HashMap<>();
        try (Stream<Path> files = Files.list(textureDirectory)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String fileName = file.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".png")
                        || isBuiltIn(fileName)) {
                    return;
                }
                String name = normalizeTextureName(fileName);
                if (name == null) {
                    plugin.getLogger().warning(
                            "Ignoring invalid texture name: " + fileName
                    );
                    return;
                }
                try {
                    BufferedImage image = ImageIO.read(file.toFile());
                    if (image == null || !isSupported(image)) {
                        throw new IOException("texture must be a PNG up to 128x128");
                    }
                    loaded.put(name, MapTexture.from(image));
                } catch (IOException exception) {
                    plugin.getLogger().warning(
                            "Could not load texture " + fileName + ": "
                                    + exception.getMessage()
                    );
                }
            });
        } catch (IOException exception) {
            plugin.getLogger().warning(
                    "Could not scan texture directory: " + exception.getMessage()
            );
        }
        return Map.copyOf(loaded);
    }

    /** Normalizes a command/file texture name and rejects path traversal. */
    private String normalizeTextureName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".png")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized.matches("[a-z0-9_-]+") ? normalized : null;
    }

    /** Checks whether a file is managed by a dedicated built-in texture slot. */
    private boolean isBuiltIn(String fileName) {
        return fileName.equalsIgnoreCase(CURSOR_FILE)
                || fileName.equalsIgnoreCase(PLAYER_FILE)
                || fileName.equalsIgnoreCase(WAYPOINT_FILE)
                || fileName.equalsIgnoreCase(WAYPOINT_HOVER_FILE);
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

    /** Creates the default diamond waypoint marker. */
    private BufferedImage defaultWaypoint(boolean hovered) {
        BufferedImage image = new BufferedImage(15, 15, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(hovered
                ? new Color(255, 220, 64, 255)
                : new Color(255, 96, 64, 255));
        int[] xPoints = {7, 14, 7, 0};
        int[] yPoints = {0, 7, 14, 7};
        graphics.fillPolygon(xPoints, yPoints, 4);
        graphics.dispose();
        return image;
    }

}
