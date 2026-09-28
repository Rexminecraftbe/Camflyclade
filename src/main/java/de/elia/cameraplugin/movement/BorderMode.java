package de.elia.cameraplugin.movement;

/**
 * What happens where the camera is not allowed to go on: at
 * {@code camera-mode.max-distance} and at the areas {@code cam-area} keeps it
 * out of on level 2 - the values of {@code camera-mode.border-mode}.
 *
 * <p>Lava, water and powder snow are not part of this. They have switches of
 * their own and stop the step at their edge whatever is set here, see
 * {@link FlightMedium}.</p>
 */
public enum BorderMode {
    /**
     * {@code barrier}: a wall of {@code camera-mode.border-block} that only the
     * camera player has, see {@link CamBorderWall}. It is run into like any
     * block of the world, and nothing jerks back.
     */
    BARRIER,
    /**
     * {@code push-back}: the step over the border is cancelled, and the server
     * puts the player back where the step started - the way camera mode always
     * did it before there was a choice.
     */
    PUSH_BACK,
    /**
     * {@code false}: no border at all. Neither the distance nor the areas hold
     * the camera back in flight, and no portal asks about them either.
     */
    OFF
}
