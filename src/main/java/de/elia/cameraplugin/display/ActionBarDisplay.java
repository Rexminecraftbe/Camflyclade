package de.elia.cameraplugin.display;

/**
 * What the action bar shows while camera mode runs, the values of
 * {@code action-bar.display}.
 */
public enum ActionBarDisplay {
    /** {@code text}: the fixed line {@code actionbar-on}, as it always was. */
    TEXT,
    /**
     * {@code distance}: how far away the body is and which way it lies, after
     * {@code actionbar-on} has been shown for a moment at the start.
     */
    DISTANCE,
    /**
     * {@code false}: only {@code actionbar-on} at the start, for
     * {@code on-duration}, and then nothing. Left alone, the line fades the
     * way {@code actionbar-off} does.
     */
    START
}
