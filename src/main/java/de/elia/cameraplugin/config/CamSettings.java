package de.elia.cameraplugin.config;

import de.elia.cameraplugin.area.CamAreaRules;
import de.elia.cameraplugin.battery.BatterySettings;
import de.elia.cameraplugin.bedrock.BedrockSettings;
import de.elia.cameraplugin.body.BodyAlarmSettings;
import de.elia.cameraplugin.body.BodySettings;
import de.elia.cameraplugin.command.CamCommandFilter;
import de.elia.cameraplugin.command.CommandMode;
import de.elia.cameraplugin.cost.StartCostSettings;
import de.elia.cameraplugin.display.ActionBarSettings;
import de.elia.cameraplugin.display.ParticleSettings;
import de.elia.cameraplugin.list.ListAccess;
import de.elia.cameraplugin.menu.MenuSettings;
import de.elia.cameraplugin.mirrordamage.MirrorDamageSettings;
import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.ore.OreSettings;
import de.elia.cameraplugin.photo.PhotoSettings;
import de.elia.cameraplugin.portal.PortalRules;
import de.elia.cameraplugin.resume.ResumeSettings;
import de.elia.cameraplugin.scout.ScoutAnchor;
import de.elia.cameraplugin.session.CameraModeSettings;
import de.elia.cameraplugin.signal.SignalSettings;
import de.elia.cameraplugin.sound.SoundSettings;
import de.elia.cameraplugin.spawning.SpawnMode;
import de.elia.cameraplugin.speed.FlightSpeedSettings;
import de.elia.cameraplugin.spot.SpotSettings;
import de.elia.cameraplugin.start.CamSafetySettings;
import de.elia.cameraplugin.stats.StatsSettings;
import de.elia.cameraplugin.storage.StorageMode;
import de.elia.cameraplugin.timelimit.FlightLimit;
import de.elia.cameraplugin.timelimit.TimeLimitSettings;
import de.elia.cameraplugin.tour.FollowSettings;
import de.elia.cameraplugin.trail.TrailSettings;
import de.elia.cameraplugin.webmap.WebMapSettings;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every value of the config file the plugin runs on, read in one go by
 * {@link #load(ConfigReader)}.
 */
public final class CamSettings {





    /** The brightest light level there is, {@code night-vision.light-level} going up to it. */
    private static final int MAX_LIGHT_LEVEL = 15;

    /**
     * The shortest time {@code afk-end.seconds} accepts. Anything shorter
     * would take camera mode off a player who only stops to look at
     * something for a moment.
     */
    public static final int MIN_AFK_SECONDS = 10;

    /**
     * The longest wait {@code start-delay.seconds} accepts. Longer than a
     * minute, standing still is no longer a wait but a punishment.
     */
    private static final int MAX_START_DELAY_SECONDS = 60;

    /**
     * The shortest time {@code exit-click-guard.seconds} accepts. The first
     * try of a held button comes in after the player's ping, which is added
     * on top, and up to two ticks more, his own and the server's: 100 ms. A
     * shorter time would let that try through.
     */
    private static final double MIN_EXIT_CLICK_GUARD_SECONDS = 0.15;

    /**
     * The default of {@code exit-click-guard.seconds}: twice the shortest,
     * with room for a try that the server takes up late, when it lags.
     */
    private static final double DEFAULT_EXIT_CLICK_GUARD_SECONDS = 0.3;

    /**
     * The longest time {@code exit-click-guard.seconds} accepts. Longer, it
     * no longer catches the tail of a click but keeps a player who is back at
     * his body from building.
     */
    private static final double MAX_EXIT_CLICK_GUARD_SECONDS = 5.0;












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



    /**
     * The farthest {@code viewpoints.start-distance} and
     * {@code follow.start-distance} reach: as far as a tour invites.
     */
    public static final double MAX_START_DISTANCE = 128.0;





    /** The section of {@code /cam follow}. */
    public static final String FOLLOW_SECTION = "follow";

    /**
     * What the section of {@code /cam follow} was called while it was
     * {@code /cam follow}: an older file is read as it was written, see
     * {@link ConfigReader#renamed}.
     */
    public static final String OLD_FOLLOW_SECTION = "tour";

    /**
     * Keys that are read although the config file built into the jar leaves
     * them out on purpose, so that they are not reported as unknown, see
     * {@link ConfigReader#warnUnknownKeys}. Also sections whose keys are names
     * the file chooses itself, such as the items of {@code start-cost.items}:
     * everything below them is checked when it is read instead. And the old
     * names of what {@code /cam follow} renamed, which an older file still
     * has, and {@code visitor-camera} of {@code /cam visit}, which is gone.
     */
    public static final Set<String> UNLISTED_KEYS = Set.of("mirror-damage.debug", "start-cost.items",
            "signal.block-costs", "hide-ores.blocks", "visitor-camera", OLD_FOLLOW_SECTION, "sounds.tour-invite", "sounds.tour-reach",
            "menu.buttons.tour", "menu.admin-buttons.tours");


    private final JavaPlugin plugin;
    private final Messages messages;

    /** Where camera mode may be started and flown, the section {@code cam-area}. */
    private final CamAreaRules camAreaRules;
    /** Whether a portal lets a camera player through, the section {@code portals}. */
    private final PortalRules portalRules = new PortalRules();
    /** Whether a teleport by a command ends camera mode, {@code teleports.command}. */
    private boolean commandTeleportEndsCam;
    /** Whether a teleport by another plugin ends camera mode, {@code teleports.plugin}. */
    private boolean pluginTeleportEndsCam;
    /** Whether a teleport by an ender pearl ends camera mode, {@code teleports.ender-pearl}. */
    private boolean pearlTeleportEndsCam;
    /** Which commands a camera player may use, {@code cam-commands.mode}. */
    private CommandMode camCommandMode = CommandMode.BLACKLIST;
    /** The commands of {@code cam-commands.list}, each as its words, see {@link CamCommandFilter#words}. */
    private List<List<String>> camCommands = List.of();
    /** What a camera player counts as for the mobs that spawn by themselves, {@code mob-spawning}. */
    private SpawnMode spawnMode;
    /** Whether a camera player earns no advancements, {@code progress-guard.advancements}. */
    private boolean advancementsBlocked;
    /** Whether a camera player's movement counts for no statistic, {@code progress-guard.statistics}. */
    private boolean statisticsBlocked;




    /** When the camera player sees in the dark, {@code night-vision.mode}. */
    private NightVisionMode nightVisionMode;
    /** Up to which light level it counts as dark, {@code night-vision.light-level}. */
    private int nightVisionLightLevel;
    /** Whether each player picks his own with {@code /cam nightvision}, {@code night-vision.player-choice}. */
    private boolean nightVisionPlayerChoice;


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





    /** Where the settings players pick for themselves are kept, {@code player-settings.storage}. */
    private StorageMode playerSettingsStorage;
    /** Whether the speed he last flew at is kept, {@code player-settings.flight-speed}. */
    private boolean playerSettingsFlightSpeed;

    /** Whether players far away are hidden from a camera player, {@code anti-scouting.enabled}. */
    private boolean antiScoutingEnabled;
    /** How far he still sees the others, in blocks, {@code anti-scouting.radius}. */
    private double antiScoutingRadius;
    /** Where that is measured from, {@code anti-scouting.measured-from}. */
    private ScoutAnchor antiScoutingFrom;



    /** Whether {@code /cam time} gives the camera player a time of his own, {@code cam-time.enabled}. */
    private boolean camTimeEnabled;
    /** Whether {@code /cam weather} gives him a weather of his own, {@code cam-weather.enabled}. */
    private boolean camWeatherEnabled;

    /** Whether the player waits before camera mode starts, {@code start-delay.enabled}. */
    private boolean startDelayEnabled;
    /** How long, in seconds, {@code start-delay.seconds}. */
    private int startDelaySeconds;


    /** Whether the end of a time limit or a battery is announced, {@code end-warning.enabled}. */
    private boolean endWarningEnabled;
    /** At how many seconds left it is announced, the most first, {@code end-warning.seconds}. */
    private List<Integer> endWarningSeconds = List.of();


    /** Whether a click on the body nudges its camera player, {@code nudge.enabled}. */
    private boolean nudgeEnabled;
    /** Seconds before the same camera player can be nudged again, {@code nudge.cooldown}. */
    private int nudgeCooldown;



    private boolean cameraHeadEnabled;
    /** What holds camera mode to a time, {@code flight-limit}. */
    private FlightLimit flightLimit;


    /** Whether the key that swaps the hands ends camera mode, {@code exit-key.enabled}. */
    private boolean exitKeyEnabled;

    /**
     * Whether the click on his own body that ends camera mode reaches nothing
     * else, {@code exit-click-guard.enabled}.
     */
    private boolean exitClickGuardEnabled;
    /** For how long, in milliseconds, {@code exit-click-guard.seconds}. */
    private long exitClickGuardMillis;





    /** Whether start and end of camera mode are written down, the section {@code cam-log}. */
    private boolean camLogEnabled;
    /** Whether the same lines go into the console as well. */
    private boolean camLogConsole;
    /** How many days of log files are kept, 0 for all of them. */
    private int camLogKeepDays;

    // The sections that have a class of their own, read anew with every load
    /** The section {@code camera-mode}, see {@link #cameraMode()}. */
    private CameraModeSettings cameraMode;
    /** The section {@code signal}, see {@link #signal()}. */
    private SignalSettings signal;
    /** The section {@code body}, see {@link #body()}. */
    private BodySettings body;
    /** The section {@code camera-particles}, see {@link #particles()}. */
    private ParticleSettings particles;
    /** The section {@code action-bar}, see {@link #actionBar()}. */
    private ActionBarSettings actionBar;
    /** The section {@code flight-speed}, see {@link #flightSpeed()}. */
    private FlightSpeedSettings flightSpeed;
    /** The section {@code sounds}, see {@link #sounds()}. */
    private SoundSettings sounds;
    /** The section {@code viewpoints}, see {@link #spots()}. */
    private SpotSettings spots;
    /** The section {@code follow}, see {@link #follow()}. */
    private FollowSettings follow;
    /** The section {@code hide-ores}, see {@link #hideOres()}. */
    private OreSettings hideOres;
    /** The section {@code web-map}, see {@link #webMap()}. */
    private WebMapSettings webMap;
    /** The section {@code resume-command}, see {@link #resume()}. */
    private ResumeSettings resume;
    /** The section {@code menu}, see {@link #menu()}. */
    private MenuSettings menu;
    /** The section {@code photo-mode}, see {@link #photo()}. */
    private PhotoSettings photo;
    /** The section {@code cam-trail}, see {@link #trail()}. */
    private TrailSettings trail;
    /** The section {@code body-alarm}, see {@link #bodyAlarm()}. */
    private BodyAlarmSettings bodyAlarm;
    /** The section {@code time-limit}, see {@link #timeLimit()}. */
    private TimeLimitSettings timeLimit;
    /** The section {@code cam-safety}, see {@link #camSafety()}. */
    private CamSafetySettings camSafety;
    /** The section {@code bedrock}, see {@link #bedrock()}. */
    private BedrockSettings bedrock;
    /** The section {@code battery}, see {@link #battery()}. */
    private BatterySettings battery;
    /** The section {@code start-cost}, see {@link #startCost()}. */
    private StartCostSettings startCost;
    /** The section {@code statistics}, see {@link #statistics()}. */
    private StatsSettings statistics;
    /** The section {@code mirror-damage}, see {@link #mirrorDamage()}. */
    private MirrorDamageSettings mirrorDamage;

    // The values of the smaller sections

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
        // First: /cam follow and /cam resume show the time and the weather only where these let them.
        camTimeEnabled = config.getBoolean("cam-time.enabled", true);
        camWeatherEnabled = config.getBoolean("cam-weather.enabled", true);
        cameraMode = new CameraModeSettings(config, messages, plugin);
        signal = new SignalSettings(config, messages);
        cameraHeadEnabled = config.getBoolean("camera-head.enabled", false);
        body = new BodySettings(config);
        particles = new ParticleSettings(config, messages);
        actionBar = new ActionBarSettings(config);
        flightSpeed = new FlightSpeedSettings(config);
        String nightVision = config.getChoice("night-vision.mode", "dark", "true", "false", "dark")
                .toLowerCase(Locale.ROOT);
        nightVisionMode = switch (nightVision) {
            case "true" -> NightVisionMode.ALWAYS;
            case "false" -> NightVisionMode.OFF;
            default -> NightVisionMode.DARK;
        };
        nightVisionLightLevel = config.getInt("night-vision.light-level", 7, 0, MAX_LIGHT_LEVEL);
        nightVisionPlayerChoice = config.getBoolean("night-vision.player-choice", true);
        sounds = new SoundSettings(config, messages);
        crashProtectionEnabled = config.getBoolean("crash-protection.enabled", true);
        camListAccess = SettingValues.access(config, "cam-list.access");
        camListTeleport = config.getBoolean("cam-list.teleport", true);
        camListStop = config.getBoolean("cam-list.stop", true);
        stopCommandEnabled = config.getBoolean("stop-command.enabled", true);
        camBanEnabled = config.getBoolean("cam-ban.enabled", true);
        afkEndEnabled = config.getBoolean("afk-end.enabled", false);
        afkEndSeconds = config.getInt("afk-end.seconds", 300, MIN_AFK_SECONDS);
        afkWarningPercent = config.getInt("afk-end.warning-percent", 80, 0, 99);
        backCommandEnabled = config.getBoolean("back-command.enabled", true);
        spots = new SpotSettings(config);
        follow = new FollowSettings(config, camTimeEnabled, camWeatherEnabled);
        hideOres = new OreSettings(config, messages);
        webMap = new WebMapSettings(config, spots.sharing());
        resume = new ResumeSettings(config, camTimeEnabled, camWeatherEnabled, nightVisionPlayerChoice);
        playerSettingsStorage = SettingValues.storage(config, "player-settings.storage");
        playerSettingsFlightSpeed = config.getBoolean("player-settings.flight-speed", true);
        antiScoutingEnabled = config.getBoolean("anti-scouting.enabled", false);
        antiScoutingRadius = config.getDouble("anti-scouting.radius", 32.0, 0.0, MAX_SCOUT_RADIUS);
        antiScoutingFrom = config.getChoice("anti-scouting.measured-from", "body", "body", "camera")
                .equalsIgnoreCase("camera") ? ScoutAnchor.CAMERA : ScoutAnchor.BODY;
        menu = new MenuSettings(config, messages);
        photo = new PhotoSettings(config);
        startDelayEnabled = config.getBoolean("start-delay.enabled", false);
        startDelaySeconds = config.getInt("start-delay.seconds", 3, 1, MAX_START_DELAY_SECONDS);
        trail = new TrailSettings(config);
        endWarningEnabled = config.getBoolean("end-warning.enabled", true);
        endWarningSeconds = readSeconds(config, "end-warning.seconds", List.of(30, 10));
        bodyAlarm = new BodyAlarmSettings(config, body.mobTargetMode());
        nudgeEnabled = config.getBoolean("nudge.enabled", true);
        nudgeCooldown = config.getInt("nudge.cooldown", 5, MIN_NUDGE_COOLDOWN);
        String limit = config.getChoice("flight-limit", "false", "false", "time-limit", "battery")
                .toLowerCase(Locale.ROOT);
        flightLimit = switch (limit) {
            case "time-limit" -> FlightLimit.TIME_LIMIT;
            case "battery" -> FlightLimit.BATTERY;
            default -> FlightLimit.OFF;
        };
        timeLimit = new TimeLimitSettings(config, flightLimit);
        camSafety = new CamSafetySettings(config);
        exitKeyEnabled = config.getBoolean("exit-key.enabled", true);
        exitClickGuardEnabled = config.getBoolean("exit-click-guard.enabled", true);
        exitClickGuardMillis = Math.round(1000.0 * config.getDouble("exit-click-guard.seconds", DEFAULT_EXIT_CLICK_GUARD_SECONDS,
                MIN_EXIT_CLICK_GUARD_SECONDS, MAX_EXIT_CLICK_GUARD_SECONDS));
        bedrock = new BedrockSettings(config, messages);
        battery = new BatterySettings(config, flightLimit);
        startCost = new StartCostSettings(config);
        statistics = new StatsSettings(config);
        camLogEnabled = config.getBoolean("cam-log.enabled", false);
        camLogConsole = config.getBoolean("cam-log.console", false);
        camLogKeepDays = config.getInt("cam-log.keep-days", 30, 0);
        camAreaRules.load(config);
        portalRules.load(config);
        commandTeleportEndsCam = config.getBoolean("teleports.command", true);
        pluginTeleportEndsCam = config.getBoolean("teleports.plugin", true);
        pearlTeleportEndsCam = config.getBoolean("teleports.ender-pearl", true);
        readCamCommands(config);
        String spawning = config.getChoice("mob-spawning", "body", "body", "camera", "false")
                .toLowerCase(Locale.ROOT);
        spawnMode = switch (spawning) {
            case "camera" -> SpawnMode.CAMERA;
            case "false" -> SpawnMode.OFF;
            default -> SpawnMode.BODY;
        };
        advancementsBlocked = config.getBoolean("progress-guard.advancements", true);
        statisticsBlocked = config.getBoolean("progress-guard.statistics", true);

        mirrorDamage = new MirrorDamageSettings(config);
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













    /** The commands of {@code cam-commands.list} out of the box, as the file built into the jar has them. */
    private static final List<String> DEFAULT_CAM_COMMANDS = List.of("sethome", "setwarp", "setspawn", "setjail",
            "spawnpoint", "setworldspawn", "claim", "plot claim", "plot auto");

    /** The section {@code cam-commands}. */
    private void readCamCommands(ConfigReader config) {
        String mode = config.getChoice("cam-commands.mode", "blacklist", "blacklist", "whitelist", "all")
                .toLowerCase(Locale.ROOT);
        camCommandMode = switch (mode) {
            case "whitelist" -> CommandMode.WHITELIST;
            case "all" -> CommandMode.ALL;
            default -> CommandMode.BLACKLIST;
        };
        List<List<String>> commands = new ArrayList<>();
        for (String entry : config.getStringList("cam-commands.list", DEFAULT_CAM_COMMANDS)) {
            List<String> words = CamCommandFilter.words(entry);
            if (!words.isEmpty()) {
                commands.add(List.copyOf(words));
            }
        }
        camCommands = List.copyOf(commands);
    }








    // ------------------------------------------------- camera-mode, camera-head













    /**
     * Whether {@code cam-area} keeps the camera out of forbidden areas in
     * flight and behind portals: level 2, and a border that holds. The start
     * is not part of this, level 1 and 2 turn it away whatever the border does.
     */
    public boolean keepsOutOfAreas() {
        return camAreaRules.getLevel().blocksFlight() && cameraMode.bordersHold();
    }













    public boolean isCameraHeadEnabled() {
        return cameraHeadEnabled;
    }

    // ------------------------------------------------------------- night-vision






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

    // ----------------------------------------------------------------- cam-list














    public ListAccess getCamListAccess() {
        return camListAccess;
    }

    public boolean isCamListTeleport() {
        return camListTeleport;
    }

    public boolean isCamListStop() {
        return camListStop;
    }

    // ------------------------------------------- stop-command, cam-ban, afk-end

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

    // ------------------------------------------------------------- back-command

    public boolean isBackCommandEnabled() {
        return backCommandEnabled;
    }

    // -------------------- player-settings, anti-scouting, cam-time, cam-weather

    public StorageMode getPlayerSettingsStorage() {
        return playerSettingsStorage;
    }

    public boolean isPlayerSettingsFlightSpeed() {
        return playerSettingsFlightSpeed;
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














    public boolean isCamTimeEnabled() {
        return camTimeEnabled;
    }

    public boolean isCamWeatherEnabled() {
        return camWeatherEnabled;
    }

    // -------------------------------------------------------------- start-delay

    public boolean isStartDelayEnabled() {
        return startDelayEnabled;
    }

    public int getStartDelaySeconds() {
        return startDelaySeconds;
    }









    // -------------------------------------------------------------- end-warning

    public boolean isEndWarningEnabled() {
        return endWarningEnabled;
    }

    /** At how many seconds left the end is announced, the most first. */
    public List<Integer> getEndWarningSeconds() {
        return endWarningSeconds;
    }





    // -------------------------------------------------- nudge, crash-protection

    public boolean isNudgeEnabled() {
        return nudgeEnabled;
    }

    public int getNudgeCooldown() {
        return nudgeCooldown;
    }

    public boolean isCrashProtectionEnabled() {
        return crashProtectionEnabled;
    }

    // ----------------------------------------------------------------- exit-key

    public boolean isExitKeyEnabled() {
        return exitKeyEnabled;
    }

    // --------------------------------------------------------- exit-click-guard

    public boolean isExitClickGuardEnabled() {
        return exitClickGuardEnabled;
    }

    public long getExitClickGuardMillis() {
        return exitClickGuardMillis;
    }

    // ------------------------------------------------------------------ cam-log







    public boolean isCamLogEnabled() {
        return camLogEnabled;
    }

    public boolean logsCamToConsole() {
        return camLogConsole;
    }

    public int getCamLogKeepDays() {
        return camLogKeepDays;
    }

    // ----------------------------------------- cam-area, portals and the rest

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
     * {@code /tpa}, {@code teleports.ender-pearl} for an ender pearl thrown
     * before camera mode began - none can be thrown in it. Portals and the
     * like have rules of their own or cannot be used in camera mode at all.
     */
    public boolean teleportEndsCamMode(TeleportCause cause) {
        return switch (cause) {
            case COMMAND -> commandTeleportEndsCam;
            case PLUGIN -> pluginTeleportEndsCam;
            case ENDER_PEARL -> pearlTeleportEndsCam;
            default -> false;
        };
    }

    /** Which commands a camera player may use, {@code cam-commands.mode}. */
    public CommandMode getCamCommandMode() {
        return camCommandMode;
    }

    /** The commands of {@code cam-commands.list}, each as its words in lower case. */
    public List<List<String>> getCamCommands() {
        return camCommands;
    }

    /** What a camera player counts as for the mobs that spawn by themselves, {@code mob-spawning}. */
    public SpawnMode getSpawnMode() {
        return spawnMode;
    }

    /** Whether a camera player earns no advancements, {@code progress-guard.advancements}. */
    public boolean blocksAdvancements() {
        return advancementsBlocked;
    }

    /** Whether a camera player's movement counts for no statistic, {@code progress-guard.statistics}. */
    public boolean blocksStatistics() {
        return statisticsBlocked;
    }

    // ----------------------------------- the sections with a class of their own

    /** The section {@code camera-mode}, as last read. */
    public CameraModeSettings cameraMode() {
        return cameraMode;
    }

    /** The section {@code signal}, as last read. */
    public SignalSettings signal() {
        return signal;
    }

    /** The section {@code body}, as last read. */
    public BodySettings body() {
        return body;
    }

    /** The section {@code camera-particles}, as last read. */
    public ParticleSettings particles() {
        return particles;
    }

    /** The section {@code action-bar}, as last read. */
    public ActionBarSettings actionBar() {
        return actionBar;
    }

    /** The section {@code flight-speed}, as last read. */
    public FlightSpeedSettings flightSpeed() {
        return flightSpeed;
    }

    /** The section {@code sounds}, as last read. */
    public SoundSettings sounds() {
        return sounds;
    }

    /** The section {@code viewpoints}, as last read. */
    public SpotSettings spots() {
        return spots;
    }

    /** The section {@code follow}, as last read. */
    public FollowSettings follow() {
        return follow;
    }

    /** The section {@code hide-ores}, as last read. */
    public OreSettings hideOres() {
        return hideOres;
    }

    /** The section {@code web-map}, as last read. */
    public WebMapSettings webMap() {
        return webMap;
    }

    /** The section {@code resume-command}, as last read. */
    public ResumeSettings resume() {
        return resume;
    }

    /** The section {@code menu}, as last read. */
    public MenuSettings menu() {
        return menu;
    }

    /** The section {@code photo-mode}, as last read. */
    public PhotoSettings photo() {
        return photo;
    }

    /** The section {@code cam-trail}, as last read. */
    public TrailSettings trail() {
        return trail;
    }

    /** The section {@code body-alarm}, as last read. */
    public BodyAlarmSettings bodyAlarm() {
        return bodyAlarm;
    }

    /** The section {@code time-limit}, as last read. */
    public TimeLimitSettings timeLimit() {
        return timeLimit;
    }

    /** The section {@code cam-safety}, as last read. */
    public CamSafetySettings camSafety() {
        return camSafety;
    }

    /** The section {@code bedrock}, as last read. */
    public BedrockSettings bedrock() {
        return bedrock;
    }

    /** The section {@code battery}, as last read. */
    public BatterySettings battery() {
        return battery;
    }

    /** The section {@code start-cost}, as last read. */
    public StartCostSettings startCost() {
        return startCost;
    }

    /** The section {@code statistics}, as last read. */
    public StatsSettings statistics() {
        return statistics;
    }

    /** The section {@code mirror-damage}, as last read. */
    public MirrorDamageSettings mirrorDamage() {
        return mirrorDamage;
    }
}
