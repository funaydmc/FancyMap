package dev.funayd.fancyMap;

import dev.funayd.fancyMap.lockview.LockViewController;
import dev.funayd.fancyMap.lockview.LockViewListener;
import dev.funayd.fancyMap.lockview.FancyMapCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class FancyMap extends JavaPlugin {
    private LockViewController lockViewController;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lockViewController = new LockViewController(this);
        getServer().getPluginManager().registerEvents(
                new LockViewListener(lockViewController),
                this
        );

        FancyMapCommand fancyMapCommand = new FancyMapCommand(lockViewController);
        Objects.requireNonNull(getCommand("fancymap")).setExecutor(fancyMapCommand);
        Objects.requireNonNull(getCommand("fancymap")).setTabCompleter(fancyMapCommand);
    }

    @Override
    public void onDisable() {
        if (lockViewController != null) {
            lockViewController.close();
        }
    }

}
