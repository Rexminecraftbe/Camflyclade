package de.elia.cameraplugin.trail;

/**
 * How long {@code /cam trail} shows the line of a flight, the values of
 * {@code cam-trail.show-mode}.
 */
public enum TrailShowMode {
    /**
     * {@code dynamic}: by how far the camera flew - {@code show-min-seconds}
     * up to {@code show-min-blocks}, {@code show-max-seconds} from
     * {@code show-max-blocks} on, and in between in proportion. With
     * {@code all} the flights count together. A flight still under way stays
     * {@code show-max-seconds}, as its line grows.
     */
    DYNAMIC,
    /** {@code fixed}: every line {@code show-seconds}, however far the camera flew. */
    FIXED,
    /**
     * {@code forever}: until the viewer hides it, with {@code /cam trail} or
     * the bucket in the menu, shows another or leaves the server.
     */
    FOREVER
}
