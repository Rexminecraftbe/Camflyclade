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
     */
    public NightVisionMode modeFor(Player player) {
        NightVisionMode own = settings.isNightVisionPlayerChoice() ? preferences.nightVision(player) : null;
        return own != null ? own : settings.getNightVisionMode();
    }

    /**
     * Starts looking after the night vision of a player who has just entered
     * camera mode. In mode {@code true} that is done as well, so the effect
     * comes back after an {@code /effect clear}.
     */
    public void startFor(Player player) {
        NightVisionMode mode = modeFor(player);
        if (mode == NightVisionMode.OFF) {
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
        preferences.setNightVision(player, mode);
        restart(player);
        messages.sendConfiguredMessage(player, preferences.keepsForGood() ? "nightvision-set" : "nightvision-set-memory",
                "{mode}", name(mode));
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
            stopFor(player);
        } else {
            startFor(player);
        }
    }

    public void onDisable() {
        for (BukkitRunnable task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        given.clear();
    }
}
