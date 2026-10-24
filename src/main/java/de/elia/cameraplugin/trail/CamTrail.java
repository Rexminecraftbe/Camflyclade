package de.elia.cameraplugin.trail;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@code /cam trail <player>}: shows where the camera of a player flew, as a
 * line of particles that only the one who asked sees, {@code cam-trail}. So an
 * admin sees at a glance who was scouting where - the log of {@code cam-log}
 * only says where a flight began and ended.
 *
 * <p>The line runs from green at the body over yellow to red where the camera
 * was last, which tells the way it went. While the player is still in camera
 * mode it is his flight so far, and it grows as he flies on; otherwise it is
 * his last one. {@code /cam trail} on its own hides it again.</p>
 *
 * <p>The flights are kept in memory only, the last one of each player, until
 * the server stops - and only while {@code cam-trail} is on.</p>
 */
public final class CamTrail implements Listener {

    /** The right the command asks for. Operators have it by default. */
    public static final String PERMISSION = "camplugin.trail";

    /** Ticks between two times the line is drawn. A speck of dust lasts about that long. */
    private static final long INTERVAL = 10L;

    /**
     * How far from the viewer the line is drawn, in blocks. The particles are
     * sent with the flag that lets a client show them that far, which the
     * server keeps to 512 blocks.
     */
    private static final double REACH = 128.0;

    /** How far apart the specks are at most, in blocks. */
    private static final double SPACING = 0.5;

    /**
     * The most specks drawn at once. A longer line within reach is drawn with
     * them further apart.
     */
    private static final int MAX_PARTICLES = 2000;

    /** How far above the feet the line runs, in blocks: about the middle of the player. */
    private static final double HEIGHT = 1.0;

    /** The size of a speck, and of the two at the ends of the line. */
    private static final float SIZE = 1.0f;
    private static final float END_SIZE = 2.5f;

    /** The colours from the body to the camera: green, yellow and red. */
    private static final int[] GREEN = {40, 220, 60};
    private static final int[] YELLOW = {235, 220, 40};
    private static final int[] RED = {230, 40, 40};

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** The last flight of each player, or the one under way. */
    private final Map<UUID, FlightTrail> trails = new HashMap<>();
    /** Who is being shown a flight: the viewer, and the task drawing it for him. */
    private final Map<UUID, BukkitRunnable> viewers = new HashMap<>();

    public CamTrail(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    // ------------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------------

    /** Starts to record the flight of a player who has just entered camera mode. */
    public void startFor(Player player, Location body) {
        if (settings.isTrailEnabled()) {
            trails.put(player.getUniqueId(), new FlightTrail(player.getUniqueId(), player.getName(), body));
        }
    }

    /**
     * Ends the flight of a player leaving camera mode.
     *
     * @param cameraAt where the camera was, before he is put back at his body
     */
    public void stopFor(Player player, Location cameraAt) {
        FlightTrail trail = trails.get(player.getUniqueId());
        if (trail != null && trail.isFlying()) {
            trail.end(cameraAt);
        }
    }

    /**
     * Notes a step of the camera. A step that something turned away did not
     * happen; a teleport is an event of its own and never comes here.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        FlightTrail trail = trails.get(event.getPlayer().getUniqueId());
        if (trail != null && trail.isFlying()) {
            trail.moved(event.getFrom(), to);
        }
    }

    // ------------------------------------------------------------------------
    // The command
    // ------------------------------------------------------------------------

    /**
     * Whether this sender may look at the flights of others: a player with
     * the right of the command, and the console, which is the server.
     */
    public boolean mayUse(CommandSender sender) {
        return settings.isTrailEnabled()
                && (!(sender instanceof Player player) || player.hasPermission(PERMISSION));
    }

    /** {@code /cam trail}, with what follows it. */
    public void command(CommandSender sender, String[] args) {
        if (!settings.isTrailEnabled()) {
            messages.sendConfiguredMessage(sender, "trail-off");
            return;
        }
        if (!mayUse(sender)) {
            messages.sendConfiguredMessage(sender, "no-permission");
            return;
        }
        if (args.length == 0 && sender instanceof Player viewer && hide(viewer)) {
            messages.sendConfiguredMessage(viewer, "trail-hidden");
            return;
        }
        if (args.length != 1) {
            messages.sendConfiguredMessage(sender, "trail-usage");
            return;
        }
        FlightTrail trail = find(args[0]);
        if (trail == null) {
            messages.sendConfiguredMessage(sender, "trail-none", "{player}", args[0]);
            return;
        }
        describe(sender, trail);
        if (sender instanceof Player viewer) {
            show(viewer, trail);
        }
    }

    /** The names of the players a flight is kept of, for the tab completion. */
    public List<String> recordedNames() {
        List<String> names = new ArrayList<>();
        for (FlightTrail trail : trails.values()) {
            names.add(trail.playerName());
        }
        return names;
    }

    /** The flight of a player by his name, as it was when he flew it. */
    private FlightTrail find(String name) {
        for (FlightTrail trail : trails.values()) {
            if (trail.playerName().equalsIgnoreCase(name)) {
                return trail;
            }
        }
        return null;
    }

    /**
     * Says how long and how far the flight went, whether it still goes on,
     * and where it began and ended - also to the console, which sees no
     * particles.
     */
    private void describe(CommandSender sender, FlightTrail trail) {
        String when;
        if (trail.isFlying()) {
            when = messages.getMessage("trail-flying");
        } else {
            long ago = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - trail.endedAt());
            when = messages.getMessage("trail-ago").replace("{time}", CamTimeLimit.formatDuration(ago));
        }
        messages.sendConfiguredMessage(sender, "trail-header",
                "{player}", trail.playerName(),
                "{distance}", String.valueOf(Math.round(trail.distanceFlown())),
                "{time}", CamTimeLimit.formatDuration(TimeUnit.MILLISECONDS.toSeconds(trail.duration())),
                "{when}", when);
        messages.sendConfiguredMessage(sender, "trail-from", "{place}", place(trail.start()));
        messages.sendConfiguredMessage(sender, "trail-to", "{place}", place(trail.last()));
    }

    /** A spot as {@code trail-place} writes it. */
    private String place(Location at) {
        return messages.getMessage("trail-place")
                .replace("{x}", String.valueOf(at.getBlockX()))
                .replace("{y}", String.valueOf(at.getBlockY()))
                .replace("{z}", String.valueOf(at.getBlockZ()))
                .replace("{world}", at.getWorld() == null ? "?" : at.getWorld().getName());
    }

    // ------------------------------------------------------------------------
    // Showing it
    // ------------------------------------------------------------------------

    /**
     * Draws the flight for the viewer, again and again for
     * {@code show-seconds}. Each time it takes the newest flight of that
     * player: one he starts meanwhile takes the place of the old.
     */
    private void show(Player viewer, FlightTrail trail) {
        hide(viewer);
        UUID owner = trail.playerId();
        long total = settings.getTrailShowSeconds() * 20L;
        BukkitRunnable task = new BukkitRunnable() {
            private long ticks;

            @Override
            public void run() {
                if (!viewer.isOnline() || ticks >= total) {
                    cancel();
                    viewers.remove(viewer.getUniqueId(), this);
                    return;
                }
                FlightTrail newest = trails.get(owner);
                if (newest != null) {
                    draw(viewer, newest);
                }
                ticks += INTERVAL;
            }
        };
        viewers.put(viewer.getUniqueId(), task);
        task.runTaskTimer(plugin, 0L, INTERVAL);
        messages.sendConfiguredMessage(viewer, "trail-shown",
                "{time}", CamTimeLimit.formatDuration(settings.getTrailShowSeconds()));
        Set<String> worlds = new LinkedHashSet<>();
        for (FlightTrail.Piece piece : trail.pieces()) {
            if (piece.isIn(viewer.getWorld())) {
                return;
            }
            worlds.add(piece.worldName());
        }
        messages.sendConfiguredMessage(viewer, "trail-elsewhere", "{world}", String.join(", ", worlds));
    }

    /**
     * Stops showing a flight to the viewer.
     *
     * @return whether one was shown
     */
    private boolean hide(Player viewer) {
        BukkitRunnable task = viewers.remove(viewer.getUniqueId());
        if (task == null) {
            return false;
        }
        task.cancel();
        return true;
    }

    /**
     * Draws the flight once, the parts within {@link #REACH} of the viewer in
     * his world. First the length of those parts is measured, so that a long
     * line is drawn with its specks further apart instead of only partly.
     */
    private void draw(Player viewer, FlightTrail trail) {
        World world = viewer.getWorld();
        Location eye = viewer.getLocation();
        List<FlightTrail.Piece> pieces = trail.pieces();
        double whole = 0.0;
        double near = 0.0;
        for (FlightTrail.Piece piece : pieces) {
            boolean here = piece.isIn(world);
            for (int i = 0; i + 1 < piece.size(); i++) {
                double length = piece.length(i);
                whole += length;
                if (here && withinReach(piece, i, length, eye)) {
                    near += length;
                }
            }
        }
        double spacing = Math.max(SPACING, near / MAX_PARTICLES);
        double along = 0.0;
        for (FlightTrail.Piece piece : pieces) {
            boolean here = piece.isIn(world);
            for (int i = 0; i + 1 < piece.size(); i++) {
                double length = piece.length(i);
                if (here && withinReach(piece, i, length, eye)) {
                    Particle.DustOptions dust = new Particle.DustOptions(
                            color(whole <= 0.0 ? 0.0 : along / whole), SIZE);
                    int specks = Math.max(1, (int) Math.ceil(length / spacing));
                    for (int k = 0; k < specks; k++) {
                        double t = (double) k / specks;
                        speck(viewer, lerp(piece.x(i), piece.x(i + 1), t),
                                lerp(piece.y(i), piece.y(i + 1), t), lerp(piece.z(i), piece.z(i + 1), t), dust);
                    }
                }
                along += length;
            }
        }
        // The ends a size bigger: where the body stood and where the camera is.
        FlightTrail.Piece first = pieces.get(0);
        FlightTrail.Piece last = pieces.get(pieces.size() - 1);
        endSpeck(viewer, eye, first, 0, color(0.0));
        endSpeck(viewer, eye, last, last.size() - 1, color(1.0));
    }

    /**
     * Whether a part of the line lies within reach of the viewer: its middle,
     * give or take half its length.
     */
    private static boolean withinReach(FlightTrail.Piece piece, int i, double length, Location eye) {
        double dx = (piece.x(i) + piece.x(i + 1)) / 2.0 - eye.getX();
        double dy = (piece.y(i) + piece.y(i + 1)) / 2.0 - eye.getY();
        double dz = (piece.z(i) + piece.z(i + 1)) / 2.0 - eye.getZ();
        double reach = REACH + length / 2.0;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private static void endSpeck(Player viewer, Location eye, FlightTrail.Piece piece, int i, Color color) {
        if (!piece.isIn(viewer.getWorld())) {
            return;
        }
        double dx = piece.x(i) - eye.getX();
        double dy = piece.y(i) - eye.getY();
        double dz = piece.z(i) - eye.getZ();
        if (dx * dx + dy * dy + dz * dz <= REACH * REACH) {
            speck(viewer, piece.x(i), piece.y(i), piece.z(i), new Particle.DustOptions(color, END_SIZE));
        }
    }

    /** One speck of dust, for the viewer alone and shown however far away he is. */
    private static void speck(Player viewer, double x, double y, double z, Particle.DustOptions dust) {
        viewer.spawnParticle(Particle.DUST, x, y + HEIGHT, z, 1, 0.0, 0.0, 0.0, 0.0, dust, true);
    }

    /** The colour at this share of the way: green at 0, yellow halfway, red at 1. */
    private static Color color(double share) {
        double t = Math.min(1.0, Math.max(0.0, share));
        int[] from = t < 0.5 ? GREEN : YELLOW;
        int[] to = t < 0.5 ? YELLOW : RED;
        double s = t < 0.5 ? t * 2.0 : (t - 0.5) * 2.0;
        return Color.fromRGB((int) Math.round(lerp(from[0], to[0], s)),
                (int) Math.round(lerp(from[1], to[1], s)), (int) Math.round(lerp(from[2], to[2], s)));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    // ------------------------------------------------------------------------
    // Reload and shutdown
    // ------------------------------------------------------------------------

    /**
     * After a reload: switched off, the flights are forgotten and nobody is
     * shown one any more. Switched on, they stay - a reload is no reason to
     * lose what an admin may still want to look at.
     */
    public void onReload() {
        if (!settings.isTrailEnabled()) {
            onDisable();
        }
    }

    /** Stops every line and forgets every flight. */
    public void onDisable() {
        for (BukkitRunnable task : viewers.values()) {
            task.cancel();
        }
        viewers.clear();
        trails.clear();
    }
}
