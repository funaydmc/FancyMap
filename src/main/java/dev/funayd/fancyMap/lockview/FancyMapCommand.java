package dev.funayd.fancyMap.lockview;

import dev.funayd.fancyMap.FancyMapMessages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import dev.funayd.fancyMap.map.WaypointListGui;

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
            lockViewController.reload();
            sender.sendMessage(FancyMapMessages.text("Reloaded config and textures."));
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("waypoint")) {
            if (args[1].equalsIgnoreCase("list") && args.length == 2) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(FancyMapMessages.text("§cLệnh này chỉ dành cho người chơi."));
                    return true;
                }
                waypointListGui.open(player);
                return true;
            }
            if (args[1].equalsIgnoreCase("seek") && args.length == 3) {
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
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(FancyMapMessages.text("§cLệnh này chỉ dành cho người chơi."));
                    return true;
                }
                if (!lockViewController.teleportToWaypoint(player, args[2])) {
                    player.sendMessage(FancyMapMessages.text("§cKhông tìm thấy waypoint hoặc thế giới không khả dụng."));
                }
                return true;
            }
            if (!sender.hasPermission("fancymap.admin")) {
                sender.sendMessage(FancyMapMessages.text("§cBạn không có quyền quản lý waypoint."));
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
            lockViewController.toggle(player);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            boolean enabled = lockViewController.toggleDebug();
            player.sendMessage(FancyMapMessages.text(
                    enabled ? "§aĐã bật chế độ debug." : "§cĐã tắt chế độ debug."
            ));
            return true;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("config")) {
            double value;
            try {
                value = Double.parseDouble(args[2]);
            } catch (NumberFormatException exception) {
                player.sendMessage(FancyMapMessages.text("§cGiá trị config phải là số."));
                return true;
            }

            if (!lockViewController.updateConfig(args[1], value)) {
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
            return partialMatches(List.of("config", "debug", "reload", "waypoint"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("waypoint")) {
            return partialMatches(List.of("create", "icon", "list", "remove", "seek", "tp"), args[1]);
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
