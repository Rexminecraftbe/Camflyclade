package de.elia.cameraplugin.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * The scoreboard objective {@code cam_mode}: 1 while a player is in camera
 * mode, 0 otherwise.
 */
public final class CamModeObjective {

    private static final String CAM_OBJECTIVE = "cam_mode";

    private Objective camModeObjective;

    /** Takes the objective over, or creates it, and puts everybody on 0. */
    public void setUp() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        camModeObjective = scoreboard.getObjective(CAM_OBJECTIVE);
        if (camModeObjective == null) {
            camModeObjective = scoreboard.registerNewObjective(CAM_OBJECTIVE, Criteria.DUMMY, "Cam Mode");
        }
        for (String entry : scoreboard.getEntries()) {
            camModeObjective.getScore(entry).setScore(0);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            camModeObjective.getScore(p.getName()).setScore(0);
        }
    }

    /** Sets the score of the player, as soon as {@link #setUp()} has run. */
    public void setScore(Player player, int score) {
        if (camModeObjective != null) {
            camModeObjective.getScore(player.getName()).setScore(score);
        }
    }
}
