package de.elia.cameraplugin.camfly2;

/**
 * A part of camera mode that has something to do when the plugin starts, when
 * {@code /cam reload} reads the config file anew, or when the plugin is
 * switched off.
 *
 * <p>The plugin keeps its parts in one list, in the order they are to do it,
 * see {@link CameraPlugin#onEnable}, and runs through it at each of these
 * times - so a part in that list is never forgotten at one of them. Each part
 * does only what it has to: every step does nothing unless the part says
 * otherwise.</p>
 *
 * <p>What has to come at a certain moment of its own - before camera mode ends
 * for everybody, after every part, the files last - the plugin does by
 * name.</p>
 */
public interface PluginPart {

    /**
     * Reads what the part keeps in files, or works out once from the config
     * file: at the start, after the config file was read, and again after
     * {@code /cam reload}, after {@link #onReload} of every part.
     */
    default void load() {
    }

    /**
     * Starts what runs while the plugin is on - a task of its own -, once,
     * after the listeners are registered. {@link #onDisable} stops it again.
     */
    default void start() {
    }

    /**
     * {@code /cam reload}, after camera mode ended for everybody and the
     * config file was read anew: forgets what held for the file as it was.
     * Each part's {@link #load} comes after this.
     */
    default void onReload() {
    }

    /**
     * The plugin is switched off, after camera mode ended for everybody: stops
     * what {@link #start} started, and takes back or saves what is left.
     */
    default void onDisable() {
    }
}
