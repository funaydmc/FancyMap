package dev.funayd.fancyMap.lockview;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Displays live FancyMap diagnostics without flooding player chat. */
final class LockViewDebugScoreboard {
    private static final String OBJECTIVE_NAME = "fancymap_debug";

    private final Map<UUID, Scoreboard> originalBoards = new HashMap<>();
    private final Map<UUID, Scoreboard> debugBoards = new HashMap<>();

    /** Shows or refreshes a player's FancyMap debug sidebar. */
    void show(Player player, List<String> lines) {
        UUID playerId = player.getUniqueId();
        Scoreboard board = debugBoards.computeIfAbsent(playerId, ignored -> {
            Scoreboard created = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective objective = created.registerNewObjective(
                    OBJECTIVE_NAME,
                    Criteria.DUMMY,
                    Component.text("FancyMap Debug"),
                    RenderType.INTEGER
            );
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            return created;
        });
        if (player.getScoreboard() != board) {
            originalBoards.putIfAbsent(playerId, player.getScoreboard());
            player.setScoreboard(board);
        }

        for (String entry : board.getEntries()) {
            board.resetScores(entry);
        }
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
    }

    /** Restores the scoreboard that was active before debugging started. */
    void hide(Player player) {
        UUID playerId = player.getUniqueId();
        Scoreboard debugBoard = debugBoards.remove(playerId);
        Scoreboard originalBoard = originalBoards.remove(playerId);
        if (debugBoard != null && player.getScoreboard() == debugBoard) {
            player.setScoreboard(originalBoard == null
                    ? Bukkit.getScoreboardManager().getMainScoreboard()
                    : originalBoard);
        }
    }
}
