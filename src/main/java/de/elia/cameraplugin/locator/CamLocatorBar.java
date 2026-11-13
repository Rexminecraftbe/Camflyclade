package de.elia.cameraplugin.locator;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.scoreboard.Team;

/**
 * The locator bar in camera mode, {@code body.locator-bar}: the point of a
 * camera player stays where he left his body, in his colour, and the camera
 * itself is never on the bar.
 *
 * <p>The game puts every living entity on the bar whose attribute
 * {@code waypoint_transmit_range} is above nought - players have sixty
 * million blocks of it, everything else none. The body is given the range
 * of the player, and the camera player a modifier that takes his to nought
 * for as long as camera mode runs.</p>
 *
 * <p>Whoever is not on the bar when he starts does not get there through
 * his body either: a player wearing a mob head or a carved pumpkin, or
 * drinking a potion of invisibility, is hidden from it, and the game has
 * these through the same attribute, see {@link #pointOf(Player)}. An
 * invisible body ({@code body.visible: false}) gets no point either - what
 * nobody is to see, nobody is to find.</p>
 *
 * <p>The modifier on the camera player is saved with him, Spigot has no
 * other kind. A crash would leave it there for good, and he would be off the
 * bar from then on: whoever joins with it and is not in camera mode loses
 * it, see {@link #onJoin}.</p>
 */
public final class CamLocatorBar implements Listener {

    /**
     * The modifier of the game that takes a crouching player off the bar.
     * It lasts only as long as he crouches, so it does not count for whether
     * his body is to be on it.
     */
    private static final NamespacedKey CROUCH = NamespacedKey.minecraft("waypoint_transmit_range_crouch");

    private final CameraPlugin plugin;
    private final CamSettings settings;
    /** The modifier that takes the camera off the bar. */
    private final NamespacedKey cameraKey;

    public CamLocatorBar(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.cameraKey = new NamespacedKey(plugin, "locator-bar-camera");
    }

    /**
     * The point a player has on the locator bar right now, as his body is to
     * take it over: read at the start of camera mode, before it pauses his
     * effects and puts its own things on him.
     *
     * <p>Whether he is on the bar at all the game decides through his
     * {@code waypoint_transmit_range}, with everything that hides him in
     * it: the invisibility, a head or pumpkin on his head, a range a command
     * or another plugin set. Only crouching is left out, his body does not
     * crouch.</p>
     *
     * <p>The colour is the one the client draws him in, see
     * {@link WaypointColors}: his own, from {@code /waypoint modify}, or that
     * of his team, or the one made up from his id.</p>
     *
     * @return his colour, or {@code null} when he is not on the bar
     */
    public Color pointOf(Player player) {
        AttributeInstance range = player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        if (range == null || standingRange(range) <= 0.0) {
            return null;
        }
        Color own = player.getWaypointColor();
        if (own != null) {
            return own;
        }
        Integer team = teamColor(player);
        return Color.fromRGB(team != null ? team : WaypointColors.ofId(player.getUniqueId()));
    }

    /**
     * What his range would be standing up, worked out the way the game works
     * out an attribute: the base and every added number, that times one plus
     * every share of the base, that times one plus every share of the whole.
     * The modifiers are all of them, of his things and his effects as well,
     * only crouching and the one of this class left out, see
     * {@link #counts}.
     */
    private double standingRange(AttributeInstance range) {
        double base = range.getBaseValue();
        for (AttributeModifier modifier : range.getModifiers()) {
            if (counts(modifier) && modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                base += modifier.getAmount();
            }
        }
        double value = base;
        for (AttributeModifier modifier : range.getModifiers()) {
            if (counts(modifier) && modifier.getOperation() == AttributeModifier.Operation.ADD_SCALAR) {
                value += base * modifier.getAmount();
            }
        }
        for (AttributeModifier modifier : range.getModifiers()) {
            if (counts(modifier) && modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1) {
                value *= 1.0 + modifier.getAmount();
            }
        }
        return value;
    }

    /** Whether a modifier counts for the range of a player standing up: all but crouching and this class's. */
    private boolean counts(AttributeModifier modifier) {
        return !CROUCH.equals(modifier.getKey()) && !cameraKey.equals(modifier.getKey());
    }

    /**
     * The colour of the team he stands in on the main scoreboard, the one
     * the game takes the colour of the bar from.
     *
     * @return the colour as {@code 0xRRGGBB}, or {@code null} for no team
     *         or one without a colour
     */
    private static Integer teamColor(Player player) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(player.getName());
        if (team == null) {
            return null;
        }
        ChatColor color;
        try {
            color = team.getColor();
        } catch (RuntimeException noColor) {
            // A team without a colour may say so this way, depending on the
            // server - it is a team without a colour all the same.
            return null;
        }
        return WaypointColors.ofTeam(color);
    }

    /**
     * Swaps the camera for the body on the bar, in the tick the camera flies
     * off - the one camera mode turns him invisible in.
     *
     * <p>Not in the tick the body appears in: a point is only shown once the
     * entity has lived through its first tick, and one that was not shown
     * then is shown to a player only when he next moves. That would leave
     * whoever stands still without the point.</p>
     *
     * <p>The colour goes on before the range: the point goes out with the
     * colour it has when it comes onto the bar.</p>
     */
    public void startFor(Player player, CameraData data) {
        if (!settings.isBodyOnLocatorBar()) {
            return;
        }
        hideCamera(player);
        Color point = data.getLocatorBarPoint();
        if (point == null || !settings.isBodyVisible()) {
            return;
        }
        LivingEntity body = data.getBody();
        AttributeInstance own = player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        AttributeInstance range = body.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        if (own == null || range == null) {
            return;
        }
        body.setWaypointColor(point);
        range.setBaseValue(own.getBaseValue());
    }

    /** Takes the camera off the bar: its range times nought. */
    private void hideCamera(Player player) {
        AttributeInstance range = player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        if (range == null || find(range) != null) {
            return;
        }
        range.addModifier(new AttributeModifier(cameraKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                EquipmentSlotGroup.ANY));
    }

    /**
     * Puts the player back on the bar at the end of camera mode, whatever
     * {@code body.locator-bar} says by now: a reload in between must not leave
     * him off it. His body takes its point along when it is removed.
     */
    public void stopFor(Player player) {
        AttributeInstance range = player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        if (range == null) {
            return;
        }
        AttributeModifier modifier = find(range);
        if (modifier != null) {
            range.removeModifier(modifier);
        }
    }

    private AttributeModifier find(AttributeInstance range) {
        for (AttributeModifier modifier : range.getModifiers()) {
            if (cameraKey.equals(modifier.getKey())) {
                return modifier;
            }
        }
        return null;
    }

    /**
     * A player joining with the modifier: camera mode never ended for the
     * state of him the world saved, a crash came first. He is back on the
     * bar, file of the crash protection or not.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        giveBack(event.getPlayer());
    }

    /** The same for everybody online when the plugin is started by hand on a running server. */
    public void giveBackOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            giveBack(player);
        }
    }

    private void giveBack(Player player) {
        if (!plugin.isInCameraMode(player)) {
            stopFor(player);
        }
    }
}
