package de.elia.cameraplugin.session;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.config.NameStyle;
import de.elia.cameraplugin.config.SettingValues;
import de.elia.cameraplugin.display.GlowMode;
import de.elia.cameraplugin.display.NameMode;
import de.elia.cameraplugin.movement.BorderMode;
import de.elia.cameraplugin.movement.DistanceMode;
import de.elia.cameraplugin.movement.FlightMedium;
import de.elia.cameraplugin.start.EffectStart;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.ChatColor;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The section {@code camera-mode} of the config file: how far the camera
 * gets and what holds it back, who sees it, its name, where it flies and in
 * which game mode. Read anew with every load, see {@link de.elia.cameraplugin.config.CamSettings#cameraMode()}.
 */
public final class CameraModeSettings {

    /**
     * The switch that said whether {@code max-distance} held the camera at
     * all, before {@code max-distance-mode} had the value {@code false} for
     * it. The update of the file takes it out; only a file it could not
     * change still has it.
     */
    public static final String OLD_DISTANCE_SWITCH = "camera-mode.max-distance-enabled";

    // Configurable values
    private double maxDistance;
    /** How max-distance holds the camera, {@code camera-mode.max-distance-mode}. */
    private DistanceMode distanceMode;
    /** How long a message at a border waits before it comes again, in seconds, {@code camera-mode.warning-cooldown}. */
    private int warningCooldown;
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
    private VisibilityMode playerVisibilityMode;
    private boolean allowInvisibilityPotion;
    private GlowMode glowMode;
    /** Whether the camera player carries a name of the plugin, {@code camera-mode.name-visible}. */
    private boolean playerNameVisible;
    /** How that name keeps to him, {@code camera-mode.name-mode}. */
    private NameMode playerNameMode;
    /** How the name over the camera player looks, the section {@code camera-mode.name}. */
    private NameStyle playerNameStyle;
    /** Whether the camera may fly into lava, {@code camera-mode.allow-lava-flight}. */
    private boolean allowLavaFlight;
    /** Whether the camera may fly into water, {@code camera-mode.allow-water-flight}. */
    private boolean allowWaterFlight;
    /** Whether the camera may fly into powder snow, {@code camera-mode.allow-powder-snow-flight}. */
    private boolean allowPowderSnowFlight;
    /** The mode the camera player flies in, {@code camera-mode.gamemode}. */
    private CamGameMode camGameMode;
    /** Whether mobs look at a camera player, {@code camera-mode.mobs-look-at-player}. */
    private boolean mobsLookAtPlayer;
    /** What {@code camera-mode.start-with-effects} allows him to start with. */
    private EffectStart startWithEffects;

    /**
     * Whether the border of {@code max-distance} and {@code cam-area} holds
     * the camera back at all, see the field of the same name.
     */
    public boolean bordersHold() {
        return bordersHold;
    }

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public CameraModeSettings(ConfigReader config, Messages messages, JavaPlugin plugin) {
        maxDistance = config.getDouble("camera-mode.max-distance", 100.0, 0.0);
        String distance = config.getChoice("camera-mode.max-distance-mode", "fixed", "fixed", "signal", "false")
                .toLowerCase(Locale.ROOT);
        distanceMode = switch (distance) {
            case "signal" -> DistanceMode.SIGNAL;
            case "false" -> DistanceMode.OFF;
            default -> DistanceMode.FIXED;
        };
        // Switched off with a key of its own before false was a value of the
        // mode. A file the update could not change says so there still.
        if (!config.getBoolean(OLD_DISTANCE_SWITCH, true)) {
            distanceMode = DistanceMode.OFF;
        }
        // Called distance-warning-cooldown before it counted for every border.
        warningCooldown = config.getInt(config.renamed("camera-mode.warning-cooldown",
                "camera-mode.distance-warning-cooldown"), 3, 0);
        String border = config.getChoice("camera-mode.border-mode", "barrier", "barrier", "push-back", "false")
                .toLowerCase(Locale.ROOT);
        borderMode = switch (border) {
            case "push-back" -> BorderMode.PUSH_BACK;
            case "false" -> BorderMode.OFF;
            default -> BorderMode.BARRIER;
        };
        borderBlock = SettingValues.block(config, messages, "camera-mode.border-block", "barrier");
        mediumBorderBlocks.clear();
        readMediumBorderBlock(config, messages, FlightMedium.WATER, "camera-mode.border-block-water", "blue_stained_glass");
        readMediumBorderBlock(config, messages, FlightMedium.LAVA, "camera-mode.border-block-lava", "magma_block");
        readMediumBorderBlock(config, messages, FlightMedium.POWDER_SNOW, "camera-mode.border-block-powder-snow",
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
        // The keys with underscores got hyphens like all others; an older file is read as it was written.
        String visibility = config.getChoice(
                config.renamed("camera-mode.player-visibility", "camera-mode.player_visibility_mode"),
                "true", "cam", "true", "false").toLowerCase(Locale.ROOT);
        playerVisibilityMode = switch (visibility) {
            case "cam" -> VisibilityMode.CAM;
            case "false" -> VisibilityMode.NONE;
            default -> VisibilityMode.ALL;
        };
        allowInvisibilityPotion = config.getBoolean(
                config.renamed("camera-mode.allow-invisibility-potion", "camera-mode.allow_invisibility_potion"), true);
        String glow = config.getChoice("camera-mode.glowing-outline", "true", "true", "false", "sight")
                .toLowerCase();
        glowMode = switch (glow) {
            case "false" -> GlowMode.OFF;
            case "sight" -> GlowMode.SIGHT;
            default -> GlowMode.ALWAYS;
        };
        playerNameVisible = config.getBoolean("camera-mode.name-visible", true);
        playerNameMode = NameMode.byName(config.getChoice("camera-mode.name-mode", NameMode.FOLLOW.getConfigName(),
                NameMode.oldValues(), NameMode.configNames()));
        playerNameStyle = NameStyle.read(config, "camera-mode.name", ChatColor.WHITE);
        allowLavaFlight = config.getBoolean(
                config.renamed("camera-mode.allow-lava-flight", "camera-mode.allow_lava_flight"), true);
        allowWaterFlight = config.getBoolean(
                config.renamed("camera-mode.allow-water-flight", "camera-mode.allow_water_flight"), true);
        allowPowderSnowFlight = config.getBoolean(
                config.renamed("camera-mode.allow-powder-snow-flight", "camera-mode.allow_powder_snow_flight"), true);
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
    private void readMediumBorderBlock(ConfigReader config, Messages messages, FlightMedium medium, String path, String def) {
        BlockData block = SettingValues.block(config, messages, path, def);
        mediumBorderBlocks.put(medium, block.getMaterial().isSolid() ? block : borderBlock.clone());
    }


    public double maxDistance() {
        return maxDistance;
    }

    /**
     * How long a message at a border waits before it is sent again, in
     * seconds: max-distance, cam-area, lava, water, powder snow and portals.
     */
    public int warningCooldown() {
        return warningCooldown;
    }

    /** A copy of the block the wall is made of, free to be changed by the caller. */
    public BlockData borderBlock() {
        return borderBlock.clone();
    }

    /** A copy of the block the wall shows in place of this medium, free to be changed by the caller. */
    public BlockData borderBlock(FlightMedium medium) {
        return mediumBorderBlocks.get(medium).clone();
    }

    public double borderRadius() {
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
     * Whether {@code max-distance} holds the camera back at a border: in the
     * mode {@code fixed}, and a border that holds. Under
     * {@code signal} it is the signal that holds it, see {@link #usesSignal()},
     * and there is no border at the distance.
     */
    public boolean limitsDistance() {
        return distanceMode == DistanceMode.FIXED && bordersHold;
    }

    /**
     * Whether the camera flies on a radio signal from its body,
     * {@code max-distance-mode: signal}.
     * Whatever {@code border-mode} says: the signal is no border, nothing is
     * run into at its end.
     */
    public boolean usesSignal() {
        return distanceMode == DistanceMode.SIGNAL;
    }

    public VisibilityMode visibilityMode() {
        return playerVisibilityMode;
    }

    public boolean allowsInvisibilityPotion() {
        return allowInvisibilityPotion;
    }

    public GlowMode glowMode() {
        return glowMode;
    }

    /**
     * Whether a camera player carries the name of {@code camera-mode.name} in
     * place of his own name tag. Not in mode NONE: nobody is meant to see him
     * there, and a name would give away where he is.
     */
    public boolean showsName() {
        return playerNameVisible && playerVisibilityMode != VisibilityMode.NONE;
    }

    public NameMode nameMode() {
        return playerNameMode;
    }

    public NameStyle nameStyle() {
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

    public CamGameMode gameMode() {
        return camGameMode;
    }

    public boolean mobsLookAtPlayer() {
        return mobsLookAtPlayer;
    }

    public EffectStart startWithEffects() {
        return startWithEffects;
    }
}
