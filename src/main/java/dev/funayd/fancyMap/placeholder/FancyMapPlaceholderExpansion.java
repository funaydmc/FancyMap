package dev.funayd.fancyMap.placeholder;

import dev.funayd.fancyMap.lockview.LockViewController;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI expansion for FancyMap player state.
 */
public final class FancyMapPlaceholderExpansion extends PlaceholderExpansion {
    private final LockViewController lockViewController;

    /**
     * Creates the FancyMap PlaceholderAPI expansion.
     *
     * @param lockViewController controller that owns active map sessions
     */
    public FancyMapPlaceholderExpansion(LockViewController lockViewController) {
        this.lockViewController = lockViewController;
    }

    /** @return expansion identifier used by the {@code fancymap} namespace */
    @Override
    public String getIdentifier() {
        return "fancymap";
    }

    /** @return expansion author */
    @Override
    public String getAuthor() {
        return "funaydd";
    }

    /** @return plugin version */
    @Override
    public String getVersion() {
        return "1.0";
    }

    /** @return true so PlaceholderAPI keeps this expansion registered */
    @Override
    public boolean persist() {
        return true;
    }

    /**
     * Resolves FancyMap placeholders.
     *
     * @param player player requesting the value
     * @param params placeholder name without the namespace
     * @return {@code true} or {@code false} for {@code %fancymap_open%}
     */
    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || !params.equalsIgnoreCase("open")) {
            return null;
        }
        return Boolean.toString(lockViewController.isMapOpen(player.getUniqueId()));
    }
}
