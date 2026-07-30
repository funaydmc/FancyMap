package dev.funayd.fancyMap;

import org.bukkit.command.CommandSender;

/** Central permission names and permission checks for FancyMap. */
public final class FancyMapPermissions {
    public static final String USE = "fancymap.use";
    public static final String DEBUG = "fancymap.debug";
    public static final String CONFIG = "fancymap.config";
    public static final String RELOAD = "fancymap.reload";
    public static final String WAYPOINT_LIST = "fancymap.waypoint.list";
    public static final String WAYPOINT_TELEPORT = "fancymap.waypoint.teleport";
    public static final String WAYPOINT_MANAGE = "fancymap.waypoint.manage";

    private FancyMapPermissions() {
    }

    /** Checks and reports a denied permission. */
    public static boolean require(CommandSender sender, String permission) {
        if (has(sender, permission)) {
            return true;
        }
        sender.sendMessage(FancyMapMessages.text("§cBạn không có quyền: §f" + permission));
        return false;
    }

    /** Checks a permission without sending a message. */
    public static boolean has(CommandSender sender, String permission) {
        return sender.hasPermission(permission);
    }
}
