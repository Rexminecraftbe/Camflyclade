package de.elia.cameraplugin.session;

/**
 * Why camera mode ends, which decides the sound that goes with it: one of
 * {@code sounds.end}, {@code sounds.end-body} and {@code sounds.end-forced},
 * or none at all.
 */
public enum EndReason {
    /** The player ended it himself: {@code /cam}, or a click or hit on his own body. */
    COMMAND,
    /** Something happened to the body: hit, hurt, moved, drowning, suffocating, or a potion. */
    BODY,
    /** It was ended from outside: {@code /cam reload}, the time limit, a teleport. */
    FORCED,
    /** Death, leaving the server, the server shutting down: nobody would hear a sound. */
    SILENT
}
