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
    DISTANCE
}
