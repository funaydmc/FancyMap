package dev.funayd.fancyMap;

import dev.funayd.fancyMap.lockview.LockViewController;
import dev.funayd.fancyMap.lockview.LockViewListener;
import dev.funayd.fancyMap.command.FancyMapCommand;
import dev.funayd.fancyMap.config.ConfigManager;
import dev.funayd.fancyMap.config.ChunkSchedulerSettings;
import dev.funayd.fancyMap.config.MapSettings;
import dev.funayd.fancyMap.config.WaypointDisplaySettings;
import dev.funayd.fancyMap.map.MapCacheListener;
import dev.funayd.fancyMap.map.MapOverlay;
import dev.funayd.fancyMap.map.ClientCanvasDisplayHelper;
import dev.funayd.fancyMap.map.PersistentChunkRenderCache;
import dev.funayd.fancyMap.waypoint.WaypointManager;
import dev.funayd.fancyMap.waypoint.WaypointListGui;
import dev.funayd.fancyMap.placeholder.FancyMapPlaceholderExpansion;
import dev.funayd.fancyMap.placeholder.WaypointPlaceholderHandler;
import dev.funayd.fancyMap.texture.TextureManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plugin entry point and dependency registration for FancyMap.
 */
public final class FancyMap extends JavaPlugin {
    private LockViewController lockViewController;
    private FancyMapPlaceholderExpansion placeholderExpansion;

    @Override
    /**
     * Initializes controllers, listeners and the command.
     */
    public void onEnable() {
        saveDefaultConfig();
        AtomicBoolean debugEnabled = new AtomicBoolean();
        ConfigManager configManager = new ConfigManager(this);
        MapSettings mapSettings = new MapSettings(configManager);
        ChunkSchedulerSettings chunkSchedulerSettings = new ChunkSchedulerSettings(configManager);
        WaypointPlaceholderHandler waypointPlaceholderHandler = new WaypointPlaceholderHandler(this);
        WaypointDisplaySettings waypointDisplaySettings = new WaypointDisplaySettings(
                configManager,
                waypointPlaceholderHandler
        );
        configManager.save();
        MapOverlay mapOverlay = new MapOverlay(this, debugEnabled::get);
        ClientCanvasDisplayHelper canvasDisplays = new ClientCanvasDisplayHelper(mapOverlay);
        PersistentChunkRenderCache mapRenderCache = new PersistentChunkRenderCache(this);
        WaypointManager waypointManager = new WaypointManager(configManager);
        lockViewController = new LockViewController(
                this,
                configManager,
                mapSettings,
                chunkSchedulerSettings,
                mapOverlay,
                canvasDisplays,
                mapRenderCache,
                new TextureManager(this),
                waypointManager,
                waypointDisplaySettings,
                debugEnabled
        );
        getServer().getPluginManager().registerEvents(
                new LockViewListener(lockViewController),
                this
        );
        getServer().getPluginManager().registerEvents(
                new MapCacheListener(mapRenderCache),
                this
        );

        WaypointListGui waypointListGui = new WaypointListGui(
                lockViewController.waypointManager(),
                waypointDisplaySettings,
                (player, id) -> {
                    if (!lockViewController.focusWaypoint(player, id)) {
                        player.sendMessage(FancyMapMessages.text(
                                "§cWaypoint hoặc world không khả dụng."
                        ));
                    }
                }
        );
        getServer().getPluginManager().registerEvents(waypointListGui, this);
        FancyMapCommand fancyMapCommand = new FancyMapCommand(
                lockViewController,
                waypointListGui
        );
        Objects.requireNonNull(getCommand("fancymap")).setExecutor(fancyMapCommand);
        Objects.requireNonNull(getCommand("fancymap")).setTabCompleter(fancyMapCommand);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion = new FancyMapPlaceholderExpansion(lockViewController);
            placeholderExpansion.registerHandler(waypointPlaceholderHandler);
            placeholderExpansion.register();
        }
    }

    @Override
    /**
     * Releases active sessions and asynchronous render resources.
     */
    public void onDisable() {
        if (lockViewController != null) {
            lockViewController.close();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
    }

}
