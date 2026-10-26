package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.movement.FlightMedium;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * Whether the camera may be put down at a spot it does not fly to - a spot of
 * {@code /cam spot}, a shared one of {@code /cam visit}, or where
 * {@code /cam resume} takes it: everything that would hold it back in flight.
 *
 * <p>In the world it is held from, not further than {@code max-distance} from
 * what it is held from - under {@code max-distance-mode: signal} only where the
 * signal reaches -, outside the areas {@code cam-area} keeps it out of, out of
 * shut lava, water and powder snow, and not into a block that stands there.
 * The jump saves the way there and nothing else.</p>
 *
 * <p>Each kind of jump says why not in messages of its own: the prefix
 * {@code spot}, {@code visit} or {@code resume} with {@code -too-far},
 * {@code -no-signal}, {@code -area} and {@code -blocked} after it.</p>
 */
public final class JumpCheck {

    /**
     * How far the box of the camera is drawn in on every side before it is
     * tested against the blocks: a camera that hung on a slab or against a
     * wall touched them, it did not stand in them.
     */
    private static final double TOUCH_MARGIN = 1.0E-3;

    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlugin plugin;

    public JumpCheck(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    /**
     * Whether the camera may be put down at the target, held from the anchor
     * in the same world.
     *
     * @param anchor     what it is held from: the body, or the spot it visits
     * @param signalFrom where the signal goes out from there
     * @param prefix     which messages say why not
     * @param fills      placeholder and value for those messages, one pair
     *                   after the other
     * @return whether it may; if not, he has been told why
     */
    public boolean mayGo(Player player, Location anchor, Location signalFrom, Location target, String prefix,
                         String... fills) {
        double max = settings.getMaxDistance();
        if (settings.limitsDistance() && target.distanceSquared(anchor) > max * max) {
            messages.sendConfiguredMessage(player, prefix + "-too-far",
                    with(fills, "{distance}", String.valueOf(max)));
            return false;
        }
        if (settings.usesSignal() && !plugin.getSignal().reaches(player, signalFrom, target)) {
            messages.sendConfiguredMessage(player, prefix + "-no-signal", fills);
            return false;
        }
        return mayStand(player, target, prefix, fills);
    }

    /**
     * Whether the camera may stand at the target at all, wherever it is held
     * from: for the spot of a visit, which it is held from itself.
     *
     * @return whether it may; if not, he has been told why
     */
    public boolean mayStand(Player player, Location target, String prefix, String... fills) {
        String area = settings.keepsOutOfAreas() ? settings.getCamAreaRules().forbiddenArea(target) : null;
        if (area != null) {
            messages.sendConfiguredMessage(player, prefix + "-area", with(fills, "{area}", area));
            return false;
        }
        FlightMedium medium = FlightMedium.reachedInto(player, settings, null, target);
        if (medium != null) {
            messages.sendMessage(player, medium.getFlightMessage());
            return false;
        }
        if (blocked(player, target)) {
            messages.sendConfiguredMessage(player, prefix + "-blocked", fills);
            return false;
        }
        return true;
    }

    /** The fills with one pair more. */
    private static String[] with(String[] fills, String key, String value) {
        String[] all = new String[fills.length + 2];
        System.arraycopy(fills, 0, all, 0, fills.length);
        all[fills.length] = key;
        all[fills.length + 1] = value;
        return all;
    }

    /**
     * Whether the camera would be stuck in a block at the spot - in one built
     * there since it was saved. A camera does not fly through blocks, and the
     * jump is no way through them either.
     */
    private static boolean blocked(Player player, Location feet) {
        double half = player.getWidth() / 2.0 - TOUCH_MARGIN;
        BoundingBox box = new BoundingBox(feet.getX() - half, feet.getY() + TOUCH_MARGIN, feet.getZ() - half,
                feet.getX() + half, feet.getY() + player.getHeight() - TOUCH_MARGIN, feet.getZ() + half);
        World world = feet.getWorld();
        for (int x = Location.locToBlock(box.getMinX()); x <= Location.locToBlock(box.getMaxX()); x++) {
            for (int y = Location.locToBlock(box.getMinY()); y <= Location.locToBlock(box.getMaxY()); y++) {
                for (int z = Location.locToBlock(box.getMinZ()); z <= Location.locToBlock(box.getMaxZ()); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    for (BoundingBox part : block.getCollisionShape().getBoundingBoxes()) {
                        if (part.clone().shift(x, y, z).overlaps(box)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
