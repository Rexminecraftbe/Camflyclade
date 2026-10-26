package de.elia.cameraplugin.trail;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@code /cam trail <player> [number|all]}: shows where the camera of a player
 * flew, as a line of particles that only the one who asked sees,
 * {@code cam-trail}. So an admin sees at a glance who was scouting where - the
 * log of {@code cam-log} only says where a flight began and ended.
 *
 * <p>The line runs from green at the body over yellow to red where the camera
 * was last, which tells the way it went. Without a number it is the newest
 * flight: while the player is still in camera mode his flight so far, which
 * grows as he flies on, and one he starts while it is shown takes its place.
 * A number picks one of the last {@code cam-trail.flights}, 1 being the
 * newest, and {@code all} shows all of them at once. {@code /cam trail} on its
 * own hides the line again.</p>
 *
 * <p>The flights are kept in memory only, the last few of each player, until
 * the server stops - and only while {@code cam-trail} is on.</p>
 */
public final class CamTrail implements Listener {

    /** The right the command asks for. Operators have it by default. */
    public static final String PERMISSION = "camplugin.trail";

    /** The word that stands for all kept flights of a player. */
    private static final String ALL = "all";

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
     * The most specks drawn at once, for all flights shown together. A longer
     * line within reach is drawn with them further apart.
     */
    private static final int MAX_PARTICLES = 2000;

    /** How far above the feet the line runs, in blocks: about the middle of the player. */
    private static final double HEIGHT = 1.0;

    /** The size of a speck, and of the two at the ends of a line. */
    private static final float SIZE = 1.0f;
    private static final float END_SIZE = 2.5f;

    /** The colours from the body to the camera: green, yellow and red. */
    private static final int[] GREEN = {40, 220, 60};
    private static final int[] YELLOW = {235, 220, 40};
    private static final int[] RED = {230, 40, 40};

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** The last flights of each player, the newest first - the one under way among them. */
    private final Map<UUID, Deque<FlightTrail>> flights = new HashMap<>();
    /** Who is being shown flights: the viewer, and the task drawing them for him. */
    private final Map<UUID, BukkitRunnable> viewers = new HashMap<>();

    public CamTrail(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    // ------------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------------

    /**
     * Starts to record the flight of a player who has just entered camera
     * mode. The oldest of his flights goes once there are more than
     * {@code cam-trail.flights}.
     */
    public void startFor(Player player, Location body) {
        if (!settings.isTrailEnabled()) {
            return;
        }
        Deque<FlightTrail> kept = flights.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>());
        kept.addFirst(new FlightTrail(player.getUniqueId(), player.getName(), body));
        while (kept.size() > settings.getTrailFlights()) {
            kept.removeLast();
        }
    }

    /**
     * Ends the flight of a player leaving camera mode.
     *
     * @param cameraAt where the camera was, before he is put back at his body
     */
    public void stopFor(Player player, Location cameraAt) {
        FlightTrail trail = flying(player.getUniqueId());
        if (trail != null) {
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
        FlightTrail trail = flying(event.getPlayer().getUniqueId());
        if (trail != null) {
            trail.moved(event.getFrom(), to);
        }
    }

    /** The flight the camera of this player is on right now, or {@code null}. */
    private FlightTrail flying(UUID playerId) {
        Deque<FlightTrail> kept = flights.get(playerId);
        FlightTrail newest = kept == null ? null : kept.peekFirst();
        return newest != null && newest.isFlying() ? newest : null;
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
        if (args.length < 1 || args.length > 2) {
            messages.sendConfiguredMessage(sender, "trail-usage");
            return;
        }
        Deque<FlightTrail> kept = find(args[0]);
        if (kept == null) {
            messages.sendConfiguredMessage(sender, "trail-none", "{player}", args[0]);
            return;
        }
        UUID owner = kept.getFirst().playerId();
        String name = kept.getFirst().playerName();
        if (args.length == 2 && args[1].equalsIgnoreCase(ALL)) {
            List<FlightTrail> all = new ArrayList<>(kept);
            describeAll(sender, name, all);
            if (sender instanceof Player viewer) {
                show(viewer, () -> keptOf(owner), all);
            }
            return;
        }
        int number = 1;
        if (args.length == 2) {
            try {
                number = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                messages.sendConfiguredMessage(sender, "trail-usage");
                return;
            }
        }
        if (number < 1 || number > kept.size()) {
            messages.sendConfiguredMessage(sender, "trail-no-such", "{player}", name,
                    "{number}", String.valueOf(number), "{count}", String.valueOf(kept.size()));
            return;
        }
        FlightTrail trail = new ArrayList<>(kept).get(number - 1);
        describe(sender, trail, number);
        sendChoices(sender, name, kept.size());
        if (sender instanceof Player viewer) {
            // Without a number the newest, also one he starts meanwhile; with
            // one the flight it named, even once a newer one moves it back.
            Supplier<List<FlightTrail>> shown = args.length == 1
                    ? () -> newestOf(owner) : () -> List.of(trail);
            show(viewer, shown, List.of(trail));
        }
    }

    /** The names of the players flights are kept of, for the tab completion. */
    public List<String> recordedNames() {
        List<String> names = new ArrayList<>();
        for (Deque<FlightTrail> kept : flights.values()) {
            if (!kept.isEmpty()) {
                names.add(kept.getFirst().playerName());
            }
        }
        return names;
    }

    /**
     * What may follow the name, for the tab completion: the number of each
     * flight kept of that player, and {@code all} once there is more than one.
     */
    public List<String> choicesFor(String name) {
        Deque<FlightTrail> kept = find(name);
        List<String> choices = new ArrayList<>();
        if (kept == null) {
            return choices;
        }
        for (int number = 1; number <= kept.size(); number++) {
            choices.add(String.valueOf(number));
        }
        if (kept.size() > 1) {
            choices.add(ALL);
        }
        return choices;
    }

    /** The flights of a player by his name, as it was when he flew the newest. */
    private Deque<FlightTrail> find(String name) {
        for (Deque<FlightTrail> kept : flights.values()) {
            if (!kept.isEmpty() && kept.getFirst().playerName().equalsIgnoreCase(name)) {
                return kept;
            }
        }
        return null;
    }

    private List<FlightTrail> keptOf(UUID owner) {
        Deque<FlightTrail> kept = flights.get(owner);
        return kept == null ? List.of() : new ArrayList<>(kept);
    }

    private List<FlightTrail> newestOf(UUID owner) {
        Deque<FlightTrail> kept = flights.get(owner);
        return kept == null || kept.isEmpty() ? List.of() : List.of(kept.getFirst());
    }

    /**
     * Says how long and how far one flight went, whether it still goes on,
     * and where it began and ended - also to the console, which sees no
     * particles.
     */
    private void describe(CommandSender sender, FlightTrail trail, int number) {
        messages.sendConfiguredMessage(sender, "trail-header",
                "{player}", trail.playerName(),
                "{number}", String.valueOf(number),
                "{distance}", String.valueOf(Math.round(trail.distanceFlown())),
                "{time}", CamTimeLimit.formatDuration(TimeUnit.MILLISECONDS.toSeconds(trail.duration())),
                "{when}", when(trail));
        messages.sendConfiguredMessage(sender, "trail-from", "{place}", place(trail.start()));
        messages.sendConfiguredMessage(sender, "trail-to", "{place}", place(trail.last()));
    }

    /** One line for each kept flight, the newest first. */
    private void describeAll(CommandSender sender, String name, List<FlightTrail> all) {
        messages.sendConfiguredMessage(sender, "trail-all-header",
                "{player}", name, "{count}", String.valueOf(all.size()));
        for (int i = 0; i < all.size(); i++) {
            FlightTrail trail = all.get(i);
            messages.sendConfiguredMessage(sender, "trail-all-entry",
                    "{number}", String.valueOf(i + 1),
                    "{distance}", String.valueOf(Math.round(trail.distanceFlown())),
                    "{time}", CamTimeLimit.formatDuration(TimeUnit.MILLISECONDS.toSeconds(trail.duration())),
                    "{when}", when(trail),
                    "{place}", place(trail.last()));
        }
    }

    /**
     * The line {@code trail-flights} with a button for each kept flight and
     * one for all of them, once there is more than one to choose from. A
     * click runs {@code /cam trail <player> <number>}; the console reads the
     * buttons as they are written.
     */
    private void sendChoices(CommandSender sender, String name, int count) {
        if (count < 2 || !messages.isMessageEnabled("trail-flights")) {
            return;
        }
        // A click runs /cam trail, which menu.admin-access may keep to the
        // menu - the flights are picked there then.
        if (sender instanceof Player && !plugin.getSettings().getAdminAccess().allowsCommands()) {
            return;
        }
        List<BaseComponent> line = new ArrayList<>(Arrays.asList(
                TextComponent.fromLegacyText(messages.getMessage("trail-flights"))));
        for (int number = 1; number <= count; number++) {
            line.addAll(button("trail-button", name, String.valueOf(number)));
        }
        line.addAll(button("trail-button-all", name, ALL));
        sender.spigot().sendMessage(line.toArray(new BaseComponent[0]));
    }

    /**
     * One button: its text, and on hovering the text of the same name with
     * {@code -hover} after it. Nothing when the text is switched off or empty.
     */
    private List<BaseComponent> button(String key, String name, String choice) {
        if (!messages.isMessageEnabled(key)) {
            return List.of();
        }
        String text = messages.getMessage(key).replace("{number}", choice);
        if (text.isEmpty()) {
            return List.of();
        }
        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(
                messages.getMessage(key + "-hover").replace("{number}", choice).replace("{player}", name))));
        ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/cam trail " + name + " " + choice.toLowerCase(Locale.ROOT));
        List<BaseComponent> parts = new ArrayList<>();
        parts.add(new TextComponent(" "));
        for (BaseComponent part : TextComponent.fromLegacyText(text)) {
            part.setHoverEvent(hover);
            part.setClickEvent(click);
            parts.add(part);
        }
        return parts;
    }

    /** {@code trail-flying}, or {@code trail-ago} with how long ago it ended. */
    private String when(FlightTrail trail) {
        if (trail.isFlying()) {
            return messages.getMessage("trail-flying");
        }
        long ago = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - trail.endedAt());
        return messages.getMessage("trail-ago").replace("{time}", CamTimeLimit.formatDuration(ago));
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
     * Draws flights for the viewer, again and again for
     * {@code show-seconds}.
     *
     * @param shown which flights to draw, asked anew each time
     * @param now   the flights shown right now, to tell the viewer when none
     *              of them is in his world
     */
    private void show(Player viewer, Supplier<List<FlightTrail>> shown, List<FlightTrail> now) {
        hide(viewer);
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
                draw(viewer, shown.get());
                ticks += INTERVAL;
            }
        };
        viewers.put(viewer.getUniqueId(), task);
        task.runTaskTimer(plugin, 0L, INTERVAL);
        messages.sendConfiguredMessage(viewer, "trail-shown",
                "{time}", CamTimeLimit.formatDuration(settings.getTrailShowSeconds()));
        Set<String> worlds = new LinkedHashSet<>();
        for (FlightTrail trail : now) {
            for (FlightTrail.Piece piece : trail.pieces()) {
                if (piece.isIn(viewer.getWorld())) {
                    return;
                }
                worlds.add(piece.worldName());
            }
        }
        messages.sendConfiguredMessage(viewer, "trail-elsewhere", "{world}", String.join(", ", worlds));
    }

    /**
     * Stops showing flights to the viewer.
     *
     * @return whether any were shown
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
     * Draws the flights once, the parts within {@link #REACH} of the viewer in
     * his world. First the length of those parts is measured, so that long
     * lines are drawn with their specks further apart instead of only partly.
     */
    private void draw(Player viewer, List<FlightTrail> trails) {
        World world = viewer.getWorld();
        Location eye = viewer.getLocation();
        double near = 0.0;
        for (FlightTrail trail : trails) {
            for (FlightTrail.Piece piece : trail.pieces()) {
                if (!piece.isIn(world)) {
                    continue;
                }
                for (int i = 0; i + 1 < piece.size(); i++) {
                    double length = piece.length(i);
                    if (withinReach(piece, i, length, eye)) {
                        near += length;
                    }
                }
            }
        }
        double spacing = Math.max(SPACING, near / MAX_PARTICLES);
        for (FlightTrail trail : trails) {
            draw(viewer, eye, trail, spacing);
        }
    }

    /** Draws one flight, its colour going along the whole of its way. */
    private static void draw(Player viewer, Location eye, FlightTrail trail, double spacing) {
        World world = viewer.getWorld();
        List<FlightTrail.Piece> pieces = trail.pieces();
        double whole = 0.0;
        for (FlightTrail.Piece piece : pieces) {
            for (int i = 0; i + 1 < piece.size(); i++) {
                whole += piece.length(i);
            }
        }
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
     * lose what an admin may still want to look at -, only the oldest go when
     * {@code cam-trail.flights} has become smaller.
     */
    public void onReload() {
        if (!settings.isTrailEnabled()) {
            onDisable();
            return;
        }
        for (Deque<FlightTrail> kept : flights.values()) {
            while (kept.size() > settings.getTrailFlights()) {
                kept.removeLast();
            }
        }
    }

    /** Stops every line and forgets every flight. */
    public void onDisable() {
        for (BukkitRunnable task : viewers.values()) {
            task.cancel();
        }
        viewers.clear();
        flights.clear();
    }
}
