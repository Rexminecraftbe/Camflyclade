package de.elia.cameraplugin.mob;

import de.elia.cameraplugin.body.MobHeads;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Keeps mobs from looking at a camera player, after
 * {@code camera-mode.mobs-look-at-player}.
 *
 * <p>Looking is a goal of its own in a mob, apart from whom it goes for: a cow
 * turns its head to the nearest player it notices, a wandering trader to
 * whoever stands right next to it. It picks that player without an event, so
 * {@link MobTargeting} does not reach it, and invisibility does not stop it
 * either - it only shortens the reach, down to two blocks. Closer than that,
 * the game has every mob notice an invisible player.</p>
 *
 * <p>Paper hands out the goals of a mob, and that is where this steps in:
 * every mob around a camera player gets one goal of its own on top, see
 * {@link Gaze}. It takes the look over the moment the mob's own goal has
 * picked a camera player, still in the same tick and before the head has
 * turned, and hands it back in the next. Swapping the game's goal for one of
 * the plugin's own would have been the plain way, but Paper does not tell the
 * priority a goal of the game was given, and without it no replacement could
 * keep its place among the others.</p>
 *
 * <p>Looked up at runtime, like the other parts only Paper has. Spigot has no
 * such API; there mobs look at a camera player as the game has it. Not
 * reached on Paper either: mobs steered by their brain - villagers, piglins,
 * goats, axolotls and the like. They pick whom they look at in their brain,
 * and no API reaches that.</p>
 */
public final class MobGaze {

    /**
     * The furthest a mob of the game looks at a player, in blocks: the fox,
     * with 24. Most look a lot less far - a cow 6, a zombie 8.
     */
    private static final double MAX_LOOK_RANGE = 24.0;

    /** How close the game lets every mob notice a player, however hidden, in blocks. */
    private static final double MIN_NOTICE_RANGE = 2.0;

    /** What sneaking leaves of the distance at which a mob notices a player - the game's value. */
    private static final double SNEAK_VISIBILITY = 0.8;

    /**
     * What invisibility leaves of that distance, times the share of the armour
     * slots that hold something - the game's value.
     */
    private static final double INVISIBLE_VISIBILITY = 0.7;

    /** The least share of armour the game counts on an invisible player. */
    private static final double MIN_ARMOR_COVER = 0.1;

    /** What a worn head of the looking mob's own kind leaves of that distance - the game's value. */
    private static final double MOB_HEAD_VISIBILITY = 0.5;

    /** Ticks between two looks for mobs around the camera players. */
    private static final long SCAN_TICKS = 5L;

    /**
     * How far around a camera player a mob gets the goal, in blocks: as far as
     * any mob looks, plus the way a camera player can fly between two looks
     * around - five and a half blocks at most -, with room to spare.
     */
    private static final double SCAN_RADIUS = MAX_LOOK_RANGE + 8.0;

    /**
     * How far a mob has to be from every camera player before it loses the
     * goal again, in blocks. Further out than {@link #SCAN_RADIUS}, so that a
     * camera player hovering at the edge does not have it added and taken away
     * over and over.
     */
    private static final double RELEASE_RADIUS = SCAN_RADIUS + 8.0;

    /**
     * The priority the goal is added with: ahead of everything the game gives
     * a mob. It may only take the look from a goal it comes before, and the
     * priority the game gave the look goal is unknown, see the class comment.
     * Being first does not keep anything else from running: the goal takes
     * the look from the look goals of the game only, and only until the mob
     * goes through its goals the next time, see {@link Gaze}.
     */
    private static final int PRIORITY = Integer.MIN_VALUE;

    /**
     * The goals of the game that look at players, by their names in Paper's
     * {@code VanillaGoal}. A name a later version drops is left out.
     */
    private static final String[] LOOK_GOALS = {
            "LOOK_AT_PLAYER", "INTERACT", "FOX_LOOK_AT_PLAYER", "PANDA_LOOK_AT_PLAYER"};

    private final JavaPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    /** The mobs around the camera players, by mob id. */
    private final Map<UUID, Gaze> gazes = new HashMap<>();
    /** Paper's goal API, {@code null} where there is none. */
    private PaperGoals paper;
    private BukkitTask task;
    /**
     * Set when the goal API answered with an error. It is not asked again
     * from then on, and the goals that are out are taken back with the next
     * look around - not at once, because the error may come up while a mob is
     * going through its goals, and they must not be changed then.
     */
    private boolean broken;

    public MobGaze(JavaPlugin plugin, CamSettings settings, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.settings = settings;
        this.cameraPlayers = cameraPlayers;
    }

    /** Looks up Paper's goal API and starts looking for mobs around the camera players. */
    public void start() {
        paper = PaperGoals.lookUp(plugin);
        if (paper == null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::scan, 1L, SCAN_TICKS);
    }

    /** Stops looking and takes every goal back from the mobs. */
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        releaseAll();
    }

    /**
     * One look around: the mobs near a camera player get the goal, the ones
     * that are far from all of them now lose it. With no camera player left,
     * with {@code mobs-look-at-player} switched on, or after an error, all of
     * them lose it.
     */
    private void scan() {
        if (broken || settings.mobsLookAtPlayer() || cameraPlayers.isEmpty()) {
            releaseAll();
            return;
        }
        try {
            Iterator<Gaze> leftBehind = gazes.values().iterator();
            while (leftBehind.hasNext()) {
                Gaze gaze = leftBehind.next();
                if (!gaze.mob.isValid()) {
                    // Gone, and its goals with it. A mob that is loaded again
                    // is built anew, without the goal.
                    leftBehind.remove();
                } else if (!cameraNear(gaze.mob, RELEASE_RADIUS)) {
                    gaze.release();
                    leftBehind.remove();
                }
            }
            for (UUID id : cameraPlayers.ids()) {
                Player camera = plugin.getServer().getPlayer(id);
                if (camera == null) {
                    continue;
                }
                Location at = camera.getLocation();
                for (Entity entity : camera.getNearbyEntities(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)) {
                    if (entity instanceof Mob mob && !gazes.containsKey(mob.getUniqueId())
                            && mob.getLocation().distanceSquared(at) <= SCAN_RADIUS * SCAN_RADIUS) {
                        gazes.put(mob.getUniqueId(), attach(mob));
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            fail(ex);
            releaseAll();
        }
    }

    /**
     * Gives a mob the goal, when it has a look goal of the game it could be
     * needed for.
     */
    private Gaze attach(Mob mob) throws ReflectiveOperationException {
        Gaze gaze = new Gaze(mob);
        // One left over from an earlier start of the plugin goes first, it has
        // the same key.
        paper.removeGoal(mob);
        if (paper.hasLookGoal(mob)) {
            gaze.goal = Proxy.newProxyInstance(MobGaze.class.getClassLoader(),
                    new Class<?>[] {paper.goalClass}, gaze);
            paper.addGoal(mob, PRIORITY, gaze.goal);
        }
        return gaze;
    }

    /** Takes the goal back from every mob that has it. */
    private void releaseAll() {
        for (Gaze gaze : gazes.values()) {
            try {
                gaze.release();
            } catch (ReflectiveOperationException | RuntimeException ex) {
                fail(ex);
            }
        }
        gazes.clear();
    }

    /** Notes an error of the goal API once and stops asking it, see {@link #broken}. */
    private void fail(Exception ex) {
        if (broken) {
            return;
        }
        broken = true;
        Throwable cause = ex instanceof InvocationTargetException target && target.getCause() != null
                ? target.getCause() : ex;
        plugin.getLogger().log(Level.WARNING, "Paper's mob goals answered with an error, so mobs look at"
                + " camera players again until the next start: " + cause, cause);
    }

    /** Whether a camera player is in the same world as this mob and at most {@code radius} blocks away. */
    private boolean cameraNear(Mob mob, double radius) {
        Location at = mob.getLocation();
        for (UUID id : cameraPlayers.ids()) {
            Player camera = plugin.getServer().getPlayer(id);
            if (camera != null && camera.getWorld().equals(at.getWorld())
                    && camera.getLocation().distanceSquared(at) <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    /**
     * The player a look goal of the game picks for this mob right now: the
     * nearest one it notices, by the same rules the game goes by.
     *
     * <p>It notices a player within its range - for this the furthest range
     * there is, {@link #MAX_LOOK_RANGE}, as the one of the goal is not to be
     * had -, shortened the way the game shortens it for whoever sneaks, is
     * invisible or wears the head of the mob's kind, but never below
     * {@link #MIN_NOTICE_RANGE}. On top of that it has to see the player, and
     * spectators, the dead and players riding the mob do not count. Nearest
     * means nearest to the mob's eyes.</p>
     *
     * @param camerasVisible whether camera players count as not invisible:
     *                       for a look the mob may have picked before they
     *                       turned invisible
     */
    private Player nearestNoticed(Mob mob, boolean camerasVisible) {
        Location feet = mob.getLocation();
        Location eyes = feet.clone();
        eyes.setY(mob.getEyeLocation().getY());
        List<Noticed> noticed = new ArrayList<>();
        for (Player player : mob.getWorld().getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.isDead() || ridesOn(player, mob)) {
                continue;
            }
            boolean seenAsVisible = camerasVisible && cameraPlayers.contains(player.getUniqueId());
            double range = Math.max(MAX_LOOK_RANGE * visibility(player, mob, seenAsVisible), MIN_NOTICE_RANGE);
            Location at = player.getLocation();
            if (feet.distanceSquared(at) <= range * range) {
                noticed.add(new Noticed(player, eyes.distanceSquared(at)));
            }
        }
        noticed.sort(Comparator.comparingDouble(Noticed::distanceSquared));
        // Line of sight last, it is the dearest to check: only until the first
        // one the mob can see.
        for (Noticed candidate : noticed) {
            if (mob.hasLineOfSight(candidate.player())) {
                return candidate.player();
            }
        }
        return null;
    }

    /** A player within the range of a mob, with the distance to its eyes. */
    private record Noticed(Player player, double distanceSquared) {
    }

    /**
     * What is left of the distance at which a mob notices this player, as a
     * share - the game's own reckoning.
     *
     * @param ignoreInvisibility whether to reckon as if the player were not invisible
     */
    private static double visibility(Player player, Mob mob, boolean ignoreInvisibility) {
        double visibility = 1.0;
        if (player.isSneaking()) {
            visibility *= SNEAK_VISIBILITY;
        }
        if (!ignoreInvisibility && player.isInvisible()) {
            visibility *= INVISIBLE_VISIBILITY * Math.max(armorCover(player), MIN_ARMOR_COVER);
        }
        if (MobHeads.matches(player.getEquipment(), mob.getType())) {
            visibility *= MOB_HEAD_VISIBILITY;
        }
        return visibility;
    }

    /** The share of the armour slots that hold something, the camera head included. */
    private static double armorCover(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        int worn = 0;
        for (ItemStack piece : armor) {
            if (piece != null && !piece.getType().isAir()) {
                worn++;
            }
        }
        return armor.length == 0 ? 0.0 : (double) worn / armor.length;
    }

    /** Whether the entity sits on this mob, directly or on something that does. */
    private static boolean ridesOn(Entity passenger, Entity vehicle) {
        for (Entity below = passenger.getVehicle(); below != null; below = below.getVehicle()) {
            if (below.equals(vehicle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The goal one mob gets, and what it knows about that mob's look.
     *
     * <p>It holds the look ({@code LOOK}) and comes before every goal of the
     * game, see {@link #PRIORITY}. A mob goes through its goals every second
     * tick, first stopping the ones that are done and then starting new ones,
     * one after the other in the order they were added - this one last. So it
     * gets to {@link #takesOver()} after the look goal of the game has picked
     * a player and started, but before any goal has acted: when it takes over,
     * the look goal stops before the head has turned at all. In the next round
     * it lets go again ({@code shouldStayActive} is {@code false}), before
     * anything starts, and the mob is as free as before.</p>
     */
    private final class Gaze implements InvocationHandler {

        private final Mob mob;
        /** The goal added to the mob, {@code null} for one without a look goal of the game. */
        private Object goal;
        /**
         * Whom the look goal of the mob may be on, see {@link #takesOver()}.
         * Empty while it is not running.
         */
        private final Set<UUID> lookedAt = new HashSet<>();
        /**
         * Whether the look of the mob has been followed since a camera player
         * came near. Before that, a running look goal may have picked a camera
         * player before that player turned invisible.
         */
        private boolean followed;

        private Gaze(Mob mob) {
            this.mob = mob;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "shouldActivate" -> takesOver();
                case "shouldStayActive" -> false;
                case "getKey" -> paper.key;
                case "getTypes" -> paper.lookTypes();
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "CamFly: mobs look past camera players";
                // start, stop and tick: nothing to do.
                default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args) : null;
            };
        }

        /**
         * Whether the goal takes the look over now, which stops the look goal
         * of the game.
         *
         * <p>That is when the look goal is running and may be on a camera
         * player. Which player it picked the game does not tell; it is the
         * nearest one it noticed when it started, see
         * {@link MobGaze#nearestNoticed(Mob, boolean)}. That is reckoned
         * again every round and gathered in {@link #lookedAt} for as long as
         * the look goal runs - started in this round, it picked the one of
         * now, started earlier one of before. Started before the plugin
         * followed the look at all, it may have picked a camera player who
         * could still be seen then, and the first round reckons with camera
         * players as visible. The goal takes over as soon as one of them is a
         * camera player now - also one who only started camera mode while
         * being looked at.</p>
         */
        private boolean takesOver() {
            if (broken || settings.mobsLookAtPlayer() || !cameraNear(mob, MAX_LOOK_RANGE)) {
                forget();
                return false;
            }
            try {
                if (!paper.lookGoalRunning(mob)) {
                    lookedAt.clear();
                    followed = true;
                    return false;
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                fail(ex);
                return false;
            }
            Player nearest = nearestNoticed(mob, !followed);
            followed = true;
            if (nearest != null) {
                lookedAt.add(nearest.getUniqueId());
            }
            for (UUID id : lookedAt) {
                if (cameraPlayers.contains(id)) {
                    lookedAt.clear();
                    return true;
                }
            }
            return false;
        }

        /** Forgets the look, for a mob no camera player is near. */
        private void forget() {
            lookedAt.clear();
            followed = false;
        }

        /** Takes the goal back from the mob, if it got one and is still there. */
        private void release() throws ReflectiveOperationException {
            if (goal != null && mob.isValid()) {
                paper.removeGoal(mob);
            }
            goal = null;
        }
    }

    /** Paper's goal API, reached by reflection: the Spigot API the plugin is built against has none. */
    private static final class PaperGoals {

        private final Object mobGoals;
        private final Class<?> goalClass;
        private final Method addGoal;
        private final Method removeGoal;
        private final Method getAllGoals;
        private final Method getRunningGoals;
        private final Method getKey;
        private final Method getNamespacedKey;
        /** {@code GoalType.LOOK}. */
        private final Object look;
        /** The key of the plugin's goal. */
        private final Object key;
        /** The keys of the look goals of the game, see {@link #LOOK_GOALS}. */
        private final Set<NamespacedKey> lookGoals;

        private PaperGoals(Object mobGoals, Class<?> mobGoalsClass, Class<?> goalClass, Class<?> keyClass,
                           Class<?> typeClass, Object look, Object key, Set<NamespacedKey> lookGoals)
                throws NoSuchMethodException {
            this.mobGoals = mobGoals;
            this.goalClass = goalClass;
            this.addGoal = mobGoalsClass.getMethod("addGoal", Mob.class, int.class, goalClass);
            this.removeGoal = mobGoalsClass.getMethod("removeGoal", Mob.class, keyClass);
            this.getAllGoals = mobGoalsClass.getMethod("getAllGoals", Mob.class, typeClass);
            this.getRunningGoals = mobGoalsClass.getMethod("getRunningGoals", Mob.class, typeClass);
            this.getKey = goalClass.getMethod("getKey");
            this.getNamespacedKey = keyClass.getMethod("getNamespacedKey");
            this.look = look;
            this.key = key;
            this.lookGoals = lookGoals;
        }

        /** Paper's goal API, or {@code null} where the server has none. */
        static PaperGoals lookUp(JavaPlugin plugin) {
            try {
                Class<?> mobGoalsClass = Class.forName("com.destroystokyo.paper.entity.ai.MobGoals");
                Class<?> goalClass = Class.forName("com.destroystokyo.paper.entity.ai.Goal");
                Class<?> keyClass = Class.forName("com.destroystokyo.paper.entity.ai.GoalKey");
                Class<?> typeClass = Class.forName("com.destroystokyo.paper.entity.ai.GoalType");
                Class<?> vanillaClass = Class.forName("com.destroystokyo.paper.entity.ai.VanillaGoal");
                Object mobGoals = Server.class.getMethod("getMobGoals").invoke(plugin.getServer());
                Method getNamespacedKey = keyClass.getMethod("getNamespacedKey");
                Set<NamespacedKey> lookGoals = new HashSet<>();
                for (String name : LOOK_GOALS) {
                    try {
                        lookGoals.add((NamespacedKey) getNamespacedKey.invoke(vanillaClass.getField(name).get(null)));
                    } catch (NoSuchFieldException ex) {
                        // Not in this version.
                    }
                }
                if (mobGoals == null || lookGoals.isEmpty()) {
                    return null;
                }
                Object look = typeClass.getMethod("valueOf", String.class).invoke(null, "LOOK");
                Object key = keyClass.getMethod("of", Class.class, NamespacedKey.class)
                        .invoke(null, Mob.class, new NamespacedKey(plugin, "look_past_cameras"));
                return new PaperGoals(mobGoals, mobGoalsClass, goalClass, keyClass, typeClass, look, key, lookGoals);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return null;
            }
        }

        void addGoal(Mob mob, int priority, Object goal) throws ReflectiveOperationException {
            addGoal.invoke(mobGoals, mob, priority, goal);
        }

        /** Takes the plugin's goal from the mob, every copy with its key. */
        void removeGoal(Mob mob) throws ReflectiveOperationException {
            removeGoal.invoke(mobGoals, mob, key);
        }

        /** Whether the mob has a look goal of the game at all. */
        boolean hasLookGoal(Mob mob) throws ReflectiveOperationException {
            return anyLookGoal((Collection<?>) getAllGoals.invoke(mobGoals, mob, look));
        }

        /** Whether a look goal of the game holds the look of the mob right now. */
        boolean lookGoalRunning(Mob mob) throws ReflectiveOperationException {
            return anyLookGoal((Collection<?>) getRunningGoals.invoke(mobGoals, mob, look));
        }

        private boolean anyLookGoal(Collection<?> goals) throws ReflectiveOperationException {
            for (Object goal : goals) {
                if (lookGoals.contains((NamespacedKey) getNamespacedKey.invoke(getKey.invoke(goal)))) {
                    return true;
                }
            }
            return false;
        }

        /** {@code EnumSet.of(GoalType.LOOK)}, a new one each time: Paper keeps what it is handed. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object lookTypes() {
            return EnumSet.of((Enum) look);
        }
    }
}
