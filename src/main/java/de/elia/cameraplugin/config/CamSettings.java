package de.elia.cameraplugin.config;

import de.elia.cameraplugin.area.CamAreaRules;
import de.elia.cameraplugin.body.BodyType;
import de.elia.cameraplugin.body.MobTargetMode;
import de.elia.cameraplugin.body.MovementSensitivity;
import de.elia.cameraplugin.display.GlowMode;
import de.elia.cameraplugin.display.NameMode;
import de.elia.cameraplugin.mirrordamage.ArmorDamageMode;
import de.elia.cameraplugin.mirrordamage.DamageMode;
import de.elia.cameraplugin.mob.MobTargeting;
import de.elia.cameraplugin.movement.BorderMode;
import de.elia.cameraplugin.movement.FlightMedium;
import de.elia.cameraplugin.portal.PortalRules;
import de.elia.cameraplugin.session.CamGameMode;
import de.elia.cameraplugin.start.EffectStart;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.boss.BarColor;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
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
     * Keys that are read although the config file built into the jar leaves
     * them out on purpose, so that they are not reported as unknown, see
     * {@link ConfigReader#warnUnknownKeys}.
     */
    public static final Set<String> UNLISTED_KEYS = Set.of("mirror-damage.debug");

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

    // Camera safety settings
    private boolean camSafetyEnabled;
    private int camSafetyDelay;

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
        timeLimitEnabled = config.getBoolean("time-limit.enabled", false);
        cooldownsEnabled = config.getBoolean("time-limit.cooldowns-enabled", false);
        durationSeconds = config.getInt("time-limit.duration-seconds", 300, 1);
        cooldownSeconds = config.getInt("time-limit.cooldown-seconds", 120, 0);
        showBossbar = config.getBoolean("time-limit.show-bossbar", true);
        bossbarColor = config.getEnum("time-limit.bossbar-color", BarColor.class, BarColor.BLUE);
        camSafetyEnabled = config.getBoolean("cam-safety.enabled", true);
        camSafetyDelay = config.getInt("cam-safety.delay", 5, 0);
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

    // --------------------------------------------------------------- cam-safety

    public boolean isCamSafetyEnabled() {
        return camSafetyEnabled;
    }

    public int getCamSafetyDelay() {
        return camSafetyDelay;
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
