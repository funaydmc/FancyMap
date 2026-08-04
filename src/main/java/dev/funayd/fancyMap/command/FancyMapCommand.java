package dev.funayd.fancyMap.command;

import dev.funayd.fancyMap.FancyMapMessages;
import dev.funayd.fancyMap.FancyMapPermissions;
import dev.funayd.fancyMap.lockview.LockViewController;
import dev.funayd.fancyMap.waypoint.WaypointListGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Command executor and tab completer for the FancyMap command.
 */
public final class FancyMapCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ITEM_MATERIALS = Arrays.stream(Material.values())
            .filter(Material::isItem)
            .map(material -> material.name().toLowerCase(Locale.ROOT))
            .toList();
    private static final String SILENT_FLAG = "--slient";

    private final LockViewController lockViewController;
    private final WaypointListGui waypointListGui;

    /**
     * Creates a command bound to a lock controller.
     *
     * @param lockViewController lock controller
     */
    public FancyMapCommand(
            LockViewController lockViewController,
            WaypointListGui waypointListGui
    ) {
        this.lockViewController = lockViewController;
        this.waypointListGui = waypointListGui;
    }

    /** Executes toggle, debug and config subcommands. */
    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        boolean requestedSilent = Arrays.stream(args).anyMatch(this::isSilentFlag);
        args = Arrays.stream(args)
                .filter(argument -> !isSilentFlag(argument))
                .toArray(String[]::new);
        boolean silent = requestedSilent && supportsSilent(args);
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!FancyMapPermissions.require(sender, FancyMapPermissions.RELOAD)) {
                return true;
            }
            lockViewController.reload();
            sender.sendMessage(FancyMapMessages.text("Reloaded config and textures."));
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("waypoint")) {
            if (args[1].equalsIgnoreCase("list") && args.length == 2) {
                if (!require(sender, FancyMapPermissions.WAYPOINT_LIST, silent)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    reply(sender, silent, "§cLệnh này chỉ dành cho người chơi.");
                    return true;
                }
                waypointListGui.open(player);
                return true;
            }
            if (args[1].equalsIgnoreCase("seek") && args.length == 3) {
                if (!require(sender, FancyMapPermissions.USE, silent)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    reply(sender, silent, "§cLệnh này chỉ dành cho người chơi.");
                    return true;
                }
                if (!lockViewController.focusWaypoint(player, args[2], silent)) {
                    reply(player, silent, "§cKhông tìm thấy waypoint hoặc world không khả dụng.");
                }
                return true;
            }
            if (args[1].equalsIgnoreCase("tp") && args.length == 3) {
                if (!require(sender, FancyMapPermissions.WAYPOINT_TELEPORT, silent)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    reply(sender, silent, "§cLệnh này chỉ dành cho người chơi.");
                    return true;
                }
                if (!lockViewController.teleportToWaypoint(player, args[2])) {
                    reply(player, silent, "§cKhông tìm thấy waypoint hoặc thế giới không khả dụng.");
                }
                return true;
            }
            if (!require(sender, FancyMapPermissions.WAYPOINT_MANAGE, silent)) {
                return true;
            }
            if (args[1].equalsIgnoreCase("remove") && args.length == 3) {
                if (lockViewController.waypointManager().remove(args[2])) {
                    lockViewController.reloadWaypoints();
                    reply(sender, silent, "§aĐã xóa waypoint.");
                } else {
                    reply(sender, silent, "§cKhông tìm thấy waypoint.");
                }
                return true;
            }
            if (args[1].equalsIgnoreCase("icon") && args.length == 4) {
                if (lockViewController.updateWaypointIcon(args[2], args[3])) {
                    reply(sender, silent, "§aĐã cập nhật icon waypoint.");
                } else {
                    reply(sender, silent, "§cKhông tìm thấy waypoint, item material hoặc texture.");
                }
                return true;
            }
            if (args[1].equalsIgnoreCase("create") && args.length >= 4) {
                if (!(sender instanceof Player player)) {
                    reply(sender, silent, "§cLệnh create cần được dùng trong game.");
                    return true;
                }
                String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                if (lockViewController.waypointManager().create(player, args[2], name)) {
                    lockViewController.reloadWaypoints();
                    reply(player, silent, "§aĐã tạo waypoint tại vị trí hiện tại.");
                } else {
                    reply(player, silent, "§cID không hợp lệ, tên trống hoặc waypoint đã tồn tại.");
                }
                return true;
            }
            reply(sender, silent,
                    "§cSử dụng: /fm waypoint create <id> <name> | "
                            + "/fm waypoint remove <id> | "
                            + "/fm waypoint icon <id> <material|texture> | "
                            + "/fm waypoint tp <id> | /fm waypoint seek <id> | /fm waypoint list"
            );
            return true;
        }

        if (!(sender instanceof Player player)) {
            reply(sender, silent, "Lệnh này chỉ dành cho người chơi.");
            return true;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("goto")) {
            if (!require(player, FancyMapPermissions.USE, silent)) {
                return true;
            }
            Double x = coordinate(args[1]);
            Double z = coordinate(args[2]);
            if (x == null || z == null || !lockViewController.gotoPosition(player, x, z, silent)) {
                reply(player, silent, "§cTọa độ không hợp lệ.");
            }
            return true;
        }

        if ((args.length == 2 || args.length == 4) && args[0].equalsIgnoreCase("world")) {
            if (!require(player, FancyMapPermissions.USE, silent)) {
                return true;
            }
            World world = Bukkit.getWorld(args[1]);
            Double x = args.length == 4 ? coordinate(args[2]) : 0.0D;
            Double z = args.length == 4 ? coordinate(args[3]) : 0.0D;
            if (world == null || x == null || z == null
                    || !lockViewController.viewWorld(player, world, x, z, silent)) {
                reply(player, silent, "§cWorld hoặc tọa độ không hợp lệ.");
            }
            return true;
        }

        if (args.length == 0) {
            if (!FancyMapPermissions.require(player, FancyMapPermissions.USE)) {
                return true;
            }
            lockViewController.toggle(player);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            if (!FancyMapPermissions.require(player, FancyMapPermissions.DEBUG)) {
                return true;
            }
            boolean enabled = lockViewController.toggleDebug();
            player.sendMessage(FancyMapMessages.text(
                    enabled ? "§aĐã bật chế độ debug." : "§cĐã tắt chế độ debug."
            ));
            return true;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("config")) {
            if (!FancyMapPermissions.require(player, FancyMapPermissions.CONFIG)) {
                return true;
            }
            if (!lockViewController.updateConfig(args[1], args[2])) {
                player.sendMessage(FancyMapMessages.text(
                        "§cConfig không hợp lệ hoặc giá trị không được phép."
                ));
                return true;
            }

            player.sendMessage(FancyMapMessages.text("§aĐã cập nhật config và áp dụng ngay."));
            return true;
        }

        reply(player, silent,
                "§cSử dụng: /fancymap | /fancymap goto <x> <z> | "
                        + "/fancymap world <world> [x] [z] | /fancymap debug | "
                        + "/fancymap config <config_key> <config_value>"
        );
        return true;
    }

    /** Returns whether the supplied arguments support the silent command flag. */
    private boolean supportsSilent(String[] args) {
        return args.length > 0 && (args[0].equalsIgnoreCase("waypoint")
                || args[0].equalsIgnoreCase("goto")
                || args[0].equalsIgnoreCase("world"));
    }

    /** Accepts the requested spelling and the conventional spelling of the flag. */
    private boolean isSilentFlag(String argument) {
        return SILENT_FLAG.equalsIgnoreCase(argument) || "--silent".equalsIgnoreCase(argument);
    }

    /** Checks a permission while honoring the command's no-message mode. */
    private boolean require(CommandSender sender, String permission, boolean silent) {
        return silent ? FancyMapPermissions.has(sender, permission)
                : FancyMapPermissions.require(sender, permission);
    }

    /** Sends a command response unless the caller explicitly requested silence. */
    private void reply(CommandSender sender, boolean silent, String message) {
        if (!silent) {
            sender.sendMessage(FancyMapMessages.text(message));
        }
    }

    /** Completes command subcommands and supported configuration keys. */
    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            if (FancyMapPermissions.has(sender, FancyMapPermissions.CONFIG)) {
                values.add("config");
            }
            if (FancyMapPermissions.has(sender, FancyMapPermissions.DEBUG)) {
                values.add("debug");
            }
            if (FancyMapPermissions.has(sender, FancyMapPermissions.RELOAD)) {
                values.add("reload");
            }
            if (FancyMapPermissions.has(sender, FancyMapPermissions.USE)
                    || FancyMapPermissions.has(sender, FancyMapPermissions.WAYPOINT_LIST)
                    || FancyMapPermissions.has(sender, FancyMapPermissions.WAYPOINT_TELEPORT)
                    || FancyMapPermissions.has(sender, FancyMapPermissions.WAYPOINT_MANAGE)) {
                values.add("waypoint");
            }
            if (FancyMapPermissions.has(sender, FancyMapPermissions.USE)) {
                values.addAll(List.of("goto", "world"));
            }
            return partialMatches(values, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("waypoint")) {
            List<String> values = new ArrayList<>();
            if (FancyMapPermissions.has(sender, FancyMapPermissions.WAYPOINT_MANAGE)) {
                values.addAll(List.of("create", "icon", "remove"));
            }
            if (FancyMapPermissions.has(sender, FancyMapPermissions.WAYPOINT_LIST)) {
                values.add("list");
            }
            if (FancyMapPermissions.has(sender, FancyMapPermissions.USE)) {
                values.add("seek");
            }
            if (FancyMapPermissions.has(sender, FancyMapPermissions.WAYPOINT_TELEPORT)) {
                values.add("tp");
            }
            return partialMatches(values, args[1]);
        }
        if (args.length == 3
                && args[0].equalsIgnoreCase("waypoint")
                && (args[1].equalsIgnoreCase("remove")
                || args[1].equalsIgnoreCase("icon")
                || args[1].equalsIgnoreCase("seek")
                || args[1].equalsIgnoreCase("tp"))) {
            return partialMatches(lockViewController.waypointManager().ids(), args[2]);
        }
        if (args.length == 4
                && args[0].equalsIgnoreCase("waypoint")
                && args[1].equalsIgnoreCase("icon")) {
            List<String> icons = new ArrayList<>(ITEM_MATERIALS);
            icons.addAll(lockViewController.waypointTextureNames());
            return partialMatches(icons, args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("config")) {
            return partialMatches(lockViewController.configKeys(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            return partialMatches(Bukkit.getWorlds().stream().map(World::getName).toList(), args[1]);
        }
        return List.of();
    }

    /** Parses one finite coordinate supplied by a command sender. */
    private Double coordinate(String value) {
        try {
            double coordinate = Double.parseDouble(value);
            return Double.isFinite(coordinate) ? coordinate : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Filters completion values by the current argument prefix. */
    private List<String> partialMatches(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.startsWith(prefix))
                .collect(Collectors.toList());
    }
}
