package de.elia.cameraplugin.trail;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.camfly2.PluginPart;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.storage.BackgroundWriter;
import de.elia.cameraplugin.storage.StorageMode;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
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
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 * own hides the line again, as does the bucket on the page of the flights in
 * the menu - a water bucket while a line is shown, an empty one otherwise.</p>
 *
 * <p>How long the line stays, {@code cam-trail.show-mode} says, see
 * {@link TrailShowMode}: by how far the camera flew, between
 * {@code show-min-seconds} and {@code show-max-seconds} - a long line needs
 * the time, it is drawn only within {@link #REACH} of the viewer, who has to
 * fly along it to see it all -, always {@code show-seconds}, or until it is
 * hidden.</p>
 *
 * <p>Recorded is only while {@code cam-trail} is on. The last
 * {@code cam-trail.flights} of each player are kept - all of them with 0 -,
 * none longer than {@code cam-trail.keep-days}: with {@code storage: memory}
 * in memory only, until the server stops; with {@code storage: file} each in
 * a file of its own as well, see {@link TrailStore}, read again at the next
 * start. An admin deletes single flights or all of a player in the menu,
 * with {@value #DELETE_PERMISSION}.</p>
 */
public final class CamTrail implements Listener, PluginPart {

    /** The right the command asks for. Operators have it by default. */
    public static final String PERMISSION = "camplugin.trail";

    /** The right to delete flights in the menu, on top of {@link #PERMISSION}. Operators have it by default. */
    public static final String DELETE_PERMISSION = "camplugin.trail.delete";

    /**
     * The most flights {@code all}, the buttons in the chat and the tab
     * completion offer at once, the newest - with all of them kept, a player
     * may have hundreds. A single one goes by its number all the same.
     */
    private static final int MAX_AT_ONCE = TrailSettings.MAX_TRAIL_FLIGHTS;

    /** Ticks between two looks for flights older than {@code keep-days}: ten minutes. */
    private static final long EXPIRY_TICKS = 20L * 60L * 10L;

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
    private final TrailStore store;
    /** The last flights of each player, the newest first - the one under way among them. */
    private final Map<UUID, Deque<FlightTrail>> flights = new HashMap<>();
    /** Who is being shown flights: the viewer, and what draws them for him. */
    private final Map<UUID, Drawing> viewers = new HashMap<>();
    /** Whether the flights of the files are in memory, read since {@code storage: file} is on. */
    private boolean fromFiles;
    /** Counts the reads of the files, so that a read overtaken by a reload or a switch is dropped. */
    private int reading;
    /** Whether the files are being read right now. */
    private boolean readPending;
    /** The flights deleted while the files are being read, which the read must not bring back. */
    private final Set<String> deletedWhileReading = new HashSet<>();
    /** Looks for flights older than {@code keep-days} every {@link #EXPIRY_TICKS}. */
    private BukkitTask expiry;

    public CamTrail(CameraPlugin plugin, ConsoleLog log, BackgroundWriter writer) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.store = new TrailStore(plugin, log, writer);
    }

    private boolean inFiles() {
        return settings.trail().storage() == StorageMode.FILE;
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
        if (!settings.trail().enabled()) {
            return;
        }
        Deque<FlightTrail> kept = flights.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>());
        kept.addFirst(new FlightTrail(player.getUniqueId(), player.getName(), body));
        trim(kept);
    }

    /**
     * Ends the flight of a player leaving camera mode, and with
     * {@code storage: file} writes it into its file.
     *
     * @param cameraAt where the camera was, before he is put back at his body
     */
    public void stopFor(Player player, Location cameraAt) {
        FlightTrail trail = flying(player.getUniqueId());
        if (trail != null) {
            trail.end(cameraAt);
            if (inFiles()) {
                save(trail);
            }
        }
    }

    /** Writes a flight that has ended into its file, which from then on holds its line. */
    private void save(FlightTrail trail) {
        store.save(trail);
        trail.keepInFile(() -> store.pieces(trail));
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
        return settings.trail().enabled()
                && (!(sender instanceof Player player) || player.hasPermission(PERMISSION));
    }

    /** {@code /cam trail}, with what follows it. */
    public void command(CommandSender sender, String[] args) {
        if (!settings.trail().enabled()) {
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
            List<FlightTrail> all = newest(kept);
            describeAll(sender, name, all);
            if (sender instanceof Player viewer) {
                show(viewer, () -> keptOf(owner), all, true);
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
            show(viewer, shown, List.of(trail), false);
        }
    }

    /**
     * The button of the admin page of the flights that hides the line: like
     * {@code /cam trail} on its own, but with no line shown it says so rather
     * than how the command goes - in the menu the command may well be off.
     */
    public void hideFromMenu(Player viewer) {
        if (!settings.trail().enabled()) {
            messages.sendConfiguredMessage(viewer, "trail-off");
            return;
        }
        if (!mayUse(viewer)) {
            messages.sendConfiguredMessage(viewer, "no-permission");
            return;
        }
        messages.sendConfiguredMessage(viewer, hide(viewer) ? "trail-hidden" : "trail-nothing-shown");
    }

    /**
     * What a viewer is being shown, as the bucket of the menu tells it.
     *
     * @param player      whose flights they are
     * @param number      which one, 1 for the newest as {@code /cam trail}
     *                    counts; 0 for all of them, and for one no longer kept
     * @param all         whether all his kept flights are shown
     * @param secondsLeft how long the line stays yet; -1 while it stays
     *                    until it is hidden, {@code show-mode: forever}
     */
    public record Shown(String player, int number, boolean all, long secondsLeft) {

        /** Whether the line stays until it is hidden, rather than going by itself. */
        public boolean untilHidden() {
            return secondsLeft < 0L;
        }
    }

    /**
     * What this viewer is being shown right now, or {@code null} while he is
     * shown no line - also in the moment after it was drawn for the last
     * time, before its task notices that its time is up.
     */
    public Shown shownTo(Player viewer) {
        Drawing drawing = viewers.get(viewer.getUniqueId());
        if (drawing == null || drawing.secondsLeft() == 0L) {
            return null;
        }
        int number = 0;
        List<FlightTrail> now = drawing.shown.get();
        if (!drawing.all && now.size() == 1) {
            number = numberOf(now.get(0));
        }
        return new Shown(drawing.player, number, drawing.all, drawing.secondsLeft());
    }

    /** Which of the kept flights of its player this is, 1 for the newest; 0 once it is no longer kept. */
    private int numberOf(FlightTrail trail) {
        Deque<FlightTrail> kept = flights.get(trail.playerId());
        if (kept == null) {
            return 0;
        }
        int number = 1;
        for (FlightTrail each : kept) {
            if (each == trail) {
                return number;
            }
            number++;
        }
        return 0;
    }

    /**
     * One kept flight, as the menu shows it.
     *
     * @param number    which one: 1 for the newest, as {@code /cam trail} counts
     * @param startedAt when it began, which tells it from the others
     * @param endedAt   when it ended, 0 while the camera still flies
     * @param duration  how long it took, or has taken so far, in milliseconds
     * @param distance  how far the camera flew, in blocks
     * @param from      where the body stood, as {@link #place} writes it
     * @param to        where the camera was last, as {@link #place} writes it
     */
    public record Flight(int number, long startedAt, long endedAt, long duration, double distance,
                         String from, String to) {

        /** Whether the camera is still flying it. */
        public boolean flying() {
            return endedAt == 0L;
        }
    }

    /** The flights kept of a player, the newest first, for the menu; none while there are none. */
    public List<Flight> flightsOf(String name) {
        Deque<FlightTrail> kept = find(name);
        List<Flight> found = new ArrayList<>();
        if (kept == null) {
            return found;
        }
        int number = 1;
        for (FlightTrail trail : kept) {
            found.add(new Flight(number++, trail.startedAt(), trail.endedAt(), trail.duration(),
                    trail.distanceFlown(), place(trail.start(), trail.startWorld()),
                    place(trail.last(), trail.lastWorld())));
        }
        return found;
    }

    /**
     * Shows one kept flight to a viewer, from the menu: as
     * {@code /cam trail <player> <number>} does, only found by when it
     * began, which stays the same while newer flights move it back.
     *
     * @param toStart whether he is taken to where the body stood first, to
     *                see the line from its beginning - out of camera mode,
     *                if he is in it
     */
    public void showFlight(Player viewer, String name, long startedAt, boolean toStart) {
        if (!settings.trail().enabled()) {
            messages.sendConfiguredMessage(viewer, "trail-off");
            return;
        }
        if (!mayUse(viewer)) {
            messages.sendConfiguredMessage(viewer, "no-permission");
            return;
        }
        Deque<FlightTrail> kept = find(name);
        FlightTrail trail = null;
        int number = 0;
        if (kept != null) {
            int count = 0;
            for (FlightTrail each : kept) {
                count++;
                if (each.startedAt() == startedAt) {
                    trail = each;
                    number = count;
                }
            }
        }
        if (trail == null) {
            messages.sendConfiguredMessage(viewer, "trail-gone", "{player}", name);
            return;
        }
        if (toStart && !trail.start().isWorldLoaded()) {
            messages.sendConfiguredMessage(viewer, "trail-world-gone", "{world}", trail.startWorld());
        } else if (toStart) {
            if (plugin.isInCameraMode(viewer)) {
                plugin.exitCameraMode(viewer, EndReason.COMMAND);
                messages.sendConfiguredMessage(viewer, "camera-off");
            }
            if (plugin.getTeleportGuard().teleport(viewer, trail.start())) {
                messages.sendConfiguredMessage(viewer, "trail-at-start", "{player}", trail.playerName(),
                        "{number}", String.valueOf(number));
            }
        }
        describe(viewer, trail, number);
        FlightTrail shown = trail;
        show(viewer, () -> List.of(shown), List.of(shown), false);
    }

    // ------------------------------------------------------------------------
    // Deleting, from the menu
    // ------------------------------------------------------------------------

    /**
     * Whether this sender may delete flights: who may look at them and has
     * {@link #DELETE_PERMISSION} as well, and the console.
     */
    public boolean mayDelete(CommandSender sender) {
        return mayUse(sender) && (!(sender instanceof Player player) || player.hasPermission(DELETE_PERMISSION));
    }

    /**
     * Deletes one kept flight, found by when it began, from memory and from
     * its file. A flight the camera is still flying stays.
     */
    public void deleteFlight(Player viewer, String name, long startedAt) {
        if (!mayDeleteNow(viewer)) {
            return;
        }
        Deque<FlightTrail> kept = find(name);
        FlightTrail trail = null;
        int number = 0;
        if (kept != null) {
            int count = 0;
            for (FlightTrail each : kept) {
                count++;
                if (each.startedAt() == startedAt) {
                    trail = each;
                    number = count;
                }
            }
        }
        if (trail == null) {
            messages.sendConfiguredMessage(viewer, "trail-gone", "{player}", name);
            return;
        }
        if (trail.isFlying()) {
            messages.sendConfiguredMessage(viewer, "trail-delete-flying", "{player}", trail.playerName(),
                    "{number}", String.valueOf(number));
            return;
        }
        kept.remove(trail);
        if (kept.isEmpty()) {
            flights.remove(trail.playerId());
        }
        forgotten(trail);
        messages.sendConfiguredMessage(viewer, "trail-deleted", "{player}", trail.playerName(),
                "{number}", String.valueOf(number));
    }

    /**
     * Deletes every kept flight of a player, from memory and from their
     * files - but one the camera is still flying.
     */
    public void deleteAll(Player viewer, String name) {
        if (!mayDeleteNow(viewer)) {
            return;
        }
        Deque<FlightTrail> kept = find(name);
        if (kept == null) {
            messages.sendConfiguredMessage(viewer, "trail-none", "{player}", name);
            return;
        }
        String player = kept.getFirst().playerName();
        UUID owner = kept.getFirst().playerId();
        int deleted = 0;
        boolean flying = false;
        for (Iterator<FlightTrail> each = kept.iterator(); each.hasNext(); ) {
            FlightTrail trail = each.next();
            if (trail.isFlying()) {
                flying = true;
                continue;
            }
            each.remove();
            forgotten(trail);
            deleted++;
        }
        if (kept.isEmpty()) {
            flights.remove(owner);
        }
        if (deleted == 0) {
            messages.sendConfiguredMessage(viewer, "trail-delete-flying", "{player}", player, "{number}", "1");
            return;
        }
        messages.sendConfiguredMessage(viewer, flying ? "trail-deleted-all-flying" : "trail-deleted-all",
                "{player}", player, "{count}", String.valueOf(deleted));
    }

    /** Whether he may delete flights right now; if not, he has been told why. */
    private boolean mayDeleteNow(Player viewer) {
        if (!settings.trail().enabled()) {
            messages.sendConfiguredMessage(viewer, "trail-off");
            return false;
        }
        if (!mayDelete(viewer)) {
            messages.sendConfiguredMessage(viewer, "no-permission");
            return false;
        }
        return true;
    }

    /**
     * A flight has gone from the kept ones - deleted, too many or too old:
     * its file goes with it, and whoever is shown it no longer is.
     */
    private void forgotten(FlightTrail trail) {
        if (trail.saved()) {
            store.delete(trail.playerId(), trail.startedAt());
        }
        if (readPending) {
            deletedWhileReading.add(key(trail.playerId(), trail.startedAt()));
        }
        for (Iterator<Drawing> each = viewers.values().iterator(); each.hasNext(); ) {
            Drawing drawing = each.next();
            List<FlightTrail> now = drawing.shown.get();
            if (now.isEmpty() || now.contains(trail)) {
                drawing.cancel();
                each.remove();
            }
        }
    }

    private static String key(UUID player, long startedAt) {
        return player + "/" + startedAt;
    }

    /**
     * A player flights are kept of, as the page of the flights in the menu
     * lists him.
     *
     * @param name   his name, as it was when he flew the newest
     * @param count  how many are kept
     * @param newest when the newest began
     * @param flying whether the camera is still flying the newest
     */
    public record Recorded(String name, int count, long newest, boolean flying) {
    }

    /** The players flights are kept of, for the menu. */
    public List<Recorded> recorded() {
        List<Recorded> found = new ArrayList<>();
        for (Deque<FlightTrail> kept : flights.values()) {
            FlightTrail newest = kept.peekFirst();
            if (newest != null) {
                found.add(new Recorded(newest.playerName(), kept.size(), newest.startedAt(), newest.isFlying()));
            }
        }
        return found;
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
        for (int number = 1; number <= Math.min(kept.size(), MAX_AT_ONCE); number++) {
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

    /** The newest of his kept flights, as many as {@code all} shows at once. */
    private List<FlightTrail> keptOf(UUID owner) {
        Deque<FlightTrail> kept = flights.get(owner);
        return kept == null ? List.of() : newest(kept);
    }

    /** The newest of these flights, as many as {@code all} shows at once. */
    private static List<FlightTrail> newest(Deque<FlightTrail> kept) {
        List<FlightTrail> newest = new ArrayList<>(Math.min(kept.size(), MAX_AT_ONCE));
        for (FlightTrail trail : kept) {
            if (newest.size() == MAX_AT_ONCE) {
                break;
            }
            newest.add(trail);
        }
        return newest;
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
        messages.sendConfiguredMessage(sender, "trail-from", "{place}", place(trail.start(), trail.startWorld()));
        messages.sendConfiguredMessage(sender, "trail-to", "{place}", place(trail.last(), trail.lastWorld()));
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
                    "{place}", place(trail.last(), trail.lastWorld()));
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
        if (sender instanceof Player && !plugin.getSettings().menu().adminAccess().allowsCommands()) {
            return;
        }
        List<BaseComponent> line = new ArrayList<>(Arrays.asList(
                TextComponent.fromLegacyText(messages.getMessage("trail-flights"))));
        for (int number = 1; number <= Math.min(count, MAX_AT_ONCE); number++) {
            line.addAll(button(sender, "trail-button", name, String.valueOf(number)));
        }
        line.addAll(button(sender, "trail-button-all", name, ALL));
        sender.spigot().sendMessage(line.toArray(new BaseComponent[0]));
    }

    /**
     * One button: its text, and on hovering the text of the same name with
     * {@code -hover} after it, and on Bedrock the command written out, see
     * {@link de.elia.cameraplugin.bedrock.BedrockPlayers#chatButton}. Nothing
     * when the text is switched off or empty.
     */
    private List<BaseComponent> button(CommandSender to, String key, String name, String choice) {
        if (!messages.isMessageEnabled(key)) {
            return List.of();
        }
        String text = messages.getMessage(key).replace("{number}", choice);
        if (text.isEmpty()) {
            return List.of();
        }
        String hover = messages.getMessage(key + "-hover").replace("{number}", choice).replace("{player}", name);
        String command = "/cam trail " + name + " " + choice.toLowerCase(Locale.ROOT);
        return plugin.getBedrock().chatButton(to, text, hover, new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
    }

    /** {@code trail-flying}, or {@code trail-ago} with how long ago it ended. */
    private String when(FlightTrail trail) {
        if (trail.isFlying()) {
            return messages.getMessage("trail-flying");
        }
        long ago = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - trail.endedAt());
        return messages.getMessage("trail-ago").replace("{time}", CamTimeLimit.formatDuration(ago));
    }

    /** A spot as {@code trail-place} writes it, with the name of its world - also one not loaded. */
    private String place(Location at, String world) {
        return messages.getMessage("trail-place")
                .replace("{x}", String.valueOf(at.getBlockX()))
                .replace("{y}", String.valueOf(at.getBlockY()))
                .replace("{z}", String.valueOf(at.getBlockZ()))
                .replace("{world}", world);
    }

    // ------------------------------------------------------------------------
    // Showing it
    // ------------------------------------------------------------------------

    /**
     * Draws flights for the viewer, again and again for as long as
     * {@link #showSeconds} says.
     *
     * @param shown which flights to draw, asked anew each time
     * @param now   the flights shown right now, to tell the viewer when none
     *              of them is in his world; never none
     * @param all   whether they are all the kept flights of their player
     */
    private void show(Player viewer, Supplier<List<FlightTrail>> shown, List<FlightTrail> now, boolean all) {
        hide(viewer);
        int seconds = showSeconds(now);
        Drawing task = new Drawing(viewer, shown, now.get(0).playerName(), all, seconds < 0 ? -1L : seconds * 20L);
        viewers.put(viewer.getUniqueId(), task);
        task.runTaskTimer(plugin, 0L, INTERVAL);
        if (seconds < 0) {
            messages.sendConfiguredMessage(viewer, "trail-shown-forever");
        } else {
            messages.sendConfiguredMessage(viewer, "trail-shown", "{time}", CamTimeLimit.formatDuration(seconds));
        }
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
     * How long the line of these flights stays, in seconds, as
     * {@code show-mode} has it; -1 for until it is hidden. With
     * {@code dynamic} the longest of them counts: up to
     * {@code show-min-blocks} it stays {@code show-min-seconds}, from
     * {@code show-max-blocks} on {@code show-max-seconds}, and in between in
     * proportion - the most while one of them is still under way, as its line
     * grows.
     */
    private int showSeconds(List<FlightTrail> now) {
        TrailShowMode mode = settings.trail().showMode();
        if (mode == TrailShowMode.FOREVER) {
            return -1;
        }
        if (mode == TrailShowMode.FIXED) {
            return settings.trail().showSeconds();
        }
        int minBlocks = settings.trail().showMinBlocks();
        int maxBlocks = settings.trail().showMaxBlocks();
        int minSeconds = settings.trail().showMinSeconds();
        int maxSeconds = settings.trail().showMaxSeconds();
        double longest = 0.0;
        for (FlightTrail trail : now) {
            if (trail.isFlying()) {
                return maxSeconds;
            }
            longest = Math.max(longest, trail.distanceFlown());
        }
        if (longest <= minBlocks) {
            return minSeconds;
        }
        if (longest >= maxBlocks) {
            return maxSeconds;
        }
        double share = (longest - minBlocks) / (maxBlocks - minBlocks);
        return (int) Math.round(minSeconds + share * (maxSeconds - minSeconds));
    }

    /** What draws the flights for one viewer, and what the menu tells of it. */
    private final class Drawing extends BukkitRunnable {

        private final Player viewer;
        /** Which flights to draw, asked anew each time. */
        private final Supplier<List<FlightTrail>> shown;
        /** Whose flights they are. */
        private final String player;
        /** Whether they are all his kept flights. */
        private final boolean all;
        /** How long the line stays, in ticks; -1 until it is hidden. */
        private final long total;
        private long ticks;

        Drawing(Player viewer, Supplier<List<FlightTrail>> shown, String player, boolean all, long total) {
            this.viewer = viewer;
            this.shown = shown;
            this.player = player;
            this.all = all;
            this.total = total;
        }

        @Override
        public void run() {
            if (!viewer.isOnline() || (total >= 0L && ticks >= total)) {
                cancel();
                viewers.remove(viewer.getUniqueId(), this);
                return;
            }
            draw(viewer, shown.get());
            ticks += INTERVAL;
        }

        /** How long the line stays yet, in whole seconds, rounded up; -1 until it is hidden. */
        long secondsLeft() {
            return total < 0L ? -1L : Math.max(0L, (total - ticks + 19L) / 20L);
        }
    }

    /**
     * Stops showing flights to the viewer.
     *
     * @return whether any were shown
     */
    private boolean hide(Player viewer) {
        Drawing task = viewers.remove(viewer.getUniqueId());
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
    // What is kept, and for how long
    // ------------------------------------------------------------------------

    /**
     * Lets the oldest of his flights go while there are more than
     * {@code cam-trail.flights}. The newest, which may still be flown, stays.
     */
    private void trim(Deque<FlightTrail> kept) {
        int most = settings.trail().flights();
        while (most > 0 && kept.size() > most) {
            forgotten(kept.removeLast());
        }
    }

    /**
     * Lets every flight go that ended longer ago than
     * {@code cam-trail.keep-days}, with its file.
     */
    private void expire() {
        int days = settings.trail().keepDays();
        if (!settings.trail().enabled() || days <= 0) {
            return;
        }
        long oldest = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        for (Iterator<Deque<FlightTrail>> players = flights.values().iterator(); players.hasNext(); ) {
            Deque<FlightTrail> kept = players.next();
            for (Iterator<FlightTrail> each = kept.iterator(); each.hasNext(); ) {
                FlightTrail trail = each.next();
                if (!trail.isFlying() && trail.endedAt() < oldest) {
                    each.remove();
                    forgotten(trail);
                }
            }
            if (kept.isEmpty()) {
                players.remove();
            }
        }
    }

    /**
     * The flights read from the files, on the main thread. Those that are in
     * memory already - flown since - and those deleted in the meantime are
     * left out; then the limits hold for all of them together.
     *
     * @param read which read it was; one overtaken by another, by a reload or
     *             by a switch to memory is dropped
     */
    private void readFromFiles(int read, List<TrailFile.Data> found) {
        if (read != reading || !fromFiles || !settings.trail().enabled()) {
            return;
        }
        readPending = false;
        // When the flights in memory began, by player - kept or held for
        // good, with all of them a player may have thousands.
        Map<UUID, Set<Long>> known = new HashMap<>();
        Set<Deque<FlightTrail>> grown = new HashSet<>();
        for (TrailFile.Data head : found) {
            if (deletedWhileReading.contains(key(head.player(), head.startedAt()))) {
                continue;
            }
            Deque<FlightTrail> kept = flights.computeIfAbsent(head.player(), id -> new ArrayDeque<>());
            Set<Long> starts = known.computeIfAbsent(head.player(), id -> {
                Set<Long> began = new HashSet<>();
                for (FlightTrail trail : kept) {
                    began.add(trail.startedAt());
                }
                return began;
            });
            if (!starts.add(head.startedAt())) {
                continue;
            }
            FlightTrail trail = FlightTrail.stored(head);
            trail.keepInFile(() -> store.pieces(trail));
            kept.addLast(trail);
            grown.add(kept);
        }
        deletedWhileReading.clear();
        for (Deque<FlightTrail> kept : grown) {
            List<FlightTrail> sorted = new ArrayList<>(kept);
            sorted.sort(Comparator.comparingLong(FlightTrail::startedAt).reversed());
            kept.clear();
            kept.addAll(sorted);
            trim(kept);
        }
        expire();
    }

    // ------------------------------------------------------------------------
    // Start, reload and shutdown
    // ------------------------------------------------------------------------

    /**
     * At the start and after a reload. With {@code storage: file}, the first
     * time since it is on: what was flown before, while the flights were kept
     * in memory only, is written into files now as well, and the flights of
     * the files are read - in the background, they come a moment later. Then
     * {@code flights} and {@code keep-days} hold, as they may have changed.
     */
    @Override
    public void load() {
        if (!settings.trail().enabled()) {
            return;
        }
        if (!inFiles()) {
            fromFiles = false;
            readPending = false;
        } else if (!fromFiles) {
            fromFiles = true;
            for (Deque<FlightTrail> kept : flights.values()) {
                for (FlightTrail trail : kept) {
                    if (!trail.isFlying() && !trail.saved()) {
                        save(trail);
                    }
                }
            }
            int read = ++reading;
            readPending = true;
            deletedWhileReading.clear();
            store.readAll(found -> readFromFiles(read, found));
        }
        for (Deque<FlightTrail> kept : new ArrayList<>(flights.values())) {
            trim(kept);
        }
        expire();
    }

    /** Looks for flights older than {@code keep-days} from now on, every ten minutes. */
    @Override
    public void start() {
        expiry = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, EXPIRY_TICKS, EXPIRY_TICKS);
    }

    /**
     * After a reload: switched off, the flights are forgotten and nobody is
     * shown one any more - their files stay, and are read again once it is
     * switched on. Switched on, they stay - a reload is no reason to lose
     * what an admin may still want to look at -, {@link #load} sees to the
     * limits.
     */
    @Override
    public void onReload() {
        if (!settings.trail().enabled()) {
            forget();
        }
    }

    /** Stops every line and forgets every flight; the files stay. */
    @Override
    public void onDisable() {
        if (expiry != null) {
            expiry.cancel();
            expiry = null;
        }
        forget();
    }

    private void forget() {
        for (Drawing task : viewers.values()) {
            task.cancel();
        }
        viewers.clear();
        flights.clear();
        fromFiles = false;
        readPending = false;
        reading++;
        deletedWhileReading.clear();
    }
}
