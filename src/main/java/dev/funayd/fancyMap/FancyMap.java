package dev.funayd.fancyMap;

import dev.funayd.fancyMap.lockview.LockViewController;
import dev.funayd.fancyMap.lockview.LockViewListener;
import dev.funayd.fancyMap.lockview.FancyMapCommand;
import dev.funayd.fancyMap.map.MapCacheListener;
import dev.funayd.fancyMap.placeholder.FancyMapPlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

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
        lockViewController = new LockViewController(this);
        getServer().getPluginManager().registerEvents(
                new LockViewListener(lockViewController),
                this
        );
        getServer().getPluginManager().registerEvents(
                new MapCacheListener(lockViewController.mapRenderCache()),
                this
        );

        FancyMapCommand fancyMapCommand = new FancyMapCommand(lockViewController);
        Objects.requireNonNull(getCommand("fancymap")).setExecutor(fancyMapCommand);
        Objects.requireNonNull(getCommand("fancymap")).setTabCompleter(fancyMapCommand);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion = new FancyMapPlaceholderExpansion(lockViewController);
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
