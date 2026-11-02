package de.elia.cameraplugin.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;

/**
 * The team {@code cam_guide}, which hides the name tag of a guide out of
 * camera mode while the names of his tour stand over him in its place, see
 * {@link de.elia.cameraplugin.display.CamNameTag}. The client draws the name
 * tag of a player who is not invisible, and only the option of a team keeps it
 * from doing so.
 *
 * <p>The team does nothing else: whoever is in it is pushed and pushes like
 * anybody. A player can only be in one team, though, and the team of another
 * plugin - a rank with its colour and prefix, say - is not taken from him: a
 * guide in one, or with a scoreboard of his own, stays out, and the names of
 * the tour stand over his name tag instead.</p>
 *
 * <p>The server keeps the teams with the world. So that nobody is left
 * without a name after a crash, the team is deleted at the start and when the
 * plugin is switched off, see {@link #delete()}.</p>
 */
public final class GuideTeam {

    private static final String GUIDE_TEAM = "cam_guide";

    /**
     * Hides his name tag, if no other team has him.
     *
     * @return whether it is hidden now
     */
    public boolean hide(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        if (player.getScoreboard() != scoreboard) {
            return false;
        }
        Team current = scoreboard.getEntryTeam(player.getName());
        if (current != null) {
            return current.getName().equals(GUIDE_TEAM);
        }
        Team team = scoreboard.getTeam(GUIDE_TEAM);
        if (team == null) {
            team = scoreboard.registerNewTeam(GUIDE_TEAM);
        }
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        team.addEntry(player.getName());
        return true;
    }

    /** Whether his name tag is hidden by the team right now - another plugin may have moved him since. */
    public boolean hides(Player player) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(GUIDE_TEAM);
        return team != null && team.hasEntry(player.getName());
    }

    /** Shows his name tag again; the team goes with its last member. */
    public void show(Player player) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(GUIDE_TEAM);
        if (team == null) {
            return;
        }
        team.removeEntry(player.getName());
        if (team.getEntries().isEmpty()) {
            team.unregister();
        }
    }

    /** Deletes the team with all its entries, if it exists. */
    public void delete() {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(GUIDE_TEAM);
        if (team == null) {
            return;
        }
        for (String entry : new HashSet<>(team.getEntries())) {
            team.removeEntry(entry);
        }
        team.unregister();
    }
}
