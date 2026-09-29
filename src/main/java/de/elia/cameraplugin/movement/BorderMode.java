package de.elia.cameraplugin.movement;

/**
 * What happens where the camera is not allowed to go on: at
 * {@code camera-mode.max-distance}, at the areas {@code cam-area} keeps it
 * out of on level 2, and at lava, water and powder snow where their switch
 * shuts them - the values of {@code camera-mode.border-mode}.
 *
 * <p>Whether lava, water and powder snow are a border at all is up to their
 * own switches, see {@link FlightMedium}; this only decides how they stop the
 * camera. A shut medium therefore stays shut under {@link #OFF} as well.</p>
 */
public enum BorderMode {
    /**
     * {@code barrier}: a wall that only the camera player has, see
     * {@link CamBorderWall} - of {@code camera-mode.border-block}, and in
     * lava, water and powder snow of the block set for each. It is run into
     * like any block of the world, and nothing jerks back.
     */
    BARRIER,
    /**
     * {@code push-back}: the step over the border is cancelled, and the server
     * puts the player back where the step started - the way camera mode always
     * did it before there was a choice.
     */
    PUSH_BACK,
    /**
     * {@code false}: no border at distance or areas. Neither holds the camera
     * back in flight, and no portal asks about them either. Shut lava, water
     * and powder snow stop the step as under {@link #PUSH_BACK}.
     */
    OFF
}
