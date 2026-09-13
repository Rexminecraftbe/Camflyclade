package de.elia.cameraplugin.body;

/**
 * How far mobs notice the body that stays behind in camera mode - the values of
 * {@code body.mob-target}.
 */
public enum MobTargetMode {
    /**
     * Every mob with the range it has in vanilla: the 16 blocks of a zombie,
     * the 48 of a blaze. The range is read off the mob itself, so a mob of a
     * later version brings its own along without anybody adding it here.
     */
    VANILLA,
    /** One range for every mob, {@code body.mob-target-radius}. */
    CUSTOM,
    /** Nobody is sent to the body. */
    OFF;

    /** Whether mobs are sent to the body at all. */
    public boolean attractsMobs() {
        return this != OFF;
    }
}
