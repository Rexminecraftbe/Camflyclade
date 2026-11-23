package de.elia.cameraplugin.body;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.NameStyle;
import de.elia.cameraplugin.mob.MobTargeting;
import org.bukkit.ChatColor;

import java.util.Locale;

/**
 * The section {@code body} of the config file: what the body is, how it
 * looks, what moves it and which mobs notice it. See {@link de.elia.cameraplugin.config.CamSettings#body()}.
 */
public final class BodySettings {

    /**
     * Smallest threshold that is accepted for {@code body.move-threshold}, in
     * blocks. Anything below is raised to this value.
     */
    public static final double MIN_MOVE_THRESHOLD = 0.01;

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
    /** Whether the locator bar points at the body instead of the camera, {@code body.locator-bar}. */
    private boolean bodyOnLocatorBar;
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

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public BodySettings(ConfigReader config) {
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
        bodyOnLocatorBar = config.getBoolean("body.locator-bar", true);
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

    public BodyType type() {
        return bodyType;
    }

    public boolean nameVisible() {
        return bodyNameVisible;
    }

    public boolean healthVisible() {
        return bodyHealthVisible;
    }

    public boolean healthAbove() {
        return bodyHealthAbove;
    }

    public boolean healthShowMissing() {
        return bodyHealthShowMissing;
    }

    public boolean healthShowAbsorption() {
        return bodyHealthShowAbsorption;
    }

    public boolean healthRowsUpward() {
        return bodyHealthRowsUpward;
    }

    public NameStyle nameStyle() {
        return bodyNameStyle;
    }

    public boolean visible() {
        return bodyVisible;
    }

    public boolean armorVisible() {
        return bodyArmorVisible;
    }

    /** {@code body.locator-bar}, see {@link de.elia.cameraplugin.locator.CamLocatorBar}. */
    public boolean onLocatorBar() {
        return bodyOnLocatorBar;
    }

    public MovementSensitivity movementSensitivity() {
        return movementSensitivity;
    }

    public double moveThresholdSquared() {
        return moveThresholdSquared;
    }

    public MobTargetMode mobTargetMode() {
        return mobTargetMode;
    }

    public double mobTargetRadius() {
        return mobTargetRadius;
    }

    public boolean mobTargetHeads() {
        return mobTargetHeads;
    }
}
