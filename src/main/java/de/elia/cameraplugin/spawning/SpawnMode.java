package de.elia.cameraplugin.spawning;

/**
 * What a player in camera mode counts as for the mobs that spawn by
 * themselves, the values of {@code mob-spawning}: body, camera and false. See
 * {@link CamSpawnFilter}.
 */
public enum SpawnMode {
    /**
     * {@code body}: he counts where his body stands, as if he stood there
     * himself. His camera no longer counts.
     */
    BODY,
    /** {@code camera}: as without the plugin, his camera counts like any player. */
    CAMERA,
    /** {@code false}: he does not count at all, neither his camera nor his body. */
    OFF
}
