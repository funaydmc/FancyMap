package dev.funayd.fancyMap.placeholder;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/** Isolates the optional PlaceholderAPI class reference from FancyMap's fallback handler. */
final class PlaceholderApiBridge {
    private PlaceholderApiBridge() {
    }

    /** Resolves a template through PlaceholderAPI. */
    static String resolve(Player player, String template) {
        return PlaceholderAPI.setPlaceholders(player, template);
    }
}
