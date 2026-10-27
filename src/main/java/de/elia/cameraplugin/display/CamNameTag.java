package de.elia.cameraplugin.display;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The name over the camera player, {@code camera-mode.name-visible},
 * {@code camera-mode.name-mode} and {@code camera-mode.name}.
 *
 * <p>It takes the place of his own name tag, which the team
 * {@code cam_no_push} switches off, see
 * {@link de.elia.cameraplugin.scoreboard.NoCollisionTeam}. Over an invisible
 * player the client draws a name tag only for his own team, and a name tag can
 * say nothing but his name. This name is a text display of its own, see
 * {@link NameDisplay}: everybody who sees him sees it, and it says what
 * {@code messages.player.name-format} says - "Camera of {player}", say.</p>
 *
 * <p>On a tour the guests fly where the guide flies, and their names would
 * be written over each other. So the names of those who follow him stand
 * over his camera, stacked over his own, see
 * {@link de.elia.cameraplugin.tour.CamTour#stackedOver}: his at the bottom,
 * {@code player.tour-guide-format}, theirs over it,
 * {@code player.tour-follower-format}. Their own names are hidden while
 * they do.</p>
 *
 * <p>How it keeps to him is the {@link NameMode}: put back over his head every
 * tick, or sitting on him as a passenger. A passenger has to come off before a
 * teleport. Spigot turns down every {@code player.teleport()} of a player who
 * carries one, Paper those into another world, and a portal throws it off -
 * on Paper it was left standing at the portal. The plugin takes it off before
 * its own teleports, see {@link #takeOffForTeleport(Player)}, the teleport
 * events do so before a change of worlds, and the next tick puts it back
 * on.</p>
 */
public final class CamNameTag implements Listener {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /**
     * The same mark the name over the body carries, so
     * {@link de.elia.cameraplugin.body.BodySpawner#removeLeftoverEntities()}
     * finds both.
     */
    private final NamespacedKey nameKey;
    private final Map<UUID, TextDisplay> names = new HashMap<>();
    /** What each name says right now, so it is only sent anew when that changes. */
    private final Map<UUID, String> texts = new HashMap<>();
    private final Map<UUID, BukkitRunnable> tasks = new HashMap<>();

    public CamNameTag(CameraPlugin plugin, CamSettings settings, Messages messages, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.cameraPlayers = cameraPlayers;
        this.nameKey = new NamespacedKey(plugin, "cam_name");
    }

    public void startFor(Player player) {
        stopFor(player);
        if (!settings.showsPlayerName()) {
            return;
        }
        TextDisplay name = putUp(player);
        if (name == null) {
            return;
        }
        names.put(player.getUniqueId(), name);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.contains(player.getUniqueId()) || !player.isOnline()) {
                    stopFor(player);
                    return;
                }
                TextDisplay current = keepToPlayer(player);
                if (current == null) {
                    stopFor(player);
                    return;
                }
                String text = textFor(player);
                if (!text.equals(texts.get(player.getUniqueId()))) {
                    current.setText(text);
                    texts.put(player.getUniqueId(), text);
                }
                showToViewers(player, current);
            }
        };
        task.runTaskTimer(plugin, 1L, 1L);
        tasks.put(player.getUniqueId(), task);
    }

    /**
     * Puts up the name, hidden from everybody: {@link #showToViewers} lets the
     * right ones see it, starting with the next tick. In mode RIDE it is put
     * onto him straight away.
     *
     * @return the name, or {@code null} when the text says nothing
     */
    private TextDisplay putUp(Player player) {
        String text = textFor(player);
        boolean rides = rides();
        TextDisplay name = NameDisplay.spawnStyled(nameLocation(player), text, settings.getPlayerNameStyle(), nameKey,
                display -> {
                    display.setVisibleByDefault(false);
                    if (rides) {
                        liftOverHead(display);
                    }
                });
        if (name != null && rides) {
            player.addPassenger(name);
        }
        texts.put(player.getUniqueId(), text);
        return name;
    }

    /**
     * What the name over him says, coloured: his {@code player.name-format}.
     * Over the camera of a guide, the names of the guests who follow him
     * stand over it, the one who joined first right above his - a text
     * display writes its lines upwards from where it stands.
     */
    private String textFor(Player player) {
        String own = nameOf(player);
        List<Player> guests = plugin.getTour().stackedOver(player);
        if (guests.isEmpty()) {
            return NameDisplay.styled(own, settings.getPlayerNameStyle());
        }
        List<String> lines = new ArrayList<>();
        for (int i = guests.size() - 1; i >= 0; i--) {
            String line = NameDisplay.styled(onTour("player.tour-follower-format", guests.get(i)),
                    settings.getPlayerNameStyle());
            if (!ChatColor.stripColor(line).isBlank()) {
                lines.add(line);
            }
        }
        String guide = onTour("player.tour-guide-format", player);
        lines.add(ChatColor.stripColor(guide).isBlank() ? NameDisplay.styled(own, settings.getPlayerNameStyle())
                : guide);
        return String.join("\n", lines);
    }

    /** His {@code player.name-format}. */
    private String nameOf(Player player) {
        return messages.getMessage("player.name-format").replace("{player}", player.getName());
    }

    /**
     * One line of the names of a tour: {@code {name}} is what his
     * name-format says, without its colour - the line brings its own -,
     * {@code {player}} his name.
     */
    private String onTour(String key, Player player) {
        return messages.getMessage(key).replace("{name}", NameDisplay.withoutLeadingColor(nameOf(player)))
                .replace("{player}", player.getName());
    }

    private boolean rides() {
        return settings.getPlayerNameMode() == NameMode.RIDE;
    }

    /**
     * Where the name of the camera player stands: over his head, where the game
     * would put his name tag. Measured from his height of the moment, which
     * shrinks while he swims or crawls.
     */
    private Location nameLocation(Player player) {
        return player.getLocation().add(0.0, player.getHeight() + NameDisplay.ABOVE_HEAD, 0.0);
    }

    /**
     * A passenger sits where the game seats one, on top of the head. The text
     * is lifted from there to where a name tag starts, the same spot mode
     * FOLLOW puts the whole name at.
     */
    private static void liftOverHead(TextDisplay display) {
        Transformation shape = display.getTransformation();
        display.setTransformation(new Transformation(new Vector3f(0f, (float) NameDisplay.ABOVE_HEAD, 0f),
                shape.getLeftRotation(), shape.getScale(), shape.getRightRotation()));
    }

    /**
     * Keeps the name on the player: puts it back over him whenever the two
     * have come apart, or back onto him when it no longer sits there.
     *
     * <p>Into another world it is not carried but put up anew where he came
     * out. Taken off for a teleport, it is put up anew wherever he
     * landed.</p>
     *
     * @return the name over him, or {@code null} when it could not be put up
     */
    private TextDisplay keepToPlayer(Player player) {
        TextDisplay name = names.get(player.getUniqueId());
        if (name == null || !name.isValid() || !name.getWorld().equals(player.getWorld())) {
            if (name != null) {
                takeDown(name);
            }
            name = putUp(player);
            if (name == null) {
                names.remove(player.getUniqueId());
                texts.remove(player.getUniqueId());
                return null;
            }
            names.put(player.getUniqueId(), name);
            return name;
        }
        if (rides()) {
            if (!player.getPassengers().contains(name)) {
                name.teleport(nameLocation(player));
                player.addPassenger(name);
            }
        } else {
            Location target = nameLocation(player);
            if (name.getLocation().distanceSquared(target)
                    > CamSettings.MIN_MOVE_THRESHOLD * CamSettings.MIN_MOVE_THRESHOLD) {
                name.teleport(target);
            }
        }
        return name;
    }

    /**
     * Shows the name to everybody who should see it and hides it from the
     * rest - from everybody, while it stands over the camera of his guide.
     */
    private void showToViewers(Player owner, TextDisplay name) {
        boolean stacked = plugin.getTour().followsClosely(owner);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean shown = !stacked && showsNameTo(owner, viewer);
            if (shown == viewer.canSee(name)) {
                continue;
            }
            if (shown) {
                viewer.showEntity(plugin, name);
            } else {
                viewer.hideEntity(plugin, name);
            }
        }
    }

    /**
     * Whether this viewer is shown the name: everybody the camera player is
     * shown to, which {@code player_visibility_mode} decides, but not he
     * himself - nobody has a name tag over his own head either -, and not
     * whoever is on the same tour, mostly at the same spot.
     */
    private boolean showsNameTo(Player owner, Player viewer) {
        return !viewer.equals(owner) && viewer.canSee(owner) && !plugin.getTour().hidesCamera(viewer, owner);
    }

    /**
     * Takes the name off a camera player who is about to be teleported, in
     * mode RIDE. The next tick puts it back on, wherever he landed.
     *
     * <p>Called by the plugin before each teleport of its own: Spigot turns
     * that teleport down as long as he carries a passenger, before any event
     * is fired.</p>
     */
    public void takeOffForTeleport(Player player) {
        if (!rides()) {
            return;
        }
        TextDisplay name = names.get(player.getUniqueId());
        if (name != null && name.isValid()) {
            takeDown(name);
        }
    }

    /**
     * Before a teleport into another world, by a command or another plugin: a
     * passenger does not come along there. Within one world it stays on - the
     * server takes it along, except for {@code player.teleport()} on Spigot,
     * which never gets as far as this event.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCameraTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        World target = to == null ? null : to.getWorld();
        if (target == null || !target.equals(event.getFrom().getWorld())) {
            takeOffForTeleport(event.getPlayer());
        }
    }

    /** Before a trip through a portal, which always leads into another world. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCameraPortal(PlayerPortalEvent event) {
        takeOffForTeleport(event.getPlayer());
    }

    /** Takes the name away, at the end of camera mode. */
    public void stopFor(Player player) {
        BukkitRunnable task = tasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        TextDisplay name = names.remove(player.getUniqueId());
        if (name != null) {
            takeDown(name);
        }
        texts.remove(player.getUniqueId());
    }

    /**
     * Gets it off the player first and only then out of the world: a removed
     * entity still sitting on him would keep Spigot turning his teleport down.
     */
    private static void takeDown(TextDisplay name) {
        name.leaveVehicle();
        name.remove();
    }

    public void onDisable() {
        for (BukkitRunnable task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        for (TextDisplay name : names.values()) {
            takeDown(name);
        }
        names.clear();
        texts.clear();
    }
}
