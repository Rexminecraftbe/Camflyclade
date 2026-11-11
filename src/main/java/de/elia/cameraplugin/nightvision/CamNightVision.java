package de.elia.cameraplugin.nightvision;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.preferences.CamPreferences;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Night vision for the camera player, {@code night-vision}: always, never,
 * or only while it is dark around the camera.
 *
 * <p>It is an effect like any other, but without particles and without an
 * icon: nobody else sees anything of it, and the camera player only sees the
 * world brighter. Its duration is endless, since one that runs out flickers
 * for its last ten seconds.</p>
 *
 * <p>Only the effect this class gave is ever taken away again. Night vision
 * the player already had is paused with his other effects when camera mode
 * starts and comes back when it ends; one somebody hands him in between, with
 * {@code /effect} say, is left alone.</p>
 *
 * <p>With {@code night-vision.player-choice} each player picks the mode for
 * himself, {@code /cam nightvision}. The choice holds for every flight after
 * it, kept as {@code player-settings.storage} says, see
 * {@link CamPreferences}: in his file, or until he leaves the server.</p>
 *
 * <p>A spot saved with a night vision of its own, see
 * {@link de.elia.cameraplugin.spot.SpotLook}, gives it to whoever jumps there
 * in place of his own, while {@code player-choice} lets anybody
 * pick one at all - until his camera is put down somewhere else, or he picks
 * one himself. It is never kept as his own. {@code /cam resume} gives him the
 * night vision of his last flight again the same way.</p>
 *
 * <p>A guest on a tour has the night vision of his guide in place of his own,
 * {@code follow.guide-night-vision}: the mode that holds for the guide in
 * camera mode, none while the guide leads out of it - the guide sees no
 * brighter world either, see {@link #showGuide}. Should he pick one himself,
 * his own holds until he is off the tour.</p>
 */
public final class CamNightVision {

    /** How often the light around the camera is looked at, in ticks. */
    private static final long INTERVAL = 10L;

    /**
     * How much brighter than {@code light-level} it has to get before the
     * night vision goes off again. Without that margin it would switch on and
     * off over and over while the camera flies along the edge of a light.
     */
    private static final int MARGIN = 2;

    private static final PotionEffect NIGHT_VISION = new PotionEffect(
            PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false);

    private final JavaPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** Keeps the mode each player picked with {@code /cam nightvision}. */
    private final CamPreferences preferences;
    private final Map<UUID, BukkitRunnable> tasks = new HashMap<>();
    /** The players wearing the night vision of this class right now. */
    private final Set<UUID> given = new HashSet<>();
    /** The mode of the spot each camera player was put down at last, in place of his own, see {@link #showSpot}. */
    private final Map<UUID, NightVisionMode> spotModes = new HashMap<>();
    /** The mode of the guide each guest follows, in place of his own, see {@link #showGuide}. */
    private final Map<UUID, NightVisionMode> guideModes = new HashMap<>();
    /** The guests who picked a mode of their own on the tour they follow: the guide's no longer stands in. */
    private final Set<UUID> ownOnTour = new HashSet<>();

    public CamNightVision(JavaPlugin plugin, CamSettings settings, Messages messages, CameraPlayers cameraPlayers,
                          CamPreferences preferences) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.cameraPlayers = cameraPlayers;
        this.preferences = preferences;
    }

    /**
     * The mode that holds for this player: the one he picked, while
     * {@code player-choice} lets him, otherwise {@code night-vision.mode}.
     * The mode of a spot his camera was put down at, or of the guide he
     * follows, stands in for his own.
     */
    public NightVisionMode modeFor(Player player) {
        NightVisionMode own = chosenMode(player);
        return own != null ? own : settings.getNightVisionMode();
    }

    /**
     * The mode of the guide he follows, while it stands in for his own; else
     * the mode he picked himself, or the one of the spot his camera was put
     * down at, while {@code player-choice} lets anybody pick one; {@code null}
     * while {@code night-vision.mode} holds for him. What a spot he saves now
     * takes along, and where his last flight ends.
     */
    public NightVisionMode chosenMode(Player player) {
        NightVisionMode guide = guideModes.get(player.getUniqueId());
        if (guide != null) {
            return guide;
        }
        if (!settings.isNightVisionPlayerChoice()) {
            return null;
        }
        NightVisionMode spot = spotModes.get(player.getUniqueId());
        return spot != null ? spot : preferences.nightVision(player);
    }

    /**
     * A guest follows his guide on a tour, every tick: the mode that holds
     * for the guide in camera mode stands in for his own - none while the
     * guide leads out of it -, with {@code follow.guide-night-vision}. Once he
     * picked one himself on this tour, his own holds. Only a change counts.
     */
    public void showGuide(Player guest, Player guide) {
        UUID id = guest.getUniqueId();
        if (!cameraPlayers.contains(id)) {
            return;
        }
        NightVisionMode wanted = !settings.isTourGuideNightVision() || ownOnTour.contains(id) ? null
                : cameraPlayers.contains(guide.getUniqueId()) ? modeFor(guide) : NightVisionMode.OFF;
        NightVisionMode was = wanted == null ? guideModes.remove(id) : guideModes.put(id, wanted);
        if (was != wanted) {
            restart(guest);
        }
    }

    /** He is off the tour he followed: his own night vision holds again. */
    public void leaveGuide(Player guest) {
        UUID id = guest.getUniqueId();
        ownOnTour.remove(id);
        if (guideModes.remove(id) != null) {
            restart(guest);
        }
    }

    /**
     * He picks a mode himself: neither a spot nor a guide stands in for it
     * any more - on a tour, until it is over.
     */
    private void pickedOwn(Player player) {
        UUID id = player.getUniqueId();
        spotModes.remove(id);
        if (guideModes.remove(id) != null) {
            ownOnTour.add(id);
        }
    }

    /**
     * The camera of a player was put down at a spot: its night vision stands
     * in for his own, while {@code player-choice} lets anybody pick one. A
     * spot without one gives him his own back, should an earlier spot have
     * stood in for it.
     *
     * @param mode the mode of the spot, or {@code null} for none
     */
    public void showSpot(Player player, NightVisionMode mode) {
        UUID id = player.getUniqueId();
        if (!cameraPlayers.contains(id)) {
            return;
        }
        NightVisionMode wanted = settings.isNightVisionPlayerChoice() ? mode : null;
        NightVisionMode was = wanted == null ? spotModes.remove(id) : spotModes.put(id, wanted);
        if (was != wanted) {
            restart(player);
        }
    }

    /** His camera was put down somewhere that is no spot: his own night vision holds again. */
    public void leaveSpot(Player player) {
        if (spotModes.remove(player.getUniqueId()) != null) {
            restart(player);
        }
    }

    /**
     * Starts looking after the night vision of a player who has just entered
     * camera mode. In mode {@code true} that is done as well, so the effect
     * comes back after an {@code /effect clear}.
     */
    public void startFor(Player player) {
        NightVisionMode mode = modeFor(player);
        // While the plugin is being switched off - the server stops in the
        // middle of a tour, say, and the guest gets his own mode back - Bukkit
        // turns every new task down. His camera mode ends right after anyway.
        if (mode == NightVisionMode.OFF || !plugin.isEnabled()) {
            return;
        }
        BukkitRunnable old = tasks.remove(player.getUniqueId());
        if (old != null) {
            old.cancel();
        }
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !cameraPlayers.contains(player.getUniqueId())) {
                    cancel();
                    tasks.remove(player.getUniqueId(), this);
                    return;
                }
                update(player, mode == NightVisionMode.ALWAYS || isDark(player));
            }
        };
        task.runTaskTimer(plugin, 0L, INTERVAL);
        tasks.put(player.getUniqueId(), task);
    }

    /**
     * Whether it is dark where the camera looks out from. The light level of
     * the block at the eyes counts the sky as dark as the time of day and the
     * weather make it, so the open sky at night is dark as well as a cave.
     */
    private boolean isDark(Player player) {
        int light = player.getEyeLocation().getBlock().getLightLevel();
        int limit = settings.getNightVisionLightLevel();
        return light <= (given.contains(player.getUniqueId()) ? limit + MARGIN : limit);
    }

    private void update(Player player, boolean wanted) {
        UUID id = player.getUniqueId();
        if (wanted) {
            // Night vision of somebody else is left on him, and is not taken
            // for this one either: it is not this class's to take away later.
            if (!player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                player.addPotionEffect(NIGHT_VISION);
                given.add(id);
            }
        } else if (given.remove(id)) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    /**
     * Takes the night vision of this class off a player leaving camera mode.
     * Called before his paused effects are given back, so that night vision
     * among them is not taken along.
     */
    public void stopFor(Player player) {
        spotModes.remove(player.getUniqueId());
        guideModes.remove(player.getUniqueId());
        ownOnTour.remove(player.getUniqueId());
        stopLooking(player);
    }

    /** Stops looking after his night vision, and takes off the one of this class. */
    private void stopLooking(Player player) {
        BukkitRunnable task = tasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        if (given.remove(player.getUniqueId())) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    /**
     * {@code /cam nightvision}: {@code on}, {@code off} or {@code dark} picks
     * the mode, {@code reset} gives him the one of the file again, and on its
     * own it switches between on and off - off if anything else holds now.
     * Allowed before camera mode as well, for the next flight; in camera mode
     * it holds at once.
     */
    public void command(Player player, String[] args) {
        if (!settings.isNightVisionPlayerChoice()) {
            messages.sendConfiguredMessage(player, "nightvision-off");
            return;
        }
        if (args.length > 1) {
            messages.sendConfiguredMessage(player, "nightvision-usage");
            return;
        }
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (word.equals("reset")) {
            // His own choice: a spot or a guide no longer stands in for it.
            pickedOwn(player);
            preferences.setNightVision(player, null);
            restart(player);
            messages.sendConfiguredMessage(player, "nightvision-reset", "{mode}", name(modeFor(player)));
            return;
        }
        NightVisionMode mode = switch (word) {
            case "" -> modeFor(player) == NightVisionMode.OFF ? NightVisionMode.ALWAYS : NightVisionMode.OFF;
            case "on" -> NightVisionMode.ALWAYS;
            case "off" -> NightVisionMode.OFF;
            case "dark" -> NightVisionMode.DARK;
            default -> null;
        };
        if (mode == null) {
            messages.sendConfiguredMessage(player, "nightvision-usage");
            return;
        }
        pickedOwn(player);
        preferences.setNightVision(player, mode);
        restart(player);
        messages.sendConfiguredMessage(player, preferences.keepsForGood() ? "nightvision-set" : "nightvision-set-memory",
                "{mode}", name(mode));
    }

    /** Whether he picked a mode of his own, which the menu marks; otherwise the one of the file holds. */
    public boolean hasOwnMode(Player player) {
        return settings.isNightVisionPlayerChoice() && preferences.nightVision(player) != null;
    }

    /** The words of {@code /cam nightvision} that come after it, for the tab completion. */
    public static String[] words() {
        return new String[]{"on", "off", "dark", "reset"};
    }

    /** What the messages call a mode, {@code nightvision-always}, {@code -never} or {@code -dark}. */
    private String name(NightVisionMode mode) {
        return messages.getMessage(switch (mode) {
            case ALWAYS -> "nightvision-always";
            case OFF -> "nightvision-never";
            case DARK -> "nightvision-dark";
        });
    }

    /**
     * Lets a new mode hold at once for a player in camera mode. The task is
     * replaced, the effect itself is left to it: a night vision that is still
     * wanted stays on without a flicker, and one that is no longer wanted the
     * next look takes off - off as a mode has no task, so that one goes here.
     */
    private void restart(Player player) {
        if (!cameraPlayers.contains(player.getUniqueId())) {
            return;
        }
        if (modeFor(player) == NightVisionMode.OFF) {
            stopLooking(player);
        } else {
            startFor(player);
        }
    }

    public void onDisable() {
        spotModes.clear();
        guideModes.clear();
        ownOnTour.clear();
        for (BukkitRunnable task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        given.clear();
    }
}
