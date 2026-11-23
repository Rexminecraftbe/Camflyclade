package de.elia.cameraplugin.sound;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.EndReason;

/**
 * The section {@code sounds} of the config file: the sound of each moment
 * of camera mode. See {@link de.elia.cameraplugin.config.CamSettings#sounds()}.
 */
public final class SoundSettings {

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

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public SoundSettings(ConfigReader config, Messages messages) {
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
    }

    public CamSound start() {
        return startSound;
    }

    /**
     * The sound for an end of camera mode: {@code sounds.end},
     * {@code end-body} or {@code end-forced}, and none for an end nobody
     * would hear.
     */
    public CamSound end(EndReason reason) {
        return switch (reason.getKind()) {
            case SELF -> endSound;
            case BODY -> endBodySound;
            case FORCED -> endForcedSound;
            case SILENT -> CamSound.OFF;
        };
    }

    public CamSound border() {
        return borderSound;
    }

    public CamSound back() {
        return backSound;
    }

    public CamSound endWarning() {
        return endWarningSound;
    }

    public CamSound bodyAlarm() {
        return bodyAlarmSound;
    }

    public CamSound startDelay() {
        return startDelaySound;
    }

    public CamSound nudge() {
        return nudgeSound;
    }

    public CamSound signalLost() {
        return signalLostSound;
    }

    public CamSound signalBack() {
        return signalBackSound;
    }

    public CamSound spot() {
        return spotSound;
    }

    public CamSound followInvite() {
        return tourInviteSound;
    }

    public CamSound followReach() {
        return tourReachSound;
    }
}
