package de.elia.cameraplugin.scoreboard;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;

/**
 * The team {@code cam_no_push}: camera players walk through everybody else and
 * nobody pushes them around.
 */
public final class NoCollisionTeam {

    private static final String NO_COLLISION_TEAM = "cam_no_push";

    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;

    public NoCollisionTeam(CamSettings settings, CameraPlayers cameraPlayers) {
        this.settings = settings;
        this.cameraPlayers = cameraPlayers;
    }

    /**
     * Creates the team as soon as at least one player needs it, and returns it.
     */
    private Team ensureNoCollisionTeam() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(NO_COLLISION_TEAM);
        if (team == null) {
            team = scoreboard.registerNewTeam(NO_COLLISION_TEAM);
        }
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        // Members see each other through the invisibility, which is what mode
        // CAM lives on. Mode NONE hides the player from everybody, so there it
        // would be a hole.
        team.setCanSeeFriendlyInvisibles(settings.getPlayerVisibilityMode() != VisibilityMode.NONE);
        // The name over a camera player is a text display of its own, see
        // CamNameTag. His name tag would stand there a second time.
        team.setOption(Team.Option.NAME_TAG_VISIBILITY,
                settings.showsPlayerName() ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS);
        return team;
    }

    /** Deletes the team with all its entries, if it exists. */
    public void deleteNoCollisionTeam() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(NO_COLLISION_TEAM);
        if (team == null) return;
        for (String entry : new HashSet<>(team.getEntries())) {
            team.removeEntry(entry);
        }
        team.unregister();
    }

    /** Deletes the team as soon as no player is in camera mode any more. */
    public void deleteNoCollisionTeamIfUnused() {
        if (cameraPlayers.isEmpty()) {
            deleteNoCollisionTeam();
        }
    }

    /**
     * Keeps the team alive exactly as long as at least one player is in
     * camera mode, and brings the membership of every online player up to date.
     */
    public void refreshNoCollisionTeam() {
        if (cameraPlayers.isEmpty()) {
            deleteNoCollisionTeam();
            return;
        }
        ensureNoCollisionTeam();
        for (Player online : Bukkit.getOnlinePlayers()) {
            updateViewerTeam(online);
        }
    }

    public void removePlayerFromNoCollisionTeam(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(NO_COLLISION_TEAM);
        if (team != null) {
            team.removeEntry(player.getName());
        }
        deleteNoCollisionTeamIfUnused();
    }

    /**
     * Keeps the team down to the players who are in camera mode right now.
     *
     * <p>The team switches collisions off for its members, so everybody in it
     * walks through everybody else. Players who were only watching used to be
     * added as well - that let them see through the camera player's invisibility,
     * but it also took collisions away from the whole server as soon as a single
     * player started camera mode. The glowing outline shows the camera player to
     * everybody instead, so nobody else has to join the team.</p>
     */
    public void updateViewerTeam(Player player) {
        if (cameraPlayers.isEmpty()) {
            // Nobody in camera mode -> the team is not needed.
            deleteNoCollisionTeam();
            return;
        }
        Team team = ensureNoCollisionTeam();

        if (cameraPlayers.contains(player.getUniqueId())) {
            if (!team.hasEntry(player.getName())) {
                team.addEntry(player.getName());
            }
        } else {
            if (team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
    }
}
