package dev.funayd.fancyMap.lockview;

import dev.funayd.fancyMap.FancyMapMessages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Command executor and tab completer for the FancyMap command.
 */
public final class FancyMapCommand implements CommandExecutor, TabCompleter {
    private final LockViewController lockViewController;

    /**
     * Creates a command bound to a lock controller.
     *
     * @param lockViewController lock controller
     */
    public FancyMapCommand(LockViewController lockViewController) {
        this.lockViewController = lockViewController;
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
            return partialMatches(List.of("config", "debug", "reload"), args[0]);
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
