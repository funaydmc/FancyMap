package dev.funayd.fancyMap.command;

import dev.funayd.fancyMap.FancyMapMessages;
import dev.funayd.fancyMap.FancyMapPermissions;
import dev.funayd.fancyMap.lockview.LockViewController;
import dev.funayd.fancyMap.waypoint.WaypointListGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.Material;

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
                if (!FancyMapPermissions.require(sender, FancyMapPermissions.WAYPOINT_LIST)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(FancyMapMessages.text("§cLệnh này chỉ dành cho người chơi."));
                    return true;
                }
                waypointListGui.open(player);
                return true;
            }
            if (args[1].equalsIgnoreCase("seek") && args.length == 3) {
                if (!FancyMapPermissions.require(sender, FancyMapPermissions.USE)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(FancyMapMessages.text("§cLệnh này chỉ dành cho người chơi."));
                    return true;
                }
                if (!lockViewController.focusWaypoint(player, args[2])) {
                    player.sendMessage(FancyMapMessages.text("§cKhông tìm thấy waypoint hoặc waypoint thuộc thế giới khác."));
                }
                return true;
            }
            if (args[1].equalsIgnoreCase("tp") && args.length == 3) {
                if (!FancyMapPermissions.require(sender, FancyMapPermissions.WAYPOINT_TELEPORT)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(FancyMapMessages.text("§cLệnh này chỉ dành cho người chơi."));
                    return true;
                }
                if (!lockViewController.teleportToWaypoint(player, args[2])) {
                    player.sendMessage(FancyMapMessages.text("§cKhông tìm thấy waypoint hoặc thế giới không khả dụng."));
                }
                return true;
            }
            if (!FancyMapPermissions.require(sender, FancyMapPermissions.WAYPOINT_MANAGE)) {
                return true;
            }
            if (args[1].equalsIgnoreCase("remove") && args.length == 3) {
                if (lockViewController.waypointManager().remove(args[2])) {
                    lockViewController.reloadWaypoints();
                    sender.sendMessage(FancyMapMessages.text("§aĐã xóa waypoint."));
                } else {
                    sender.sendMessage(FancyMapMessages.text("§cKhông tìm thấy waypoint."));
                }
                return true;
            }
            if (args[1].equalsIgnoreCase("icon") && args.length == 4) {
                if (lockViewController.updateWaypointIcon(args[2], args[3])) {
                    sender.sendMessage(FancyMapMessages.text(
                            "§aĐã cập nhật icon waypoint."
                    ));
                } else {
                    sender.sendMessage(FancyMapMessages.text(
                            "§cKhông tìm thấy waypoint, item material hoặc texture."
                    ));
                }
                return true;
            }
            if (args[1].equalsIgnoreCase("create") && args.length >= 4) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(FancyMapMessages.text("§cLệnh create cần được dùng trong game."));
                    return true;
                }
                String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                if (lockViewController.waypointManager().create(player, args[2], name)) {
                    lockViewController.reloadWaypoints();
                    player.sendMessage(FancyMapMessages.text("§aĐã tạo waypoint tại vị trí hiện tại."));
                } else {
                    player.sendMessage(FancyMapMessages.text("§cID không hợp lệ, tên trống hoặc waypoint đã tồn tại."));
                }
                return true;
            }
            sender.sendMessage(FancyMapMessages.text(
                    "§cSử dụng: /fm waypoint create <id> <name> | "
                            + "/fm waypoint remove <id> | "
                            + "/fm waypoint icon <id> <material|texture> | "
                            + "/fm waypoint tp <id> | /fm waypoint seek <id> | /fm waypoint list"
            ));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(FancyMapMessages.text("Lệnh này chỉ dành cho người chơi."));
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

        player.sendMessage(FancyMapMessages.text(
                "§cSử dụng: /fancymap | /fancymap debug | /fancymap config <config_key> <config_value>"
        ));
        return true;
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
        return List.of();
    }

    /** Filters completion values by the current argument prefix. */
    private List<String> partialMatches(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.startsWith(prefix))
                .collect(Collectors.toList());
    }
}
