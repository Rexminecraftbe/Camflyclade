package de.elia.cameraplugin.list;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.EndReason;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * {@code /cam tp <player> camera|body}: takes whoever looks after the server
 * to a camera or to the body it belongs to. The buttons behind each line of
 * {@code /cam list} run it, see {@code cam-list.teleport}.
 *
 * <p>Both go as camera mode's own teleport, which ends nobody's camera mode:
 * a teleport landing right at a camera would otherwise end that camera mode
 * and send him to the body instead, see
 * {@link de.elia.cameraplugin.teleport.CamTeleportGuard}. Whoever is in
 * camera mode himself leaves it first, the way he would with {@code /cam}:
 * his body stays behind otherwise, and his distance to it is gone in one
 * jump.</p>
 */
public final class CamListTeleport {

    /** The right the command asks for on top of {@code /cam list}. Operators have it by default. */
    public static final String PERMISSION = "camplugin.teleport";

    /** The player's width and height, for a free spot next to a body. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    /**
     * How far from the body he is set down, in blocks: far enough that the
     * two do not touch - he would push a body that players may push, and
     * that ends its camera mode -, close enough to stand right by it.
     */
    private static final double BESIDE = 1.0;

    /** The sides tried for that spot, one after the other. */
    private static final BlockFace[] SIDES = {BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST};

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;

    public CamListTeleport(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /**
     * Whether this sender may go to cameras and bodies: a player who may see
     * the list and has the right of the command, while {@code cam-list.teleport}
     * is on. The console goes nowhere.
     */
    public boolean mayUse(CommandSender sender) {
        return sender instanceof Player player && settings.isCamListTeleport()
                && plugin.getCamList().mayUse(player) && player.hasPermission(PERMISSION);
    }

    /** {@code /cam tp}, with what follows it. */
    public void command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            messages.sendConfiguredMessage(sender, "no-player");
            return;
        }
        if (!mayUse(viewer)) {
            messages.sendConfiguredMessage(viewer, "no-permission");
            return;
        }
        boolean toCamera = args.length == 2 && args[1].equalsIgnoreCase("camera");
        if (args.length != 2 || (!toCamera && !args[1].equalsIgnoreCase("body"))) {
            messages.sendConfiguredMessage(viewer, "cam-tp-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        CameraData data = target == null ? null : cameraPlayers.get(target.getUniqueId());
        if (data == null) {
            messages.sendConfiguredMessage(viewer, "cam-tp-not-in-cam", "{player}", args[0]);
            return;
        }
        if (target.equals(viewer)) {
            messages.sendConfiguredMessage(viewer, "cam-tp-self");
            return;
        }
        // Asked before camera mode is left: it is his own flight that counts,
        // the one camera mode gives him back.
        if (toCamera && !canFly(viewer)) {
            messages.sendConfiguredMessage(viewer, "cam-tp-cannot-fly");
            return;
        }
        if (plugin.isInCameraMode(viewer)) {
            plugin.exitCameraMode(viewer, EndReason.COMMAND);
            messages.sendConfiguredMessage(viewer, "camera-off");
        }
        Location to = toCamera ? target.getLocation() : besideBody(data.getBody().getLocation());
        if (!plugin.getTeleportGuard().teleport(viewer, to)) {
            return;
        }
        if (toCamera) {
            // The camera hangs in the air, and a teleport sets him down
            // without his flight.
            viewer.setFlying(true);
        }
        messages.sendConfiguredMessage(viewer, toCamera ? "cam-tp-camera" : "cam-tp-body",
                "{player}", target.getName());
    }

    /**
     * Whether the player can stay up in the air where a camera hangs: in
     * creative or spectator mode, or allowed to fly by another plugin. In
     * camera mode that is the flight he had before it.
     */
    private boolean canFly(Player viewer) {
        CameraData own = cameraPlayers.get(viewer.getUniqueId());
        return own != null ? own.getOriginalAllowFlight() : viewer.getAllowFlight();
    }

    /**
     * A spot right next to the body, looking at it: on the first side where a
     * player fits and has ground under his feet. Where there is none, the
     * spot of the body itself.
     */
    private static Location besideBody(Location body) {
        World world = body.getWorld();
        for (BlockFace side : SIDES) {
            Location spot = body.clone().add(side.getModX() * BESIDE, 0.0, side.getModZ() * BESIDE);
            BoundingBox box = new BoundingBox(spot.getX() - HALF_WIDTH, spot.getY(), spot.getZ() - HALF_WIDTH,
                    spot.getX() + HALF_WIDTH, spot.getY() + HEIGHT, spot.getZ() + HALF_WIDTH);
            BoundingBox ground = new BoundingBox(box.getMinX(), box.getMinY() - 0.1, box.getMinZ(),
                    box.getMaxX(), box.getMinY(), box.getMaxZ());
            if (!collides(world, box) && collides(world, ground)) {
                spot.setDirection(body.toVector().subtract(spot.toVector()));
                return spot;
            }
        }
        return body.clone();
    }

    /** Whether a block of the world takes up any of this box. */
    private static boolean collides(World world, BoundingBox box) {
        for (int x = floor(box.getMinX()); x <= floor(box.getMaxX()); x++) {
            for (int y = floor(box.getMinY()); y <= floor(box.getMaxY()); y++) {
                for (int z = floor(box.getMinZ()); z <= floor(box.getMaxZ()); z++) {
                    BoundingBox local = box.clone().shift(-x, -y, -z);
                    if (world.getBlockAt(x, y, z).getCollisionShape().overlaps(local)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
