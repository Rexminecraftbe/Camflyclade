package de.elia.cameraplugin.config;

import de.elia.cameraplugin.area.CamAreaRules;
import de.elia.cameraplugin.body.BodyType;
import de.elia.cameraplugin.body.MobTargetMode;
import de.elia.cameraplugin.body.MovementSensitivity;
import de.elia.cameraplugin.display.GlowMode;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.mirrordamage.ArmorDamageMode;
import de.elia.cameraplugin.mirrordamage.DamageMode;
import de.elia.cameraplugin.movement.BorderMode;
import de.elia.cameraplugin.movement.FlightMedium;
import de.elia.cameraplugin.portal.PortalRules;
import de.elia.cameraplugin.session.CamGameMode;
import de.elia.cameraplugin.start.EffectStart;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.boss.BarColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

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
     * Largest value accepted for {@code camera-mode.border-radius}, in blocks.
     * The wall looks at every block that far around the player each time the
     * player crosses into the next one, and that work grows with the cube of
     * the radius.
     */
    public static final double MAX_BORDER_RADIUS = 16.0;

    private final JavaPlugin plugin;
    private final ConsoleLog log;

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
    private final CamAreaRules camAreaRules = new CamAreaRules();
    /** Whether a portal lets a camera player through, the section {@code portals}. */
    private final PortalRules portalRules = new PortalRules();
    private VisibilityMode playerVisibilityMode;
    private boolean allowInvisibilityPotion;
    private GlowMode glowMode;
    /** Whether the camera may fly into lava, {@code camera-mode.allow_lava_flight}. */
    private boolean allowLavaFlight;
    /** Whether the camera may fly into water, {@code camera-mode.allow_water_flight}. */
    private boolean allowWaterFlight;
    /** Whether the camera may fly into powder snow, {@code camera-mode.allow_powder_snow_flight}. */
    private boolean allowPowderSnowFlight;
    /** The mode the camera player flies in, {@code camera-mode.gamemode}. */
    private CamGameMode camGameMode;
    /** What {@code camera-mode.start-with-effects} allows him to start with. */
    private EffectStart startWithEffects;

    private double particleHeight;
    private int particlesPerTick;
    private boolean showOwnParticles;

    private boolean actionBarEnabled;
    private String actionBarOnMessage;
    private String actionBarOffMessage;
    private int actionBarOffDuration;

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
    // Time limit and cooldown settings
    private boolean timeLimitEnabled;
    private boolean cooldownsEnabled;
    private int durationSeconds;
    private int cooldownSeconds;
    private boolean showBossbar;
    private BarColor bossbarColor;
    private String bossbarText;
    private String cooldownAvailableText;

    // Camera safety settings
    private boolean camSafetyEnabled;
    private int camSafetyDelay;
    private String camSafetyMessage;

    public CamSettings(JavaPlugin plugin, ConsoleLog log) {
        this.plugin = plugin;
        this.log = log;
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
        borderRadius = config.getDouble("camera-mode.border-radius", 5.0, 0.0, MAX_BORDER_RADIUS);
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
        String effects = config.getChoice("camera-mode.start-with-effects",
                "positive", "true", "false", "positive").toLowerCase();
        startWithEffects = switch (effects) {
            case "true" -> EffectStart.ANY;
            case "false" -> EffectStart.NONE;
            default -> EffectStart.POSITIVE;
        };
        cameraHeadEnabled = config.getBoolean("camera-head.enabled", false);
        bodyType = resolveBodyType(config, config.getInt("body.type", BodyType.ARMOR_STAND.getId()));
        bodyNameVisible = config.getBoolean("body.name-visible", true);
        bodyVisible = config.getBoolean("body.visible", true);
        bodyArmorVisible = config.getBoolean("body.armor-visible", true);
        movementSensitivity = resolveMovementSensitivity(config,
                config.getInt("body.movement-sensitivity", MovementSensitivity.NORMAL.getId()));
        double moveThreshold = config.getDouble("body.move-threshold", 0.05, MIN_MOVE_THRESHOLD);
        moveThresholdSquared = moveThreshold * moveThreshold;
        mobTargetMode = resolveMobTargetMode(config);
        mobTargetRadius = config.getDouble("body.mob-target-radius", 16.0, 0.0);
        mobTargetHeads = config.getBoolean("body.mob-target-heads", true);
        particleHeight = config.getDouble("camera-particles.height", 1.0);
        particlesPerTick = config.getInt("camera-particles.particles-per-tick", 5, 0);
        showOwnParticles = config.getBoolean("camera-particles.show-own-particles", false);
        actionBarEnabled = config.getBoolean("action-bar.enabled", true);
        actionBarOffDuration = config.getInt("action-bar.off-duration", 10, 0);
        actionBarOnMessage = ChatColor.translateAlternateColorCodes('&', config.getString("messages.actionbar-on", "&aCam-Modus aktiviert"));
        actionBarOffMessage = ChatColor.translateAlternateColorCodes('&', config.getString("messages.actionbar-off", "&cCam-Modus beendet"));
        timeLimitEnabled = config.getBoolean("time-limit.enabled", false);
        cooldownsEnabled = config.getBoolean("time-limit.cooldowns-enabled", false);
        durationSeconds = config.getInt("time-limit.duration-seconds", 300, 1);
        cooldownSeconds = config.getInt("time-limit.cooldown-seconds", 120, 0);
        showBossbar = config.getBoolean("time-limit.show-bossbar", true);
        bossbarColor = config.getEnum("time-limit.bossbar-color", BarColor.class, BarColor.BLUE);
        bossbarText = ChatColor.translateAlternateColorCodes('&', config.getString("messages.bossbar-text", "Cam-Modus endet in: %time%"));
        cooldownAvailableText = ChatColor.translateAlternateColorCodes('&', config.getString("messages.cooldown-available", "&aCam-Modus wieder verf\u00fcgbar"));
        camSafetyEnabled = config.getBoolean("cam-safety.enabled", true);
        camSafetyDelay = config.getInt("cam-safety.delay", 5, 0);
        camSafetyMessage = config.getString("messages.cam-safety",
                "§cDu kannst den Cam-Modus nicht starten! Du musst noch %seconds% Sekunden in Sicherheit bleiben.");
        camAreaRules.load(config);
        portalRules.load(config);

        mirrorKnockback = config.getBoolean("mirror-damage.knockback", true);
        // Only for measuring, so left out of config.yml on purpose: whoever
        // needs it adds "debug: true" under mirror-damage by hand.
        mirrorDebug = config.getBoolean("mirror-damage.debug", false);
        // "off" war frueher die Schreibweise fuer aus und wird weiter gelesen.
        String modeRaw = readMode(config, "mirror-damage.damage-mode", "mirror",
                List.of("mirror", "custom", "false"), List.of("off"));
        if ("custom".equalsIgnoreCase(modeRaw)) {
            damageMode = DamageMode.CUSTOM;
        } else if ("false".equalsIgnoreCase(modeRaw) || "off".equalsIgnoreCase(modeRaw)) {
            damageMode = DamageMode.OFF;
        } else {
            damageMode = DamageMode.MIRROR;
        }
        customDamageHearts = config.getDouble("mirror-damage.custom-damage-hearts", 0.5, 0.0);
        // Used to be called custom-damage-counts-armor, back when it only had a
        // say over the custom damage. A file that still carries the old name
        // keeps its setting, it is simply read as the default of the new one.
        damageCountsArmor = config.getBoolean("mirror-damage.damage-counts-armor",
                config.getBoolean("mirror-damage.custom-damage-counts-armor", true));
        // The mode grew out of the old truth value damage-armor, so a config
        // file that still carries only that one keeps saying what it said:
        // true wears the armour down, false leaves it alone.
        String armorDefault = config.getBoolean("mirror-damage.damage-armor", true) ? "mirror" : "false";
        String armorRaw = readMode(config, "mirror-damage.damage-armor-mode", armorDefault,
                List.of("mirror", "custom", "false"), List.of("off", "true"));
        if ("custom".equalsIgnoreCase(armorRaw)) {
            armorDamageMode = ArmorDamageMode.CUSTOM;
        } else if ("false".equalsIgnoreCase(armorRaw) || "off".equalsIgnoreCase(armorRaw)) {
            armorDamageMode = ArmorDamageMode.OFF;
        } else {
            armorDamageMode = ArmorDamageMode.MIRROR;
        }
        customArmorDamage = config.getInt("mirror-damage.custom-armor-damage", 1, 0);
        respectUnbreaking = config.getBoolean("mirror-damage.respect-unbreaking", true);
        // Read one by one while the server runs, so a wrong value would show up
        // again and again instead of once. Checked here in one go instead.
        config.checkBooleanSection("message-settings");
        warnAboutOldArmorStandSection();
        warnAboutOldDamageArmorKey();
        warnAboutOldStructureKey();
    }

    /**
     * Says once that a leftover {@code armorstand} section is not read any
     * more. Its two remaining settings now sit in {@code body}, and gravity is
     * decided by {@code body.movement-sensitivity} - without this note a config
     * file from an older version would quietly run on the default values.
     */
    private void warnAboutOldArmorStandSection() {
        if (!plugin.getConfig().isConfigurationSection("armorstand")) {
            return;
        }
        plugin.getLogger().warning("Der Abschnitt \"armorstand\" wird nicht mehr gelesen: name-visible und visible"
                + " stehen jetzt unter \"body\", gravity ist durch body.movement-sensitivity ersetzt.");
    }

    /**
     * Says once that {@code mirror-damage.damage-armor} has become a mode of
     * its own. The truth value is still read, as the default of the new key,
     * so that a config file from an older version keeps behaving the way it
     * reads - but only the new key knows the third mode.
     */
    private void warnAboutOldDamageArmorKey() {
        if (!plugin.getConfig().isSet("mirror-damage.damage-armor")) {
            return;
        }
        log.log(Level.WARNING, "mirror-damage.damage-armor heisst jetzt damage-armor-mode und kennt drei Werte:"
                + " mirror, custom und false. true wird als mirror gelesen, false bleibt false.");
    }

    /**
     * Says once that {@code cam-area.forbidden-structures} has become two
     * lists. The old one is still read, as the default of the box list, so that
     * a config file from an older version keeps behaving the way it reads - but
     * measuring by pieces only happens once the new list is filled.
     */
    private void warnAboutOldStructureKey() {
        if (!plugin.getConfig().isSet("cam-area.forbidden-structures")) {
            return;
        }
        plugin.getLogger().warning("cam-area.forbidden-structures ist in zwei Listen aufgeteilt:"
                + " forbidden-structures-box misst den ganzen Kasten einer Struktur,"
                + " forbidden-structures-components nur ihre einzelnen Bauteile."
                + " Die alten Eintraege werden als Kasten-Liste gelesen.");
    }

    /**
     * Reads a setting that knows more than two values and hands back the
     * spelling it was written with.
     *
     * <p>Kept apart from {@link ConfigReader#getChoice} because of the
     * spellings an older config file may carry: those are still read, they are
     * simply not offered any more. Only {@code shown} turns up in the note
     * about a value nobody knows, so nobody is sent back to a spelling this
     * version has left behind.</p>
     *
     * @param shown  the spellings this version writes
     * @param legacy the spellings an older version wrote, read but not offered
     */
    private String readMode(ConfigReader config, String path, String def,
                            List<String> shown, List<String> legacy) {
        String value = config.getString(path, def);
        if (isOneOf(value, shown) || isOneOf(value, legacy)) {
            return value;
        }
        config.warnUnknownValue(path, value, String.join(", ", shown), def);
        return def;
    }

    /** Whether the value is one of these spellings, capitals not counting. */
    private static boolean isOneOf(String value, List<String> spellings) {
        for (String spelling : spellings) {
            if (spelling.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
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
            config.warnUnknownValue(path, raw, "jeder Block, z. B. barrier oder glass", def);
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
     * Reads {@code body.mob-target}. The setting used to be a truth value, back
     * when there was only the one range to switch on and off, so those two
     * values keep saying what they said: {@code true} is the range out of the
     * config file, {@code false} is nobody sent to the body.
     */
    private MobTargetMode resolveMobTargetMode(ConfigReader config) {
        String raw = readMode(config, "body.mob-target", "false",
                List.of("vanilla", "custom", "false"), List.of("off", "true"));
        if ("vanilla".equalsIgnoreCase(raw)) {
            return MobTargetMode.VANILLA;
        }
        if ("custom".equalsIgnoreCase(raw) || "true".equalsIgnoreCase(raw)) {
            return MobTargetMode.CUSTOM;
        }
        return MobTargetMode.OFF;
    }

    /**
     * Turns the number configured in {@code body.type} into a body type and
     * falls back to the armour stand when the value is unknown.
     */
    private BodyType resolveBodyType(ConfigReader config, int configuredId) {
        BodyType requested = BodyType.fromId(configuredId);
        if (requested == null) {
            config.warnUnknownValue("body.type", configuredId, "1, 2",
                    String.valueOf(BodyType.ARMOR_STAND.getId()));
            return BodyType.ARMOR_STAND;
        }
        return requested;
    }

    /**
     * Turns the number configured in {@code body.movement-sensitivity} into a
     * level and falls back to 1 when the value is unknown.
     *
     * <p>Level 2 is cut back to level 1 for an armour stand body. That is a
     * valid setting in the wrong combination and not a mistake, so it is
     * described in the config file instead of being logged.</p>
     */
    private MovementSensitivity resolveMovementSensitivity(ConfigReader config, int configuredId) {
        MovementSensitivity requested = MovementSensitivity.fromId(configuredId);
        if (requested == null) {
            config.warnUnknownValue("body.movement-sensitivity", configuredId, "0, 1, 2",
                    String.valueOf(MovementSensitivity.NORMAL.getId()));
            requested = MovementSensitivity.NORMAL;
        }
        return requested.forBodyType(bodyType);
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

    public double getParticleHeight() {
        return particleHeight;
    }

    public int getParticlesPerTick() {
        return particlesPerTick;
    }

    public boolean showsOwnParticles() {
        return showOwnParticles;
    }

    public boolean isActionBarEnabled() {
        return actionBarEnabled;
    }

    public String getActionBarOnMessage() {
        return actionBarOnMessage;
    }

    public String getActionBarOffMessage() {
        return actionBarOffMessage;
    }

    public int getActionBarOffDuration() {
        return actionBarOffDuration;
    }

    // --------------------------------------------------- time limit and cooldown

    public boolean isTimeLimitEnabled() {
        return timeLimitEnabled;
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

    public String getBossbarText() {
        return bossbarText;
    }

    public String getCooldownAvailableText() {
        return cooldownAvailableText;
    }

    // --------------------------------------------------------------- cam-safety

    public boolean isCamSafetyEnabled() {
        return camSafetyEnabled;
    }

    public int getCamSafetyDelay() {
        return camSafetyDelay;
    }

    public String getCamSafetyMessage() {
        return camSafetyMessage;
    }

    // --------------------------------------------------------- cam-area, portals

    public CamAreaRules getCamAreaRules() {
        return camAreaRules;
    }

    public PortalRules getPortalRules() {
        return portalRules;
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
