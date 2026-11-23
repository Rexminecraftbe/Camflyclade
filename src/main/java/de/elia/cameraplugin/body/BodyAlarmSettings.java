package de.elia.cameraplugin.body;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.mob.MobTargeting;

/**
 * The section {@code body-alarm} of the config file: when a camera player
 * is warned that something comes for the body. See {@link de.elia.cameraplugin.config.CamSettings#bodyAlarm()}.
 */
public final class BodyAlarmSettings {

    /** Whether a mob after the body sets off the alarm, {@code body-alarm.mobs}. */
    private boolean bodyAlarmMobs;
    /** Whether a player close to the body sets it off, {@code body-alarm.players}. */
    private boolean bodyAlarmPlayers;
    /** How close a player has to come for that, in blocks, {@code body-alarm.player-radius}. */
    private double bodyAlarmPlayerRadius;
    /** Seconds before the next alarm, {@code body-alarm.cooldown}. */
    private int bodyAlarmCooldown;

    /** {@code body.mob-target}: a body no mob notices needs no alarm about mobs. */
    private final MobTargetMode mobTargetMode;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public BodyAlarmSettings(ConfigReader config, MobTargetMode mobTargetMode) {
        this.mobTargetMode = mobTargetMode;
        bodyAlarmMobs = config.getBoolean("body-alarm.mobs", true);
        bodyAlarmPlayers = config.getBoolean("body-alarm.players", false);
        bodyAlarmPlayerRadius = config.getDouble("body-alarm.player-radius", 10.0, 0.0,
                MobTargeting.MAX_MOB_TARGET_RANGE);
        bodyAlarmCooldown = config.getInt("body-alarm.cooldown", 5, 0);
    }

    /**
     * Whether a mob after the body sets off the alarm. Only where a mob can
     * be after it at all: in the mode {@code false} of {@code body.mob-target}
     * none is.
     */
    public boolean forMobs() {
        return bodyAlarmMobs && mobTargetMode.attractsMobs();
    }

    public boolean forPlayers() {
        return bodyAlarmPlayers && bodyAlarmPlayerRadius > 0.0;
    }

    public double playerRadius() {
        return bodyAlarmPlayerRadius;
    }

    public int cooldown() {
        return bodyAlarmCooldown;
    }
}
