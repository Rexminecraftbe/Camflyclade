package de.elia.cameraplugin.trail;

/**
 * How long {@code /cam trail} shows the line of a flight, the values of
 * {@code cam-trail.show-mode}.
 */
public enum TrailShowMode {
    /**
     * {@code dynamic}: by how far the camera flew, see {@link #seconds}. With
     * {@code all} the flights count together. A flight still under way stays
     * the longest, {@link #MAX_SECONDS}, as its line grows.
     */
    DYNAMIC,
    /** {@code fixed}: every line {@code show-seconds}, however far the camera flew. */
    FIXED,
    /**
     * {@code forever}: until the viewer hides it, with {@code /cam trail} or
     * the bucket in the menu, shows another or leaves the server.
     */
    FOREVER;

    /**
     * How long {@code dynamic} shows a line: up to {@code MIN_BLOCKS} of
     * flight {@code MIN_SECONDS}, from {@code MAX_BLOCKS} on
     * {@code MAX_SECONDS}, and in between in proportion - 300 blocks a
     * minute. Ten seconds see a short flight whole, five minutes are enough to
     * fly along a long one. The section {@code cam-trail} set the four as
     * {@code show-min-blocks}, {@code show-min-seconds},
     * {@code show-max-blocks} and {@code show-max-seconds} once.
     */
    static final int MIN_BLOCKS = 50;
    static final int MIN_SECONDS = 10;
    static final int MAX_BLOCKS = 1500;
    static final int MAX_SECONDS = 300;

    /** How long {@code dynamic} shows the lines of flights this far together, in seconds. */
    static int seconds(double blocks) {
        if (blocks <= MIN_BLOCKS) {
            return MIN_SECONDS;
        }
        if (blocks >= MAX_BLOCKS) {
            return MAX_SECONDS;
        }
        double share = (blocks - MIN_BLOCKS) / (MAX_BLOCKS - MIN_BLOCKS);
        return (int) Math.round(MIN_SECONDS + share * (MAX_SECONDS - MIN_SECONDS));
    }
}
