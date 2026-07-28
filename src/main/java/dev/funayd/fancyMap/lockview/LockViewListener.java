package dev.funayd.fancyMap.lockview;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Bukkit lifecycle and safety listeners for active lock sessions.
 */
public final class LockViewListener implements Listener {
    private final LockViewController lockViewController;

    /**
     * Creates a listener backed by the lock controller.
     *
     * @param lockViewController lock controller
     */
    public LockViewListener(LockViewController lockViewController) {
        this.lockViewController = lockViewController;
    }

    /** Restores the player when they leave the server. */
    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent event) {
        lockViewController.unlock(event.getPlayer());
    }

    /** Disables the map when the locked player dies. */
    @EventHandler
    private void onPlayerDeath(PlayerDeathEvent event) {
        lockViewController.disable(event.getEntity());
    }

    /** Disables the map before a teleport changes the player context. */
    @EventHandler
    private void onPlayerTeleport(PlayerTeleportEvent event) {
        lockViewController.disable(event.getPlayer());
    }

    /** Cancels all damage directed at a locked player. */
    @EventHandler
    private void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player && lockViewController.isLocked(player)) {
            event.setCancelled(true);
        }
    }

    /** Hides newly spawned entities from active map viewers. */
    @EventHandler
    private void onEntitySpawn(EntitySpawnEvent event) {
        lockViewController.hideEntityFromLockedPlayers(event.getEntity());
    }

    /** Hides a newly joined player from active map viewers. */
    @EventHandler
    private void onPlayerJoin(PlayerJoinEvent event) {
        lockViewController.hideEntityFromLockedPlayers(event.getPlayer());
    }
}
