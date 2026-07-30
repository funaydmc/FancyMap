package dev.funayd.fancyMap.placeholder;

import org.bukkit.OfflinePlayer;

/** Handles a subset of parameters in FancyMap's single PlaceholderAPI expansion. */
@FunctionalInterface
public interface FancyMapPlaceholderHandler {
    /**
     * Resolves a parameter without the {@code fancymap_} namespace.
     *
     * @return the value, or {@code null} when this handler does not own the parameter
     */
    String resolve(OfflinePlayer player, String parameter);
}
