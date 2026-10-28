package de.elia.cameraplugin.config;

import de.elia.cameraplugin.area.CamAreaRules;
import de.elia.cameraplugin.body.BodyType;
import de.elia.cameraplugin.body.MobTargetMode;
import de.elia.cameraplugin.body.MovementSensitivity;
import de.elia.cameraplugin.display.ActionBarDisplay;
import de.elia.cameraplugin.display.GlowMode;
import de.elia.cameraplugin.display.NameMode;
import de.elia.cameraplugin.list.ListAccess;
import de.elia.cameraplugin.menu.AdminButton;
import de.elia.cameraplugin.menu.MenuAccess;
import de.elia.cameraplugin.menu.MenuButton;
import de.elia.cameraplugin.menu.MenuIcon;
import de.elia.cameraplugin.menu.MenuSlot;
import de.elia.cameraplugin.mirrordamage.ArmorDamageMode;
import de.elia.cameraplugin.mirrordamage.DamageMode;
import de.elia.cameraplugin.mob.MobTargeting;
import de.elia.cameraplugin.movement.BorderMode;
import de.elia.cameraplugin.movement.DistanceMode;
import de.elia.cameraplugin.movement.FlightMedium;
import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.portal.PortalRules;
import de.elia.cameraplugin.scout.ScoutAnchor;
import de.elia.cameraplugin.session.CamGameMode;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.signal.SignalCosts;
import de.elia.cameraplugin.signal.SignalLoss;
import de.elia.cameraplugin.sound.CamSound;
import de.elia.cameraplugin.start.EffectStart;
import de.elia.cameraplugin.storage.StorageMode;
import de.elia.cameraplugin.timelimit.FlightLimit;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every value of the config file the plugin runs on, read in one go by
 * {@link #load(ConfigReader)}.
 */
public final class CamSettings {

    /**
     * Smallest threshold that is accepted for {@code body.move-threshold}, in
     * blocks. Anything below is raised to this value.
     */
    public static final double MIN_MOVE_THRESHOLD = 0.01;

    /**
     * Largest value that is accepted for
     * {@code camera-particles.particles-per-tick}. Every player who sees the
     * camera player gets that many each tick, and the client has to draw all
     * of them.
     */
    private static final double MAX_PARTICLES_PER_TICK = 100.0;

    /**
     * Fastest speed {@code flight-speed} accepts, 1 being the speed of the
     * game. Sprinting at it the camera covers more than five blocks a tick:
     * any faster and the world does not load in time ahead of it, nor does
     * the wall of {@code border-mode: barrier} get built.
     */
    public static final double MAX_FLIGHT_SPEED = 5.0;

    /** Slowest speed {@code flight-speed} accepts: slower than that hardly moves at all. */
    private static final double MIN_FLIGHT_SPEED = 0.1;

    /** The brightest light level there is, {@code night-vision.light-level} going up to it. */
    private static final int MAX_LIGHT_LEVEL = 15;

    /**
     * The shortest time {@code afk-end.seconds} accepts. Anything shorter
     * would take camera mode off a player who only stops to look at
     * something for a moment.
     */
    private static final int MIN_AFK_SECONDS = 10;

    /**
     * The longest wait {@code start-delay.seconds} accepts. Longer than a
     * minute, standing still is no longer a wait but a punishment.
     */
    private static final int MAX_START_DELAY_SECONDS = 60;

    /** The longest {@code cam-trail.show-seconds} accepts: ten minutes. */
    private static final int MAX_TRAIL_SHOW_SECONDS = 600;

    /** The most flights {@code cam-trail.flights} keeps of each player. */
    private static final int MAX_TRAIL_FLIGHTS = 20;

    /**
     * The most spots {@code viewpoints.max-per-player} lets each player keep,
     * short of no limit at all, 0. {@code /cam spot list} sends a line for
     * each of them, and more than this no longer fits into the chat a player
     * scrolls back through.
     */
    private static final int MAX_VIEWPOINTS = 100;

    /** The longest {@code viewpoints.views-cooldown-minutes} accepts: a day. */
    private static final int MAX_VIEWS_COOLDOWN_MINUTES = 1440;

    /** The longest {@code viewpoints.guestbook-interval-minutes} accepts: a day. */
    private static final int MAX_GUESTBOOK_INTERVAL_MINUTES = 1440;

    /** The levels of {@code signal.levels}, from the most bars down, as the config file names them. */
    private static final String[] SIGNAL_LEVEL_NAMES = {"excellent", "good", "medium", "weak"};

    /** From how many percent each of {@link #SIGNAL_LEVEL_NAMES} counts out of the box. */
    private static final double[] DEFAULT_SIGNAL_LEVELS = {80.0, 60.0, 40.0, 20.0};

    /**
     * The longest {@code signal.lost-seconds} accepts. Longer than a minute,
     * a camera without a signal is no longer looking for it but flying on
     * without one.
     */
    private static final int MAX_SIGNAL_LOST_SECONDS = 60;

    /**
     * The shortest wait {@code nudge.cooldown} accepts. One click sets off
     * several events, see {@link de.elia.cameraplugin.nudge.CamNudge}, and
     * without a wait each of them would nudge on its own.
     */
    private static final int MIN_NUDGE_COOLDOWN = 1;

    /**
     * The farthest {@code anti-scouting.radius} reaches. The server sends
     * players to a client no further than about this anyway, so beyond it
     * nothing is left to hide.
     */
    private static final double MAX_SCOUT_RADIUS = 256.0;

    /** The most {@code menu.ban-shift-step} takes. */
    private static final int MAX_BAN_SHIFT_STEP = 100;

    /**
     * The widest {@code follow.radius}: about as far as the server sends other
     * players to a client, and a guest is to be seen close by anyway.
     */
    private static final double MAX_TOUR_RADIUS = 128.0;

    /**
     * The farthest {@code viewpoints.start-distance} and
     * {@code follow.start-distance} reach: as far as a tour invites.
     */
    private static final double MAX_START_DISTANCE = 128.0;

    /** The longest {@code follow.out-of-reach-seconds} accepts, like {@code signal.lost-seconds}. */
    private static final int MAX_TOUR_OUT_OF_REACH_SECONDS = 60;

    /** The most guests {@code follow.max-guests} lets one tour take. */
    private static final int MAX_TOUR_GUESTS = 100;

    /**
     * The longest {@code follow.invitation-seconds} and
     * {@code follow.invite-cooldown-seconds} accept: an hour.
     */
    private static final int MAX_TOUR_INVITATION_SECONDS = 3600;

    /** The shortest {@code follow.invitation-seconds} accepts: time enough to read it and click. */
    private static final int MIN_TOUR_INVITATION_SECONDS = 5;

    /** The section of {@code /cam follow}. */
    private static final String FOLLOW_SECTION = "follow";

    /**
     * What the section of {@code /cam follow} was called while it was
     * {@code /cam follow}: an older file is read as it was written, see
     * {@link ConfigReader#renamed}.
     */
    private static final String OLD_FOLLOW_SECTION = "tour";

    /**
     * Keys that are read although the config file built into the jar leaves
     * them out on purpose, so that they are not reported as unknown, see
     * {@link ConfigReader#warnUnknownKeys}. Also sections whose keys are names
     * the file chooses itself, such as the items of {@code start-cost.items}:
     * everything below them is checked when it is read instead. And the old
     * names of what {@code /cam follow} renamed, which an older file still
     * has.
     */
    public static final Set<String> UNLISTED_KEYS = Set.of("mirror-damage.debug", "start-cost.items",
            "signal.block-costs", OLD_FOLLOW_SECTION, "sounds.tour-invite", "sounds.tour-reach",
            "menu.buttons.tour", "menu.admin-buttons.tours");

    /**
     * The most of one item {@code start-cost.items} accepts: a full inventory
     * of it. More could never be paid.
     */
    private static final int MAX_COST_ITEMS = 36 * 64;

    private final JavaPlugin plugin;
    private final Messages messages;

    // Configurable values
    private boolean maxDistanceEnabled;
    private double maxDistance;
    /** How max-distance holds the camera, {@code camera-mode.max-distance-mode}. */
    private DistanceMode distanceMode;
    private int distanceWarningCooldown;
    /** What happens at the border of camera mode, {@code camera-mode.border-mode}. */
    private BorderMode borderMode;
    /** What the wall of the mode barrier is made of, {@code camera-mode.border-block}. */
    private BlockData borderBlock;
    /**
     * What the wall shows in place of water, lava and powder snow,
     * {@code camera-mode.border-block-water}, {@code -lava} and
     * {@code -powder-snow}.
     */
    private final Map<FlightMedium, BlockData> mediumBorderBlocks = new EnumMap<>(FlightMedium.class);
    /** How far around the player that wall is built, {@code camera-mode.border-radius}. */
    private double borderRadius;
    /**
     * Whether the border of {@code max-distance} and {@code cam-area} holds
     * the camera back at all.
     *
     * <p>{@code push-back} does, and {@code barrier} does with a wall that
     * stops somebody: made of a block that cannot be walked through, and
     * built at least a little way around the player. Air, water, grass or a
     * radius of 0 leave the camera exactly as free as {@code false} does - in
     * flight and behind portals alike.</p>
     */
    private boolean bordersHold;
    private boolean bodyNameVisible;
    /** Whether the name over the body shows the hearts of the player, {@code body.health-visible}. */
    private boolean bodyHealthVisible;
    /** Whether they stand over the name rather than under it, {@code body.health-position}. */
    private boolean bodyHealthAbove;
    /** Whether the hearts he is missing stand there as empty ones, {@code body.health-show-missing}. */
    private boolean bodyHealthShowMissing;
    /** Whether the yellow hearts of absorption stand there as well, {@code body.health-show-absorption}. */
    private boolean bodyHealthShowAbsorption;
    /** Whether every further line of hearts stands above the one before, {@code body.health-rows-upward}. */
    private boolean bodyHealthRowsUpward;
    /** How the name over the body looks, the section {@code body.name}. */
    private NameStyle bodyNameStyle;
    private boolean bodyVisible;
    /** Whether the armour the body wears is drawn, {@code body.armor-visible}. */
    private boolean bodyArmorVisible;
    private BodyType bodyType;
    private MovementSensitivity movementSensitivity;
    /** {@code body.move-threshold} squared, so the square root can be skipped. */
    private double moveThresholdSquared;
    /** Which range decides whether a mob notices the body. */
    private MobTargetMode mobTargetMode;
    /** The range of the mode {@code custom}, in blocks. */
    private double mobTargetRadius;
    /** Whether a mob head on the body halves the range of that kind of mob. */
    private boolean mobTargetHeads;
    /** Where camera mode may be started and flown, the section {@code cam-area}. */
    private final CamAreaRules camAreaRules;
    /** Whether a portal lets a camera player through, the section {@code portals}. */
    private final PortalRules portalRules = new PortalRules();
    /** Whether a teleport by a command ends camera mode, {@code teleports.command}. */
    private boolean commandTeleportEndsCam;
    /** Whether a teleport by another plugin ends camera mode, {@code teleports.plugin}. */
    private boolean pluginTeleportEndsCam;
    private VisibilityMode playerVisibilityMode;
    private boolean allowInvisibilityPotion;
    private GlowMode glowMode;
    /** Whether the camera player carries a name of the plugin, {@code camera-mode.name-visible}. */
    private boolean playerNameVisible;
    /** How that name keeps to him, {@code camera-mode.name-mode}. */
    private NameMode playerNameMode;
    /** How the name over the camera player looks, the section {@code camera-mode.name}. */
    private NameStyle playerNameStyle;
    /** Whether the camera may fly into lava, {@code camera-mode.allow_lava_flight}. */
    private boolean allowLavaFlight;
    /** Whether the camera may fly into water, {@code camera-mode.allow_water_flight}. */
    private boolean allowWaterFlight;
    /** Whether the camera may fly into powder snow, {@code camera-mode.allow_powder_snow_flight}. */
    private boolean allowPowderSnowFlight;
    /** The mode the camera player flies in, {@code camera-mode.gamemode}. */
    private CamGameMode camGameMode;
    /** Whether mobs look at a camera player, {@code camera-mode.mobs-look-at-player}. */
    private boolean mobsLookAtPlayer;
    /** What {@code camera-mode.start-with-effects} allows him to start with. */
    private EffectStart startWithEffects;

    /** Whether the particles are switched on, {@code camera-particles.enabled}. */
    private boolean particlesEnabled;
    /** Which particle it is, {@code camera-particles.particle}. */
    private Particle particleType;
    private double particleHeight;
    /** Particles per tick, below one meaning one every few ticks. */
    private double particlesPerTick;
    /** How far they scatter around the spot, {@code camera-particles.spread}. */
    private double particleSpread;

    private boolean actionBarEnabled;
    private int actionBarOffDuration;
    /** How long {@code actionbar-on} stands at the start before the distance, in ticks, {@code action-bar.on-duration}. */
    private int actionBarOnDuration;
    /** What the action bar shows while camera mode runs, {@code action-bar.display}. */
    private ActionBarDisplay actionBarDisplay;

    /** Whether the mouse wheel changes the speed, {@code flight-speed.mouse-wheel}. */
    private boolean flightSpeedWheel;
    /** The speeds of {@code flight-speed}, 1 being the speed of the game. */
    private double flightSpeedStart;
    private double flightSpeedMin;
    private double flightSpeedMax;
    private double flightSpeedStep;

    /** When the camera player sees in the dark, {@code night-vision.mode}. */
    private NightVisionMode nightVisionMode;
    /** Up to which light level it counts as dark, {@code night-vision.light-level}. */
    private int nightVisionLightLevel;
    /** Whether each player picks his own with {@code /cam nightvision}, {@code night-vision.player-choice}. */
    private boolean nightVisionPlayerChoice;

    /** The sounds of {@code sounds}, {@link CamSound#OFF} for one that is switched off. */
    private CamSound startSound;
    private CamSound endSound;
    private CamSound endBodySound;
    private CamSound endForcedSound;
    private CamSound borderSound;
    private CamSound backSound;
    private CamSound endWarningSound;
    private CamSound bodyAlarmSound;
    private CamSound startDelaySound;
    private CamSound nudgeSound;
    private CamSound signalLostSound;
    private CamSound signalBackSound;
    private CamSound spotSound;
    private CamSound tourInviteSound;
    private CamSound tourReachSound;

    /** Whether camera mode leaves a file to recover from a crash, {@code crash-protection.enabled}. */
    private boolean crashProtectionEnabled;

    /** Who may use {@code /cam list}, {@code cam-list.access}. */
    private ListAccess camListAccess;
    /** Whether the list offers to go to a camera or a body, {@code cam-list.teleport}. */
    private boolean camListTeleport;
    /** Whether the list offers to end a camera mode, {@code cam-list.stop}. */
    private boolean camListStop;

    /** Whether {@code /cam stop} ends the camera mode of others, {@code stop-command.enabled}. */
    private boolean stopCommandEnabled;

    /** Whether {@code /cam ban} keeps players out of camera mode, {@code cam-ban.enabled}. */
    private boolean camBanEnabled;

    /** Whether camera mode ends after a while without moving, {@code afk-end.enabled}. */
    private boolean afkEndEnabled;
    /** How long that while is, in seconds, {@code afk-end.seconds}. */
    private int afkEndSeconds;
    /** After how much of that while the warning comes, in percent, 0 for none, {@code afk-end.warning-percent}. */
    private int afkWarningPercent;

    /** Whether {@code /cam back} takes the camera back to its body, {@code back-command.enabled}. */
    private boolean backCommandEnabled;

    /** Whether {@code /cam spot} keeps spots to jump to, {@code viewpoints.enabled}. */
    private boolean viewpointsEnabled;
    /** How many spots each player may keep, {@code viewpoints.max-per-player}. */
    private int viewpointsMax;
    /** Where the spots are kept, {@code viewpoints.storage}. */
    private StorageMode viewpointsStorage;
    /** Whether a player may share his spots with others, {@code viewpoints.sharing}. */
    private boolean viewpointsSharing;
    /** Whether {@code /cam visit} takes the camera to a spot shared with him, {@code visitor-camera.enabled}. */
    private boolean visitorCameraEnabled;
    /** Whether {@code /cam follow} lets a camera player show others round, {@code follow.enabled}. */
    private boolean tourEnabled;
    /** How close a camera has to be to be invited, {@code follow.radius}. */
    private double tourRadius;
    /** How long a guest may be out of reach before his tour ends, {@code follow.out-of-reach-seconds}. */
    private int tourOutOfReachSeconds;
    /** Whether a guest sees through the eyes of his guide, {@code follow.guide-view}. */
    private boolean tourGuideView;
    /** How close a player out of camera mode has to be to the guide to come along, {@code follow.start-distance}. */
    private double tourStartDistance;
    /** How many guests one tour takes at once, {@code follow.max-guests}; 0 for no limit. */
    private int tourMaxGuests;
    /** How long an invitation holds, {@code follow.invitation-seconds}. */
    private int tourInvitationSeconds;
    /** How long before a guide may invite the same player again, {@code follow.invite-cooldown-seconds}. */
    private int tourInviteCooldownSeconds;
    /** Whether a player out of camera mode may show others round as well, {@code follow.lead-without-cam}. */
    private boolean tourLeadWithoutCam;
    /**
     * Whether the names of a tour take the place of the name tag of a guide
     * out of camera mode, {@code follow.guide-name: replace}, rather than
     * standing over it, {@code above}.
     */
    private boolean tourGuideNameReplaces;
    /** Whether the guests of a guide out of camera mode see his hearts as theirs, {@code follow.mirror-health}. */
    private boolean tourMirrorHealth;
    /** Whether they see the item in his hand in theirs, {@code follow.mirror-main-hand}. */
    private boolean tourMirrorMainHand;
    /** Whether they see the item in his off hand in theirs, {@code follow.mirror-off-hand}. */
    private boolean tourMirrorOffHand;
    /** Whether the owner of a spot hears how often it was looked at and liked, {@code viewpoints.guestbook}. */
    private boolean viewpointsGuestbook;
    /** How often the guestbook tells the owners online what came, {@code viewpoints.guestbook-interval-minutes}. */
    private int guestbookIntervalMinutes;
    /** How close a player out of camera mode has to be to a spot to jump there, {@code viewpoints.start-distance}. */
    private double viewpointsStartDistance;
    /** How long the same player at the same spot is no new view, {@code viewpoints.views-cooldown-minutes}. */
    private int viewsCooldownMinutes;

    /** Whether {@code /cam resume} goes on where the last flight ended, {@code resume-command.enabled}. */
    private boolean resumeEnabled;
    /** Where the end of the last flight is kept, {@code resume-command.storage}. */
    private StorageMode resumeStorage;

    /** Where the settings players pick for themselves are kept, {@code player-settings.storage}. */
    private StorageMode playerSettingsStorage;
    /** Whether the speed he last flew at is kept, {@code player-settings.flight-speed}. */
    private boolean playerSettingsFlightSpeed;
    /** Whether his time of {@code /cam time} is kept, {@code player-settings.time}. */
    private boolean playerSettingsTime;
    /** Whether his weather of {@code /cam weather} is kept, {@code player-settings.weather}. */
    private boolean playerSettingsWeather;

    /** Whether players far away are hidden from a camera player, {@code anti-scouting.enabled}. */
    private boolean antiScoutingEnabled;
    /** How far he still sees the others, in blocks, {@code anti-scouting.radius}. */
    private double antiScoutingRadius;
    /** Where that is measured from, {@code anti-scouting.measured-from}. */
    private ScoutAnchor antiScoutingFrom;

    /** How the players get at back, spot, photo and the rest, {@code menu.access}. */
    private MenuAccess menuAccess;
    /** How admins get at list, stop, ban, trail and the rest, {@code menu.admin-access}. */
    private MenuAccess adminAccess;
    /** What each button of the main page of the menu shows, {@code menu.buttons.<button>.item}. */
    private final Map<MenuButton, MenuIcon> menuIcons = new EnumMap<>(MenuButton.class);
    /** Where it stands, {@code menu.buttons.<button>.slot}. */
    private final Map<MenuButton, Integer> menuSlots = new EnumMap<>(MenuButton.class);
    /** The same for the admin page, {@code menu.admin-buttons}. */
    private final Map<AdminButton, MenuIcon> adminIcons = new EnumMap<>(AdminButton.class);
    /**
     * How many days, hours or minutes a click with shift adds to a ban on its
     * page or takes away, {@code menu.ban-shift-step}.
     */
    private int banShiftStep;
    private final Map<AdminButton, Integer> adminSlots = new EnumMap<>(AdminButton.class);

    /** Whether {@code /cam photo} is there, {@code photo-mode.enabled}. */
    private boolean photoModeEnabled;
    /** Whether it hides the other players, {@code photo-mode.hide-players}. */
    private boolean photoHidePlayers;
    /** Whether it hides the bodies, {@code photo-mode.hide-bodies}. */
    private boolean photoHideBodies;

    /** Whether {@code /cam time} gives the camera player a time of his own, {@code cam-time.enabled}. */
    private boolean camTimeEnabled;
    /** Whether {@code /cam weather} gives him a weather of his own, {@code cam-weather.enabled}. */
    private boolean camWeatherEnabled;

    /** Whether the player waits before camera mode starts, {@code start-delay.enabled}. */
    private boolean startDelayEnabled;
    /** How long, in seconds, {@code start-delay.seconds}. */
    private int startDelaySeconds;

    /** Whether flights are recorded for {@code /cam trail}, {@code cam-trail.enabled}. */
    private boolean trailEnabled;
    /** How long {@code /cam trail} shows a flight, in seconds, {@code cam-trail.show-seconds}. */
    private int trailShowSeconds;
    /** How many of his last flights are kept of each player, {@code cam-trail.flights}. */
    private int trailFlights;

    /** Whether the end of a time limit or a battery is announced, {@code end-warning.enabled}. */
    private boolean endWarningEnabled;
    /** At how many seconds left it is announced, the most first, {@code end-warning.seconds}. */
    private List<Integer> endWarningSeconds = List.of();

    /** Whether a mob after the body sets off the alarm, {@code body-alarm.mobs}. */
    private boolean bodyAlarmMobs;
    /** Whether a player close to the body sets it off, {@code body-alarm.players}. */
    private boolean bodyAlarmPlayers;
    /** How close a player has to come for that, in blocks, {@code body-alarm.player-radius}. */
    private double bodyAlarmPlayerRadius;
    /** Seconds before the next alarm, {@code body-alarm.cooldown}. */
    private int bodyAlarmCooldown;

    /** Whether a click on the body nudges its camera player, {@code nudge.enabled}. */
    private boolean nudgeEnabled;
    /** Seconds before the same camera player can be nudged again, {@code nudge.cooldown}. */
    private int nudgeCooldown;

    /** Blocks of range each block in the way takes off the signal, {@code signal.block-cost} and {@code -costs}. */
    private SignalCosts signalCosts;
    /**
     * From how many percent each level of the signal display counts,
     * {@code signal.levels}: excellent, good, medium and weak, going down.
     */
    private double[] signalLevels;
    /** What happens once the signal is lost, {@code signal.lost}. */
    private SignalLoss signalLoss;
    /** How long the camera has to fly back into the signal, in seconds, {@code signal.lost-seconds}. */
    private int signalLostSeconds;

    // Damage transfer settings
    private DamageMode damageMode;
    /** How hard the transferred hit wears down the player's armour. */
    private ArmorDamageMode armorDamageMode;
    /** Durability points per hit in the armour mode "custom". */
    private int customArmorDamage;
    /** Whether the Unbreaking enchantment counts against that wear. */
    private boolean respectUnbreaking;
    /** Whether the player's armour still takes its share off the damage. */
    private boolean damageCountsArmor;
    /** Whether the transferred hit pushes the player back. */
    private boolean mirrorKnockback;
    /** Whether every transferred hit reports its numbers, for measuring. */
    private boolean mirrorDebug;
    private double customDamageHearts;

    private boolean cameraHeadEnabled;
    /** What holds camera mode to a time, {@code flight-limit}. */
    private FlightLimit flightLimit;
    // Time limit and cooldown settings
    private boolean cooldownsEnabled;
    private int durationSeconds;
    private int cooldownSeconds;
    private boolean showBossbar;
    /** Whether the cooldown shows as a bossbar until it is over, {@code time-limit.show-bossbar-cooldown}. */
    private boolean cooldownBossbar;
    private BarColor bossbarColor;
    /** The notches of both bossbars of the time limit, {@code time-limit.bossbar-notches}. */
    private BarStyle bossbarStyle;
    /** Where the cooldowns are kept, {@code time-limit.storage}. */
    private StorageMode cooldownStorage;

    // Camera safety settings
    private boolean camSafetyEnabled;
    private int camSafetyDelay;
    /** Whether a hit on another player makes him wait as well, {@code cam-safety.after-attack.players}. */
    private boolean camSafetyAfterPlayers;
    /** The same for a hit on a hostile mob, {@code cam-safety.after-attack.mobs}. */
    private boolean camSafetyAfterMobs;
    /** The same for a hit on anything friendly, {@code cam-safety.after-attack.friendly}. */
    private boolean camSafetyAfterFriendly;
    /** Whether another player close by keeps camera mode from starting, {@code cam-safety.nearby-players}. */
    private boolean camSafetyNearbyPlayers;
    /** How close he has to be for that, in blocks, {@code cam-safety.nearby-radius}. */
    private double camSafetyNearbyRadius;

    /** Whether the key that swaps the hands ends camera mode, {@code exit-key.enabled}. */
    private boolean exitKeyEnabled;

    /** A full battery, in seconds of flight. */
    private int batteryCapacity;
    /** Seconds of flight that come back for every second outside camera mode. */
    private double batteryRecharge;
    /** The charge a start needs at least, in seconds of flight. */
    private int batteryMinStart;
    /** Whether the battery charges while the player is not on the server. */
    private boolean batteryRechargeOffline;
    private boolean batteryBossbar;
    /** Whether the bossbar stays after camera mode until the battery is full, {@code show-bossbar-charging}. */
    private boolean batteryBossbarCharging;
    private BarColor batteryBossbarColor;
    /** The notches of both bossbars of the battery, {@code battery.bossbar-notches}. */
    private BarStyle batteryBossbarStyle;
    /** Where the charge is kept, {@code battery.storage}. */
    private StorageMode batteryStorage;

    /** Whether a start costs something, the section {@code start-cost}. */
    private boolean startCostEnabled;
    /** Experience a start costs, in levels or points. */
    private int startCostXp;
    /** Whether {@link #startCostXp} counts points rather than levels, {@code start-cost.xp-type}. */
    private boolean startCostXpPoints;
    /** Money a start costs, through Vault. */
    private double startCostMoney;
    /** Items a start costs, and how many of each, in the order of the file. */
    private final Map<Material, Integer> startCostItems = new LinkedHashMap<>();

    /** Whether camera mode is counted for {@code /cam stats}, {@code statistics.enabled}. */
    private boolean statsEnabled;
    /** Who may look at the statistics of somebody else, {@code statistics.view-others}. */
    private ListAccess statsViewOthers;

    /** Whether start and end of camera mode are written down, the section {@code cam-log}. */
    private boolean camLogEnabled;
    /** Whether the same lines go into the console as well. */
    private boolean camLogConsole;
    /** How many days of log files are kept, 0 for all of them. */
    private int camLogKeepDays;

    public CamSettings(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.camAreaRules = new CamAreaRules(messages);
    }

    /**
     * Reads every value out of the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public void load(ConfigReader config) {
        maxDistanceEnabled = config.getBoolean("camera-mode.max-distance-enabled", true);
        maxDistance = config.getDouble("camera-mode.max-distance", 100.0, 0.0);
        distanceMode = config.getChoice("camera-mode.max-distance-mode", "fixed", "fixed", "signal")
                .equalsIgnoreCase("signal") ? DistanceMode.SIGNAL : DistanceMode.FIXED;
        readSignal(config);
        distanceWarningCooldown = config.getInt("camera-mode.distance-warning-cooldown", 3, 0);
        String border = config.getChoice("camera-mode.border-mode", "barrier", "barrier", "push-back", "false")
                .toLowerCase(Locale.ROOT);
        borderMode = switch (border) {
            case "push-back" -> BorderMode.PUSH_BACK;
            case "false" -> BorderMode.OFF;
            default -> BorderMode.BARRIER;
        };
        borderBlock = resolveBlock(config, "camera-mode.border-block", "barrier");
        mediumBorderBlocks.clear();
        readMediumBorderBlock(config, FlightMedium.WATER, "camera-mode.border-block-water", "blue_stained_glass");
        readMediumBorderBlock(config, FlightMedium.LAVA, "camera-mode.border-block-lava", "magma_block");
        readMediumBorderBlock(config, FlightMedium.POWDER_SNOW, "camera-mode.border-block-powder-snow",
                "snow_block");
        // No further than the server sends the world to a client, sixteen
        // blocks for each chunk of its view-distance: the client throws away
        // every block beyond that. A world with a smaller view-distance of
        // its own cuts it back further, see CamBorderWall.
        borderRadius = config.getDouble("camera-mode.border-radius", 5.0, 0.0,
                plugin.getServer().getViewDistance() * 16.0);
        // A wall that nobody runs into holds nobody back: made of something
        // that can be walked through, or not built anywhere around the player.
        // Stopping the camera some other way would say the opposite of what
        // the file says, so there is simply no border then, as with "false".
        bordersHold = borderMode == BorderMode.PUSH_BACK
                || (borderMode == BorderMode.BARRIER && borderRadius > 0.0
                        && borderBlock.getMaterial().isSolid());
        String visibility = config.getChoice("camera-mode.player_visibility_mode", "cam", "cam", "true", "false")
                .toLowerCase();
        playerVisibilityMode = switch (visibility) {
            case "true" -> VisibilityMode.ALL;
            case "false" -> VisibilityMode.NONE;
            default -> VisibilityMode.CAM;
        };
        allowInvisibilityPotion = config.getBoolean("camera-mode.allow_invisibility_potion", true);
        String glow = config.getChoice("camera-mode.glowing-outline", "true", "true", "false", "sight")
                .toLowerCase();
        glowMode = switch (glow) {
            case "false" -> GlowMode.OFF;
            case "sight" -> GlowMode.SIGHT;
            default -> GlowMode.ALWAYS;
        };
        playerNameVisible = config.getBoolean("camera-mode.name-visible", true);
        playerNameMode = resolveNameMode(config, config.getInt("camera-mode.name-mode", NameMode.FOLLOW.getId()));
        playerNameStyle = NameStyle.read(config, "camera-mode.name", ChatColor.WHITE);
        allowLavaFlight = config.getBoolean("camera-mode.allow_lava_flight", true);
        allowWaterFlight = config.getBoolean("camera-mode.allow_water_flight", true);
        allowPowderSnowFlight = config.getBoolean("camera-mode.allow_powder_snow_flight", true);
        String camMode = config.getChoice("camera-mode.gamemode",
                "adventure", "adventure", "survival", "creative", "keep").toLowerCase();
        camGameMode = switch (camMode) {
            case "survival" -> CamGameMode.SURVIVAL;
            case "creative" -> CamGameMode.CREATIVE;
            case "keep" -> CamGameMode.KEEP;
            default -> CamGameMode.ADVENTURE;
        };
        mobsLookAtPlayer = config.getBoolean("camera-mode.mobs-look-at-player", false);
        String effects = config.getChoice("camera-mode.start-with-effects",
                "positive", "true", "false", "positive").toLowerCase();
        startWithEffects = switch (effects) {
            case "true" -> EffectStart.ANY;
            case "false" -> EffectStart.NONE;
            default -> EffectStart.POSITIVE;
        };
        cameraHeadEnabled = config.getBoolean("camera-head.enabled", false);
        bodyType = resolveBodyType(config, config.getString("body.type", BodyType.ARMOR_STAND.getConfigName()));
        bodyNameVisible = config.getBoolean("body.name-visible", true);
        bodyHealthVisible = config.getBoolean("body.health-visible", false);
        bodyHealthAbove = config.getChoice("body.health-position", "above", "above", "below")
                .equalsIgnoreCase("above");
        bodyHealthShowMissing = config.getBoolean("body.health-show-missing", false);
        bodyHealthShowAbsorption = config.getBoolean("body.health-show-absorption", true);
        bodyHealthRowsUpward = config.getBoolean("body.health-rows-upward", true);
        bodyNameStyle = NameStyle.read(config, "body.name", ChatColor.YELLOW);
        bodyVisible = config.getBoolean("body.visible", true);
        bodyArmorVisible = config.getBoolean("body.armor-visible", true);
        movementSensitivity = resolveMovementSensitivity(config,
                config.getInt("body.movement-sensitivity", MovementSensitivity.NORMAL.getId()));
        double moveThreshold = config.getDouble("body.move-threshold", 0.05, MIN_MOVE_THRESHOLD);
        moveThresholdSquared = moveThreshold * moveThreshold;
        String mobTarget = config.getChoice("body.mob-target", "false", "vanilla", "custom", "false")
                .toLowerCase(Locale.ROOT);
        mobTargetMode = switch (mobTarget) {
            case "vanilla" -> MobTargetMode.VANILLA;
            case "custom" -> MobTargetMode.CUSTOM;
            default -> MobTargetMode.OFF;
        };
        mobTargetRadius = config.getDouble("body.mob-target-radius", 16.0, 0.0,
                MobTargeting.MAX_MOB_TARGET_RANGE);
        mobTargetHeads = config.getBoolean("body.mob-target-heads", true);
        particlesEnabled = config.getBoolean("camera-particles.enabled", true);
        particleType = resolveParticle(config, "camera-particles.particle", Particle.SOUL_FIRE_FLAME);
        particleHeight = config.getDouble("camera-particles.height", 1.0);
        particlesPerTick = config.getDouble("camera-particles.particles-per-tick", 1.0, 0.0,
                MAX_PARTICLES_PER_TICK);
        particleSpread = config.getDouble("camera-particles.spread", 0.0, 0.0);
        actionBarEnabled = config.getBoolean("action-bar.enabled", true);
        actionBarOffDuration = config.getInt("action-bar.off-duration", 10, 0);
        actionBarOnDuration = config.getInt("action-bar.on-duration", 60, 0);
        String display = config.getChoice("action-bar.display", "distance", "distance", "text", "false")
                .toLowerCase(Locale.ROOT);
        actionBarDisplay = switch (display) {
            case "text" -> ActionBarDisplay.TEXT;
            case "false" -> ActionBarDisplay.START;
            default -> ActionBarDisplay.DISTANCE;
        };
        readFlightSpeed(config);
        String nightVision = config.getChoice("night-vision.mode", "dark", "true", "false", "dark")
                .toLowerCase(Locale.ROOT);
        nightVisionMode = switch (nightVision) {
            case "true" -> NightVisionMode.ALWAYS;
            case "false" -> NightVisionMode.OFF;
            default -> NightVisionMode.DARK;
        };
        nightVisionLightLevel = config.getInt("night-vision.light-level", 7, 0, MAX_LIGHT_LEVEL);
        nightVisionPlayerChoice = config.getBoolean("night-vision.player-choice", true);
        String allowedSounds = messages.configMessage("config-allowed-sounds");
        startSound = CamSound.read(config, "sounds.start", allowedSounds, "block.beacon.activate", 1.0);
        endSound = CamSound.read(config, "sounds.end", allowedSounds, "block.beacon.deactivate", 1.0);
        endBodySound = CamSound.read(config, "sounds.end-body", allowedSounds,
                "entity.illusioner.mirror_move", 1.0);
        endForcedSound = CamSound.read(config, "sounds.end-forced", allowedSounds,
                "block.respawn_anchor.deplete", 1.0);
        borderSound = CamSound.read(config, "sounds.border", allowedSounds, "block.note_block.bass", 0.5);
        backSound = CamSound.read(config, "sounds.back", allowedSounds, "entity.enderman.teleport", 1.0);
        endWarningSound = CamSound.read(config, "sounds.end-warning", allowedSounds,
                "item.lodestone_compass.lock", 1.0);
        bodyAlarmSound = CamSound.read(config, "sounds.body-alarm", allowedSounds,
                "entity.warden.nearby_close", 1.0);
        startDelaySound = CamSound.read(config, "sounds.start-delay", allowedSounds, "block.note_block.hat", 1.0);
        nudgeSound = CamSound.read(config, "sounds.nudge", allowedSounds, "block.note_block.chime", 1.0);
        signalLostSound = CamSound.read(config, "sounds.signal-lost", allowedSounds,
                "block.conduit.deactivate", 1.0);
        signalBackSound = CamSound.read(config, "sounds.signal-back", allowedSounds,
                "block.conduit.activate", 1.0);
        spotSound = CamSound.read(config, "sounds.spot", allowedSounds, "item.chorus_fruit.teleport", 1.0);
        // Called tour-invite and tour-reach before /cam tour became /cam follow.
        tourInviteSound = CamSound.read(config, config.renamed("sounds.follow-invite", "sounds.tour-invite"),
                allowedSounds, "block.note_block.bell", 1.0);
        tourReachSound = CamSound.read(config, config.renamed("sounds.follow-reach", "sounds.tour-reach"),
                allowedSounds, "block.conduit.deactivate", 1.0);
        crashProtectionEnabled = config.getBoolean("crash-protection.enabled", true);
        camListAccess = readAccess(config, "cam-list.access");
        camListTeleport = config.getBoolean("cam-list.teleport", true);
        camListStop = config.getBoolean("cam-list.stop", true);
        stopCommandEnabled = config.getBoolean("stop-command.enabled", true);
        camBanEnabled = config.getBoolean("cam-ban.enabled", true);
        afkEndEnabled = config.getBoolean("afk-end.enabled", false);
        afkEndSeconds = config.getInt("afk-end.seconds", 300, MIN_AFK_SECONDS);
        afkWarningPercent = config.getInt("afk-end.warning-percent", 80, 0, 99);
        backCommandEnabled = config.getBoolean("back-command.enabled", true);
        viewpointsEnabled = config.getBoolean("viewpoints.enabled", true);
        viewpointsMax = config.getInt("viewpoints.max-per-player", 10, 0, MAX_VIEWPOINTS);
        viewpointsStorage = readStorage(config, "viewpoints.storage");
        viewpointsSharing = config.getBoolean("viewpoints.sharing", true);
        visitorCameraEnabled = config.getBoolean("visitor-camera.enabled", true);
        // The section of /cam follow, which was called tour before: an older
        // file is read as it was written.
        String follow = config.renamed(FOLLOW_SECTION, OLD_FOLLOW_SECTION);
        tourEnabled = config.getBoolean(follow + ".enabled", true);
        tourRadius = config.getDouble(follow + ".radius", 16.0, 1.0, MAX_TOUR_RADIUS);
        tourOutOfReachSeconds = config.getInt(follow + ".out-of-reach-seconds", 5, 1,
                MAX_TOUR_OUT_OF_REACH_SECONDS);
        tourGuideView = config.getBoolean(follow + ".guide-view", true);
        tourStartDistance = config.getDouble(follow + ".start-distance", 16.0, 0.0, MAX_START_DISTANCE);
        tourMaxGuests = config.getInt(follow + ".max-guests", 5, 0, MAX_TOUR_GUESTS);
        tourInvitationSeconds = config.getInt(follow + ".invitation-seconds", 60, MIN_TOUR_INVITATION_SECONDS,
                MAX_TOUR_INVITATION_SECONDS);
        tourInviteCooldownSeconds = config.getInt(follow + ".invite-cooldown-seconds", 60, 0,
                MAX_TOUR_INVITATION_SECONDS);
        tourLeadWithoutCam = config.getBoolean(follow + ".lead-without-cam", true);
        tourGuideNameReplaces = config.getChoice(follow + ".guide-name", "replace", "replace", "above")
                .equalsIgnoreCase("replace");
        tourMirrorHealth = config.getBoolean(follow + ".mirror-health", true);
        tourMirrorMainHand = config.getBoolean(follow + ".mirror-main-hand", true);
        tourMirrorOffHand = config.getBoolean(follow + ".mirror-off-hand", true);
        viewpointsGuestbook = config.getBoolean("viewpoints.guestbook", true);
        guestbookIntervalMinutes = config.getInt("viewpoints.guestbook-interval-minutes", 10, 1,
                MAX_GUESTBOOK_INTERVAL_MINUTES);
        viewpointsStartDistance = config.getDouble("viewpoints.start-distance", 16.0, 0.0, MAX_START_DISTANCE);
        viewsCooldownMinutes = config.getInt("viewpoints.views-cooldown-minutes", 10, 0, MAX_VIEWS_COOLDOWN_MINUTES);
        resumeEnabled = config.getBoolean("resume-command.enabled", true);
        resumeStorage = readStorage(config, "resume-command.storage");
        playerSettingsStorage = readStorage(config, "player-settings.storage");
        playerSettingsFlightSpeed = config.getBoolean("player-settings.flight-speed", true);
        playerSettingsTime = config.getBoolean("player-settings.time", false);
        playerSettingsWeather = config.getBoolean("player-settings.weather", false);
        antiScoutingEnabled = config.getBoolean("anti-scouting.enabled", false);
        antiScoutingRadius = config.getDouble("anti-scouting.radius", 32.0, 0.0, MAX_SCOUT_RADIUS);
        antiScoutingFrom = config.getChoice("anti-scouting.measured-from", "body", "body", "camera")
                .equalsIgnoreCase("camera") ? ScoutAnchor.CAMERA : ScoutAnchor.BODY;
        menuAccess = readMenuAccess(config, "menu.access");
        adminAccess = readMenuAccess(config, "menu.admin-access");
        readButtons(config, "menu.buttons", MenuButton.values(), menuIcons, menuSlots);
        readButtons(config, "menu.admin-buttons", AdminButton.values(), adminIcons, adminSlots);
        banShiftStep = config.getInt("menu.ban-shift-step", 10, 1, MAX_BAN_SHIFT_STEP);
        photoModeEnabled = config.getBoolean("photo-mode.enabled", true);
        photoHidePlayers = config.getBoolean("photo-mode.hide-players", true);
        photoHideBodies = config.getBoolean("photo-mode.hide-bodies", true);
        camTimeEnabled = config.getBoolean("cam-time.enabled", true);
        camWeatherEnabled = config.getBoolean("cam-weather.enabled", true);
        startDelayEnabled = config.getBoolean("start-delay.enabled", false);
        startDelaySeconds = config.getInt("start-delay.seconds", 3, 1, MAX_START_DELAY_SECONDS);
        trailEnabled = config.getBoolean("cam-trail.enabled", true);
        trailShowSeconds = config.getInt("cam-trail.show-seconds", 30, 1, MAX_TRAIL_SHOW_SECONDS);
        trailFlights = config.getInt("cam-trail.flights", 5, 1, MAX_TRAIL_FLIGHTS);
        endWarningEnabled = config.getBoolean("end-warning.enabled", true);
        endWarningSeconds = readSeconds(config, "end-warning.seconds", List.of(30, 10));
        bodyAlarmMobs = config.getBoolean("body-alarm.mobs", true);
        bodyAlarmPlayers = config.getBoolean("body-alarm.players", false);
        bodyAlarmPlayerRadius = config.getDouble("body-alarm.player-radius", 10.0, 0.0,
                MobTargeting.MAX_MOB_TARGET_RANGE);
        bodyAlarmCooldown = config.getInt("body-alarm.cooldown", 5, 0);
        nudgeEnabled = config.getBoolean("nudge.enabled", true);
        nudgeCooldown = config.getInt("nudge.cooldown", 5, MIN_NUDGE_COOLDOWN);
        String limit = config.getChoice("flight-limit", "false", "false", "time-limit", "battery")
                .toLowerCase(Locale.ROOT);
        flightLimit = switch (limit) {
            case "time-limit" -> FlightLimit.TIME_LIMIT;
            case "battery" -> FlightLimit.BATTERY;
            default -> FlightLimit.OFF;
        };
        cooldownsEnabled = config.getBoolean("time-limit.cooldowns-enabled", false);
        durationSeconds = config.getInt("time-limit.duration-seconds", 300, 1);
        cooldownSeconds = config.getInt("time-limit.cooldown-seconds", 120, 0);
        showBossbar = config.getBoolean("time-limit.show-bossbar", true);
        cooldownBossbar = config.getBoolean("time-limit.show-bossbar-cooldown", true);
        bossbarColor = config.getEnum("time-limit.bossbar-color", BarColor.class, BarColor.BLUE);
        bossbarStyle = readNotches(config, "time-limit.bossbar-notches", 0);
        cooldownStorage = readStorage(config, "time-limit.storage");
        camSafetyEnabled = config.getBoolean("cam-safety.enabled", true);
        camSafetyDelay = config.getInt("cam-safety.delay", 5, 0);
        camSafetyAfterPlayers = config.getBoolean("cam-safety.after-attack.players", false);
        camSafetyAfterMobs = config.getBoolean("cam-safety.after-attack.mobs", false);
        camSafetyAfterFriendly = config.getBoolean("cam-safety.after-attack.friendly", false);
        camSafetyNearbyPlayers = config.getBoolean("cam-safety.nearby-players", false);
        camSafetyNearbyRadius = config.getDouble("cam-safety.nearby-radius", 16.0, 0.0,
                MobTargeting.MAX_MOB_TARGET_RANGE);
        exitKeyEnabled = config.getBoolean("exit-key.enabled", true);
        readBattery(config);
        readStartCost(config);
        statsEnabled = config.getBoolean("statistics.enabled", true);
        statsViewOthers = readAccess(config, "statistics.view-others");
        camLogEnabled = config.getBoolean("cam-log.enabled", false);
        camLogConsole = config.getBoolean("cam-log.console", false);
        camLogKeepDays = config.getInt("cam-log.keep-days", 30, 0);
        camAreaRules.load(config);
        portalRules.load(config);
        commandTeleportEndsCam = config.getBoolean("teleports.command", true);
        pluginTeleportEndsCam = config.getBoolean("teleports.plugin", true);

        mirrorKnockback = config.getBoolean("mirror-damage.knockback", true);
        // Only for measuring, so left out of config.yml on purpose: whoever
        // needs it adds "debug: true" under mirror-damage by hand. That is
        // why it is one of the UNLISTED_KEYS.
        mirrorDebug = config.getBoolean("mirror-damage.debug", false);
        String mode = config.getChoice("mirror-damage.damage-mode", "mirror", "mirror", "custom", "false")
                .toLowerCase(Locale.ROOT);
        damageMode = switch (mode) {
            case "custom" -> DamageMode.CUSTOM;
            case "false" -> DamageMode.OFF;
            default -> DamageMode.MIRROR;
        };
        customDamageHearts = config.getDouble("mirror-damage.custom-damage-hearts", 0.5, 0.0);
        damageCountsArmor = config.getBoolean("mirror-damage.damage-counts-armor", true);
        String armorMode = config.getChoice("mirror-damage.damage-armor-mode", "mirror", "mirror", "custom", "false")
                .toLowerCase(Locale.ROOT);
        armorDamageMode = switch (armorMode) {
            case "custom" -> ArmorDamageMode.CUSTOM;
            case "false" -> ArmorDamageMode.OFF;
            default -> ArmorDamageMode.MIRROR;
        };
        customArmorDamage = config.getInt("mirror-damage.custom-armor-damage", 1, 0);
        respectUnbreaking = config.getBoolean("mirror-damage.respect-unbreaking", true);
    }

    /**
     * Reads the section {@code flight-speed}. The slowest speed is read first,
     * the others are held to it: the fastest is no slower, and the one at the
     * start lies between the two. Their defaults follow along, so that a
     * slowest speed above 3 does not set off a note about a fastest speed that
     * is not even in the file.
     */
    private void readFlightSpeed(ConfigReader config) {
        flightSpeedWheel = config.getBoolean("flight-speed.mouse-wheel", true);
        flightSpeedMin = config.getDouble("flight-speed.min", 0.5, MIN_FLIGHT_SPEED, MAX_FLIGHT_SPEED);
        flightSpeedMax = config.getDouble("flight-speed.max", Math.max(3.0, flightSpeedMin),
                flightSpeedMin, MAX_FLIGHT_SPEED);
        flightSpeedStart = config.getDouble("flight-speed.start",
                Math.min(Math.max(1.0, flightSpeedMin), flightSpeedMax), flightSpeedMin, flightSpeedMax);
        flightSpeedStep = config.getDouble("flight-speed.step", 0.5, 0.05, MAX_FLIGHT_SPEED);
    }

    /**
     * Reads the section {@code signal}, for {@code max-distance-mode: signal}.
     * Read whatever the mode, so that a value that does not fit is reported
     * now and not only once somebody switches the signal on.
     */
    private void readSignal(ConfigReader config) {
        signalCosts = SignalCosts.read(config, messages.configMessage("config-allowed-signal-costs"));
        readSignalLevels(config);
        signalLoss = config.getChoice("signal.lost", "back", "back", "end").equalsIgnoreCase("end")
                ? SignalLoss.END
                : SignalLoss.BACK;
        signalLostSeconds = config.getInt("signal.lost-seconds", 3, 0, MAX_SIGNAL_LOST_SECONDS);
    }

    /**
     * Reads {@code signal.levels}: from how many percent the signal display
     * shows which level. Each has to be lower than the one before; if one is
     * not, they are reported together and all of them keep their defaults.
     */
    private void readSignalLevels(ConfigReader config) {
        double[] levels = new double[SIGNAL_LEVEL_NAMES.length];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = config.getDouble("signal.levels." + SIGNAL_LEVEL_NAMES[i], DEFAULT_SIGNAL_LEVELS[i],
                    0.0, 100.0);
        }
        for (int i = 1; i < levels.length; i++) {
            if (levels[i] >= levels[i - 1]) {
                config.warnNotDescending("signal.levels", signalLevelsText(levels),
                        signalLevelsText(DEFAULT_SIGNAL_LEVELS));
                levels = DEFAULT_SIGNAL_LEVELS.clone();
                break;
            }
        }
        signalLevels = levels;
    }

    /** The levels as the note names them, e.g. {@code excellent 80, good 60, medium 40, weak 20}. */
    private static String signalLevelsText(double[] levels) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            double level = levels[i];
            parts.add(SIGNAL_LEVEL_NAMES[i] + " "
                    + (level == Math.rint(level) ? String.valueOf((long) level) : String.valueOf(level)));
        }
        return String.join(", ", parts);
    }

    /**
     * Reads a list of seconds, such as {@code end-warning.seconds}: whole
     * numbers of at least 1, the most first and each once. An entry that is
     * none is reported and left out.
     */
    private static List<Integer> readSeconds(ConfigReader config, String path, List<Integer> def) {
        List<String> defaults = new ArrayList<>();
        for (Integer seconds : def) {
            defaults.add(String.valueOf(seconds));
        }
        List<Integer> seconds = new ArrayList<>();
        for (String entry : config.getStringList(path, defaults)) {
            int value;
            try {
                value = Integer.parseInt(entry.trim());
            } catch (NumberFormatException ex) {
                value = 0;
            }
            if (value < 1) {
                config.warnUnknownEntry(path, entry);
            } else if (!seconds.contains(value)) {
                seconds.add(value);
            }
        }
        seconds.sort(Comparator.reverseOrder());
        return List.copyOf(seconds);
    }

    /**
     * Reads how many notches a bossbar is divided into, such as
     * {@code time-limit.bossbar-notches}: 0 for none, or 6, 10, 12 or 20 -
     * the ones the game can draw, as {@code /bossbar} has them.
     */
    private static BarStyle readNotches(ConfigReader config, String path, int def) {
        int notches = config.getInt(path, def);
        BarStyle style = notchStyle(notches);
        if (style == null) {
            config.warnUnknownValue(path, notches, "0, 6, 10, 12, 20", String.valueOf(def));
            return notchStyle(def);
        }
        return style;
    }

    /** The bossbar with this many notches, or {@code null} when the game has none. */
    private static BarStyle notchStyle(int notches) {
        return switch (notches) {
            case 0 -> BarStyle.SOLID;
            case 6 -> BarStyle.SEGMENTED_6;
            case 10 -> BarStyle.SEGMENTED_10;
            case 12 -> BarStyle.SEGMENTED_12;
            case 20 -> BarStyle.SEGMENTED_20;
            default -> null;
        };
    }

    /** Reads where something is kept, the values file and memory. */
    private static StorageMode readStorage(ConfigReader config, String path) {
        return config.getChoice(path, "file", "file", "memory").equalsIgnoreCase("memory")
                ? StorageMode.MEMORY
                : StorageMode.FILE;
    }

    /** Reads who may use something, the values admin, all and false. */
    private static ListAccess readAccess(ConfigReader config, String path) {
        String access = config.getChoice(path, "admin", "admin", "all", "false").toLowerCase(Locale.ROOT);
        return switch (access) {
            case "all" -> ListAccess.ALL;
            case "false" -> ListAccess.OFF;
            default -> ListAccess.ADMIN;
        };
    }

    /** Reads how something is got at, the values menu, both, command and false. */
    private static MenuAccess readMenuAccess(ConfigReader config, String path) {
        String access = config.getChoice(path, "menu", "menu", "both", "command", "false").toLowerCase(Locale.ROOT);
        return switch (access) {
            case "both" -> MenuAccess.BOTH;
            case "command" -> MenuAccess.COMMAND;
            case "false" -> MenuAccess.OFF;
            default -> MenuAccess.MENU;
        };
    }

    /**
     * Reads a section of buttons, {@code menu.buttons} or
     * {@code menu.admin-buttons}: the item and the slot of each. A slot that
     * another button of the same page has taken already - other than start
     * and end, which are never there together - is noted, and the button goes
     * to its own slot of out of the box, or the first one free.
     */
    private <B extends MenuSlot> void readButtons(ConfigReader config, String section, B[] buttons,
                                                  Map<B, MenuIcon> icons, Map<B, Integer> slots) {
        icons.clear();
        slots.clear();
        String allowed = messages.configMessage("config-allowed-menu-items");
        for (B button : buttons) {
            String path = button.oldKey() == null ? section + "." + button.key()
                    : config.renamed(section + "." + button.key(), section + "." + button.oldKey());
            String raw = config.getString(path + ".item", button.defaultItem());
            MenuIcon icon = MenuIcon.parse(raw);
            if (icon == null) {
                config.warnUnknownValue(path + ".item", raw, allowed, button.defaultItem());
                icon = MenuIcon.parse(button.defaultItem());
            }
            icons.put(button, icon);
            int slot = config.getInt(path + ".slot", button.defaultSlot(), 0, MenuButton.SLOTS - 1);
            B other = slotTakenBy(slots, button, slot);
            if (other != null) {
                int free = slotTakenBy(slots, button, button.defaultSlot()) == null ? button.defaultSlot() : 0;
                while (slotTakenBy(slots, button, free) != null) {
                    free++;
                }
                config.warnSlotTaken(path + ".slot", slot, section + "." + other.key() + ".slot", free);
                slot = free;
            }
            slots.put(button, slot);
        }
    }

    /** The button that has this slot already and may not share it with this one, or {@code null}. */
    private static <B extends MenuSlot> B slotTakenBy(Map<B, Integer> slots, B button, int slot) {
        for (Map.Entry<B, Integer> entry : slots.entrySet()) {
            if (entry.getValue() == slot && !button.mayShareWith(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Reads the section {@code battery}. The charge a start needs is held to
     * the capacity: more than a full battery would let nobody start at all.
     */
    private void readBattery(ConfigReader config) {
        batteryCapacity = config.getInt("battery.capacity", 300, 1);
        // Without any charging an empty battery would stay empty for good.
        batteryRecharge = config.getDouble("battery.recharge-per-second", 0.5, 0.01);
        batteryMinStart = config.getInt("battery.min-start", Math.min(10, batteryCapacity), 0, batteryCapacity);
        batteryRechargeOffline = config.getBoolean("battery.recharge-offline", true);
        batteryBossbar = config.getBoolean("battery.show-bossbar", true);
        batteryBossbarCharging = config.getBoolean("battery.show-bossbar-charging", true);
        batteryBossbarColor = config.getEnum("battery.bossbar-color", BarColor.class, BarColor.GREEN);
        batteryBossbarStyle = readNotches(config, "battery.bossbar-notches", 10);
        batteryStorage = readStorage(config, "battery.storage");
    }

    /**
     * Reads the section {@code start-cost}. An item the game does not have, or
     * that cannot be carried, is reported and left out of the cost.
     */
    private void readStartCost(ConfigReader config) {
        startCostEnabled = config.getBoolean("start-cost.enabled", false);
        startCostXp = config.getInt("start-cost.xp", 0, 0);
        startCostXpPoints = config.getChoice("start-cost.xp-type", "levels", "levels", "points")
                .equalsIgnoreCase("points");
        startCostMoney = config.getDouble("start-cost.money", 0.0, 0.0);
        startCostItems.clear();
        for (Map.Entry<String, Integer> entry
                : config.getAmounts("start-cost.items", 0, MAX_COST_ITEMS).entrySet()) {
            Material material = parseItem(entry.getKey().trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
            if (material == null) {
                config.warnUnknownEntry("start-cost.items", entry.getKey());
            } else if (entry.getValue() > 0) {
                startCostItems.merge(material, entry.getValue(), Integer::sum);
            }
        }
    }

    /** The item a name stands for, or {@code null} when it is none a player can carry. */
    private static Material parseItem(String name) {
        Material material = name.isEmpty() ? null : Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            return null;
        }
        return material;
    }

    /**
     * Reads one of the blocks of the wall, {@code camera-mode.border-block}
     * and its three companions: the name of a block, with or without
     * {@code minecraft:} in front, capitals and spaces not counting -
     * {@code light_blue_stained_glass} and {@code Light Blue Stained Glass}
     * are the same block. Its states may follow in square brackets, the way
     * the game writes them, e.g. {@code glass_pane[north=true,south=true]}.
     * Anything that is no block falls back to {@code def}.
     *
     * <p>Fences, panes and iron bars given without states are joined on every
     * side. Standing on its own, each of them is only a thin post, and a wall
     * of posts has gaps wide enough to slip through.</p>
     */
    private BlockData resolveBlock(ConfigReader config, String path, String def) {
        String raw = config.getString(path, def);
        BlockData block = parseBlock(raw);
        if (block == null) {
            config.warnUnknownValue(path, raw, messages.configMessage("config-allowed-blocks"), def);
            return parseBlock(def);
        }
        return block;
    }

    /**
     * Reads what the wall shows in place of water, lava or powder snow.
     *
     * <p>A block that can be walked through would leave a hole in the wall
     * right there - and would say nothing about how the border holds, which
     * {@code border-block} alone decides. The wall takes its own block in
     * such a place instead. A barrier stands under water, so with the barrier
     * as {@code border-block} an entry of {@code water} keeps the water as it
     * looks.</p>
     */
    private void readMediumBorderBlock(ConfigReader config, FlightMedium medium, String path, String def) {
        BlockData block = resolveBlock(config, path, def);
        mediumBorderBlocks.put(medium, block.getMaterial().isSolid() ? block : borderBlock.clone());
    }

    /** The block a {@code border-block} entry names, or {@code null} for none. */
    private static BlockData parseBlock(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        int states = text.indexOf('[');
        String name = (states < 0 ? text : text.substring(0, states)).trim().replace(' ', '_');
        Material material = name.isEmpty() ? null : Material.matchMaterial(name);
        if (material == null || !material.isBlock()) {
            return null;
        }
        if (states >= 0) {
            try {
                return material.createBlockData(text.substring(states));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        BlockData block = material.createBlockData();
        if (block instanceof MultipleFacing sides) {
            for (BlockFace face : sides.getAllowedFaces()) {
                sides.setFace(face, true);
            }
        }
        return block;
    }

    /**
     * Reads {@code camera-particles.particle}: the name of a particle, with or
     * without {@code minecraft:} in front, capitals and spaces not counting.
     *
     * <p>Only a particle that needs nothing but its name will do. A dust wants
     * a colour and a block crack a block, and the server turns one away that
     * comes without them - every tick, for every player who could see it.</p>
     */
    private Particle resolveParticle(ConfigReader config, String path, Particle def) {
        String defName = def.name().toLowerCase(Locale.ROOT);
        String raw = config.getString(path, defName);
        Particle particle = parseParticle(raw);
        if (particle == null || particle.getDataType() != Void.class) {
            config.warnUnknownValue(path, raw, messages.configMessage("config-allowed-particles"), defName);
            return def;
        }
        return particle;
    }

    private static Particle parseParticle(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Turns the number configured in {@code camera-mode.name-mode} into a mode
     * and falls back to 1 when the value is unknown.
     */
    private NameMode resolveNameMode(ConfigReader config, int configuredId) {
        NameMode requested = NameMode.fromId(configuredId);
        if (requested == null) {
            config.warnUnknownValue("camera-mode.name-mode", configuredId, "1, 2",
                    String.valueOf(NameMode.FOLLOW.getId()));
            return NameMode.FOLLOW;
        }
        return requested;
    }

    /**
     * Turns the value configured in {@code body.type} into a body type and
     * falls back to the armour stand when the value is unknown.
     */
    private BodyType resolveBodyType(ConfigReader config, String configured) {
        BodyType requested = BodyType.fromConfig(configured);
        if (requested == null) {
            config.warnUnknownValue("body.type", configured, BodyType.configNames(),
                    BodyType.ARMOR_STAND.getConfigName());
            return BodyType.ARMOR_STAND;
        }
        return requested;
    }

    /**
     * Turns the number configured in {@code body.movement-sensitivity} into a
     * level and falls back to 1 when the value is unknown.
     *
     * <p>Level 2 is cut back to level 1 for the visible armour stand of the
     * armorstand body. That is a valid setting in the wrong combination and
     * not a mistake, so it is described in the config file instead of being
     * logged.</p>
     */
    private MovementSensitivity resolveMovementSensitivity(ConfigReader config, int configuredId) {
        MovementSensitivity requested = MovementSensitivity.fromId(configuredId);
        if (requested == null) {
            config.warnUnknownValue("body.movement-sensitivity", configuredId, "0, 1, 2",
                    String.valueOf(MovementSensitivity.NORMAL.getId()));
            requested = MovementSensitivity.NORMAL;
        }
        return requested.forBody(bodyType.isMannequinBody(bodyVisible));
    }

    // ------------------------------------------------------------ camera-mode

    public double getMaxDistance() {
        return maxDistance;
    }

    public int getDistanceWarningCooldown() {
        return distanceWarningCooldown;
    }

    /** A copy of the block the wall is made of, free to be changed by the caller. */
    public BlockData getBorderBlock() {
        return borderBlock.clone();
    }

    /** A copy of the block the wall shows in place of this medium, free to be changed by the caller. */
    public BlockData getBorderBlock(FlightMedium medium) {
        return mediumBorderBlocks.get(medium).clone();
    }

    public double getBorderRadius() {
        return borderRadius;
    }

    /**
     * Whether the wall of {@code border-mode: barrier} is built. Not when it
     * would stop nobody, see {@link #bordersHold}.
     */
    public boolean buildsBorderWall() {
        return borderMode == BorderMode.BARRIER && bordersHold;
    }

    /**
     * Whether {@code max-distance} holds the camera back at a border: switched
     * on, in the mode {@code fixed}, and a border that holds. Under
     * {@code signal} it is the signal that holds it, see {@link #usesSignal()},
     * and there is no border at the distance.
     */
    public boolean limitsDistance() {
        return maxDistanceEnabled && distanceMode == DistanceMode.FIXED && bordersHold;
    }

    /**
     * Whether the camera flies on a radio signal from its body,
     * {@code max-distance-mode: signal} with {@code max-distance} switched on.
     * Whatever {@code border-mode} says: the signal is no border, nothing is
     * run into at its end.
     */
    public boolean usesSignal() {
        return maxDistanceEnabled && distanceMode == DistanceMode.SIGNAL;
    }

    public SignalCosts getSignalCosts() {
        return signalCosts;
    }

    /**
     * From how many percent each level of the signal display counts,
     * {@code signal.levels}: excellent, good, medium and weak, each lower
     * than the one before. Not to be changed.
     */
    public double[] getSignalLevels() {
        return signalLevels;
    }

    public SignalLoss getSignalLoss() {
        return signalLoss;
    }

    public int getSignalLostSeconds() {
        return signalLostSeconds;
    }

    /**
     * Whether {@code cam-area} keeps the camera out of forbidden areas in
     * flight and behind portals: level 2, and a border that holds. The start
     * is not part of this, level 1 and 2 turn it away whatever the border does.
     */
    public boolean keepsOutOfAreas() {
        return camAreaRules.getLevel().blocksFlight() && bordersHold;
    }

    public VisibilityMode getPlayerVisibilityMode() {
        return playerVisibilityMode;
    }

    public boolean allowsInvisibilityPotion() {
        return allowInvisibilityPotion;
    }

    public GlowMode getGlowMode() {
        return glowMode;
    }

    /**
     * Whether a camera player carries the name of {@code camera-mode.name} in
     * place of his own name tag. Not in mode NONE: nobody is meant to see him
     * there, and a name would give away where he is.
     */
    public boolean showsPlayerName() {
        return playerNameVisible && playerVisibilityMode != VisibilityMode.NONE;
    }

    public NameMode getPlayerNameMode() {
        return playerNameMode;
    }

    public NameStyle getPlayerNameStyle() {
        return playerNameStyle;
    }

    public boolean allowsLavaFlight() {
        return allowLavaFlight;
    }

    public boolean allowsWaterFlight() {
        return allowWaterFlight;
    }

    public boolean allowsPowderSnowFlight() {
        return allowPowderSnowFlight;
    }

    public CamGameMode getCamGameMode() {
        return camGameMode;
    }

    public boolean mobsLookAtPlayer() {
        return mobsLookAtPlayer;
    }

    public EffectStart getStartWithEffects() {
        return startWithEffects;
    }

    public boolean isCameraHeadEnabled() {
        return cameraHeadEnabled;
    }

    // ------------------------------------------------------------------- body

    public BodyType getBodyType() {
        return bodyType;
    }

    public boolean isBodyNameVisible() {
        return bodyNameVisible;
    }

    public boolean isBodyHealthVisible() {
        return bodyHealthVisible;
    }

    public boolean isBodyHealthAbove() {
        return bodyHealthAbove;
    }

    public boolean isBodyHealthShowMissing() {
        return bodyHealthShowMissing;
    }

    public boolean isBodyHealthShowAbsorption() {
        return bodyHealthShowAbsorption;
    }

    public boolean isBodyHealthRowsUpward() {
        return bodyHealthRowsUpward;
    }

    public NameStyle getBodyNameStyle() {
        return bodyNameStyle;
    }

    public boolean isBodyVisible() {
        return bodyVisible;
    }

    public boolean isBodyArmorVisible() {
        return bodyArmorVisible;
    }

    public MovementSensitivity getMovementSensitivity() {
        return movementSensitivity;
    }

    public double getMoveThresholdSquared() {
        return moveThresholdSquared;
    }

    public MobTargetMode getMobTargetMode() {
        return mobTargetMode;
    }

    public double getMobTargetRadius() {
        return mobTargetRadius;
    }

    public boolean isMobTargetHeads() {
        return mobTargetHeads;
    }

    // ------------------------------------------------- particles and action bar

    /**
     * Whether a camera player gets particles at all: switched off by
     * {@code camera-particles.enabled: false}, and just as well by
     * {@code particles-per-tick: 0}.
     */
    public boolean areParticlesEnabled() {
        return particlesEnabled && particlesPerTick > 0;
    }

    public Particle getParticleType() {
        return particleType;
    }

    public double getParticleHeight() {
        return particleHeight;
    }

    public double getParticlesPerTick() {
        return particlesPerTick;
    }

    public double getParticleSpread() {
        return particleSpread;
    }

    public boolean isActionBarEnabled() {
        return actionBarEnabled;
    }

    public int getActionBarOffDuration() {
        return actionBarOffDuration;
    }

    public int getActionBarOnDuration() {
        return actionBarOnDuration;
    }

    public ActionBarDisplay getActionBarDisplay() {
        return actionBarDisplay;
    }

    // ------------------------------------------- flight speed and night vision

    public boolean isFlightSpeedWheel() {
        return flightSpeedWheel;
    }

    public double getFlightSpeedStart() {
        return flightSpeedStart;
    }

    public double getFlightSpeedMin() {
        return flightSpeedMin;
    }

    public double getFlightSpeedMax() {
        return flightSpeedMax;
    }

    public double getFlightSpeedStep() {
        return flightSpeedStep;
    }

    public NightVisionMode getNightVisionMode() {
        return nightVisionMode;
    }

    public int getNightVisionLightLevel() {
        return nightVisionLightLevel;
    }

    /** Whether a player may pick his own night vision with {@code /cam nightvision}. */
    public boolean isNightVisionPlayerChoice() {
        return nightVisionPlayerChoice;
    }

    // ------------------------------------------------------- sounds, cam-list

    public CamSound getStartSound() {
        return startSound;
    }

    /**
     * The sound for an end of camera mode: {@code sounds.end},
     * {@code end-body} or {@code end-forced}, and none for an end nobody
     * would hear.
     */
    public CamSound getEndSound(EndReason reason) {
        return switch (reason.getKind()) {
            case SELF -> endSound;
            case BODY -> endBodySound;
            case FORCED -> endForcedSound;
            case SILENT -> CamSound.OFF;
        };
    }

    public CamSound getBorderSound() {
        return borderSound;
    }

    public CamSound getBackSound() {
        return backSound;
    }

    public CamSound getEndWarningSound() {
        return endWarningSound;
    }

    public CamSound getBodyAlarmSound() {
        return bodyAlarmSound;
    }

    public CamSound getStartDelaySound() {
        return startDelaySound;
    }

    public CamSound getNudgeSound() {
        return nudgeSound;
    }

    public CamSound getSignalLostSound() {
        return signalLostSound;
    }

    public CamSound getSignalBackSound() {
        return signalBackSound;
    }

    public CamSound getSpotSound() {
        return spotSound;
    }

    public CamSound getTourInviteSound() {
        return tourInviteSound;
    }

    public CamSound getTourReachSound() {
        return tourReachSound;
    }

    public ListAccess getCamListAccess() {
        return camListAccess;
    }

    public boolean isCamListTeleport() {
        return camListTeleport;
    }

    public boolean isCamListStop() {
        return camListStop;
    }

    // --------------------------------------------- stop-command, cam-ban, afk-end

    public boolean isStopCommandEnabled() {
        return stopCommandEnabled;
    }

    public boolean isCamBanEnabled() {
        return camBanEnabled;
    }

    public boolean isAfkEndEnabled() {
        return afkEndEnabled;
    }

    public int getAfkEndSeconds() {
        return afkEndSeconds;
    }

    public int getAfkWarningPercent() {
        return afkWarningPercent;
    }

    // ---------------------------------------------------------------- back-command

    public boolean isBackCommandEnabled() {
        return backCommandEnabled;
    }

    // ------------------------------------------- viewpoints, photo-mode, cam-time, cam-weather

    public boolean isViewpointsEnabled() {
        return viewpointsEnabled;
    }

    /** How many spots each player may keep, {@code viewpoints.max-per-player}; 0 for no limit. */
    public int getViewpointsMax() {
        return viewpointsMax;
    }

    /** Whether there is a limit to how many spots a player may keep. */
    public boolean limitsViewpoints() {
        return viewpointsMax > 0;
    }

    public StorageMode getViewpointsStorage() {
        return viewpointsStorage;
    }

    /** Whether spots may be shared, which only goes while there are spots at all. */
    public boolean isViewpointsSharing() {
        return viewpointsEnabled && viewpointsSharing;
    }

    /** Whether {@code /cam visit} goes, which only goes while spots may be shared. */
    public boolean isVisitorCameraEnabled() {
        return isViewpointsSharing() && visitorCameraEnabled;
    }

    /** Whether {@code /cam follow} is there. */
    public boolean isTourEnabled() {
        return tourEnabled;
    }

    /** How close, camera to camera, a player has to be to be invited to a tour, in blocks. */
    public double getTourRadius() {
        return tourRadius;
    }

    /** How long the guide may be out of a guest's reach before the tour ends for him, in seconds. */
    public int getTourOutOfReachSeconds() {
        return tourOutOfReachSeconds;
    }

    /** Whether a guest may see through the eyes of his guide, where the server lets it. */
    public boolean isTourGuideView() {
        return tourGuideView;
    }

    /**
     * How close, in blocks, a player out of camera mode has to be to the
     * guide's camera to accept an invitation, which starts camera mode for
     * him; 0 while only who is in camera mode may come along.
     */
    public double getTourStartDistance() {
        return tourStartDistance;
    }

    /** How many guests one tour takes at once; 0 for no limit. */
    public int getTourMaxGuests() {
        return tourMaxGuests;
    }

    /** How long an invitation holds, in seconds. */
    public int getTourInvitationSeconds() {
        return tourInvitationSeconds;
    }

    /** How long after inviting a player a guide may not invite him again, in seconds; 0 at once. */
    public int getTourInviteCooldownSeconds() {
        return tourInviteCooldownSeconds;
    }

    /** Whether a player out of camera mode may show others round as well, while he builds, say. */
    public boolean isTourLeadWithoutCam() {
        return tourLeadWithoutCam;
    }

    /**
     * Whether the names of a tour take the place of the name tag of a guide
     * out of camera mode; otherwise they stand over it.
     */
    public boolean isTourGuideNameReplacing() {
        return tourGuideNameReplaces;
    }

    /** Whether the guests of a guide out of camera mode see his hearts on their screen. */
    public boolean isTourMirrorHealth() {
        return tourMirrorHealth;
    }

    /** Whether they see the item in his hand in theirs. */
    public boolean isTourMirrorMainHand() {
        return tourMirrorMainHand;
    }

    /** Whether they see the item in his off hand in theirs. */
    public boolean isTourMirrorOffHand() {
        return tourMirrorOffHand;
    }

    /**
     * Whether the owner of a spot hears who looked at it and liked it -
     * which only others do, so only while spots may be shared.
     */
    public boolean isGuestbookEnabled() {
        return isViewpointsSharing() && viewpointsGuestbook;
    }

    public int getGuestbookIntervalMinutes() {
        return guestbookIntervalMinutes;
    }

    /**
     * How close, in blocks, a player out of camera mode has to be to a spot
     * to jump there, which starts camera mode for him; 0 while only who is in
     * camera mode may jump.
     */
    public double getViewpointsStartDistance() {
        return viewpointsStartDistance;
    }

    /**
     * How long, in minutes, the same player at the same spot does not count
     * as a view again; 0 while every jump and every visit counts.
     */
    public int getViewsCooldownMinutes() {
        return viewsCooldownMinutes;
    }

    public boolean isResumeEnabled() {
        return resumeEnabled;
    }

    public StorageMode getResumeStorage() {
        return resumeStorage;
    }

    // ------------------------------------------- player-settings, anti-scouting, menu

    public StorageMode getPlayerSettingsStorage() {
        return playerSettingsStorage;
    }

    public boolean isPlayerSettingsFlightSpeed() {
        return playerSettingsFlightSpeed;
    }

    public boolean isPlayerSettingsTime() {
        return playerSettingsTime;
    }

    public boolean isPlayerSettingsWeather() {
        return playerSettingsWeather;
    }

    public boolean isAntiScoutingEnabled() {
        return antiScoutingEnabled;
    }

    public double getAntiScoutingRadius() {
        return antiScoutingRadius;
    }

    public ScoutAnchor getAntiScoutingFrom() {
        return antiScoutingFrom;
    }

    public MenuAccess getMenuAccess() {
        return menuAccess;
    }

    public MenuAccess getAdminAccess() {
        return adminAccess;
    }

    /** How many days, hours or minutes a click with shift adds to a ban in the menu or takes away. */
    public int getBanShiftStep() {
        return banShiftStep;
    }

    /** What a button of the main page of the menu shows. */
    public MenuIcon getMenuIcon(MenuButton button) {
        return menuIcons.get(button);
    }

    /** Where a button of the main page of the menu stands, 0 being the top left. */
    public int getMenuSlot(MenuButton button) {
        return menuSlots.get(button);
    }

    /** What a button of the admin page of the menu shows. */
    public MenuIcon getAdminIcon(AdminButton button) {
        return adminIcons.get(button);
    }

    /** Where a button of the admin page of the menu stands. */
    public int getAdminSlot(AdminButton button) {
        return adminSlots.get(button);
    }

    public boolean isPhotoModeEnabled() {
        return photoModeEnabled;
    }

    public boolean photoHidesPlayers() {
        return photoHidePlayers;
    }

    public boolean photoHidesBodies() {
        return photoHideBodies;
    }

    public boolean isCamTimeEnabled() {
        return camTimeEnabled;
    }

    public boolean isCamWeatherEnabled() {
        return camWeatherEnabled;
    }

    // ------------------------------------------------------ start-delay, cam-trail

    public boolean isStartDelayEnabled() {
        return startDelayEnabled;
    }

    public int getStartDelaySeconds() {
        return startDelaySeconds;
    }

    public boolean isTrailEnabled() {
        return trailEnabled;
    }

    public int getTrailShowSeconds() {
        return trailShowSeconds;
    }

    public int getTrailFlights() {
        return trailFlights;
    }

    // ----------------------------------------------------- end-warning, body-alarm

    public boolean isEndWarningEnabled() {
        return endWarningEnabled;
    }

    /** At how many seconds left the end is announced, the most first. */
    public List<Integer> getEndWarningSeconds() {
        return endWarningSeconds;
    }

    /**
     * Whether a mob after the body sets off the alarm. Only where a mob can
     * be after it at all: in the mode {@code false} of {@code body.mob-target}
     * none is.
     */
    public boolean alarmsForMobs() {
        return bodyAlarmMobs && mobTargetMode.attractsMobs();
    }

    public boolean alarmsForPlayers() {
        return bodyAlarmPlayers && bodyAlarmPlayerRadius > 0.0;
    }

    public double getBodyAlarmPlayerRadius() {
        return bodyAlarmPlayerRadius;
    }

    public int getBodyAlarmCooldown() {
        return bodyAlarmCooldown;
    }

    // ------------------------------------------------------------------- nudge

    public boolean isNudgeEnabled() {
        return nudgeEnabled;
    }

    public int getNudgeCooldown() {
        return nudgeCooldown;
    }

    public boolean isCrashProtectionEnabled() {
        return crashProtectionEnabled;
    }

    // --------------------------------------------------- time limit and cooldown

    /** Whether {@code flight-limit} is {@code time-limit}. */
    public boolean isTimeLimitEnabled() {
        return flightLimit == FlightLimit.TIME_LIMIT;
    }

    public boolean isCooldownsEnabled() {
        return cooldownsEnabled;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean showsBossbar() {
        return showBossbar;
    }

    public BarColor getBossbarColor() {
        return bossbarColor;
    }

    public boolean showsCooldownBossbar() {
        return cooldownBossbar;
    }

    public BarStyle getBossbarStyle() {
        return bossbarStyle;
    }

    public StorageMode getCooldownStorage() {
        return cooldownStorage;
    }

    // --------------------------------------------------------------- cam-safety

    public boolean isCamSafetyEnabled() {
        return camSafetyEnabled;
    }

    public int getCamSafetyDelay() {
        return camSafetyDelay;
    }

    public boolean waitsAfterHittingPlayers() {
        return camSafetyAfterPlayers;
    }

    public boolean waitsAfterHittingMobs() {
        return camSafetyAfterMobs;
    }

    public boolean waitsAfterHittingFriendly() {
        return camSafetyAfterFriendly;
    }

    /**
     * Whether another player close by keeps camera mode from starting: the
     * safety switched on, {@code nearby-players} as well, and a radius anybody
     * can stand inside of.
     */
    public boolean locksNearPlayers() {
        return camSafetyEnabled && camSafetyNearbyPlayers && camSafetyNearbyRadius > 0.0;
    }

    public double getCamSafetyNearbyRadius() {
        return camSafetyNearbyRadius;
    }

    // --------------------------------------------------------------- exit-key

    public boolean isExitKeyEnabled() {
        return exitKeyEnabled;
    }

    // ---------------------------------------------------------------- battery

    /** Whether {@code flight-limit} is {@code battery}. */
    public boolean isBatteryEnabled() {
        return flightLimit == FlightLimit.BATTERY;
    }

    public int getBatteryCapacity() {
        return batteryCapacity;
    }

    public double getBatteryRecharge() {
        return batteryRecharge;
    }

    public int getBatteryMinStart() {
        return batteryMinStart;
    }

    public boolean batteryRechargesOffline() {
        return batteryRechargeOffline;
    }

    public boolean showsBatteryBossbar() {
        return batteryBossbar;
    }

    public boolean showsBatteryBossbarCharging() {
        return batteryBossbarCharging;
    }

    public BarColor getBatteryBossbarColor() {
        return batteryBossbarColor;
    }

    public BarStyle getBatteryBossbarStyle() {
        return batteryBossbarStyle;
    }

    public StorageMode getBatteryStorage() {
        return batteryStorage;
    }

    // ------------------------------------------------------------- start-cost

    /** Whether a start costs anything at all: switched on, and something to pay. */
    public boolean hasStartCost() {
        return startCostEnabled && (startCostXp > 0 || startCostMoney > 0.0 || !startCostItems.isEmpty());
    }

    /** The experience a start costs, in points or levels as {@link #costsXpPoints()} says. */
    public int getStartCostXp() {
        return startCostXp;
    }

    public boolean costsXpPoints() {
        return startCostXpPoints;
    }

    public double getStartCostMoney() {
        return startCostMoney;
    }

    /** The items a start costs and how many of each, not to be changed. */
    public Map<Material, Integer> getStartCostItems() {
        return Collections.unmodifiableMap(startCostItems);
    }

    // ---------------------------------------------------- statistics, cam-log

    public boolean isStatsEnabled() {
        return statsEnabled;
    }

    public ListAccess getStatsViewOthers() {
        return statsViewOthers;
    }

    public boolean isCamLogEnabled() {
        return camLogEnabled;
    }

    public boolean logsCamToConsole() {
        return camLogConsole;
    }

    public int getCamLogKeepDays() {
        return camLogKeepDays;
    }

    // --------------------------------------------------------- cam-area, portals

    public CamAreaRules getCamAreaRules() {
        return camAreaRules;
    }

    public PortalRules getPortalRules() {
        return portalRules;
    }

    /**
     * Whether a teleport of this kind ends camera mode, see
     * {@link de.elia.cameraplugin.teleport.CamTeleportGuard}:
     * {@code teleports.command} for a command such as {@code /tp},
     * {@code teleports.plugin} for another plugin such as one for
     * {@code /tpa}. Portals, ender pearls and the like have rules of their
     * own or cannot be used in camera mode at all.
     */
    public boolean teleportEndsCamMode(TeleportCause cause) {
        return switch (cause) {
            case COMMAND -> commandTeleportEndsCam;
            case PLUGIN -> pluginTeleportEndsCam;
            default -> false;
        };
    }

    // ----------------------------------------------------------- mirror-damage

    public DamageMode getDamageMode() {
        return damageMode;
    }

    public ArmorDamageMode getArmorDamageMode() {
        return armorDamageMode;
    }

    public int getCustomArmorDamage() {
        return customArmorDamage;
    }

    public boolean respectsUnbreaking() {
        return respectUnbreaking;
    }

    public boolean damageCountsArmor() {
        return damageCountsArmor;
    }

    public boolean isMirrorKnockback() {
        return mirrorKnockback;
    }

    public boolean isMirrorDebug() {
        return mirrorDebug;
    }

    public double getCustomDamageHearts() {
        return customDamageHearts;
    }
}
