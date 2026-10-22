package de.elia.cameraplugin.config;

import de.elia.cameraplugin.area.CamAreaRules;
import de.elia.cameraplugin.body.BodyType;
import de.elia.cameraplugin.body.MobTargetMode;
import de.elia.cameraplugin.body.MovementSensitivity;
import de.elia.cameraplugin.display.ActionBarDisplay;
import de.elia.cameraplugin.display.GlowMode;
import de.elia.cameraplugin.display.NameMode;
import de.elia.cameraplugin.list.ListAccess;
import de.elia.cameraplugin.mirrordamage.ArmorDamageMode;
import de.elia.cameraplugin.mirrordamage.DamageMode;
import de.elia.cameraplugin.mob.MobTargeting;
import de.elia.cameraplugin.movement.BorderMode;
import de.elia.cameraplugin.movement.FlightMedium;
import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.portal.PortalRules;
import de.elia.cameraplugin.session.CamGameMode;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.sound.CamSound;
import de.elia.cameraplugin.start.AttackSafety;
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
     * Keys that are read although the config file built into the jar leaves
     * them out on purpose, so that they are not reported as unknown, see
     * {@link ConfigReader#warnUnknownKeys}. Also sections whose keys are names
     * the file chooses itself, such as the items of {@code start-cost.items}:
     * everything below them is checked when it is read instead.
     */
    public static final Set<String> UNLISTED_KEYS = Set.of("mirror-damage.debug", "start-cost.items");

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

    /** The sounds of {@code sounds}, {@link CamSound#OFF} for one that is switched off. */
    private CamSound startSound;
    private CamSound endSound;
    private CamSound endBodySound;
    private CamSound endForcedSound;
    private CamSound borderSound;
    private CamSound backSound;
    private CamSound endWarningSound;
    private CamSound bodyAlarmSound;

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

    /** Whether {@code /cam back} takes the camera back to its body, {@code back-command.enabled}. */
    private boolean backCommandEnabled;

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
    /** Which hits a player hands out make him wait as well, {@code cam-safety.after-attack}. */
    private AttackSafety camSafetyAfterAttack;

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
        crashProtectionEnabled = config.getBoolean("crash-protection.enabled", true);
        camListAccess = readAccess(config, "cam-list.access");
        camListTeleport = config.getBoolean("cam-list.teleport", true);
        camListStop = config.getBoolean("cam-list.stop", true);
        stopCommandEnabled = config.getBoolean("stop-command.enabled", false);
        camBanEnabled = config.getBoolean("cam-ban.enabled", false);
        afkEndEnabled = config.getBoolean("afk-end.enabled", false);
        afkEndSeconds = config.getInt("afk-end.seconds", 300, MIN_AFK_SECONDS);
        backCommandEnabled = config.getBoolean("back-command.enabled", true);
        endWarningEnabled = config.getBoolean("end-warning.enabled", true);
        endWarningSeconds = readSeconds(config, "end-warning.seconds", List.of(30, 10));
        bodyAlarmMobs = config.getBoolean("body-alarm.mobs", true);
        bodyAlarmPlayers = config.getBoolean("body-alarm.players", false);
        bodyAlarmPlayerRadius = config.getDouble("body-alarm.player-radius", 10.0, 0.0,
                MobTargeting.MAX_MOB_TARGET_RANGE);
        bodyAlarmCooldown = config.getInt("body-alarm.cooldown", 5, 0);
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
        String afterAttack = config.getChoice("cam-safety.after-attack", "false", "false", "players", "all")
                .toLowerCase(Locale.ROOT);
        camSafetyAfterAttack = switch (afterAttack) {
            case "players" -> AttackSafety.PLAYERS;
            case "all" -> AttackSafety.ALL;
            default -> AttackSafety.OFF;
        };
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

    /** Whether {@code max-distance} holds the camera back: switched on, and a border that holds. */
    public boolean limitsDistance() {
        return maxDistanceEnabled && bordersHold;
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

    // ------------------------------------------- back-command, end-warning, body-alarm

    public boolean isBackCommandEnabled() {
        return backCommandEnabled;
    }

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

    public AttackSafety getCamSafetyAfterAttack() {
        return camSafetyAfterAttack;
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
