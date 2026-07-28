package dev.funayd.fancyMap.lockview;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class LockViewListener implements Listener {
    private final LockViewController lockViewController;

    public LockViewListener(LockViewController lockViewController) {
        this.lockViewController = lockViewController;
    }

    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent event) {
        lockViewController.unlock(event.getPlayer());
    }
}
