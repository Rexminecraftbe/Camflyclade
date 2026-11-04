package de.elia.cameraplugin.progress;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code progress-guard}: what the camera goes through does not count for the
 * player - no advancements, no statistics of moving about.
 *
 * <p>The camera flies where the player never went. Without this the flying
 * alone would earn "Adventuring Time" biome by biome, the advancements for
 * entering structures or the Nether, and a long column of blocks flown in the
 * statistics.</p>
 *
 * <p>{@code progress-guard.advancements} turns away every criterion of an
 * advancement that stands in the advancement screen - one of the game's, or
 * of a data pack -, whatever grants it: the game, a command, another plugin.
 * Recipes are left alone, and so are the advancements without a display that
 * data packs hang their functions on: those are no progress anybody sees, and
 * a data pack would stop working for camera players. The only event that
 * asks before a criterion is granted is Paper's, looked up at runtime like
 * the events in {@link de.elia.cameraplugin.interaction.CamKnockbackGuard};
 * on Spigot camera players earn advancements as before. Whether an
 * advancement has a display is asked by name as well: Paper's
 * {@code getDisplay} hands back a type of its own, not Bukkit's, and a call
 * compiled against Bukkit's would not be found there.</p>
 *
 * <p>{@code progress-guard.statistics} keeps the statistics of moving as they
 * were when camera mode began: every distance (flown, walked, swum ...),
 * jumps and the time spent sneaking. They are not turned away one by one -
 * Bukkit asks about none of them before counting, too many come in, see
 * {@code CraftEventFactory.handleStatisticsIncrease} -, they are read when
 * camera mode starts, kept in the {@link CameraData} and in the file of
 * {@link de.elia.cameraplugin.crash.CamCrashGuard}, and set back when it ends.
 * That is exact: while camera mode runs, the player is the camera, and his
 * body moves without him. Everything else counts on - the time played,
 * damage and deaths, which the body passes on to the player for real, and the
 * time since he last slept, which brings the phantoms.</p>
 */
public final class CamProgressGuard {

    /** Paper's event before a criterion of an advancement is granted. */
    private static final String PAPER_CRITERION_EVENT =
            "com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent";

    /**
     * The statistics of moving: every distance - their names end in
     * {@code _ONE_CM} -, jumps and the time spent sneaking.
     */
    private static final Set<Statistic> MOVEMENT = movementStatistics();

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;

    public CamProgressGuard(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /** Whether this statistic is one of moving, which {@code progress-guard.statistics} keeps back. */
    public static boolean isMovement(Statistic statistic) {
        return MOVEMENT.contains(statistic);
    }

    /**
     * The statistics of moving as they stand now, for camera mode to put them
     * back when it ends. Read whatever {@code progress-guard.statistics} says:
     * the switch may be turned on before the flight ends.
     */
    public static Map<Statistic, Integer> readMovement(Player player) {
        Map<Statistic, Integer> values = new EnumMap<>(Statistic.class);
        for (Statistic statistic : MOVEMENT) {
            values.put(statistic, player.getStatistic(statistic));
        }
        return Collections.unmodifiableMap(values);
    }

    /** The same by name, for a file: name of the statistic to its value. */
    public static Map<String, Integer> byName(Map<Statistic, Integer> values) {
        Map<String, Integer> named = new LinkedHashMap<>();
        values.forEach((statistic, value) -> named.put(statistic.name(), value));
        return named;
    }

    /** Puts back what the camera moved, when camera mode ends, while {@code progress-guard.statistics} is on. */
    public void stopFor(Player player, CameraData data) {
        if (settings.blocksStatistics()) {
            data.getMovementAtStart().forEach(player::setStatistic);
        }
    }

    /**
     * The same from the file of the crash protection: sets the statistics of
     * moving back to what they were when camera mode started, while
     * {@code progress-guard.statistics} is on. Names that are no statistic of
     * moving - from a file of another version - are skipped.
     *
     * @param atStart by the name of each statistic, as {@link #byName} wrote them
     */
    public void restoreMovement(Player player, Map<String, ?> atStart) {
        if (!settings.blocksStatistics()) {
            return;
        }
        atStart.forEach((name, value) -> {
            Statistic statistic;
            try {
                statistic = Statistic.valueOf(name);
            } catch (IllegalArgumentException ex) {
                return;
            }
            if (isMovement(statistic) && value instanceof Number number && number.intValue() >= 0) {
                player.setStatistic(statistic, number.intValue());
            }
        });
    }

    /** Hands the check of advancements to the server, where it can ask about them. */
    public void register() {
        Class<? extends Event> grant;
        Method advancementOf;
        Method display;
        try {
            grant = Class.forName(PAPER_CRITERION_EVENT).asSubclass(Event.class);
            advancementOf = grant.getMethod("getAdvancement");
            display = Advancement.class.getMethod("getDisplay");
        } catch (ClassNotFoundException | NoSuchMethodException | ClassCastException ex) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvent(grant, new Listener() {
        }, EventPriority.NORMAL, (listener, event) -> {
            if (!grant.isInstance(event) || !settings.blocksAdvancements()
                    || !cameraPlayers.contains(((PlayerEvent) event).getPlayer().getUniqueId())) {
                return;
            }
            try {
                Advancement advancement = (Advancement) advancementOf.invoke(event);
                // Only what stands in the advancement screen.
                if (advancement != null && display.invoke(advancement) != null) {
                    ((Cancellable) event).setCancelled(true);
                }
            } catch (ReflectiveOperationException ex) {
                throw new EventException(ex);
            }
        }, plugin, true);
    }

    private static Set<Statistic> movementStatistics() {
        Set<Statistic> movement = EnumSet.of(Statistic.JUMP, Statistic.SNEAK_TIME);
        for (Statistic statistic : Statistic.values()) {
            if (statistic.name().endsWith("_ONE_CM")) {
                movement.add(statistic);
            }
        }
        return Collections.unmodifiableSet(movement);
    }
}
