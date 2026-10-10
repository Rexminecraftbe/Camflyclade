package de.elia.cameraplugin.scoreboard;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;
import java.util.UUID;

/**
 * The teams that keep camera mode out of everybody's way: {@code cam_no_push},
 * in which camera players walk through everybody else and nobody pushes them
 * around, and {@code cam_body}, in which nobody pushes their bodies either.
 */
public final class NoCollisionTeam {

    private static final String NO_COLLISION_TEAM = "cam_no_push";
    /**
     * The team of the mannequins that take the hits, see
     * {@link #refreshBodyTeam()}. A team of their own and not the camera
     * players' one: its members see each other through the invisibility, and
     * the camera player would see the invisible body.
     */
    private static final String BODY_TEAM = "cam_body";

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

    /** Deletes both teams with all their entries, if they exist. */
    public void deleteNoCollisionTeam() {
        deleteTeam(NO_COLLISION_TEAM);
        deleteTeam(BODY_TEAM);
    }

    private void deleteTeam(String name) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(name);
        if (team == null) return;
        for (String entry : new HashSet<>(team.getEntries())) {
            team.removeEntry(entry);
        }
        team.unregister();
    }

    /** Deletes the teams as soon as no player is in camera mode any more. */
    public void deleteNoCollisionTeamIfUnused() {
        if (cameraPlayers.isEmpty()) {
            deleteNoCollisionTeam();
        }
    }

    /**
     * Keeps the teams alive exactly as long as at least one player is in
     * camera mode, and brings the membership of every online player and of
     * every body up to date.
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
        refreshBodyTeam();
    }

    /**
     * Puts the mannequin taking the hits for each camera player into
     * {@code cam_body}, whose collision rule keeps players and mobs from
     * pushing it - for as long as {@code body.movement-sensitivity} does not
     * allow them to.
     *
     * <p>Switching the mannequin non-collidable would keep them off as well,
     * but the server asks the very same switch whether anything can hit it
     * from afar: an arrow, a trident, a wind charge and the stab of a spear
     * went straight through, and only the armour stand of a visible type 1
     * caught them. The collision rule of a team leaves the hits alone.</p>
     *
     * <p>Only the mannequin goes in, the armour stand of type 1 is left as it
     * always was. A body that is removed leaves the team by itself, the server
     * takes every entity out of its team when it goes.</p>
     */
    private void refreshBodyTeam() {
        if (settings.getMovementSensitivity().allowsEntityPush()) {
            deleteTeam(BODY_TEAM);
            return;
        }
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(BODY_TEAM);
        if (team == null) {
            team = scoreboard.registerNewTeam(BODY_TEAM);
        }
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        for (UUID playerId : cameraPlayers.ids()) {
            CameraData data = cameraPlayers.get(playerId);
            // Entities are entered by their id, players by their name.
            String entry = data.getDamageTarget().getUniqueId().toString();
            if (!team.hasEntry(entry)) {
                team.addEntry(entry);
            }
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
